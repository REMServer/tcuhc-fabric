# TC-UHC Design Principles

Derived by reading the whole `tcuhc` package on branch `1.21.1`
(107 Java files / ~12.0k lines, plus the `data/tcuhc` datapack).

Audited against **`3294174` (v1.2.8)**. When resyncing with
`origin/1.21.1`, re-check `05-invariants-and-findings.md` — it is the document
that goes stale fastest.

These documents describe **how this mod is built and why**, so that new work
stays consistent with the existing grain instead of fighting it.

| Doc | Covers |
| --- | --- |
| [01-architecture.md](01-architecture.md) | Runtime shape: singletons, the tick pump, the Task scheduler, the hook surface |
| [02-gameplay-and-config.md](02-gameplay-and-config.md) | Match lifecycle, modes, teams, the Option/book configuration system |
| [03-worldgen.md](03-worldgen.md) | Structures, features, MARINE generator, pregeneration, deferred placement |
| [04-mixin-and-porting.md](04-mixin-and-porting.md) | Mixin conventions and the 1.18 → 1.21.1 migration rules already applied |
| [05-invariants-and-findings.md](05-invariants-and-findings.md) | Hard invariants, plus concrete defects found during this analysis |
| [06-pre-gen-buffer.md](06-pre-gen-buffer.md) | Requirements and scope for per-preset pre-generated world slots |

## The five principles in one screen

1. **One server, one game, one instance.** `UhcGameManager.instance` is a
   process-global singleton created from the `MinecraftServer` constructor.
   Every subsystem reaches state through it. Do not add a second source of truth.
2. **Vanilla is edited by mixin, never forked.** The mod ships no custom blocks
   or items. Behaviour changes are surgical injections into vanilla classes;
   content changes are datapack JSON under `data/tcuhc`.
3. **Everything periodic is a `Task`.** There is no scheduler besides
   `Taskable`/`Task`. Anything that needs to happen later, repeatedly, or
   "once the player exists" is a `Task` attached to a `Taskable` owner.
4. **Configuration is data the operator edits in-game.** Options are typed,
   self-describing, persisted to `uhc.properties`, and rendered as a clickable
   written book. Adding a knob means adding one `Option`, not new plumbing.
5. **Worldgen mutation is deferred to the server thread.** Chunk generation runs
   off-thread; block entities are not ready during it. Every placement that needs
   a `BlockEntity` is queued and flushed in `END_SERVER_TICK`.

## Reading order for a new contributor

`TcUhcMod` → `MinecraftServerMixin` → `UhcGameManager` → `UhcPlayerManager`
→ `options/Options` → `task/Task` → whichever subsystem you are touching.
