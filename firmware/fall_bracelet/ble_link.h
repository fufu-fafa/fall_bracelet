// BLE peripheral: the bracelet's link to the phone app. See ble_protocol.h / PROTOCOL.md.
#pragma once
#include <stdint.h>

#include "ble_protocol.h"
#include "sensor.h"

// Things the phone asked for. Produced on the BLE task, consumed in loop() via ble::poll().
struct BleMessage {
  enum Kind : uint8_t { CommandMsg, TimeMsg, ConfigMsg, AckedMsg } kind;
  union {
    uint8_t command;
    TimePacket time;
    ConfigPacket config;
    EventRecord acked;  // the event the phone just confirmed
  };
};

namespace ble {
void begin();
bool connected();
bool streaming();
void setStreaming(bool on);

bool poll(BleMessage& out);         // next message from the phone, if any
void publishStatus(const StatusPacket& s);
void publishConfig(const ConfigPacket& c);
void streamSamples(const Sample* s, size_t n);

// Delivers queued events one at a time, resending until acknowledged. Call from loop().
void deliver(uint32_t now);
bool deliveryFailing();             // retries for the front event are used up
}  // namespace ble
