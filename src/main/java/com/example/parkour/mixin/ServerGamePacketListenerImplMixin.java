package com.example.parkour.mixin;

import com.example.parkour.ParkourMod;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Unique
	private double parkourmod$oldX;

	@Unique
	private double parkourmod$oldY;

	@Unique
	private double parkourmod$oldZ;

	@Inject(method = "handleMovePlayer", at = @At("HEAD"))
	private void parkourmod$capturePosition(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		parkourmod$oldX = player.getX();
		parkourmod$oldY = player.getY();
		parkourmod$oldZ = player.getZ();
	}

	@Inject(method = "handleMovePlayer", at = @At("RETURN"))
	private void parkourmod$afterAcceptedMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		ParkourMod.handlePlayerMovement(player, parkourmod$oldX, parkourmod$oldY, parkourmod$oldZ, System.nanoTime());
	}
}
