package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.WaveBoundaryAccumulator;
import com.krimzik.spectra.propagation.WaveMedium;
import com.krimzik.spectra.propagation.geometry.MaterialBoundaryScanner;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

public final class MinecraftCombinedWavePath implements FrequencyMaterialAttenuation.MaterialSegmentConsumer, MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> {
    private final MinecraftMaterialPropagation propagation;
    private final MinecraftWaveMediumResolver resolver;
    private final WaveBoundaryAccumulator boundaries = new WaveBoundaryAccumulator();

    private ResourceLocation previousMaterial;
    private double frequencyHz;
    private double pathLength;
    private double lossDb;
    private double attenuationFactor = Double.NaN;
    private boolean reflectionEnabled;
    private boolean attenuationEnabled;

    public MinecraftCombinedWavePath(MinecraftMaterialPropagation propagation, MinecraftWaveMediumResolver resolver) {
        this.propagation = Objects.requireNonNull(propagation);
        this.resolver = Objects.requireNonNull(resolver);

        if (resolver.hasStateSpecificRules()) {
            throw new IllegalArgumentException("Unified cached paths require named material properties");
        }
    }

    public double evaluate(Vec3 start, Vec3 end, List<? extends SubLevelAccess> candidates, double frequencyHz) {
        return evaluate(start, end, candidates, frequencyHz, FrequencyMaterialConfig.reflectionEnabled());
    }

    public double evaluate(Vec3 start, Vec3 end, List<? extends SubLevelAccess> candidates, double frequencyHz, boolean reflectionEnabled) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(candidates);

        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            throw new IllegalArgumentException("Invalid frequency");
        }

        double distance = start.distanceTo(end);

        if (!Double.isFinite(distance)) {
            throw new IllegalArgumentException("Invalid path distance");
        }

        this.frequencyHz = frequencyHz;
        this.pathLength = distance;
        this.reflectionEnabled = reflectionEnabled;
        this.attenuationEnabled = FrequencyMaterialAttenuation.enabled();

        previousMaterial = null;
        lossDb = 0.0;
        attenuationFactor = Double.NaN;
        boundaries.reset();

        if (candidates.isEmpty()) {
            double attenuation = propagation.factorWithWorldBoundaries(start, end, this);
            if (!Double.isFinite(attenuation)) {
                return Double.NaN;
            }

            attenuationFactor = attenuation;
        } else {
            boolean complete = propagation.forEachCombinedMaterialLossSegment(start, end, candidates, this);
            if (!complete) {
                return Double.NaN;
            }

            attenuationFactor = attenuationEnabled ? FrequencyMaterialRules.amplitudeFactor(lossDb) : 1.0;
        }

        return attenuationFactor * boundaries.directAmplitudeFactor();
    }

    @Override
    public void accept(ResourceLocation material, double startFraction, double endFraction, int enteredAxes) {
        throw new IllegalStateException("Missing exact material attenuation");
    }

    @Override
    public void acceptWithLoss(ResourceLocation material, double startFraction, double endFraction, int enteredAxes, double decibelsPerMeter) {
        if (previousMaterial != null && !previousMaterial.equals(material)) {
            onBoundary(previousMaterial, material, startFraction, enteredAxes);
        }

        if (attenuationEnabled) {
            lossDb = Math.fma(decibelsPerMeter, pathLength * (endFraction - startFraction), lossDb);
        }

        previousMaterial = material;
    }

    @Override
    public void onBoundary(ResourceLocation from, ResourceLocation to, double fraction, int crossedAxes) {
        WaveMedium first = resolver.resolveMaterial(from, frequencyHz);
        WaveMedium second = resolver.resolveMaterial(to, frequencyHz);

        boundaries.crossNormal(first, second, reflectionEnabled);
    }

    public double factorSnapshot(FrequencyMaterialWorldSnapshot snapshot, Vec3 start, Vec3 end, double frequencyHz) {
        WaveBoundaryAccumulator result = new WaveBoundaryAccumulator();

        class SnapshotConsumer implements FrequencyMaterialAttenuation.MaterialSegmentConsumer {
            private ResourceLocation previous;

            @Override
            public void accept(ResourceLocation material, double startFraction, double endFraction, int enteredAxes) {
                if (previous != null && !previous.equals(material)) {
                    result.crossNormal(resolver.resolveMaterial(previous, frequencyHz), resolver.resolveMaterial(material, frequencyHz), true);
                }

                previous = material;
            }
        }

        double attenuation = FrequencyMaterialAttenuation.factorSnapshotWithMaterials(snapshot, start, end, new SnapshotConsumer());
        return Double.isFinite(attenuation) ? attenuation * result.directAmplitudeFactor() : Double.NaN;
    }

    public double factorSnapshotCombined(MaterialPathOverlay overlay, double distance, double frequencyHz, boolean attenuationEnabled) {
        if (!Double.isFinite(distance) || distance < 0.0 || !Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            throw new IllegalArgumentException("Invalid snapshot wave path");
        }

        WaveBoundaryAccumulator transmission = new WaveBoundaryAccumulator();
        class Collector implements FrequencyMaterialAttenuation.MaterialSegmentConsumer {
            ResourceLocation previous;
            double loss;

            @Override
            public void accept(ResourceLocation material, double from, double to, int axes) {
                throw new IllegalStateException("Missing exact material attenuation");
            }

            @Override
            public void acceptWithLoss(ResourceLocation material, double from, double to, int axes, double decibelsPerMeter) {
                if (previous != null && !previous.equals(material)) {
                    transmission.crossNormal(resolver.resolveMaterial(previous, frequencyHz), resolver.resolveMaterial(material, frequencyHz), true);
                }

                if (attenuationEnabled) {
                    loss = Math.fma(decibelsPerMeter, distance * (to - from), loss);
                }

                previous = material;
            }
        }

        Collector collector = new Collector();
        if (!overlay.replayWithLoss(collector)) {
            return Double.NaN;
        }

        double attenuation = attenuationEnabled ? FrequencyMaterialRules.amplitudeFactor(collector.loss) : 1.0;
        return attenuation * transmission.directAmplitudeFactor();
    }

    public double attenuationFactor() {
        return attenuationFactor;
    }

    public double transmissionAmplitudeFactor() {
        return boundaries.directAmplitudeFactor();
    }

    public double transmittedEnergyFraction() {
        return boundaries.transmittedEnergyFraction();
    }

    public double reflectedEnergyFraction() {
        return boundaries.reflectedEnergyFraction();
    }

    public int boundaryCount() {
        return boundaries.boundaryCount();
    }
}