# Verification results — pre-generation buffer

## Result

Fast contract verification and the full Gradle build pass.

| Check | Result | Evidence |
| --- | --- | --- |
| Buffer contract suite | PASS | 39 passed, 0 failed |
| Full Gradle build | PASS | `BUILD SUCCESSFUL`; 8 tasks completed/up to date |
| Repository checks | PASS | 17 passed, 0 failed |
| Previous issue-fix regression suites | PASS | 97 assertions across three suites |
| Mixin registration audit | PASS | 46 registered / 46 on disk |
| Diff whitespace check | Scoped warning | only pre-existing/user-owned trailing whitespace in `docs/design_principle/06-pre-gen-buffer.md` |
| Dedicated-server buffer smoke | PASS | server reached `Done`; 8 command/persistence/shutdown checks passed in a disposable copy |

Commands run from the repository root on 2026-09-14:

```powershell
python docs/agent_run/2026-09-14-pre-gen-buffer/tests/run_all.py
./gradlew.bat build --console=plain
python tests/test_repository.py
python docs/agent_run/2026-09-12-issue-fixes/tests/run_all.py
python scripts/audit_mixins.py
git diff --check
```

## Covered behavior

`test_buffer_contract.py` verifies 39 connected implementation invariants,
including exactly three slots, bounded paths, default-off scheduling, the idle
gate, state transitions, canonical SHA-256 preset fingerprints, atomic manifest
writes, safe world deletion, staged activation and rollback, previous-active
slot retirement, command permissions/surface, two-step activation confirmation,
and integration with startup and pregeneration completion.

The suite and integration review exposed twelve defects or omissions which were
fixed before the passing run:

1. Fingerprinting raw `.properties` bytes made an unchanged preset stale because
   `Properties.store` changes its timestamp comment. Fingerprinting now uses a
   sorted canonical key/value representation.
2. `/uhc buffer status` and scoped confirmation for `/uhc buffer use` were
   absent. Both are now represented in the Brigadier tree; confirmation binds
   both preset and slot.
3. A crash after writing `active-job.properties` but before switching
   `server.properties` could cause the original world to be pregenerated and
   mislabeled as a ready slot. Startup now validates both selected level and
   installed preset, then rolls back and marks the slot failed on mismatch.
4. A malformed generation marker could have applied unsafe default rollback
   values. Invalid markers are now quarantined without rewriting the live
   server or UHC configuration.
5. A preset overwritten during generation could have labeled old terrain with
   the new fingerprint. Each slot now freezes `preset.properties` and its
   fingerprint at job start, generates and activates from that snapshot, and
   becomes `STALE` when the named preset later differs.
6. Automatic filling initially ran one second after startup, which could restart
   a newly activated world before players connected. Startup, enabling, and
   interval changes now begin a full configured waiting interval.
7. Generation and activation I/O after staging a marker was not entirely inside
   the immediate rollback boundary. All post-marker writes now roll back on any
   exception and retain durable recovery evidence if rollback itself fails.
8. Activation recovery trusted marker paths and metadata without validating the
   target world, preload marker, snapshot fingerprint, or installed options.
   Recovery now validates all of them before retiring a previous active slot;
   malformed markers are quarantined.
9. The Windows smoke harness used a bare Gradle path, GBK console output, and
   single-process cleanup. It now uses the explicit wrapper path, UTF-8 output,
   and process-tree cleanup.
10. Generation tried to create the parent of a flat `uhc_buffer_*` path; that
    parent is `null`, so every real fill would fail before restart. The needless
    call was removed and is now covered explicitly.
11. Completion trusted a job that had only been validated at startup. It now
    revalidates the slot number, immutable snapshot fingerprint, and selected
    world before writing `READY`.
12. Clearing a slot checked active generation but not a pending activation.
    Both durable job markers now prevent deletion of their target slot.

## Runtime smoke result

`tests/smoke_buffer_server.py` is runnable and checks the real dedicated-server
command-to-filesystem boundary: preset creation, status/list, slot naming,
interval persistence, enable/disable persistence, absence of accidental active
jobs, clean shutdown, and fatal/mixin/command errors.

It was run from a disposable copy because `run/` contains an existing world,
presets, logs, and restart helpers. The script requires `--allow-local` and
deletes that copy's `run/` before creating its isolated fixture. Run it only in
a fresh/disposable checkout or after deliberately backing up that directory:

```powershell
python docs/agent_run/2026-09-14-pre-gen-buffer/tests/smoke_buffer_server.py --allow-local
```

Generation and activation intentionally cross JVM restart boundaries. The
destructive end-to-end activation and crash-interruption procedures remain in
`TEST_PLAN.md`; they require a disposable server world and a working restart
helper.
