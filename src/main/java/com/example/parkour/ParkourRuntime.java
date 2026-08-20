package com.example.parkour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.util.EnumSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ParkourRuntime {
	private static final String NO_COLLISION_TEAM_NAME = "parkour_nocoll";
	private static final int CHECKPOINT_SLOT = 7;
	private static final int RESET_SLOT = 8;
	private static final double AUTO_HIDE_PLAYER_RADIUS = 3.0D;
	private static final double FALL_ZONE_RADIUS = 1.8D;
	private static final double SPAWN_YAW_CHECK_RADIUS = 3.0D;
	private static final double PRESSURE_PLATE_EDGE_SAMPLE = 0.25D;
	private static final double PRESSURE_PLATE_MAX_CENTER_DISTANCE = 0.86D;
	private static final double PRESSURE_PLATE_TOUCH_HEIGHT = 0.25D;
	private static final long FALL_DAMAGE_GRACE_MILLIS = 1000L;
	private static final int FINISH_TELEPORT_DELAY_TICKS = 20;
	private static final Component RESET_ITEM_NAME = ParkourText.label("Reset", ParkourText.RED);
	private static final Component CHECKPOINT_ITEM_NAME = ParkourText.label("Checkpoint", ParkourText.ORANGE);

	private final ParkourStorage storage;
	private final ParkourScoreboard scoreboard;
	private final Map<UUID, RunState> runs = new HashMap<>();
	private final Map<UUID, BlockPos> lastStartPlates = new HashMap<>();
	private final Map<UUID, Long> fallDamageGraceUntil = new HashMap<>();
	private final Map<UUID, PendingTeleport> finishTeleports = new HashMap<>();
	private final Map<UUID, Set<Integer>> hiddenEntityIdsByViewer = new HashMap<>();
	private final Map<UUID, String> previousTeams = new HashMap<>();
	private final Map<UUID, PlayerSnapshot> playerSnapshots = new HashMap<>();
	private final Map<PlateKey, String> startPlateIndex = new HashMap<>();

	ParkourRuntime(ParkourStorage storage, ParkourScoreboard scoreboard) {
		this.storage = storage;
		this.scoreboard = scoreboard;
		rebuildStartPlateIndex();
	}

	void rebuildStartPlateIndex() {
		// Cache start plates by dimension + block position so idle players do not scan every parkour each tick.
		startPlateIndex.clear();
		storage.parkours.forEach((name, parkour) -> {
			if (parkour.start != null) {
				startPlateIndex.put(new PlateKey(parkour.start.dimension(), parkour.start.blockPos()), name);
			}
		});
	}

	void updateStartPlateIndex(String name) {
		startPlateIndex.entrySet().removeIf(entry -> entry.getValue().equals(name));
		ParkourStorage.ParkourData parkour = storage.parkours.get(name);
		if (parkour != null && parkour.start != null) {
			startPlateIndex.put(new PlateKey(parkour.start.dimension(), parkour.start.blockPos()), name);
		}
	}

	void tick(MinecraftServer server) {
		tickFinishTeleports(server);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			long currentNanos = System.nanoTime();

			RunState run = runs.get(player.getUUID());
			if (run == null) {
				continue;
			}

			ParkourStorage.ParkourData parkour = storage.parkours.get(run.parkourName);
			if (parkour == null || !parkour.isComplete()) {
				runs.remove(player.getUUID());
				continue;
			}

			player.sendOverlayMessage(ParkourText.timerActionBar(elapsedMillis(run.startedAtNanos, currentNanos)));
			updateAutoHiddenPlayers(player);
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
		PlayerSnapshot previousSnapshot = movementPreviousSnapshot(player, level, oldX, oldY, oldZ, currentNanos);
		BlockPos currentPlate = pressurePlateAtPosition(level, player, player.getX(), player.getY(), player.getZ());
		BlockPos previousPlate = pressurePlateAtPosition(level, player, oldX, oldY, oldZ);

		if (currentPlate == null) {
			lastStartPlates.remove(player.getUUID());
		} else {
			BlockPos lastStartPlate = lastStartPlates.get(player.getUUID());
			boolean steppedOntoStartPlate = !currentPlate.equals(lastStartPlate) && !currentPlate.equals(previousPlate);

			// Movement handling sees the accepted server position immediately, so start timing before the next tick.
			if (!runs.containsKey(player.getUUID()) && steppedOntoStartPlate) {
				String parkourName = startPlateIndex.get(PlateKey.of(level, currentPlate));
				ParkourStorage.ParkourData parkour = parkourName == null ? null : storage.parkours.get(parkourName);
				if (parkour != null && parkour.isComplete()) {
					long startNanos = interpolatedTriggerNanos(level, player, previousSnapshot, currentPlate, player.getX(), player.getY(), player.getZ(), currentNanos);
					startRun(player, parkourName, parkour, startNanos);
				}
			}
			lastStartPlates.put(player.getUUID(), currentPlate);
		}

		RunState run = runs.get(player.getUUID());
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
		if (!(entity instanceof ServerPlayer player) || !source.is(DamageTypes.FALL)) {
			return true;
		}
		// Active runs and short post-teleport windows should never apply fall damage.
		return !runs.containsKey(player.getUUID()) && System.currentTimeMillis() > fallDamageGraceUntil.getOrDefault(player.getUUID(), 0L);
	}

	InteractionResult useResetItem(Player player, Level world, InteractionHand hand) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		ItemStack usedItem = player.getItemInHand(hand);
		boolean resetItem = isResetItem(usedItem);
		boolean checkpointItem = isCheckpointItem(usedItem);
		if (!resetItem && !checkpointItem) {
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

		if (resetItem) {
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
		runs.put(player.getUUID(), new RunState(parkourName, startedAtNanos, player.getY(), parkour.spawn));
		disablePlayerCollision(player);
		if (!parkour.checkpoints.isEmpty()) {
			giveCheckpointItem(player);
		}
		giveResetItem(player);
		playSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8F, 1.35F);
	}

	private void finishRun(MinecraftServer server, ServerPlayer player, RunState run, ParkourStorage.ParkourData parkour, long finishedAtNanos) {
		runs.remove(player.getUUID());
		restorePlayerCollision(player);
		removeCheckpointItem(player);
		removeResetItem(player);
		showAllHiddenPlayers(player);
		addFallDamageGrace(player);
		finishTeleports.put(player.getUUID(), new PendingTeleport(run.parkourName, FINISH_TELEPORT_DELAY_TICKS));
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

		if (newGlobalRecord) {
			spawnWorldRecordFirework(server, parkour);
			player.sendOverlayMessage(ParkourText.finishActionBar(millis, displayedPersonalBestMillis, displayedWorldRecordMillis));
			server.getPlayerList().broadcastSystemMessage(ParkourText.recordBroadcast(player.getGameProfile().name(), run.parkourName, millis), false);
		} else {
			player.sendOverlayMessage(ParkourText.finishActionBar(millis, displayedPersonalBestMillis, displayedWorldRecordMillis));
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
		for (int i = 0; i < parkour.checkpoints.size(); i++) {
			ParkourLocation checkpoint = parkour.checkpoints.get(i);
			if (run.reachedCheckpoints.contains(i) || !isSameBlock(level, platePos, checkpoint)) {
				continue;
			}

			run.reachedCheckpoints.add(i);
			run.lastCheckpoint = checkpoint;
			run.highestY = player.getY();
			run.extraFallBlocks = 0.0D;
			playSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.6F, 1.8F);
			return;
		}
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
		removeCheckpointItem(player);
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

	private boolean isResetItem(ItemStack stack) {
		return stack.is(Items.BARRIER) && RESET_ITEM_NAME.equals(stack.getCustomName());
	}

	private boolean isCheckpointItem(ItemStack stack) {
		return stack.is(Items.IRON_DOOR) && CHECKPOINT_ITEM_NAME.equals(stack.getCustomName());
	}

	private void updateAutoHiddenPlayers(ServerPlayer viewer) {
		Set<Integer> hiddenEntityIds = hiddenEntityIdsByViewer.computeIfAbsent(viewer.getUUID(), ignored -> new HashSet<>());
		Set<Integer> shouldBeHidden = new HashSet<>();

		for (ServerPlayer other : viewer.level().players()) {
			if (other.getUUID().equals(viewer.getUUID())) {
				continue;
			}

			if (viewer.distanceToSqr(other) <= AUTO_HIDE_PLAYER_RADIUS * AUTO_HIDE_PLAYER_RADIUS) {
				shouldBeHidden.add(other.getId());
				if (hiddenEntityIds.add(other.getId())) {
					hidePlayer(viewer, other);
				}
			}
		}

		hiddenEntityIds.removeIf(entityId -> {
			if (shouldBeHidden.contains(entityId)) {
				return false;
			}
			ServerPlayer other = findPlayerByEntityId(viewer.level(), entityId);
			if (other != null) {
				showPlayer(viewer, other);
			}
			return true;
		});
	}

	private void showAllHiddenPlayers(ServerPlayer viewer) {
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
		viewer.connection.send(new ClientboundRemoveEntitiesPacket(other.getId()));
	}

	private void showPlayer(ServerPlayer viewer, ServerPlayer other) {
		viewer.connection.send(new ClientboundAddEntityPacket(other, 0, other.blockPosition()));
		viewer.connection.send(new ClientboundRotateHeadPacket(other, (byte) ((int) (other.getYHeadRot() * 256.0F / 360.0F))));
		viewer.connection.send(new ClientboundSetEntityDataPacket(other.getId(), other.getEntityData().getNonDefaultValues()));
	}

	private void disablePlayerCollision(ServerPlayer player) {
		ServerScoreboard scoreboard = player.level().getServer().getScoreboard();
		String scoreboardName = player.getScoreboardName();
		previousTeams.putIfAbsent(player.getUUID(), currentTeamName(scoreboard, scoreboardName));

		PlayerTeam noCollisionTeam = scoreboard.getPlayerTeam(NO_COLLISION_TEAM_NAME);
		if (noCollisionTeam == null) {
			noCollisionTeam = scoreboard.addPlayerTeam(NO_COLLISION_TEAM_NAME);
			noCollisionTeam.setCollisionRule(Team.CollisionRule.NEVER);
		}
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

	private record PlayerSnapshot(String dimension, double x, double y, double z, long nanos) {
	}
}
