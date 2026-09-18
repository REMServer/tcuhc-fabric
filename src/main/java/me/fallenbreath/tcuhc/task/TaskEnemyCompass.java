package me.fallenbreath.tcuhc.task;

import me.fallenbreath.tcuhc.UhcGameManager;
import me.fallenbreath.tcuhc.UhcGamePlayer;
import me.fallenbreath.tcuhc.UhcPlayerManager;
import me.fallenbreath.tcuhc.util.CompassHint;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlayerSpawnPositionS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Match-scoped fixes: the HUD follows the viewer, never an enemy between scans. */
public class TaskEnemyCompass extends Task.TaskTimer {
	private record Fix(UUID target, BlockPos position, long tick) {}
	private final Map<UUID, Fix> fixes = new HashMap<>();
	private final Set<UUID> visible = new HashSet<>();
	private final Set<UUID> directed = new HashSet<>();
	private long nextScan;
	private int lastIntervalTicks = -1;

	public TaskEnemyCompass() {
		super(0, 5);
	}

	@Override
	public void onTimer() {
		UhcGameManager game = UhcGameManager.instance;
		if (hasFinished() || !game.isGamePlaying()) {
			cancel();
			return;
		}
		if (!game.getOptions().getBooleanOptionValue("enemyCompass")) {
			resetTracking();
			nextScan = 0;
			return;
		}
		int seconds = game.getOptions().getIntegerOptionValue("compassInterval");
		int intervalTicks = seconds == 0 ? (isHunt() ? 20 : 1200) : seconds * 20;
		if (intervalTicks != lastIntervalTicks) {
			lastIntervalTicks = intervalTicks;
			nextScan = 0;
		}
		long now = game.getOverWorld().getTime();
		boolean scan = now >= nextScan;
		if (scan) nextScan = now + intervalTicks;
		Set<UUID> online = new HashSet<>();
		for (ServerPlayerEntity viewer : game.getServerPlayerManager().getPlayerList()) {
			UUID id = viewer.getUuid();
			online.add(id);
			UhcGamePlayer participant = game.getUhcPlayerManager().getGamePlayer(viewer);
			if (participant == null || !participant.isAlive() || participant.getTeam() == null || viewer.isSpectator() || !viewer.isAlive()) {
				clear(viewer);
				continue;
			}
			boolean holding = isPlainCompass(viewer.getMainHandStack()) || isPlainCompass(viewer.getOffHandStack());
			// The existing spawn-position compass only works in the Overworld. Do not
			// show a believable arrow beside a randomly spinning Nether compass.
			if (!viewer.getWorld().getRegistryKey().equals(World.OVERWORLD)) {
				clear(viewer);
				show(viewer, holding, "敌人定位 · 普通指南针仅支持主世界");
				continue;
			}
			if (scan) {
				ServerPlayerEntity target = nearestEnemy(participant, viewer);
				Fix fix = new Fix(target == null ? null : target.getUuid(), target == null ? null : target.getBlockPos().toImmutable(), now);
				fixes.put(id, fix);
				if (target != null) {
					point(viewer, fix.position());
				} else {
					resetDirection(viewer);
				}
			}
			Fix fix = fixes.get(id);
			if (fix != null && fix.target() != null) {
				ServerPlayerEntity target = game.getServerPlayerManager().getPlayer(fix.target());
				if (!isEnemy(participant, viewer, target)) {
					fixes.remove(id);
					resetDirection(viewer);
					fix = null;
				}
			}
			String hint;
			if (fix == null) {
				hint = "敌人定位 · 等待下次定位";
			} else if (fix.target() == null) {
				hint = "同维度暂无敌人 · " + Math.max(0, (now - fix.tick()) / 20) + " 秒前扫描";
			} else {
				hint = CompassHint.format(fix.position().getX() + 0.5 - viewer.getX(),
					fix.position().getZ() + 0.5 - viewer.getZ(), viewer.getYaw(), (now - fix.tick()) / 20);
				// Reapply when taking out a compass, including after a client respawn.
				if (holding && !visible.contains(id)) point(viewer, fix.position());
			}
			show(viewer, holding, hint);
		}
		fixes.keySet().retainAll(online);
		visible.retainAll(online);
		directed.retainAll(online);
	}

	private static boolean isHunt() {
		return UhcGameManager.getGameMode() == UhcGameManager.EnumMode.HUNTER
			|| UhcGameManager.getGameMode() == UhcGameManager.EnumMode.GHOSTHUNTER;
	}

	private static boolean isPlainCompass(ItemStack stack) {
		return stack.isOf(Items.COMPASS) && !stack.contains(DataComponentTypes.LODESTONE_TRACKER);
	}

	private static boolean isEnemy(UhcGamePlayer participant, ServerPlayerEntity viewer, ServerPlayerEntity candidate) {
		if (candidate == null || candidate == viewer || !candidate.isAlive() || candidate.isSpectator()
			|| candidate.getWorld() != viewer.getWorld()) return false;
		UhcPlayerManager players = UhcGameManager.instance.getUhcPlayerManager();
		UhcGamePlayer enemy = players.getGamePlayer(candidate);
		return enemy != null && enemy.isAlive() && enemy.getTeam() != null
			&& enemy.getTeam() != participant.getTeam()
			&& (!isHunt() || enemy.getTeam() == players.getPreyTeam());
	}

	private static ServerPlayerEntity nearestEnemy(UhcGamePlayer participant, ServerPlayerEntity viewer) {
		ServerPlayerEntity nearest = null;
		double distance = Double.POSITIVE_INFINITY;
		for (UhcGamePlayer candidate : UhcGameManager.instance.getUhcPlayerManager().getCombatPlayers()) {
			ServerPlayerEntity entity = candidate.getRealPlayer().orElse(null);
			if (isEnemy(participant, viewer, entity)) {
				double squaredDistance = viewer.squaredDistanceTo(entity);
				if (squaredDistance < distance) {
					nearest = entity;
					distance = squaredDistance;
				}
			}
		}
		return nearest;
	}

	private void point(ServerPlayerEntity viewer, BlockPos position) {
		viewer.networkHandler.sendPacket(new PlayerSpawnPositionS2CPacket(position, 0));
		directed.add(viewer.getUuid());
	}

	private void resetDirection(ServerPlayerEntity viewer) {
		if (directed.remove(viewer.getUuid())) {
			var world = UhcGameManager.instance.getOverWorld();
			viewer.networkHandler.sendPacket(new PlayerSpawnPositionS2CPacket(world.getSpawnPos(), world.getSpawnAngle()));
		}
	}

	private void show(ServerPlayerEntity viewer, boolean holding, String hint) {
		if (holding) {
			viewer.sendMessage(Text.literal(hint).formatted(Formatting.WHITE), true);
			visible.add(viewer.getUuid());
		} else if (visible.remove(viewer.getUuid())) {
			viewer.sendMessage(Text.empty(), true);
		}
	}

	private void clear(ServerPlayerEntity viewer) {
		fixes.remove(viewer.getUuid());
		resetDirection(viewer);
		show(viewer, false, "");
	}

	@Override
	public void cancel() {
		setCanceled();
		resetTracking();
	}

	private void resetTracking() {
		if (fixes.isEmpty() && visible.isEmpty() && directed.isEmpty()) return;
		UhcGameManager.instance.getServerPlayerManager().getPlayerList().forEach(this::clear);
		fixes.clear();
		visible.clear();
		directed.clear();
	}
}
