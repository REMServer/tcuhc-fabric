#!/usr/bin/env python3
"""Exercise the pre-generation buffer through a real dedicated-server console.

This deletes and recreates the repository's development ``run`` directory. It
does not exercise ``buffer generate`` or ``buffer use`` because both deliberately
restart the JVM and the latter selects a different active world.
"""

from __future__ import annotations

import argparse
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
RUN = ROOT / "run"
READY = "Done ("
FATAL = (
    "Critical injection failure",
    "MixinApplyError",
    "Exception in server tick loop",
    "Failed to start the minecraft server",
    "Unknown or incomplete command",
)


def properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    if not path.is_file():
        return result
    for raw in path.read_text(encoding="iso-8859-1").splitlines():
        line = raw.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-local", action="store_true", help="allow replacing the local run directory")
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    if not os.environ.get("CI") and not args.allow_local:
        parser.error("this replaces the development run directory; pass --allow-local on a disposable checkout")

    if RUN.exists():
        shutil.rmtree(RUN)
    RUN.mkdir()
    (RUN / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (RUN / "server.properties").write_text(
        "online-mode=false\nserver-port=0\nlevel-name=buffer-smoke-world\n"
        "view-distance=2\nsimulation-distance=2\nmax-tick-time=-1\n",
        encoding="utf-8",
    )
    (RUN / "uhc.properties").write_text(
        "pregenerateOnStart=false\nnetherPregenerate=false\nborderStart=100\n",
        encoding="utf-8",
    )

    # Use an explicit path on Windows. cmd may inherit
    # NoDefaultCurrentDirectoryInExePath=1 and refuse to search the working directory.
    gradle = str(ROOT / "gradlew.bat") if os.name == "nt" else str(ROOT / "gradlew")
    process = subprocess.Popen(
        [gradle, "runServer", "--console=plain"], cwd=ROOT,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    lines: queue.Queue[str | None] = queue.Queue()

    def collect() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=collect, daemon=True).start()
    transcript: list[str] = []

    def drain(seconds: float) -> None:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=0.2)
            except queue.Empty:
                continue
            if line is None:
                return
            print(line, end="")
            transcript.append(line.rstrip())

    deadline = time.monotonic() + args.timeout
    ready = False
    try:
        while time.monotonic() < deadline and process.poll() is None:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                break
            print(line, end="")
            transcript.append(line.rstrip())
            if READY in line:
                ready = True
                break
        if ready:
            assert process.stdin is not None
            commands = (
                "uhc preset save buffer_smoke overwrite",
                "uhc buffer status",
                "uhc buffer list buffer_smoke",
                "uhc buffer name buffer_smoke 1 smoke_slot",
                "uhc buffer interval 60",
                "uhc buffer enable",
                "uhc buffer status",
                "uhc buffer disable",
                "uhc buffer status",
            )
            for command in commands:
                print(f"> {command}")
                process.stdin.write(command + "\n")
                process.stdin.flush()
                drain(0.7)
            process.stdin.write("stop\n")
            process.stdin.flush()
            process.wait(timeout=60)
            drain(1)
    except subprocess.TimeoutExpired:
        pass
    finally:
        if process.poll() is None:
            if os.name == "nt":
                # gradlew.bat starts cmd, Gradle, and the dedicated server as a process tree.
                # Killing only cmd leaves java.exe holding run/logs/debug.log open.
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

    joined = "\n".join(transcript)
    config = properties(RUN / "uhc_pregen_buffer/config.properties")
    slot = properties(RUN / "uhc_pregen_buffer/buffer_smoke/slot-1/slot.properties")
    checks = {
        "server reached ready state": ready,
        "server stopped cleanly": process.returncode in (0, 143),
        "no fatal or rejected command output": not any(marker in joined for marker in FATAL),
        "buffer ended disabled": config.get("bufferEnabled", config.get("enabled")) == "false",
        "interval command persisted 60 seconds": config.get("checkIntervalSeconds") == "60",
        "slot display name persisted": slot.get("name") == "smoke_slot",
        "slot metadata remains non-ready": slot.get("state", "EMPTY") != "READY",
        "safe commands did not stage generation": not (RUN / "uhc_pregen_buffer/active-job.properties").exists(),
    }
    for name, ok in checks.items():
        print(f"{'PASS' if ok else 'FAIL'}  {name}")
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
