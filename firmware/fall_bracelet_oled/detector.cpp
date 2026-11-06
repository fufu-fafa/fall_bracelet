#include "detector.h"

#include <math.h>

void FallDetector::reset() {
  phase_ = Phase::Watching;
  ffRun_ = 0;
  ffSeen_ = false;
}

void FallDetector::meanDirection(uint32_t from, uint32_t to, float out[3]) const {
  float sx = 0, sy = 0, sz = 0;
  uint32_t count = 0;
  for (uint32_t i = from; i < to; i++) {
    sx += at(i).x;
    sy += at(i).y;
    sz += at(i).z;
    count++;
  }
  if (count == 0) count = 1;
  out[0] = sx / count;
  out[1] = sy / count;
  out[2] = sz / count;
}

static float angleBetween(const float a[3], const float b[3]) {
  float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  float na = sqrtf(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
  float nb = sqrtf(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
  if (na < 1e-3f || nb < 1e-3f) return 0;
  float c = fmaxf(-1.0f, fminf(1.0f, dot / (na * nb)));
  return acosf(c) * 180.0f / (float)M_PI;
}

bool FallDetector::push(const Sample& s) {
  const uint32_t i = n_;
  ring_[i % HISTORY] = s;
  n_++;
  svm_ = sqrtf(s.x * s.x + s.y * s.y + s.z * s.z);

  switch (phase_) {
    case Phase::Watching: {
      // Phase 1 (optional): free fall.
      if (svm_ < FF_THRESH_G) {
        if (ffRun_++ == 0) ffStart_ = i;
      } else {
        if (ffRun_ >= ms(FF_MIN_MS)) {
          ffSeen_ = true;
          ffEnd_ = i;
        }
        ffRun_ = 0;
      }
      if (ffSeen_ && i - ffEnd_ > ms(IMPACT_WINDOW_MS)) ffSeen_ = false;

      // Phase 2: impact. A clean free fall lowers the threshold.
      float threshold = ffSeen_ ? IMPACT_THRESH_G : IMPACT_ONLY_THRESH_G;
      if (svm_ > threshold) {
        uint32_t eventStart = ffSeen_ ? ffStart_ : (i > ms(200) ? i - ms(200) : 0);
        uint32_t from = eventStart > ms(PRE_EVENT_MS) ? eventStart - ms(PRE_EVENT_MS) : 0;
        // Never look further back than the ring holds.
        if (i - from >= HISTORY) from = i - HISTORY + 1;
        meanDirection(from, eventStart, pre_);
        impactAt_ = i;
        peak_ = svm_;
        phase_ = Phase::Settling;
      }
      break;
    }

    case Phase::Settling:
      // Ignore the bounce, but keep the highest peak for the strong-impact rule and the alert.
      peak_ = fmaxf(peak_, svm_);
      if (i - impactAt_ >= ms(SETTLE_MS)) {
        sum_ = sumSq_ = 0;
        stillCount_ = 0;
        phase_ = Phase::Still;
      }
      break;

    case Phase::Still: {
      // Phase 3: lying still.
      sum_ += svm_;
      sumSq_ += (double)svm_ * svm_;
      stillCount_++;
      if (stillCount_ < ms(STILL_WINDOW_MS)) break;

      double mean = sum_ / stillCount_;
      float sd = sqrtf(fmax(0.0, sumSq_ / stillCount_ - mean * mean));
      float post[3];
      meanDirection(i + 1 - stillCount_, i + 1, post);
      angle_ = angleBetween(pre_, post);
      reset();
      // Stillness alone also fits putting the bracelet down hard on a table.
      return sd < STILL_STD_G && (angle_ > ORIENT_CHANGE_DEG || peak_ > STRONG_IMPACT_G);
    }
  }
  return false;
}
