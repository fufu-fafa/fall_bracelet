# Fall-detection bracelet

Android companion app and ESP32-C3 firmware template for the fall-detection bracelet.
The bracelet detects a fall (or an SOS hold), buzzes a 20 s cancel window, then sends the
event over Bluetooth LE. The phone stores it, raises an alarm notification and can text the
caregivers by SMS.

- [`app/`](app) — Android app (Kotlin, Jetpack Compose, min SDK 26)
- [`firmware/`](firmware) — Arduino sketch for the XIAO ESP32-C3 ([build notes](firmware/README.md))
- [`PROTOCOL.md`](PROTOCOL.md) — the BLE contract between them

## The app

| Tab | |
|---|---|
| Home | Connection, state, battery; a red cancel card during a fall countdown; find / test buttons |
| Events | Every event with the caregiver message and whether the SMS went out |
| Live | Streams the 100 Hz accelerometer, plots SVM against the thresholds, records labelled CSV trials for tuning |
| Settings | Wearer name, cancel window and check-in time (written to the bracelet); caregiver numbers and SMS; out-of-range warning |

A foreground service keeps the BLE link up in the background and reconnects automatically.
Events are acknowledged only after they're stored, so nothing is lost if the phone is out
of range — the bracelet keeps them in flash and resends.

Build:

```bash
./gradlew :app:assembleDebug
```

The UI uses the dark palette from the project's web theme (`ui/theme/Theme.kt`).
