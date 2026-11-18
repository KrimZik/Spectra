package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.geometry.MaterialBoundaryScanner;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public final class MaterialBoundaryCollector implements FrequencyMaterialAttenuation.MaterialSegmentConsumer {
    private final MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> consumer;

    private ResourceLocation firstMaterial;
    private ResourceLocation previous;
    private int boundaryCount;

    public MaterialBoundaryCollector(MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> consumer) {
        this.consumer = Objects.requireNonNull(consumer);
    }

    public void reset() {
        firstMaterial = null;
        previous = null;
        boundaryCount = 0;
    }

    @Override
    public void accept(ResourceLocation material, double startFraction, double endFraction, int enteredAxes) {
        Objects.requireNonNull(material);

        if (firstMaterial == null) {
            firstMaterial = material;
        }

        if (previous != null && !previous.equals(material)) {
            consumer.onBoundary(previous, material, startFraction, enteredAxes);
            boundaryCount++;
        }

        previous = material;
    }

    public ResourceLocation firstMaterial() {
        return firstMaterial;
    }

    public int boundaryCount() {
        return boundaryCount;
    }
}