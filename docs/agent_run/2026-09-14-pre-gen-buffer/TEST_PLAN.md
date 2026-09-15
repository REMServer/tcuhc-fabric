# Test plan — pre-generation buffer

## Fast contract suite

Run:

```powershell
python docs/agent_run/2026-09-14-pre-gen-buffer/tests/run_all.py
```

The suite inspects complete Java method bodies (comments and literals removed)
and checks linked behavior rather than the mere presence of names. It covers:

- exactly three bounded slots per preset;
- default-off persistence and an enabled/idle scheduler gate;
- explicit non-ready and ready states, with `READY` written only after a
  successful capture;
- stable preset fingerprints and stale-ready detection;
- validated preset names and slot bounds at the storage boundary;
- restart-boundary activation with confirmation, staging/backup recovery, and
  slot consumption;
- the complete permission-gated `/uhc buffer` operator surface.

## Dedicated-server smoke

Run on a disposable checkout only:

```powershell
python docs/agent_run/2026-09-14-pre-gen-buffer/tests/smoke_buffer_server.py --allow-local
```

This test replaces the local `run/` directory. The opt-in guard is intentional.
It starts a fresh dedicated server, saves a preset through the real command
dispatcher, toggles the buffer, changes its interval, names an empty slot,
and inspects the persisted files. It then stops the server cleanly. The
test proves command-to-disk integration and rejects fatal/mixin errors; it does
not activate a generated world because doing so intentionally replaces the
active save on the next restart.

## Activation scenario (manual, destructive)

On a server whose world has been backed up:

1. `/uhc buffer generate smoke 1`; wait until `/uhc buffer list smoke` reports
   slot 1 `READY`.
2. `/uhc buffer name smoke 1 semifinal`; list again and verify the display name.
3. `/uhc preset save smoke overwrite`; verify the ready slot becomes `STALE` if
   generation-affecting options changed, and otherwise remains `READY`.
4. Generate a compatible slot, run `/uhc buffer use smoke 1`, then the exact
   confirmation command printed by the server.
5. Restart. Verify the selected world opens, the matching preset snapshot is
   active, slot 1 reports `ACTIVE`, and activation staging/backup markers are
   absent.
6. Interrupt a separate activation between staging its marker/configuration and
   completing the restart; restart and verify recovery deterministically rolls
   forward or rolls back without losing either world.

The last two recovery cases are deliberately manual because they change the
operator's selected world and process lifetime.
