package com.krimzik.spectra.propagation;

public record WaveMedium(double speedMetersPerSecond, double impedance, double lossDbPerMeter) {
    public WaveMedium {
        if (!Double.isFinite(speedMetersPerSecond) || speedMetersPerSecond <= 0.0 || !Double.isFinite(impedance) || impedance <= 0.0 || !Double.isFinite(lossDbPerMeter) || lossDbPerMeter < 0.0) {
            throw new IllegalArgumentException("Invalid wave medium");
        }
    }
}