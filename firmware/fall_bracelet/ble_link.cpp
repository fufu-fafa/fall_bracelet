#include "ble_link.h"

#include <NimBLEDevice.h>
#include <esp_mac.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "config.h"
#include "storage.h"

namespace {
NimBLEServer* server = nullptr;
NimBLECharacteristic* chStatus = nullptr;
NimBLECharacteristic* chEvent = nullptr;
NimBLECharacteristic* chConfig = nullptr;
NimBLECharacteristic* chStream = nullptr;

QueueHandle_t inbox = nullptr;
volatile bool isConnected = false;
volatile bool eventsSubscribed = false;
volatile bool streamOn = false;
volatile uint32_t ackedSeq = 0;  // written on the BLE task, handled in deliver()
volatile bool ackPending = false;

// Delivery state, loop() only.
uint32_t inFlightSeq = 0;
uint32_t sentAt = 0;
uint8_t tries = 0;
bool failing = false;
uint16_t streamCounter = 0;

#if BLE_REQUIRE_BONDING
constexpr uint32_t READ = NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC;
constexpr uint32_t WRITE = NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC;
#else
constexpr uint32_t READ = NIMBLE_PROPERTY::READ;
constexpr uint32_t WRITE = NIMBLE_PROPERTY::WRITE;
#endif

void send(const BleMessage& m) { xQueueSend(inbox, &m, 0); }

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
    isConnected = true;
    // 30–60 ms interval, up to 4 s supervision timeout: responsive but easy on the battery.
    s->updateConnParams(info.getConnHandle(), 24, 48, 0, 400);
  }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
    isConnected = false;
    eventsSubscribed = false;
    streamOn = false;
    NimBLEDevice::startAdvertising();
  }
};

class WriteCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    NimBLEAttValue v = c->getValue();
    BleMessage m{};
    const NimBLEUUID& id = c->getUUID();
    if (id == NimBLEUUID(UUID_COMMAND) && v.size() >= 1) {
      m.kind = BleMessage::CommandMsg;
      m.command = v.data()[0];
      send(m);
    } else if (id == NimBLEUUID(UUID_TIME) && v.size() >= sizeof(TimePacket)) {
      m.kind = BleMessage::TimeMsg;
      memcpy(&m.time, v.data(), sizeof(TimePacket));
      send(m);
    } else if (id == NimBLEUUID(UUID_CONFIG) && v.size() >= sizeof(ConfigPacket)) {
      m.kind = BleMessage::ConfigMsg;
      memcpy(&m.config, v.data(), sizeof(ConfigPacket));
      send(m);
    } else if (id == NimBLEUUID(UUID_EVENT_ACK) && v.size() >= 4) {
      uint32_t seq;
      memcpy(&seq, v.data(), 4);
      ackedSeq = seq;
      ackPending = true;
    }
  }

  void onSubscribe(NimBLECharacteristic* c, NimBLEConnInfo&, uint16_t subValue) override {
    if (c == chEvent) eventsSubscribed = (subValue & 0x02) != 0;  // indications
  }
};

ServerCallbacks serverCallbacks;
WriteCallbacks writeCallbacks;
}  // namespace

namespace ble {

void begin() {
  inbox = xQueueCreate(8, sizeof(BleMessage));

  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_BT);
  char name[24];
  snprintf(name, sizeof(name), "%s-%02X%02X", BLE_NAME_PREFIX, mac[4], mac[5]);

  NimBLEDevice::init(name);
  NimBLEDevice::setPower(BLE_TX_POWER_DBM);
  NimBLEDevice::setMTU(185);
#if BLE_REQUIRE_BONDING
  // "Just works" pairing (no screen or keypad), bonded, LE Secure Connections.
  NimBLEDevice::setSecurityAuth(true, false, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
#endif

  server = NimBLEDevice::createServer();
  server->setCallbacks(&serverCallbacks);
  NimBLEService* svc = server->createService(UUID_SERVICE);

  chStatus = svc->createCharacteristic(UUID_STATUS, READ | NIMBLE_PROPERTY::NOTIFY);
  chEvent = svc->createCharacteristic(UUID_EVENT, READ | NIMBLE_PROPERTY::INDICATE);
  chConfig = svc->createCharacteristic(UUID_CONFIG, READ | WRITE);
  chStream = svc->createCharacteristic(UUID_STREAM, NIMBLE_PROPERTY::NOTIFY);
  NimBLECharacteristic* chAck = svc->createCharacteristic(UUID_EVENT_ACK, WRITE);
  NimBLECharacteristic* chTime = svc->createCharacteristic(UUID_TIME, WRITE);
  NimBLECharacteristic* chCommand = svc->createCharacteristic(UUID_COMMAND, WRITE);
  for (NimBLECharacteristic* c : {chEvent, chConfig, chAck, chTime, chCommand}) c->setCallbacks(&writeCallbacks);
  svc->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  NimBLEAdvertisementData advData;
  advData.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  advData.addServiceUUID(NimBLEUUID(UUID_SERVICE));
  NimBLEAdvertisementData scanData;  // the name doesn't fit next to a 128-bit UUID
  scanData.setName(name);
  adv->setAdvertisementData(advData);
  adv->setScanResponseData(scanData);
  // TODO: tune the advertising interval for battery life (e.g. 500–1000 ms when idle).
  adv->start();
}

bool connected() { return isConnected; }
bool streaming() { return streamOn && isConnected; }
void setStreaming(bool on) { streamOn = on && isConnected; }

bool poll(BleMessage& out) { return inbox && xQueueReceive(inbox, &out, 0) == pdTRUE; }

void publishStatus(const StatusPacket& s) {
  chStatus->setValue((const uint8_t*)&s, sizeof(s));
  if (isConnected) chStatus->notify();
}

void publishConfig(const ConfigPacket& c) { chConfig->setValue((const uint8_t*)&c, sizeof(c)); }

void streamSamples(const Sample* s, size_t n) {
  if (!streaming()) return;
  uint8_t pkt[2 + STREAM_SAMPLES_PER_PACKET * 6];
  for (size_t start = 0; start < n; start += STREAM_SAMPLES_PER_PACKET) {
    size_t k = min(n - start, (size_t)STREAM_SAMPLES_PER_PACKET);
    memcpy(pkt, &streamCounter, 2);
    streamCounter++;
    for (size_t i = 0; i < k; i++) {
      int16_t v[3] = {(int16_t)(s[start + i].x * 1000), (int16_t)(s[start + i].y * 1000),
                      (int16_t)(s[start + i].z * 1000)};
      memcpy(pkt + 2 + i * 6, v, 6);
    }
    chStream->notify(pkt, 2 + k * 6);
  }
}

void deliver(uint32_t now) {
  if (ackPending) {
    ackPending = false;
    const EventRecord* f = storage::front();
    if (f && f->seq == ackedSeq) {
      BleMessage m{};
      m.kind = BleMessage::AckedMsg;
      m.acked = *f;
      storage::ack(ackedSeq);
      send(m);
      inFlightSeq = 0;
      tries = 0;
      failing = false;
    }
  }

  const EventRecord* f = storage::front();
  if (!f) {
    failing = false;
    return;
  }
  if (!isConnected || !eventsSubscribed) {
    inFlightSeq = 0;  // send again from scratch on the next connection
    tries = 0;
    return;
  }

  bool due;
  if (inFlightSeq != f->seq) {
    due = true;
    tries = 0;
  } else if (tries <= ALERT_RETRIES) {
    due = now - sentAt >= ALERT_ACK_TIMEOUT_MS;
  } else {
    failing = true;
    due = now - sentAt >= ALERT_RETRY_MIN * 60000UL;
    if (due) tries = 0;
  }
  if (!due) return;

  chEvent->setValue((const uint8_t*)f, sizeof(EventRecord));
  chEvent->indicate();
  inFlightSeq = f->seq;
  sentAt = now;
  tries++;
}

bool deliveryFailing() { return failing; }

}  // namespace ble
