/*
 From Gamepiaynmo: https://github.com/Gamepiaynmo/TC-UHC
 */

package me.fallenbreath.tcuhc.util;

import me.fallenbreath.tcuhc.TcUhcMod;
import me.fallenbreath.tcuhc.UhcGameManager;
import me.fallenbreath.tcuhc.task.Task;
import me.fallenbreath.tcuhc.task.TaskSpawnPlatformProtect;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SpawnPlatform {
	private static final int DEFAULT_HEIGHT = 160;
	private static final String ENTITY_TAG = "tcuhc_lobby";
	public static int height = DEFAULT_HEIGHT;
	private static BlockBox platformBounds;
	private static BlockPos[] spawnPositions;
	private static int returnHeight;
	private static boolean active;
	private static String decorationGeneration = "";

	public static void registerDecorationCleanup() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (!isStaleDecoration(entity) || UhcGameManager.instance == null) return;
			UUID uuid = entity.getUuid();
			// Saved entity chunks may arrive after the block chunks and template placement.
			// Wait until entity registration finishes, then resolve by UUID and remove stale copies.
			UhcGameManager.instance.addTask(new Task() {
				@Override
				public void onUpdate() {
					Entity loaded = world.getEntity(uuid);
					if (loaded != null && isStaleDecoration(loaded)) loaded.discard();
				}
			});
		});
	}

	private static boolean isStaleDecoration(Entity entity) {
		return entity.getType() == EntityType.ARMOR_STAND && entity.getCommandTags().contains(ENTITY_TAG)
				&& (!active || !entity.getCommandTags().contains(decorationGeneration));
	}

	private static void sampleTerrainHeight(UhcGameManager gameManager, World world, int templateHeight) {
		UhcWorldData uhcWorldData = gameManager.getWorldData();
		height = uhcWorldData.spawnPlatformHeight;
		if (!uhcWorldData.isSpawnPlatformHeightValid()) {
			height = DEFAULT_HEIGHT;
			final int sampleWidth = 40;
			for (int x = -sampleWidth; x <= sampleWidth; x++)
				for (int z = -sampleWidth; z <= sampleWidth; z++) {
					height = Math.max(height, world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) + 64);
				}
		}
		// Reserve room for the whole island, not just the walking surface.
		height = Math.max(world.getBottomY(), Math.min(height, world.getTopY() - templateHeight - 2));
		if (uhcWorldData.spawnPlatformHeight != height) {
			uhcWorldData.spawnPlatformHeight = height;
			uhcWorldData.save();
		}
		UhcGameManager.LOG.info("Set spawn platform height to y{}", height);
	}

	public static void generatePlatform(UhcGameManager gameManager, ServerWorld world) {
		UhcWorldData worldData = gameManager.getWorldData();
		String lobbyId = worldData.lobbyTemplate != null ? worldData.lobbyTemplate
				: LobbyRotation.current(UhcGameManager.getWorldRootPath());
		LobbyDefinition lobby = LobbyDefinition.byId(lobbyId);
		Identifier templateId = TcUhcMod.id("lobby/" + lobby.id);
		StructureTemplate template = world.getStructureTemplateManager().getTemplate(templateId)
				.orElseThrow(() -> new IllegalStateException("Missing lobby template: " + templateId));
		Vec3i size = template.getSize();
		sampleTerrainHeight(gameManager, world, size.getY());
		BlockPos origin = new BlockPos(-size.getX() / 2, height, -size.getZ() / 2);
		StructurePlacementData placement = new StructurePlacementData().setIgnoreEntities(false);
		platformBounds = template.calculateBoundingBox(placement, origin);
		// Load saved decorations before removing them, including after a server restart.
		for (int x = platformBounds.getMinX() >> 4; x <= platformBounds.getMaxX() >> 4; x++)
			for (int z = platformBounds.getMinZ() >> 4; z <= platformBounds.getMaxZ() >> 4; z++)
				world.getChunk(x, z);
		removeDecorations(world);
		active = true;
		decorationGeneration = "tcuhc_lobby_" + UUID.randomUUID();
		NbtCompound data = template.writeNbt(new NbtCompound());
		NbtList entities = data.getList("entities", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < entities.size(); i++) {
			NbtCompound entity = entities.getCompound(i).getCompound("nbt");
			NbtList tags = entity.getList("Tags", NbtElement.STRING_TYPE);
			tags.add(NbtString.of(decorationGeneration));
			entity.put("Tags", tags);
		}
		// Keep the cached resource pristine: each placement gets its own entity generation tag.
		template = new StructureTemplate();
		template.readNbt(world.getRegistryManager().getWrapperOrThrow(RegistryKeys.BLOCK), data);
		if (!template.place(world, origin, origin, placement, world.getRandom(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE)) {
			active = false;
			throw new IllegalStateException("Failed to place lobby template: " + templateId);
		}
		spawnPositions = lobby.floors.stream().map(pos -> origin.add(pos.x(), pos.y(), pos.z())).toArray(BlockPos[]::new);
		returnHeight = java.util.Arrays.stream(spawnPositions).mapToInt(BlockPos::getY).min().orElseThrow() - 12;
		validateSpawnPositions(world);
		worldData.lobbyTemplate = lobby.id;
		worldData.save();
		gameManager.addTask(new TaskSpawnPlatformProtect(gameManager));
		UhcGameManager.LOG.info("Placed lobby template {} at {} (size {}), spawn positions {}",
				templateId, origin, size, (Object) spawnPositions);
	}

	public static void validateSpawnPositions(World world) {
		if (!active) return;
		for (BlockPos pos : spawnPositions) {
			if (!world.getBlockState(pos).isFullCube(world, pos)
					|| !world.isAir(pos.up()) || !world.isAir(pos.up(2))) {
				throw new IllegalStateException("Unsafe lobby spawn position: " + pos);
			}
		}
	}

	public static boolean isProtected(World world, BlockPos pos) {
		return active && world.getRegistryKey() == World.OVERWORLD
				&& platformBounds != null && platformBounds.contains(pos);
	}

	public static boolean shouldReturnPlayer(ServerPlayerEntity player) {
		if (!active || player.getWorld().getRegistryKey() != World.OVERWORLD) return false;
		// Return players before they fall through the island's lower decorative layers.
		return player.getY() < returnHeight
				|| player.getX() < platformBounds.getMinX() - 4 || player.getX() > platformBounds.getMaxX() + 5
				|| player.getZ() < platformBounds.getMinZ() - 4 || player.getZ() > platformBounds.getMaxZ() + 5;
	}

	private static void removeDecorations(ServerWorld world) {
		// Remove only our tagged template entities, never unrelated mobs or player entities.
		List<Entity> decorations = new ArrayList<>();
		for (Entity entity : world.iterateEntities()) {
			if (entity.getCommandTags().contains(ENTITY_TAG)) decorations.add(entity);
		}
		decorations.forEach(Entity::discard);
	}

	public static void destroyPlatform(ServerWorld world) {
		if (platformBounds == null) return;
		removeDecorations(world);
		for (int y = platformBounds.getMaxY(); y >= platformBounds.getMinY(); y--)
			for (int x = platformBounds.getMinX(); x <= platformBounds.getMaxX(); x++)
				for (int z = platformBounds.getMinZ(); z <= platformBounds.getMaxZ(); z++) {
					BlockPos pos = new BlockPos(x, y, z);
					// Containers must not spill decorative contents into the match world.
					if (world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory) {
						((net.minecraft.inventory.Inventory) world.getBlockEntity(pos)).clear();
					}
					if (!world.isAir(pos)) world.setBlockState(pos, Blocks.AIR.getDefaultState(),
							Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
				}
		active = false;
	}

	public static BlockPos getRandomSpawnPosition(Random rand) {
		return spawnPositions[rand.nextInt(spawnPositions.length)];
	}
}
