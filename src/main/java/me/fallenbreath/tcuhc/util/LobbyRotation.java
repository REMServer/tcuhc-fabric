package me.fallenbreath.tcuhc.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Arrays;
import java.util.Properties;

/** A per-world journal beside the world directory, so regen cannot delete it. */
public final class LobbyRotation {
	public static final List<String> ORDER = Arrays.stream(LobbyDefinition.values()).map(lobby -> lobby.id).toList();

	private LobbyRotation() {}

	public static String next(String current) {
		int index = ORDER.indexOf(current);
		if (index < 0) throw new IllegalArgumentException("Unknown lobby: " + current);
		return ORDER.get((index + 1) % ORDER.size());
	}

	public static Path stateFile(Path world) {
		Path normalized = world.toAbsolutePath().normalize();
		return normalized.resolveSibling(normalized.getFileName() + ".lobby-rotation.properties");
	}

	public static String current(Path world) {
		return read(world).getProperty("current", ORDER.getFirst());
	}

	/** Staging alone never changes the current world, even if the restart fails. */
	public static String prepare(Path world, String current) {
		String next = next(current);
		Properties state = new Properties();
		state.setProperty("current", current);
		state.setProperty("pending", next);
		write(world, state);
		return next;
	}

	public static void cancel(Path world) {
		Properties state = read(world);
		if (state.remove("pending") != null) write(world, state);
	}

	/** Called before deleting a world: consuming the pending entry is idempotent. */
	public static void onWorldOpen(Path world, boolean regenerating) {
		Properties state = read(world);
		String pending = (String) state.remove("pending");
		if (pending == null) return;
		if (regenerating) state.setProperty("current", pending);
		write(world, state);
	}

	private static Properties read(Path world) {
		Properties state = new Properties();
		Path file = stateFile(world);
		if (!Files.exists(file)) return state;
		try (var input = Files.newInputStream(file)) {
			state.load(input);
			for (String key : List.of("current", "pending")) {
				String id = state.getProperty(key);
				if (id != null && !ORDER.contains(id)) throw new IOException("Unknown lobby in " + file + ": " + id);
			}
			return state;
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read lobby rotation " + file, e);
		}
	}

	private static void write(Path world, Properties state) {
		Path file = stateFile(world);
		Path temporary = null;
		try {
			Files.createDirectories(file.getParent());
			temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
			try (var output = Files.newOutputStream(temporary)) {
				state.store(output, "TC UHC lobby rotation; advances only on confirmed regen");
			}
			try {
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot save lobby rotation " + file, e);
		} finally {
			if (temporary != null) {
				try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
			}
		}
	}
}
