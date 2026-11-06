// Everything kept in NVS flash: the (setup) settings, the event queue and its sequence counter.
// Nothing personal is compiled into the firmware.
#pragma once
#include <stdint.h>

#include "ble_protocol.h"

struct Settings {
  uint8_t countdownS;
  uint8_t checkinHour;
  uint8_t checkinMinute;
  char wearerName[WEARER_NAME_BYTES + 1];
  int32_t lastCheckinDay;  // local day number of the last check-in, -1 = never
};

namespace storage {
void begin();

Settings& settings();
void saveSettings();

// Event queue, oldest first. Survives reboots and deep sleep until the phone acknowledges.
uint32_t push(EventType type, uint8_t batteryPct, uint16_t impactCg = 0, uint8_t alarms24h = 0,
              uint8_t cancelled24h = 0);
const EventRecord* front();
bool ack(uint32_t seq);  // removes the front event if seq matches
uint16_t count();
bool hasAlarm();         // a FALL or SOS is still waiting
}  // namespace storage
