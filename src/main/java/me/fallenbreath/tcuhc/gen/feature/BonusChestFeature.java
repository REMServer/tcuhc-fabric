package me.fallenbreath.tcuhc.gen.feature;

import com.mojang.serialization.Codec;
import me.fallenbreath.tcuhc.TcUhcMod;
import me.fallenbreath.tcuhc.UhcGameManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.recipe.BrewingRecipeRegistry;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BonusChestFeature extends Feature<DefaultFeatureConfig>
{
	private static final Text BONUS_CHEST_NAME = Text.literal("Bonus Chest");
	private static final Text EMPTY_CHEST_NAME = Text.literal("Empty Chest");
	private static final int LOG_INTERVAL = 16;
	private static int placedCount;
	private static boolean flushRegistered;
	private static final Map<Long, ConcurrentLinkedQueue<PendingPlacement>> PENDING_PLACEMENTS = new ConcurrentHashMap<>();
	private static final ConcurrentLinkedQueue<Long> READY_CHUNKS = new ConcurrentLinkedQueue<>();

	private static class PendingPlacement
	{
		private final BlockPos pos;
		private final long chunkKey;
		private final long randomSeed;
		private final boolean empty;

		private PendingPlacement(BlockPos pos, long randomSeed, boolean empty)
		{
			this.pos = pos.toImmutable();
			this.chunkKey = ChunkPos.toLong(pos);
			this.randomSeed = randomSeed;
			this.empty = empty;
		}
	}
	private static final RegistryKey<Enchantment>[] NORMAL_ENCHANTMENTS = new RegistryKey[]{
			net.minecraft.enchantment.Enchantments.POWER,
			net.minecraft.enchantment.Enchantments.SHARPNESS,
			net.minecraft.enchantment.Enchantments.FIRE_ASPECT,
			net.minecraft.enchantment.Enchantments.PROTECTION,
			net.minecraft.enchantment.Enchantments.PROJECTILE_PROTECTION
	};
	private static final RegistryKey<Enchantment>[] MARINE_ENCHANTMENTS = new RegistryKey[]{
			net.minecraft.enchantment.Enchantments.POWER,
			net.minecraft.enchantment.Enchantments.SHARPNESS,
			net.minecraft.enchantment.Enchantments.FIRE_ASPECT,
			net.minecraft.enchantment.Enchantments.PROTECTION,
			net.minecraft.enchantment.Enchantments.PROJECTILE_PROTECTION,
			net.minecraft.enchantment.Enchantments.LOYALTY,
			net.minecraft.enchantment.Enchantments.RIPTIDE,
			net.minecraft.enchantment.Enchantments.IMPALING,
			net.minecraft.enchantment.Enchantments.FROST_WALKER,
			net.minecraft.enchantment.Enchantments.AQUA_AFFINITY,
			net.minecraft.enchantment.Enchantments.DEPTH_STRIDER
	};
	private static final RegistryKey<Enchantment>[] ICARUS_ENCHANTMENTS = new RegistryKey[]{
			net.minecraft.enchantment.Enchantments.POWER,
			net.minecraft.enchantment.Enchantments.SHARPNESS,
			net.minecraft.enchantment.Enchantments.FIRE_ASPECT,
			net.minecraft.enchantment.Enchantments.PROTECTION,
			net.minecraft.enchantment.Enchantments.PROJECTILE_PROTECTION,
			net.minecraft.enchantment.Enchantments.FEATHER_FALLING
	};
	private static final RegistryEntry<net.minecraft.potion.Potion>[] BREWABLE_TIPPED_ARROW_POTIONS = new RegistryEntry[]{
			Potions.NIGHT_VISION,
			Potions.LONG_NIGHT_VISION,
			Potions.INVISIBILITY,
			Potions.LONG_INVISIBILITY,
			Potions.LEAPING,
			Potions.LONG_LEAPING,
			Potions.STRONG_LEAPING,
			Potions.FIRE_RESISTANCE,
			Potions.LONG_FIRE_RESISTANCE,
			Potions.SWIFTNESS,
			Potions.LONG_SWIFTNESS,
			Potions.STRONG_SWIFTNESS,
			Potions.SLOWNESS,
			Potions.LONG_SLOWNESS,
			Potions.WATER_BREATHING,
			Potions.LONG_WATER_BREATHING,
			Potions.HEALING,
			Potions.STRONG_HEALING,
			Potions.HARMING,
			Potions.STRONG_HARMING,
			Potions.POISON,
			Potions.LONG_POISON,
			Potions.STRONG_POISON,
			Potions.REGENERATION,
			Potions.LONG_REGENERATION,
			Potions.STRONG_REGENERATION,
			Potions.STRENGTH,
			Potions.LONG_STRENGTH,
			Potions.STRONG_STRENGTH,
			Potions.WEAKNESS,
			Potions.LONG_WEAKNESS,
			Potions.LUCK,
			Potions.SLOW_FALLING,
			Potions.LONG_SLOW_FALLING
	};

	public BonusChestFeature(Codec<DefaultFeatureConfig> codec)
	{
		super(codec);
	}

	public static void registerDeferredPlacementHook()
	{
		if (flushRegistered)
		{
			return;
		}
		flushRegistered = true;
		ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> markChunkReady(world, chunk.getPos().toLong()));
		ServerTickEvents.END_SERVER_TICK.register(server -> flushReadyChunks(server));
	}

	@Override
	public boolean generate(FeatureContext<DefaultFeatureConfig> context)
	{
		if (UhcGameManager.instance == null)
		{
			return false;
		}
		StructureWorldAccess world = context.getWorld();
		BlockPos origin = context.getOrigin();
		int chunkX = origin.getX() >> 4;
		int chunkZ = origin.getZ() >> 4;
		if (Math.abs(chunkX) <= 1 || Math.abs(chunkZ) <= 1)
		{
			return false;
		}

		Random random = context.getRandom();
		float chestChance = UhcGameManager.instance.getOptions().getFloatOptionValue("chestFrequency");
		float emptyChestChance = UhcGameManager.instance.getOptions().getFloatOptionValue("trappedChestFrequency");
		if (chestChance <= 0.0F)
		{
			return false;
		}

		int posX = random.nextInt(32) + origin.getX() - 16;
		int posZ = random.nextInt(32) + origin.getZ() - 16;
		int posY = world.getTopY(Heightmap.Type.OCEAN_FLOOR, posX, posZ);
		BlockPos pos = new BlockPos(posX, posY, posZ);
		while (!world.getBlockState(pos).isSolidBlock(world, pos) && pos.getY() > world.getBottomY())
		{
			pos = pos.down();
		}
		if (pos.getY() <= world.getBottomY())
		{
			return false;
		}

		double biomeChance = getBiomeChance(world, pos);
		if (biomeChance <= 0.0D || random.nextDouble() >= biomeChance * chestChance)
		{
			return false;
		}

		boolean empty = random.nextDouble() < emptyChestChance;
		PendingPlacement pending = new PendingPlacement(pos, random.nextLong(), empty);
		PENDING_PLACEMENTS.computeIfAbsent(pending.chunkKey, ignored -> new ConcurrentLinkedQueue<>()).add(pending);
		return true;
	}

	private static void markChunkReady(net.minecraft.server.world.ServerWorld world, long chunkKey)
	{
		if (world.getRegistryKey() != World.OVERWORLD)
		{
			return;
		}
		READY_CHUNKS.add(chunkKey);
	}

	private static void flushReadyChunks(net.minecraft.server.MinecraftServer server)
	{
		net.minecraft.server.world.ServerWorld world = server.getWorld(World.OVERWORLD);
		if (world == null)
		{
			return;
		}
		Long chunkKey;
		while ((chunkKey = READY_CHUNKS.poll()) != null)
		{
			flushPendingPlacements(world, chunkKey);
		}
	}

	private static void flushPendingPlacements(net.minecraft.server.world.ServerWorld world, long chunkKey)
	{
		ConcurrentLinkedQueue<PendingPlacement> placements = PENDING_PLACEMENTS.remove(chunkKey);
		if (placements == null)
		{
			return;
		}
		PendingPlacement pending;
		while ((pending = placements.poll()) != null)
		{
			if (!placeChest(world, pending))
			{
				UhcGameManager.LOG.warn("Bonus chest deferred placement skipped at {} after chunk load", pending.pos);
			}
		}
	}

	private static boolean placeChest(net.minecraft.server.world.ServerWorld world, PendingPlacement pending)
	{
		BlockPos pos = pending.pos;
		Random random = Random.create(pending.randomSeed);
		BlockState currentState = world.getBlockState(pos);
		boolean waterlogged = world.getFluidState(pos).isStill();
		BlockState chestState = (pending.empty ? Blocks.TRAPPED_CHEST : Blocks.CHEST)
				.getDefaultState()
				.rotate(BlockRotation.random(random))
				.with(ChestBlock.WATERLOGGED, waterlogged);
		if (!world.setBlockState(pos, chestState, Block.NOTIFY_LISTENERS))
		{
			return false;
		}
		if (world.getBlockState(pos.up()).isOf(Blocks.SNOW))
		{
			world.setBlockState(pos.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
		}

		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (!(blockEntity instanceof ChestBlockEntity))
		{
			world.setBlockState(pos, currentState, Block.NOTIFY_LISTENERS);
			return false;
		}
		ChestBlockEntity chest = (ChestBlockEntity)blockEntity;
		if (pending.empty)
		{
			fillChestItems(chest, buildEmptyItems(), random, false);
		}
		else
		{
			fillChestItems(chest, buildCommonItems(), random, false);
			fillChestItems(chest, buildValuableItems(random), random, true);
			// Books get their own roll, so an earlier diamond/tool reward cannot suppress them.
			createBonusBook(world, random).ifPresent(book -> placeBookInEmptySlot(chest, book, random));
		}
		placedCount++;
		if (placedCount % LOG_INTERVAL == 0)
		{
			UhcGameManager.LOG.info("Bonus chest feature placed {} chests so far; latest at {}", placedCount, pos);
		}
		return true;
	}

	private static void fillChestItems(ChestBlockEntity chest, List<RandomItem> items, Random random, boolean stopAfterFirst)
	{
		for (RandomItem item : items)
		{
			Optional<ItemStack> stack = item.tryCreate(random);
			if (stack.isPresent())
			{
				chest.setStack(random.nextInt(chest.size()), stack.get());
				if (stopAfterFirst)
				{
					break;
				}
			}
		}
	}

	private static List<RandomItem> buildCommonItems()
	{
		List<RandomItem> items = new ArrayList<>();
		items.add(new RandomItem(1, random -> new ItemStack(Items.STICK)));
		items.add(new RandomItem(1, random -> new ItemStack(Items.BONE, 2)));
		items.add(new RandomItem(2, random -> new ItemStack(Items.STRING)));
		items.add(new RandomItem(2, random -> new ItemStack(Items.IRON_INGOT, random.nextInt(2) + 1)));
		items.add(new RandomItem(3, random -> new ItemStack(Items.GOLD_INGOT)));
		items.add(new RandomItem(3, random -> new ItemStack(Items.CHORUS_FRUIT)));
		items.add(new RandomItem(5, random -> new ItemStack(Items.LEATHER)));
		items.add(new RandomItem(5, random -> new ItemStack(Items.EXPERIENCE_BOTTLE, random.nextInt(3) + 2)));
		if (UhcGameManager.getBattleType() == UhcGameManager.EnumBattleType.MARINE)
		{
			items.add(new RandomItem(5, random -> new ItemStack(Items.OAK_LOG)));
			items.add(new RandomItem(2, random -> new ItemStack(Items.OAK_SAPLING)));
		}
		else if (UhcGameManager.getBattleType() == UhcGameManager.EnumBattleType.ICARUS)
		{
			int chance = UhcGameManager.getLevelType() == UhcGameManager.EnumLevelType.AMPLIFIED ? 1 : 3;
			items.add(new RandomItem(chance, random -> new ItemStack(Items.FIREWORK_ROCKET)));
		}
		return items;
	}

	private static List<RandomItem> buildValuableItems(Random random)
	{
		List<RandomItem> items = new ArrayList<>();
		items.add(new RandomItem(16, ignored -> new ItemStack(Items.DIAMOND_SWORD)));
		items.add(new RandomItem(24, ignored -> new ItemStack(Items.DIAMOND_PICKAXE)));
		items.add(new RandomItem(20, ignored -> new ItemStack(Items.GOLDEN_APPLE)));
		items.add(new RandomItem(8, ignored -> new ItemStack(Items.DIAMOND)));
		if (UhcGameManager.getBattleType() == UhcGameManager.EnumBattleType.MARINE)
		{
			items.add(new RandomItem(8, ignored -> PotionContentsComponent.createStack(Items.POTION, Potions.WATER_BREATHING)));
			items.add(new RandomItem(16, ignored -> new ItemStack(Items.TRIDENT)));
			items.add(new RandomItem(8, ignored -> new ItemStack(Items.APPLE)));
		}
		else if (UhcGameManager.getBattleType() == UhcGameManager.EnumBattleType.ICARUS)
		{
			items.add(new RandomItem(16, ignored -> new ItemStack(Items.GUNPOWDER)));
			items.add(new RandomItem(32, ignored -> PotionContentsComponent.createStack(Items.TIPPED_ARROW, Potions.LUCK)));
			items.add(new RandomItem(8, ignored -> PotionContentsComponent.createStack(Items.TIPPED_ARROW, randomBrewablePotion(random))));
		}
		return items;
	}

	private static List<RandomItem> buildEmptyItems()
	{
		List<RandomItem> items = new ArrayList<>();
		items.add(new RandomItem(1, ignored -> {
			ItemStack stack = new ItemStack(Blocks.DEAD_BUSH);
			stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("There should be something here, but ..."));
			return stack;
		}));
		return items;
	}

	private static Optional<ItemStack> createBonusBook(StructureWorldAccess world, Random random)
	{
		// One exclusive roll: 4/64 random books, plus 1/64 each for the two marine specials.
		// The remaining outcomes give no book, preserving a limited supply and at most one per chest.
		int roll = random.nextInt(64);
		if (roll < 4) return Optional.of(createEnchantedBook(world, random));
		if (UhcGameManager.getBattleType() == UhcGameManager.EnumBattleType.MARINE)
		{
			if (roll == 4) return Optional.of(createSpecificEnchantedBook(world, net.minecraft.enchantment.Enchantments.CHANNELING, 1));
			if (roll == 5) return Optional.of(createSpecificEnchantedBook(world, net.minecraft.enchantment.Enchantments.RIPTIDE, 1));
		}
		return Optional.empty();
	}

	private static void placeBookInEmptySlot(ChestBlockEntity chest, ItemStack book, Random random)
	{
		List<Integer> emptySlots = new ArrayList<>();
		for (int slot = 0; slot < chest.size(); slot++)
		{
			if (chest.getStack(slot).isEmpty()) emptySlots.add(slot);
		}
		if (!emptySlots.isEmpty()) chest.setStack(emptySlots.get(random.nextInt(emptySlots.size())), book);
	}

	private static ItemStack createEnchantedBook(StructureWorldAccess world, Random random)
	{
		RegistryKey<Enchantment>[] pool;
		switch (UhcGameManager.getBattleType())
		{
			case MARINE:
				pool = MARINE_ENCHANTMENTS;
				break;
			case ICARUS:
				pool = ICARUS_ENCHANTMENTS;
				break;
			default:
				pool = NORMAL_ENCHANTMENTS;
				break;
		}
		RegistryKey<Enchantment> key = pool[random.nextInt(pool.length)];
		int level = random.nextInt(4) == 0 ? 2 : 1;
		return createSpecificEnchantedBook(world, key, level);
	}

	private static ItemStack createSpecificEnchantedBook(StructureWorldAccess world, RegistryKey<Enchantment> key, int level)
	{
		RegistryEntry<Enchantment> enchantment = getEnchantment(world, key);
		int validLevel = Math.max(enchantment.value().getMinLevel(), Math.min(level, enchantment.value().getMaxLevel()));
		return EnchantedBookItem.forEnchantment(new net.minecraft.enchantment.EnchantmentLevelEntry(enchantment, validLevel));
	}

	private static RegistryEntry<Enchantment> getEnchantment(StructureWorldAccess world, RegistryKey<Enchantment> key)
	{
		return world.getRegistryManager().get(net.minecraft.registry.RegistryKeys.ENCHANTMENT).entryOf(key);
	}

	private static RegistryEntry<net.minecraft.potion.Potion> randomBrewablePotion(Random random)
	{
		return BREWABLE_TIPPED_ARROW_POTIONS[random.nextInt(BREWABLE_TIPPED_ARROW_POTIONS.length)];
	}

	private interface ItemFactory
	{
		ItemStack create(Random random);
	}

	private static class RandomItem
	{
		private final int chance;
		private final ItemFactory factory;

		private RandomItem(int chance, ItemFactory factory)
		{
			this.chance = chance;
			this.factory = factory;
		}

		private Optional<ItemStack> tryCreate(Random random)
		{
			return random.nextInt(this.chance) == 0 ? Optional.of(this.factory.create(random)) : Optional.empty();
		}
	}

	private static double getBiomeChance(StructureWorldAccess world, BlockPos pos)
	{
		RegistryEntry<Biome> biome = world.getGeneratorStoredBiome(
				BiomeCoords.fromBlock(pos.getX()),
				BiomeCoords.fromBlock(pos.getY()),
				BiomeCoords.fromBlock(pos.getZ())
		);
		String biomeId = biome.getKey().map(key -> key.getValue().getPath()).orElse("");
		if (biomeId.contains("river") || biomeId.contains("beach"))
		{
			return 0.0D;
		}
		if (biomeId.contains("mushroom"))
		{
			return 0.10D;
		}
		if (biomeId.contains("swamp") || biomeId.contains("mangrove"))
		{
			return 0.10D;
		}
		if (biomeId.contains("desert"))
		{
			return 0.06D;
		}
		if (biomeId.contains("mesa") || biomeId.contains("badlands"))
		{
			return 0.12D;
		}
		if (biomeId.contains("jungle"))
		{
			return 0.12D;
		}
		if (biomeId.contains("savanna"))
		{
			return 0.12D;
		}
		if (biomeId.contains("taiga") || biomeId.contains("forest") || biomeId.contains("grove"))
		{
			return 0.12D;
		}
		if (biomeId.contains("snow") || biomeId.contains("ice") || biomeId.contains("frozen"))
		{
			return 0.20D;
		}
		if (biomeId.contains("mountain") || biomeId.contains("windswept") || biomeId.contains("peak") || biomeId.contains("hill"))
		{
			return 0.12D;
		}
		if (biomeId.contains("plains") || biomeId.contains("meadow") || biomeId.contains("sunflower"))
		{
			return 0.06D;
		}
		if (biomeId.contains("ocean"))
		{
			return UhcGameManager.getBattleType() == UhcGameManager.EnumBattleType.MARINE ? 0.20D : 0.0D;
		}
		return 0.0D;
	}
}
