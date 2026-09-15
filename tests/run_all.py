#!/usr/bin/env python3
"""Run every fast, dependency-free test suite maintained by the project."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUITES = [
    ROOT / "tests/test_repository.py",
    ROOT / "scripts/audit_mixins.py",
    ROOT / "docs/agent_run/2026-09-12-issue-fixes/tests/test_java_syntax.py",
    ROOT / "docs/agent_run/2026-09-12-issue-fixes/tests/test_structural.py",
    ROOT / "docs/agent_run/2026-09-12-issue-fixes/tests/test_noise.py",
    ROOT / "docs/agent_run/2026-09-14-pre-gen-buffer/tests/test_buffer_contract.py",
]


def main() -> int:
    failed: list[str] = []
    for suite in SUITES:
        print(f"\n{'=' * 78}\n{suite.relative_to(ROOT)}\n{'=' * 78}", flush=True)
        result = subprocess.run([sys.executable, str(suite)], cwd=ROOT)
        if result.returncode:
            failed.append(str(suite.relative_to(ROOT)))
    if failed:
        print("\nFAILED: " + ", ".join(failed))
        return 1
    print(f"\nAll {len(SUITES)} fast test suites passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
