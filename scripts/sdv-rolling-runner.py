#!/usr/bin/env python3
"""Run a child process and roll external stdout/stderr logs by size."""
import logging
from logging.handlers import RotatingFileHandler
import os
import signal
import subprocess
import sys

def main():
    if len(sys.argv) < 3:
        print("Usage: sdv-rolling-runner.py LOGFILE COMMAND [ARGS...]", file=sys.stderr)
        return 2
    logfile = os.path.abspath(sys.argv[1])
    os.makedirs(os.path.dirname(logfile), exist_ok=True)
    logger = logging.getLogger("sdv.wsl.runner")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    handler = RotatingFileHandler(logfile, maxBytes=25 * 1024 * 1024, backupCount=14, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger.addHandler(handler)
    process = None
    def forward(sig, frame):
        if process is not None and process.poll() is None:
            process.send_signal(sig)
    signal.signal(signal.SIGTERM, forward)
    signal.signal(signal.SIGINT, forward)
    try:
        process = subprocess.Popen(sys.argv[2:], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, errors="replace", bufsize=1)
        for line in process.stdout:
            logger.info(line.rstrip("\r\n"))
        return process.wait()
    except Exception:
        logger.exception("SDV child process failed")
        return 1
    finally:
        handler.close()
if __name__ == "__main__":
    sys.exit(main())
