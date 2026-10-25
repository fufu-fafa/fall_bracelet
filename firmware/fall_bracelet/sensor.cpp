#include "sensor.h"

#include <Adafruit_MPU6050.h>
#include <Wire.h>

#include "config.h"

namespace {
Adafruit_MPU6050 mpu;

constexpr uint8_t ADDR = 0x68;
constexpr uint8_t REG_FIFO_EN = 0x23;
constexpr uint8_t REG_INT_STATUS = 0x3A;
constexpr uint8_t REG_USER_CTRL = 0x6A;
constexpr uint8_t REG_PWR_MGMT_1 = 0x6B;
constexpr uint8_t REG_PWR_MGMT_2 = 0x6C;
constexpr uint8_t REG_FIFO_COUNT_H = 0x72;
constexpr uint8_t REG_FIFO_R_W = 0x74;
constexpr float LSB_PER_G = 32768.0f / ACC_RANGE_G;  // 4096 at ±8 g

void writeReg(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(ADDR);
  Wire.write(reg);
  Wire.write(value);
  Wire.endTransmission();
}

size_t readRegs(uint8_t reg, uint8_t* buf, size_t len) {
  Wire.beginTransmission(ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return 0;
  size_t n = Wire.requestFrom(ADDR, (uint8_t)len);
  for (size_t i = 0; i < n; i++) buf[i] = Wire.read();
  return n;
}

void resetFifo() {
  writeReg(REG_USER_CTRL, 0x04);  // FIFO_RESET
  writeReg(REG_USER_CTRL, 0x40);  // FIFO_EN
}
}  // namespace

namespace sensor {

bool begin() {
  Wire.begin(PIN_SDA, PIN_SCL);
  Wire.setClock(400000);
  if (!mpu.begin(ADDR, &Wire)) return false;

  static_assert(ACC_RANGE_G == 8, "update setAccelerometerRange for another range");
  mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
  // With the DLPF on the base rate is 1 kHz, so divisor 9 gives 100 Hz.
  mpu.setFilterBandwidth(MPU6050_BAND_44_HZ);
  mpu.setSampleRateDivisor(1000 / SAMPLE_HZ - 1);

  // Gyro off: internal oscillator, all three gyro axes in standby. Saves ~3 mA.
  writeReg(REG_PWR_MGMT_1, 0x00);
  writeReg(REG_PWR_MGMT_2, 0x07);

  writeReg(REG_FIFO_EN, 0x08);  // accelerometer only, 6 bytes per sample
  resetFifo();
  return true;
}

size_t read(Sample* out, size_t max) {
  uint8_t buf[6 * 20];
  uint8_t st = 0;
  readRegs(REG_INT_STATUS, &st, 1);
  if (st & 0x10) {  // FIFO_OFLOW: lost samples, start clean
    resetFifo();
    return 0;
  }
  uint8_t c[2];
  if (readRegs(REG_FIFO_COUNT_H, c, 2) != 2) return 0;
  size_t available = ((c[0] << 8) | c[1]) / 6;
  size_t count = 0;
  while (count < available && count < max) {
    size_t batch = min(min(available - count, max - count), sizeof(buf) / 6);
    if (readRegs(REG_FIFO_R_W, buf, batch * 6) != batch * 6) break;
    for (size_t i = 0; i < batch; i++) {
      const uint8_t* p = buf + i * 6;
      out[count + i] = {
          (int16_t)((p[0] << 8) | p[1]) / LSB_PER_G,
          (int16_t)((p[2] << 8) | p[3]) / LSB_PER_G,
          (int16_t)((p[4] << 8) | p[5]) / LSB_PER_G,
      };
    }
    count += batch;
  }
  return count;
}

void armMotionWake() {
  writeReg(REG_USER_CTRL, 0x00);  // FIFO off
  mpu.setHighPassFilter(MPU6050_HIGHPASS_0_63_HZ);
  mpu.setMotionDetectionThreshold((uint8_t)(MOTION_WAKE_G * 1000 / 2));  // 2 mg per LSB
  mpu.setMotionDetectionDuration(1);
  mpu.setInterruptPinLatch(true);
  mpu.setInterruptPinPolarity(false);  // active high
  mpu.setMotionInterrupt(true);
  mpu.setCycleRate(MPU6050_CYCLE_5_HZ);
  mpu.enableCycle(true);
}

void sleep() {
  mpu.setMotionInterrupt(false);
  mpu.enableSleep(true);
}

}  // namespace sensor
