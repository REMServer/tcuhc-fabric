#!/usr/bin/env python3
"""Start a fresh dedicated development server, wait for readiness, then stop it."""

from __future__ import annotations

import argparse
import os
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
READY = "Done ("
FATAL_MARKERS = (
    "Critical injection failure",
    "MixinApplyError",
    "Exception in server tick loop",
    "Failed to start the minecraft server",
)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-local", action="store_true", help="allow modifying the local run directory")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if not os.environ.get("CI") and not args.allow_local:
        parser.error("this creates a fresh run directory; pass --allow-local outside CI")

    run = ROOT / "run"
    run.mkdir(exist_ok=True)
    (run / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run / "server.properties").write_text(
        "online-mode=false\nserver-port=0\nlevel-name=ci-smoke-world\n"
        "view-distance=2\nsimulation-distance=2\nmax-tick-time=-1\n",
        encoding="utf-8",
    )
    (run / "uhc.properties").write_text(
        "pregenerateOnStart=false\nnetherPregenerate=false\nborderStart=100\n",
        encoding="utf-8",
    )

    gradle = str(ROOT / "gradlew.bat") if os.name == "nt" else str(ROOT / "gradlew")
    process = subprocess.Popen(
        [gradle, "runServer", "--console=plain"], cwd=ROOT,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    lines: queue.Queue[str | None] = queue.Queue()

    def read_output() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    deadline = time.monotonic() + args.timeout
    tail: list[str] = []
    ready = False
    try:
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                if process.poll() is not None:
                    break
                continue
            if line is None:
                break
            print(line, end="")
            tail.append(line.rstrip())
            tail = tail[-100:]
            if READY in line:
                ready = True
                assert process.stdin is not None
                process.stdin.write("stop\n")
                process.stdin.flush()
                break
        if ready:
            process.wait(timeout=60)
    except subprocess.TimeoutExpired:
        pass
    finally:
        if process.poll() is None:
            if os.name == "nt":
                subprocess.run(
                    ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    check=False,
                )
            else:
                process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()

    output = "\n".join(tail)
    fatal = [marker for marker in FATAL_MARKERS if marker in output]
    if not ready or fatal or process.returncode not in (0, 143):
        print(f"\nServer smoke test failed (ready={ready}, exit={process.returncode}, fatal={fatal}).")
        return 1
    print("\nDedicated server reached ready state and stopped cleanly.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
