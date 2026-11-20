package com.krimzik.spectra.propagation;

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

        if (!Double.isFinite(first) || first <= 0.0 || !Double.isFinite(second) || second <= 0.0) {
            throw new IllegalArgumentException("Invalid wave impedance");
        }

        double ratio = Math.min(first, second) / Math.max(first, second);
        double sum = 1.0 + ratio;
        double contrast = (1.0 - ratio) / sum;

        double reflected = contrast * contrast;
        double transmitted = 4.0 * ratio / (sum * sum);

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