# TC UHC for Fabric

TC UHC is a server-side Fabric mod that manages complete Minecraft Ultra Hardcore matches. It provides team selection, multiple game and battle modes, configurable match timing and borders, custom world generation and loot, world pre-generation, spectator handling, live match administration, and reusable configuration presets. Vanilla clients can join without installing the mod.

This branch targets **Minecraft 1.21.1** and currently builds **TC UHC 1.2.9**.

## Features

- Eight match modes: Normal, Solo, Boss, Ghost, Bomber, King, Hunter, and Ghost Hunter.
- Normal, Marine, and Icarus battle types with mode-specific terrain and equipment behavior.
- In-game books for team selection, match configuration, and administrative adjustment.
- Configurable teams, difficulty, weather, borders, match phases, loot, ores, merchants, mobs, and pre-generation.
- Overworld and optional Nether pre-generation with progress and estimated-time reporting.
- A preset-aware pre-generation buffer with three reusable world slots per saved preset.
- Saved configuration presets with list, inspect, compare, load, and delete operations.
- Custom structures, trades, recipes, loot, death handling, scoring, and post-match spectator support.
- An Iceland NBT lobby with safe spawn positions, creative editing, and protection for its blocks and decorations.
- A match sidebar with minute/second countdowns, alive player/team totals, and compact final boundary information.
- Independent enchanted-book rewards in bonus chests, using battle-specific pools and valid enchantment levels.
- Dedicated-server operation with no client-side mod requirement.

## Requirements

| Component | Version |
|---|---|
| Minecraft Java Edition | 1.21.1 |
| Fabric Loader | 0.15.0 or later |
| Java runtime | 21 |

The required Fabric API modules are bundled into the produced mod JAR by the build configuration.

## Installation

1. Install a Fabric 1.21.1 dedicated server with Java 21.
2. Download the release JAR named `tcuhc-fabric-mc1.21.1-<version>.jar`.
3. Place the JAR in the server's `mods` directory.
4. Start the server. TC UHC creates and maintains `uhc.properties` in the server working directory.
5. Join the server as an operator and run `/uhc config` to prepare the lobby and configure the next match.

Back up existing worlds before using `/uhc regen`; regeneration deliberately replaces the current match worlds.

## Basic operation

The usual match flow is:

1. Run `/uhc config` and set the match options.
2. Have players select a team or observer status using the selection book.
3. Wait for world pre-generation to finish, when enabled.
4. Run `/uhc start` twice to confirm and begin the match.
5. Use `/uhc stop` only when an operator must end a running match.
6. After the result is shown, players remain in Spectator mode to inspect the battlefield. Run `/uhc config` when everyone should return to the lobby and Survival mode.

Complete command documentation is available in [English](docs/commands/en.md) and [简体中文](docs/commands/zh-CN.md).

## Configuration

Settings can be changed through the in-game configuration book or `/uhc option <name> <add|sub|set>`. They are persisted to `uhc.properties`. Some settings have delayed activation:

- World-generation and loot-frequency changes require `/uhc regen`.
- Pre-generation startup settings require a server restart.
- Gameplay settings normally apply to the next match.

Use `/uhc preset save <name>` to save a complete configuration and `/uhc preset load <name>` to restore it. The command manuals list every option, range, default, and preset operation.

## World pre-generation buffer

The optional buffer prepares up to three worlds for each saved preset while the server is idle, so an operator can activate a ready world instead of waiting for pre-generation before a match. It is disabled by default. Enable it with `/uhc buffer enable`, inspect it with `/uhc buffer status`, and activate a ready slot with `/uhc buffer use <preset> <slot>` followed by the displayed confirmation command.

Buffer generation starts only when no players are online, no match is active, and no other pre-generation is running. Filling a slot requires two automatic server restarts; activating one requires a single restart. The server installation must therefore provide the restart/start helpers described in the [recovery and operations guide](docs/agent_run/2026-09-14-pre-gen-buffer/RECOVERY.md). See the buffer sections in the [English command reference](docs/commands/en.md#pre-generation-buffer) or [简体中文命令参考](docs/commands/zh-CN.md#世界预生成缓冲区) for every command, slot state, interval limit, and safety rule.

## Building from source

For the current source branch, see the [Iceland lobby guide](docs/lobby-iceland.md),
[sidebar rules](docs/sidebar.md), and [enchanted-book rewards](docs/enchanted-book-generation.md).
Lobby or display code/template changes require a server restart. Book reward changes
apply only to newly filled bonus chests; existing items are retained. These source
changes do not update the previously published 1.2.9 release artifact.

Java 21 is required. On Windows:

```powershell
.\gradlew.bat clean build
```

On Linux or macOS:

```bash
./gradlew clean build
```

Compiled artifacts are written to `build/libs/`. The deployable JAR for the current version is:

```text
build/libs/tcuhc-fabric-mc1.21.1-1.2.9.jar
```

Change `mod_version` in `gradle.properties` before producing a new release. Release versions must use the stable semantic format `major.minor.patch`, for example `1.2.10`.

## Testing and continuous integration

Run the dependency-free repository tests with:

```bash
python tests/run_all.py
```

Run a complete compile and package check with:

```bash
./gradlew clean build
```

GitHub Actions runs repository checks, the Gradle build, packaging, and a dedicated-server startup smoke test on every push and pull request. See [tests/README.md](tests/README.md) for test scope and local smoke-test safety notes.

## Build and release workflow

The **Build or Release** workflow always validates the version, runs all tests, builds both JARs, and smoke-tests a server. It can be dispatched manually or triggered by pushing a matching `v<version>` tag.

- Select `create_release: false` to compile and retain downloadable workflow artifacts without publishing a release.
- Select `create_release: true` to create a `v<version>` GitHub tag/release and attach the binary and sources JARs after all checks pass.

The workflow rejects malformed versions and refuses to replace an existing release tag. With an authenticated [GitHub CLI](https://cli.github.com/), the same choices can be dispatched locally after committing all changes:

```bash
python scripts/github_release.py --build-only
python scripts/github_release.py --release
```

## Documentation

- [Command reference — English](docs/commands/en.md)
- [命令参考 — 简体中文](docs/commands/zh-CN.md)
- [Design principles](docs/design_principle/README.md)
- [Development and verification notes](docs/agent_run/)
- [Iceland NBT lobby — 简体中文](docs/lobby-iceland.md)
- [Match sidebar — 简体中文](docs/sidebar.md)
- [Enchanted-book rewards — 简体中文](docs/enchanted-book-generation.md)
- [Lobby, sidebar, and reward development record](docs/agent_run/2026-09-16-lobby-change/README.md)

## Credits and license

TC UHC originates from [Gamepiaynmo/TC-UHC](https://github.com/Gamepiaynmo/TC-UHC) and has been maintained and ported by the contributors listed in `fabric.mod.json`.

The project is distributed under the terms in [LICENSE](LICENSE).

---

## 中文简介

TC UHC 是一个服务端 Fabric 模组，用于组织完整的极限生存竞技对局。当前分支支持 Minecraft 1.21.1、Fabric Loader 0.15.0 及以上版本和 Java 21；原版客户端无需安装模组即可加入。可选的世界预生成缓冲区会在服务器空闲时为每个配置预设准备 3 个世界槽位，并允许管理员通过一次重启启用已就绪的世界；该功能默认关闭，填充槽位需要自动重启两次。安装、编译、自动测试和发布流程见上文，全部缓冲区命令、状态与安全限制请参阅[简体中文命令参考](docs/commands/zh-CN.md#世界预生成缓冲区)。

当前源码使用可编辑并受保护的 Iceland NBT 大厅。侧边栏沿用白色标签、红色数值，显示分钟秒数、存活人数与队伍比例，并合并后期边界坐标。奖励箱独立抽取附魔书，不生成效率或耐久，等级不超过原版上限。详细规则和验证范围见[本次开发记录](docs/agent_run/2026-09-16-lobby-change/README.md)；这些修改尚未发布为新的 Release。
