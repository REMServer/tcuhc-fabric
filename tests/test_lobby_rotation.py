#!/usr/bin/env python3
"""Exercise the production rotation journal with real files and JVM restarts."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA_BIN = Path(os.environ['JAVA_HOME']) / 'bin' if 'JAVA_HOME' in os.environ else None
HARNESS = '''
import me.fallenbreath.tcuhc.util.LobbyRotation;
import java.nio.file.*;
public class LobbyRotationTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        Path world = Path.of(args[1]).resolve("world");
        if (args[0].equals("resume")) {
            check(LobbyRotation.current(world).equals("the_valley"));
            LobbyRotation.onWorldOpen(world, true);
            check(LobbyRotation.current(world).equals("sak_auth"));
            LobbyRotation.onWorldOpen(world, true);
            check(LobbyRotation.current(world).equals("sak_auth"));
            System.out.println("Passed " + checks + " cross-JVM recovery checks");
            return;
        }
        Files.createDirectories(world);
        check(LobbyRotation.current(world).equals("the_valley"));
        check(!Files.exists(LobbyRotation.stateFile(world)));
        check(!LobbyRotation.stateFile(world).startsWith(world));
        check(LobbyRotation.stateFile(world.resolve(".")).equals(LobbyRotation.stateFile(world)));
        LobbyRotation.onWorldOpen(world, false);
        check(!Files.exists(LobbyRotation.stateFile(world)));
        check(LobbyRotation.prepare(world, "the_valley").equals("sak_auth"));
        check(LobbyRotation.current(world).equals("the_valley"));
        // A failed restart/deletion leaves the preload marker: don't consume the next lobby.
        LobbyRotation.onWorldOpen(world, false);
        check(LobbyRotation.current(world).equals("the_valley"));
        LobbyRotation.onWorldOpen(world, true);
        check(LobbyRotation.current(world).equals("the_valley"));
        LobbyRotation.prepare(world, "the_valley");
        LobbyRotation.cancel(world);
        LobbyRotation.onWorldOpen(world, true);
        check(LobbyRotation.current(world).equals("the_valley"));
        for (String expected : new String[]{"sak_auth", "ic_waiting", "iceland", "the_valley"}) {
            String current = LobbyRotation.current(world);
            check(LobbyRotation.prepare(world, current).equals(expected));
            check(LobbyRotation.prepare(world, current).equals(expected));
            check(LobbyRotation.current(world).equals(current));
            Files.delete(world);
            LobbyRotation.onWorldOpen(world, true);
            check(LobbyRotation.current(world).equals(expected));
            LobbyRotation.onWorldOpen(world, true); // retry after a crash during world deletion
            check(LobbyRotation.current(world).equals(expected));
            Files.createDirectories(world);
            LobbyRotation.onWorldOpen(world, false);
            check(LobbyRotation.current(world).equals(expected));
        }
        Path slot = world.getParent().resolve("slots/other-world");
        check(LobbyRotation.current(slot).equals("the_valley"));
        LobbyRotation.prepare(slot, "ic_waiting");
        LobbyRotation.onWorldOpen(slot, true);
        check(LobbyRotation.current(slot).equals("iceland"));
        check(LobbyRotation.current(world).equals("the_valley"));
        byte[] before = Files.readAllBytes(LobbyRotation.stateFile(world));
        try { LobbyRotation.prepare(world, "invalid"); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }
        check(java.util.Arrays.equals(before, Files.readAllBytes(LobbyRotation.stateFile(world))));
        Files.writeString(LobbyRotation.stateFile(slot), "current=invalid\\n");
        try { LobbyRotation.current(slot); throw new AssertionError(); }
        catch (java.io.UncheckedIOException expected) { checks++; }
        LobbyRotation.prepare(world, "the_valley");
        System.out.println("Passed " + checks + " rotation lifecycle checks");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='tcuhc-lobby-') as folder:
    harness = Path(folder) / 'LobbyRotationTest.java'
    harness.write_text(HARNESS, encoding='utf-8')
    source = ROOT / 'src/main/java/me/fallenbreath/tcuhc/util'
    subprocess.run([str(JAVA_BIN / 'javac') if JAVA_BIN else 'javac', '-encoding', 'UTF-8', '-d', folder,
                    str(source / 'LobbyDefinition.java'), str(source / 'LobbyRotation.java'), str(harness)], check=True)
    for phase in ('prepare', 'resume'):
        subprocess.run([str(JAVA_BIN / 'java') if JAVA_BIN else 'java', '-cp', folder,
                        'LobbyRotationTest', phase, folder], check=True)
