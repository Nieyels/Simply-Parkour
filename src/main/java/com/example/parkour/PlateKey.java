package com.example.parkour;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

record PlateKey(String dimension, BlockPos pos) {
	static PlateKey of(ServerLevel level, BlockPos pos) {
		return new PlateKey(level.dimension().identifier().toString(), pos.immutable());
	}
}
