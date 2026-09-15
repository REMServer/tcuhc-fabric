# TC UHC command reference

This document covers the commands in the current TC UHC build. `<value>` is required and `[value]` is optional. Unless stated otherwise, administrative commands require Minecraft permission level 2. Tab completion is available for option names, player names, preset names, and fixed operations.

## Player commands

| Command | Purpose | Example |
|---|---|---|
| `/uhc` | Display the TC UHC and Minecraft versions. | `/uhc` |
| `/uhc version` | Display the same version information explicitly. | `/uhc version` |
| `/uhc select <team_id>` | Select a team or role before the match. IDs `0`–`7` are red, blue, yellow, green, orange, purple, cyan, and brown; `8` is observer; `9` joins combat/random assignment where supported. | `/uhc select 2` |
| `/uhc deathpos` | Show your recorded death position, when available. | `/uhc deathpos` |

## Configuration and lifecycle commands

| Command | Purpose | Example |
|---|---|---|
| `/uhc config` | Return all players to the lobby, restore Survival mode, and give/open the configuration items for the next match. | `/uhc config` |
| `/uhc configPage <page>` | Open an internal zero-based configuration-book page (`0`–`4`). Intended for book navigation. | `/uhc configPage 2` |
| `/uhc configPageJump <page>` | Open a human-numbered configuration page (`1`–`5`). | `/uhc configPageJump 3` |
| `/uhc configPagePrompt` | Ask the executing player to type a page number in chat. | `/uhc configPagePrompt` |
| `/uhc reset` | Explain the available reset scopes; it does not change settings by itself. | `/uhc reset` |
| `/uhc reset gameplay` | Restore gameplay, timing, team, and server-start options to mod defaults. Legacy alias: `/uhc reset 0`. | `/uhc reset gameplay` |
| `/uhc reset generation` | Restore world-generation frequency options to defaults. Run regeneration for them to affect terrain. Legacy alias: `/uhc reset 1`. | `/uhc reset generation` |
| `/uhc regen` | Request destructive world regeneration. Run the command a second time to confirm. | `/uhc regen` twice |
| `/uhc cancelRegen` | Cancel a pending regeneration confirmation. | `/uhc cancelRegen` |
| `/uhc start` | Request a normal start after readiness checks. Run it a second time to confirm. | `/uhc start` twice |
| `/uhc forceStart` | Request a start that bypasses pre-generation readiness. Run it a second time to confirm. | `/uhc forceStart` twice |
| `/uhc cancelStart` | Cancel either pending start confirmation. | `/uhc cancelStart` |
| `/uhc stop` | Stop the active match. Players remain spectators so they can inspect the battlefield; `/uhc config` returns everyone to the lobby. | `/uhc stop` |

`/uhc regen` deletes and rebuilds the current match worlds. Back up any world data that must be retained. `forceStart` is for diagnostics or deliberate bypasses; incomplete terrain can cause poor gameplay.

## Changing an option

Use `/uhc option <name> <add|sub|set>`.

- `add` advances an enum, enables a boolean, or increases a number by its step.
- `sub` moves backward, disables a boolean, or decreases a number.
- `set` asks an in-game player to enter a value in chat. This interactive form is not available from a console without an online player.

Examples:

```text
/uhc option teamCount add
/uhc option battleType set
# then type MARINE in chat
/uhc option pregenerateOnStart sub
```

Enum inputs accept constant names without case sensitivity. The principal values are `gameMode`: `NORMAL`, `SOLO`, `BOSS`, `GHOST`, `BOMBER`, `KING`, `HUNTER`, `GHOSTHUNTER`; `battleType`: `NORMAL`, `MARINE`, `ICARUS`; `levelType`: `DEFAULT`, `AMPLIFIED`, `LARGEBIOMES`; `difficulty`: `PEACEFUL`, `EASY`, `NORMAL`, `HARD`; and `weather`: `NORMAL`, `CLEAR`, `RAIN`, `THUNDER`. Boolean input accepts `true` or `false` (and the displayed Chinese equivalents).

### Option catalog

| Option ID | Default | Range or values | Meaning |
|---|---:|---|---|
| `gameMode` | `NORMAL` | game-mode enum | Match rules and team formation. |
| `battleType` | `NORMAL` | `NORMAL`, `MARINE`, `ICARUS` | Classic, ocean, or elytra combat. |
| `levelType` | `DEFAULT` | `DEFAULT`, `AMPLIFIED`, `LARGEBIOMES` | Overworld terrain style. |
| `disableOceanBiomes` | `true` | boolean | Replace ocean biomes with land outside Marine mode. |
| `randomTeams` | `true` | boolean | Assign teams randomly instead of manually. |
| `teamCount` | `4` | 2–8, step 1 | Number of teams in Normal mode. |
| `difficulty` | `HARD` | vanilla difficulty | Match difficulty. |
| `weather` | `NORMAL` | weather enum | Forced match weather or normal behavior. |
| `daylightCycle` | `true` | boolean | Enable the day/night cycle. |
| `friendlyFire` | `false` | boolean | Allow damage between teammates. |
| `teamCollision` | `true` | boolean | Allow teammate collision. |
| `greenhandProtect` | `false` | boolean | Reduce early-game damage. |
| `forceViewport` | `true` | boolean | Force dead players to spectate teammates. |
| `deathBonus` | `true` | boolean | Give surviving teammates a temporary death bonus. |
| `TNTBomber` | `false` | boolean | Give starting TNT in Bomber mode. |
| `borderStart` | `2000` | 100–2,000,000, step 100 | Initial world-border size. |
| `borderEnd` | `200` | 10–2,000,000, step 10 | First shrink target. |
| `borderFinal` | `50` | 10–2,000,000, step 10 | Final border target. |
| `gameTime` | `5400` | 0–1,000,000 seconds, step 100 | Total match duration. |
| `borderStartTime` | `1800` | 0–1,000,000 seconds, step 100 | Time when border shrinking starts. |
| `borderEndTime` | `4800` | 0–1,000,000 seconds, step 100 | Time when the first shrink ends. |
| `netherCloseTime` | `4800` | 0–1,000,000 seconds, step 100 | Time when Nether and End access closes. |
| `caveCloseTime` | `5100` | 0–1,000,000 seconds, step 100 | Time when caves close. |
| `greenhandTime` | `4800` | 0–1,000,000 seconds, step 100 | Greenhand protection duration. |
| `merchantFrequency` | `1.0` | 0–10, step 0.05 | Merchant-generation multiplier. |
| `oreFrequency` | `4` | 0–100, step 1 | Variable ore-generation frequency. |
| `chestFrequency` | `1.0` | 0–10, step 0.1 | Bonus-chest frequency. |
| `trappedChestFrequency` | `0.2` | 0–1, step 0.05 | Empty/trapped bonus-chest share. |
| `chestItemFrequency` | `1.0` | 0–10, step 0.1 | Variable chest-loot multiplier. |
| `mobCount` | `70` | 10–300, step 10 | Monster population target. |
| `netherPregenerate` | `true` | boolean | Include the Nether in pre-generation. |
| `pregenerateOnStart` | `true` | boolean | Automatically pre-generate after server startup. |
| `pregenerateParallelism` | `2` | 1–16, step 1 | Concurrent pre-generation work; higher values use more CPU. |

World-generation settings take effect after `/uhc regen`; the three pre-generation settings are read at server startup. Other options normally apply to the next match.

## Presets

| Command | Purpose |
|---|---|
| `/uhc preset` or `/uhc preset list` | List saved presets and identify the current match. |
| `/uhc preset save <name>` | Save all current options. Names use letters, digits, `_`, or `-`. |
| `/uhc preset save <name> overwrite` | Replace an existing preset. |
| `/uhc preset load <name>` | Load a preset. During a match, repeat with `confirm`. |
| `/uhc preset load <name> confirm` | Confirm an in-progress-match preset load. |
| `/uhc preset show <name>` | Show every saved value. |
| `/uhc preset diff <name>` | Compare a preset with current settings. |
| `/uhc preset delete <name>` | Request deletion. |
| `/uhc preset delete <name> confirm` | Confirm deletion of the same preset. |

Example: `/uhc preset save tournament`, followed later by `/uhc preset diff tournament` and `/uhc preset load tournament`.

## Pre-generation buffer

The buffer keeps three world slots for every saved preset. It is disabled by default. When enabled, its first automatic check occurs after one complete interval (300 seconds by default), and subsequent checks use the same delay. Changing the interval immediately restarts that countdown; valid values are 30–86400 seconds. Automatic and manual generation start only while no players are online, no match is active, and no other pre-generation is running, so these lifecycle commands are normally issued from the server console. All buffer commands require permission level 2.

| Command | Purpose |
|---|---|
| `/uhc buffer`, `/uhc buffer status`, or `/uhc buffer list` | Show whether automatic filling is enabled, the current interval, and all three slots for every preset. |
| `/uhc buffer list <preset>` | Show the three slots for one saved preset. |
| `/uhc buffer enable` | Enable automatic filling during idle periods. The first check occurs after the configured interval. |
| `/uhc buffer disable` | Stop starting automatic fills. An already-running generation job is not canceled. |
| `/uhc buffer interval <seconds>` | Set the automatic check interval to 30–86400 seconds and restart its countdown. |
| `/uhc buffer generate <preset> <slot>` | Fill slot `1`, `2`, or `3` manually. The slot must be `EMPTY` or `FAILED`. |
| `/uhc buffer name <preset> <slot> <name>` | Set an ASCII display name using 1–32 letters, digits, `_`, or `-`. The name never becomes a filesystem path. |
| `/uhc buffer use <preset> <slot>` | Request activation of a `READY` slot and print the confirmation command. |
| `/uhc buffer use <preset> <slot> confirm` | Confirm activation. This installs the slot's saved preset snapshot, switches worlds, and restarts the server. |
| `/uhc buffer clear <preset> <slot>` | Permanently delete an eligible slot so it becomes `EMPTY`. Current, generating, and pending-activation slots are protected. |

A fill uses two automatic restarts: the first boots the dedicated server into the slot world and pre-generates it; after completion, the second restores the original world and configuration. Do not start a match during this cycle. Activation uses one restart. The selected slot becomes `ACTIVE`; when a different buffered slot is activated later, the previous active slot is deleted and becomes `EMPTY`, allowing automatic filling to replenish it.

Slot states are `EMPTY` (available), `GENERATING`, `READY`, `STALE` (the saved preset changed after generation), `FAILED`, and `ACTIVE` (the server's selected world). Each generated slot retains the complete preset snapshot and its fingerprint. A stale slot cannot be activated; clear and regenerate it. `/uhc buffer clear` cannot clear the world selected in `server.properties`, an active generation job, or a slot whose activation is pending. Automatic filling also pauses while generation or activation metadata is pending. See [the recovery guide](../agent_run/2026-09-14-pre-gen-buffer/RECOVERY.md) before manually changing buffer files after an interrupted restart.

## Match adjustment and diagnostics

| Command | Purpose |
|---|---|
| `/uhc adjust` | Give/open the live match-adjustment book. |
| `/uhc adjust end` | Remove the adjustment book. |
| `/uhc adjust kill <player>` | Administratively eliminate a game player. |
| `/uhc adjust resu <player>` | Mark an eliminated player alive again. |
| `/uhc adjust respawn <player>` | Resurrect and respawn the player. |
| `/uhc givemorals [owner]` | Give all predefined moral items, or one owner's item, to the executing player. |
| `/uhc debug biome [radius]` | Report nearby biome distribution; radius defaults to 4 and accepts 1–16 chunks. |
| `/uhc debug terrain [radius]` | Report nearby terrain diagnostics; radius defaults to 4 and accepts 1–24 chunks. |
| `/uhc debug terrain <radius> <chunkX> <chunkZ>` | Run terrain diagnostics around explicit chunk coordinates. |

Commands that give books/items or use the executor's position should be run by a player. When issued from the server console, the implementation uses the first online player where possible; it reports an error if no suitable player exists.
