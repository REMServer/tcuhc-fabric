/*
 From Gamepiaynmo: https://github.com/Gamepiaynmo/TC-UHC
 */

package me.fallenbreath.tcuhc;

import me.fallenbreath.tcuhc.UhcGamePlayer.EnumStat;
import me.fallenbreath.tcuhc.mixins.core.MinecraftServerAccessor;
import me.fallenbreath.tcuhc.options.Options;
import me.fallenbreath.tcuhc.pregen.PreGenBufferManager;
import me.fallenbreath.tcuhc.task.*;
import me.fallenbreath.tcuhc.util.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;


import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.level.ServerWorldProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

public class UhcGameManager extends Taskable {

	public static final Logger LOG = LogManager.getLogger("TC UHC");
	public static UhcGameManager instance;
	public static final Random rand = new Random();
	
	private final MinecraftServer mcServer;

	private final UhcPlayerManager playerManager;
	private final UhcConfigManager configManager = new UhcConfigManager();
	private final Options uhcOptions;
	private final PreGenBufferManager preGenBufferManager;

	private boolean isGamePlaying;
	private boolean isGameEnded;
	
	private boolean isPregenerating;
	private static boolean preloaded;
	
	public LastWinnerList winnerList;
	private Optional<ServerBossBar> bossInfo = Optional.empty();

	public final MsptRecorder msptRecorder = new MsptRecorder();
	private final UhcWorldData worldData;
	
	public UhcGameManager(MinecraftServer server)
	{
		instance = this;
		mcServer = server;
		uhcOptions = Options.instance;
		playerManager = new UhcPlayerManager(this);
		winnerList = new LastWinnerList(new File("lastwinners.txt"));
		worldData = UhcWorldData.load();
		preGenBufferManager = new PreGenBufferManager(this);
	}

	public MinecraftServer getMinecraftServer() { return mcServer; }
	public PlayerManager getServerPlayerManager() { return mcServer.getPlayerManager(); }
	public UhcPlayerManager getUhcPlayerManager() { return playerManager; }
	public UhcConfigManager getConfigManager() { return configManager; }
	public Options getOptions() { return uhcOptions; }
	public PreGenBufferManager getPreGenBufferManager() { return preGenBufferManager; }
	public boolean isGamePlaying() { return isGamePlaying; }
	public boolean isConfiguring() { return configManager.isConfiguring(); }
	private Optional<String> getCannotStartReason(boolean forceStart)
	{
		if (isGamePlaying) {
			return Optional.of("当前游戏已经开始，无法再次开始。若需结束当前对局，请先执行停止流程。");
		}
		if (isPregenerating && !forceStart) {
			return Optional.of("世界仍在预生成，使用 /uhc forceStart 可立即开始；预生成会继续在后台进行。");
		}
		return Optional.empty();
	}
	public boolean hasGameEnded() { return isGameEnded; }
	public static EnumBattleType getBattleType() { return (EnumBattleType)instance.getOptions().getOptionValue("battleType"); }
	public static EnumLevelType getLevelType() { return (EnumLevelType)instance.getOptions().getOptionValue("levelType"); }
	public static Weather getWeather() { return (Weather)instance.getOptions().getOptionValue("weather"); }
	public static EnumMode getGameMode() { return (EnumMode)instance.getOptions().getOptionValue("gameMode"); }

	public ServerWorld getOverWorld()
	{
		return mcServer.getWorld(World.OVERWORLD);
	}

	public UhcWorldData getWorldData()
	{
		return worldData;
	}

	public void onPlayerJoin(ServerPlayerEntity player) {
		try {
			playerManager.onPlayerJoin(player);
			bossInfo.ifPresent(info -> info.addPlayer(player));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public boolean onPlayerChat(ServerPlayerEntity player, String msg) {
		try {
			if (configManager.onPlayerChat(player, msg))
				playerManager.onPlayerChat(player, msg);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}
	
	public void onPlayerDeath(ServerPlayerEntity player, DamageSource cause) {
		try {
			playerManager.onPlayerDeath(player, cause);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void onPlayerRespawn(ServerPlayerEntity player) {
		try {
			playerManager.onPlayerRespawn(player);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void onPlayerDamaged(ServerPlayerEntity player, DamageSource cause, float amount) {
		try {
			playerManager.onPlayerDamaged(player, cause, amount);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public Entity onPlayerSpectate(ServerPlayerEntity player, Entity target, Entity origin) {
		try {
			return playerManager.onPlayerSpectate(player, target, origin);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return target;
	}
	
	public void onServerInited()
	{
		this.displayHealth();
		TaskScoreboard.hideScoreboard();
		if (preloaded && preGenBufferManager.isActiveGeneration()) {
			// A crash may happen after the preload marker is flushed but before the completion
			// restart. Finish that durable job instead of leaving it stuck forever.
			if (preGenBufferManager.onPregenerationComplete()) return;
		}
		if (!preloaded) {
			if (preGenBufferManager.isActiveGeneration() || uhcOptions.getBooleanOptionValue("pregenerateOnStart")) {
				this.startPregenerateOverworld();
				isPregenerating = true;
			} else {
				// Pregeneration is disabled, so the world is ready the moment it is created.
				// Still mark it as complete, otherwise the missing preload marker makes the
				// next boot wipe this world again.
				this.setPregenerateComplete();
			}
		}
		SpawnPlatform.generatePlatform(this, getOverWorld());
		this.addTask(new TaskHUDInfo(mcServer));
		preGenBufferManager.installScheduler();
		this.warnOnStaleTerrain();
	}

	/**
	 * The settings that shape terrain live in {@code uhc.properties}, which survives a regen, while
	 * the terrain itself lives in the world folder. Changing {@code battleType} without regenerating
	 * therefore silently keeps the previous terrain - the reported "normal mode seems to be using
	 * the marine world generator".
	 */
	private void warnOnStaleTerrain() {
		String previous = worldData.checkGeneratorIdentity(getGeneratorIdentity());
		if (previous == null) {
			return;
		}
		LOG.warn("World terrain was generated with [{}] but the current settings are [{}]", previous, getGeneratorIdentity());
		this.broadcastMessage(Formatting.RED + "警告：当前地形是用 [" + previous + "] 生成的，");
		this.broadcastMessage(Formatting.RED + "但现在的设置是 [" + getGeneratorIdentity() + "]。");
		this.broadcastMessage(Formatting.RED + "地形不会自动改变，请执行 /uhc regen 重新生成地形。");
	}

	/** The generator-affecting settings, as a single comparable string stored in {@code uhc.json}. */
	public static String getGeneratorIdentity() {
		return String.format(
				"battleType=%s,levelType=%s,disableOceanBiomes=%s",
				getBattleType().name(),
				getLevelType().name(),
				Options.instance.getBooleanOptionValue("disableOceanBiomes")
		);
	}

	public void startPregenerateOverworld()
	{
		int borderStart = uhcOptions.getIntegerOptionValue("borderStart");
		int radius = borderStart / 32;
		this.addTask(new TaskPregenerate(mcServer, radius + 5, getOverWorld()));
	}

	public void startPregenerateNether()
	{
		if (!uhcOptions.getBooleanOptionValue("netherPregenerate"))
		{
			// Skipping the nether still has to end the pregeneration flow, otherwise the
			// preload marker is never written and the next boot regenerates the world.
			this.setPregenerateComplete();
			return;
		}
		ServerWorld nether = mcServer.getWorld(World.NETHER);
		if (nether == null)
		{
			this.setPregenerateComplete();
			return;
		}
		int borderStart = uhcOptions.getIntegerOptionValue("borderStart");
		int radius = borderStart / 32;
		this.addTask(new TaskPregenerate(mcServer, radius / 8 + 10, nether));
	}
	
	/**
	 * Single exit point for "this world finished pregenerating". It also owns the preload
	 * marker, which doubles as the flag that tells {@link #tryUpdateSaveFolder} the world
	 * directory may be kept: a world without it gets wiped on the next boot.
	 */
	public void setPregenerateComplete() {
		isPregenerating = false;
		try {
			File preload = getPreloadFile();
			if (!preload.exists() && !preload.createNewFile()) {
				LOG.warn("Failed to create preload marker {}", preload);
			}
		} catch (IOException e) {
			LOG.warn("Failed to create preload marker", e);
		}
		preGenBufferManager.onPregenerationComplete();
	}
	
	public boolean isPregenerating() {
		return isPregenerating;
	}
	
	public float modifyPlayerDamage(float amount) {
		if (isGamePlaying) {
			boolean greenHand = uhcOptions.getBooleanOptionValue("greenhandProtect");
			int time = uhcOptions.getIntegerOptionValue("greenhandTime");
			int gameTime = uhcOptions.getIntegerOptionValue("gameTime") - this.getGameTimeRemaining();
			if (greenHand && gameTime < time) return amount / 2;
		}
		return amount;
	}
	
	public static void tryUpdateSaveFolder(Path saveFolder) {
		if (!saveFolder.resolve("preload").toFile().exists()) {
			LOG.warn("Deleting {} for UHC world regenerate", saveFolder);
			deleteFolder(saveFolder.toFile());
		} else {
			preloaded = true;
		}
	}

	public static void deleteFolder(File folder) {
		File[] files = folder.listFiles();
		if (files == null) return;
		for (File file : files) {
			if (file.isDirectory()) deleteFolder(file);
			else if (!file.getName().equals("carpet.conf")) file.delete();
		}
	}

	public static File getPreloadFile() {
		return ((MinecraftServerAccessor)instance.mcServer).getSession().getDirectory(WorldSavePath.ROOT).resolve("preload").toFile();
	}

	public static File getDataFile() {
		return ((MinecraftServerAccessor)instance.mcServer).getSession().getDirectory(WorldSavePath.ROOT).resolve("uhc.json").toFile();
	}

	private static Path getServerRootPath() {
		Path worldRoot = ((MinecraftServerAccessor)instance.mcServer).getSession().getDirectory(WorldSavePath.ROOT);
		Path cwd = new File(".").getAbsoluteFile().toPath().normalize();
		Path serverRoot = worldRoot.toAbsolutePath().getParent();
		return serverRoot != null ? serverRoot : cwd;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static Path getRegenRestartHelperPath() {
		Path serverRootPath = getServerRootPath();
		String[] helperNames = isWindows()
				? new String[]{"restart-server.bat", "restart-server.cmd"}
				: new String[]{"restart-server.sh"};
		for (String helperName : helperNames) {
			Path helperPath = serverRootPath.resolve(helperName);
			if (helperPath.toFile().exists()) {
				return helperPath;
			}
			Path cwdHelperPath = new File(helperName).toPath().toAbsolutePath();
			if (cwdHelperPath.toFile().exists()) {
				return cwdHelperPath;
			}
		}
		throw new IllegalStateException("Missing regen restart helper (" + Arrays.toString(helperNames) + ") under " + serverRootPath);
	}

	private static void launchRegenRestartHelper(Path helperPath) {
		String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
		String pid = runtimeName.contains("@") ? runtimeName.substring(0, runtimeName.indexOf('@')) : runtimeName;
		try {
			List<String> command = isWindows()
					? Arrays.asList("cmd", "/c", helperPath.toAbsolutePath().toString(), pid)
					: Arrays.asList(helperPath.toAbsolutePath().toString(), pid);
			LOG.info("Launching regen restart helper {} for pid {}", command, pid);
			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.directory(helperPath.toAbsolutePath().getParent().toFile());
			processBuilder.start();
		} catch (IOException e) {
			throw new RuntimeException("Failed to launch regen restart helper", e);
		}
	}

	/** Starts the existing one-shot restart helper, then stops after all current tasks are canceled. */
	public void restartServer(String reason) {
		Path helperPath = getRegenRestartHelperPath();
		launchRegenRestartHelper(helperPath);
		LOG.info("Restart requested: {}", reason);
		this.cancelTasks();
		this.isPregenerating = false;
		this.broadcastMessage("服务器即将重启，请稍后重新连接。");
		this.mcServer.stop(false);
	}
	
	public static void regenerateTerrain() {
		Path helperPath = getRegenRestartHelperPath();
		// A mod can stop the dedicated server, but restarting the JVM must be delegated to an external helper.
		launchRegenRestartHelper(helperPath);
		instance.cancelTasks();
		instance.isPregenerating = false;
		File preload = getPreloadFile();
		if (preload.exists() && !preload.delete()) {
			throw new IllegalStateException("Failed to delete preload marker: " + preload);
		}
		instance.broadcastMessage("地形重生成已确认，服务器即将自动重启。请稍候重新连接。");
		instance.mcServer.stop(false);
	}
	
	public void startGame(ServerPlayerEntity operator, boolean forceStart) {
		Optional<String> cannotStartReason = getCannotStartReason(forceStart);
		if (cannotStartReason.isPresent()) {
			operator.sendMessage(Text.literal("现在还不能开始游戏。"), false);
			operator.sendMessage(Text.literal(cannotStartReason.get()), false);
			return;
		}
		if (isPregenerating) {
			this.broadcastMessage("管理员已跳过预生成直接开始游戏，剩余预生成任务将继续在后台执行。");
		}

		if (!configManager.isConfiguring()) {
			startConfiguration(operator);
		}

		boolean autoTeams = uhcOptions.getBooleanOptionValue("randomTeams");
		playerManager.refreshOnlinePlayers();

		java.util.List<UhcGamePlayer> allPlayers = new java.util.ArrayList<>(playerManager.getAllPlayers());
		java.util.List<UhcGamePlayer> unselected = new java.util.ArrayList<>();
		for (UhcGamePlayer gamePlayer : allPlayers) {
			if (!gamePlayer.getColorSelected().isPresent()) {
				unselected.add(gamePlayer);
			}
		}

		if (allPlayers.isEmpty() || unselected.size() == allPlayers.size()) {
			this.broadcastMessage(Formatting.RED + "游戏未配置 /uhc config");
			this.broadcastMessage(Formatting.RED + "请先进行游戏身份选择或者重新配置生成");
			TitleUtil.sendTitleToPlayer(Formatting.RED + "游戏未配置", "请先进行游戏身份选择或者重新配置生成", operator);
			return;
		}

		if (!unselected.isEmpty()) {
			for (UhcGamePlayer gamePlayer : unselected) {
				this.broadcastMessage(Formatting.YELLOW + "@ " + gamePlayer.getName() + " 请速速选择队伍！");
				gamePlayer.getRealPlayer().ifPresent(player ->
					TitleUtil.sendTitleToPlayer(Formatting.RED + "请选择队伍", "点击彩色皮甲完成选择", player)
				);
			}
			TitleUtil.sendTitleToPlayer(Formatting.RED + "有玩家未选队", unselected.size() + " 人尚未选择队伍", operator);
			operator.sendMessage(Text.literal(Formatting.RED + "有 " + unselected.size() + " 名玩家尚未选择队伍，无法开始游戏。"), false);
			return;
		}

		if (!playerManager.formTeams(autoTeams)) {
			operator.sendMessage(Text.literal("开始游戏失败：" + playerManager.getLastTeamFormFailureReason().orElse("分队条件未满足，请检查队伍与模式设置。")), false);
			return;
		}

		this.broadcastMessage(Formatting.GOLD + "=== 游戏模式：" + getGameMode() + " | 战斗类型：" + getBattleType() + " ===");
		LOG.info("Game starting with mode={}, battleType={}", getGameMode(), getBattleType());

		switch (getGameMode()) {
			case BOSS:
				bossInfo = Optional.of(new ServerBossBar(Text.literal(playerManager.getBossPlayer().getName()), BossBar.Color.PURPLE, BossBar.Style.PROGRESS));
				getServerPlayerManager().getPlayerList().forEach(player -> bossInfo.ifPresent(info -> info.addPlayer(player)));
				bossInfo.ifPresent(info -> info.setVisible(true));
				break;
		}
		isGamePlaying = true;
		this.initWorlds();
		configManager.stopConfiguring();
		playerManager.setupIngameTeams();
		playerManager.spreadPlayers();
		this.destroySpawnPlatform();
		this.addTask(new TaskTitleCountDown(10, 80, 20));
	}
	
	public void endGame() {
		if (isGameEnded) return;
		isGamePlaying = false;
		isGameEnded = true;
		// The ended phase is still part of the match experience: everyone should be free to fly
		// around and inspect the battlefield until an operator opens /uhc config for the next game.
		// Do this immediately instead of waiting for TaskBroadcastData's first round, otherwise the
		// surviving players remain in survival for eight seconds after the winner is announced.
		getServerPlayerManager().getPlayerList().forEach(player -> {
			player.changeGameMode(GameMode.SPECTATOR);
			player.setCameraEntity(player);
		});
		removeWorldBorder();
		TaskScoreboard.hideScoreboard();
		bossInfo.ifPresent(info -> info.setVisible(false));
		bossInfo = Optional.empty();
	}
	
	/**
	 * Ends the current match on an operator's say-so.
	 *
	 * <p>{@link #endGame()} on its own only tears the HUD down - no message, no score board, no
	 * winner recorded - so from a player's seat {@code /uhc stop} looked like it had done nothing
	 * at all. Settle the match the same way {@link #onNoTeamWin()} does, just with the reason
	 * being an admin rather than the last death.
	 *
	 * @return false if there was no match to stop, so the caller can say so.
	 */
	public boolean stopGameByOperator() {
		if (!isGamePlaying || isGameEnded) return false;
		TitleUtil.sendTitleToAllPlayers("游戏结束", "管理员已结束本局");
		this.broadcastMessage(Formatting.GOLD + "管理员已结束本局游戏，按当前积分结算。");
		finalizeAliveTimes();
		this.printFinalScores(null);
		winnerList.setWinner(new ArrayList<UhcGamePlayer>());
		this.endGame();
		this.addTask(new TaskBroadcastData(160));
		return true;
	}

	public void checkWinner() {
		if (isGameEnded || !isGamePlaying) return;
		int remainTeamCnt = 0;
		UhcGameTeam winner = null;
		for (UhcGameTeam team : playerManager.getTeams()) {
			if (team.getAliveCount() > 0) {
				remainTeamCnt++;
				winner = team;
			}
		}
		if (remainTeamCnt == 1)
			this.onTeamWin(winner);
		else if (remainTeamCnt == 0)
			this.onNoTeamWin();
	}

	public void onTeamWin(UhcGameTeam team) {
		TitleUtil.sendTitleToAllPlayers(team.getColorfulTeamName() + " 获胜！", "恭喜！");
		this.broadcastMessage(team.getColorfulTeamName() + " 是本局冠军！");
		finalizeAliveTimes();
		this.printFinalScores(team);
		
		winnerList.setWinner(team.getPlayers());
		this.endGame();
		this.addTask(new TaskBroadcastData(160));
	}

	private void onNoTeamWin() {
		TitleUtil.sendTitleToAllPlayers("游戏结束", "无人存活");
		this.broadcastMessage("本局所有玩家都已出局，按最终积分结算。");
		finalizeAliveTimes();
		this.printFinalScores(null);
		winnerList.setWinner(new ArrayList<UhcGamePlayer>());
		this.endGame();
		this.addTask(new TaskBroadcastData(160));
	}
	
	private void initWorlds() {
		boolean daylightCycle = uhcOptions.getBooleanOptionValue("daylightCycle");
		Difficulty difficulty = (Difficulty) uhcOptions.getOptionValue("difficulty");
		Weather weather = getWeather();
		int borderStart = uhcOptions.getIntegerOptionValue("borderStart");
		for (ServerWorld world : mcServer.getWorlds()) {
			world.getGameRules().get(GameRules.NATURAL_REGENERATION).set(false, mcServer);
			// No "You died - Respawn / Title Screen" panel during a match. A UHC death is final,
			// so the panel offers a choice that does not exist and just delays the switch to
			// spectator until the player clicks something. With this on, the client respawns by
			// itself and UhcPlayerManager.onPlayerRespawn turns that into spectator-at-death-spot.
			world.getGameRules().get(GameRules.DO_IMMEDIATE_RESPAWN).set(true, mcServer);
			world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(daylightCycle, mcServer);
			world.setTimeOfDay(0);
			if(weather != Weather.NORMAL) {
				world.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(false, mcServer);
				ServerWorldProperties worldinfo = (ServerWorldProperties) world.getLevelProperties();
				if (weather != weather.CLEAR) {
					worldinfo.setClearWeatherTime(0);
					worldinfo.setRainTime(6000);
				}
				if (weather == weather.RAIN)
					worldinfo.setRaining(true);
				if (weather == weather.THUNDER)
					worldinfo.setThundering(true);
			}
			world.getWorldBorder().setSize(borderStart);
		}
		mcServer.setDifficulty(difficulty, true);
	}
	
	private void removeWorldBorder() {
		for (ServerWorld world : mcServer.getWorlds()) {
			world.getWorldBorder().setSize(world.getWorldBorder().getMaxRadius());
		}
	}
	
	public void displayHealth() {
		Scoreboard scoreboard = getMainScoreboard();
		String name = "生命值";
		ScoreboardObjective objective;
		if ((objective = scoreboard.getNullableObjective(name)) == null) {
			objective = scoreboard.addObjective(name, ScoreboardCriterion.HEALTH, Text.literal(name), ScoreboardCriterion.RenderType.HEARTS, true, null);
		}
		scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.LIST, objective);
		scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.BELOW_NAME, objective);
	}
	
	public Scoreboard getMainScoreboard() {
		return getOverWorld().getScoreboard();
	}
	
	public void tick() {
		try {
			this.updateTasks();
			if (!this.isGamePlaying)
				this.winnerParticles();
			for (UhcGamePlayer player : playerManager.getAllPlayers()) {
				player.tick();
			}
			bossInfo.ifPresent(info -> playerManager.getBossPlayer().getRealPlayer().ifPresent(player -> info.setPercent(player.getHealth() / player.getMaxHealth())));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void winnerParticles() {
		for (ServerPlayerEntity player : getServerPlayerManager().getPlayerList()) {
			if (player.age % 2 == 0 && winnerList.isWinner(player.getName().getString())) {
				double angle = (player.age % 360) * 9 * Math.PI / 180;
				double dx = Math.cos(angle) * 0.6;
				double dz = Math.sin(angle) * 0.6;
				double dy = Math.cos(angle) * 0.4;
				((ServerWorld) player.getWorld()).spawnParticles(ParticleTypes.FLAME, player.getX() + dx, player.getY() + dy + player.getStandingEyeHeight() / 2, player.getZ() + dz, 1, 0, 0, 0, 0);
				((ServerWorld) player.getWorld()).spawnParticles(ParticleTypes.FLAME, player.getX() - dx, player.getY() + dy + player.getStandingEyeHeight() / 2, player.getZ() - dz, 1, 0, 0, 0, 0);
			}
		}
	}
	
	private void finalizeAliveTimes() {
		for (UhcGamePlayer player : playerManager.getCombatPlayers()) {
			if (player.getStat().getFloatStat(EnumStat.ALIVE_TIME) < 1) {
				player.getStat().setStat(EnumStat.ALIVE_TIME, uhcOptions.getIntegerOptionValue("gameTime") - this.getGameTimeRemaining());
			}
		}
	}

	private void printFinalScores(UhcGameTeam winningTeam) {
		List<UhcGamePlayer> ranking = new ArrayList<UhcGamePlayer>();
		for (UhcGamePlayer player : playerManager.getCombatPlayers()) {
			ranking.add(player);
		}
		ranking.sort(Comparator.comparingDouble((UhcGamePlayer player) -> calculatePlayerScore(player, winningTeam)).reversed());

		String title = winningTeam != null ? "===== " + winningTeam.getColorfulTeamName() + " 最终积分榜 =====" : "===== 最终积分榜 =====";
		broadcastMessage(title);
		int rank = 1;
		for (UhcGamePlayer player : ranking) {
			broadcastMessage(formatScoreLine(rank++, player, winningTeam));
		}
		if (winningTeam != null) {
			broadcastMessage(winningTeam.getColorfulTeamName() + " 队伍总积分: " + String.format("%.1f", calculateTeamScore(winningTeam, winningTeam)) + "分");
		}
		broadcastMessage("===========================");
	}

	private float calculatePlayerScore(UhcGamePlayer player, UhcGameTeam winningTeam) {
		float aliveTime = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.ALIVE_TIME);
		float playerKills = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.PLAYER_KILLED);
		float diamonds = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.DIAMOND_FOUND);
		float goldenApples = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.GOLDEN_APPLE_EATEN);
		float damageDealt = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.DAMAGE_DEALT);
		float teamBonus = winningTeam != null && player.getTeam() == winningTeam ? 10.0f : 0.0f;
		return aliveTime / 20 / 60 + playerKills * 2 + diamonds + goldenApples * 0.5f + damageDealt / 100 + teamBonus;
	}

	private float calculateTeamScore(UhcGameTeam team, UhcGameTeam winningTeam) {
		float total = 0;
		for (UhcGamePlayer player : team.getPlayers()) {
			total += calculatePlayerScore(player, winningTeam);
		}
		return total;
	}

	private String formatScoreLine(int rank, UhcGamePlayer player, UhcGameTeam winningTeam) {
		float aliveTime = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.ALIVE_TIME) / 20 / 60;
		float playerKills = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.PLAYER_KILLED);
		float diamonds = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.DIAMOND_FOUND);
		float goldenApples = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.GOLDEN_APPLE_EATEN);
		float damageDealt = player.getStat().getFloatStat(UhcGamePlayer.EnumStat.DAMAGE_DEALT);
		float teamBonus = winningTeam != null && player.getTeam() == winningTeam ? 10.0f : 0.0f;
		float totalScore = calculatePlayerScore(player, winningTeam);
		String teamName = player.getTeam() != null ? player.getTeam().getColorfulTeamName() : Formatting.GRAY + "无队伍";
		return String.format(
				"#%d %s [%s] 总积分 %.1f（存活 %.1f，击杀 %.0f，钻石 %.0f，金苹果 %.0f，伤害 %.1f%s）",
				rank,
				player.getName(),
				teamName,
				totalScore,
				aliveTime,
				playerKills,
				diamonds,
				goldenApples,
				damageDealt / 100,
				teamBonus > 0 ? String.format("，团队奖励 %.0f", teamBonus) : ""
		);
	}
	
	public void generateSpawnPlatform() { SpawnPlatform.generatePlatform(this, getOverWorld()); }
	public void destroySpawnPlatform() { SpawnPlatform.destroyPlatform(getOverWorld()); }
	
	public void startConfiguration(ServerPlayerEntity operator) {
		// A finished match leaves everyone in spectator with no way back, so re-configuring used to
		// require switching to creative by hand. Reopening the configuration is the natural
		// "set up the next game" gesture, so make it reset the match first.
		if (isGameEnded) {
			this.returnToLobby();
		}
		configManager.startConfiguring(playerManager.getGamePlayer(operator));
		// Give-or-refresh, never a blind insert: running /uhc config twice used to leave the
		// operator holding two config books, and every later edit refreshed only one of them.
		playerManager.giveOrRefreshConfigBook(operator);
		if (!UhcGameManager.instance.isGamePlaying()) SpawnPlatform.validateSpawnPositions(getOverWorld());
	}

	/**
	 * Takes the server from "match finished" back to the pre-game lobby, so the operator can set up
	 * the next game without creative mode and without a restart.
	 *
	 * <p>Deliberately does <em>not</em> touch the terrain: regenerating is a separate, explicit
	 * decision made with {@code /uhc regen}.
	 */
	public void returnToLobby() {
		if (!isGameEnded && !isGamePlaying) {
			return;
		}
		isGamePlaying = false;
		isGameEnded = false;
		this.cancelTasks();
		removeWorldBorder();
		TaskScoreboard.hideScoreboard();
		bossInfo.ifPresent(info -> info.setVisible(false));
		bossInfo = Optional.empty();

		Scoreboard scoreboard = getMainScoreboard();
		for (Object team : scoreboard.getTeams().toArray()) {
			scoreboard.removeTeam((Team) team);
		}

		// Restore the template and its safe positions before teleporting players back.
		this.generateSpawnPlatform();
		playerManager.resetForNextGame();
		// Back to vanilla behaviour outside a match; initWorlds turns it on again next game.
		for (ServerWorld world : mcServer.getWorlds()) {
			world.getGameRules().get(GameRules.DO_IMMEDIATE_RESPAWN).set(false, mcServer);
		}
		this.addTask(new TaskHUDInfo(mcServer));
		this.broadcastMessage(Formatting.GOLD + "已返回大厅，可以配置下一局游戏了。");
	}
	
	public void broadcastMessage(String msg) {
		Text text = Text.literal(msg);
		getServerPlayerManager().getPlayerList().forEach(player -> player.sendMessage(text, false));
		LOG.info(msg);
	}
	
	public BlockPos buildSmallHouse(BlockPos pos, DyeColor color) {
		World world = getOverWorld();
		world.getBlockState(pos);
		pos = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, pos).down();
		ColorUtil.ColorfulBlocks colorfulBlocks = ColorUtil.fromColor(color);
		BlockState floor = colorfulBlocks.wool.getDefaultState();
		BlockState wall = colorfulBlocks.glassPane.getDefaultState();
		BlockState ceiling = colorfulBlocks.glass.getDefaultState();
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				world.setBlockState(pos.add(x, 0, z), floor);
				world.setBlockState(pos.add(x, 4, z), ceiling);
				if (x == -3 || x == 3 || z == -3 || z == 3) {
					for (int y = 1; y <= 3; y++) {
						world.setBlockState(pos.add(x, y, z), wall);
					}
				} else {
					for (int y = 1; y <= 3; y++) {
						world.setBlockState(pos.add(x, y, z), Blocks.AIR.getDefaultState());
					}
				}
			}
		}
		world.setBlockState(pos.up(), Blocks.CHEST.getDefaultState());
		return pos.up();
	}
	
	public int getGameTimeRemaining() {
		Scoreboard scoreboard = getMainScoreboard();
		ScoreboardObjective objective = scoreboard.getNullableObjective(TaskScoreboard.scoreName);
		if (objective == null) return 0;
		ScoreAccess score = scoreboard.getOrCreateScore(ScoreHolder.fromName(TaskScoreboard.lines[0]), objective);
		return score.getScore();
	}

	/**
	 * Returns the effective border shrink window [startTime, endTime] (in seconds) so that the
	 * border always shrinks before the game ends, even when the configured times exceed gameTime.
	 */
	public static int[] getScaledBorderTimes() {
		int gameTime = Options.instance.getIntegerOptionValue("gameTime");
		int startTime = Options.instance.getIntegerOptionValue("borderStartTime");
		int endTime = Options.instance.getIntegerOptionValue("borderEndTime");
		if (endTime > gameTime) {
			double scale = (double) gameTime / Math.max(1.0, endTime);
			startTime = (int) Math.round(startTime * scale);
			endTime = gameTime;
		}
		if (startTime >= endTime) {
			startTime = Math.max(0, endTime - Math.max(1, gameTime / 10));
		}
		return new int[]{startTime, endTime};
	}
	
	public static enum EnumMode {
		NORMAL(true),
		SOLO(false),
		BOSS(false),
		GHOST(false),
		BOMBER(false),
		KING(true),
		HUNTER(false),
		GHOSTHUNTER(false);

		private final boolean deathRegen;

		EnumMode(boolean deathRegen)
		{
			this.deathRegen = deathRegen;
		}

		public boolean doDeathRegen()
		{
			return deathRegen;
		}

		@Override
		public String toString()
		{
			switch (this)
			{
				case NORMAL: return "普通";
				case SOLO: return "单人";
				case BOSS: return "Boss";
				case GHOST: return "隐身";
				case BOMBER: return "小天才模式";
				case KING: return "国王";
				case HUNTER: return "猎人";
				case GHOSTHUNTER: return "幽灵猎人";
				default: return name();
			}
		}
	}

	public static enum EnumBattleType {
		NORMAL,
		MARINE,
		ICARUS;

		@Override
		public String toString()
		{
			switch (this)
			{
				case NORMAL: return "普通";
				case MARINE: return "海战";
				case ICARUS: return "鞘翅模式";
				default: return name();
			}
		}
	}

	public static enum EnumLevelType {
		DEFAULT,
		AMPLIFIED,
		LARGEBIOMES;

		@Override
		public String toString()
		{
			switch (this)
			{
				case DEFAULT: return "默认";
				case AMPLIFIED: return "放大化";
				case LARGEBIOMES: return "大型生物群系";
				default: return name();
			}
		}
	}

	public static enum Weather {
		NORMAL,
		CLEAR,
		RAIN,
		THUNDER;

		@Override
		public String toString()
		{
			switch (this)
			{
				case NORMAL: return "默认";
				case CLEAR: return "晴天";
				case RAIN: return "下雨";
				case THUNDER: return "雷暴";
				default: return name();
			}
		}
	}
}
