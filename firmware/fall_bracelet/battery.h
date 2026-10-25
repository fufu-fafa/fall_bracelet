#pragma once
#include <stdint.h>

namespace battery {
// Average of 16 readings through the divider on PIN_VBAT.
uint16_t readMv();
// Rough voltage-based estimate, 3.3 V = 0 %, 4.2 V = 100 %. Reads high while charging.
uint8_t percent(uint16_t mv);
}  // namespace battery
