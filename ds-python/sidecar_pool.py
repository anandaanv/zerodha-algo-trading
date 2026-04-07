#!/usr/bin/env python3
"""
sidecar_pool.py — Launches all three TSFM sidecar servers as isolated subprocesses.
Each model runs under its own venv Python interpreter.

Usage:
    python sidecar_pool.py [--only chronos|llama|ttm]

Ports:
    11111 — Chronos-Bolt (Tiny)   venv: .venv-chronos
    11112 — Lag-Llama              venv: .venv-lagllama
    11113 — IBM Granite TTM        venv: .venv-granite
"""
from __future__ import annotations

import argparse
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict, List

BASE = Path(__file__).parent

SERVERS: Dict[str, dict] = {
    "chronos": {
        "script": str(BASE / "models" / "chronos_server.py"),
        "venv":   str(BASE / ".venv-chronos"),
        "port":   11111,
        "label":  "Chronos-Bolt (Tiny)",
    },
    "llama": {
        "script": str(BASE / "models" / "lagllama_server.py"),
        "venv":   str(BASE / ".venv-lagllama"),
        "port":   11112,
        "label":  "Lag-Llama",
    },
    "ttm": {
        "script": str(BASE / "models" / "granite_server.py"),
        "venv":   str(BASE / ".venv-granite"),
        "port":   11113,
        "label":  "IBM Granite TTM",
    },
}


def _python(venv: str) -> str:
    """Return path to the venv's Python interpreter."""
    p = Path(venv) / "bin" / "python"
    if not p.exists():
        print(f"[pool] ERROR: venv not found at {venv}. Run ./start.sh first.")
        sys.exit(1)
    return str(p)


def _wait_port_free(port: int, timeout: float = 15.0) -> None:
    """Block until the port is free or timeout expires."""
    import socket
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if s.connect_ex(("127.0.0.1", port)) != 0:
                return  # port is free
        time.sleep(0.5)
    print(f"[pool] WARNING: port {port} still occupied after {timeout}s — spawning anyway")


def _spawn(key: str) -> subprocess.Popen:
    cfg = SERVERS[key]
    python = _python(cfg["venv"])
    _wait_port_free(cfg["port"])
    proc = subprocess.Popen(
        [python, cfg["script"]],
        cwd=str(BASE),
    )
    print(f"[pool] {cfg['label']} started  pid={proc.pid}  port={cfg['port']}")
    return proc


def main(targets: List[str]) -> None:
    processes: Dict[str, subprocess.Popen] = {}

    for key in targets:
        processes[key] = _spawn(key)

    def _shutdown(signum, frame):
        print(f"\n[pool] received signal {signum}, shutting down ...")
        for key, proc in processes.items():
            if proc.poll() is None:
                print(f"[pool] terminating {key} (pid={proc.pid})")
                proc.terminate()
        for key, proc in processes.items():
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                print(f"[pool] force-killing {key}")
                proc.kill()
        print("[pool] all servers stopped.")
        sys.exit(0)

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    MAX_RETRIES = 3
    retry_counts: Dict[str, int] = {key: 0 for key in targets}
    dead: set = set()

    # monitor loop — restart crashed children (up to MAX_RETRIES times)
    while True:
        time.sleep(5)
        for key in list(targets):
            if key in dead:
                continue
            proc = processes[key]
            if proc.poll() is not None:
                exit_code = proc.returncode
                retry_counts[key] += 1
                if retry_counts[key] > MAX_RETRIES:
                    print(
                        f"[pool] {key} has crashed {retry_counts[key] - 1} times "
                        f"(exit={exit_code}). Giving up — other servers continue running."
                    )
                    dead.add(key)
                    continue
                print(
                    f"[pool] {key} exited (code={exit_code}), "
                    f"restarting (attempt {retry_counts[key]}/{MAX_RETRIES}) ..."
                )
                processes[key] = _spawn(key)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="TSFM sidecar pool")
    parser.add_argument(
        "--only",
        choices=list(SERVERS.keys()),
        default=None,
        help="Start only one server (useful for debugging)",
    )
    args = parser.parse_args()
    targets = [args.only] if args.only else list(SERVERS.keys())
    main(targets)
