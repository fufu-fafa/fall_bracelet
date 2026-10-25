# Bracelet firmware (template)

Arduino sketch for the v3 board: Seeed XIAO ESP32-C3, MPU6050, vibration motor, SW2 button,
400 mAh LiPo. It implements the spec's five states, the three-phase detector, the button and
buzz patterns, the battery rules, and **alerts over BLE to the Android app** (spec option B).
The wire format is in [`../PROTOCOL.md`](../PROTOCOL.md).

| File | What it does |
|---|---|
| `config.h` | Every pin and number from the spec's settings table |
| `ble_protocol.h` | UUIDs and packet layouts shared with the app |
| `fall_bracelet.ino` | State machine, sleep, countdown, alerts, daily check-in |
| `detector.*` | Free fall → impact → stillness, plus the orientation / strong-impact check |
| `sensor.*` | MPU6050 at 100 Hz into its FIFO (accelerometer only, gyro in standby), motion-wake |
| `button.*` | Short press, 2–4 s SOS, 5 s off, with the 2 s tick |
| `motor.*` | Non-blocking vibration patterns |
| `battery.*` | VBAT on D0, 16-sample average, 3.3–4.2 V → % |
| `storage.*` | NVS: setup values and the event queue (survives reboots until the phone ACKs) |
| `ble_link.*` | NimBLE peripheral, event delivery with retries |

## Build

1. Arduino IDE 2 (or `arduino-cli`), board package **esp32 by Espressif 3.x**, board
   **XIAO_ESP32C3**.
2. Libraries: **NimBLE-Arduino 2.x** (h2zero) and **Adafruit MPU6050** (pulls in Adafruit
   Unified Sensor and BusIO).
3. Open `fall_bracelet/fall_bracelet.ino`, check the pins at the top of `config.h` against the
   schematic, upload.

```bash
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C3 firmware/fall_bracelet
```

## Before trusting it

- **Pins are placeholders.** SW2 and the MPU6050 INT line must sit on GPIO0–5 to wake the
  chip from deep sleep, and SW2 needs a pull-up that holds during deep sleep.
- **Thresholds are untuned.** Use the app's Live tab to stream and record labelled trials
  (spec test plan, step 1), then replay them through `detector.cpp` on a laptop.
- **Power.** The loop currently idles with `delay()`. Reaching the spec's 1–2 mA in
  Monitoring needs light sleep between FIFO batches with BLE modem sleep
  (`CONFIG_PM_ENABLE`, `CONFIG_BT_CTRL_MODEM_SLEEP`), which the stock Arduino core doesn't
  enable. Measure the board before quoting battery life. Remove the GY-521's power LED.
- **Idle means no BLE.** In Idle and Off the chip is in deep sleep, so the phone loses the
  link. The bracelet sends its state first and the app doesn't raise an out-of-range warning
  for those two states.

## BLE message test (`ble_msg_test/`)

A stand-in bracelet for a plain ESP32 DevKit: same BLE protocol, no sensor, so the app can be
tested by hand. Type a command on its serial port and that event goes to the app (`fall`,
`sos`, `checkin`, `lowbatt`, `cancelled`, `on`, `off`, `test`, plus `batt <pct>`,
`state <…>`, `info`). The ESP32 prints `[ACK] seq N` once the app has stored the event.

On the Pi (DevKit on `/dev/ttyUSB0`):

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 ble_msg_test
arduino-cli upload --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 ble_msg_test
python3 ble_msg_test/bt_cmd.py fall          # or: picocom -b 115200 --omap crlf /dev/ttyUSB0
```

`ble_protocol.h` in that folder is a copy; keep it in sync with `fall_bracelet/`.
