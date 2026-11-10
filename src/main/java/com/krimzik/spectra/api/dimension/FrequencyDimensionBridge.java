package com.krimzik.spectra.api.dimension;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

public record FrequencyDimensionBridge(ResourceKey<Level> sourceDimension, ResourceKey<Level> destinationDimension, FrequencyDimensionTransform transform, double transmissionFactor) {
    public FrequencyDimensionBridge {
        Objects.requireNonNull(sourceDimension);
        Objects.requireNonNull(destinationDimension);
        Objects.requireNonNull(transform);

        if (sourceDimension.equals(destinationDimension)) {
            throw new IllegalArgumentException("A dimension bridge must connect different dimensions");
        }

        if (!Double.isFinite(transmissionFactor) || transmissionFactor < 0.0 || transmissionFactor > 1.0) {
            throw new IllegalArgumentException("Transmission factor must be between 0 and 1");
        }
    }
}
