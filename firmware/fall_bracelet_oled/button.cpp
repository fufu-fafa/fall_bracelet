#include "button.h"

#include <Arduino.h>

#include "config.h"

namespace {
bool stable = false;       // debounced: true = pressed
bool lastRaw = false;
uint32_t rawChangedAt = 0;
uint32_t pressedAt = 0;
bool sosTicked = false, offFired = false, ignoring = false;
}  // namespace

namespace button {

void begin(bool ignoreUntilReleased) {
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  stable = lastRaw = digitalRead(PIN_BUTTON) == LOW;
  ignoring = ignoreUntilReleased && stable;
}

bool isDown() { return digitalRead(PIN_BUTTON) == LOW; }

ButtonEvent update(uint32_t now) {
  bool raw = isDown();
  if (raw != lastRaw) {
    lastRaw = raw;
    rawChangedAt = now;
  }
  if (raw != stable && now - rawChangedAt >= DEBOUNCE_MS) {
    stable = raw;
    if (stable) {
      pressedAt = now;
      sosTicked = offFired = false;
      return ButtonEvent::Down;
    }
    if (ignoring) {
      ignoring = false;
      return ButtonEvent::None;
    }
    uint32_t held = now - pressedAt;
    if (offFired) return ButtonEvent::None;
    if (held < SHORT_PRESS_MAX_MS) return ButtonEvent::Short;
    if (held >= SOS_HOLD_MS && held < SOS_HOLD_MAX_MS) return ButtonEvent::Sos;
    return ButtonEvent::None;  // 1–2 s and 4–5 s do nothing
  }
  if (stable && !ignoring) {
    uint32_t held = now - pressedAt;
    if (!sosTicked && held >= SOS_HOLD_MS) {
      sosTicked = true;
      return ButtonEvent::SosTick;
    }
    if (!offFired && held >= OFF_HOLD_MS) {
      offFired = true;
      return ButtonEvent::Off;
    }
  }
  return ButtonEvent::None;
}

}  // namespace button
