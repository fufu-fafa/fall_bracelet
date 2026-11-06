// MPU6050 accelerometer: 100 Hz into the on-chip FIFO, read in batches.
#pragma once
#include <stddef.h>

struct Sample {
  float x, y, z;  // g
};

namespace sensor {
bool begin();
// Drains the FIFO into out (up to max samples). Returns the number read.
size_t read(Sample* out, size_t max);
// Low-power cycle mode that raises INT on movement above MOTION_WAKE_G (for Idle).
void armMotionWake();
// Full sleep (for Off).
void sleep();
}  // namespace sensor
