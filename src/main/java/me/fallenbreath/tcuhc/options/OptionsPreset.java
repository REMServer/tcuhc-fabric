package me.fallenbreath.tcuhc.options;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import me.fallenbreath.tcuhc.TcUhcMod;
import me.fallenbreath.tcuhc.UhcGameManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Whole snapshots of the UHC configuration, stored as plain properties files next to
 * uhc.properties.
 *
 * <p>A preset file holds nothing but option id to value, which is exactly what uhc.properties
 * holds, so one can be renamed over the other and everything still works. Nothing else is stored:
 * the name is the file name, the save time is the file's own timestamp, and the summary shown in
 * the listing is derived from the values every time it is asked for. That keeps the file honest -
 * it is also what lets a preset be described without an index that could drift out of sync with
 * the files.
 *
 * <p>Like uhc.properties, the directory is resolved against the working directory, which for a
 * dedicated server is its root.
 */
public final class OptionsPreset {

	private static final Logger LOGGER = LogManager.getLogger();
	private static final String DIRECTORY_NAME = "uhc_presets";
	private static final String SUFFIX = ".properties";

	/**
	 * The one security relevant rule in here. A preset name turns into a file name, so anything
	 * able to walk out of the preset directory - dots, slashes, backslashes, whitespace - has to
	 * be refused. Kept in this class rather than the command layer so it cannot be skipped.
	 */
	private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private OptionsPreset() {}

	public static File getDirectory() {
		return new File(DIRECTORY_NAME);
	}

	/** @return the name, for chaining; throws when it could not be used as a file name */
	public static String checkName(String name) {
		if (name == null || !NAME_PATTERN.matcher(name).matches()) {
			throw new IllegalArgumentException("预设名只能包含字母、数字、下划线、连字符，长度 1~32");
		}
		return name;
	}

	private static File fileOf(String name) {
		return new File(getDirectory(), checkName(name) + SUFFIX);
	}

	/** The validated preset file, for lifecycle features which must install it before a restart. */
	public static File getFile(String name) {
		return fileOf(name);
	}

	public static boolean exists(String name) {
		return fileOf(name).isFile();
	}

	/** Names only, for command completion. Does not open any file. */
	public static List<String> listNames() {
		File[] files = getDirectory().listFiles((dir, fileName) -> fileName.endsWith(SUFFIX));
		if (files == null) {
			return Lists.newArrayList();
		}
		return java.util.Arrays.stream(files)
				.map(file -> file.getName().substring(0, file.getName().length() - SUFFIX.length()))
				.sorted()
				.collect(Collectors.toList());
	}

	/** Every preset, sorted by name, with a summary derived from its contents. */
	public static List<PresetEntry> list() {
		File[] files = getDirectory().listFiles((dir, fileName) -> fileName.endsWith(SUFFIX));
		List<PresetEntry> entries = Lists.newArrayList();
		if (files == null) {
			return entries;
		}
		for (File file : files) {
			String name = file.getName().substring(0, file.getName().length() - SUFFIX.length());
			try {
				entries.add(new PresetEntry(name, summarize(read(name))));
			} catch (Exception e) {
				LOGGER.warn("Failed to read preset {}", file, e);
				entries.add(new PresetEntry(name, "（读取失败）"));
			}
		}
		entries.sort(Comparator.comparing(entry -> entry.name));
		return entries;
	}

	/**
	 * @return option id to raw value, in option registration order followed by any ids this build
	 * does not know (sorted). Unknown ids are kept rather than dropped so that a preset from
	 * another version reports them instead of silently losing them.
	 */
	public static Map<String, String> read(String name) throws IOException {
		File file = fileOf(name);
		if (!file.isFile()) {
			throw new FileNotFoundException("预设 " + name + " 不存在");
		}
		Properties properties = new Properties();
		// Read as UTF-8 instead of the ISO-8859-1 default. Everything written here is pure ASCII
		// anyway, because Properties.store escapes non-ASCII, but this way a hand edited file may
		// also hold Chinese characters directly instead of turning into mojibake.
		try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
			properties.load(reader);
		}

		Map<String, String> values = Maps.newLinkedHashMap();
		for (Option option : Options.instance.getOptionsInOrder()) {
			String raw = properties.getProperty(option.getId());
			if (raw != null) {
				values.put(option.getId(), raw);
			}
		}
		properties.stringPropertyNames().stream()
				.filter(id -> !values.containsKey(id))
				.sorted()
				.forEach(id -> values.put(id, properties.getProperty(id)));
		return values;
	}

	public static void save(String name, Map<String, String> values) throws IOException {
		File directory = getDirectory();
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new IOException("无法创建预设目录 " + directory.getAbsolutePath());
		}
		Properties properties = new Properties();
		values.forEach(properties::setProperty);
		File file = fileOf(name);
		// Stored through an OutputStream on purpose: that is the overload that escapes non-ASCII,
		// keeping the file pure ASCII and thus interchangeable with uhc.properties, which is read
		// back through a plain FileInputStream.
		try (FileOutputStream output = new FileOutputStream(file)) {
			properties.store(output, "UHC preset " + name + " - saved by tcuhc-fabric " + TcUhcMod.getModVersion());
		}
	}

	public static void delete(String name) throws IOException {
		File file = fileOf(name);
		if (!file.isFile()) {
			throw new FileNotFoundException("预设 " + name + " 不存在");
		}
		if (!file.delete()) {
			throw new IOException("无法删除 " + file.getAbsolutePath());
		}
	}

	/**
	 * The short label that follows a preset's name in the listing: game mode and team count,
	 * nothing else. Derived from the values rather than stored, so it cannot disagree with them.
	 *
	 * <p>Deliberately just those two - battle type and terrain type are what {@code /uhc preset
	 * show} is for. Keeping this to a couple of words is what lets the listing fit a name and its
	 * description on a single chat line instead of wrapping.
	 */
	public static String summarize(Map<String, String> values) {
		String mode = values.get("gameMode");
		if (mode == null) {
			return "?";
		}
		if (UhcGameManager.EnumMode.SOLO.toString().equals(mode)) {
			// A team count says nothing in single player mode.
			return "单人模式";
		}
		String teams = values.get("teamCount");
		// A preset that names no team count - a hand written one, in practice - shows the mode on
		// its own rather than a dangling "?队".
		return teams == null ? mode : mode + " " + teams + "队";
	}

	/** The preset file's own timestamp, formatted. "?" when it cannot be read. */
	public static String describeSavedAt(String name) {
		long time = fileOf(name).lastModified();
		return time == 0L ? "?" : TIME_FORMAT.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
	}

	/** What the listing needs about one preset. */
	public static class PresetEntry {
		public final String name;
		public final String summary;

		PresetEntry(String name, String summary) {
			this.name = name;
			this.summary = summary;
		}
	}
}
