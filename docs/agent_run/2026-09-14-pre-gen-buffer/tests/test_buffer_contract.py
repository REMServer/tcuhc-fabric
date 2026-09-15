#!/usr/bin/env python3
"""Behavioral source-contract checks for the pre-generation buffer.

These checks intentionally inspect whole method bodies and relationships between
them. A class containing the right vocabulary but wiring it in the wrong order
does not pass. The dedicated-server test covers the command-to-disk boundary.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
JAVA = ROOT / "src/main/java/me/fallenbreath/tcuhc"
MANAGER_PATH = JAVA / "pregen/PreGenBufferManager.java"
COMMAND_PATH = JAVA / "UhcGameCommand.java"
GAME_PATH = JAVA / "UhcGameManager.java"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def method(source: str, marker: str) -> str:
    start = source.find(marker)
    if start < 0:
        return ""
    opening = source.find("{", start)
    if opening < 0:
        return ""
    depth = 0
    state = "code"
    escaped = False
    for index in range(opening, len(source)):
        char = source[index]
        nxt = source[index + 1] if index + 1 < len(source) else ""
        if state in ("string", "char"):
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif (state == "string" and char == '"') or (state == "char" and char == "'"):
                state = "code"
            continue
        if state == "line-comment":
            if char == "\n":
                state = "code"
            continue
        if state == "block-comment":
            if char == "*" and nxt == "/":
                state = "block-comment-end"
            continue
        if state == "block-comment-end":
            state = "code"
            continue
        if char == "/" and nxt == "/":
            state = "line-comment"
        elif char == "/" and nxt == "*":
            state = "block-comment"
        elif char == '"':
            state = "string"
        elif char == "'":
            state = "char"
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    return ""


class Results:
    def __init__(self) -> None:
        self.passed = 0
        self.failed = 0

    def check(self, name: str, condition: bool, detail: str = "") -> None:
        if condition:
            self.passed += 1
            print(f"PASS  {name}")
        else:
            self.failed += 1
            print(f"FAIL  {name}{': ' + detail if detail else ''}")


def ordered(body: str, *tokens: str) -> bool:
    cursor = -1
    for token in tokens:
        cursor = body.find(token, cursor + 1)
        if cursor < 0:
            return False
    return True


def main() -> int:
    r = Results()
    manager = read(MANAGER_PATH)
    command = read(COMMAND_PATH)
    game = read(GAME_PATH)
    r.check("buffer manager exists", bool(manager), str(MANAGER_PATH.relative_to(ROOT)))
    if not manager:
        return 1

    listing = method(manager, "public List<SlotInfo> list(")
    bounds = method(manager, "private static void checkSlot(")
    r.check("slot count is exactly three", bool(re.search(r"SLOT_COUNT\s*=\s*3\s*;", manager)))
    r.check(
        "listing emits every bounded slot",
        "slot = 1" in listing and "slot <= SLOT_COUNT" in listing and "new SlotInfo" in listing,
    )
    r.check(
        "storage boundary rejects slots outside 1..SLOT_COUNT",
        "slot < 1" in bounds and "slot > SLOT_COUNT" in bounds and "IllegalArgumentException" in bounds,
    )

    load_config = method(manager, "private void loadConfig(")
    save_config = method(manager, "private void saveConfig(")
    scheduler = method(manager, "public void installScheduler(")
    automatic = method(manager, "private void tryStartAutomaticFill(")
    idle = method(manager, "private boolean isIdle(")
    r.check(
        "missing config defaults disabled and is persisted",
        '"false"' in load_config and "Boolean.parseBoolean" in load_config
        and "Boolean.toString(enabled)" in save_config
        and any(f'getProperty("{key}"' in load_config and f'setProperty("{key}"' in save_config
                for key in ("bufferEnabled", "enabled")),
    )
    r.check(
        "scheduler delegates to the guarded automatic fill",
        "Task.TaskTimer" in scheduler and "tryStartAutomaticFill()" in scheduler,
    )
    r.check(
        "automatic fill honors a full interval after startup or enable",
        "lastAutomaticCheckMillis = System.currentTimeMillis()" in scheduler
        and "lastAutomaticCheckMillis = System.currentTimeMillis()" in method(manager, "public void setEnabled("),
        "a one-second first fill can immediately restart a newly activated world",
    )
    r.check(
        "automatic fill is gated by enabled, no generation/activation job, and idle",
        "!enabled" in automatic and "ACTIVE_JOB" in automatic
        and "ACTIVATION_JOB" in automatic and "!isIdle()" in automatic,
    )
    r.check(
        "idle means no players, match, or foreground pregeneration",
        "getCurrentPlayerCount() == 0" in idle
        and "!gameManager.isGamePlaying()" in idle
        and "!gameManager.isPregenerating()" in idle,
    )

    generate = method(manager, "public void generate(")
    complete = method(manager, "public boolean onPregenerationComplete(")
    active_generation = method(manager, "public boolean isActiveGeneration(")
    constructor = method(manager, "public PreGenBufferManager(")
    generation_recovery = method(manager, "private void recoverInterruptedGenerationSetup(")
    state = method(manager, "private static String slotState(")
    failure = method(manager, "private void markFailed(")
    r.check(
        "generation records GENERATING before changing level and restarting",
        ordered(generate, 'setProperty("state", "GENERATING")', "writeProperties(slotMetadataPath", "writeLevelName(", "restartServer("),
    )
    r.check(
        "flat buffer world creation never dereferences a missing parent path",
        "createDirectories(world.getParent())" not in generate,
        "Path.of(\"uhc_buffer_<preset>_<slot>\").getParent() is null",
    )
    r.check(
        "only completion promotes a slot to READY with identity and fingerprint",
        all(token in complete for token in (
            'setProperty("state", "READY")', 'setProperty("generatorIdentity"',
            'setProperty("presetFingerprint"', "writeProperties(slotMetadataPath",
        ))
        and 'setProperty("state", "READY")' not in generate,
    )
    r.check(
        "completion revalidates its slot, snapshot fingerprint, and selected world",
        "OptionsPreset.checkName(preset)" in complete and "checkSlot(slot)" in complete
        and "propertiesFingerprint(slotPresetPath(preset, slot))" in complete
        and "readLevelName()" in complete and "worldName(preset, slot)" in complete,
        "a job marker or snapshot can be altered while generation is running",
    )
    r.check(
        "generation freezes the preset snapshot and carries its start-time fingerprint to READY",
        "slotPresetPath(preset, slot)" in generate
        and ordered(generate, "Files.copy(OptionsPreset.getFile", "propertiesFingerprint(presetSnapshot)",
                    'job.setProperty("presetFingerprint"', "writeProperties(ACTIVE_JOB")
        and 'job.getProperty("presetFingerprint", "")' in complete
        and 'meta.setProperty("presetFingerprint", fingerprint)' in complete,
        "overwriting a preset mid-generation must make the resulting old-terrain slot STALE",
    )
    r.check(
        "READY requires a real pregenerated world",
        '"READY".equals(state)' in state and "Files.isDirectory(world)" in state
        and 'world.resolve("preload")' in state and "slotPresetPath(preset, slot)" in state
        and "propertiesFingerprint(" in state and 'return "FAILED"' in state,
    )
    r.check("preset mismatch reports STALE", "presetFingerprint(preset)" in state and 'return "STALE"' in state)
    r.check("exceptions can persist FAILED", 'setProperty("state", "FAILED")' in failure and "markFailed(" in complete)
    direct_active_validation = (
        "readLevelName()" in active_generation and "worldName(" in active_generation
        and "preset" in active_generation and "slot" in active_generation
        and ("restoreJobFiles(" in active_generation or "recoveryRestartNeeded" in active_generation)
    )
    startup_generation_recovery = (
        "recoverInterruptedGenerationSetup()" in constructor
        and "readLevelName()" in generation_recovery and "worldName(" in generation_recovery
        and ("restoreJobFiles(" in generation_recovery or "writeLevelName(" in generation_recovery)
        and ("markFailed(" in generation_recovery or "recoveryRestartNeeded" in generation_recovery)
    )
    r.check(
        "partial GENERATING setup cannot pregenerate the original world as the slot",
        direct_active_validation or startup_generation_recovery,
        "ACTIVE_JOB may exist before server.properties has been switched to its buffer world",
    )
    r.check(
        "a corrupt generation marker is quarantined without rewriting live configuration",
        "if (!jobValid)" in generation_recovery
        and "active-job.invalid.properties" in generation_recovery
        and ordered(generation_recovery, "if (!jobValid)", "Files.move(ACTIVE_JOB", "return;", "restoreJobFiles(job)"),
        "untrusted marker defaults must not delete uhc.properties or select an arbitrary world",
    )

    fingerprint = method(manager, "private static String presetFingerprint(")
    properties_fingerprint = method(manager, "private static String propertiesFingerprint(")
    r.check(
        "fingerprint is canonical over preset values, not timestamped file bytes",
        "propertiesFingerprint(" in fingerprint and "MessageDigest" in properties_fingerprint
        and "stringPropertyNames()" in properties_fingerprint
        and "Files.readAllBytes" not in properties_fingerprint,
        "Properties.store rewrites its timestamp comment even when option values are unchanged",
    )

    for public_method in ("public void generate(", "public void use(", "public void name(", "public void clear("):
        body = method(manager, public_method)
        label = public_method.removeprefix("public void ").split("(", 1)[0]
        r.check(
            f"{label} validates both preset and slot before filesystem work",
            ordered(body, "checkSlot(slot)", "OptionsPreset.checkName(preset)"),
        )
    clear = method(manager, "public void clear(")
    r.check(
        "clear refuses both generating and pending-activation slots",
        "ACTIVE_JOB" in clear and "ACTIVATION_JOB" in clear
        and "job.getProperty(\"slot\")" in clear and "activation.getProperty(\"slot\")" in clear,
    )

    write_props = method(manager, "private static void writeProperties(")
    delete_world = method(manager, "private static void deleteWorldTree(")
    r.check(
        "metadata writes use replaceable temp files",
        'resolveSibling(path.getFileName() + ".tmp")' in write_props
        and "StandardCopyOption.ATOMIC_MOVE" in write_props
        and "StandardCopyOption.REPLACE_EXISTING" in write_props,
    )
    r.check(
        "world deletion is restricted to direct buffer-world children",
        "safeRoot.getParent().equals(cwd)" in delete_world
        and 'startsWith("uhc_buffer_")' in delete_world,
    )

    use = method(manager, "public void use(")
    recover = method(manager, "private void recoverInterruptedActivation(")
    restore_activation = method(manager, "private static void restoreActivationFiles(")
    r.check(
        "use accepts only an idle READY slot and stages activation before restart",
        '"READY".equals(slotState' in use and "!isIdle()" in use
        and ordered(use, "writeProperties(ACTIVATION_JOB", "slotPresetPath(preset, slot)", "writeLevelName", "restartServer("),
    )
    r.check(
        "failed restart rolls options, level name, and activation marker back",
        "catch (Exception" in use and "oldOptions" in use and "oldLevelName" in use
        and ordered(use, "catch (Exception", "writeLevelName(oldLevelName)", "Files.deleteIfExists(ACTIVATION_JOB)"),
    )
    r.check(
        "startup recovery either commits ACTIVE or restores the previous selection",
        'setProperty("state", "ACTIVE")' in recover
        and "restoreActivationFiles(activation)" in recover
        and "ACTIVATION_OPTIONS_BACKUP" in restore_activation
        and "writeLevelName(activation.getProperty(\"originalLevelName\"" in restore_activation
        and "recoveryRestartNeeded = true" in recover,
    )
    r.check(
        "activation recovery validates marker identity, slot data, and installed options",
        "OptionsPreset.checkName(" in recover and "checkSlot(slot)" in recover
        and "worldName(preset, slot)" in recover and "slotPresetPath(preset, slot)" in recover
        and 'world.resolve("preload")' not in recover  # target path is checked directly below
        and "Files.isRegularFile(Path.of(target).resolve(\"preload\"))" in recover
        and "sameProperties(OPTIONS_FILE, snapshot)" in recover
        and "activation-job.invalid.properties" in recover,
    )
    r.check(
        "committing a new ACTIVE slot safely consumes the previous closed slot",
        '"ACTIVE".equals(info.state)' in recover
        and ordered(recover, "deleteWorldTree(", "deleteTree(slotDirectory("),
    )

    command_tree = method(command, "public static void registerCommand(")
    buffer_tree = command_tree[command_tree.find('literal("buffer")'):] if 'literal("buffer")' in command_tree else ""
    required_commands = {"status", "enable", "disable", "list", "interval", "generate", "name", "use", "clear"}
    literals = set(re.findall(r'literal\("([A-Za-z]+)"\)', buffer_tree))
    r.check("/uhc buffer is registered", bool(buffer_tree))
    r.check("buffer commands are permission-level 2", "requires(UhcGameCommand::isOp)" in buffer_tree)
    r.check(
        "operator surface covers status, control, slots, and activation",
        required_commands <= literals,
        "missing: " + ", ".join(sorted(required_commands - literals)),
    )
    use_command = method(command, "private static int bufferUse(")
    r.check(
        "activation is two-step and scoped to the same preset/slot",
        "bufferUsePresetPending" in use_command and "bufferUseSlotPending" in use_command
        and "confirmed" in use_command and "buffer().use(" in use_command,
    )

    server_init = method(game, "public void onServerInited(")
    r.check("scheduler is installed from server initialization", "installScheduler()" in server_init)
    r.check(
        "an active buffer job forces generation even when normal startup pregeneration is off",
        "preGenBufferManager.isActiveGeneration()" in server_init
        and 'getBooleanOptionValue("pregenerateOnStart")' in server_init,
    )
    r.check(
        "normal pregeneration completion notifies the active buffer job",
        "onPregenerationComplete()" in game,
    )

    print(f"\n{r.passed} passed, {r.failed} failed")
    return 1 if r.failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
