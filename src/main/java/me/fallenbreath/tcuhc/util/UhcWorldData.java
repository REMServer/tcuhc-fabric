package me.fallenbreath.tcuhc.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.fallenbreath.tcuhc.UhcGameManager;

import java.io.*;
import java.util.Random;

public class UhcWorldData
{
	public int spawnPlatformHeight = -1;
	public StructureType netherFortressType = StructureType.randomChoose();

	/**
	 * The generator-affecting settings this world's terrain was actually built with.
	 *
	 * <p>Terrain lives in the world folder, but the settings that shaped it live in
	 * {@code uhc.properties} in the server directory, which survives a regen. Changing
	 * {@code battleType} without regenerating therefore leaves the old terrain in place - that is
	 * what "normal mode seems to be using the marine world generator" actually is. Recording the
	 * identity here lets the server say so at startup instead of leaving it to be discovered in
	 * game.
	 *
	 * <p>Null on worlds created before this field existed; treated as "unknown, do not warn".
	 */
	public String generatorIdentity = null;

	private UhcWorldData()
	{
		// Gson also calls this constructor while loading an existing save. Writing here
		// would replace that file with defaults before its fields have been restored.
	}

	public boolean isSpawnPlatformHeightValid()
	{
		return this.spawnPlatformHeight != -1;
	}

	/**
	 * Compares the recorded generator identity with the current settings, adopting the current one
	 * if this world has never recorded any.
	 *
	 * @return the previously recorded identity when it differs from {@code current}, otherwise null
	 */
	public String checkGeneratorIdentity(String current)
	{
		if (this.generatorIdentity == null)
		{
			this.generatorIdentity = current;
			this.save();
			return null;
		}
		if (this.generatorIdentity.equals(current))
		{
			return null;
		}
		return this.generatorIdentity;
	}

	public static UhcWorldData load()
	{
		File file = UhcGameManager.getDataFile();
		try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file)))
		{
			UhcWorldData data = new Gson().fromJson(reader, UhcWorldData.class);
			UhcGameManager.LOG.info("Loaded uhc world data");
			return data;
		}
		catch (Exception e)
		{
			if (e instanceof FileNotFoundException)
			{
				UhcGameManager.LOG.warn("Generating new uhc world data");
			}
			else
			{
				UhcGameManager.LOG.error("Failed to read uhc world data file", e);
			}
			UhcWorldData data = new UhcWorldData();
			data.save();
			return data;
		}
	}

	public synchronized void save()
	{
		File file = UhcGameManager.getDataFile();
		try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file)))
		{
			writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(this));
		}
		catch (Exception e)
		{
			UhcGameManager.LOG.error("Failed to save uhc data file", e);
		}
	}

	public enum StructureType
	{
		NETHER_FORTRESS,
		BASTION_REMNANT;

		private static final Random random = new Random();

		public static StructureType randomChoose()
		{
			StructureType[] values = values();
			return values[random.nextInt(values.length)];
		}
	}
}
