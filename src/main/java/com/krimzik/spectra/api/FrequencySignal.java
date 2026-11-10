package com.krimzik.spectra.api;

import com.krimzik.spectra.math.FrequencyMath;

public record FrequencySignal(double frequencyHz, double amplitude, double phaseRadians, double propagationSpeed) {
    public FrequencySignal {
        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0)
            throw new IllegalArgumentException("Invalid frequency");

        if (!Double.isFinite(amplitude) || amplitude < 0)
            throw new IllegalArgumentException("Invalid amplitude");

        if (!Double.isFinite(phaseRadians))
            throw new IllegalArgumentException("Invalid phase");

        if (!Double.isFinite(propagationSpeed) || propagationSpeed <= 0)
            throw new IllegalArgumentException("Invalid propagation speed");
    }

    public double sample(double timeSeconds) {
        return FrequencyMath.sampleSine(frequencyHz, amplitude, phaseRadians, timeSeconds);
    }

    public double sampleAtTick(long tick) {
        return FrequencyMath.sampleSineAtTick(frequencyHz, amplitude, phaseRadians, tick, 0.0);
    }

    public double wavelength() {
        return FrequencyMath.wavelength(frequencyHz, propagationSpeed);
    }
}
