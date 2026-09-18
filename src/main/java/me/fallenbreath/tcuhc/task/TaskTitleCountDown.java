/*
 From Gamepiaynmo: https://github.com/Gamepiaynmo/TC-UHC
 */

package me.fallenbreath.tcuhc.task;

import me.fallenbreath.tcuhc.UhcGameColor;
import me.fallenbreath.tcuhc.UhcGameManager;
import me.fallenbreath.tcuhc.UhcGameTeam;
import me.fallenbreath.tcuhc.UhcPlayerManager;
import me.fallenbreath.tcuhc.task.Task.TaskTimer;
import me.fallenbreath.tcuhc.util.TitleUtil;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collections;
import java.util.Optional;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

public class TaskTitleCountDown extends TaskTimer {
	
	private int count;

	public TaskTitleCountDown(int init, int delay, int interval) {
		super(delay, interval);
		count = init;
	}
	
	@Override
	public void onTimer() {
		TitleUtil.sendTitleToAllPlayers(Formatting.GOLD + String.valueOf(--count), null);
		if (count == 0) this.setCanceled();
	}
	
	@Override
	public void onFinish() {
		UhcGameManager.EnumMode mode = UhcGameManager.getGameMode();
		String modeSubtitle = mode.toString() + " - " + UhcGameManager.getBattleType().toString();
		TitleUtil.sendTitleToAllPlayers("游戏开始！", modeSubtitle);
		UhcGameManager.instance.getUhcPlayerManager().getCombatPlayers().forEach(player -> player.addTask(new TaskFindPlayer(player) {
			@SuppressWarnings("ConstantConditions")
			@Override
			public void onFindPlayer(ServerPlayerEntity player) {
				player.changeGameMode(GameMode.SURVIVAL);
				player.setInvulnerable(false);
				player.clearStatusEffects();
				UhcGameManager.instance.getUhcPlayerManager().resetHealthAndFood(player);
				player.resetStat(Stats.CUSTOM.getOrCreateStat(Stats.TIME_SINCE_REST));  // no free phantom
				if(UhcGameManager.getBattleType() == UhcGameManager.EnumBattleType.ICARUS) {
					ItemStack elytra = new ItemStack(Items.ELYTRA);
					addEnchantment(elytra, Enchantments.MENDING, 1);
					addEnchantment(elytra, Enchantments.BINDING_CURSE, 1);
					player.equipStack(EquipmentSlot.CHEST, elytra);
				} else if(UhcGameManager.getBattleType() == UhcGameManager.EnumBattleType.MARINE) {
					player.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 1200, 0));
				}

				// revoke all advancements
				player.getServer().getAdvancementLoader().getAdvancements().forEach(advancement -> {
					AdvancementProgress advancementProgress = player.getAdvancementTracker().getProgress(advancement);
					if (advancementProgress.isAnyObtained()) {
						for(String string : advancementProgress.getObtainedCriteria()) {
							player.getAdvancementTracker().revokeCriterion(advancement, string);
						}
					}
				});

				// give invisibility and shiny potion to player for ghost mode
					switch (UhcGameManager.getGameMode()) {
						case BOMBER:
							this.getGamePlayer().addBomberModeEffect();
						case GHOST:
						this.getGamePlayer().addGhostModeEffect();
						ItemStack shinyPotion = createShinyPotion();
						player.getInventory().insertStack(shinyPotion);
						break;
					case HUNTER:
						if(this.getGamePlayer().getTeam().getTeamColor() == UhcGameColor.RED) {
							ItemStack speedPotion = createSpeedPotion();
							player.getInventory().insertStack(speedPotion);
						} else {
							ItemStack compass = new ItemStack(Items.COMPASS);
							compass.set(DataComponentTypes.CUSTOM_NAME, Text.of("猎人指南针"));
							addEnchantment(compass, Enchantments.VANISHING_CURSE, 1);
							player.getInventory().insertStack(compass);
						}
						break;
					case GHOSTHUNTER:
						if(this.getGamePlayer().getTeam().getTeamColor() == UhcGameColor.RED) {
							this.getGamePlayer().addGhostModeEffect();
						} else {
							ItemStack shinyPotion2 = createShinyPotion();
							player.getInventory().insertStack(shinyPotion2);
							ItemStack compass = new ItemStack(Items.COMPASS);
							compass.set(DataComponentTypes.CUSTOM_NAME, Text.of("猎人指南针"));
							addEnchantment(compass, Enchantments.VANISHING_CURSE, 1);
							player.getInventory().insertStack(compass);
						}
					case KING:
						if (this.getGamePlayer().isKing()) {
							DyeColor dyeColor = this.getGamePlayer().getTeam().getTeamColor().dyeColor;
							ItemStack kingsHelmet = new ItemStack(Items.LEATHER_HELMET);
							kingsHelmet.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(dyeColor.getEntityColor(), false));
							kingsHelmet.set(DataComponentTypes.CUSTOM_NAME, Text.literal(String.format("%s之冠", dyeColor.getName())));
							addEnchantment(kingsHelmet, Enchantments.PROTECTION, 6);
							addEnchantment(kingsHelmet, Enchantments.BINDING_CURSE, 1);
							addEnchantment(kingsHelmet, Enchantments.VANISHING_CURSE, 1);
							player.equipStack(EquipmentSlot.HEAD, kingsHelmet);
						}
						break;
				}
				// 10s Resistance V spawn protection, applied last so mode-specific permanent
				// effects (e.g. bomber Resistance II) are kept as the hidden effect and re-surface afterwards
				player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 200, 4));
			}
		}));
		if (UhcGameManager.getGameMode() == UhcGameManager.EnumMode.KING) {
			UhcGameManager.instance.addTask(new TaskKingEffectField());
		}
		UhcGameManager.instance.addTask(new TaskScoreboard());
		UhcGameManager.instance.addTask(new TaskEnemyCompass());
	}

	private static RegistryEntry<net.minecraft.enchantment.Enchantment> getEnchantment(net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key) {
		Registry<net.minecraft.enchantment.Enchantment> enchantmentRegistry = (Registry<net.minecraft.enchantment.Enchantment>) Registries.REGISTRIES.get(RegistryKeys.ENCHANTMENT.getValue());
		if (enchantmentRegistry == null) {
			throw new IllegalStateException("Missing enchantment registry");
		}
		return enchantmentRegistry.getEntry(key).orElseThrow(RuntimeException::new);
	}

	private static void setEnchantments(ItemStack stack, java.util.function.Consumer<net.minecraft.component.type.ItemEnchantmentsComponent.Builder> consumer) {
		EnchantmentHelper.apply(stack, consumer);
	}

	private static void addEnchantment(ItemStack stack, net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key, int level) {
		setEnchantments(stack, builder -> builder.set(getEnchantment(key), level));
	}

	private static ItemStack createShinyPotion()
	{
		ItemStack shinyPotion = new ItemStack(Items.SPLASH_POTION);
		PotionContentsComponent potionContents = new PotionContentsComponent(java.util.Optional.of(Potions.WATER), java.util.Optional.of(0x00FFFF), java.util.Collections.singletonList(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0)));
		shinyPotion.set(DataComponentTypes.POTION_CONTENTS, potionContents);
		shinyPotion.set(DataComponentTypes.CUSTOM_NAME, Text.literal("闪耀喷溅药水"));
		return shinyPotion;
	}

	private static ItemStack createSpeedPotion()
	{
		ItemStack speedPotion = PotionContentsComponent.createStack(Items.SPLASH_POTION, Potions.SWIFTNESS);
		speedPotion.set(DataComponentTypes.CUSTOM_NAME, Text.literal("疾速喷溅药水"));
		return speedPotion;
	}

}
