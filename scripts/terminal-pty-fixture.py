#!/usr/bin/env python3
"""Test-only local PTY peer. JSON lines on stdio; independent bounded input/output credits."""
import argparse
import base64
import errno
import fcntl
import json
import os
import pty
import selectors
import signal
import struct
import sys
import termios
import time

WINDOW = 1024 * 1024
INPUT_WINDOW = 64 * 1024


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory")
    parser.add_argument("columns", type=int)
    parser.add_argument("rows", type=int)
    args = parser.parse_args()
    assert 1 <= args.columns <= 4096 and 1 <= args.rows <= 4096
    master, slave = pty.openpty()

    def resize(columns, rows):
        assert type(columns) is int and type(rows) is int and 1 <= columns <= 4096 and 1 <= rows <= 4096
        fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", rows, columns, 0, 0))

    resize(args.columns, args.rows)
    pid = os.fork()
    if pid == 0:
        os.close(master)
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
        for fd in (0, 1, 2):
            os.dup2(slave, fd)
        if slave > 2:
            os.close(slave)
        os.chdir(args.directory)
        environment = dict(os.environ, TERM="xterm-256color", COLORTERM="truecolor", TERM_PROGRAM="Kinetica",
                           ZDOTDIR=args.directory, PS1="KINETICA> ", RPS1="", RPROMPT="", HISTFILE="/dev/null",
                           LC_ALL="en_US.UTF-8", LESSHISTFILE="-", LESS="", TMUX="")
        environment.update({f"XDG_{kind.upper()}_HOME": os.path.join(args.directory, kind)
                            for kind in ("config", "data", "state", "cache")})
        os.execve("/bin/zsh", ["zsh", "-d", "-f", "-i"], environment)
        os._exit(127)
    os.close(slave)
    os.set_blocking(master, False)
    os.set_blocking(0, False)
    selector = selectors.DefaultSelector()
    selector.register(0, selectors.EVENT_READ, "control")
    output_credit = 0
    pending = bytearray()
    commands = bytearray()
    status = None

    def send(message):
        sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
        sys.stdout.flush()

    def terminate(_signal, _frame):
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, terminate)
    try:
        send({"inputCredit": INPUT_WINDOW, "pid": pid})
        while True:
            flags = (selectors.EVENT_READ if output_credit else 0) | (selectors.EVENT_WRITE if pending else 0)
            if master in selector.get_map():
                if flags:
                    selector.modify(master, flags, "pty")
                else:
                    selector.unregister(master)
            elif flags:
                selector.register(master, flags, "pty")
            for key, ready in selector.select(0.1):
                if key.data == "control":
                    data = os.read(0, 16 * 1024)
                    if not data:
                        return
                    commands.extend(data)
                    assert len(commands) <= 128 * 1024, "Control frame overflow"
                    while b"\n" in commands:
                        line, _, commands = commands.partition(b"\n")
                        message = json.loads(line)
                        if "input" in message:
                            value = base64.b64decode(message["input"], validate=True)
                            assert len(value) <= INPUT_WINDOW - len(pending), "Input window exceeded"
                            pending.extend(value)
                        elif "credit" in message:
                            value = message["credit"]
                            assert type(value) is int and 0 < value <= WINDOW - output_credit, "Output credit overflow"
                            output_credit += value
                        elif "resize" in message:
                            resize(*message["resize"])
                        else:
                            raise ValueError("Unknown control")
                else:
                    if ready & selectors.EVENT_WRITE:
                        try:
                            count = os.write(master, pending)
                            del pending[:count]
                            if count:
                                send({"inputCredit": count})
                        except BlockingIOError:
                            pass
                    if ready & selectors.EVENT_READ:
                        try:
                            data = os.read(master, min(output_credit, 16 * 1024))
                        except OSError as error:
                            if error.errno != errno.EIO:
                                raise
                            data = b""
                        if not data:
                            _, status = os.waitpid(pid, 0)
                            send({"eof": True, "exit": os.waitstatus_to_exitcode(status)})
                            return
                        output_credit -= len(data)
                        send({"output": base64.b64encode(data).decode("ascii")})
    finally:
        selector.close()
        os.close(master)
        if status is None:
            # Only this fixture's child/process group; never signal a user tmux server.
            for attempt in range(51):
                result, status = os.waitpid(pid, os.WNOHANG)
                if result:
                    break
                if attempt == 50:
                    try:
                        os.killpg(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    os.waitpid(pid, 0)
                    break
                time.sleep(0.01)


if __name__ == "__main__":
    main()
