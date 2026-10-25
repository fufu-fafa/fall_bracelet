#!/usr/bin/env python3
"""Send commands to the ble_msg_test firmware and print what it says back.

    python3 bt_cmd.py fall            # one command, then listen 12 s for the ACK
    python3 bt_cmd.py -t 30 sos info  # several commands, listen 30 s
    python3 bt_cmd.py -t 0            # just listen until Ctrl-C

For an interactive console use: picocom -b 115200 --omap crlf /dev/ttyUSB0
"""
import argparse
import sys
import time

import serial

ap = argparse.ArgumentParser()
ap.add_argument("commands", nargs="*")
ap.add_argument("-p", "--port", default="/dev/ttyUSB0")
ap.add_argument("-t", "--listen", type=float, default=12, help="seconds to listen after the last command (0 = forever)")
args = ap.parse_args()

s = serial.Serial()
s.port, s.baudrate, s.timeout = args.port, 115200, 0.1
# Leave DTR/RTS as the kernel sets them (both on): toggling them one at a time resets the DevKit.
s.open()

for c in args.commands:
    s.write((c + "\n").encode())
    time.sleep(0.3)

end = time.time() + args.listen if args.listen else float("inf")
try:
    while time.time() < end:
        line = s.readline()
        if line:
            sys.stdout.write(line.decode(errors="replace"))
            sys.stdout.flush()
except KeyboardInterrupt:
    pass
