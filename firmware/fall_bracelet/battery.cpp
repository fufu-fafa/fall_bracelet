#include "battery.h"

#include <Arduino.h>

#include "config.h"

namespace battery {

uint16_t readMv() {
  uint32_t sum = 0;
  for (int i = 0; i < 16; i++) sum += analogReadMilliVolts(PIN_VBAT);
  return (uint16_t)(sum / 16 * VBAT_DIVIDER);
}

uint8_t percent(uint16_t mv) {
  if (mv <= BATT_EMPTY_MV) return 0;
  if (mv >= BATT_FULL_MV) return 100;
  return (uint8_t)((uint32_t)(mv - BATT_EMPTY_MV) * 100 / (BATT_FULL_MV - BATT_EMPTY_MV));
}

}  // namespace battery
