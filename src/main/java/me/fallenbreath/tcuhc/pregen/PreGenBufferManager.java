package me.fallenbreath.tcuhc.pregen;

import me.fallenbreath.tcuhc.UhcGameManager;
import me.fallenbreath.tcuhc.options.OptionsPreset;
import me.fallenbreath.tcuhc.task.Task;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Persistent three-slot buffer for pregenerated worlds.
 *
 * <p>Minecraft cannot safely attach a second overworld to a running dedicated server. A buffer
 * fill is therefore a small, crash-recoverable restart job: the slot world is the only loaded
 * world while {@code TaskPregenerate} runs. No open save is copied or moved.
 */
public final class PreGenBufferManager
{
	public static final int SLOT_COUNT = 3;
	private static final int DEFAULT_INTERVAL_SECONDS = 300;
	private static final int MIN_INTERVAL_SECONDS = 30;
	private static final Path ROOT = Path.of("uhc_pregen_buffer");
	private static final Path CONFIG = ROOT.resolve("config.properties");
	private static final Path ACTIVE_JOB = ROOT.resolve("active-job.properties");
	private static final Path OPTIONS_BACKUP = ROOT.resolve("active-job-uhc.properties");
	private static final Path ACTIVATION_JOB = ROOT.resolve("activation-job.properties");
	private static final Path ACTIVATION_OPTIONS_BACKUP = ROOT.resolve("activation-job-uhc.properties");
	private static final Path OPTIONS_FILE = Path.of("uhc.properties");
	private static final Path SERVER_PROPERTIES = Path.of("server.properties");

	private final UhcGameManager gameManager;
	private boolean enabled;
	private int intervalSeconds;
	private boolean finishingJob;
	private long lastAutomaticCheckMillis;
	private boolean recoveryRestartNeeded;

	public PreGenBufferManager(UhcGameManager gameManager)
	{
		this.gameManager = gameManager;
		loadConfig();
		recoverInterruptedGenerationSetup();
		recoverInterruptedActivation();
	}

	public void installScheduler()
	{
		if (recoveryRestartNeeded)
		{
			gameManager.restartServer("recovering interrupted buffer activation");
			return;
		}
		lastAutomaticCheckMillis = System.currentTimeMillis();
		gameManager.addTask(new Task.TaskTimer(20, 20)
		{
			@Override
			public void onTimer()
			{
				tryStartAutomaticFill();
			}
		});
	}

	public boolean isEnabled() { return enabled; }
	public int getIntervalSeconds() { return intervalSeconds; }

	public void setEnabled(boolean enabled) throws IOException
	{
		this.enabled = enabled;
		if (enabled) lastAutomaticCheckMillis = System.currentTimeMillis();
		saveConfig();
	}

	public void setIntervalSeconds(int seconds) throws IOException
	{
		if (seconds < MIN_INTERVAL_SECONDS || seconds > 86400)
		{
			throw new IllegalArgumentException("检查间隔必须在 30 到 86400 秒之间");
		}
		this.intervalSeconds = seconds;
		lastAutomaticCheckMillis = System.currentTimeMillis();
		saveConfig();
	}

	public boolean isActiveGeneration()
	{
		Properties job = readProperties(ACTIVE_JOB);
		return "GENERATING".equals(job.getProperty("phase"));
	}

	public String activeGenerationDescription()
	{
		Properties job = readProperties(ACTIVE_JOB);
		return job.getProperty("preset", "?") + "/" + job.getProperty("slot", "?");
	}

	/**
	 * ACTIVE_JOB is durable before either configuration file is changed. If a process dies between
	 * those writes, the next JVM must not mistake the host world for the slot world.
	 */
	private void recoverInterruptedGenerationSetup()
	{
		if (!Files.isRegularFile(ACTIVE_JOB)) return;
		Properties job = readProperties(ACTIVE_JOB);
		String preset = job.getProperty("preset", "");
		int slot = -1;
		boolean jobValid = false;
		boolean targetSelected = false;
		boolean presetInstalled = false;
		try
		{
			OptionsPreset.checkName(preset);
			slot = Integer.parseInt(job.getProperty("slot", "-1"));
			checkSlot(slot);
			Path snapshot = slotPresetPath(preset, slot);
			jobValid = Files.isRegularFile(snapshot)
					&& job.getProperty("presetFingerprint", "").equals(propertiesFingerprint(snapshot));
			targetSelected = jobValid && normalizeLevelName(readLevelName()).equals(normalizeLevelName(worldName(preset, slot)));
			presetInstalled = jobValid && sameProperties(OPTIONS_FILE, snapshot);
		}
		catch (Exception e)
		{
			UhcGameManager.LOG.warn("Invalid active pregeneration buffer job; quarantining it", e);
		}
		if (targetSelected && presetInstalled) return;
		try
		{
			if (!jobValid)
			{
				// A corrupt or hand-edited marker is not trustworthy enough to drive changes to
				// uhc.properties or server.properties. Quarantine it and leave both files alone;
				// the backup remains available for an operator to inspect manually.
				Path invalidJob = ROOT.resolve("active-job.invalid.properties");
				Files.move(ACTIVE_JOB, invalidJob, StandardCopyOption.REPLACE_EXISTING);
				UhcGameManager.LOG.error("Quarantined invalid pregeneration buffer job as {}", invalidJob);
				return;
			}
			restoreJobFiles(job);
			Files.deleteIfExists(ACTIVE_JOB);
			Files.deleteIfExists(OPTIONS_BACKUP);
			markFailed(preset, slot, "生成任务启动阶段被中断");
			recoveryRestartNeeded = true;
			UhcGameManager.LOG.warn("Rolled back interrupted buffer generation setup for {}/{}", preset, slot);
		}
		catch (Exception e)
		{
			UhcGameManager.LOG.error("Could not recover interrupted buffer generation setup", e);
		}
	}

	/** Called only after overworld (and optionally nether) pregeneration has completed. */
	public boolean onPregenerationComplete()
	{
		if (!isActiveGeneration() || finishingJob)
		{
			return false;
		}
		finishingJob = true;
		Properties job = readProperties(ACTIVE_JOB);
		String preset = job.getProperty("preset", "");
		int slot = -1;
		try
		{
			OptionsPreset.checkName(preset);
			slot = Integer.parseInt(job.getProperty("slot", "-1"));
			checkSlot(slot);
			String fingerprint = job.getProperty("presetFingerprint", "");
			if (fingerprint.isEmpty() || !fingerprint.equals(propertiesFingerprint(slotPresetPath(preset, slot))))
				throw new IOException("Pregeneration preset snapshot changed while the job was running");
			if (!normalizeLevelName(readLevelName()).equals(normalizeLevelName(worldName(preset, slot))))
				throw new IOException("Pregeneration completed on the wrong selected world");
			Properties meta = slotMetadata(preset, slot);
			meta.setProperty("state", "READY");
			meta.setProperty("createdAt", Instant.now().toString());
			meta.setProperty("generatorIdentity", UhcGameManager.getGeneratorIdentity());
			meta.setProperty("presetFingerprint", fingerprint);
			meta.setProperty("worldName", worldName(preset, slot));
			writeProperties(slotMetadataPath(preset, slot), meta, "TC UHC pregeneration buffer slot");
			restoreJobFiles(job);
			Files.deleteIfExists(ACTIVE_JOB);
			Files.deleteIfExists(OPTIONS_BACKUP);
			UhcGameManager.LOG.info("Pregeneration buffer slot {}/{} is ready; returning to {}",
					preset, slot, job.getProperty("originalLevelName", "world"));
			gameManager.restartServer("pregeneration buffer slot completed");
			return true;
		}
		catch (Exception e)
		{
			finishingJob = false;
			UhcGameManager.LOG.error("Could not finish pregeneration buffer job", e);
			if (slot >= 1 && slot <= SLOT_COUNT)
				markFailed(preset, slot, e.getMessage());
			return false;
		}
	}

	public void generate(String preset, int slot) throws IOException
	{
		checkSlot(slot);
		OptionsPreset.checkName(preset);
		if (!OptionsPreset.exists(preset))
		{
			throw new IOException("预设不存在：" + preset);
		}
		if (Files.exists(ACTIVE_JOB) || Files.exists(ACTIVATION_JOB))
		{
			throw new IOException("已有缓冲世界任务正在运行或等待恢复");
		}
		if (!isIdle())
		{
			throw new IOException("生成缓冲世界时不能有在线玩家、进行中的对局或预生成任务");
		}
		String state = slotState(preset, slot);
		if (!"EMPTY".equals(state) && !"FAILED".equals(state))
		{
			throw new IOException("槽位 " + slot + " 当前状态为 " + state + "，请先清空");
		}

		Files.createDirectories(ROOT);
		boolean optionsExisted = Files.exists(OPTIONS_FILE);
		if (optionsExisted)
		{
			Files.copy(OPTIONS_FILE, OPTIONS_BACKUP, StandardCopyOption.REPLACE_EXISTING);
		}
		else
		{
			Files.deleteIfExists(OPTIONS_BACKUP);
		}
		String originalLevelName = readLevelName();
		Path world = Path.of(worldName(preset, slot));
		deleteWorldTree(world);
		Files.createDirectories(slotDirectory(preset, slot));
		Path presetSnapshot = slotPresetPath(preset, slot);
		Files.copy(OptionsPreset.getFile(preset).toPath(), presetSnapshot, StandardCopyOption.REPLACE_EXISTING);
		String fingerprint = propertiesFingerprint(presetSnapshot);

		Properties job = new Properties();
		job.setProperty("phase", "GENERATING");
		job.setProperty("preset", preset);
		job.setProperty("slot", Integer.toString(slot));
		job.setProperty("originalLevelName", originalLevelName);
		job.setProperty("optionsExisted", Boolean.toString(optionsExisted));
		job.setProperty("presetFingerprint", fingerprint);
		writeProperties(ACTIVE_JOB, job, "TC UHC active pregeneration buffer job");

		try
		{
			Properties meta = slotMetadata(preset, slot);
			meta.setProperty("state", "GENERATING");
			meta.setProperty("preset", preset);
			meta.remove("error");
			writeProperties(slotMetadataPath(preset, slot), meta, "TC UHC pregeneration buffer slot");

			Files.copy(presetSnapshot, OPTIONS_FILE, StandardCopyOption.REPLACE_EXISTING);
			writeLevelName(worldName(preset, slot));
			gameManager.restartServer("starting pregeneration buffer job " + preset + "/" + slot);
		}
		catch (Exception e)
		{
			try
			{
				restoreJobFiles(job);
				Files.deleteIfExists(ACTIVE_JOB);
				Files.deleteIfExists(OPTIONS_BACKUP);
			}
			catch (Exception rollbackError) { e.addSuppressed(rollbackError); }
			markFailed(preset, slot, e.getMessage());
			throw new IOException("无法启动服务器重启程序；已尝试回滚，下次启动会继续恢复", e);
		}
	}

	public void use(String preset, int slot) throws IOException
	{
		checkSlot(slot);
		OptionsPreset.checkName(preset);
		if (Files.exists(ACTIVE_JOB) || Files.exists(ACTIVATION_JOB))
		{
			throw new IOException("已有缓冲世界任务正在运行或等待恢复");
		}
		if (!"READY".equals(slotState(preset, slot)))
		{
			throw new IOException("槽位尚未就绪：" + preset + "/" + slot);
		}
		if (!isIdle())
		{
			throw new IOException("使用缓冲世界时不能有在线玩家、进行中的对局或预生成任务");
		}
		byte[] oldOptions = Files.exists(OPTIONS_FILE) ? Files.readAllBytes(OPTIONS_FILE) : null;
		String oldLevelName = readLevelName();
		if (oldOptions != null) Files.copy(OPTIONS_FILE, ACTIVATION_OPTIONS_BACKUP, StandardCopyOption.REPLACE_EXISTING);
		else Files.deleteIfExists(ACTIVATION_OPTIONS_BACKUP);
		Properties activation = new Properties();
		activation.setProperty("preset", preset);
		activation.setProperty("slot", Integer.toString(slot));
		activation.setProperty("targetLevelName", worldName(preset, slot));
		activation.setProperty("originalLevelName", oldLevelName);
		activation.setProperty("optionsExisted", Boolean.toString(oldOptions != null));
		writeProperties(ACTIVATION_JOB, activation, "TC UHC pending buffer activation");
		try
		{
			Files.copy(slotPresetPath(preset, slot), OPTIONS_FILE, StandardCopyOption.REPLACE_EXISTING);
			writeLevelName(worldName(preset, slot));
			gameManager.restartServer("using pregeneration buffer slot " + preset + "/" + slot);
		}
		catch (Exception e)
		{
			try
			{
				if (oldOptions == null) Files.deleteIfExists(OPTIONS_FILE); else Files.write(OPTIONS_FILE, oldOptions);
				writeLevelName(oldLevelName);
				Files.deleteIfExists(ACTIVATION_JOB);
				Files.deleteIfExists(ACTIVATION_OPTIONS_BACKUP);
			}
			catch (Exception rollbackError) { e.addSuppressed(rollbackError); }
			throw new IOException("无法启动服务器重启程序；已尝试回滚，下次启动会继续恢复", e);
		}
	}

	private void recoverInterruptedActivation()
	{
		if (!Files.isRegularFile(ACTIVATION_JOB)) return;
		Properties activation = readProperties(ACTIVATION_JOB);
		String preset;
		int slot;
		String target;
		try
		{
			preset = OptionsPreset.checkName(activation.getProperty("preset", ""));
			slot = Integer.parseInt(activation.getProperty("slot", "-1"));
			checkSlot(slot);
			target = activation.getProperty("targetLevelName", "");
			if (!normalizeLevelName(worldName(preset, slot)).equals(normalizeLevelName(target)))
				throw new IllegalArgumentException("Activation target does not match its preset and slot");
			String original = activation.getProperty("originalLevelName", "");
			if (original.trim().isEmpty() || original.contains("\r") || original.contains("\n"))
				throw new IllegalArgumentException("Activation original level name is invalid");
			String optionsExisted = activation.getProperty("optionsExisted", "");
			if (!"true".equals(optionsExisted) && !"false".equals(optionsExisted))
				throw new IllegalArgumentException("Activation options backup flag is invalid");
		}
		catch (Exception e)
		{
			try
			{
				Path invalidJob = ROOT.resolve("activation-job.invalid.properties");
				Files.move(ACTIVATION_JOB, invalidJob, StandardCopyOption.REPLACE_EXISTING);
				UhcGameManager.LOG.error("Quarantined invalid buffer activation job as {}", invalidJob, e);
			}
			catch (IOException moveError)
			{
				UhcGameManager.LOG.error("Could not quarantine invalid buffer activation job", moveError);
			}
			return;
		}
		try
		{
			Path snapshot = slotPresetPath(preset, slot);
			Properties meta = slotMetadata(preset, slot);
			boolean targetSelected = normalizeLevelName(readLevelName()).equals(normalizeLevelName(target));
			boolean slotIntact = Files.isDirectory(Path.of(target))
					&& Files.isRegularFile(Path.of(target).resolve("preload"))
					&& Files.isRegularFile(snapshot)
					&& meta.getProperty("presetFingerprint", "").equals(propertiesFingerprint(snapshot));
			if (targetSelected && slotIntact && sameProperties(OPTIONS_FILE, snapshot))
			{
				// The selected save is now active. The previously active slot is closed at this point,
				// so consumption can safely finish and the ring can replenish it.
				for (SlotInfo info : list(null))
				{
					if ("ACTIVE".equals(info.state) && !worldName(info.preset, info.slot).equals(target))
					{
						deleteWorldTree(Path.of(worldName(info.preset, info.slot)));
						deleteTree(slotDirectory(info.preset, info.slot));
					}
				}
				meta.setProperty("state", "ACTIVE");
				meta.setProperty("activatedAt", Instant.now().toString());
				writeProperties(slotMetadataPath(preset, slot), meta, "TC UHC pregeneration buffer slot");
			}
			else
			{
				restoreActivationFiles(activation);
				recoveryRestartNeeded = true;
			}
			Files.deleteIfExists(ACTIVATION_JOB);
			Files.deleteIfExists(ACTIVATION_OPTIONS_BACKUP);
		}
		catch (Exception e)
		{
			UhcGameManager.LOG.error("Could not recover interrupted buffer activation", e);
		}
	}

	private static void restoreActivationFiles(Properties activation) throws IOException
	{
		if ("true".equals(activation.getProperty("optionsExisted")))
		{
			if (!Files.isRegularFile(ACTIVATION_OPTIONS_BACKUP))
				throw new IOException("Missing activation options backup");
			Files.copy(ACTIVATION_OPTIONS_BACKUP, OPTIONS_FILE, StandardCopyOption.REPLACE_EXISTING);
		}
		else Files.deleteIfExists(OPTIONS_FILE);
		writeLevelName(activation.getProperty("originalLevelName"));
	}

	public void name(String preset, int slot, String label) throws IOException
	{
		checkSlot(slot);
		OptionsPreset.checkName(preset);
		if (!OptionsPreset.exists(preset))
		{
			throw new IOException("预设不存在：" + preset);
		}
		if (label == null || !label.matches("[A-Za-z0-9_-]{1,32}"))
		{
			throw new IllegalArgumentException("槽位名称只能包含字母、数字、下划线和连字符，长度 1~32");
		}
		Properties meta = slotMetadata(preset, slot);
		meta.setProperty("name", label);
		meta.setProperty("preset", preset);
		writeProperties(slotMetadataPath(preset, slot), meta, "TC UHC pregeneration buffer slot");
	}

	public void clear(String preset, int slot) throws IOException
	{
		checkSlot(slot);
		OptionsPreset.checkName(preset);
		String configuredWorld = worldName(preset, slot);
		if (normalizeLevelName(readLevelName()).equals(normalizeLevelName(configuredWorld)))
		{
			throw new IOException("不能清空 server.properties 当前选中的世界");
		}
		Properties job = readProperties(ACTIVE_JOB);
		if (preset.equals(job.getProperty("preset")) && Integer.toString(slot).equals(job.getProperty("slot")))
		{
			throw new IOException("不能清空正在生成的槽位");
		}
		Properties activation = readProperties(ACTIVATION_JOB);
		if (preset.equals(activation.getProperty("preset")) && Integer.toString(slot).equals(activation.getProperty("slot")))
		{
			throw new IOException("不能清空正在启用或恢复的槽位");
		}
		deleteWorldTree(Path.of(configuredWorld));
		deleteTree(slotDirectory(preset, slot));
	}

	public List<SlotInfo> list(String onlyPreset)
	{
		List<String> presets = onlyPreset == null ? OptionsPreset.listNames() : List.of(onlyPreset);
		List<SlotInfo> result = new ArrayList<>();
		for (String preset : presets)
		{
			try { OptionsPreset.checkName(preset); }
			catch (IllegalArgumentException ignored) { continue; }
			for (int slot = 1; slot <= SLOT_COUNT; slot++)
			{
				Properties meta = slotMetadata(preset, slot);
				result.add(new SlotInfo(preset, slot, slotState(preset, slot), meta.getProperty("name", ""), meta.getProperty("createdAt", "")));
			}
		}
		result.sort(Comparator.comparing((SlotInfo info) -> info.preset).thenComparingInt(info -> info.slot));
		return result;
	}

	private void tryStartAutomaticFill()
	{
		long now = System.currentTimeMillis();
		if (now - lastAutomaticCheckMillis < intervalSeconds * 1000L) return;
		lastAutomaticCheckMillis = now;
		if (!enabled || Files.exists(ACTIVE_JOB) || Files.exists(ACTIVATION_JOB) || !isIdle()) return;
		for (String preset : OptionsPreset.listNames())
		{
			for (int slot = 1; slot <= SLOT_COUNT; slot++)
			{
				if ("EMPTY".equals(slotState(preset, slot)))
				{
					try { generate(preset, slot); }
					catch (Exception e) { UhcGameManager.LOG.error("Automatic buffer fill failed for {}/{}", preset, slot, e); }
					return;
				}
			}
		}
	}

	private boolean isIdle()
	{
		MinecraftServer server = gameManager.getMinecraftServer();
		return server.getPlayerManager().getCurrentPlayerCount() == 0
				&& !gameManager.isGamePlaying() && !gameManager.isPregenerating();
	}

	private void restoreJobFiles(Properties job) throws IOException
	{
		String optionsExisted = job.getProperty("optionsExisted");
		if ("true".equals(optionsExisted) && Files.isRegularFile(OPTIONS_BACKUP))
		{
			Files.copy(OPTIONS_BACKUP, OPTIONS_FILE, StandardCopyOption.REPLACE_EXISTING);
		}
		else if ("false".equals(optionsExisted)) Files.deleteIfExists(OPTIONS_FILE);
		String originalLevelName = job.getProperty("originalLevelName");
		if (originalLevelName != null && !originalLevelName.trim().isEmpty()) writeLevelName(originalLevelName);
	}

	private void markFailed(String preset, int slot, String message)
	{
		try
		{
			Properties meta = slotMetadata(preset, slot);
			meta.setProperty("state", "FAILED");
			meta.setProperty("error", message == null ? "unknown error" : message);
			writeProperties(slotMetadataPath(preset, slot), meta, "TC UHC pregeneration buffer slot");
		}
		catch (Exception ignored) {}
	}

	private void loadConfig()
	{
		Properties config = readProperties(CONFIG);
		enabled = Boolean.parseBoolean(config.getProperty("bufferEnabled", config.getProperty("enabled", "false")));
		try { intervalSeconds = Integer.parseInt(config.getProperty("checkIntervalSeconds", Integer.toString(DEFAULT_INTERVAL_SECONDS))); }
		catch (NumberFormatException e) { intervalSeconds = DEFAULT_INTERVAL_SECONDS; }
		if (intervalSeconds < MIN_INTERVAL_SECONDS || intervalSeconds > 86400) intervalSeconds = DEFAULT_INTERVAL_SECONDS;
		try { saveConfig(); } catch (IOException e) { UhcGameManager.LOG.warn("Failed to save pregeneration buffer config", e); }
	}

	private void saveConfig() throws IOException
	{
		Properties config = new Properties();
		config.setProperty("bufferEnabled", Boolean.toString(enabled));
		config.setProperty("enabled", Boolean.toString(enabled));
		config.setProperty("checkIntervalSeconds", Integer.toString(intervalSeconds));
		writeProperties(CONFIG, config, "TC UHC pregeneration buffer (disabled by default)");
	}

	private static String slotState(String preset, int slot)
	{
		Properties meta = slotMetadata(preset, slot);
		String state = meta.getProperty("state", "EMPTY");
		Path world = Path.of(meta.getProperty("worldName", worldName(preset, slot)));
		if ("READY".equals(state) && (!Files.isDirectory(world) || !Files.isRegularFile(world.resolve("preload"))))
		{
			return "FAILED";
		}
		if ("READY".equals(state))
		{
			try
			{
				String snapshotFingerprint = propertiesFingerprint(slotPresetPath(preset, slot));
				if (!meta.getProperty("presetFingerprint", "").equals(snapshotFingerprint)) return "FAILED";
			}
			catch (Exception e) { return "FAILED"; }
		}
		if ("READY".equals(state) && !meta.getProperty("presetFingerprint", "").equals(presetFingerprint(preset))) return "STALE";
		return state;
	}

	private static Properties slotMetadata(String preset, int slot) { return readProperties(slotMetadataPath(preset, slot)); }
	private static Path slotMetadataPath(String preset, int slot) { return slotDirectory(preset, slot).resolve("slot.properties"); }
	private static Path slotPresetPath(String preset, int slot) { return slotDirectory(preset, slot).resolve("preset.properties"); }
	private static Path slotDirectory(String preset, int slot) { return ROOT.resolve(preset).resolve("slot-" + slot); }
	private static String worldName(String preset, int slot) { return "uhc_buffer_" + preset + "_" + slot; }

	private static String presetFingerprint(String preset)
	{
		try
		{
			return propertiesFingerprint(OptionsPreset.getFile(preset).toPath());
		}
		catch (Exception e) { return "unavailable"; }
	}

	private static String propertiesFingerprint(Path path) throws IOException
	{
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
		String canonical = properties.stringPropertyNames().stream()
				.sorted()
				.map(key -> key + "=" + properties.getProperty(key) + "\n")
				.collect(Collectors.joining());
		byte[] digest;
		try { digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)); }
		catch (Exception e) { throw new IOException("SHA-256 is unavailable", e); }
		StringBuilder result = new StringBuilder();
		for (byte value : digest) result.append(String.format("%02x", value));
		return result.toString();
	}

	private static boolean sameProperties(Path first, Path second)
	{
		if (!Files.isRegularFile(first) || !Files.isRegularFile(second)) return false;
		Properties a = readProperties(first);
		Properties b = readProperties(second);
		return a.equals(b);
	}

	private static void checkSlot(int slot)
	{
		if (slot < 1 || slot > SLOT_COUNT) throw new IllegalArgumentException("槽位只能是 1、2 或 3");
	}

	private static Properties readProperties(Path path)
	{
		Properties properties = new Properties();
		if (!Files.isRegularFile(path)) return properties;
		try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
		catch (IOException e) { UhcGameManager.LOG.warn("Failed to read {}", path, e); }
		return properties;
	}

	private static void writeProperties(Path path, Properties properties, String comment) throws IOException
	{
		Files.createDirectories(path.toAbsolutePath().getParent());
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try (OutputStream output = Files.newOutputStream(temporary)) { properties.store(output, comment); }
		try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
		catch (IOException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
	}

	private static String readLevelName() throws IOException
	{
		if (!Files.isRegularFile(SERVER_PROPERTIES)) return "world";
		for (String line : Files.readAllLines(SERVER_PROPERTIES, StandardCharsets.UTF_8))
		{
			if (line.startsWith("level-name=")) return line.substring("level-name=".length()).trim();
		}
		return "world";
	}

	private static void writeLevelName(String levelName) throws IOException
	{
		List<String> lines = Files.isRegularFile(SERVER_PROPERTIES)
				? Files.readAllLines(SERVER_PROPERTIES, StandardCharsets.UTF_8) : new ArrayList<>();
		boolean replaced = false;
		for (int i = 0; i < lines.size(); i++)
		{
			if (lines.get(i).startsWith("level-name="))
			{
				lines.set(i, "level-name=" + levelName);
				replaced = true;
			}
		}
		if (!replaced) lines.add("level-name=" + levelName);
		Path temporary = SERVER_PROPERTIES.resolveSibling("server.properties.tmp");
		Files.write(temporary, lines, StandardCharsets.UTF_8);
		try { Files.move(temporary, SERVER_PROPERTIES, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
		catch (IOException e) { Files.move(temporary, SERVER_PROPERTIES, StandardCopyOption.REPLACE_EXISTING); }
	}

	private static String normalizeLevelName(String value) { return value.replace('\\', '/').replaceAll("^\\./", ""); }

	private static void deleteTree(Path root) throws IOException
	{
		if (!Files.exists(root)) return;
		Path safeRoot = root.toAbsolutePath().normalize();
		Path safeBuffer = ROOT.toAbsolutePath().normalize();
		if (!safeRoot.startsWith(safeBuffer) || safeRoot.equals(safeBuffer)) throw new IOException("拒绝不安全的缓冲目录删除：" + safeRoot);
		try (java.util.stream.Stream<Path> paths = Files.walk(safeRoot))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(path);
		}
	}

	private static void deleteWorldTree(Path root) throws IOException
	{
		Path safeRoot = root.toAbsolutePath().normalize();
		Path cwd = Path.of(".").toAbsolutePath().normalize();
		if (!safeRoot.getParent().equals(cwd) || !safeRoot.getFileName().toString().startsWith("uhc_buffer_"))
			throw new IOException("拒绝不安全的缓冲世界删除：" + safeRoot);
		if (!Files.exists(safeRoot)) return;
		try (java.util.stream.Stream<Path> paths = Files.walk(safeRoot))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(path);
		}
	}

	public static final class SlotInfo
	{
		public final String preset;
		public final int slot;
		public final String state;
		public final String name;
		public final String createdAt;
		public SlotInfo(String preset, int slot, String state, String name, String createdAt)
		{
			this.preset = preset; this.slot = slot; this.state = state; this.name = name; this.createdAt = createdAt;
		}
	}
}
