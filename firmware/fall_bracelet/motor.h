// Vibration patterns, played without blocking the main loop.
#pragma once
#include <stdint.h>

enum class Pattern : uint8_t {
  Tick,        // while holding: SOS reached / about to switch off
  Countdown,   // 0.5 s on/off, then 0.2 s on/off for the last 5 s
  Cancelled,   // 1 short
  Sent,        // 3 long
  Failed,      // 2 short (repeated each minute by the caller)
  SosAccepted, // 2 long
  TurnedOn,    // 1 long
  TurningOff,  // 3 short
  BatteryLow,  // 2 short (repeated hourly by the caller)
  Find,        // 2 long
};

namespace motor {
void begin();
void play(Pattern p, uint8_t countdownS = 0);
void status(uint8_t quarters);  // 1–4 short buzzes
void stop();
void update(uint32_t now);
bool busy();
}  // namespace motor
