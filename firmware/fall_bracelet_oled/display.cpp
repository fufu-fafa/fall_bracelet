#include "display.h"

#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Wire.h>

#include "config.h"

namespace {
// clkAfter must match sensor::begin(): the library's default drops the shared bus to 100 kHz
// after every frame, which would slow the FIFO reads too.
Adafruit_SSD1306 oled(OLED_WIDTH, OLED_HEIGHT, &Wire, -1, 400000UL, 400000UL);
bool ready = false;

constexpr uint8_t CHAR_W = 6;  // built-in font at size 1, including spacing
constexpr uint8_t BODY_Y = 18; // two-colour panels switch colour at row 16
constexpr int16_t GRAPH_TOP = 14;
constexpr int16_t GRAPH_BOTTOM = OLED_HEIGHT - 1;

// Each pixel column keeps the min and max of its samples, so a one-sample impact spike or
// free-fall dip still shows.
float colMin[OLED_WIDTH], colMax[OLED_WIDTH];
uint8_t colHead = 0;   // where the next column goes
uint8_t colCount = 0;
float binMin = 0, binMax = 0;
uint8_t binSamples = 0;

void centered(const char* text, int16_t y, uint8_t size) {
  oled.setTextSize(size);
  oled.setCursor((OLED_WIDTH - (int16_t)strlen(text) * CHAR_W * size) / 2, y);
  oled.print(text);
}

void drawHeader(const DisplayView& v) {
  char buf[8];
  oled.setTextSize(1);
  oled.setCursor(0, 3);
  oled.print(v.bleConnected ? "BLE" : "---");

  if (v.clockSet) {
    snprintf(buf, sizeof(buf), "%02u:%02u", v.hour, v.minute);
    centered(buf, 3, 1);
  }

  if (v.batteryReal) snprintf(buf, sizeof(buf), "%u%%", v.batteryPct);
  else snprintf(buf, sizeof(buf), "USB");
  oled.setCursor(OLED_WIDTH - (int16_t)strlen(buf) * CHAR_W, 3);
  oled.print(buf);
  oled.drawFastHLine(0, 13, OLED_WIDTH, SSD1306_WHITE);
}

int16_t graphY(float g) {
  float scaled = constrain(g / GRAPH_MAX_G, 0.0f, 1.0f);
  return GRAPH_BOTTOM - (int16_t)(scaled * (GRAPH_BOTTOM - GRAPH_TOP) + 0.5f);
}

void drawGuide(float g, uint8_t spacing) {
  int16_t y = graphY(g);
  for (int16_t x = 0; x < OLED_WIDTH; x += spacing) oled.drawPixel(x, y, SSD1306_WHITE);
}

// Text over the oldest part of the graph, on a black background so it stays readable.
void label(const char* text, int16_t y) {
  oled.setTextSize(1);
  oled.setTextColor(SSD1306_WHITE, SSD1306_BLACK);
  oled.setCursor(0, y);
  oled.print(text);
  oled.setTextColor(SSD1306_WHITE);
}

void drawGraph(const DisplayView& v) {
  drawGuide(1.0f, 4);              // resting
  drawGuide(IMPACT_THRESH_G, 8);   // what counts as an impact

  float peak = 0;
  for (uint8_t i = 0; i < colCount; i++) {
    uint8_t c = (colHead + OLED_WIDTH - colCount + i) % OLED_WIDTH;
    int16_t top = graphY(colMax[c]);
    oled.drawFastVLine(OLED_WIDTH - colCount + i, top, graphY(colMin[c]) - top + 1, SSD1306_WHITE);
    peak = max(peak, colMax[c]);
  }

  char buf[22];
  snprintf(buf, sizeof(buf), "%.2fg pk %.1f", v.g, peak);
  label(buf, GRAPH_TOP + 1);
  if (v.pendingEvents > 0) {
    snprintf(buf, sizeof(buf), "%u queued", v.pendingEvents);
    label(buf, GRAPH_BOTTOM - 7);
  }
}

void drawMonitoring(const DisplayView& v) {
  if (!v.sensorOk) {
    centered("NO SENSOR", BODY_Y + 4, 2);
    centered("Falls NOT detected", BODY_Y + 28, 1);
    return;
  }
  drawGraph(v);
}

void drawCountdown(const DisplayView& v) {
  char buf[4];
  snprintf(buf, sizeof(buf), "%u", v.countdownS);
  centered("FALL DETECTED", BODY_Y, 1);
  centered(buf, BODY_Y + 11, 3);
  centered("Press to cancel", BODY_Y + 37, 1);
}

void drawAlert(const DisplayView& v) {
  centered("ALERT", BODY_Y + 2, 2);
  const char* detail = !v.bleConnected      ? "No phone connected"
                       : v.deliveryFailing ? "Not delivered yet"
                                           : "Sending to phone...";
  centered(detail, BODY_Y + 28, 1);
}
}  // namespace

namespace display {

bool begin() {
  // periphBegin = false: Wire is already running on PIN_SDA/PIN_SCL. Starting it here would
  // use the board's default pins.
  ready = oled.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR, /*reset=*/false, /*periphBegin=*/false);
  if (ready) {
    oled.setTextColor(SSD1306_WHITE);
    oled.clearDisplay();
    oled.display();
  }
  return ready;
}

void addSample(float g) {
  binMin = binSamples ? min(binMin, g) : g;
  binMax = binSamples ? max(binMax, g) : g;
  if (++binSamples < GRAPH_SAMPLES_PER_COLUMN) return;
  binSamples = 0;
  colMin[colHead] = binMin;
  colMax[colHead] = binMax;
  colHead = (colHead + 1) % OLED_WIDTH;
  if (colCount < OLED_WIDTH) colCount++;
}

void show(const DisplayView& v) {
  if (!ready) return;
  oled.clearDisplay();
  drawHeader(v);
  switch (v.state) {
    case DevState::Monitoring: drawMonitoring(v); break;
    case DevState::Countdown: drawCountdown(v); break;
    case DevState::SendingAlert: drawAlert(v); break;
    case DevState::Idle: centered("Sleeping", BODY_Y + 12, 2); break;
    case DevState::Off: centered("Turning off", BODY_Y + 16, 1); break;
  }
  oled.display();
}

void off() {
  if (!ready) return;
  oled.clearDisplay();
  oled.display();
  oled.ssd1306_command(SSD1306_DISPLAYOFF);
}

}  // namespace display
