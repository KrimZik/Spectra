package com.krimzik.spectra.api.dimension;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class FrequencyDimensions {
    private FrequencyDimensions() {}

    public static boolean sameDimension(ServerLevel source, ServerLevel destination) {
        return source == destination;
    }

    public static Vec3 scalePosition(ServerLevel source, ServerLevel destination, Vec3 position) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(position);

        if (source == destination) return position;

        double scale = DimensionType.getTeleportationScale(source.dimensionType(), destination.dimensionType());
        return new Vec3(position.x * scale, position.y, position.z * scale);
    }
}
