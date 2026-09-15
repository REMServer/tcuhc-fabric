#!/usr/bin/env python3
"""Repository-wide checks that do not require Minecraft or third-party packages."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src/main/java"
RESOURCES = ROOT / "src/main/resources"


class Checks:
    def __init__(self) -> None:
        self.total = 0
        self.failures: list[str] = []

    def check(self, name: str, condition: bool, detail: str = "") -> None:
        self.total += 1
        if condition:
            print(f"PASS  {name}")
        else:
            self.failures.append(name)
            print(f"FAIL  {name}{': ' + detail if detail else ''}")


def properties() -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def strip_java_literals(source: str) -> str:
    """Remove comments and quoted literals before checking delimiter balance."""
    pattern = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
    return pattern.sub("", source)


def main() -> int:
    c = Checks()
    props = properties()
    version = props.get("mod_version", "")
    c.check("mod_version is stable semantic version x.y.z", bool(re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", version)), version)

    bad_json: list[str] = []
    for path in RESOURCES.rglob("*.json"):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            bad_json.append(f"{path.relative_to(ROOT)} ({exc})")
    c.check("every resource JSON file parses", not bad_json, "; ".join(bad_json))

    metadata = json.loads((RESOURCES / "fabric.mod.json").read_text(encoding="utf-8"))
    c.check("Fabric mod id is tcuhc", metadata.get("id") == "tcuhc")
    c.check("Fabric metadata receives the Gradle version", metadata.get("version") == "${version}")
    c.check("mod is declared server-side", metadata.get("environment") == "server")
    c.check("Minecraft dependency matches gradle.properties", metadata.get("depends", {}).get("minecraft") == props.get("minecraft_version"))

    entrypoints = metadata.get("entrypoints", {}).get("main", [])
    missing_entrypoints = [name for name in entrypoints if not (JAVA_ROOT / (name.replace(".", "/") + ".java")).is_file()]
    c.check("all declared entrypoints exist", not missing_entrypoints, ", ".join(missing_entrypoints))
    c.check("declared access widener exists", (RESOURCES / metadata.get("accessWidener", "missing")).is_file())
    c.check("all declared mixin configurations exist", all((RESOURCES / name).is_file() for name in metadata.get("mixins", [])))

    unbalanced: list[str] = []
    for path in JAVA_ROOT.rglob("*.java"):
        cleaned = strip_java_literals(path.read_text(encoding="utf-8"))
        for opening, closing in (("{", "}"), ("(", ")"), ("[", "]")):
            depth = 0
            for char in cleaned:
                if char == opening:
                    depth += 1
                elif char == closing:
                    depth -= 1
                    if depth < 0:
                        break
            if depth != 0:
                unbalanced.append(f"{path.relative_to(ROOT)} ({opening}{closing})")
                break
    c.check("all Java files have balanced delimiters", not unbalanced, ", ".join(unbalanced))

    option_source = (JAVA_ROOT / "me/fallenbreath/tcuhc/options/Options.java").read_text(encoding="utf-8")
    option_ids = re.findall(r'addOption\(new Option\("([A-Za-z][A-Za-z0-9]*)"', option_source)
    c.check("configuration option ids are unique", len(option_ids) == len(set(option_ids)))
    c.check("configuration catalog is not accidentally truncated", len(option_ids) >= 33, str(len(option_ids)))

    command_source = (JAVA_ROOT / "me/fallenbreath/tcuhc/UhcGameCommand.java").read_text(encoding="utf-8")
    command_literals = set(re.findall(r'literal\("([A-Za-z]+)"\)', command_source))
    expected = {"version", "select", "deathpos", "config", "reset", "regen", "start", "forceStart", "cancelStart", "stop", "option", "cancelRegen", "adjust", "givemorals", "debug", "preset", "buffer"}
    c.check("public command tree contains every documented command group", expected <= command_literals, ", ".join(sorted(expected - command_literals)))

    for language in ("en.md", "zh-CN.md"):
        doc_path = ROOT / "docs/commands" / language
        text = doc_path.read_text(encoding="utf-8") if doc_path.is_file() else ""
        missing = [f"/uhc {name}" for name in sorted(expected) if f"/uhc {name}" not in text]
        c.check(f"{language} documents every command group", not missing, ", ".join(missing))
        missing_options = [name for name in option_ids if f"`{name}`" not in text]
        c.check(f"{language} lists every option id", not missing_options, ", ".join(missing_options))

    print(f"\n{c.total - len(c.failures)}/{c.total} repository checks passed.")
    if c.failures:
        print("Failed: " + ", ".join(c.failures))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
