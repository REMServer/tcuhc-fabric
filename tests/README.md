# Test suite

Run the fast, dependency-free checks with:

```bash
python tests/run_all.py
```

Run the complete local build with Java 21:

```bash
./gradlew clean build
```

The dedicated-server smoke test is destructive to the development `run` directory and is therefore guarded locally. GitHub Actions runs it in a fresh checkout. To run it deliberately on a disposable local checkout:

```bash
python tests/smoke_server.py --allow-local
```

The suite checks resource JSON, Fabric metadata, Java source invariants, mixin registration, option and command coverage, corrected gameplay algorithms, and pre-generation buffer lifecycle contracts. The Gradle and smoke commands cover compilation, packaging, and server startup. Multiplayer behavior that requires real client connections still needs the manual procedures under `docs/agent_run/`.
