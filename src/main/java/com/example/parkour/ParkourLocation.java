package com.example.parkour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

record ParkourLocation(String dimension, double x, double y, double z, float yaw, float pitch) {
	static ParkourLocation from(Level level, double x, double y, double z, float yaw, float pitch) {
		return new ParkourLocation(level.dimension().identifier().toString(), x, y, z, yaw, pitch);
	}

	BlockPos blockPos() {
		return BlockPos.containing(x, y, z);
	}

	ResourceKey<Level> dimensionKey() {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
	}
}
