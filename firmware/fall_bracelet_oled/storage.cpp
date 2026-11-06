#include "storage.h"

#include <Preferences.h>
#include <string.h>
#include <time.h>

#include "config.h"

namespace {
Preferences prefs;
Settings current;
EventRecord queue[EVENT_QUEUE_MAX];
uint16_t queued = 0;
uint32_t nextSeq = 1;

void saveQueue() {
  if (queued) prefs.putBytes("queue", queue, queued * sizeof(EventRecord));
  else prefs.remove("queue");
  prefs.putUShort("queued", queued);
}

bool isAlarm(const EventRecord& e) {
  return e.type == (uint8_t)EventType::Fall || e.type == (uint8_t)EventType::Sos;
}

void dropOne() {
  // Full: drop the oldest event that isn't an alarm, or the oldest alarm if they all are.
  uint16_t victim = 0;
  for (uint16_t i = 0; i < queued; i++) {
    if (!isAlarm(queue[i])) {
      victim = i;
      break;
    }
  }
  memmove(&queue[victim], &queue[victim + 1], (queued - victim - 1) * sizeof(EventRecord));
  queued--;
}
}  // namespace

namespace storage {

void begin() {
  prefs.begin("bracelet", false);
  current.countdownS = prefs.getUChar("countdown", COUNTDOWN_S_DEFAULT);
  current.checkinHour = prefs.getUChar("ckHour", CHECKIN_HOUR_DEFAULT);
  current.checkinMinute = prefs.getUChar("ckMin", CHECKIN_MIN_DEFAULT);
  current.lastCheckinDay = prefs.getInt("ckDay", -1);
  memset(current.wearerName, 0, sizeof(current.wearerName));
  prefs.getString("name", current.wearerName, sizeof(current.wearerName));

  nextSeq = prefs.getUInt("seq", 1);
  queued = prefs.getUShort("queued", 0);
  if (queued > EVENT_QUEUE_MAX) queued = 0;
  if (queued) prefs.getBytes("queue", queue, queued * sizeof(EventRecord));
}

Settings& settings() { return current; }

void saveSettings() {
  prefs.putUChar("countdown", current.countdownS);
  prefs.putUChar("ckHour", current.checkinHour);
  prefs.putUChar("ckMin", current.checkinMinute);
  prefs.putInt("ckDay", current.lastCheckinDay);
  prefs.putString("name", current.wearerName);
}

uint32_t push(EventType type, uint8_t batteryPct, uint16_t impactCg, uint8_t alarms24h,
              uint8_t cancelled24h) {
  if (queued == EVENT_QUEUE_MAX) dropOne();
  time_t now = time(nullptr);
  EventRecord& e = queue[queued++];
  e.seq = nextSeq++;
  e.type = (uint8_t)type;
  e.batteryPct = batteryPct;
  e.epoch = now > 1600000000 ? (uint32_t)now : 0;  // the clock is only valid once the phone set it
  e.impactCg = impactCg;
  e.alarms24h = alarms24h;
  e.cancelled24h = cancelled24h;
  e.reserved = 0;
  prefs.putUInt("seq", nextSeq);
  saveQueue();
  return e.seq;
}

const EventRecord* front() { return queued ? &queue[0] : nullptr; }

bool ack(uint32_t seq) {
  if (!queued || queue[0].seq != seq) return false;
  memmove(&queue[0], &queue[1], (queued - 1) * sizeof(EventRecord));
  queued--;
  saveQueue();
  return true;
}

uint16_t count() { return queued; }

bool hasAlarm() {
  for (uint16_t i = 0; i < queued; i++)
    if (isAlarm(queue[i])) return true;
  return false;
}

}  // namespace storage
