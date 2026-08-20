package com.example.parkour;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ParkourScoreboard {
	private static final int SCOREBOARD_MAX_ENTRIES = 10;
	private static final EntityDataAccessor<Component> TEXT_DISPLAY_TEXT_ACCESSOR = textDisplayTextAccessor();

	private final ParkourStorage storage;

	ParkourScoreboard(ParkourStorage storage) {
		this.storage = storage;
	}

	void updateAll(MinecraftServer server) {
		storage.parkours.keySet().forEach(name -> {
			update(server, name);
			updatePersonalLines(server, name);
		});
	}

	void remove(MinecraftServer server, String parkourName) {
		String tag = scoreboardTag(parkourName);
		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, "kill @e[type=minecraft:text_display,tag=" + tag + "]");
	}

	void update(MinecraftServer server, String parkourName) {
		ParkourStorage.ParkourData parkour = storage.parkours.get(parkourName);
		if (parkour == null || parkour.scoreboard == null) {
			return;
		}

		ServerLevel level = server.getLevel(parkour.scoreboard.dimensionKey());
		if (level == null) {
			return;
		}

		CommandSourceStack source = server.createCommandSourceStack()
			.withLevel(level)
			.withPosition(new Vec3(parkour.scoreboard.x(), parkour.scoreboard.y(), parkour.scoreboard.z()))
			.withSuppressedOutput();

		String tag = scoreboardTag(parkourName);
		// Scoreboards are owned by tag, so reloading one parkour does not touch others.
		server.getCommands().performPrefixedCommand(source, "kill @e[type=minecraft:text_display,tag=" + tag + ",distance=..8]");

		List<Map.Entry<UUID, ParkourStorage.BestTime>> sortedTimes = sortedTimes(parkour);
		String text = buildScoreboardSnbtText(parkourName, sortedTimes);

		String command = "summon minecraft:text_display "
			+ parkour.scoreboard.x() + " " + parkour.scoreboard.y() + " " + parkour.scoreboard.z()
			+ " {Tags:[\"parkour_scoreboard\",\"" + tag + "\"],billboard:\"vertical\",text:" + text + "}";
		server.getCommands().performPrefixedCommand(source, command);
	}

	void update(MinecraftServer server, String parkourName, UUID focusPlayerId) {
		update(server, parkourName);
		updatePersonalLine(server, parkourName, focusPlayerId);
	}

	void updatePersonalLines(MinecraftServer server, String parkourName) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			updatePersonalLine(server, parkourName, player.getUUID());
		}
	}

	void updatePersonalLine(MinecraftServer server, String parkourName, UUID playerId) {
		ParkourStorage.ParkourData parkour = storage.parkours.get(parkourName);
		if (parkour == null || parkour.scoreboard == null) {
			return;
		}

		ServerLevel level = server.getLevel(parkour.scoreboard.dimensionKey());
		if (level == null) {
			return;
		}

		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		Entity scoreboardEntity = findScoreboardEntity(level, scoreboardTag(parkourName), parkour.scoreboard);
		if (player == null || scoreboardEntity == null) {
			return;
		}

		List<Map.Entry<UUID, ParkourStorage.BestTime>> sortedTimes = sortedTimes(parkour);
		int focusRank = rankOf(sortedTimes, playerId);
		if (focusRank <= SCOREBOARD_MAX_ENTRIES) {
			return;
		}

		// Only this player's connection receives the expanded text, so personal ranks are not visible to others.
		Component personalText = buildScoreboardComponent(parkourName, sortedTimes, focusRank);
		player.connection.send(new ClientboundSetEntityDataPacket(
			scoreboardEntity.getId(),
			List.of(SynchedEntityData.DataValue.create(TEXT_DISPLAY_TEXT_ACCESSOR, personalText))));
	}

	private static List<Map.Entry<UUID, ParkourStorage.BestTime>> sortedTimes(ParkourStorage.ParkourData parkour) {
		return parkour.bestTimes.entrySet().stream()
			.sorted(Comparator.comparingLong(entry -> entry.getValue().millis))
			.toList();
	}

	private static String buildScoreboardSnbtText(String parkourName, List<Map.Entry<UUID, ParkourStorage.BestTime>> sortedTimes) {
		// Text Display expects an SNBT text component. Supplying JSON as a quoted string renders raw markup in-game.
		StringBuilder text = new StringBuilder("{text:\"")
			.append(ParkourText.escapeSnbtString(ParkourText.smallCaps("Parkour " + parkourName + " Beste Tijden")))
			.append("\",color:\"")
			.append(ParkourText.GOLD_HEX)
			.append("\",bold:true,extra:[");

		int renderedEntries = Math.min(SCOREBOARD_MAX_ENTRIES, sortedTimes.size());
		for (int i = 0; i < renderedEntries; i++) {
			appendScoreLine(text, i + 1, sortedTimes.get(i).getValue());
		}

		if (text.charAt(text.length() - 1) == ',') {
			text.deleteCharAt(text.length() - 1);
		}
		text.append("]}");
		return text.toString();
	}

	private static Component buildScoreboardComponent(String parkourName, List<Map.Entry<UUID, ParkourStorage.BestTime>> sortedTimes, int focusRank) {
		MutableComponent text = ParkourText.label("Parkour ", ParkourText.GOLD)
			.append(ParkourText.literal(parkourName, ParkourText.GOLD))
			.append(ParkourText.label(" Beste Tijden", ParkourText.GOLD))
			.withStyle(ChatFormatting.BOLD);
		int renderedEntries = Math.min(SCOREBOARD_MAX_ENTRIES, sortedTimes.size());
		for (int i = 0; i < renderedEntries; i++) {
			text.append(scoreLine(i + 1, sortedTimes.get(i).getValue()));
		}
		text.append(ParkourText.literal("\n", ParkourText.MUTED));
		text.append(scoreLine(focusRank, sortedTimes.get(focusRank - 1).getValue()));
		return text;
	}

	private static String scoreboardTag(String parkourName) {
		return "parkour_scoreboard_" + parkourName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
	}

	private static int rankOf(List<Map.Entry<UUID, ParkourStorage.BestTime>> sortedTimes, UUID playerId) {
		for (int i = 0; i < sortedTimes.size(); i++) {
			if (sortedTimes.get(i).getKey().equals(playerId)) {
				return i + 1;
			}
		}
		return -1;
	}

	private static void appendScoreLine(StringBuilder text, int rank, ParkourStorage.BestTime best) {
		text.append("{text:\"\\n#")
			.append(rank)
			.append("\",color:\"")
			.append(rankColorHex(rank))
			.append("\",bold:false},{text:\" ")
			.append(ParkourText.escapeSnbtString(best.playerName))
			.append(ParkourText.smallCaps(" - "))
			.append(ParkourText.formatTime(best.millis))
			.append("\",color:\"")
			.append(ParkourText.TEXT_HEX)
			.append("\",bold:false},");
	}

	private static MutableComponent scoreLine(int rank, ParkourStorage.BestTime best) {
		return ParkourText.literal("\n#" + rank, rankColor(rank))
			.append(ParkourText.literal(" ", ParkourText.TEXT))
			.append(ParkourText.raw(best.playerName, ParkourText.TEXT))
			.append(ParkourText.literal(" - " + ParkourText.formatTime(best.millis), ParkourText.TEXT));
	}

	private static int rankColor(int rank) {
		return switch (rank) {
			case 1 -> ParkourText.GOLD;
			case 2 -> ParkourText.SILVER;
			case 3 -> ParkourText.BRONZE;
			default -> ParkourText.TEXT;
		};
	}

	private static String rankColorHex(int rank) {
		return switch (rank) {
			case 1 -> ParkourText.GOLD_HEX;
			case 2 -> ParkourText.SILVER_HEX;
			case 3 -> ParkourText.BRONZE_HEX;
			default -> ParkourText.TEXT_HEX;
		};
	}

	private static Entity findScoreboardEntity(ServerLevel level, String tag, ParkourLocation location) {
		Entity closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (Entity entity : level.getAllEntities()) {
			if (!entity.entityTags().contains(tag)) {
				continue;
			}

			double distance = entity.distanceToSqr(location.x(), location.y(), location.z());
			if (distance < closestDistance) {
				closest = entity;
				closestDistance = distance;
			}
		}
		return closest;
	}

	@SuppressWarnings("unchecked")
	private static EntityDataAccessor<Component> textDisplayTextAccessor() {
		try {
			Field field = Display.TextDisplay.class.getDeclaredField("DATA_TEXT_ID");
			field.setAccessible(true);
			return (EntityDataAccessor<Component>) field.get(null);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Cannot access TextDisplay text data accessor", exception);
		}
	}
}
