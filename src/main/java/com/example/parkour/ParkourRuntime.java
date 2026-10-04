package com.example.parkour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.util.EnumSet;
import java.util.Comparator;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ParkourRuntime {
	private static final String NO_COLLISION_TEAM_NAME = "parkour_nocoll";
	private static final int CHECKPOINT_SLOT = 6;
	private static final int VISIBILITY_SLOT = 7;
	private static final int RESET_SLOT = 8;
	private static final double FALL_ZONE_RADIUS = 2.5D;
	private static final double SPAWN_YAW_CHECK_RADIUS = 3.0D;
	private static final double PRESSURE_PLATE_EDGE_SAMPLE = 0.25D;
	private static final double PRESSURE_PLATE_MAX_CENTER_DISTANCE = 0.86D;
	private static final double PRESSURE_PLATE_TOUCH_HEIGHT = 0.25D;
	private static final long FALL_DAMAGE_GRACE_MILLIS = 1000L;
	private static final byte INVISIBLE_FLAG = 0x20;
	private static final EntityDataAccessor<Byte> SHARED_FLAGS_ACCESSOR = sharedFlagsAccessor();
	private static final Component RESET_ITEM_NAME = ParkourText.label("Reset", ParkourText.RED);
	private static final Component CHECKPOINT_ITEM_NAME = ParkourText.label("Checkpoint", ParkourText.ORANGE);
	private static final Component HIDE_PLAYERS_ITEM_NAME = ParkourText.label("Hide Players", ParkourText.CYAN);
	private static final Component SHOW_PLAYERS_ITEM_NAME = ParkourText.label("Show Players", ParkourText.GREEN);

	private final ParkourStorage storage;
	private final ParkourScoreboard scoreboard;
	private final Map<UUID, RunState> runs = new HashMap<>();
	private final Map<UUID, BlockPos> lastStartPlates = new HashMap<>();
	private final Map<UUID, Long> fallDamageGraceUntil = new HashMap<>();
	private final Map<UUID, PendingTeleport> finishTeleports = new HashMap<>();
	private final Map<UUID, PendingFinishFeedback> finishFeedbacks = new HashMap<>();
	private final Map<UUID, Set<Integer>> hiddenEntityIdsByViewer = new HashMap<>();
	private final Set<UUID> playersHiddenViewers = new HashSet<>();
	private final Map<UUID, String> previousTeams = new HashMap<>();
	private final Map<UUID, GameType> previousGameModes = new HashMap<>();
	private final Map<UUID, PlayerSnapshot> playerSnapshots = new HashMap<>();
	private final Map<PlateKey, String> startPlateIndex = new HashMap<>();
	private final Map<String, Map<PlateKey, Integer>> checkpointPlateIndexes = new HashMap<>();
	private final Set<String> startPlateDimensions = new HashSet<>();

	ParkourRuntime(ParkourStorage storage, ParkourScoreboard scoreboard) {
		this.storage = storage;
		this.scoreboard = scoreboard;
		rebuildStartPlateIndex();
	}

	void rebuildStartPlateIndex() {
		// Cache start plates by dimension + block position so idle players do not scan every parkour each tick.
		startPlateIndex.clear();
		checkpointPlateIndexes.clear();
		startPlateDimensions.clear();
		storage.parkours.forEach((name, parkour) -> {
			if (parkour.start != null) {
				startPlateIndex.put(new PlateKey(parkour.start.dimension(), parkour.start.blockPos()), name);
				startPlateDimensions.add(parkour.start.dimension());
			}
			rebuildCheckpointPlateIndex(name, parkour);
		});
	}

	void updateStartPlateIndex(String name) {
		startPlateIndex.entrySet().removeIf(entry -> entry.getValue().equals(name));
		rebuildStartPlateDimensions();
		ParkourStorage.ParkourData parkour = storage.parkours.get(name);
		if (parkour != null && parkour.start != null) {
			startPlateIndex.put(new PlateKey(parkour.start.dimension(), parkour.start.blockPos()), name);
			startPlateDimensions.add(parkour.start.dimension());
		}
	}

	void updateCheckpointPlateIndex(String name) {
		checkpointPlateIndexes.remove(name);
		ParkourStorage.ParkourData parkour = storage.parkours.get(name);
		if (parkour != null) {
			rebuildCheckpointPlateIndex(name, parkour);
		}
	}

	private void rebuildStartPlateDimensions() {
		startPlateDimensions.clear();
		startPlateIndex.keySet().forEach(key -> startPlateDimensions.add(key.dimension()));
	}

	private void rebuildCheckpointPlateIndex(String name, ParkourStorage.ParkourData parkour) {
		Map<PlateKey, Integer> checkpointIndex = new HashMap<>();
		for (int i = 0; i < parkour.checkpoints.size(); i++) {
			ParkourLocation checkpoint = parkour.checkpoints.get(i);
			if (checkpoint != null) {
				checkpointIndex.put(new PlateKey(checkpoint.dimension(), checkpoint.blockPos()), i);
			}
		}
		if (!checkpointIndex.isEmpty()) {
			checkpointPlateIndexes.put(name, checkpointIndex);
		}
	}

	void tick(MinecraftServer server) {
		tickFinishTeleports(server);
		tickFinishFeedbacks(server);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			long currentNanos = System.nanoTime();

			RunState run = runs.get(player.getUUID());
			if (run == null) {
				continue;
			}

			ParkourStorage.ParkourData parkour = storage.parkours.get(run.parkourName);
			if (parkour == null || !parkour.isComplete()) {
				cancelRun(player);
				continue;
			}

			player.sendOverlayMessage(ParkourText.timerActionBar(elapsedMillis(run.startedAtNanos, currentNanos)));
			if (playersHiddenViewers.contains(player.getUUID())) {
				updateHiddenPlayers(player);
			}
			updateFallAllowance(player, run, parkour);

			if (player.getY() > run.highestY) {
				run.highestY = player.getY();
			}

			if (run.highestY - player.getY() >= parkour.fallDistance + run.extraFallBlocks) {
				if (parkour.checkpoints.isEmpty() || run.reachedCheckpoints.isEmpty()) {
					teleportToSpawn(server, player, parkour);
					cancelRun(player);
				} else {
					teleportToLastCheckpoint(server, player, run, parkour);
					run.highestY = player.getY();
					run.extraFallBlocks = 0.0D;
					playSound(player, SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.8F);
				}
				continue;
			}

			if (isNearSpawn(player, parkour) && yawDifference(player.getYRot(), parkour.spawn.yaw()) > 60.0F) {
				cancelRun(player);
				continue;
			}
		}
	}

	void handleAcceptedMovement(ServerPlayer player, double oldX, double oldY, double oldZ, long currentNanos) {
		ServerLevel level = player.level();
		UUID playerId = player.getUUID();
		RunState run = runs.get(playerId);
		String dimension = level.dimension().identifier().toString();
		if (run == null && !startPlateDimensions.contains(dimension)) {
			return;
		}

		BlockPos currentPlate = pressurePlateAtPosition(level, player, player.getX(), player.getY(), player.getZ());
		BlockPos previousPlate = currentPlate == null ? null : pressurePlateAtPosition(level, player, oldX, oldY, oldZ);
		PlayerSnapshot previousSnapshot = currentPlate == null ? null : movementPreviousSnapshot(player, level, oldX, oldY, oldZ, currentNanos);

		if (currentPlate == null) {
			lastStartPlates.remove(playerId);
		} else {
			BlockPos lastStartPlate = lastStartPlates.get(playerId);
			boolean steppedOntoStartPlate = !currentPlate.equals(lastStartPlate) && !currentPlate.equals(previousPlate);

			// Movement handling sees the accepted server position immediately, so start timing before the next tick.
			if (run == null && steppedOntoStartPlate) {
				String parkourName = startPlateIndex.get(PlateKey.of(level, currentPlate));
				ParkourStorage.ParkourData parkour = parkourName == null ? null : storage.parkours.get(parkourName);
				if (parkour != null && parkour.isComplete()) {
					long startNanos = interpolatedTriggerNanos(level, player, previousSnapshot, currentPlate, player.getX(), player.getY(), player.getZ(), currentNanos);
					startRun(player, parkourName, parkour, startNanos);
					run = runs.get(playerId);
				}
			}
			lastStartPlates.put(playerId, currentPlate);
		}

		if (run != null) {
			ParkourStorage.ParkourData parkour = storage.parkours.get(run.parkourName);
			if (parkour != null && parkour.isComplete() && movedAwayFromSpawn(player, parkour, oldX, oldZ)) {
				cancelRun(player);
				rememberSnapshot(player, currentNanos);
				return;
			}
			if (parkour != null && parkour.isComplete() && currentPlate != null) {
				markCheckpoint(level, player, run, parkour, currentPlate);
			}
			if (parkour != null && parkour.isComplete() && currentPlate != null && isSameBlock(level, currentPlate, parkour.finish)) {
				if (!hasReachedAllCheckpoints(run, parkour)) {
					rememberSnapshot(player, currentNanos);
					return;
				}
				long finishNanos = interpolatedTriggerNanos(level, player, previousSnapshot, currentPlate, player.getX(), player.getY(), player.getZ(), currentNanos);
				finishRun(level.getServer(), player, run, parkour, finishNanos);
			}
		}

		rememberSnapshot(player, currentNanos);
	}

	boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer player)) {
			return true;
		}

		if (runs.containsKey(player.getUUID()) && isEntityAttack(source)) {
			return false;
		}

		if (!source.is(DamageTypes.FALL)) {
			return true;
		}

		// Active runs and short post-teleport windows should never apply fall damage.
		return !runs.containsKey(player.getUUID()) && System.currentTimeMillis() > fallDamageGraceUntil.getOrDefault(player.getUUID(), 0L);
	}

	InteractionResult attackEntity(Player player, Level world, InteractionHand hand, Entity entity, EntityHitResult hitResult) {
		if (!(player instanceof ServerPlayer serverPlayer) || !runs.containsKey(serverPlayer.getUUID())) {
			return InteractionResult.PASS;
		}

		return InteractionResult.FAIL;
	}

	void cleanupPlayer(ServerPlayer player) {
		UUID playerId = player.getUUID();
		runs.remove(playerId);
		lastStartPlates.remove(playerId);
		fallDamageGraceUntil.remove(playerId);
		finishTeleports.remove(playerId);
		finishFeedbacks.remove(playerId);
		playerSnapshots.remove(playerId);
		removeCheckpointItem(player);
		removeVisibilityItem(player);
		removeResetItem(player);
		showAllHiddenPlayers(player);
		restoreGameMode(player);
		if (previousTeams.containsKey(playerId)) {
			restorePlayerCollision(player);
		}

		int entityId = player.getId();
		hiddenEntityIdsByViewer.values().forEach(hiddenIds -> hiddenIds.remove(entityId));
		playersHiddenViewers.remove(playerId);
	}

	InteractionResult useResetItem(Player player, Level world, InteractionHand hand) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		ItemStack usedItem = player.getItemInHand(hand);
		boolean resetItem = isResetItem(usedItem);
		boolean checkpointItem = isCheckpointItem(usedItem);
		boolean visibilityItem = isVisibilityItem(usedItem);
		if (!resetItem && !checkpointItem && !visibilityItem) {
			return InteractionResult.PASS;
		}

		RunState run = runs.get(serverPlayer.getUUID());
		if (run == null) {
			return InteractionResult.PASS;
		}

		ParkourStorage.ParkourData parkour = storage.parkours.get(run.parkourName);
		if (parkour == null || parkour.spawn == null) {
			return InteractionResult.PASS;
		}

		if (visibilityItem) {
			togglePlayerVisibility(serverPlayer);
			return InteractionResult.SUCCESS;
		} else if (resetItem) {
			cancelRun(serverPlayer, false);
			teleportToSpawn(serverPlayer.level().getServer(), serverPlayer, parkour);
		} else {
			if (parkour.checkpoints.isEmpty()) {
				return InteractionResult.PASS;
			}
			teleportToLastCheckpoint(serverPlayer.level().getServer(), serverPlayer, run, parkour);
			run.highestY = serverPlayer.getY();
			run.extraFallBlocks = 0.0D;
			addFallDamageGrace(serverPlayer);
		}
		playSound(serverPlayer, SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.8F);
		return InteractionResult.SUCCESS;
	}

	boolean shouldBlockContainerClick(ServerPlayer player, ServerboundContainerClickPacket packet) {
		if (!runs.containsKey(player.getUUID()) || packet.containerId() != player.containerMenu.containerId) {
			return false;
		}

		boolean touchesProtectedSlot = isProtectedMenuSlot(player, packet.slotNum())
			|| isProtectedHotbarSwap(player, packet)
			|| changedSlotsTouchProtectedItem(player, packet);
		if (!touchesProtectedSlot && !isProtectedParkourItem(player.containerMenu.getCarried())) {
			return false;
		}

		resyncProtectedItems(player);
		return true;
	}

	boolean shouldBlockPlayerAction(ServerPlayer player, ServerboundPlayerActionPacket packet) {
		if (!runs.containsKey(player.getUUID())) {
			return false;
		}

		ServerboundPlayerActionPacket.Action action = packet.getAction();
		if (action != ServerboundPlayerActionPacket.Action.DROP_ITEM
			&& action != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
			&& action != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
			return false;
		}

		if (!isProtectedParkourItem(player.getInventory().getSelectedItem())) {
			return false;
		}

		resyncProtectedItems(player);
		return true;
	}

	boolean isPressurePlate(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getBlock() instanceof BasePressurePlateBlock;
	}

	private boolean isSameBlock(ServerLevel level, BlockPos pos, ParkourLocation location) {
		return level.dimension().identifier().toString().equals(location.dimension()) && pos.equals(location.blockPos());
	}

	private BlockPos pressurePlateAtPlayer(ServerLevel level, ServerPlayer player) {
		return pressurePlateAtPosition(level, player, player.getX(), player.getY(), player.getZ());
	}

	private BlockPos pressurePlateAtPosition(ServerLevel level, ServerPlayer player, double x, double y, double z) {
		int blockY = (int) Math.floor(y);
		// Sample the player's footprint so edge contact still counts, but validate height and center distance below.
		double[][] samples = {
			{x, z},
			{x - PRESSURE_PLATE_EDGE_SAMPLE, z},
			{x + PRESSURE_PLATE_EDGE_SAMPLE, z},
			{x, z - PRESSURE_PLATE_EDGE_SAMPLE},
			{x, z + PRESSURE_PLATE_EDGE_SAMPLE},
			{x - PRESSURE_PLATE_EDGE_SAMPLE, z - PRESSURE_PLATE_EDGE_SAMPLE},
			{x - PRESSURE_PLATE_EDGE_SAMPLE, z + PRESSURE_PLATE_EDGE_SAMPLE},
			{x + PRESSURE_PLATE_EDGE_SAMPLE, z - PRESSURE_PLATE_EDGE_SAMPLE},
			{x + PRESSURE_PLATE_EDGE_SAMPLE, z + PRESSURE_PLATE_EDGE_SAMPLE}
		};

		for (double[] sample : samples) {
			BlockPos candidate = BlockPos.containing(sample[0], blockY, sample[1]);
			if (isPressurePlate(level, candidate) && isCloseEnoughToPressurePlateCenter(x, z, candidate) && isFeetNearPressurePlate(y, candidate)) {
				return candidate;
			}
			BlockPos below = candidate.below();
			if (isPressurePlate(level, below) && isCloseEnoughToPressurePlateCenter(x, z, below) && isFeetNearPressurePlate(y, below)) {
				return below;
			}
		}
		return null;
	}

	private boolean isCloseEnoughToPressurePlateCenter(ServerPlayer player, BlockPos platePos) {
		return isCloseEnoughToPressurePlateCenter(player.getX(), player.getZ(), platePos);
	}

	private boolean isCloseEnoughToPressurePlateCenter(double x, double z, BlockPos platePos) {
		double dx = x - (platePos.getX() + 0.5D);
		double dz = z - (platePos.getZ() + 0.5D);
		return dx * dx + dz * dz <= PRESSURE_PLATE_MAX_CENTER_DISTANCE * PRESSURE_PLATE_MAX_CENTER_DISTANCE;
	}

	private boolean isFeetNearPressurePlate(ServerPlayer player, BlockPos platePos) {
		return isFeetNearPressurePlate(player.getY(), platePos);
	}

	private boolean isFeetNearPressurePlate(double y, BlockPos platePos) {
		// Prevent triggering plates while flying or falling above them.
		double feetAbovePlateBlock = y - platePos.getY();
		return feetAbovePlateBlock >= -0.05D && feetAbovePlateBlock <= PRESSURE_PLATE_TOUCH_HEIGHT;
	}

	private PlayerSnapshot movementPreviousSnapshot(ServerPlayer player, ServerLevel level, double oldX, double oldY, double oldZ, long currentNanos) {
		PlayerSnapshot previousSnapshot = playerSnapshots.get(player.getUUID());
		if (previousSnapshot != null && previousSnapshot.dimension.equals(level.dimension().identifier().toString()) && isSamePosition(previousSnapshot, oldX, oldY, oldZ)) {
			return previousSnapshot;
		}
		return new PlayerSnapshot(level.dimension().identifier().toString(), oldX, oldY, oldZ, currentNanos);
	}

	private boolean isSamePosition(PlayerSnapshot snapshot, double x, double y, double z) {
		return Math.abs(snapshot.x - x) < 1.0E-5D && Math.abs(snapshot.y - y) < 1.0E-5D && Math.abs(snapshot.z - z) < 1.0E-5D;
	}

	private long interpolatedTriggerNanos(ServerLevel level, ServerPlayer player, PlayerSnapshot previousSnapshot, BlockPos targetPlate, double newX, double newY, double newZ, long currentNanos) {
		if (previousSnapshot == null || !previousSnapshot.dimension.equals(level.dimension().identifier().toString())) {
			return currentNanos;
		}

		BlockPos previousPlate = pressurePlateAtPosition(level, player, previousSnapshot.x, previousSnapshot.y, previousSnapshot.z);
		if (targetPlate.equals(previousPlate)) {
			return currentNanos;
		}

		double low = 0.0D;
		double high = 1.0D;
		for (int i = 0; i < 12; i++) {
			double mid = (low + high) * 0.5D;
			double x = lerp(previousSnapshot.x, newX, mid);
			double y = lerp(previousSnapshot.y, newY, mid);
			double z = lerp(previousSnapshot.z, newZ, mid);
			BlockPos plate = pressurePlateAtPosition(level, player, x, y, z);
			if (targetPlate.equals(plate)) {
				high = mid;
			} else {
				low = mid;
			}
		}

		long tickNanos = currentNanos - previousSnapshot.nanos;
		if (tickNanos <= 0L) {
			return currentNanos;
		}
		return previousSnapshot.nanos + (long) (tickNanos * high);
	}

	private void rememberSnapshot(ServerPlayer player, long nanos) {
		playerSnapshots.put(player.getUUID(), new PlayerSnapshot(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(), nanos));
	}

	private static double lerp(double start, double end, double fraction) {
		return start + (end - start) * fraction;
	}

	private void startRun(ServerPlayer player, String parkourName, ParkourStorage.ParkourData parkour, long startedAtNanos) {
		finishFeedbacks.remove(player.getUUID());
		runs.put(player.getUUID(), new RunState(parkourName, startedAtNanos, player.getY(), parkour.spawn));
		applyAdventureMode(player);
		disablePlayerCollision(player);
		if (!parkour.checkpoints.isEmpty()) {
			giveCheckpointItem(player);
		}
		giveVisibilityItem(player, false);
		giveResetItem(player);
		playSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8F, 1.35F);
	}

	private void finishRun(MinecraftServer server, ServerPlayer player, RunState run, ParkourStorage.ParkourData parkour, long finishedAtNanos) {
		runs.remove(player.getUUID());
		restorePlayerCollision(player);
		restoreGameMode(player);
		removeCheckpointItem(player);
		removeVisibilityItem(player);
		removeResetItem(player);
		showAllHiddenPlayers(player);
		addFallDamageGrace(player);
		int finishTeleportDelayTicks = finishTeleportDelayTicks(parkour);
		if (finishTeleportDelayTicks > 0) {
			finishTeleports.put(player.getUUID(), new PendingTeleport(run.parkourName, finishTeleportDelayTicks));
		}
		long millis = elapsedMillis(run.startedAtNanos, finishedAtNanos);
		ParkourStorage.BestTime personalBest = parkour.bestTimes.get(player.getUUID());
		ParkourStorage.BestTime globalBest = parkour.bestTimes.values().stream()
			.min(Comparator.comparingLong(best -> best.millis))
			.orElse(null);
		boolean newPersonalBest = personalBest == null || millis < personalBest.millis;
		boolean newGlobalRecord = globalBest == null || millis < globalBest.millis;
		long displayedPersonalBestMillis = newPersonalBest ? millis : personalBest.millis;
		long displayedWorldRecordMillis = newGlobalRecord ? millis : globalBest.millis;

		if (newPersonalBest) {
			parkour.bestTimes.put(player.getUUID(), new ParkourStorage.BestTime(player.getGameProfile().name(), millis));
			storage.save(server);
			scoreboard.update(server, run.parkourName);
			scoreboard.updatePersonalLines(server, run.parkourName);
		}

		if (newPersonalBest) {
			playSound(player, SoundEvents.PLAYER_LEVELUP, 0.8F, 1.2F);
		} else {
			playSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8F, 1.6F);
		}

		Component finishFeedback = ParkourText.finishActionBar(millis, displayedPersonalBestMillis, displayedWorldRecordMillis);
		finishFeedbacks.put(player.getUUID(), new PendingFinishFeedback(finishFeedback, 100));

		if (newGlobalRecord) {
			spawnWorldRecordFirework(server, parkour);
			player.sendOverlayMessage(finishFeedback);
			server.getPlayerList().broadcastSystemMessage(ParkourText.recordBroadcast(player.getGameProfile().name(), run.parkourName, millis), false);
		} else {
			player.sendOverlayMessage(finishFeedback);
		}
	}

	private void spawnWorldRecordFirework(MinecraftServer server, ParkourStorage.ParkourData parkour) {
		ServerLevel level = server.getLevel(parkour.finish.dimensionKey());
		if (level == null) {
			return;
		}

		double x = parkour.finish.blockPos().getX() + 0.5D;
		double y = parkour.finish.blockPos().getY() + 0.7D;
		double z = parkour.finish.blockPos().getZ() + 0.5D;
		level.sendParticles(ParticleTypes.FIREWORK, x, y, z, 80, 0.55D, 0.45D, 0.55D, 0.18D);
		level.sendParticles(ParticleTypes.END_ROD, x, y, z, 18, 0.35D, 0.25D, 0.35D, 0.05D);
	}

	private void teleportToSpawn(MinecraftServer server, ServerPlayer player, ParkourStorage.ParkourData parkour) {
		teleportToLocation(server, player, parkour.spawn);
	}

	private void teleportToLastCheckpoint(MinecraftServer server, ServerPlayer player, RunState run, ParkourStorage.ParkourData parkour) {
		ParkourLocation target = run.lastCheckpoint == null ? parkour.spawn : run.lastCheckpoint;
		if (target == parkour.spawn) {
			teleportToLocation(server, player, target);
			return;
		}

		ParkourLocation centeredPlateTarget = new ParkourLocation(
			target.dimension(),
			target.blockPos().getX() + 0.5D,
			target.blockPos().getY() + 0.1D,
			target.blockPos().getZ() + 0.5D,
			target.yaw(),
			target.pitch());
		teleportToLocation(server, player, centeredPlateTarget);
	}

	private void teleportToLocation(MinecraftServer server, ServerPlayer player, ParkourLocation location) {
		if (location == null) {
			return;
		}
		ServerLevel level = server.getLevel(location.dimensionKey());
		if (level == null) {
			return;
		}
		addFallDamageGrace(player);
		player.teleportTo(level, location.x(), location.y(), location.z(), EnumSet.noneOf(net.minecraft.world.entity.Relative.class), location.yaw(), location.pitch(), false);
	}

	private void tickFinishTeleports(MinecraftServer server) {
		// Finish feedback stays visible briefly before moving the player back to spawn.
		finishTeleports.entrySet().removeIf(entry -> {
			PendingTeleport pendingTeleport = entry.getValue();
			pendingTeleport.ticksRemaining--;
			if (pendingTeleport.ticksRemaining > 0) {
				return false;
			}

			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			ParkourStorage.ParkourData parkour = storage.parkours.get(pendingTeleport.parkourName);
			if (player != null && parkour != null && parkour.spawn != null) {
				teleportToSpawn(server, player, parkour);
			}
			return true;
		});
	}

	private void tickFinishFeedbacks(MinecraftServer server) {
		finishFeedbacks.entrySet().removeIf(entry -> {
			PendingFinishFeedback feedback = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || feedback.ticksRemaining <= 0) {
				return true;
			}

			player.sendOverlayMessage(feedback.message);
			feedback.ticksRemaining--;
			return false;
		});
	}

	private int finishTeleportDelayTicks(ParkourStorage.ParkourData parkour) {
		double seconds = parkour.finishTeleportDelaySeconds == null ? 1.0D : parkour.finishTeleportDelaySeconds;
		if (seconds <= 0.0D) {
			return 0;
		}
		return Math.max(1, (int) Math.round(seconds * 20.0D));
	}

	private boolean isNearSpawn(ServerPlayer player, ParkourStorage.ParkourData parkour) {
		if (!player.level().dimension().identifier().toString().equals(parkour.spawn.dimension())) {
			return false;
		}

		double dx = player.getX() - parkour.spawn.x();
		double dy = player.getY() - parkour.spawn.y();
		double dz = player.getZ() - parkour.spawn.z();
		return dx * dx + dy * dy + dz * dz <= SPAWN_YAW_CHECK_RADIUS * SPAWN_YAW_CHECK_RADIUS;
	}

	private boolean movedAwayFromSpawn(ServerPlayer player, ParkourStorage.ParkourData parkour, double oldX, double oldZ) {
		if (!player.level().dimension().identifier().toString().equals(parkour.spawn.dimension())) {
			return false;
		}

		double oldDx = oldX - parkour.spawn.x();
		double oldDz = oldZ - parkour.spawn.z();
		double newDx = player.getX() - parkour.spawn.x();
		double newDz = player.getZ() - parkour.spawn.z();
		double oldDistanceSqr = oldDx * oldDx + oldDz * oldDz;
		double newDistanceSqr = newDx * newDx + newDz * newDz;
		double guardRadiusSqr = SPAWN_YAW_CHECK_RADIUS * SPAWN_YAW_CHECK_RADIUS;
		if (oldDistanceSqr > guardRadiusSqr || newDistanceSqr <= oldDistanceSqr) {
			return false;
		}

		double moveX = player.getX() - oldX;
		double moveZ = player.getZ() - oldZ;
		double moveDistanceSqr = moveX * moveX + moveZ * moveZ;
		if (moveDistanceSqr < 1.0E-4D) {
			return false;
		}

		return yawDifference(horizontalMovementYaw(moveX, moveZ), parkour.spawn.yaw()) > 60.0F;
	}

	private void markCheckpoint(ServerLevel level, ServerPlayer player, RunState run, ParkourStorage.ParkourData parkour, BlockPos platePos) {
		Map<PlateKey, Integer> checkpointIndex = checkpointPlateIndexes.get(run.parkourName);
		if (checkpointIndex == null) {
			return;
		}

		Integer checkpointId = checkpointIndex.get(PlateKey.of(level, platePos));
		if (checkpointId == null || run.reachedCheckpoints.contains(checkpointId) || checkpointId >= parkour.checkpoints.size()) {
			return;
		}

		ParkourLocation checkpoint = parkour.checkpoints.get(checkpointId);
		run.reachedCheckpoints.add(checkpointId);
		run.lastCheckpoint = checkpoint;
		run.highestY = player.getY();
		run.extraFallBlocks = 0.0D;
		playSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.6F, 1.8F);
	}

	private boolean hasReachedAllCheckpoints(RunState run, ParkourStorage.ParkourData parkour) {
		return run.reachedCheckpoints.size() >= parkour.checkpoints.size();
	}

	private void updateFallAllowance(ServerPlayer player, RunState run, ParkourStorage.ParkourData parkour) {
		boolean nearValidFallZone = false;
		double allowedExtraFallBlocks = 0.0D;
		for (ParkourStorage.FallZone fallZone : parkour.fallZones) {
			if (fallZone.location == null || !isNearFallZone(player, fallZone)) {
				continue;
			}
			nearValidFallZone = true;
			if (yawDifference(player.getYRot(), fallZone.location.yaw()) <= 60.0F) {
				allowedExtraFallBlocks = Math.max(allowedExtraFallBlocks, fallZone.extraBlocks);
			}
		}

		if (nearValidFallZone) {
			// Arm deep jumps while grounded and keep the allowance during the jump once it is valid.
			if (player.onGround()) {
				run.extraFallBlocks = allowedExtraFallBlocks;
			} else if (allowedExtraFallBlocks > 0.0D) {
				run.extraFallBlocks = Math.max(run.extraFallBlocks, allowedExtraFallBlocks);
			}
		}

		if (player.onGround() && !nearValidFallZone) {
			run.extraFallBlocks = 0.0D;
			run.highestY = player.getY();
		}
	}

	private boolean isNearFallZone(ServerPlayer player, ParkourStorage.FallZone fallZone) {
		ParkourLocation location = fallZone.location;
		if (!player.level().dimension().identifier().toString().equals(location.dimension())) {
			return false;
		}

		double dx = player.getX() - location.x();
		double dy = player.getY() - location.y();
		double dz = player.getZ() - location.z();
		return dx * dx + dy * dy + dz * dz <= FALL_ZONE_RADIUS * FALL_ZONE_RADIUS;
	}

	private void cancelRun(ServerPlayer player) {
		cancelRun(player, true);
	}

	private void cancelRun(ServerPlayer player, boolean playCancelSound) {
		runs.remove(player.getUUID());
		restorePlayerCollision(player);
		restoreGameMode(player);
		removeCheckpointItem(player);
		removeVisibilityItem(player);
		removeResetItem(player);
		showAllHiddenPlayers(player);
		addFallDamageGrace(player);
		if (playCancelSound) {
			playSound(player, SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.8F);
		}
		player.sendOverlayMessage(ParkourText.stoppedActionBar());
	}

	private void addFallDamageGrace(ServerPlayer player) {
		fallDamageGraceUntil.put(player.getUUID(), System.currentTimeMillis() + FALL_DAMAGE_GRACE_MILLIS);
	}

	private void playSound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		player.connection.send(new ClientboundSoundPacket(
			BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
			SoundSource.PLAYERS,
			player.getX(),
			player.getY(),
			player.getZ(),
			volume,
			pitch,
			player.getRandom().nextLong()));
	}

	private void giveResetItem(ServerPlayer player) {
		// Slot 8 is the ninth hotbar slot in vanilla inventory indexing.
		ItemStack resetItem = new ItemStack(Items.BARRIER);
		resetItem.set(DataComponents.CUSTOM_NAME, RESET_ITEM_NAME);
		player.getInventory().setItem(RESET_SLOT, resetItem);
	}

	private void giveCheckpointItem(ServerPlayer player) {
		ItemStack checkpointItem = new ItemStack(Items.IRON_DOOR);
		checkpointItem.set(DataComponents.CUSTOM_NAME, CHECKPOINT_ITEM_NAME);
		player.getInventory().setItem(CHECKPOINT_SLOT, checkpointItem);
	}

	private void giveVisibilityItem(ServerPlayer player, boolean playersHidden) {
		ItemStack visibilityItem = new ItemStack(playersHidden ? Items.ENDER_PEARL : Items.ENDER_EYE);
		visibilityItem.set(DataComponents.CUSTOM_NAME, playersHidden ? SHOW_PLAYERS_ITEM_NAME : HIDE_PLAYERS_ITEM_NAME);
		player.getInventory().setItem(VISIBILITY_SLOT, visibilityItem);
	}

	private void removeResetItem(ServerPlayer player) {
		if (isResetItem(player.getInventory().getItem(RESET_SLOT))) {
			player.getInventory().setItem(RESET_SLOT, ItemStack.EMPTY);
		}
	}

	private void removeCheckpointItem(ServerPlayer player) {
		if (isCheckpointItem(player.getInventory().getItem(CHECKPOINT_SLOT))) {
			player.getInventory().setItem(CHECKPOINT_SLOT, ItemStack.EMPTY);
		}
	}

	private void removeVisibilityItem(ServerPlayer player) {
		if (isVisibilityItem(player.getInventory().getItem(VISIBILITY_SLOT))) {
			player.getInventory().setItem(VISIBILITY_SLOT, ItemStack.EMPTY);
		}
	}

	private boolean isResetItem(ItemStack stack) {
		return stack.is(Items.BARRIER) && RESET_ITEM_NAME.equals(stack.getCustomName());
	}

	private boolean isCheckpointItem(ItemStack stack) {
		return stack.is(Items.IRON_DOOR) && CHECKPOINT_ITEM_NAME.equals(stack.getCustomName());
	}

	private boolean isVisibilityItem(ItemStack stack) {
		return (stack.is(Items.ENDER_EYE) && HIDE_PLAYERS_ITEM_NAME.equals(stack.getCustomName()))
			|| (stack.is(Items.ENDER_PEARL) && SHOW_PLAYERS_ITEM_NAME.equals(stack.getCustomName()));
	}

	private boolean isEntityAttack(DamageSource source) {
		return source.getEntity() instanceof LivingEntity || source.getDirectEntity() instanceof LivingEntity;
	}

	private boolean isProtectedParkourItem(ItemStack stack) {
		return isResetItem(stack) || isCheckpointItem(stack) || isVisibilityItem(stack);
	}

	private boolean isProtectedHotbarSwap(ServerPlayer player, ServerboundContainerClickPacket packet) {
		return packet.containerInput() == ContainerInput.SWAP && isProtectedInventoryItemAtSlot(player, packet.buttonNum());
	}

	private boolean changedSlotsTouchProtectedItem(ServerPlayer player, ServerboundContainerClickPacket packet) {
		for (int slotNum : packet.changedSlots().keySet()) {
			if (isProtectedMenuSlot(player, slotNum)) {
				return true;
			}
		}
		return false;
	}

	private boolean isProtectedMenuSlot(ServerPlayer player, int slotNum) {
		if (slotNum < 0 || !player.containerMenu.isValidSlotIndex(slotNum)) {
			return false;
		}

		Slot slot = player.containerMenu.getSlot(slotNum);
		return slot.container == player.getInventory() && isProtectedInventoryItemAtSlot(player, slot.getContainerSlot());
	}

	private boolean isProtectedInventoryItemAtSlot(ServerPlayer player, int inventorySlot) {
		ItemStack stack = player.getInventory().getItem(inventorySlot);
		return switch (inventorySlot) {
			case RESET_SLOT -> isResetItem(stack);
			case VISIBILITY_SLOT -> isVisibilityItem(stack);
			case CHECKPOINT_SLOT -> isCheckpointItem(stack);
			default -> false;
		};
	}

	private void resyncProtectedItems(ServerPlayer player) {
		player.containerMenu.setCarried(ItemStack.EMPTY);
		if (!isResetItem(player.getInventory().getItem(RESET_SLOT))) {
			giveResetItem(player);
		}

		RunState run = runs.get(player.getUUID());
		ParkourStorage.ParkourData parkour = run == null ? null : storage.parkours.get(run.parkourName);
		boolean playersHidden = playersHiddenViewers.contains(player.getUUID());
		if (!isVisibilityItem(player.getInventory().getItem(VISIBILITY_SLOT))) {
			giveVisibilityItem(player, playersHidden);
		}
		if (parkour != null && !parkour.checkpoints.isEmpty() && !isCheckpointItem(player.getInventory().getItem(CHECKPOINT_SLOT))) {
			giveCheckpointItem(player);
		}

		playerInventoryRefresh(player);
	}

	private void togglePlayerVisibility(ServerPlayer viewer) {
		UUID viewerId = viewer.getUUID();
		if (playersHiddenViewers.remove(viewerId)) {
			showAllHiddenPlayers(viewer);
			giveVisibilityItem(viewer, false);
		} else {
			playersHiddenViewers.add(viewerId);
			updateHiddenPlayers(viewer);
			giveVisibilityItem(viewer, true);
		}
		playerInventoryRefresh(viewer);
	}

	private void updateHiddenPlayers(ServerPlayer viewer) {
		Set<Integer> hiddenEntityIds = hiddenEntityIdsByViewer.computeIfAbsent(viewer.getUUID(), ignored -> new HashSet<>());

		for (ServerPlayer other : viewer.level().players()) {
			if (other.getUUID().equals(viewer.getUUID())) {
				continue;
			}

			hiddenEntityIds.add(other.getId());
			hidePlayer(viewer, other);
		}

		Iterator<Integer> iterator = hiddenEntityIds.iterator();
		while (iterator.hasNext()) {
			int entityId = iterator.next();
			ServerPlayer other = findPlayerByEntityId(viewer.level(), entityId);
			if (other == null) {
				iterator.remove();
			}
		}
	}

	private void showAllHiddenPlayers(ServerPlayer viewer) {
		playersHiddenViewers.remove(viewer.getUUID());
		Set<Integer> hiddenEntityIds = hiddenEntityIdsByViewer.remove(viewer.getUUID());
		if (hiddenEntityIds == null) {
			return;
		}

		for (int entityId : hiddenEntityIds) {
			ServerPlayer other = findPlayerByEntityId(viewer.level(), entityId);
			if (other != null) {
				showPlayer(viewer, other);
			}
		}
	}

	private ServerPlayer findPlayerByEntityId(ServerLevel level, int entityId) {
		for (ServerPlayer player : level.players()) {
			if (player.getId() == entityId) {
				return player;
			}
		}
		return null;
	}

	private void hidePlayer(ServerPlayer viewer, ServerPlayer other) {
		sendViewerInvisibility(viewer, other, true);
	}

	private void showPlayer(ServerPlayer viewer, ServerPlayer other) {
		sendViewerInvisibility(viewer, other, false);
	}

	private void playerInventoryRefresh(ServerPlayer player) {
		player.containerMenu.broadcastFullState();
		player.inventoryMenu.broadcastFullState();
	}

	private void sendViewerInvisibility(ServerPlayer viewer, ServerPlayer other, boolean invisible) {
		byte sharedFlags = other.getEntityData().get(SHARED_FLAGS_ACCESSOR);
		byte viewerFlags = invisible ? (byte) (sharedFlags | INVISIBLE_FLAG) : sharedFlags;
		viewer.connection.send(new ClientboundSetEntityDataPacket(
			other.getId(),
			java.util.List.of(SynchedEntityData.DataValue.create(SHARED_FLAGS_ACCESSOR, viewerFlags))));
	}

	private void disablePlayerCollision(ServerPlayer player) {
		ServerScoreboard scoreboard = player.level().getServer().getScoreboard();
		String scoreboardName = player.getScoreboardName();
		previousTeams.putIfAbsent(player.getUUID(), currentTeamName(scoreboard, scoreboardName));

		PlayerTeam noCollisionTeam = scoreboard.getPlayerTeam(NO_COLLISION_TEAM_NAME);
		if (noCollisionTeam == null) {
			noCollisionTeam = scoreboard.addPlayerTeam(NO_COLLISION_TEAM_NAME);
		}
		noCollisionTeam.setCollisionRule(Team.CollisionRule.NEVER);
		noCollisionTeam.setSeeFriendlyInvisibles(false);
		scoreboard.addPlayerToTeam(scoreboardName, noCollisionTeam);
	}

	private void restorePlayerCollision(ServerPlayer player) {
		ServerScoreboard scoreboard = player.level().getServer().getScoreboard();
		String scoreboardName = player.getScoreboardName();
		scoreboard.removePlayerFromTeam(scoreboardName);

		String previousTeamName = previousTeams.remove(player.getUUID());
		if (previousTeamName != null) {
			PlayerTeam previousTeam = scoreboard.getPlayerTeam(previousTeamName);
			if (previousTeam != null) {
				scoreboard.addPlayerToTeam(scoreboardName, previousTeam);
			}
		}
	}

	private void applyAdventureMode(ServerPlayer player) {
		previousGameModes.putIfAbsent(player.getUUID(), player.gameMode());
		if (player.gameMode() != GameType.ADVENTURE) {
			player.setGameMode(GameType.ADVENTURE);
		}
	}

	private void restoreGameMode(ServerPlayer player) {
		GameType previousGameMode = previousGameModes.remove(player.getUUID());
		if (previousGameMode != null && player.gameMode() != previousGameMode) {
			player.setGameMode(previousGameMode);
		}
	}

	private String currentTeamName(ServerScoreboard scoreboard, String scoreboardName) {
		PlayerTeam currentTeam = scoreboard.getPlayersTeam(scoreboardName);
		return currentTeam == null ? null : currentTeam.getName();
	}

	private static float yawDifference(float firstYaw, float secondYaw) {
		float difference = Math.abs(firstYaw - secondYaw) % 360.0F;
		return difference > 180.0F ? 360.0F - difference : difference;
	}

	private static float horizontalMovementYaw(double x, double z) {
		return (float) Math.toDegrees(Math.atan2(-x, z));
	}

	private static long elapsedMillis(long startedAtNanos, long finishedAtNanos) {
		return (finishedAtNanos - startedAtNanos) / 1_000_000L;
	}

	@SuppressWarnings("unchecked")
	private static EntityDataAccessor<Byte> sharedFlagsAccessor() {
		try {
			Field field = Entity.class.getDeclaredField("DATA_SHARED_FLAGS_ID");
			field.setAccessible(true);
			return (EntityDataAccessor<Byte>) field.get(null);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Cannot access Entity shared flags data accessor", exception);
		}
	}

	private static final class RunState {
		final String parkourName;
		final long startedAtNanos;
		final Set<Integer> reachedCheckpoints = new HashSet<>();
		double highestY;
		double extraFallBlocks;
		ParkourLocation lastCheckpoint;

		RunState(String parkourName, long startedAtNanos, double highestY, ParkourLocation lastCheckpoint) {
			this.parkourName = parkourName;
			this.startedAtNanos = startedAtNanos;
			this.highestY = highestY;
			this.lastCheckpoint = lastCheckpoint;
		}
	}

	private static final class PendingTeleport {
		final String parkourName;
		int ticksRemaining;

		PendingTeleport(String parkourName, int ticksRemaining) {
			this.parkourName = parkourName;
			this.ticksRemaining = ticksRemaining;
		}
	}

	private static final class PendingFinishFeedback {
		final Component message;
		int ticksRemaining;

		PendingFinishFeedback(Component message, int ticksRemaining) {
			this.message = message;
			this.ticksRemaining = ticksRemaining;
		}
	}

	private record PlayerSnapshot(String dimension, double x, double y, double z, long nanos) {
	}
}
