package me.fallenbreath.tcuhc;

import me.fallenbreath.tcuhc.gen.feature.BonusChestFeature;
import me.fallenbreath.tcuhc.gen.feature.UhcFeatures;
import me.fallenbreath.tcuhc.gen.structure.SinglePieceLandStructure;
import me.fallenbreath.tcuhc.util.LootInjector;
import me.fallenbreath.tcuhc.util.SpawnPlatform;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.MinecraftVersion;
import net.minecraft.util.Identifier;

public class TcUhcMod implements ModInitializer
{
	private static final String MOD_ID = "tcuhc";
	private static String version;
	private static final String MINECRAFT_VERSION = MinecraftVersion.CURRENT.getName();

	@Override
	public void onInitialize()
	{
		version = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(RuntimeException::new).getMetadata().getVersion().getFriendlyString();
		SinglePieceLandStructure.registerLootRetryHook();
		BonusChestFeature.registerDeferredPlacementHook();
		UhcFeatures.register();
		LootInjector.register();
		SpawnPlatform.registerDecorationCleanup();
	}

	public static String getModId()
	{
		return MOD_ID;
	}

	public static String getModVersion()
	{
		return version;
	}

	public static String getMinecraftVersion()
	{
		return MINECRAFT_VERSION;
	}

	public static Identifier id(String name)
	{
		return Identifier.of(MOD_ID, name);
	}
}
