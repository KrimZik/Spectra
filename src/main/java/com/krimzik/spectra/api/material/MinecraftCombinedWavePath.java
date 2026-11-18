package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.WaveBoundaryAccumulator;
import com.krimzik.spectra.propagation.WaveMedium;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

public final class MinecraftCombinedWavePath implements FrequencyMaterialAttenuation.MaterialSegmentConsumer {
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

        boolean complete = propagation.forEachCombinedMaterialLossSegment(start, end, candidates, this);
        if (!complete) {
            return Double.NaN;
        }

        attenuationFactor = attenuationEnabled ? FrequencyMaterialRules.amplitudeFactor(lossDb) : 1.0;
        return attenuationFactor * boundaries.directAmplitudeFactor();
    }

    @Override
    public void accept(ResourceLocation material, double startFraction, double endFraction, int enteredAxes) {
        throw new IllegalStateException("Missing exact material attenuation");
    }

    @Override
    public void acceptWithLoss(ResourceLocation material, double startFraction, double endFraction, int enteredAxes, double decibelsPerMeter) {
        if (previousMaterial != null && !previousMaterial.equals(material)) {
            WaveMedium first = resolver.resolveMaterial(previousMaterial, frequencyHz);
            WaveMedium second = resolver.resolveMaterial(material, frequencyHz);

            boundaries.crossNormal(first, second, reflectionEnabled);
        }

        if (attenuationEnabled) {
            lossDb = Math.fma(decibelsPerMeter, pathLength * (endFraction - startFraction), lossDb);
        }

        previousMaterial = material;
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