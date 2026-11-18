package com.krimzik.spectra.propagation;

import com.krimzik.spectra.math.WaveBoundaryMath;

import java.util.Objects;

public final class WaveBoundaryAccumulator {
    private double transmittedEnergy = 1.0;
    private double reflectedEnergy;
    private int boundaryCount;

    public void reset() {
        transmittedEnergy = 1.0;
        reflectedEnergy = 0.0;
        boundaryCount = 0;
    }

    public void crossNormal(WaveMedium from, WaveMedium to, boolean reflectionEnabled) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);

        boundaryCount++;

        if (!reflectionEnabled) {
            return;
        }

        double incoming = transmittedEnergy;
        double first = from.impedance();
        double second = to.impedance();

        double reflected = WaveBoundaryMath.reflectance(first, second);
        double transmitted = WaveBoundaryMath.transmittance(first, second);

        reflectedEnergy = Math.fma(incoming, reflected, reflectedEnergy);
        transmittedEnergy = incoming * transmitted;
    }

    public double transmittedEnergyFraction() {
        return transmittedEnergy;
    }

    public double reflectedEnergyFraction() {
        return reflectedEnergy;
    }

    public double directAmplitudeFactor() {
        return Math.sqrt(transmittedEnergy);
    }

    public int boundaryCount() {
        return boundaryCount;
    }
}