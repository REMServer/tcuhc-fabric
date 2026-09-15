# Plan — pre-generation buffer slots

- **Date:** 2026-09-14
- **Branch:** `1.21.1`
- **Base commit:** `d4e5790`
- **Design:** [`06-pre-gen-buffer.md`](../../design_principle/06-pre-gen-buffer.md)
- **Existing user change:** the design document was modified before this run and
  is preserved as user-owned work.

## Outcome

For every saved `/uhc preset`, maintain exactly three world-buffer slots. When
the feature is enabled, idle capacity may fill empty slots from that preset.
Operators can inspect slots, give them display names, explicitly request a fill,
and activate a ready slot. A selected slot is `ACTIVE` while its world is open;
after the operator switches to another ready slot, the closed former slot is
retired to `EMPTY` and a later idle cycle may refill it.

The feature is disabled by default and runs entirely as part of the Minecraft
server/restart lifecycle. No MCDR, Prime Backup, daemon, or separately managed
backend session is introduced.

## Required behavior

1. Slot identity is `(preset name, slot 1..3)`; display names are metadata and
   never filesystem paths.
2. A ready slot records the complete saved preset snapshot and a stable
   generation fingerprint. Editing/overwriting the preset makes old slots stale
   instead of silently treating them as compatible.
3. Slot states are explicit: `EMPTY`, `GENERATING`, `READY`, `STALE`, `FAILED`,
   and `ACTIVE`. Interrupted work must not appear ready.
4. Generation only starts when the feature is enabled and the server is idle by
   a documented definition. Manual fill remains available to an operator.
5. Minecraft never has two live worlds in one `MinecraftServer`. Offline world
   directory moves/copies happen only after the server releases the active save.
6. Activating a ready slot is confirmation-gated, preserves or restores the
   current configuration consistently, consumes the selected slot, and restarts
   into the selected world.
7. All paths are derived from validated preset names and bounded slot numbers.
   Moves use staging/backup names so a failed operation does not destroy the
   only usable world.

## Proposed operator interface

The exact Brigadier layout may be adjusted during compilation, but the shipped
surface must cover these operations:

```text
/uhc buffer status
/uhc buffer enable | disable
/uhc buffer list [preset]
/uhc buffer name <preset> <slot> <display-name>
/uhc buffer generate <preset> [slot]
/uhc buffer use <preset> <slot> [confirm]
```

Commands are permission-level 2. `status`/`list` must be usable from the server
console. Destructive activation uses the repository's existing two-step
confirmation pattern.

## Idle policy

The default is off. When enabled, a periodic interval option controls scans for
missing slots. "Idle" initially means no match is playing, configuration/start
is not in progress, no foreground pregeneration is running, and no players are
online. This conservative policy prevents background filling from competing
with a hosted game. Manual `generate` can request the next safe generation
cycle, but cannot bypass world-lifecycle safety.

## Task allocation

| Task | Owner | Deliverable | Gate |
| --- | --- | --- | --- |
| **C1 — storage/lifecycle implementation** | subagent `buffer_implementation` | production Java/config changes for three-slot manifests, commands, scheduling, generation and activation lifecycle | `gradlew build`; no open-world replacement; default off |
| **V1 — independent automated verification** | subagent `buffer_verification` | runnable tests in this session, including path/state/fingerprint/command invariants and a live-server driver | tests fail on representative broken implementations and pass on integrated code |
| **I1 — integration review** | primary agent | reconcile design and implementation, inspect all diffs, run repository regression tests and mixin audit when applicable | clean scoped diff; user edits preserved |
| **V2 — runtime verification** | primary agent | build plus dedicated-server smoke test; execute safe command/state scenarios possible without a vanilla client | server reaches `Done`; no fatal/mixin errors; documented remaining manual checks |
| **D1 — handoff documentation** | primary agent | update this run with final commands, evidence, limitations, and recovery procedure | another agent/operator can repeat the run |

## Verification matrix

| Risk | Automated check | Live/server check |
| --- | --- | --- |
| More/fewer than three slots | manifest/storage API test | `/uhc buffer list <preset>` shows 1–3 |
| Feature runs when disabled | option default and scheduler guard test | idle server creates no slot work while disabled |
| Preset changed after generation | fingerprint mismatch fixture | overwrite preset; old ready slot reports `STALE` |
| Unsafe names/path traversal | invalid name/display-name cases | commands reject invalid preset/slot inputs |
| Interrupted generation marked ready | staged-state fixture | stop during fill; restart reports recoverable non-ready state |
| Active world overwritten while open | lifecycle/static invariant | activation requires confirmation and restart boundary |
| Selected settings differ from world | snapshot/fingerprint assertions | after activation, config and generator identity match slot metadata |
| Slot not consumed | state transition test | used slot becomes empty/consumed and may refill later |
| Existing gameplay regresses | repository test suite/build | dedicated server boots; normal `/uhc` commands remain registered |

## Planned verification commands

```powershell
python docs/agent_run/2026-09-14-pre-gen-buffer/tests/run_all.py
python scripts/audit_mixins.py
./gradlew.bat build
python tests/smoke_server.py --allow-local
```

The verification subagent may add a purpose-built server scenario. Any script
that mutates `run/` must require an explicit opt-in flag, use a dedicated
throwaway level name, and describe what it removes or replaces.

## Non-goals

- Multiple simultaneous Minecraft server processes.
- Generating a second live dimension/save inside the active server.
- MCDR or Prime Backup integration.
- Automatic scheduling based on CPU utilization, terminal focus, or OS-specific
  telemetry in the first implementation; the conservative in-game idle signal
  is deterministic and portable.
- An unlimited slot count; the contract is three per preset.

## Recovery requirement

Before activation, the active world must have a deterministic backup/staging
location. On startup, incomplete activation markers must be detected and either
rolled forward or rolled back without guessing. Recovery steps and directory
layout will be documented after the production implementation fixes their exact
names.
