package com.example.parkour;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class ParkourCommands {
	private final Supplier<ParkourStorage> storage;
	private final Supplier<ParkourRuntime> runtime;
	private final Supplier<ParkourScoreboard> scoreboard;
	private final Map<UUID, String> pendingDeleteConfirmations = new HashMap<>();

	ParkourCommands(Supplier<ParkourStorage> storage, Supplier<ParkourRuntime> runtime, Supplier<ParkourScoreboard> scoreboard) {
		this.storage = storage;
		this.runtime = runtime;
		this.scoreboard = scoreboard;
	}

	void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("parkour")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("set")
				.then(Commands.literal("start")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames).executes(context -> setPlateLocation(context.getSource(), "start", StringArgumentType.getString(context, "naam")))))
				.then(Commands.literal("spawn")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames).executes(context -> setSpawnLocation(context.getSource(), StringArgumentType.getString(context, "naam")))))
				.then(Commands.literal("finish")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames).executes(context -> setPlateLocation(context.getSource(), "finish", StringArgumentType.getString(context, "naam")))))
				.then(Commands.literal("checkpoint")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames).executes(context -> setCheckpointLocation(context.getSource(), StringArgumentType.getString(context, "naam")))))
				.then(Commands.literal("fall")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames)
						.then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.0D)).executes(context -> setFallLocation(
							context.getSource(),
							StringArgumentType.getString(context, "naam"),
							DoubleArgumentType.getDouble(context, "blocks"))))))
				.then(Commands.literal("scoreboard")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames).executes(context -> setScoreboardLocation(context.getSource(), StringArgumentType.getString(context, "naam"))))))
			.then(Commands.literal("reloadscoreboard")
				.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames).executes(context -> {
					scoreboard().update(context.getSource().getServer(), StringArgumentType.getString(context, "naam"));
					context.getSource().sendSuccess(() -> message("Parkour scoreboard bijgewerkt.", ParkourText.GREEN), false);
					return Command.SINGLE_SUCCESS;
				}))
				.executes(context -> {
					scoreboard().updateAll(context.getSource().getServer());
					context.getSource().sendSuccess(() -> message("Parkour scoreboard bijgewerkt.", ParkourText.GREEN), false);
					return Command.SINGLE_SUCCESS;
				}))
			.then(Commands.literal("times")
				.then(Commands.literal("list")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames)
						.executes(context -> listScoreboardTimes(context.getSource(), StringArgumentType.getString(context, "naam")))))
				.then(Commands.literal("clear")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames)
						.executes(context -> clearScoreboardTimes(
							context.getSource(),
							StringArgumentType.getString(context, "naam")))
						.then(Commands.argument("speler", StringArgumentType.word()).suggests(this::suggestBestTimePlayerNames)
							.executes(context -> removeScoreboardTime(
								context.getSource(),
								StringArgumentType.getString(context, "naam"),
								StringArgumentType.getString(context, "speler")))))))
			.then(Commands.literal("config")
				.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNamesAndDeleteNames)
					.executes(context -> showConfig(context.getSource(), StringArgumentType.getString(context, "naam")))
					.then(Commands.literal("fallDistance")
						.then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.1D)).executes(context -> setFallDistance(
							context.getSource(),
							StringArgumentType.getString(context, "naam"),
							DoubleArgumentType.getDouble(context, "blocks")))))
					.then(Commands.literal("finishTeleport")
						.then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.0D)).executes(context -> setFinishTeleportDelay(
							context.getSource(),
							StringArgumentType.getString(context, "naam"),
							DoubleArgumentType.getDouble(context, "seconds")))))
					.then(Commands.literal("rename")
						.then(Commands.argument("nieuweNaam", StringArgumentType.word()).executes(context -> renameParkour(
							context.getSource(),
							StringArgumentType.getString(context, "naam"),
							StringArgumentType.getString(context, "nieuweNaam")))))))
			.then(Commands.literal("confirm")
				.then(Commands.literal("delete")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames).executes(context -> confirmDelete(context.getSource(), StringArgumentType.getString(context, "naam"))))))
			.then(Commands.literal("fall")
				.then(Commands.literal("list")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames)
						.executes(context -> listFallPoints(context.getSource(), StringArgumentType.getString(context, "naam")))))
				.then(Commands.literal("remove")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames)
						.then(Commands.argument("id", IntegerArgumentType.integer(1))
							.executes(context -> removeFallPoint(context.getSource(), StringArgumentType.getString(context, "naam"), IntegerArgumentType.getInteger(context, "id"))))))
				.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestDeleteParkourNames)
					.then(Commands.argument("id", IntegerArgumentType.integer(1))
						.executes(context -> removeFallPointShorthand(context.getSource(), StringArgumentType.getString(context, "naam"), IntegerArgumentType.getInteger(context, "id"))))))
			.then(Commands.literal("checkpoint")
				.then(Commands.literal("list")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames)
						.executes(context -> listCheckpoints(context.getSource(), StringArgumentType.getString(context, "naam")))))
				.then(Commands.literal("remove")
					.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestParkourNames)
						.then(Commands.argument("id", IntegerArgumentType.integer(1))
							.executes(context -> removeCheckpoint(context.getSource(), StringArgumentType.getString(context, "naam"), IntegerArgumentType.getInteger(context, "id"))))))
				.then(Commands.argument("naam", StringArgumentType.word()).suggests(this::suggestDeleteParkourNames)
					.then(Commands.argument("id", IntegerArgumentType.integer(1))
						.executes(context -> removeCheckpointShorthand(context.getSource(), StringArgumentType.getString(context, "naam"), IntegerArgumentType.getInteger(context, "id")))))));
	}

	private CompletableFuture<Suggestions> suggestParkourNames(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		storage().parkours.keySet().forEach(builder::suggest);
		return builder.buildFuture();
	}

	private CompletableFuture<Suggestions> suggestDeleteParkourNames(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		String remaining = builder.getRemainingLowerCase();
		storage().parkours.keySet().stream()
			.map(name -> "-" + name)
			.filter(name -> name.toLowerCase().startsWith(remaining))
			.forEach(builder::suggest);
		return builder.buildFuture();
	}

	private CompletableFuture<Suggestions> suggestParkourNamesAndDeleteNames(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		String remaining = builder.getRemainingLowerCase();
		if (remaining.startsWith("-")) {
			return suggestDeleteParkourNames(context, builder);
		}

		storage().parkours.keySet().stream()
			.filter(name -> name.toLowerCase().startsWith(remaining))
			.forEach(builder::suggest);
		return builder.buildFuture();
	}

	private CompletableFuture<Suggestions> suggestBestTimePlayerNames(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		String parkourName;
		try {
			parkourName = StringArgumentType.getString(context, "naam");
		} catch (IllegalArgumentException exception) {
			return builder.buildFuture();
		}

		ParkourStorage.ParkourData parkour = storage().parkours.get(parkourName);
		if (parkour == null) {
			return builder.buildFuture();
		}

		String remaining = builder.getRemainingLowerCase();
		parkour.bestTimes.values().stream()
			.map(best -> best.playerName)
			.filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(remaining))
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.forEach(builder::suggest);
		return builder.buildFuture();
	}

	private int setPlateLocation(CommandSourceStack source, String type, String name) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception exception) {
			source.sendFailure(message("Dit command moet door een speler uitgevoerd worden.", ParkourText.RED));
			return 0;
		}

		ServerLevel level = source.getLevel();
		BlockPos blockPos = player.blockPosition();
		if (!runtime().isPressurePlate(level, blockPos)) {
			source.sendFailure(message("Je moet op een pressure plate staan.", ParkourText.RED));
			return 0;
		}

		ParkourStorage.ParkourData parkour = storage().parkour(name);
		// Start and finish are block targets, not exact player positions.
		ParkourLocation location = ParkourLocation.from(level, blockPos.getX(), blockPos.getY(), blockPos.getZ(), 0.0F, 0.0F);

		if ("start".equals(type)) {
			parkour.start = location;
			// Keep the O(1) start lookup in sync when admins move a start plate.
			runtime().updateStartPlateIndex(name);
		} else if ("finish".equals(type)) {
			parkour.finish = location;
		} else {
			throw new IllegalArgumentException("Unknown plate type: " + type);
		}

		storage().save(source.getServer());
		source.sendSuccess(() -> message("Parkour '" + name + "' " + type + " pressure plate ingesteld.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int setSpawnLocation(CommandSourceStack source, String name) {
		Vec3 pos = source.getPosition();
		ServerLevel level = source.getLevel();
		// Center respawns on the block so players do not reappear on an edge.
		double centeredX = Math.floor(pos.x) + 0.5D;
		double centeredZ = Math.floor(pos.z) + 0.5D;
		ParkourLocation location = ParkourLocation.from(level, centeredX, pos.y, centeredZ, source.getRotation().y, source.getRotation().x);
		storage().parkour(name).spawn = location;
		storage().save(source.getServer());
		source.sendSuccess(() -> message("Parkour '" + name + "' spawn ingesteld met yaw/pitch.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int setScoreboardLocation(CommandSourceStack source, String name) {
		Vec3 pos = source.getPosition();
		ServerLevel level = source.getLevel();
		ParkourLocation location = ParkourLocation.from(level, pos.x, pos.y, pos.z, source.getRotation().y, source.getRotation().x);
		storage().parkour(name).scoreboard = location;
		storage().save(source.getServer());
		scoreboard().update(source.getServer(), name);
		source.sendSuccess(() -> message("Parkour '" + name + "' scoreboard ingesteld.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int setFallLocation(CommandSourceStack source, String name, double blocks) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception exception) {
			source.sendFailure(message("Dit command moet door een speler uitgevoerd worden.", ParkourText.RED));
			return 0;
		}

		ServerLevel level = source.getLevel();
		BlockPos standingBlock = player.blockPosition().below();
		// Fall zones are anchored to the block being stood on, with yaw as the intended jump direction.
		ParkourLocation location = ParkourLocation.from(
			level,
			standingBlock.getX() + 0.5D,
			standingBlock.getY() + 1.0D,
			standingBlock.getZ() + 0.5D,
			source.getRotation().y,
			0.0F);

		storage().parkour(name).fallZones.add(new ParkourStorage.FallZone(location, blocks));
		storage().save(source.getServer());
		source.sendSuccess(() -> message("Parkour '" + name + "' fall jump ingesteld: +" + blocks + " blokken.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int setCheckpointLocation(CommandSourceStack source, String name) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception exception) {
			source.sendFailure(message("Dit command moet door een speler uitgevoerd worden.", ParkourText.RED));
			return 0;
		}

		ServerLevel level = source.getLevel();
		BlockPos blockPos = player.blockPosition();
		if (!runtime().isPressurePlate(level, blockPos)) {
			source.sendFailure(message("Je moet op een pressure plate staan.", ParkourText.RED));
			return 0;
		}

		ParkourLocation location = ParkourLocation.from(level, blockPos.getX(), blockPos.getY(), blockPos.getZ(), source.getRotation().y, source.getRotation().x);
		storage().parkour(name).checkpoints.add(location);
		storage().save(source.getServer());
		runtime().updateCheckpointPlateIndex(name);
		source.sendSuccess(() -> message("Parkour '" + name + "' checkpoint toegevoegd.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int showConfig(CommandSourceStack source, String name) {
		if (name.startsWith("-")) {
			return requestDelete(source, name.substring(1));
		}

		ParkourStorage.ParkourData parkour = storage().parkours.get(name);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet.", ParkourText.RED));
			return 0;
		}

		source.sendSuccess(() -> ParkourText.label("--- Parkour: ", ParkourText.GOLD)
			.append(ParkourText.literal(name, ParkourText.GOLD))
			.append(ParkourText.literal(" --- ", ParkourText.GOLD))
			.append(deletePrefix("/parkour config -" + name, "Verwijder parkour '" + name + "' met confirm"))
			.append(ParkourText.literal(" ", ParkourText.MUTED))
			.append(clickable("[rename]", "/parkour config " + name + " rename ", ParkourText.CYAN, "Hernoem deze parkour")), false);
		source.sendSuccess(() -> editableConfigLine("Start", formatLocation(parkour.start), "/parkour set start " + name, "Zet start op de pressure plate waar je op staat"), false);
		source.sendSuccess(() -> editableConfigLine("Spawn", formatLocation(parkour.spawn), "/parkour set spawn " + name, "Zet spawn op je huidige positie en kijkrichting"), false);
		source.sendSuccess(() -> editableConfigLine("Finish", formatLocation(parkour.finish), "/parkour set finish " + name, "Zet finish op de pressure plate waar je op staat"), false);
		source.sendSuccess(() -> editableConfigLine("Scoreboard", formatLocation(parkour.scoreboard), "/parkour set scoreboard " + name, "Zet scoreboard op je huidige positie"), false);
		source.sendSuccess(() -> clickableConfigLine("Checkpoints", String.valueOf(parkour.checkpoints.size()), "/parkour checkpoint list " + name, "Toon alle checkpoints"), false);
		source.sendSuccess(() -> editableConfigLine("Fall distance", formatFallDistance(parkour.fallDistance), "/parkour config " + name + " fallDistance " + formatFallDistance(parkour.fallDistance), "Pas de normale valafstand aan"), false);
		source.sendSuccess(() -> editableConfigLine("Finish teleport", formatSeconds(parkour.finishTeleportDelaySeconds) + "s", "/parkour config " + name + " finishTeleport " + formatSeconds(parkour.finishTeleportDelaySeconds), "Aantal seconden na finish tot teleport naar spawn. 0 = nooit teleporteren"), false);
		source.sendSuccess(() -> clickableConfigLine("Fall points", String.valueOf(parkour.fallZones.size()), "/parkour fall list " + name, "Toon alle diepe fall points"), false);
		if (!parkour.checkpoints.isEmpty()) {
			source.sendSuccess(() -> ParkourText.label("Checkpoints:", ParkourText.GOLD), false);
			for (int i = 0; i < parkour.checkpoints.size(); i++) {
				int id = i + 1;
				ParkourLocation checkpoint = parkour.checkpoints.get(i);
				source.sendSuccess(() -> checkpointLine(name, id, checkpoint), false);
			}
		}
		if (!parkour.fallZones.isEmpty()) {
			source.sendSuccess(() -> ParkourText.label("Fall points:", ParkourText.GOLD), false);
			for (int i = 0; i < parkour.fallZones.size(); i++) {
				int id = i + 1;
				ParkourStorage.FallZone fallZone = parkour.fallZones.get(i);
				source.sendSuccess(() -> fallPointLine(name, id, fallZone), false);
			}
		}
		return Command.SINGLE_SUCCESS;
	}

	private int setFallDistance(CommandSourceStack source, String name, double blocks) {
		ParkourStorage.ParkourData parkour = storage().parkours.get(name);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet.", ParkourText.RED));
			return 0;
		}

		parkour.fallDistance = blocks;
		storage().save(source.getServer());
		source.sendSuccess(() -> message("Parkour '" + name + "' fall distance ingesteld op " + formatFallDistance(blocks) + " blokken.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int setFinishTeleportDelay(CommandSourceStack source, String name, double seconds) {
		ParkourStorage.ParkourData parkour = storage().parkours.get(name);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet.", ParkourText.RED));
			return 0;
		}

		parkour.finishTeleportDelaySeconds = seconds;
		storage().save(source.getServer());
		String value = seconds <= 0.0D ? "uit" : formatSeconds(seconds) + "s";
		source.sendSuccess(() -> message("Parkour '" + name + "' finish teleport ingesteld op " + value + ".", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int renameParkour(CommandSourceStack source, String oldName, String newName) {
		if (!storage().parkours.containsKey(oldName)) {
			source.sendFailure(message("Parkour '" + oldName + "' bestaat niet.", ParkourText.RED));
			return 0;
		}
		if (storage().parkours.containsKey(newName)) {
			source.sendFailure(message("Parkour '" + newName + "' bestaat al.", ParkourText.RED));
			return 0;
		}

		ParkourStorage.ParkourData parkour = storage().parkours.remove(oldName);
		storage().parkours.put(newName, parkour);
		storage().save(source.getServer());
		runtime().rebuildStartPlateIndex();
		scoreboard().remove(source.getServer(), oldName);
		scoreboard().update(source.getServer(), newName);
		source.sendSuccess(() -> message("Parkour '" + oldName + "' hernoemd naar '" + newName + "'.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int requestDelete(CommandSourceStack source, String name) {
		if (!storage().parkours.containsKey(name)) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet.", ParkourText.RED));
			return 0;
		}

		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception exception) {
			source.sendFailure(message("Delete confirm werkt alleen als speler.", ParkourText.RED));
			return 0;
		}

		pendingDeleteConfirmations.put(player.getUUID(), name);
		source.sendSuccess(() -> message("Weet je zeker dat je parkour '" + name + "' wilt verwijderen?", ParkourText.RED), false);
		source.sendSuccess(() -> message("Typ: /parkour confirm delete " + name, ParkourText.GOLD), false);
		return Command.SINGLE_SUCCESS;
	}

	private int confirmDelete(CommandSourceStack source, String name) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception exception) {
			source.sendFailure(message("Delete confirm werkt alleen als speler.", ParkourText.RED));
			return 0;
		}

		String pendingName = pendingDeleteConfirmations.get(player.getUUID());
		if (!name.equals(pendingName)) {
			source.sendFailure(message("Geen delete-confirmatie open voor parkour '" + name + "'.", ParkourText.RED));
			return 0;
		}

		ParkourStorage.ParkourData removed = storage().parkours.remove(name);
		pendingDeleteConfirmations.remove(player.getUUID());
		if (removed == null) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet meer.", ParkourText.RED));
			return 0;
		}

		storage().save(source.getServer());
		runtime().rebuildStartPlateIndex();
		scoreboard().remove(source.getServer(), name);
		source.sendSuccess(() -> message("Parkour '" + name + "' verwijderd.", ParkourText.RED), false);
		return Command.SINGLE_SUCCESS;
	}

	private int listFallPoints(CommandSourceStack source, String name) {
		ParkourStorage.ParkourData parkour = storage().parkours.get(name);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet.", ParkourText.RED));
			return 0;
		}

		source.sendSuccess(() -> ParkourText.label("--- Fall points: ", ParkourText.GOLD)
			.append(ParkourText.literal(name, ParkourText.GOLD))
			.append(ParkourText.literal(" ---", ParkourText.GOLD)), false);
		if (parkour.fallZones.isEmpty()) {
			source.sendSuccess(() -> message("Geen fall points ingesteld.", ParkourText.MUTED), false);
			return Command.SINGLE_SUCCESS;
		}

		for (int i = 0; i < parkour.fallZones.size(); i++) {
			int id = i + 1;
			ParkourStorage.FallZone fallZone = parkour.fallZones.get(i);
			source.sendSuccess(() -> fallPointLine(name, id, fallZone), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private int removeFallPoint(CommandSourceStack source, String name, int id) {
		ParkourStorage.ParkourData parkour = storage().parkours.get(name);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet.", ParkourText.RED));
			return 0;
		}
		if (id < 1 || id > parkour.fallZones.size()) {
			source.sendFailure(message("Fall point #" + id + " bestaat niet voor parkour '" + name + "'.", ParkourText.RED));
			return 0;
		}

		parkour.fallZones.remove(id - 1);
		storage().save(source.getServer());
		source.sendSuccess(() -> message("Fall point #" + id + " verwijderd van parkour '" + name + "'.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int removeFallPointShorthand(CommandSourceStack source, String name, int id) {
		if (!name.startsWith("-")) {
			source.sendFailure(message("Gebruik /parkour fall list <naam> of /parkour fall remove <naam> <id>.", ParkourText.RED));
			return 0;
		}
		return removeFallPoint(source, name.substring(1), id);
	}

	private int listScoreboardTimes(CommandSourceStack source, String name) {
		ParkourStorage.ParkourData parkour = storage().parkours.get(name);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet.", ParkourText.RED));
			return 0;
		}

		source.sendSuccess(() -> ParkourText.label("--- Scoreboard tijden: ", ParkourText.GOLD)
			.append(ParkourText.literal(name, ParkourText.GOLD))
			.append(ParkourText.literal(" ---", ParkourText.GOLD)), false);
		if (parkour.bestTimes.isEmpty()) {
			source.sendSuccess(() -> message("Geen tijden opgeslagen.", ParkourText.MUTED), false);
			return Command.SINGLE_SUCCESS;
		}

		List<Map.Entry<UUID, ParkourStorage.BestTime>> sortedTimes = sortedBestTimes(parkour);
		for (int i = 0; i < sortedTimes.size(); i++) {
			int rank = i + 1;
			ParkourStorage.BestTime best = sortedTimes.get(i).getValue();
			source.sendSuccess(() -> scoreboardTimeLine(name, rank, best), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private int removeScoreboardTime(CommandSourceStack source, String parkourName, String playerName) {
		ParkourStorage.ParkourData parkour = storage().parkours.get(parkourName);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + parkourName + "' bestaat niet.", ParkourText.RED));
			return 0;
		}

		UUID removedPlayerId = null;
		ParkourStorage.BestTime removedBest = null;
		for (Map.Entry<UUID, ParkourStorage.BestTime> entry : parkour.bestTimes.entrySet()) {
			ParkourStorage.BestTime best = entry.getValue();
			if (best.playerName != null && best.playerName.equalsIgnoreCase(playerName)) {
				removedPlayerId = entry.getKey();
				removedBest = best;
				break;
			}
		}

		if (removedPlayerId == null) {
			source.sendFailure(message("Geen tijd gevonden voor '" + playerName + "' op parkour '" + parkourName + "'.", ParkourText.RED));
			return 0;
		}

		parkour.bestTimes.remove(removedPlayerId);
		storage().save(source.getServer());
		scoreboard().update(source.getServer(), parkourName);
		scoreboard().updatePersonalLines(source.getServer(), parkourName);
		ParkourStorage.BestTime finalRemovedBest = removedBest;
		source.sendSuccess(() -> message("Tijd verwijderd: " + finalRemovedBest.playerName + " - " + ParkourText.formatTime(finalRemovedBest.millis), ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int clearScoreboardTimes(CommandSourceStack source, String parkourName) {
		ParkourStorage.ParkourData parkour = storage().parkours.get(parkourName);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + parkourName + "' bestaat niet.", ParkourText.RED));
			return 0;
		}

		int removedCount = parkour.bestTimes.size();
		if (removedCount == 0) {
			source.sendSuccess(() -> message("Parkour '" + parkourName + "' heeft geen opgeslagen tijden.", ParkourText.MUTED), false);
			return Command.SINGLE_SUCCESS;
		}

		parkour.bestTimes.clear();
		storage().save(source.getServer());
		scoreboard().update(source.getServer(), parkourName);
		scoreboard().updatePersonalLines(source.getServer(), parkourName);
		source.sendSuccess(() -> message(removedCount + " tijden verwijderd van parkour '" + parkourName + "'.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int listCheckpoints(CommandSourceStack source, String name) {
		ParkourStorage.ParkourData parkour = storage().parkours.get(name);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet.", ParkourText.RED));
			return 0;
		}

		source.sendSuccess(() -> ParkourText.label("--- Checkpoints: ", ParkourText.GOLD)
			.append(ParkourText.literal(name, ParkourText.GOLD))
			.append(ParkourText.literal(" ---", ParkourText.GOLD)), false);
		if (parkour.checkpoints.isEmpty()) {
			source.sendSuccess(() -> message("Geen checkpoints ingesteld.", ParkourText.MUTED), false);
			return Command.SINGLE_SUCCESS;
		}

		for (int i = 0; i < parkour.checkpoints.size(); i++) {
			int id = i + 1;
			ParkourLocation checkpoint = parkour.checkpoints.get(i);
			source.sendSuccess(() -> checkpointLine(name, id, checkpoint), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private int removeCheckpoint(CommandSourceStack source, String name, int id) {
		ParkourStorage.ParkourData parkour = storage().parkours.get(name);
		if (parkour == null) {
			source.sendFailure(message("Parkour '" + name + "' bestaat niet.", ParkourText.RED));
			return 0;
		}
		if (id < 1 || id > parkour.checkpoints.size()) {
			source.sendFailure(message("Checkpoint #" + id + " bestaat niet voor parkour '" + name + "'.", ParkourText.RED));
			return 0;
		}

		parkour.checkpoints.remove(id - 1);
		storage().save(source.getServer());
		runtime().updateCheckpointPlateIndex(name);
		source.sendSuccess(() -> message("Checkpoint #" + id + " verwijderd van parkour '" + name + "'.", ParkourText.GREEN), false);
		return Command.SINGLE_SUCCESS;
	}

	private int removeCheckpointShorthand(CommandSourceStack source, String name, int id) {
		if (!name.startsWith("-")) {
			source.sendFailure(message("Gebruik /parkour checkpoint list <naam> of /parkour checkpoint remove <naam> <id>.", ParkourText.RED));
			return 0;
		}
		return removeCheckpoint(source, name.substring(1), id);
	}

	private MutableComponent editableConfigLine(String label, String value, String command, String hover) {
		return deleteSpacer()
			.append(clickable(label + ": ", command, ParkourText.GOLD, hover))
			.append(ParkourText.literal(value, ParkourText.MUTED));
	}

	private MutableComponent clickableConfigLine(String label, String value, String command, String hover) {
		return deleteSpacer()
			.append(clickable(label + ": ", command, ParkourText.GOLD, hover))
			.append(ParkourText.literal(value, ParkourText.MUTED));
	}

	private MutableComponent fallPointLine(String parkourName, int id, ParkourStorage.FallZone fallZone) {
		return deletePrefix("/parkour fall remove " + parkourName + " " + id, "Verwijder fall point #" + id)
			.append(ParkourText.literal(" #" + id + " " + formatLocation(fallZone.location) + " +" + formatNumber(fallZone.extraBlocks) + " blocks", ParkourText.MUTED));
	}

	private MutableComponent checkpointLine(String parkourName, int id, ParkourLocation checkpoint) {
		return deletePrefix("/parkour checkpoint remove " + parkourName + " " + id, "Verwijder checkpoint #" + id)
			.append(ParkourText.literal(" #" + id + " " + formatLocation(checkpoint), ParkourText.MUTED));
	}

	private MutableComponent scoreboardTimeLine(String parkourName, int rank, ParkourStorage.BestTime best) {
		return deletePrefix("/parkour times clear " + parkourName + " " + best.playerName, "Verwijder de tijd van " + best.playerName)
			.append(ParkourText.literal(" #" + rank + " ", ParkourText.GOLD))
			.append(ParkourText.raw(best.playerName, ParkourText.TEXT))
			.append(ParkourText.literal(" - " + ParkourText.formatTime(best.millis), ParkourText.MUTED));
	}

	private MutableComponent deletePrefix(String command, String hover) {
		return clickable("-", command, ParkourText.RED, hover);
	}

	private MutableComponent deleteSpacer() {
		return ParkourText.literal("  ", ParkourText.MUTED);
	}

	private MutableComponent clickable(String text, String command, int color, String hover) {
		return ParkourText.label(text, color).withStyle(style -> style
			.withClickEvent(new ClickEvent.SuggestCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(
				ParkourText.label(hover, ParkourText.MUTED)
					.append(ParkourText.literal("\n", ParkourText.MUTED))
					.append(ParkourText.label("Klik om command in te vullen:", ParkourText.GOLD))
					.append(ParkourText.literal("\n" + command, ParkourText.CYAN)))));
	}

	private MutableComponent message(String text, int color) {
		return ParkourText.label(text, color);
	}

	private String formatLocation(ParkourLocation location) {
		if (location == null) {
			return "niet ingesteld";
		}
		return "%s %s %s %s yaw %s pitch %s".formatted(
			location.dimension(),
			formatNumber(location.x()),
			formatNumber(location.y()),
			formatNumber(location.z()),
			formatNumber(location.yaw()),
			formatNumber(location.pitch()));
	}

	private String formatNumber(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private String formatFallDistance(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private String formatSeconds(Double value) {
		double seconds = value == null ? 1.0D : value;
		return String.format(Locale.ROOT, "%.1f", seconds);
	}

	private List<Map.Entry<UUID, ParkourStorage.BestTime>> sortedBestTimes(ParkourStorage.ParkourData parkour) {
		return parkour.bestTimes.entrySet().stream()
			.sorted(Comparator.comparingLong(entry -> entry.getValue().millis))
			.toList();
	}

	private ParkourStorage storage() {
		return storage.get();
	}

	private ParkourRuntime runtime() {
		return runtime.get();
	}

	private ParkourScoreboard scoreboard() {
		return scoreboard.get();
	}
}
