// SW2: what a press means depends on how long it is held.
#pragma once
#include <stdint.h>

enum class ButtonEvent : uint8_t {
  None,
  Down,       // any press starts (cancels a countdown)
  Short,      // released before SHORT_PRESS_MAX_MS: status check
  SosTick,    // held SOS_HOLD_MS: "release now for SOS"
  Sos,        // released between SOS_HOLD_MS and SOS_HOLD_MAX_MS
  Off,        // held OFF_HOLD_MS: soft off (fires while still held)
};

namespace button {
// ignoreUntilReleased: the press that woke the chip shouldn't count as a new press.
void begin(bool ignoreUntilReleased);
ButtonEvent update(uint32_t now);
bool isDown();
}  // namespace button
