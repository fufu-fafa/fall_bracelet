// 0.96" SSD1306 OLED (128x64) on the same I2C bus as the MPU6050. Status only: the bracelet
// works the same with the display missing or broken.
#pragma once
#include <stdint.h>

#include "ble_protocol.h"

// Everything a frame shows, filled in by the sketch.
struct DisplayView {
  DevState state;
  uint8_t batteryPct;
  bool batteryReal;      // false with PROTOTYPE_NO_BATTERY, where the percentage is made up
  bool bleConnected;
  bool sensorOk;
  bool deliveryFailing;
  bool clockSet;
  uint8_t hour, minute;  // local time, valid when clockSet
  uint8_t countdownS;
  uint16_t pendingEvents;
  float g;               // latest acceleration magnitude, printed above the graph
};

namespace display {
bool begin();  // call after sensor::begin(), which starts the I2C bus
void addSample(float g);  // feed every sample so the graph keeps running behind other screens
void show(const DisplayView& v);
void off();    // the panel stays lit through deep sleep unless told otherwise
}  // namespace display
