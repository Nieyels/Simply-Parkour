package com.example.parkour;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;

public final class ParkourMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("parkourmod");

	private static ParkourStorage storage = new ParkourStorage();
	private static ParkourScoreboard scoreboard = new ParkourScoreboard(storage);
	private static ParkourRuntime runtime = new ParkourRuntime(storage, scoreboard);

	@Override
	public void onInitialize() {
		// Runtime objects are recreated after world data is loaded so commands and events use persisted parkour data.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			storage = ParkourStorage.load(server);
			scoreboard = new ParkourScoreboard(storage);
			runtime = new ParkourRuntime(storage, scoreboard);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> storage.save(server));
		ServerTickEvents.END_SERVER_TICK.register(server -> runtime.tick(server));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> runtime.allowDamage(entity, source, amount));
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> runtime.attackEntity(player, world, hand, entity, hitResult));
		UseItemCallback.EVENT.register((player, world, hand) -> runtime.useResetItem(player, world, hand));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> runtime.cleanupPlayer(handler.player));
		// Suppliers keep command handlers pointing at the current runtime after SERVER_STARTED replaces these instances.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> new ParkourCommands(ParkourMod::storage, ParkourMod::runtime, ParkourMod::scoreboard).register(dispatcher));
	}

	static ParkourStorage storage() {
		return storage;
	}

	static ParkourRuntime runtime() {
		return runtime;
	}

	static ParkourScoreboard scoreboard() {
		return scoreboard;
	}

	public static void handlePlayerMovement(ServerPlayer player, double oldX, double oldY, double oldZ, long nanos) {
		runtime.handleAcceptedMovement(player, oldX, oldY, oldZ, nanos);
	}

	public static boolean shouldBlockContainerClick(ServerPlayer player, ServerboundContainerClickPacket packet) {
		return runtime.shouldBlockContainerClick(player, packet);
	}

	public static boolean shouldBlockPlayerAction(ServerPlayer player, ServerboundPlayerActionPacket packet) {
		return runtime.shouldBlockPlayerAction(player, packet);
	}
}
