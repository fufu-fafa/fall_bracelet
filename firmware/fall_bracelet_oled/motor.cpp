#include "motor.h"

#include <Arduino.h>

#include "config.h"

namespace {
struct Step {
  uint16_t onMs, offMs;
};
constexpr size_t MAX_STEPS = 80;  // a 60 s countdown needs 55 + 12
Step steps[MAX_STEPS];
size_t count = 0, current = 0;
bool on = false;
uint32_t phaseEnd = 0;
bool startPending = false;

void add(uint16_t onMs, uint16_t offMs, size_t times = 1) {
  while (times-- && count < MAX_STEPS) steps[count++] = {onMs, offMs};
}

void set(bool v) {
  on = v;
  digitalWrite(PIN_MOTOR, v ? HIGH : LOW);
}
}  // namespace

namespace motor {

void begin() {
  pinMode(PIN_MOTOR, OUTPUT);
  set(false);
}

void play(Pattern p, uint8_t countdownS) {
  count = 0;
  switch (p) {
    case Pattern::Tick: add(80, 0); break;
    case Pattern::Countdown: {
      uint8_t slow = countdownS > 5 ? countdownS - 5 : 0;
      add(500, 500, slow);
      add(200, 200, (countdownS - slow) * 1000 / 400);
      break;
    }
    case Pattern::Cancelled: add(200, 0); break;
    case Pattern::Sent: add(600, 300, 3); break;
    case Pattern::Failed: add(150, 200, 2); break;
    case Pattern::SosAccepted: add(600, 300, 2); break;
    case Pattern::TurnedOn: add(1000, 0); break;
    case Pattern::TurningOff: add(150, 200, 3); break;
    case Pattern::BatteryLow: add(150, 200, 2); break;
    case Pattern::Find: add(600, 300, 2); break;
  }
  current = 0;
  startPending = true;
}

void status(uint8_t quarters) {
  count = 0;
  add(150, 250, quarters < 1 ? 1 : quarters > 4 ? 4 : quarters);
  current = 0;
  startPending = true;
}

void stop() {
  count = current = 0;
  startPending = false;
  set(false);
}

bool busy() { return startPending || current < count; }

void update(uint32_t now) {
  if (startPending) {
    startPending = false;
    if (count == 0) return;
    set(true);
    phaseEnd = now + steps[0].onMs;
    return;
  }
  if (current >= count || (int32_t)(now - phaseEnd) < 0) return;
  if (on) {
    set(false);
    phaseEnd = now + steps[current].offMs;
  } else {
    if (++current >= count) return;
    set(true);
    phaseEnd = now + steps[current].onMs;
  }
}

}  // namespace motor
