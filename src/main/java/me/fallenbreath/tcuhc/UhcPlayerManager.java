/*
 From Gamepiaynmo: https://github.com/Gamepiaynmo/TC-UHC
 */

package me.fallenbreath.tcuhc;

import com.google.common.collect.Lists;
import me.fallenbreath.tcuhc.UhcGameManager.EnumMode;
import me.fallenbreath.tcuhc.UhcGamePlayer.EnumStat;
import me.fallenbreath.tcuhc.task.Task;
import me.fallenbreath.tcuhc.task.TaskFindPlayer;
import me.fallenbreath.tcuhc.task.TaskKeepSpectate;
import me.fallenbreath.tcuhc.task.TaskOnce;
import me.fallenbreath.tcuhc.util.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.network.packet.s2c.play.OpenWrittenBookS2CPacket;
import net.minecraft.potion.Potions;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class UhcPlayerManager
{
	private final UhcGameManager gameManager;
	
	private final List<UhcGamePlayer> allPlayerList = Lists.newArrayList();
	private final List<UhcGamePlayer> combatPlayerList = Lists.newArrayList();
	private final List<UhcGamePlayer> observePlayerList = Lists.newArrayList();
	private final List<UhcGameTeam> teams = Lists.newArrayList();
	private String lastTeamFormFailureReason;
	
	private int playersPerTeam;
	
	public UhcPlayerManager(UhcGameManager manager) {
		gameManager = manager;
	}
	
	public Optional<ServerPlayerEntity> getPlayerByUUID(UUID id) {
		return Optional.ofNullable(gameManager.getServerPlayerManager().getPlayer(id));
	}
	
	public boolean forceFriendlyView(UhcGamePlayer player) {
		if (player.isAlive()) return false;
		if (!gameManager.getOptions().getBooleanOptionValue("forceViewport")) return false;
		return player.getTeam().getAliveCount() != 0;
	}
	
	public UhcGamePlayer getGamePlayer(PlayerEntity player) {
		for (UhcGamePlayer gamePlayer : allPlayerList) {
			if (gamePlayer.isSamePlayer(player))
				return gamePlayer;
		}
		return null;
	}

	public void onPlayerJoin(ServerPlayerEntity player) {
		UhcGamePlayer gamePlayer = getGamePlayer(player);
		if (gamePlayer == null)
		{
			allPlayerList.add(gamePlayer = new UhcGamePlayer(player));
		}
		if (gameManager.isGamePlaying()) {
			if (combatPlayerList.contains(gamePlayer)) {
				if (gamePlayer.isAlive()) {
					player.changeGameMode(GameMode.SURVIVAL);
					// on game player rejoins, adds:
					// - 5s WEAKNESS II
					// - 5s SLOWNESS II
					// - 3s BLINDNESS I
					player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 1));
					player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 1));
					player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0));
				}
				else player.changeGameMode(GameMode.SPECTATOR);
			} else {
				player.changeGameMode(GameMode.SPECTATOR);
				if (!observePlayerList.contains(gamePlayer))
					observePlayerList.add(gamePlayer);
			}
		} else {
			randomSpawnPosition(player);
			resetHealthAndFood(player);
			if (gameManager.hasGameEnded())
				player.changeGameMode(GameMode.SPECTATOR);
			else {
				player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(20);
				player.changeGameMode(gameManager.getConfigManager().isConfiguring()
						? GameMode.SURVIVAL : GameMode.ADVENTURE);
				regiveConfigItems(player);
				if (gameManager.getConfigManager().isConfiguring())
					player.setInvulnerable(true);
			}
		}
	}
	
	public void regiveConfigItems(ServerPlayerEntity player) {
		if (!gameManager.isGamePlaying()) 
			player.getInventory().clear();
		if (gameManager.getConfigManager().isConfiguring()) {
			this.getGamePlayer(player).getColorSelected().ifPresent(color -> {
				ItemStack teamItem = getTeamItem(color);
				player.equipStack(EquipmentSlot.CHEST, teamItem);
			});
			if (gameManager.getConfigManager().isOperator(player))
				this.giveOrRefreshConfigBook(player);
			this.giveOrRefreshPlayerBook(player);
		}
	}
	
	/**
	 * Gives the operator a config book, or refreshes the one they already hold.
	 *
	 * <p>Never inserts a second copy. {@code /uhc config} is safe to run repeatedly, which matters
	 * because it is also the "set up the next game" entry point.
	 */
	public void giveOrRefreshConfigBook(ServerPlayerEntity player) {
		PlayerInventory inventory = player.getInventory();
		boolean found = false;
		for (int slot = 0; slot < inventory.size(); slot++) {
			if (BookNBT.isTcUhcBook(inventory.getStack(slot), BookNBT.CONFIG_BOOK)) {
				inventory.setStack(slot, BookNBT.getConfigBook(gameManager, gameManager.getConfigManager().getConfigBookPage()));
				found = true;
			}
		}
		if (!found) {
			inventory.insertStack(BookNBT.getConfigBook(gameManager, gameManager.getConfigManager().getConfigBookPage()));
		}
		player.playerScreenHandler.sendContentUpdates();
	}

	/** Same give-or-refresh contract as {@link #giveOrRefreshConfigBook}, for the team-select book. */
	public void giveOrRefreshPlayerBook(ServerPlayerEntity player) {
		PlayerInventory inventory = player.getInventory();
		boolean found = false;
		for (int slot = 0; slot < inventory.size(); slot++) {
			if (BookNBT.isTcUhcBook(inventory.getStack(slot), BookNBT.PLAYER_BOOK)) {
				inventory.setStack(slot, BookNBT.getPlayerBook(gameManager));
				found = true;
			}
		}
		if (!found) {
			inventory.insertStack(BookNBT.getPlayerBook(gameManager));
		}
		player.playerScreenHandler.sendContentUpdates();
	}

	/**
	 * Returns every tracked player to the pre-game lobby state after a match has ended: alive,
	 * un-teamed, full health, survival mode, standing on the spawn platform with the selection
	 * book. Called from {@link UhcGameManager#returnToLobby()}.
	 */
	public void resetForNextGame() {
		teams.forEach(UhcGameTeam::clearTeam);
		teams.clear();
		combatPlayerList.clear();
		observePlayerList.clear();
		lastTeamFormFailureReason = null;
		playersPerTeam = 0;

		for (UhcGamePlayer gamePlayer : Lists.newArrayList(allPlayerList)) {
			gamePlayer.resetForNextGame();
			gamePlayer.getRealPlayer().ifPresent(playermp -> {
				playermp.changeGameMode(GameMode.SURVIVAL);
				playermp.setCameraEntity(playermp);
				playermp.clearStatusEffects();
				playermp.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(20);
				playermp.getInventory().clear();
				this.randomSpawnPosition(playermp);
				this.resetHealthAndFood(playermp);
			});
		}
	}

	public void regiveAdjustBook(ServerPlayerEntity player, boolean force) {
		ItemStack currentStack = player.getInventory().getMainHandStack();
		ItemStack book = BookNBT.getAdjustBook(gameManager);
		if (BookNBT.isTcUhcBook(currentStack))
			player.getInventory().setStack(player.getInventory().selectedSlot, book);
		else if (force) player.getInventory().insertStack(book);
	}
	
	public void removeAdjustBook(ServerPlayerEntity player) {
		ItemStack currentStack = player.getInventory().getMainHandStack();
		if (BookNBT.isTcUhcBook(currentStack, BookNBT.ADJUST_BOOK))
			player.getInventory().setStack(player.getInventory().selectedSlot, ItemStack.EMPTY);
	}
	
	public void refreshConfigBook() {
		UhcGamePlayer operator = gameManager.getConfigManager().getOperator();
		if (operator != null) {
			operator.getRealPlayer().ifPresent(this::refreshConfigBooksInPlace);
		}
	}

	private void refreshConfigBooksInPlace(ServerPlayerEntity player) {
		boolean hasConfigBook = false;
		boolean hasPlayerBook = false;
		boolean reopenMainHandBook = BookNBT.isTcUhcBook(player.getMainHandStack(), BookNBT.CONFIG_BOOK) || BookNBT.isTcUhcBook(player.getMainHandStack(), BookNBT.PLAYER_BOOK);
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (BookNBT.isTcUhcBook(stack, BookNBT.CONFIG_BOOK)) {
				inventory.setStack(slot, BookNBT.getConfigBook(gameManager, gameManager.getConfigManager().getConfigBookPage()));
				hasConfigBook = true;
			} else if (BookNBT.isTcUhcBook(stack, BookNBT.PLAYER_BOOK)) {
				inventory.setStack(slot, BookNBT.getPlayerBook(gameManager));
				hasPlayerBook = true;
			}
		}
		if (!hasConfigBook && gameManager.getConfigManager().isOperator(player)) {
			inventory.insertStack(BookNBT.getConfigBook(gameManager, gameManager.getConfigManager().getConfigBookPage()));
		}
		if (!hasPlayerBook) {
			inventory.insertStack(BookNBT.getPlayerBook(gameManager));
		}
		player.playerScreenHandler.sendContentUpdates();
		// Config books are now rendered as a single-page server-side view, so reopening is safe
		// and ensures the player immediately sees the refreshed settings or requested section.
		if (reopenMainHandBook) {
			player.networkHandler.sendPacket(new OpenWrittenBookS2CPacket(Hand.MAIN_HAND));
		}
	}
	
	private ItemStack getTeamItem(UhcGameColor color) {
		ItemStack stack = new ItemStack(Items.LEATHER_CHESTPLATE);
		int rgb = color.dyeColor.getEntityColor();
		stack.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(rgb, false));
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(color.name + "队胸甲"));
		return stack;
	}
	
	public void randomSpawnPosition(ServerPlayerEntity player) {
		BlockPos pos = SpawnPlatform.getRandomSpawnPosition(UhcGameManager.rand);
		// The lobby is in the overworld, including when returning from another dimension.
		player.teleport(gameManager.getOverWorld(), pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 0.0F, 0.0F);
		player.fallDistance = 0.0f;
	}
	
	public void resetHealthAndFood(ServerPlayerEntity player) {
		player.setHealth(player.getMaxHealth());
		player.getHungerManager().add(20, 20);
	}
	
	public Collection<UhcGamePlayer> getAllPlayers() {
		return allPlayerList;
	}
	
	public Iterable<UhcGamePlayer> getCombatPlayers() {
		return combatPlayerList;
	}
	
	public Iterable<UhcGamePlayer> getObservePlayers() {
		return observePlayerList;
	}
	
	public Iterable<UhcGameTeam> getTeams() {
		return teams;
	}
	
	public boolean isObserver(UhcGamePlayer player) {
		return observePlayerList.contains(player);
	}
	
	public void onPlayerChat(ServerPlayerEntity player, String msg) {
		if (msg == null) return;
		if (!gameManager.isGamePlaying()) {
			gameManager.broadcastMessage(chatMessage(player, msg, false));
			return;
		}
		if (msg.startsWith("p ")) {
			gameManager.broadcastMessage(chatMessage(player, msg.substring(2), false));
			return;
		}
		UhcGamePlayer gamePlayer = getGamePlayer(player);
		if (gamePlayer.getTeam() == null || gamePlayer.getTeam().getAliveCount() == 0) {
			gameManager.broadcastMessage(chatMessage(player, msg, false));
			return;
		}
		String message = chatMessage(player, msg, true);
		gamePlayer.getTeam().getPlayers().forEach(other -> other.getRealPlayer().ifPresent(playermp -> playermp.sendMessage(Text.literal(message), false)));
	}
	
	private String chatMessage(PlayerEntity player, String msg, boolean secret) {
		UhcGamePlayer gamePlayer = getGamePlayer(player);
		Formatting color = gamePlayer.getTeam() == null ? Formatting.WHITE : gamePlayer.getTeam().getTeamColor().chatColor;
		return Formatting.AQUA.toString() + "[" + Formatting.GOLD + (secret ? "队内" : "全体") + Formatting.AQUA.toString() + "]" +
				color + player.getName().getString() + Formatting.YELLOW + ": " + Formatting.WHITE + msg;
	}
	
	public void onPlayerDeath(ServerPlayerEntity player, DamageSource cause) {
		if (gameManager.isGamePlaying()) {
			UhcGamePlayer gamePlayer = getGamePlayer(player);
			if (combatPlayerList.contains(gamePlayer) && gamePlayer.isAlive()) {
				gamePlayer.setDead(gameManager.getGameTimeRemaining());
				broadcastDeathMessage(player, cause);
				spawnDeathLightning(player);
				this.enterSpectatorAfterDeathProcessing(gamePlayer);
				if (gamePlayer.getTeam().getAliveCount() == 0) {
					broadcastTeamEliminated(gamePlayer.getTeam());
					gameManager.checkWinner();
				} else {
					this.deadPotionEffects(gamePlayer.getTeam());
				}

				if (UhcGameManager.getGameMode() == EnumMode.KING && gamePlayer.isKing()) {
					gamePlayer.getTeam().getPlayers().forEach(teamMate -> {
						if (teamMate.isAlive()) {
							gameManager.addTask(new TaskOnce(new Task(){
								@Override
								public void onUpdate() {
									teamMate.getRealPlayer().ifPresent(LivingEntity::kill);
								}
							}));
						}
					});
				}

				// fancy death sound xd
				player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 10000.0F, 0.8F + UhcGameManager.rand.nextFloat() * 0.2F);
			}
		}
		ItemEntity entityitem = player.dropStack(PlayerItems.getPlayerItem(player.getName().getString(), player.isOnFire()));
		if (entityitem != null)
		{
			entityitem.setPickupDelay(40);
		}
	}

	/**
	 * Puts a dead player into spectator mode, but only once vanilla has finished processing the
	 * death.
	 *
	 * <p>This must not happen inline. The UHC death hook injects at the HEAD of
	 * {@code ServerPlayerEntity.onDeath}, and vanilla's own body then does:
	 *
	 * <pre>if (!this.isSpectator()) { this.drop(damageSource); }</pre>
	 *
	 * <p>Switching the gamemode straight away therefore makes vanilla skip {@code drop(...)}
	 * entirely, so {@code dropInventory()} never runs and the inventory is silently never dropped.
	 * Deferring by one task tick keeps the player a survival player for the rest of vanilla's
	 * death handling and restores the drop.
	 */
	private void enterSpectatorAfterDeathProcessing(UhcGamePlayer gamePlayer) {
		gameManager.addTask(new TaskOnce(new Task() {
			@Override
			public void onUpdate() {
				enterSpectatorNow(gamePlayer);
			}
		}));
	}

	/**
	 * Puts a dead player into spectator mode right now.
	 *
	 * <p>Idempotent on purpose. With {@code doImmediateRespawn} on (see
	 * {@code UhcGameManager.initWorlds}) the client asks to respawn the instant it is told about
	 * the death, so this can be reached from two directions in either order: the deferred
	 * post-death task above, and {@link #onPlayerRespawn}. Whichever arrives first wins and the
	 * other is a no-op.
	 */
	private void enterSpectatorNow(UhcGamePlayer gamePlayer) {
		gamePlayer.getRealPlayer().ifPresent(playermp -> {
			if (!playermp.isSpectator()) {
				playermp.changeGameMode(GameMode.SPECTATOR);
			}
		});
		if (gameManager.getOptions().getBooleanOptionValue("forceViewport") && !gamePlayer.isSpectateTaskArmed()) {
			gamePlayer.setSpectateTaskArmed(true);
			gameManager.addTask(new TaskKeepSpectate(gamePlayer));
		}
	}

	/**
	 * Sends a freshly respawned dead player back to where they died.
	 *
	 * <p>An immediate respawn drops them at the world spawn point, which for MARINE is the middle
	 * of the ocean and nowhere near the fight they just lost.
	 */
	private void returnToDeathPos(UhcGamePlayer gamePlayer, ServerPlayerEntity player) {
		Position deathPos = gamePlayer.getDeathPos();
		if (deathPos == null) {
			return;
		}
		ServerWorld deathWorld = gameManager.getMinecraftServer().getWorld(deathPos.dimension);
		if (deathWorld == null) {
			return;
		}
		player.teleport(deathWorld, deathPos.pos.x, deathPos.pos.y, deathPos.pos.z, deathPos.yaw, deathPos.pitch);
	}

	// Keep the original UHC feedback explicit even if vanilla death chat is inconsistent.
	private void broadcastDeathMessage(ServerPlayerEntity player, DamageSource cause) {
		gameManager.broadcastMessage(cause.getDeathMessage(player).getString());
	}

	// Use a cosmetic lightning entity so deaths remain visible without setting blocks on fire.
	private void spawnDeathLightning(ServerPlayerEntity player) {
		if (!(player.getWorld() instanceof ServerWorld)) {
			return;
		}
		ServerWorld serverWorld = (ServerWorld) player.getWorld();
		LightningEntity lightning = EntityType.LIGHTNING_BOLT.create(serverWorld);
		if (lightning == null) {
			return;
		}
		lightning.setCosmetic(true);
		lightning.refreshPositionAfterTeleport(player.getX(), player.getY(), player.getZ());
		serverWorld.spawnEntity(lightning);
	}

	private void broadcastTeamEliminated(UhcGameTeam team) {
		gameManager.broadcastMessage(team.getColorfulTeamName() + Formatting.WHITE + " 已被淘汰。");
	}
	
	private void deadPotionEffects(UhcGameTeam team) {
		if (gameManager.getOptions().getBooleanOptionValue("deathBonus")) {
			for (UhcGamePlayer player : team.getPlayers()) {
				if (player.isAlive()) {
					gameManager.addTask(new TaskFindPlayer(player) {
						@Override
						public void onFindPlayer(ServerPlayerEntity playermp) {
							playermp.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 300, 1));
							playermp.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 300, 1));
							playermp.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 300, 1));
							if (UhcGameManager.getGameMode().doDeathRegen()) {
								float playerCnt = player.getTeam().getPlayerCount();
								if (playerCnt > 1 && playersPerTeam > 1) {
									float regen = 4 * playersPerTeam * (playersPerTeam - 1) / playerCnt / (playerCnt - 1);
									playermp.heal((float) (regen - Math.floor(regen)));
									playermp.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 25 * (int) Math.floor(regen), 1));
								}
							}
						}
					});
				}
			}
		}
	}
	
	public void onPlayerRespawn(ServerPlayerEntity player) {
		if (gameManager.isGamePlaying()) {
			UhcGamePlayer gamePlayer = getGamePlayer(player);
			if (!gamePlayer.isAlive()) {
				// The match runs with doImmediateRespawn on so the client never shows the
				// "Respawn / Title Screen" panel. The cost is that vanilla hands us back a plain
				// survival player standing at the world spawn, so finish the job here: spectator,
				// and back to where they died.
				this.enterSpectatorNow(gamePlayer);
				this.returnToDeathPos(gamePlayer, player);
				player.setCameraEntity(player);
			}
		} else this.randomSpawnPosition(player);
	}
	
	public void onPlayerDamaged(ServerPlayerEntity player, DamageSource source, float amount) {
		if (gameManager.isGamePlaying()) {
			String msg = String.format("你受到了 %.2f 点伤害，来源：", amount);
			String byEnding = source.getAttacker() != null ? "（攻击者：" + source.getAttacker().getName().getString() + "）" : "";
			if (source.isOf(DamageTypes.IN_FIRE)) msg += "火焰";
			else if (source.isOf(DamageTypes.LIGHTNING_BOLT)) msg += "闪电";
			else if (source.isOf(DamageTypes.ON_FIRE)) msg += "燃烧";
			else if (source.isOf(DamageTypes.LAVA)) msg += "岩浆";
			else if (source.isOf(DamageTypes.HOT_FLOOR)) msg += "炽热地面";
			else if (source.isOf(DamageTypes.IN_WALL)) msg += "窒息";
			else if (source.isOf(DamageTypes.CRAMMING)) msg += "挤压";
			else if (source.isOf(DamageTypes.DROWN)) msg += "溺水";
			else if (source.isOf(DamageTypes.STARVE)) msg += "饥饿";
			else if (source.isOf(DamageTypes.CACTUS)) msg += "仙人掌";
			else if (source.isOf(DamageTypes.FALL)) msg += "摔落";
			else if (source.isOf(DamageTypes.FLY_INTO_WALL)) msg += "撞墙飞行";
			else if (source.isOf(DamageTypes.OUT_OF_WORLD)) msg += "虚空";
			else if (source.isOf(DamageTypes.GENERIC)) msg += "未知";
			else if (source.isOf(DamageTypes.MAGIC)) msg += "魔法";
			else if (source.isOf(DamageTypes.WITHER)) msg += "凋零";
			else if (source.isOf(DamageTypes.FALLING_ANVIL)) msg += "铁砧";
			else if (source.isOf(DamageTypes.FALLING_BLOCK)) msg += "坠落方块";
			else if (source.isOf(DamageTypes.DRAGON_BREATH)) msg += "龙息";
			else if (source.isOf(DamageTypes.SWEET_BERRY_BUSH)) msg += "甜浆果丛";
			else if (source.isOf(DamageTypes.EXPLOSION) || source.isOf(DamageTypes.PLAYER_EXPLOSION)) msg += "爆炸" + byEnding;
			else if (source.getSource() instanceof ProjectileEntity) msg += source.getSource().getName().getString() + byEnding;
			else if (source.getAttacker() != null) msg += source.getAttacker().getName().getString();
			else msg += source.getName() + byEnding;
			player.sendMessage(Text.literal(Formatting.RED + msg), false);
			if (source.getAttacker() instanceof ServerPlayerEntity) {
				((ServerPlayerEntity)source.getAttacker()).sendMessage(Text.literal(String.format("%s你对 %s 造成了 %.2f 点伤害", Formatting.BLUE, player.getName().getString(), amount)), false);
			}
		}
	}
	
	public Entity onPlayerSpectate(ServerPlayerEntity player, Entity target, Entity origin) {
		UhcGamePlayer gamePlayer = getGamePlayer(player);
		if (gamePlayer.isAlive()) return target;
		if (!SpectateTargetUtil.isCapableTarget(gamePlayer, target)) {
			return SpectateTargetUtil.getCapableTarget(gamePlayer, origin);
		}
		return target;
	}

	private Optional<UhcGamePlayer> getPlayerByName(String name) {
		return allPlayerList.stream().filter(player -> player.getName().equals(name)).findFirst();
	}
	
	public void killPlayer(String playerName) {
		getPlayerByName(playerName).ifPresent(player -> {
			player.setDead(gameManager.getGameTimeRemaining());
			player.getRealPlayer().ifPresent(playermp -> playermp.changeGameMode(GameMode.SPECTATOR));
			if (gameManager.getOptions().getBooleanOptionValue("forceViewport"))
				gameManager.addTask(new TaskKeepSpectate(player));
			if (player.getTeam() != null) {
				if (player.getTeam().getAliveCount() == 0) {
					broadcastTeamEliminated(player.getTeam());
					gameManager.checkWinner();
				}
				else this.deadPotionEffects(player.getTeam());
				gameManager.broadcastMessage(player.getTeam().getTeamColor().chatColor + player.getName() + Formatting.WHITE + " 被判定为 -1s。");
			}
		});
	}

	/**
	 * @param respawnPos the position to teleport the player to after respawn
	 * @return false if the player is alive or player not found
 	 */
	private boolean resurrectPlayer(String playerName, @Nullable Position respawnPos, boolean cleanInventory, boolean usingMoral) {
		MutableBoolean ret = new MutableBoolean(false);
		getPlayerByName(playerName).ifPresent(player -> {
			if (player.isAlive || !player.getRealPlayer().isPresent()) {
				ret.setFalse();
				return;
			}

			// init player

			player.deathTime = 0;
			player.isAlive = true;
			player.getStat().setStat(EnumStat.ALIVE_TIME, 0);
			// apply mode-specific permanent effects first so temporary Resistance V spawn
			// protection is layered on top (kept as hidden effect) instead of discarding them
			if (UhcGameManager.getGameMode() == EnumMode.GHOST)
				player.addGhostModeEffect();
			else if (UhcGameManager.getGameMode() == EnumMode.BOMBER) {
				player.addBomberModeEffect();
				player.addGhostModeEffect();
			}
			player.getRealPlayer().ifPresent(playermp -> {
				playermp.changeGameMode(GameMode.SURVIVAL);
				if (respawnPos != null) {
					// 5s Resistance V + 3s Blindness I
					playermp.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 100, 4));
					playermp.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0));
					if (cleanInventory) {
						playermp.getInventory().clear();
					}
					playermp.teleport(
							UhcGameManager.instance.getMinecraftServer().getWorld(respawnPos.dimension),
							respawnPos.pos.x, respawnPos.pos.y, respawnPos.pos.z, respawnPos.yaw, respawnPos.pitch
					);
				}
			});
			player.resetDeathPos();

			// side effects & broadcast

			if (usingMoral) {
				player.getRealPlayer().ifPresent(playermp -> playermp.setHealth(1.0F));
			}
			if (player.getTeam() != null) {
				String msg;
				if (usingMoral) {
					msg = " 已被道德值复活。";
				} else {
					msg = " 获得了 +1s" + (respawnPos != null ? "，并回到了死亡地点。" : "，且保留了背包。");
				}
				gameManager.broadcastMessage(player.getTeam().getTeamColor().chatColor + player.getName() + Formatting.WHITE + msg);
			}
			ret.setTrue();
		});
		return ret.getValue();
	}

	public boolean resurrectPlayerUsingCommand(String playerName, boolean cleanInventory, boolean teleportBack) {
		Optional<UhcGamePlayer> optionalPlayer = getPlayerByName(playerName);
		return optionalPlayer.isPresent() && resurrectPlayer(playerName, teleportBack ? optionalPlayer.get().getDeathPos() : null, cleanInventory, false);
	}

	public boolean resurrectPlayerUsingMoral(String playerName, Position respawnPos) {
		return resurrectPlayer(playerName, respawnPos, true, true);
	}
	
	public boolean formTeams(boolean auto) {
		lastTeamFormFailureReason = null;
		this.refreshOnlinePlayers();
		return auto ? this.automaticFormTeams() : this.manuallyFormTeams();
	}

	public Optional<String> getLastTeamFormFailureReason()
	{
		return Optional.ofNullable(lastTeamFormFailureReason);
	}

	private boolean failTeamForm(String reason)
	{
		lastTeamFormFailureReason = reason;
		return false;
	}
	
	private boolean automaticFormTeams() {
		Optional<ServerPlayerEntity> operator = gameManager.getConfigManager().getOperator().getRealPlayer();
		boolean alright = true;
		for (UhcGamePlayer gamePlayer : getAllPlayers()) {
			UhcGameColor color = gamePlayer.getColorSelected().orElse(null);
			if (color == null) {
				gamePlayer.getRealPlayer().ifPresent(player -> player.sendMessage(Text.literal(Formatting.DARK_RED + "请选择一个队伍加入，其他人还在等你！"), false));
				operator.ifPresent(player -> player.sendMessage(Text.literal(Formatting.DARK_RED + gamePlayer.getName()), false));
				alright = false;
			} else {
				if (color == UhcGameColor.WHITE) observePlayerList.add(gamePlayer);
				else combatPlayerList.add(gamePlayer);
			}
		}
		
		if (!alright) {
			operator.ifPresent(player -> player.sendMessage(Text.literal(Formatting.DARK_RED + "仍有玩家尚未完成选择。"), false));
			return failTeamForm("仍有玩家尚未完成选择，请先完成分队或阵营选择。");
		}
		
		teams.clear();
		switch (UhcGameManager.getGameMode()) {
			case NORMAL:
			case KING:{
				int playerCount = combatPlayerList.size();
				int teamCount = gameManager.getOptions().getIntegerOptionValue("teamCount");
				playersPerTeam = playerCount / teamCount + (playerCount % teamCount == 0 ? 0 : 1);
				int morePlayers = playerCount % teamCount;
				int[] randomTeam = new int[playerCount];
				int posCnt = 0;
				for (int i = 0; i < teamCount; i++) {
					for (int j = (morePlayers > 0 && i >= morePlayers ? 1 : 0); j < playersPerTeam; j++)
						randomTeam[posCnt++] = i;
					teams.add(new UhcGameTeam().setColorTeam(UhcGameColor.getColor(i)));
				}
				for (int i = 0; i < playerCount; i++) {
					int pos = UhcGameManager.rand.nextInt(playerCount - i) + i;
					int temp = randomTeam[i];
					randomTeam[i] = randomTeam[pos];
					randomTeam[pos] = temp;
					teams.get(randomTeam[i]).addPlayer(combatPlayerList.get(i));
				}
				break;
			}
			case SOLO:
			case GHOST:
			case BOMBER: {
				combatPlayerList.stream().map(player -> new UhcGameTeam().setPlayerTeam(player)).forEach(teams::add);
				playersPerTeam = 1;
				break;
			}
			case HUNTER:
			case GHOSTHUNTER:
			case BOSS: {
				UhcGamePlayer boss = combatPlayerList.get(UhcGameManager.rand.nextInt(combatPlayerList.size()));
				teams.add(new UhcGameTeam().setColorTeam(UhcGameColor.RED).addPlayer(boss));
				UhcGameTeam team = new UhcGameTeam().setColorTeam(UhcGameColor.BLUE);
				combatPlayerList.stream().filter(player -> player != boss).forEach(team::addPlayer);
				teams.add(team);
				playersPerTeam = combatPlayerList.size() - 1;
				break;
			}
		}
		return true;
	}
	
	private boolean manuallyFormTeams() {
		Optional<ServerPlayerEntity> operator = gameManager.getConfigManager().getOperator().getRealPlayer();
		boolean alright = true;
		for (UhcGamePlayer gamePlayer : getAllPlayers()) {
			UhcGameColor color = gamePlayer.getColorSelected().orElse(null);
			if (color == null) {
				gamePlayer.getRealPlayer().ifPresent(player -> player.sendMessage(Text.literal(Formatting.DARK_RED + "请选择一个队伍加入，其他人还在等你！"), false));
				operator.ifPresent(player -> player.sendMessage(Text.literal(Formatting.DARK_RED + gamePlayer.getName()), false));
				alright = false;
			} else {
				if (color == UhcGameColor.WHITE) observePlayerList.add(gamePlayer);
				else combatPlayerList.add(gamePlayer);
			}
		}
		
		if (!alright) {
			operator.ifPresent(player -> player.sendMessage(Text.literal(Formatting.DARK_RED + "仍有玩家尚未完成选择。"), false));
			return failTeamForm("仍有玩家尚未完成选择，请先完成分队或阵营选择。");
		}
		
		teams.clear();
		switch (UhcGameManager.getGameMode()) {
			case NORMAL:
			case KING: {
				int playerCount = combatPlayerList.size();
				int teamCount = gameManager.getOptions().getIntegerOptionValue("teamCount");
				for (int i = 0; i < teamCount; i++) {
					teams.add(new UhcGameTeam().setColorTeam(UhcGameColor.getColor(i)));
				}

				List<UhcGamePlayer> randomPlayers = Lists.newArrayList();
				combatPlayerList.forEach(player -> {
					UhcGameColor color = player.getColorSelected().orElse(UhcGameColor.WHITE);
					if (color != UhcGameColor.BLACK) {
						teams.get(color.getId()).addPlayer(player);
					} else {
						randomPlayers.add(player);
					}
				});

				for (int i = 0; i < teamCount; i++) {
					int pos = UhcGameManager.rand.nextInt(teamCount);
					UhcGameTeam temp = teams.get(i);
					teams.set(i, teams.get(pos));
					teams.set(pos, temp);
				}

				for (int i = 0; i < randomPlayers.size(); i++) {
					int pos = UhcGameManager.rand.nextInt(randomPlayers.size());
					UhcGamePlayer temp = randomPlayers.get(i);
					randomPlayers.set(i, randomPlayers.get(pos));
					randomPlayers.set(pos, temp);
				}

				randomPlayers.forEach(player -> {
					UhcGameTeam team = teams.get(0);
					for (int i = 1; i < teamCount; i++) {
						if (teams.get(i).getPlayerCount() < team.getPlayerCount())
							team = teams.get(i);
					}

					team.addPlayer(player);
				});

				playersPerTeam = teams.stream().mapToInt(UhcGameTeam::getPlayerCount).max().orElse(0);
				break;
			}
			case SOLO:
			case GHOST:
			case BOMBER: {
				combatPlayerList.stream().map(player -> new UhcGameTeam().setPlayerTeam(player)).forEach(teams::add);
				playersPerTeam = 1;
				break;
			}
			case BOSS: {
				UhcGamePlayer boss = null;
				for (UhcGamePlayer player : combatPlayerList) {
					if (player.getColorSelected().orElse(UhcGameColor.BLUE) == UhcGameColor.RED) {
						if (boss == null) boss = player;
						else if(UhcGameManager.getGameMode() == EnumMode.BOSS) {
							player.getRealPlayer().ifPresent(playermp -> playermp.sendMessage(Text.literal(Formatting.DARK_RED + "Boss 阵营不能超过一人。"), false));
							alright = false;
						}
					}
				}
				if (!alright) {
					operator.ifPresent(player -> player.sendMessage(Text.literal(Formatting.DARK_RED + "当前被选择为 Boss 的玩家超过一人。"), false));
					return failTeamForm("Boss 模式下只能有一名玩家被选择为 Boss。" );
				}
				teams.add(new UhcGameTeam().setColorTeam(UhcGameColor.RED).addPlayer(boss));
				UhcGameTeam team = new UhcGameTeam().setColorTeam(UhcGameColor.BLUE);
				final UhcGamePlayer playerBoss = boss;
				combatPlayerList.stream().filter(player -> player != playerBoss).forEach(team::addPlayer);
				teams.add(team);
				playersPerTeam = combatPlayerList.size() - 1;
				break;
			}
			case HUNTER:
			case GHOSTHUNTER: {
				UhcGameTeam preyTeam = new UhcGameTeam().setColorTeam(UhcGameColor.RED);
				UhcGameTeam hunterTeam = new UhcGameTeam().setColorTeam(UhcGameColor.BLUE);
				teams.add(preyTeam);
				teams.add(hunterTeam);
				for (UhcGamePlayer player : combatPlayerList) {
					if (player.getColorSelected().orElse(UhcGameColor.BLUE) == UhcGameColor.RED)
						preyTeam.addPlayer(player);
					else
						hunterTeam.addPlayer(player);
				}
				if (preyTeam.getPlayerCount() == 0 || hunterTeam.getPlayerCount() == 0 ) {
					operator.ifPresent(player -> player.sendMessage(Text.literal(Formatting.DARK_RED + "猎人模式中必须同时存在猎物和猎人。"), false));
					return failTeamForm("猎人模式中必须同时存在至少一名猎物和一名猎人。");
				}
				playersPerTeam = 1;
				break;
			}
		}
		
		return true;
	}
	
	protected UhcGamePlayer getBossPlayer() {
		if (UhcGameManager.getGameMode() == EnumMode.BOSS) {
			return teams.get(0).getPlayers().iterator().next();
		}
		return null;
	}

	public UhcGameTeam getPreyTeam() {
		if (UhcGameManager.getGameMode() == EnumMode.HUNTER || UhcGameManager.getGameMode() == EnumMode.GHOSTHUNTER) {
			return teams.get(0);
		}
		return null;
	}

	public UhcGameTeam getHunterTeam() {
		if (UhcGameManager.getGameMode() == EnumMode.HUNTER || UhcGameManager.getGameMode() == EnumMode.GHOSTHUNTER) {
			return teams.get(1);
		}
		return null;
	}
	
	public void setupIngameTeams() {
		Scoreboard scoreboard = gameManager.getMainScoreboard();
		for (Object team : scoreboard.getTeams().toArray())
			scoreboard.removeTeam((Team)team);
		boolean teamFire = gameManager.getOptions().getBooleanOptionValue("friendlyFire");
		boolean teamColl = gameManager.getOptions().getBooleanOptionValue("teamCollision");
		for (UhcGameTeam team : teams) {
			Team spTeam = scoreboard.addTeam(team.getTeamName());
			spTeam.setColor(team.getTeamColor().chatColor);
			spTeam.setFriendlyFireAllowed(teamFire);
			spTeam.setCollisionRule(teamColl ? AbstractTeam.CollisionRule.ALWAYS : AbstractTeam.CollisionRule.PUSH_OTHER_TEAMS);
			gameManager.broadcastMessage(team.getColorfulTeamName() + " Members:");
			for (UhcGamePlayer player : team.getPlayers()) {
				scoreboard.addScoreHolderToTeam(player.getName(), spTeam);
				String message = "    " + team.getTeamColor().chatColor + player.getName();
				if (player.isKing()) message += " [KING]";
				gameManager.broadcastMessage(message);
			}
		}
	}
	
	private void addInitialEquipments(BlockPos pos, int playerCnt) {
		World world = gameManager.getOverWorld();
		BlockEntity te = world.getBlockEntity(pos);
		if (!(te instanceof ChestBlockEntity)) return;
		ChestBlockEntity chest = (ChestBlockEntity) te;
		int slot = 0;
		chest.setStack(slot++, new ItemStack(Items.WOODEN_AXE));
		chest.setStack(slot++, new ItemStack(Items.WOODEN_SWORD));
		if (UhcGameManager.getBattleType() == UhcGameManager.EnumBattleType.MARINE) {
			for(int i =0; i < playerCnt; i ++)
				chest.setStack(slot++, new ItemStack(Items.OAK_BOAT));
		}
		switch (UhcGameManager.getGameMode()) {
			case BOMBER:{
				int firstArrowSlot = slot;
				ItemStack item1 = createBomberArrowStack();
				ItemStack item2 = createBomberArrowStack();
				chest.setStack(slot++, item1);
				chest.setStack(slot++, item2);
				if (gameManager.getOptions().getBooleanOptionValue("TNTBomber"))
					chest.setStack(slot++, new ItemStack(Items.TNT, 64));
				break;
			}
		}
		if (gameManager.getOptions().getBooleanOptionValue("greenhandProtect"))
			chest.setStack(slot++, new ItemStack(Items.GOLDEN_APPLE, playerCnt));
		chest.markDirty();
	}

	private static ItemStack createBomberArrowStack() {
		return PotionContentsComponent.createStack(Items.TIPPED_ARROW, Potions.LUCK).copyWithCount(64);
	}
	
	public void spreadPlayers() {
		
		class TaskInitPlayer extends TaskFindPlayer {
			private final BlockPos homePos;
			private final double health;
			public TaskInitPlayer(UhcGamePlayer player, BlockPos pos, double health) {
				super(player);
				this.homePos = pos;
				this.health = health;
			}
			@Override
			public void onFindPlayer(ServerPlayerEntity player) {
				BlockPos newpos = homePos.add(UhcGameManager.rand.nextInt(5) - 2, 0, UhcGameManager.rand.nextInt(5) - 2);
				player.requestTeleport(newpos.getX() + 0.5, newpos.getY() + 0.5, newpos.getZ() + 0.5);
				player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(health);
				player.fallDistance = 0.0f;
				player.getInventory().clear();
				player.changeGameMode(GameMode.ADVENTURE);
			}
		}
		
		World world = gameManager.getOverWorld();
		int borderStart = gameManager.getOptions().getIntegerOptionValue("borderStart");
		switch (UhcGameManager.getGameMode()) {
			case NORMAL:
			case KING: {
				SpawnPosition spawnPosition = new SpawnPosition(teams.size(), borderStart);
				for (UhcGameTeam team : teams) {
					final BlockPos pos = gameManager.buildSmallHouse(spawnPosition.nextPos(), team.getTeamColor().dyeColor);
					this.addInitialEquipments(pos, team.getPlayerCount());
					double teamHealth = 20.0 * this.playersPerTeam / team.getPlayerCount();
					team.getPlayers().forEach(player -> gameManager.addTask(new TaskInitPlayer(player, pos, teamHealth)));
				}
				break;
			}
			case SOLO:
			case GHOST:
			case BOMBER:
			case BOSS:
			case HUNTER:
			case GHOSTHUNTER: {
				SpawnPosition spawnPosition = new SpawnPosition(combatPlayerList.size(), borderStart);
				double maxHealth = 20.0;
				if(UhcGameManager.getGameMode() != EnumMode.HUNTER && UhcGameManager.getGameMode() != EnumMode.GHOSTHUNTER)
					maxHealth *= playersPerTeam;
				for (UhcGamePlayer player : combatPlayerList) {
					final BlockPos pos = gameManager.buildSmallHouse(spawnPosition.nextPos(), player.getTeam().getTeamColor().dyeColor);
					this.addInitialEquipments(pos, 1);
					gameManager.addTask(new TaskInitPlayer(player, pos, player.getTeam().getPlayerCount() == 1 ? maxHealth : 20));
				}
				break;
			}
		}
		
		for (UhcGamePlayer player : observePlayerList) {
			player.getRealPlayer().ifPresent(playermp -> playermp.changeGameMode(GameMode.SPECTATOR));
		}
	}
	
	public void refreshOnlinePlayers() {
		List<UhcGamePlayer> toRemove = Lists.newArrayList();
		allPlayerList.stream().filter(player -> !player.getRealPlayer().isPresent()).forEach(toRemove::add);
		allPlayerList.removeAll(toRemove);
		combatPlayerList.clear();
		observePlayerList.clear();
		teams.forEach(UhcGameTeam::clearTeam);
		teams.clear();
	}

}
