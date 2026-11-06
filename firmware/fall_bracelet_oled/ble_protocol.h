// Wire format shared with the Android app (app/.../ble/BleProtocol.kt). See PROTOCOL.md.
// All multi-byte fields are little-endian, which is the ESP32's native order.
#pragma once
#include <stdint.h>

#define PROTOCOL_VERSION 1

#define UUID_SERVICE   "7a1f0001-6b2c-4e3d-9f10-a1b2c3d4e5f6"
#define UUID_STATUS    "7a1f0002-6b2c-4e3d-9f10-a1b2c3d4e5f6"
#define UUID_EVENT     "7a1f0003-6b2c-4e3d-9f10-a1b2c3d4e5f6"
#define UUID_EVENT_ACK "7a1f0004-6b2c-4e3d-9f10-a1b2c3d4e5f6"
#define UUID_TIME      "7a1f0005-6b2c-4e3d-9f10-a1b2c3d4e5f6"
#define UUID_CONFIG    "7a1f0006-6b2c-4e3d-9f10-a1b2c3d4e5f6"
#define UUID_COMMAND   "7a1f0007-6b2c-4e3d-9f10-a1b2c3d4e5f6"
#define UUID_STREAM    "7a1f0008-6b2c-4e3d-9f10-a1b2c3d4e5f6"

enum class DevState : uint8_t { Off = 0, Monitoring = 1, Idle = 2, Countdown = 3, SendingAlert = 4 };

enum class EventType : uint8_t {
  Fall = 1, Sos = 2, LowBattery = 3, Off = 4, Checkin = 5, Cancelled = 6, On = 7, Test = 8,
};

enum Command : uint8_t {
  CMD_FIND = 0x01, CMD_CANCEL = 0x02, CMD_STREAM_ON = 0x03, CMD_STREAM_OFF = 0x04,
  CMD_TEST = 0x05, CMD_POWER_OFF = 0x06,
};

#define STATUS_FLAG_CLOCK_SET  0x01
#define STATUS_FLAG_STREAMING  0x02
#define STATUS_FLAG_LOW_BATT   0x04

struct __attribute__((packed)) StatusPacket {
  uint8_t state;
  uint8_t batteryPct;
  uint16_t batteryMv;
  uint8_t countdownRemaining;
  uint8_t flags;
  uint16_t pendingEvents;
  uint32_t uptimeS;
};
static_assert(sizeof(StatusPacket) == 12, "STATUS is 12 bytes");

struct __attribute__((packed)) EventRecord {
  uint32_t seq;
  uint8_t type;
  uint8_t batteryPct;
  uint32_t epoch;      // 0 when the clock wasn't set
  uint16_t impactCg;   // centi-g
  uint8_t alarms24h;
  uint8_t cancelled24h;
  uint16_t reserved;
};
static_assert(sizeof(EventRecord) == 16, "EVENT is 16 bytes");

struct __attribute__((packed)) TimePacket {
  uint32_t epoch;
  int16_t utcOffsetMin;
};
static_assert(sizeof(TimePacket) == 6, "TIME is 6 bytes");

#define WEARER_NAME_BYTES 20
struct __attribute__((packed)) ConfigPacket {
  uint8_t countdownS;
  uint8_t checkinHour;
  uint8_t checkinMinute;
  uint8_t protocolVersion;
  char wearerName[WEARER_NAME_BYTES];  // UTF-8, zero-padded, not necessarily terminated
};
static_assert(sizeof(ConfigPacket) == 24, "CONFIG is 24 bytes");

#define STREAM_SAMPLES_PER_PACKET 9  // 2 + 9 × 6 = 56 bytes, needs MTU ≥ 59
