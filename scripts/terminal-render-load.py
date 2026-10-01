#!/usr/bin/env python3
"""Bounded real-PTY load for renderer benchmarks. Input M<digits> CR updates row one.

The remaining rows scroll colored Unicode continuously; row one retains the most recent
input echo. Nonblocking output has at most 64 KiB of user-space backlog. Neither input nor
output is a host command, and no user shell configuration or files are touched.
"""
import argparse
import os
import re
import select
import termios
import time
import tty


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=float, default=10)
    parser.add_argument("--mib-per-second", type=float, default=4)
    args = parser.parse_args()
    assert 0 < args.seconds <= 120 and 0 < args.mib_per_second <= 100
    previous = termios.tcgetattr(0)
    tty.setraw(0)
    os.set_blocking(0, False)
    os.set_blocking(1, False)
    rows = os.get_terminal_size(1).lines
    line = "\x1b[38;2;90;180;240mload\x1b[0m 世界 é 😀 0123456789 abcdefghijklmnopqrstuvwxyz\r\n".encode()
    chunk = line * 128
    pending = bytearray(f"\x1b[2J\x1b[?25l\x1b[1;1HREADY\x1b[2;{rows}r\x1b[2;1H".encode())
    inputs = bytearray()
    start = time.monotonic()
    last = start
    credit = 0.0
    rate = args.mib_per_second * 1024 * 1024
    try:
        while time.monotonic() - start < args.seconds or pending:
            now = time.monotonic()
            credit = min(65536, credit + (now - last) * rate)
            last = now
            reading, writing, _ = select.select([0], [1] if pending else [], [], 0.001)
            if reading:
                data = os.read(0, 4096)
                if not data:
                    break
                inputs.extend(data)
                assert len(inputs) <= 4096, "Benchmark input overflow"
                while b"\r" in inputs:
                    marker, _, inputs = inputs.partition(b"\r")
                    assert re.fullmatch(rb"M[0-9]{6}", marker), repr(marker)
                    pending.extend(b"\x1b7\x1b[1;1H\x1b[0mINPUT:" + marker[1:] + b"\x1b[K\x1b8")
                    assert len(pending) <= 65536, "Benchmark output overflow"
            if writing:
                try:
                    count = os.write(1, pending)
                    del pending[:count]
                except BlockingIOError:
                    pass
            if now - start < args.seconds and credit >= len(chunk) and len(pending) + len(chunk) <= 32768:
                pending.extend(chunk)
                credit -= len(chunk)
    finally:
        # Input/output are the same PTY; restore its discipline before the process exits.
        termios.tcsetattr(0, termios.TCSANOW, previous)


if __name__ == "__main__":
    main()
