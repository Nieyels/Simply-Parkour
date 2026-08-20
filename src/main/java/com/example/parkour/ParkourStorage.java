package com.example.parkour;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ParkourStorage {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	Map<String, ParkourData> parkours = new HashMap<>();

	static ParkourStorage load(MinecraftServer server) {
		Path path = path(server);
		if (!Files.exists(path)) {
			return new ParkourStorage();
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			ParkourStorage storage = GSON.fromJson(reader, ParkourStorage.class);
			if (storage == null) {
				return new ParkourStorage();
			}
			if (storage.parkours == null) {
				storage.parkours = new HashMap<>();
			}
			storage.parkours.values().forEach(ParkourData::ensureDefaults);
			return storage;
		} catch (IOException exception) {
			ParkourMod.LOGGER.error("Could not load parkour data", exception);
			return new ParkourStorage();
		}
	}

	ParkourData parkour(String name) {
		ParkourData parkour = parkours.computeIfAbsent(name, ignored -> new ParkourData());
		parkour.ensureDefaults();
		return parkour;
	}

	static final class ParkourData {
		ParkourLocation start;
		ParkourLocation spawn;
		ParkourLocation finish;
		ParkourLocation scoreboard;
		double fallDistance = 3.0D;
		List<FallZone> fallZones = new ArrayList<>();
		List<ParkourLocation> checkpoints = new ArrayList<>();
		Map<UUID, BestTime> bestTimes = new HashMap<>();

		boolean isComplete() {
			return start != null && spawn != null && finish != null;
		}

		void ensureDefaults() {
			if (bestTimes == null) {
				bestTimes = new HashMap<>();
			}
			if (fallZones == null) {
				fallZones = new ArrayList<>();
			}
			if (checkpoints == null) {
				checkpoints = new ArrayList<>();
			}
			if (fallDistance <= 0.0D) {
				fallDistance = 3.0D;
			}
		}
	}

	void save(MinecraftServer server) {
		Path path = path(server);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException exception) {
			ParkourMod.LOGGER.error("Could not save parkour data", exception);
		}
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("parkourmod.json");
	}

	static final class BestTime {
		String playerName;
		long millis;

		BestTime(String playerName, long millis) {
			this.playerName = playerName;
			this.millis = millis;
		}
	}

	static final class FallZone {
		ParkourLocation location;
		double extraBlocks;

		FallZone(ParkourLocation location, double extraBlocks) {
			this.location = location;
			this.extraBlocks = extraBlocks;
		}
	}
}
