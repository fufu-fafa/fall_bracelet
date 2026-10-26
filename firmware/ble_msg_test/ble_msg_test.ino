// BLE message test for the FallBracelet app on a plain ESP32 DevKit (no sensor, motor or button).
//
// Speaks the real protocol from PROTOCOL.md, so the Android app connects to it like a bracelet.
// You pick what it sends by typing commands on the USB serial port (115200 baud), or from a BLE
// terminal on the phone (e.g. Serial Bluetooth Terminal) through the Nordic UART service:
//
//   fall [g]    queue a FALL event (peak impact in g, default 3.2)
//   sos         queue an SOS event
//   lowbatt     queue a LOW_BATTERY event
//   checkin     queue a CHECKIN event
//   cancelled   queue a CANCELLED event
//   on | off    queue an ON / OFF event
//   test        queue a TEST event
//   state <monitoring|idle|countdown [s]|sending|off>   change STATUS.state and notify
//   batt <pct>  change the battery % and notify STATUS
//   info        print link, clock and queue state
//   help        print this list
//
// Events are delivered like the real firmware: indicated one at a time, resent every 10 s until
// the phone writes EVENT_ACK. "[ACK] seq N" means the app received and stored it. Everything
// printed goes to both USB serial and the BLE terminal.
//
// Build: arduino-cli compile --fqbn esp32:esp32:esp32 firmware/ble_msg_test
// Needs NimBLE-Arduino 2.x.

#include <NimBLEDevice.h>
#include <Preferences.h>
#include <esp_mac.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <stdarg.h>
#include <deque>

#include "ble_protocol.h"

// NimBLE host: tell bonded peers the GATT table changed, so they drop their cached copy.
extern "C" void ble_svc_gatt_changed(uint16_t start_handle, uint16_t end_handle);

#define NAME_PREFIX         "FallBracelet"
#define REQUIRE_BONDING     1      // same as the real firmware; the app expects a bonded link
#define ACK_TIMEOUT_MS      10000
#define STATUS_INTERVAL_MS  30000

// Nordic UART service, which BLE terminal apps understand. No encryption required, so a terminal
// app can use it without the FallBracelet app's pairing.
#define UUID_NUS     "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define UUID_NUS_RX  "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"  // terminal -> ESP32
#define UUID_NUS_TX  "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"  // ESP32 -> terminal
#define NUS_CHUNK    20  // fits the default MTU, whatever the terminal negotiates
#define NUS_LINE_MAX 96
#define GATT_LAYOUT  2  // bump when services change, so bonded phones refresh their cache once

#if REQUIRE_BONDING
constexpr uint32_t PROP_READ = NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC;
constexpr uint32_t PROP_WRITE = NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC;
#else
constexpr uint32_t PROP_READ = NIMBLE_PROPERTY::READ;
constexpr uint32_t PROP_WRITE = NIMBLE_PROPERTY::WRITE;
#endif

namespace {
NimBLECharacteristic* chStatus;
NimBLECharacteristic* chEvent;
NimBLECharacteristic* chConfig;
NimBLECharacteristic* chNusTx;
Preferences prefs;
QueueHandle_t nusLines;  // complete command lines from the terminal, BLE task -> loop()

// Written on the BLE task, handled in loop().
volatile int connCount = 0;  // the app and a terminal may use separate links
volatile bool isConnected = false;
volatile bool eventsSubscribed = false;
volatile bool nusSubscribed = false;
volatile bool gattNotifyPending = false;
uint32_t gattNotified;  // GATT_LAYOUT the bonded phone was last told about (NVS)
volatile bool ackPending = false;
volatile uint32_t ackedSeq = 0;
volatile bool timePending = false;
TimePacket timeRx;
volatile int commandRx = -1;

// loop() only.
std::deque<EventRecord> queue;
uint32_t nextSeq;
uint8_t state = (uint8_t)DevState::Monitoring;
uint8_t batteryPct = 87;
uint8_t countdownLeft = 0;
int64_t clockOffset = 0;  // epoch = millis()/1000 + clockOffset, once set
bool clockSet = false;
uint32_t inFlightSeq = 0, sentAt = 0, lastStatus = 0;
bool wasConnected = false, wasSubscribed = false, wasNusSubscribed = false;

uint32_t epochNow() { return clockSet ? (uint32_t)(millis() / 1000 + clockOffset) : 0; }

// printf to USB serial and, when a terminal is subscribed, to the BLE terminal. loop() only.
void out(const char* fmt, ...) {
  char buf[256];
  va_list ap;
  va_start(ap, fmt);
  int n = vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  if (n <= 0) return;
  n = min(n, (int)sizeof(buf) - 1);
  Serial.write((const uint8_t*)buf, n);
  if (!nusSubscribed) return;
  for (int i = 0; i < n; i += NUS_CHUNK) chNusTx->notify((const uint8_t*)buf + i, min(NUS_CHUNK, n - i));
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo&) override {
    isConnected = ++connCount > 0;
    if (connCount < CONFIG_BT_NIMBLE_MAX_CONNECTIONS) NimBLEDevice::startAdvertising();  // room for a terminal
  }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int reason) override {
    if (connCount > 0) connCount--;
    isConnected = connCount > 0;
    if (!isConnected) { eventsSubscribed = false; nusSubscribed = false; }
    NimBLEDevice::startAdvertising();
  }
  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    Serial.printf("[pairing] %s\n", info.isEncrypted() ? "encrypted link up" : "FAILED");
    // A phone bonded before the terminal service existed keeps using its cached service list
    // and never sees it. Service Changed makes it rediscover, but only once per GATT layout:
    // sent on every connection it leaves the FallBracelet app holding stale handles.
    if (info.isEncrypted() && gattNotified != GATT_LAYOUT) {
      ble_svc_gatt_changed(0x0001, 0xFFFF);
      gattNotified = GATT_LAYOUT;
      gattNotifyPending = true;  // persisted from loop(); NVS writes don't belong on the BLE task
    }
  }
};

class CharCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    NimBLEAttValue v = c->getValue();
    const NimBLEUUID& id = c->getUUID();
    if (id == NimBLEUUID(UUID_EVENT_ACK) && v.size() >= 4) {
      uint32_t seq;
      memcpy(&seq, v.data(), 4);
      ackedSeq = seq;
      ackPending = true;
    } else if (id == NimBLEUUID(UUID_TIME) && v.size() >= sizeof(TimePacket)) {
      memcpy(&timeRx, v.data(), sizeof(TimePacket));
      timePending = true;
    } else if (id == NimBLEUUID(UUID_COMMAND) && v.size() >= 1) {
      commandRx = v.data()[0];
    } else if (id == NimBLEUUID(UUID_CONFIG)) {
      Serial.printf("[RX] CONFIG written (%u bytes)\n", (unsigned)v.size());
    } else if (id == NimBLEUUID(UUID_NUS_RX)) {
      // Terminals may send a line in several writes, or several lines in one.
      static char line[NUS_LINE_MAX];
      static size_t len = 0;
      for (size_t i = 0; i < v.size(); i++) {
        char ch = (char)v.data()[i];
        if (ch == '\n' || ch == '\r') {
          if (len) { line[len] = 0; xQueueSend(nusLines, line, 0); len = 0; }
        } else if (len < NUS_LINE_MAX - 1) {
          line[len++] = ch;
        }
      }
    }
  }
  void onSubscribe(NimBLECharacteristic* c, NimBLEConnInfo&, uint16_t subValue) override {
    if (c == chEvent) eventsSubscribed = (subValue & 0x02) != 0;
    if (c == chNusTx) nusSubscribed = (subValue & 0x01) != 0;
  }
};

ServerCallbacks serverCallbacks;
CharCallbacks charCallbacks;

const char* typeName(uint8_t t) {
  static const char* names[] = {"?", "FALL", "SOS", "LOW_BATTERY", "OFF", "CHECKIN", "CANCELLED", "ON", "TEST"};
  return t <= 8 ? names[t] : "?";
}

void publishStatus() {
  StatusPacket s{};
  s.state = state;
  s.batteryPct = batteryPct;
  s.batteryMv = 3300 + batteryPct * 9;  // rough linear 3.3–4.2 V
  s.countdownRemaining = state == (uint8_t)DevState::Countdown ? countdownLeft : 0;
  s.flags = (clockSet ? STATUS_FLAG_CLOCK_SET : 0) | (batteryPct <= 20 ? STATUS_FLAG_LOW_BATT : 0);
  s.pendingEvents = queue.size();
  s.uptimeS = millis() / 1000;
  chStatus->setValue((const uint8_t*)&s, sizeof(s));
  if (isConnected) chStatus->notify();
  lastStatus = millis();
}

void queueEvent(EventType type, float impactG = 0) {
  EventRecord e{};
  e.seq = nextSeq++;
  prefs.putUInt("seq", nextSeq);  // monotonic across reboots; the app de-duplicates by seq
  e.type = (uint8_t)type;
  e.batteryPct = batteryPct;
  e.epoch = epochNow();
  e.impactCg = (uint16_t)(impactG * 100);
  if (type == EventType::Checkin) { e.alarms24h = 1; e.cancelled24h = 2; }
  queue.push_back(e);
  out("[queued] seq %lu %s%s\n", (unsigned long)e.seq, typeName(e.type),
                isConnected && eventsSubscribed ? "" : " (will send when the app connects)");
  publishStatus();
}

void deliver() {
  if (ackPending) {
    ackPending = false;
    if (!queue.empty() && queue.front().seq == ackedSeq) {
      out("[ACK] seq %lu %s received by the app\n", (unsigned long)ackedSeq, typeName(queue.front().type));
      queue.pop_front();
      inFlightSeq = 0;
      publishStatus();
    } else {
      out("[ACK] seq %lu (not the front event, ignored)\n", (unsigned long)ackedSeq);
    }
  }
  if (queue.empty() || !isConnected || !eventsSubscribed) { inFlightSeq = 0; return; }
  const EventRecord& f = queue.front();
  if (inFlightSeq == f.seq && millis() - sentAt < ACK_TIMEOUT_MS) return;
  out("[TX] %s seq %lu %s\n", inFlightSeq == f.seq ? "resend" : "indicate", (unsigned long)f.seq, typeName(f.type));
  chEvent->setValue((const uint8_t*)&f, sizeof(f));
  chEvent->indicate();
  inFlightSeq = f.seq;
  sentAt = millis();
}

void printHelp() {
  out("%s\n", "Events: fall [g] | sos | lowbatt | checkin | cancelled | on | off | test");
  out("%s\n", "Status: state <monitoring|idle|countdown [s]|sending|off> | batt <pct> | info | help");
}

// Lines this firmware prints, so an echo of them is not taken as a command.
bool isOwnOutput(const String& line) {
  static const char* prefixes[] = {"[", "unknown", "link ", "Events:", "Status:", "state:", NAME_PREFIX};
  for (const char* p : prefixes) if (line.startsWith(p)) return true;
  return false;
}

void handleCommand(String line, bool fromTerminal = false) {
  line.trim();
  if (line.isEmpty()) return;
  int sp = line.indexOf(' ');
  String cmd = sp < 0 ? line : line.substring(0, sp);
  String arg = sp < 0 ? String() : line.substring(sp + 1);
  cmd.toLowerCase();
  arg.trim();
  arg.toLowerCase();

  if (cmd == "fall") queueEvent(EventType::Fall, arg.isEmpty() ? 3.2f : arg.toFloat());
  else if (cmd == "sos") queueEvent(EventType::Sos);
  else if (cmd == "lowbatt") queueEvent(EventType::LowBattery);
  else if (cmd == "checkin") queueEvent(EventType::Checkin);
  else if (cmd == "cancelled") queueEvent(EventType::Cancelled, 2.8f);
  else if (cmd == "on") queueEvent(EventType::On);
  else if (cmd == "off") queueEvent(EventType::Off);
  else if (cmd == "test") queueEvent(EventType::Test);
  else if (cmd == "batt") { batteryPct = constrain(arg.toInt(), 0, 100); publishStatus(); out("[status] battery %u%%\n", batteryPct); }
  else if (cmd == "state") {
    if (arg.startsWith("mon")) state = (uint8_t)DevState::Monitoring;
    else if (arg.startsWith("idle")) state = (uint8_t)DevState::Idle;
    else if (arg.startsWith("count")) {
      state = (uint8_t)DevState::Countdown;
      int s = arg.indexOf(' ');
      countdownLeft = s < 0 ? 20 : arg.substring(s + 1).toInt();
    }
    else if (arg.startsWith("send")) state = (uint8_t)DevState::SendingAlert;
    else if (arg.startsWith("off")) state = (uint8_t)DevState::Off;
    else { out("%s\n", "state: monitoring | idle | countdown [s] | sending | off"); return; }
    publishStatus();
    out("[status] state %u\n", state);
  }
  else if (cmd == "info") {
    out("link %s, events %s, clock %s, %u queued, next seq %lu, battery %u%%\n",
                  isConnected ? "CONNECTED" : "advertising", eventsSubscribed ? "subscribed" : "not subscribed",
                  clockSet ? "set" : "not set", (unsigned)queue.size(), (unsigned long)nextSeq, batteryPct);
  }
  else if (cmd == "help") printHelp();
  // Never answer unknown input from the terminal, and never repeat unknown input back: if the
  // other end echoes what it receives (a BLE terminal, or a host tty with echo on), a reply would
  // come straight back as another unknown command and loop forever.
  else if (fromTerminal) Serial.printf("[terminal] ignored: %s\n", line.c_str());
  else if (!isOwnOutput(line)) { out("unknown command, type help\n"); }
}
}  // namespace

void setup() {
  Serial.begin(115200);
  delay(500);
  nusLines = xQueueCreate(8, NUS_LINE_MAX);
  prefs.begin("blemsgtest", false);
  nextSeq = prefs.getUInt("seq", 1);
  gattNotified = prefs.getUInt("gatt", 0);

  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_BT);
  char name[24];
  snprintf(name, sizeof(name), "%s-%02X%02X", NAME_PREFIX, mac[4], mac[5]);

  NimBLEDevice::init(name);
  NimBLEDevice::setMTU(185);
#if REQUIRE_BONDING
  NimBLEDevice::setSecurityAuth(true, false, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
#endif

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(&serverCallbacks);
  NimBLEService* svc = server->createService(UUID_SERVICE);
  chStatus = svc->createCharacteristic(UUID_STATUS, PROP_READ | NIMBLE_PROPERTY::NOTIFY);
  chEvent = svc->createCharacteristic(UUID_EVENT, PROP_READ | NIMBLE_PROPERTY::INDICATE);
  chConfig = svc->createCharacteristic(UUID_CONFIG, PROP_READ | PROP_WRITE);
  svc->createCharacteristic(UUID_STREAM, NIMBLE_PROPERTY::NOTIFY);
  NimBLECharacteristic* chAck = svc->createCharacteristic(UUID_EVENT_ACK, PROP_WRITE);
  NimBLECharacteristic* chTime = svc->createCharacteristic(UUID_TIME, PROP_WRITE);
  NimBLECharacteristic* chCommand = svc->createCharacteristic(UUID_COMMAND, PROP_WRITE);
  for (NimBLECharacteristic* c : {chEvent, chConfig, chAck, chTime, chCommand}) c->setCallbacks(&charCallbacks);

  NimBLEService* nus = server->createService(UUID_NUS);
  chNusTx = nus->createCharacteristic(UUID_NUS_TX, NIMBLE_PROPERTY::NOTIFY);
  NimBLECharacteristic* chNusRx = nus->createCharacteristic(
      UUID_NUS_RX, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  chNusTx->setCallbacks(&charCallbacks);
  chNusRx->setCallbacks(&charCallbacks);
  nus->start();

  ConfigPacket cfg{};
  cfg.countdownS = 20;
  cfg.checkinHour = 8;
  cfg.protocolVersion = PROTOCOL_VERSION;
  strncpy(cfg.wearerName, "Test DevKit", WEARER_NAME_BYTES);
  chConfig->setValue((const uint8_t*)&cfg, sizeof(cfg));
  svc->start();
  publishStatus();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  NimBLEAdvertisementData advData;
  advData.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  advData.addServiceUUID(NimBLEUUID(UUID_SERVICE));
  NimBLEAdvertisementData scanData;
  scanData.setName(name);
  adv->setAdvertisementData(advData);
  adv->setScanResponseData(scanData);
  adv->start();

  out("\n%s ready, advertising. Next event seq %lu.\n", name, (unsigned long)nextSeq);
  printHelp();
}

void loop() {
  static String buf;
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == '\n' || ch == '\r') { handleCommand(buf); buf = ""; }
    else buf += ch;
  }
  char nusLine[NUS_LINE_MAX];
  while (xQueueReceive(nusLines, nusLine, 0) == pdTRUE) {
    Serial.printf("[terminal] %s\n", nusLine);
    handleCommand(nusLine, true);
  }

  if (isConnected != wasConnected) {
    wasConnected = isConnected;
    out("%s\n", isConnected ? "[link] phone connected" : "[link] phone disconnected, advertising");
  }
  if (nusSubscribed != wasNusSubscribed) {
    wasNusSubscribed = nusSubscribed;
    if (nusSubscribed) { out("[link] BLE terminal attached\n"); printHelp(); }
  }
  if (eventsSubscribed != wasSubscribed) {
    wasSubscribed = eventsSubscribed;
    if (eventsSubscribed) out("%s\n", "[link] app subscribed to events, ready");
  }
  if (gattNotifyPending) {
    gattNotifyPending = false;
    prefs.putUInt("gatt", gattNotified);
  }
  if (timePending) {
    timePending = false;
    clockOffset = (int64_t)timeRx.epoch - millis() / 1000;
    clockSet = true;
    out("[RX] TIME %lu (UTC%+d min)\n", (unsigned long)timeRx.epoch, timeRx.utcOffsetMin);
    publishStatus();
  }
  if (commandRx >= 0) {
    int c = commandRx;
    commandRx = -1;
    out("[RX] COMMAND 0x%02X\n", c);
    if (c == CMD_TEST) queueEvent(EventType::Test);
  }

  deliver();
  if (millis() - lastStatus >= STATUS_INTERVAL_MS) publishStatus();
  delay(5);
}
