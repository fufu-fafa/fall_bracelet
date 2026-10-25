// Fall-detection bracelet firmware — template.
// Board: Seeed XIAO ESP32-C3 · Sensor: MPU6050 on I2C (D4/D5) · Alerts: BLE to the phone app.
//
// Main loop: read the accelerometer FIFO -> run the three-phase detector -> handle the button
// -> drive the motor -> deliver queued events over BLE -> sleep when nothing is happening.
// Every tunable number is in config.h. The BLE wire format is in ble_protocol.h / PROTOCOL.md.

#include <Arduino.h>
#include <driver/gpio.h>
#include <esp_sleep.h>
#include <math.h>
#include <sys/time.h>
#include <time.h>

#include "battery.h"
#include "ble_link.h"
#include "ble_protocol.h"
#include "button.h"
#include "config.h"
#include "detector.h"
#include "motor.h"
#include "sensor.h"
#include "storage.h"

// ---- Kept across deep sleep ----------------------------------------------------------------
enum SleepReason : uint8_t { SLEPT_NONE = 0, SLEPT_OFF = 1, SLEPT_IDLE = 2 };
RTC_DATA_ATTR uint8_t sleepReason = SLEPT_NONE;
RTC_DATA_ATTR int16_t utcOffsetMin = 0;
RTC_DATA_ATTR uint8_t alarms24h = 0;
RTC_DATA_ATTR uint8_t cancelled24h = 0;
RTC_DATA_ATTR bool lowBattSent = false;

// ---- Runtime state -------------------------------------------------------------------------
DevState state = DevState::Monitoring;
FallDetector detector;
Sample batch[SAMPLE_HZ * 2];  // the FIFO holds ~1.7 s
bool sensorOk = false;

uint16_t battMv = 0;
uint8_t battPct = 0;
float countdownPeak = 0;
uint32_t countdownEnd = 0;
uint8_t lastCountdownShown = 0;
bool pressConsumed = false;  // the press that cancelled a countdown shouldn't also do something else
bool statusDirty = true;

uint32_t lastFifoRead = 0, lastMotion = 0, lastStatus = 0, lastBattCheck = 0;
uint32_t lastCheckinCheck = 0, lastFailedBuzz = 0, lastLowBuzz = 0, alarmQueuedAt = 0;

// ---- Helpers -------------------------------------------------------------------------------

bool clockSet() { return time(nullptr) > 1600000000; }

uint8_t batteryQuarters() { return battPct > 75 ? 4 : battPct > 50 ? 3 : battPct > 25 ? 2 : 1; }

void setState(DevState s) {
  if (state == s) return;
  state = s;
  statusDirty = true;
}

void publishStatus(uint32_t now) {
  StatusPacket s{};
  s.state = (uint8_t)state;
  s.batteryPct = battPct;
  s.batteryMv = battMv;
  s.countdownRemaining = state == DevState::Countdown ? (countdownEnd - now + 999) / 1000 : 0;
  s.flags = (clockSet() ? STATUS_FLAG_CLOCK_SET : 0) | (ble::streaming() ? STATUS_FLAG_STREAMING : 0) |
            (lowBattSent ? STATUS_FLAG_LOW_BATT : 0);
  s.pendingEvents = storage::count();
  s.uptimeS = now / 1000;
  ble::publishStatus(s);
  lastStatus = now;
  statusDirty = false;
}

void publishConfig() {
  const Settings& st = storage::settings();
  ConfigPacket c{};
  c.countdownS = st.countdownS;
  c.checkinHour = st.checkinHour;
  c.checkinMinute = st.checkinMinute;
  c.protocolVersion = PROTOCOL_VERSION;
  memcpy(c.wearerName, st.wearerName, WEARER_NAME_BYTES);
  ble::publishConfig(c);
}

void raiseAlarm(EventType type, float impactG, uint32_t now) {
  storage::push(type, battPct, (uint16_t)(impactG * 100));
  if (alarms24h < 255) alarms24h++;
  alarmQueuedAt = now;
  lastFailedBuzz = now;
  setState(DevState::SendingAlert);
}

// Deep sleep until the button (and, when idle, movement) wakes the chip. setup() runs again.
[[noreturn]] void deepSleep(SleepReason reason) {
  sleepReason = reason;
  motor::stop();
  while (button::isDown()) delay(10);  // otherwise the held button wakes it straight away
  delay(50);

  // TODO: SW2 needs a pull-up that holds in deep sleep; an external 100 kΩ is the safe choice.
  gpio_pullup_en((gpio_num_t)PIN_BUTTON);
  esp_deep_sleep_enable_gpio_wakeup(1ULL << PIN_BUTTON, ESP_GPIO_WAKEUP_GPIO_LOW);
  if (reason == SLEPT_IDLE) esp_deep_sleep_enable_gpio_wakeup(1ULL << PIN_MPU_INT, ESP_GPIO_WAKEUP_GPIO_HIGH);
  esp_deep_sleep_start();
}

void powerOff(bool pushOffEvent) {
  uint32_t now = millis();
  if (pushOffEvent) storage::push(EventType::Off, battPct);
  motor::stop();
  motor::play(Pattern::TurningOff);
  setState(DevState::Off);
  publishStatus(now);

  // Give the phone a few seconds to receive the OFF message before the radio goes down.
  uint32_t t0 = millis();
  while (millis() - t0 < 10000 && (motor::busy() || (ble::connected() && storage::count() > 0))) {
    ble::deliver(millis());
    BleMessage m;
    while (ble::poll(m)) {}
    motor::update(millis());
    delay(10);
  }
  if (sensorOk) sensor::sleep();
  deepSleep(SLEPT_OFF);
}

void enterIdle(uint32_t now) {
  setState(DevState::Idle);
  publishStatus(now);  // the app shows "Idle" rather than warning about a lost connection
  delay(300);
  sensor::armMotionWake();
  deepSleep(SLEPT_IDLE);
}

// ---- Countdown -----------------------------------------------------------------------------

void startCountdown(float peakG, uint32_t now) {
  uint8_t secs = storage::settings().countdownS;
  countdownPeak = peakG;
  countdownEnd = now + secs * 1000UL;
  lastCountdownShown = secs;
  motor::play(Pattern::Countdown, secs);
  setState(DevState::Countdown);
}

void cancelCountdown(uint32_t now) {
  motor::stop();
  motor::play(Pattern::Cancelled);
  if (cancelled24h < 255) cancelled24h++;
  // Logged so the app (and the daily check-in) show how often the thresholds trigger.
  storage::push(EventType::Cancelled, battPct, (uint16_t)(countdownPeak * 100));
  setState(storage::hasAlarm() ? DevState::SendingAlert : DevState::Monitoring);
}

void updateCountdown(uint32_t now) {
  if ((int32_t)(now - countdownEnd) >= 0) {
    raiseAlarm(EventType::Fall, countdownPeak, now);
    return;
  }
  uint8_t left = (countdownEnd - now + 999) / 1000;
  if (left != lastCountdownShown) {
    lastCountdownShown = left;
    statusDirty = true;
  }
}

// ---- Inputs --------------------------------------------------------------------------------

void handleButton(uint32_t now) {
  ButtonEvent e = button::update(now);
  if (e == ButtonEvent::None) return;
  if (e == ButtonEvent::Down) {
    pressConsumed = false;
    if (state == DevState::Countdown) {
      cancelCountdown(now);
      pressConsumed = true;
    }
    return;
  }
  if (pressConsumed) return;

  switch (e) {
    case ButtonEvent::Short:
      motor::status(batteryQuarters());
      break;
    case ButtonEvent::SosTick:
      motor::play(Pattern::Tick);
      break;
    case ButtonEvent::Sos:
      raiseAlarm(EventType::Sos, 0, now);
      motor::play(Pattern::SosAccepted);
      break;
    case ButtonEvent::Off:
      powerOff(true);
      break;
    default:
      break;
  }
}

void handleBle(uint32_t now) {
  BleMessage m;
  while (ble::poll(m)) {
    switch (m.kind) {
      case BleMessage::CommandMsg:
        switch (m.command) {
          case CMD_FIND: motor::play(Pattern::Find); break;
          case CMD_CANCEL:
            if (state == DevState::Countdown) cancelCountdown(now);
            break;
          case CMD_STREAM_ON: ble::setStreaming(true); statusDirty = true; break;
          case CMD_STREAM_OFF: ble::setStreaming(false); statusDirty = true; break;
          case CMD_TEST: storage::push(EventType::Test, battPct); break;
          case CMD_POWER_OFF: powerOff(true); break;
        }
        break;

      case BleMessage::TimeMsg: {
        timeval tv{(time_t)m.time.epoch, 0};
        settimeofday(&tv, nullptr);
        utcOffsetMin = m.time.utcOffsetMin;
        statusDirty = true;
        break;
      }

      case BleMessage::ConfigMsg: {
        Settings& st = storage::settings();
        st.countdownS = constrain(m.config.countdownS, 5, 60);
        st.checkinHour = constrain(m.config.checkinHour, 0, 23);
        st.checkinMinute = constrain(m.config.checkinMinute, 0, 59);
        memset(st.wearerName, 0, sizeof(st.wearerName));
        memcpy(st.wearerName, m.config.wearerName, WEARER_NAME_BYTES);
        storage::saveSettings();
        publishConfig();
        break;
      }

      case BleMessage::AckedMsg:
        if (m.acked.type == (uint8_t)EventType::Fall || m.acked.type == (uint8_t)EventType::Sos) {
          motor::play(Pattern::Sent);
        }
        statusDirty = true;
        break;
    }
  }
}

void readSensor(uint32_t now) {
  size_t n = sensor::read(batch, sizeof(batch) / sizeof(batch[0]));
  ble::streamSamples(batch, n);
  for (size_t i = 0; i < n; i++) {
    bool fall = detector.push(batch[i]);
    if (fabsf(detector.lastSvm() - 1.0f) > MOTION_WAKE_G) lastMotion = now;
    if (fall && state != DevState::Countdown && state != DevState::Off) {
      startCountdown(detector.peakG(), now);
    }
  }
}

// ---- Periodic work -------------------------------------------------------------------------

void checkBattery(uint32_t now) {
  battMv = battery::readMv();
  battPct = battery::percent(battMv);
  lastBattCheck = now;
  statusDirty = true;

  if (battPct <= CRIT_BATT_PCT) {
    storage::push(EventType::LowBattery, battPct);
    storage::push(EventType::Off, battPct);
    powerOff(false);
  }
  if (battPct <= LOW_BATT_PCT && !lowBattSent) {
    storage::push(EventType::LowBattery, battPct);
    lowBattSent = true;
    lastLowBuzz = now;
    motor::play(Pattern::BatteryLow);
  } else if (battPct > LOW_BATT_PCT + 5) {
    lowBattSent = false;  // charged
  }
}

void checkDailyCheckin() {
  if (!clockSet()) return;
  time_t local = time(nullptr) + utcOffsetMin * 60;
  int32_t day = local / 86400;
  int minuteOfDay = (local % 86400) / 60;
  Settings& st = storage::settings();
  if (st.lastCheckinDay < 0) {  // first time the clock is known: start counting from tomorrow
    st.lastCheckinDay = day;
    storage::saveSettings();
    return;
  }
  if (day == st.lastCheckinDay || minuteOfDay < st.checkinHour * 60 + st.checkinMinute) return;
  storage::push(EventType::Checkin, battPct, 0, alarms24h, cancelled24h);
  alarms24h = cancelled24h = 0;
  st.lastCheckinDay = day;
  storage::saveSettings();
}

void updateAlerts(uint32_t now) {
  bool alarmWaiting = storage::hasAlarm();
  if (state == DevState::SendingAlert && !alarmWaiting) setState(DevState::Monitoring);
  if (alarmWaiting && now - alarmQueuedAt > ALERT_FAILED_AFTER_MS && now - lastFailedBuzz >= 60000 &&
      !motor::busy() && state != DevState::Countdown) {
    motor::play(Pattern::Failed);
    lastFailedBuzz = now;
  }
  if (lowBattSent && now - lastLowBuzz >= 3600000UL && !motor::busy()) {
    motor::play(Pattern::BatteryLow);
    lastLowBuzz = now;
  }
}

// ---- Arduino entry points ------------------------------------------------------------------

void setup() {
  Serial.begin(115200);
  motor::begin();
  storage::begin();
  pinMode(PIN_BUTTON, INPUT_PULLUP);

  esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();
  bool wokeByPin = cause == ESP_SLEEP_WAKEUP_GPIO;
  bool fromOff = wokeByPin && sleepReason == SLEPT_OFF;
  bool fromIdle = wokeByPin && sleepReason == SLEPT_IDLE;

  if (fromOff) {
    // Off: only a 3 s hold turns it on. Anything shorter goes straight back to sleep.
    uint32_t t0 = millis();
    while (button::isDown() && millis() - t0 < ON_HOLD_MS) delay(10);
    if (millis() - t0 < ON_HOLD_MS) deepSleep(SLEPT_OFF);
  }

  battMv = battery::readMv();
  battPct = battery::percent(battMv);
  if (battPct <= CRIT_BATT_PCT && !fromIdle) {
    // Too flat to run. TODO: this also blocks start-up on a charger until the cell recovers.
    motor::play(Pattern::TurningOff);
    while (motor::busy()) { motor::update(millis()); delay(10); }
    deepSleep(SLEPT_OFF);
  }

  sensorOk = sensor::begin();
  if (!sensorOk) Serial.println("MPU6050 not found — check wiring on D4/D5");
  ble::begin();
  button::begin(/*ignoreUntilReleased=*/true);
  publishConfig();

  if (!fromIdle) {
    storage::push(EventType::On, battPct);
    motor::play(Pattern::TurnedOn);
  }
  sleepReason = SLEPT_NONE;

  uint32_t now = millis();
  lastMotion = lastBattCheck = lastFifoRead = now;
  if (storage::hasAlarm()) {  // an alert from before a reboot is still waiting
    alarmQueuedAt = now;
    setState(DevState::SendingAlert);
  }
  publishStatus(now);
}

void loop() {
  uint32_t now = millis();

  handleButton(now);
  handleBle(now);

  if (sensorOk && now - lastFifoRead >= FIFO_READ_MS) {
    lastFifoRead = now;
    readSensor(now);
  }
  if (state == DevState::Countdown) updateCountdown(now);

  ble::deliver(now);
  updateAlerts(now);

  if (now - lastBattCheck >= BATT_CHECK_MIN * 60000UL) checkBattery(now);
  if (now - lastCheckinCheck >= 30000) {
    lastCheckinCheck = now;
    checkDailyCheckin();
  }
  if (statusDirty || now - lastStatus >= STATUS_INTERVAL_MS) publishStatus(now);

  if (state == DevState::Monitoring && now - lastMotion >= IDLE_TIMEOUT_MIN * 60000UL &&
      storage::count() == 0 && !ble::streaming() && !motor::busy()) {
    enterIdle(now);
  }

  motor::update(now);
  // TODO(power): the spec's 1–2 mA budget needs light sleep between FIFO batches. With BLE
  // active that requires CONFIG_PM_ENABLE + BT modem sleep (ESP-IDF build or a custom Arduino
  // sdkconfig). Until then this delay only lets FreeRTOS idle; expect tens of mA.
  delay(10);
}
