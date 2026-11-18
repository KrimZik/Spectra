package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.WaveBoundaryAccumulator;
import com.krimzik.spectra.propagation.WaveMedium;
import com.krimzik.spectra.propagation.geometry.MaterialBoundaryScanner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class MinecraftWorldWavePath implements MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> {
    private final MinecraftMaterialPropagation propagation;
    private final MinecraftWaveMediumResolver resolver;
    private final WaveBoundaryAccumulator boundaries = new WaveBoundaryAccumulator();

    private double frequencyHz;
    private boolean reflectionEnabled;
    private double attenuationFactor = Double.NaN;

    public MinecraftWorldWavePath(MinecraftMaterialPropagation propagation, MinecraftWaveMediumResolver resolver) {
        this.propagation = Objects.requireNonNull(propagation);
        this.resolver = Objects.requireNonNull(resolver);

        if (resolver.hasStateSpecificRules()) {
            throw new IllegalArgumentException("Cached wave paths require named material properties, not block-specific wave-medium rules");
        }
    }

    public double evaluate(Vec3 start, Vec3 end, double frequencyHz) {
        return evaluate(start, end, frequencyHz, FrequencyMaterialConfig.reflectionEnabled());
    }

    public double evaluate(Vec3 start, Vec3 end, double frequencyHz, boolean reflectionEnabled) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            throw new IllegalArgumentException("Invalid frequency");
        }

        this.frequencyHz = frequencyHz;
        this.reflectionEnabled = reflectionEnabled;
        this.attenuationFactor = Double.NaN;
        boundaries.reset();

        double attenuation = propagation.factorWithWorldBoundaries(start, end, this);

        if (!Double.isFinite(attenuation)) {
            return Double.NaN;
        }

        attenuationFactor = attenuation;
        return attenuation * boundaries.directAmplitudeFactor();
    }

    @Override
    public void onBoundary(ResourceLocation from, ResourceLocation to, double fraction, int crossedAxes) {
        WaveMedium first = resolver.resolveMaterial(from, frequencyHz);
        WaveMedium second = resolver.resolveMaterial(to, frequencyHz);

        boundaries.crossNormal(first, second, reflectionEnabled);
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