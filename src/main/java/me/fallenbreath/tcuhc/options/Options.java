/*
 From Gamepiaynmo: https://github.com/Gamepiaynmo/TC-UHC
 */

package me.fallenbreath.tcuhc.options;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import me.fallenbreath.tcuhc.UhcGameManager;
import me.fallenbreath.tcuhc.UhcGameManager.EnumMode;
import me.fallenbreath.tcuhc.UhcGameManager.EnumBattleType;
import me.fallenbreath.tcuhc.UhcGamePlayer;
import me.fallenbreath.tcuhc.task.Task;
import net.minecraft.world.Difficulty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Options {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String OPTION_FILE_NAME = "uhc.properties";
	public static Options instance = new Options(new File(OPTION_FILE_NAME));
	
	/**
	 * Options that only take effect once the terrain is generated again, and which do not carry
	 * {@code setNeedToSave()} themselves. Those six loot and spawn frequency options are picked up
	 * through {@link Option#needToSave()} instead of a copy of the list here.
	 */
	private static final Set<String> WORLD_GENERATION_OPTIONS = ImmutableSet.of(
			"levelType", "disableOceanBiomes", "battleType"
	);
	
	/** Options read once in {@link UhcGameManager#onServerInited()}, i.e. only on a server start. */
	private static final Set<String> SERVER_START_OPTIONS = ImmutableSet.of(
			"netherPregenerate", "pregenerateOnStart", "pregenerateParallelism"
	);
	
	private final Map<String, Option> configOptions = Maps.newHashMap();
	/** Registration order of {@link #configOptions}, so listings and preset files come out stable. */
	private final List<String> optionOrder = Lists.newArrayList();
	private final Properties uhcProperties = new Properties();
	private final File uhcOptionsFile;
	
	/**
	 * Nesting depth of {@link #runBatch}. While it is above zero the two shared tasks are deferred
	 * instead of run: without that, applying a 34 entry preset would write uhc.properties 34 times
	 * and hand every player a fresh config book up to six times over.
	 */
	private int batchDepth = 0;
	private boolean batchSavePending = false;
	private boolean batchReselectPending = false;
	
	public final Task taskSaveProperties = new Task() {
		@Override
		public void onUpdate() {
			if (Options.this.inBatch()) {
				Options.this.batchSavePending = true;
				return;
			}
			Options.this.savePropertiesFile();
		}
		@Override
		public boolean hasFinished() { return false; }
	};
	
	public final Task taskReselectTeam = new Task() {
		@Override
		public void onUpdate() {
			if (Options.this.inBatch()) {
				Options.this.batchReselectPending = true;
				return;
			}
			Options.this.reselectTeam();
		}
		@Override
		public boolean hasFinished() { return false; }
	};
	
	private void reselectTeam() {
		for (UhcGamePlayer player : UhcGameManager.instance.getUhcPlayerManager().getAllPlayers()) {
			player.setColorSelected(null);
			player.getRealPlayer().ifPresent(playermp -> {
				UhcGameManager.instance.getUhcPlayerManager().regiveConfigItems(playermp);
				playermp.setInvulnerable(true);
			});
		}
	}
	
	private Options(File optionsFile) {
		instance = this;
		uhcOptionsFile = optionsFile;

		addOption(new Option("gameMode", "游戏模式", new OptionType.EnumType(EnumMode.class), EnumMode.NORMAL).addTask(taskReselectTeam).setDescription("UHC 对局模式，普通为经典规则，单人为一人一队，Boss 为特殊 Boss 模式。"));
		addOption(new Option("battleType", "战斗类型", new OptionType.EnumType(EnumBattleType.class), EnumBattleType.NORMAL).addTask(taskReselectTeam).setDescription("UHC 战斗类型，普通为经典规则，鞘翅模式为空战，海战为水域战斗。"));
		addOption(new Option("levelType", "地形类型", new OptionType.EnumType(UhcGameManager.EnumLevelType.class), UhcGameManager.EnumLevelType.DEFAULT).addTask(taskReselectTeam).setDescription("世界地形类型，默认是原版地形，放大化为夸张地形。"));
		addOption(new Option("disableOceanBiomes", "禁用海洋群系", new OptionType.BooleanType(), true).addTask(taskReselectTeam).setDescription("非海战模式下不生成任何海洋群系，海洋区域改为同温度的陆地群系，世界以大陆为主。海战模式始终使用纯海洋地形。修改后需要 /uhc regen 重新生成世界才会生效。"));
		addOption(new Option("randomTeams", "随机分队", new OptionType.BooleanType(), true).addTask(taskReselectTeam).setDescription("队伍随机分配还是手动选择，在单人模式下无效。"));
		addOption(new Option("teamCount", "队伍数量", new OptionType.IntegerType(2, 8, 1), 4).addTask(taskReselectTeam).setDescription("不同队伍的数量，只在普通模式下生效。"));
		addOption(new Option("enemyCompass", "敌人指南针", new OptionType.BooleanType(), true).setDescription("启用指南针敌人定位及持有时的方向、距离提示。关闭后恢复普通指南针指向，不移除物品；所有模式立即生效。"));
		addOption(new Option("compassInterval", "定位间隔", new OptionType.IntegerType(0, 300, 5), 0).setDescription("两次敌人定位之间的游戏秒数：0 为自动（普通模式 60 秒，猎人/幽灵猎人 1 秒），1～300 为自定义秒数。左右按钮每次增减 5 秒，也可点击数值直接输入；修改后立即重新定位。"));

		addOption(new Option("difficulty", "游戏难度", new OptionType.EnumType(Difficulty.class), Difficulty.HARD).setDescription("对局使用的游戏难度。"));
		addOption(new Option("weather", "天气", new OptionType.EnumType(UhcGameManager.Weather.class), UhcGameManager.Weather.NORMAL).setDescription("对局中的天气。"));
		addOption(new Option("daylightCycle", "昼夜循环", new OptionType.BooleanType(), true).setDescription("是否启用昼夜循环。"));
		addOption(new Option("friendlyFire", "队友伤害", new OptionType.BooleanType(), false).setDescription("队友之间是否可以互相造成伤害。"));
		addOption(new Option("teamCollision", "队友碰撞", new OptionType.BooleanType(), true).setDescription("队友之间是否会发生碰撞。"));
		addOption(new Option("greenhandProtect", "新手保护", new OptionType.BooleanType(), false).setDescription("前几分钟内降低受到的伤害。"));
		addOption(new Option("forceViewport", "强制旁观", new OptionType.BooleanType(), true).setDescription("死亡后强制跟随队友视角。"));
		addOption(new Option("deathBonus", "死亡增益", new OptionType.BooleanType(), true).setDescription("队友死亡后为其他成员提供短暂增益。"));
		addOption(new Option("TNTBomber", "初始给予TNT", new OptionType.BooleanType(), false).setDescription("小天才模式下给予一组 TNT。"));

		addOption(new Option("borderStart", "初始边界", new OptionType.IntegerType(100, 2000000, 100), 2000).setDescription("世界边界的初始大小。"));
		addOption(new Option("borderEnd", "边界终点", new OptionType.IntegerType(10, 2000000, 10), 200).setDescription("世界边界第一次收缩结束时的大小。"));
		addOption(new Option("borderFinal", "最终边界", new OptionType.IntegerType(10, 2000000, 10), 50).setDescription("世界边界最终缩小到的大小。"));

		addOption(new Option("gameTime", "游戏时长", new OptionType.IntegerType(0, 1000000, 100), 5400).setDescription("整局游戏的总时长。"));
		addOption(new Option("borderStartTime", "边界开始时间", new OptionType.IntegerType(0, 1000000, 100), 1800).setDescription("世界边界开始收缩的时间。"));
		addOption(new Option("borderEndTime", "边界结束时间", new OptionType.IntegerType(0, 1000000, 100), 4800).setDescription("世界边界停止收缩的时间。"));
		addOption(new Option("netherCloseTime", "地狱关闭时间", new OptionType.IntegerType(0, 1000000, 100), 4800).setDescription("地狱与末地被禁用的时间。"));
		addOption(new Option("caveCloseTime", "洞穴关闭时间", new OptionType.IntegerType(0, 1000000, 100), 5100).setDescription("洞穴被禁用的时间。"));
		addOption(new Option("greenhandTime", "新手保护时长", new OptionType.IntegerType(0, 1000000, 100), 4800).setDescription("新手保护持续的时间。"));

		addOption(new Option("merchantFrequency", "商人频率", new OptionType.FloatType(0.0f, 10.0f, 0.05f), 1.0f).setNeedToSave().setDescription("商人出现的频率。"));
		addOption(new Option("oreFrequency", "矿物频率", new OptionType.IntegerType(0, 100, 1), 4).setNeedToSave().setDescription("钻石、青金石和金矿等可变矿物的生成频率。"));
		addOption(new Option("chestFrequency", "奖励宝箱", new OptionType.FloatType(0.0f, 10.0f, 0.1f), 1.0f).setNeedToSave().setDescription("奖励宝箱生成的频率。"));
		addOption(new Option("trappedChestFrequency", "空宝箱", new OptionType.FloatType(0.0f, 1.0f, 0.05f), 0.2f).setNeedToSave().setDescription("空奖励宝箱的出现频率。"));
		addOption(new Option("chestItemFrequency", "宝箱掉落", new OptionType.FloatType(0.0f, 10.0f, 0.1f), 1.0f).setNeedToSave().setDescription("奖励宝箱内可变物品的生成频率。"));
		addOption(new Option("mobCount", "怪物数量", new OptionType.IntegerType(10, 300, 10), 70).setNeedToSave().setDescription("调整世界中的怪物数量。"));
		addOption(new Option("netherPregenerate", "地狱预生成", new OptionType.BooleanType(), true).setDescription("是否在预生成阶段一并预生成地狱。关闭可明显缩短预生成总耗时，地狱区块将在玩家进入时按需生成。"));
		addOption(new Option("pregenerateOnStart", "启动时预生成", new OptionType.BooleanType(), true).setDescription("服务器启动后是否自动预生成世界。关闭后世界创建完即视为就绪，需要预生成时用 /uhc regen 重新生成。"));
		addOption(new Option("pregenerateParallelism", "预生成并行度", new OptionType.IntegerType(1, 16, 1), 2).setDescription("预生成时同时处理的区块数。调高可加快预生成，但会占用更多线程并可能让服务器变卡；默认 2 是对真实服务器最稳妥的取值。"));

		loadPropertiesFile();
		savePropertiesFile();
	}
	
	public void loadPropertiesFile() {
		if (uhcOptionsFile.exists()) {
			try (FileInputStream input = new FileInputStream(uhcOptionsFile)) {
				uhcProperties.load(input);
			} catch (Exception e) {
				LOGGER.warn("Failed to load {}", uhcOptionsFile, e);
			}
		} else {
			LOGGER.warn("{} does not exist", uhcOptionsFile);
		}

		for (Entry<Object, Object> entry : uhcProperties.entrySet()) {
			Option option = configOptions.get((String) entry.getKey());
			if (option != null) {
				option.setInitialValue((String) entry.getValue());
			} else {
				LOGGER.warn("Unknown key {} in {}", entry.getKey(), OPTION_FILE_NAME);
			}
		}
	}
	
	public void savePropertiesFile() {
		try (FileOutputStream output = new FileOutputStream(uhcOptionsFile)) {
			configOptions.values().forEach(opt -> uhcProperties.setProperty(opt.getId(), opt.getStringValue()));
			uhcProperties.store(output, "UHC Game Properties");
		} catch (Exception e) {
			LOGGER.warn("Failed to save {}", this.uhcOptionsFile, e);
		}
	}
	
	private void addOption(Option option) {
		configOptions.put(option.getId(), option);
		optionOrder.add(option.getId());
	}
	
	public Optional<Option> getOption(String option) {
		return Optional.ofNullable(configOptions.get(option));
	}

	public Stream<String> getOptionIdStream() {
		return configOptions.keySet().stream();
	}
	
	/** Every option in registration order. Map iteration order is not usable for anything shown. */
	public List<Option> getOptionsInOrder() {
		return optionOrder.stream().map(configOptions::get).collect(Collectors.toList());
	}
	
	/** The current value of every option, as the same strings {@code uhc.properties} holds. */
	public Map<String, String> snapshot() {
		Map<String, String> values = new LinkedHashMap<>();
		getOptionsInOrder().forEach(opt -> values.put(opt.getId(), opt.getStringValue()));
		return values;
	}
	
	/**
	 * Applies a preset over the current configuration.
	 *
	 * <p>Not all or nothing overall, but all or nothing per load: every value is validated first
	 * and a single unusable one aborts the whole load, because a half applied preset is far more
	 * confusing than a refused one. Two things are deliberately not errors - an id this build does
	 * not know (a preset from another version) is skipped with a warning, and an option the preset
	 * does not mention keeps its current value rather than being reset to its default.
	 *
	 * @param values option id to raw value, as read from a preset file
	 * @return what happened, so the caller can tell the user exactly what did and did not apply
	 */
	public SnapshotResult applySnapshot(Map<String, String> values) {
		SnapshotResult result = new SnapshotResult();
		Map<String, String> usable = new LinkedHashMap<>();
		
		for (Entry<String, String> entry : values.entrySet()) {
			Option option = configOptions.get(entry.getKey());
			if (option == null) {
				result.unknown.add(entry.getKey());
				continue;
			}
			String problem = option.validateStringValue(entry.getValue());
			if (problem != null) {
				result.invalid.add(entry.getKey() + "=" + entry.getValue() + "（" + problem + "）");
			} else if (option.getStringValue().equals(entry.getValue())) {
				result.unchanged.add(entry.getKey());
			} else {
				usable.put(entry.getKey(), entry.getValue());
			}
		}
		
		optionOrder.stream().filter(id -> !values.containsKey(id)).forEach(result.missing::add);
		
		if (!result.invalid.isEmpty()) {
			return result;
		}
		
		runBatch(() -> usable.forEach((id, raw) -> configOptions.get(id).setStringValue(raw)));
		result.applied.addAll(usable.keySet());
		return result;
	}
	
	/**
	 * Runs {@code action} with the shared save and team-reselect tasks deferred, then fires each of
	 * them at most once afterwards. {@link Option#setStringValue} calls {@code updateTasks()}
	 * synchronously, so anything touching many options at once has to go through here.
	 */
	public void runBatch(Runnable action) {
		batchDepth++;
		try {
			action.run();
		} finally {
			batchDepth--;
			if (batchDepth == 0) {
				boolean save = batchSavePending;
				boolean reselect = batchReselectPending;
				batchSavePending = false;
				batchReselectPending = false;
				if (reselect) {
					taskReselectTeam.onUpdate();
				}
				if (save) {
					taskSaveProperties.onUpdate();
				}
			}
		}
	}
	
	public boolean inBatch() {
		return batchDepth > 0;
	}
	
	/** True when a change to this option only shows up after {@code /uhc regen} rebuilds the world. */
	public static boolean affectsWorldGeneration(String optionId) {
		if (WORLD_GENERATION_OPTIONS.contains(optionId)) {
			return true;
		}
		return instance.getOption(optionId).map(Option::needToSave).orElse(false);
	}
	
	/** True when a change to this option is only read again on the next server start. */
	public static boolean affectsServerStart(String optionId) {
		return SERVER_START_OPTIONS.contains(optionId);
	}
	
	/** Outcome of {@link #applySnapshot}, split so the caller can report each case differently. */
	public static class SnapshotResult {
		/** Ids that were changed. */
		public final List<String> applied = Lists.newArrayList();
		/** Ids whose value already matched, so nothing was written for them. */
		public final List<String> unchanged = Lists.newArrayList();
		/** Ids present in the preset but unknown to this build. Warnings, never fatal. */
		public final List<String> unknown = Lists.newArrayList();
		/** {@code id=value} pairs this build knows but cannot parse. Any entry here means nothing was applied. */
		public final List<String> invalid = Lists.newArrayList();
		/** Options of this build that the preset says nothing about; they keep their current value. */
		public final List<String> missing = Lists.newArrayList();
		
		public boolean hasErrors() {
			return !invalid.isEmpty();
		}
	}
	
	public void setOptionValue(String option, Object value) {
		getOption(option).ifPresent(opt -> {
			opt.setValue(value);
		});
	}
	
	public void incOptionValue(String option) {
		getOption(option).ifPresent(Option::incValue);
	}
	
	public void decOptionValue(String option) {
		getOption(option).ifPresent(Option::decValue);
	}
	
	public Object getOptionValue(String option) {
		return getOption(option).map(Option::getValue).orElse(null);
	}
	
	public int getIntegerOptionValue(String option) {
		return (int) getOptionValue(option);
	}
	
	public float getFloatOptionValue(String option) {
		return (float) getOptionValue(option);
	}
	
	public String getStringOptionValue(String option) {
		return (String) getOptionValue(option);
	}
	
	public boolean getBooleanOptionValue(String option) {
		return (boolean) getOptionValue(option);
	}

	public void resetOptions(boolean generate) {
		// Batched for the same reason a preset load is: up to 34 options each firing the shared
		// save task, plus six of them re-sending the config book, is pure waste on a single reset.
		runBatch(() -> configOptions.values().stream().filter(opt -> opt.needToSave() == generate).forEach(Option::reset));
	}

}
