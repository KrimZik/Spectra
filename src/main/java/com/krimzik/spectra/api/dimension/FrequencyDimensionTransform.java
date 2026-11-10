package com.krimzik.spectra.api.dimension;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

@FunctionalInterface
public interface FrequencyDimensionTransform {
    Vec3 toDestination(ServerLevel source, ServerLevel destination, Vec3 sourceWorldPosition);
}
