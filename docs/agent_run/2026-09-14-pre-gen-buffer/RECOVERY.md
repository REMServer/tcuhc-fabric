# Pre-generation buffer recovery

The buffer records restart work before changing the selected world. On a normal startup it either continues a complete job or rolls an incomplete setup back; manual file operations should be a last resort and must be done only while the Minecraft server is stopped.

## Files and directories

All paths are relative to the dedicated-server working directory.

| Path | Purpose |
|---|---|
| `uhc_pregen_buffer/config.properties` | Persistent automatic-fill switch and check interval. |
| `uhc_pregen_buffer/active-job.properties` | In-progress slot-generation marker, including `preset`, `slot`, `originalLevelName`, and the start-time preset fingerprint. |
| `uhc_pregen_buffer/active-job-uhc.properties` | Original `uhc.properties` saved for a generation job. |
| `uhc_pregen_buffer/activation-job.properties` | Pending slot-activation marker, including the original and target level names. |
| `uhc_pregen_buffer/activation-job-uhc.properties` | Original `uhc.properties` saved for activation rollback. |
| `uhc_pregen_buffer/<preset>/slot-<n>/slot.properties` | Slot state, display name, creation time, preset fingerprint, and world name. |
| `uhc_pregen_buffer/<preset>/slot-<n>/preset.properties` | Complete preset snapshot captured when generation starts and installed again during activation. |
| `uhc_buffer_<preset>_<n>/` | The actual buffered Minecraft world. |

## Expected restart sequences

Generating a slot takes two restarts. The first restart selects `uhc_buffer_<preset>_<n>` and generates that world. When pre-generation completes, the slot is marked `READY`, `uhc.properties` and `level-name` are restored, the generation marker is removed, and the second restart returns to the original world.

Activating a ready slot takes one restart. Before changing `uhc.properties` or `level-name`, the server writes the activation marker and options backup. On the next startup it marks the target slot `ACTIVE`, retires the previously active slot, and removes the activation files. If startup observes that the target was not selected, it restores the recorded original configuration and requests a recovery restart.

## After an interruption

1. Keep the server stopped and copy `uhc_pregen_buffer/`, `uhc.properties`, `server.properties`, and every `uhc_buffer_*` directory to a safe location.
2. Restore or repair the normal `restart-server.bat`, `restart-server.cmd`, or `restart-server.sh` helper. The buffer uses the same helper as `/uhc regen`.
3. Start the server normally once. Leave the job files in place: startup recovery uses them to continue or roll back deterministically and may request one additional restart.
4. After the server settles, run `/uhc buffer status`. A generation interrupted before safe setup is reported as `FAILED`; clear and regenerate that slot. A completed activation is reported as `ACTIVE`.

If startup repeatedly fails before commands become available, stop the server and preserve the evidence above before editing anything. `originalLevelName`, `targetLevelName`, and `optionsExisted` in the job marker identify the intended rollback or roll-forward state. Prefer restoring the recorded original `level-name` and its matching `*-uhc.properties` backup; do not delete either the original world or a `uhc_buffer_*` world until a successful boot confirms which one is active.

`/uhc buffer clear <preset> <slot>` is the supported cleanup path after recovery. It refuses to delete the world currently selected by `server.properties` and refuses an active generation job.
