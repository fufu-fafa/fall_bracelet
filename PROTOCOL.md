# Bracelet ↔ phone BLE protocol

This is the contract between the ESP32-C3 firmware (`firmware/fall_bracelet/ble_protocol.h`)
and the Android app (`app/src/main/java/com/fallbracelet/ble/BleProtocol.kt`). Change both
sides together and bump `PROTOCOL_VERSION`.

The spec leaves the alert channel open; this implements **option B — BLE → phone companion
app**. The bracelet detects and queues events; the phone stores them, raises a notification,
optionally texts the caregivers, and then acknowledges each event so the bracelet can drop it
from flash.

All integers are **little-endian**. The device advertises as `FallBracelet-XXXX` (last two
bytes of the MAC) with the service UUID in the advertisement.

## Service `7a1f0001-6b2c-4e3d-9f10-a1b2c3d4e5f6`

| Char | UUID suffix | Props | Size | Purpose |
|---|---|---|---|---|
| STATUS | `…0002…` | read, notify | 12 B | Current state and battery, pushed on change and every 30 s |
| EVENT | `…0003…` | read, indicate | 16 B | Queued events, sent oldest first, one at a time |
| EVENT_ACK | `…0004…` | write | 4 B | Phone confirms an event `seq`; bracelet deletes it and sends the next |
| TIME | `…0005…` | write | 6 B | Phone sets the clock (replaces NTP) |
| CONFIG | `…0006…` | read, write | 24 B | The *setup* values from `config.h` |
| COMMAND | `…0007…` | write | 1 B | One-byte commands |
| STREAM | `…0008…` | notify | 2 + 6·n B | Raw accelerometer for logging/tuning (off by default) |

Full UUIDs are `7a1f000X-6b2c-4e3d-9f10-a1b2c3d4e5f6`.

### STATUS (12 bytes)

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | state: 0 OFF, 1 MONITORING, 2 IDLE, 3 COUNTDOWN, 4 SENDING_ALERT |
| 1 | u8 | battery % (0–100) |
| 2 | u16 | battery mV |
| 4 | u8 | countdown seconds remaining (0 unless COUNTDOWN) |
| 5 | u8 | flags: bit0 clock set, bit1 streaming, bit2 low battery |
| 6 | u16 | events waiting in the flash queue |
| 8 | u32 | uptime, seconds |

### EVENT (16 bytes)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | seq — monotonic, survives reboots (stored in NVS) |
| 4 | u8 | type: 1 FALL, 2 SOS, 3 LOW_BATTERY, 4 OFF, 5 CHECKIN, 6 CANCELLED, 7 ON, 8 TEST |
| 5 | u8 | battery % when it happened |
| 6 | u32 | Unix time when it happened (0 = clock was not set; phone uses receive time) |
| 10 | u16 | peak impact, centi-g (FALL / CANCELLED), else 0 |
| 12 | u8 | alarms in the last 24 h (CHECKIN) |
| 13 | u8 | cancelled countdowns in the last 24 h (CHECKIN) |
| 14 | u16 | reserved, 0 |

Delivery: after the phone subscribes to EVENT, the bracelet indicates the oldest queued event
and waits for EVENT_ACK with the same `seq`. With no ACK after 10 s it resends, up to
`ALERT_RETRIES` times; after that it buzzes the "failed" pattern each minute and retries
every `ALERT_RETRY_MIN` minutes or on the next connection. The phone de-duplicates by `seq`,
so a resent event never alerts twice.

### TIME (6 bytes)

`u32` Unix time, `i16` local UTC offset in minutes. Written by the phone on every connection,
before it subscribes to EVENT.

### CONFIG (24 bytes)

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | `COUNTDOWN_S` (5–60) |
| 1 | u8 | `CHECKIN_TIME` hour (0–23) |
| 2 | u8 | `CHECKIN_TIME` minute (0–59) |
| 3 | u8 | protocol version (read-only, currently 1) |
| 4 | 20 B | `WEARER_NAME`, UTF-8, zero-padded |

### COMMAND (1 byte)

| Value | Command |
|---|---|
| 0x01 | FIND — buzz 2 long so the bracelet can be found |
| 0x02 | CANCEL — cancel a running fall countdown (same as a button press) |
| 0x03 | STREAM_ON — start raw accelerometer notifications |
| 0x04 | STREAM_OFF |
| 0x05 | TEST — queue a TEST event (exercises the full alert path) |
| 0x06 | POWER_OFF — soft off (sends an OFF event first) |

### STREAM

`u16` packet counter, then up to 9 samples of `i16 ax, ay, az` in milli-g at 100 Hz.
The phone requests a 185-byte MTU so a full packet fits.

## Security

`BLE_REQUIRE_BONDING` in `config.h` (default on) makes every characteristic require an
encrypted, bonded link. Android shows the system pairing prompt on first connection.
