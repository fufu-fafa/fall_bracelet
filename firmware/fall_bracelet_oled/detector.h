// Three-phase fall detector: optional free fall -> impact -> lying still, then an orientation
// or strong-impact check. Fed one sample at a time at SAMPLE_HZ.
#pragma once
#include <stdint.h>

#include "config.h"
#include "sensor.h"

class FallDetector {
 public:
  // Returns true once, on the sample that confirms a fall.
  bool push(const Sample& s);
  void reset();

  float peakG() const { return peak_; }
  float angleDeg() const { return angle_; }
  float lastSvm() const { return svm_; }

 private:
  enum class Phase : uint8_t { Watching, Settling, Still };

  static constexpr uint32_t ms(uint32_t v) { return v * SAMPLE_HZ / 1000; }  // ms -> samples
  static constexpr uint32_t HISTORY = HISTORY_S * SAMPLE_HZ;

  const Sample& at(uint32_t index) const { return ring_[index % HISTORY]; }
  void meanDirection(uint32_t from, uint32_t to, float out[3]) const;

  Sample ring_[HISTORY] = {};
  uint32_t n_ = 0;  // samples seen

  Phase phase_ = Phase::Watching;
  uint32_t ffRun_ = 0;           // consecutive samples below FF_THRESH_G
  uint32_t ffStart_ = 0;         // first sample of the last qualifying free fall
  uint32_t ffEnd_ = 0;           // sample after it ended
  bool ffSeen_ = false;
  uint32_t impactAt_ = 0;
  float pre_[3] = {0, 0, 1};     // gravity direction before the event
  float peak_ = 0, angle_ = 0, svm_ = 1;
  double sum_ = 0, sumSq_ = 0;
  uint32_t stillCount_ = 0;
};
