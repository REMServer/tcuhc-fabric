/*
 From Gamepiaynmo: https://github.com/Gamepiaynmo/TC-UHC
 */

package me.fallenbreath.tcuhc.task;

import me.fallenbreath.tcuhc.UhcGameManager;
import me.fallenbreath.tcuhc.UhcGamePlayer;
import me.fallenbreath.tcuhc.UhcGameTeam;
import me.fallenbreath.tcuhc.UhcPlayerManager;
import me.fallenbreath.tcuhc.options.Options;
import me.fallenbreath.tcuhc.task.Task.TaskTimer;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.number.FixedNumberFormat;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public class TaskScoreboard extends TaskTimer {
	
	private final int borderStart, borderEnd;
	private final int gameTime, startTime, endTime, netherTime, caveTime;
	
	public static final String scoreName = "time";
	public static final String displayName = "对局信息";
	public static final String[] lines = { "剩余时间：", "边界半径：", "地狱关闭：", "洞穴关闭：" };
	private static final String ALIVE_PLAYERS = "存活人数：";
	private static final String ALIVE_TEAMS = "存活队伍：";
	private static final String SAFE_HEIGHT = "安全高度范围：";
	private static final String BORDER_CENTER = "边界中心：";
	private final List<UhcGamePlayer> participants = new ArrayList<>();
	private final List<UhcGameTeam> participatingTeams = new ArrayList<>();
	
	private Scoreboard scoreboard;
	private ScoreboardObjective objective;
	
	public TaskScoreboard() {
		super(0, 20);
		
		Options options = UhcGameManager.instance.getOptions();
		borderStart = options.getIntegerOptionValue("borderStart");
		borderEnd = options.getIntegerOptionValue("borderEnd");
		gameTime = options.getIntegerOptionValue("gameTime");
		int[] borderTimes = UhcGameManager.getScaledBorderTimes();
		startTime = borderTimes[0];
		endTime = borderTimes[1];
		netherTime = options.getIntegerOptionValue("netherCloseTime");
		caveTime = options.getIntegerOptionValue("caveCloseTime");
		// Snapshot this match's roster: spectators and configured-but-empty teams do not count.
		UhcPlayerManager playerManager = UhcGameManager.instance.getUhcPlayerManager();
		playerManager.getCombatPlayers().forEach(participants::add);
		playerManager.getTeams().forEach(team -> {
			if (team.getPlayerCount() > 0) participatingTeams.add(team);
		});
		
		scoreboard = UhcGameManager.instance.getMainScoreboard();
		if ((objective = scoreboard.getNullableObjective(scoreName)) == null) {
			objective = scoreboard.addObjective(scoreName, ScoreboardCriterion.DUMMY, Text.literal(displayName), ScoreboardCriterion.RenderType.INTEGER, true, null);
		}
		objective.setDisplayName(Text.literal(displayName));
		// A previous match or an older save may still contain late-game boundary rows.
		for (String oldLine : new String[] { SAFE_HEIGHT, BORDER_CENTER,
				"边界最低 Y：", "边界最高 Y：", "边界中心 X：", "边界中心 Z：" }) {
			scoreboard.removeScore(ScoreHolder.fromName(oldLine), objective);
		}
		scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, objective);
		setTimeRow(lines[0], gameTime);
		scoreboard.getOrCreateScore(ScoreHolder.fromName(lines[1]), objective).setScore(borderStart / 2);
		setTimeRow(lines[2], netherTime);
		setTimeRow(lines[3], caveTime);
		updateSurvivalCounts();
		
		UhcGameManager.instance.addTask(new TaskNetherCave());
	}
	
	private int getBorderPosition() {
		return (int) UhcGameManager.instance.getOverWorld().getWorldBorder().getSize();
	}
	
	@Override
	public void onTimer() {
		if (this.hasFinished() || !UhcGameManager.instance.isGamePlaying()) {
			this.setCanceled();
			return;
		}
		updateSurvivalCounts();
		ScoreAccess score = scoreboard.getOrCreateScore(ScoreHolder.fromName(lines[0]), objective);
		int timeRemaining = score.getScore();
		setTimeRow(lines[0], timeRemaining - 1);
		if (timeRemaining == gameTime - startTime) {
			UhcGameManager.instance.addTask(new TaskBorderReminder());
		}
		scoreboard.getOrCreateScore(ScoreHolder.fromName(lines[1]), objective).setScore(getBorderPosition() / 2);

		score = scoreboard.getOrCreateScore(ScoreHolder.fromName(lines[2]), objective);
		setTimeRow(lines[2], Math.max(0, score.getScore() - 1));

		score = scoreboard.getOrCreateScore(ScoreHolder.fromName(lines[3]), objective);
		setTimeRow(lines[3], Math.max(0, score.getScore() - 1));

		if (UhcGameManager.getGameMode() == UhcGameManager.EnumMode.HUNTER && timeRemaining == 0) {
			UhcGameManager.instance.onTeamWin(UhcGameManager.instance.getUhcPlayerManager().getPreyTeam());
		}
	}

	private void setTimeRow(String label, int seconds) {
		// Only format the visible value; match rules continue reading the raw seconds.
		int visibleSeconds = Math.max(0, seconds);
		setTextRow(scoreboard, objective, label, seconds,
				visibleSeconds / 60 + "分" + visibleSeconds % 60 + "秒");
	}

	private void updateSurvivalCounts() {
		long alivePlayers = participants.stream().filter(UhcGamePlayer::isAlive).count();
		long aliveTeams = participatingTeams.stream().filter(team -> team.getAliveCount() > 0).count();
		setTextRow(scoreboard, objective, ALIVE_PLAYERS, -1, alivePlayers + " / " + participants.size());
		setTextRow(scoreboard, objective, ALIVE_TEAMS, -2, aliveTeams + " / " + participatingTeams.size());
	}

	public static void updateFinalBoundary(int minY, int maxY, int centerX, int centerZ) {
		Scoreboard scoreboard = UhcGameManager.instance.getMainScoreboard();
		ScoreboardObjective objective = scoreboard.getNullableObjective(scoreName);
		if (objective == null) return;
		setTextRow(scoreboard, objective, SAFE_HEIGHT, -3, minY + "～" + maxY);
		setTextRow(scoreboard, objective, BORDER_CENTER, -4, "X " + centerX + "，Z " + centerZ);
	}

	private static void setTextRow(Scoreboard scoreboard, ScoreboardObjective objective,
			String label, int order, String value) {
		ScoreHolder holder = ScoreHolder.fromName(label);
		ReadableScoreboardScore previous = scoreboard.getScore(holder, objective);
		Text formatted = Text.literal(value).formatted(Formatting.RED);
		boolean changed = previous == null || previous.getNumberFormat() == null
				|| !previous.getNumberFormat().format(order).equals(formatted);
		ScoreAccess score = scoreboard.getOrCreateScore(holder, objective);
		// Native 1.21.1 number formats display text while keeping these rows in a stable order.
		// Existing timer scores remain numeric because match rules read them directly.
		score.setScore(order);
		if (changed) score.setNumberFormat(new FixedNumberFormat(formatted));
	}

	public static void hideScoreboard() {
		UhcGameManager.instance.getMainScoreboard().setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, null);
	}

}
