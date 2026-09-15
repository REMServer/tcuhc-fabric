#!/usr/bin/env python3
"""Run the fast pre-generation-buffer verification suite."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SUITES = [HERE / "test_buffer_contract.py"]


def main() -> int:
    failures: list[str] = []
    for suite in SUITES:
        print(f"\n{'=' * 78}\n{suite.name}\n{'=' * 78}", flush=True)
        result = subprocess.run([sys.executable, str(suite)], cwd=HERE.parents[3])
        if result.returncode:
            failures.append(suite.name)
    if failures:
        print("\nFAILED: " + ", ".join(failures))
        return 1
    print(f"\nAll {len(SUITES)} pre-generation-buffer suites passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
