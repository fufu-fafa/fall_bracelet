// Every number the firmware uses. Values marked (setup) are defaults that the phone app can
// change over BLE; they are then stored in NVS (see settings.cpp).
// The detection thresholds are starting points, not validated values — tune them with
// recorded data (Live tab in the app) before relying on them.
#pragma once
#include <Arduino.h>

// ---- Pins (Seeed XIAO ESP32-C3) ------------------------------------------------------------
// TODO: check every pin against the v3 schematic. The button and the MPU6050 INT line must be
// on GPIO0–GPIO5, the only pins that can wake the ESP32-C3 from deep sleep.
#define PIN_VBAT     D0   // GPIO2, battery divider
#define PIN_BUTTON   D1   // GPIO3, SW2 to GND (needs a pull-up that survives deep sleep)
#define PIN_MPU_INT  D2   // GPIO4, MPU6050 INT, active high
#define PIN_MOTOR    D3   // GPIO5, motor driver transistor
#define PIN_SDA      D4   // GPIO6
#define PIN_SCL      D5   // GPIO7
#define VBAT_DIVIDER 2.0f // VBAT = pin voltage × this

// ---- Sampling ------------------------------------------------------------------------------
#define SAMPLE_HZ         100
#define ACC_RANGE_G       8
#define HISTORY_S         3     // ring buffer kept for look-back and logging
#define FIFO_READ_MS      100   // TODO: 1000 once light sleep between batches works (see power notes)

// ---- Fall detection ------------------------------------------------------------------------
#define FF_THRESH_G           0.5f
#define FF_MIN_MS             80
#define IMPACT_WINDOW_MS      1000
#define IMPACT_THRESH_G       2.5f
#define IMPACT_ONLY_THRESH_G  3.5f
#define STRONG_IMPACT_G       4.0f
#define SETTLE_MS             1000
#define STILL_WINDOW_MS       2000
#define STILL_STD_G           0.15f
#define ORIENT_CHANGE_DEG     45.0f
#define PRE_EVENT_MS          1000  // gravity direction is averaged over this long before the event

// ---- Wearer interaction --------------------------------------------------------------------
#define COUNTDOWN_S_DEFAULT   20    // (setup)
#define SHORT_PRESS_MAX_MS    1000
#define SOS_HOLD_MS           2000
#define SOS_HOLD_MAX_MS       4000
#define OFF_HOLD_MS           5000
#define ON_HOLD_MS            3000
#define DEBOUNCE_MS           30

// ---- Power ---------------------------------------------------------------------------------
#define IDLE_TIMEOUT_MIN      10
#define MOTION_WAKE_G         0.1f
#define BATT_CHECK_MIN        10
#define LOW_BATT_PCT          20
#define CRIT_BATT_PCT         5
#define BATT_EMPTY_MV         3300
#define BATT_FULL_MV          4200

// ---- Alerts --------------------------------------------------------------------------------
#define ALERT_RETRIES         3     // resends, ALERT_ACK_TIMEOUT_MS apart
#define ALERT_ACK_TIMEOUT_MS  10000
#define ALERT_RETRY_MIN       5
#define ALERT_FAILED_AFTER_MS 30000 // buzz "failed" once an alarm has waited this long
#define CHECKIN_HOUR_DEFAULT  8     // (setup)
#define CHECKIN_MIN_DEFAULT   0     // (setup)
#define EVENT_QUEUE_MAX       32    // oldest non-alarm events are dropped first when full

// ---- BLE -----------------------------------------------------------------------------------
#define BLE_NAME_PREFIX       "FallBracelet"
#define BLE_REQUIRE_BONDING   1     // encrypted, bonded link for every characteristic
#define BLE_TX_POWER_DBM      3
#define STATUS_INTERVAL_MS    30000
