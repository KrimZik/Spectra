package com.krimzik.spectra.math;

public final class MaterialAttenuationMath {
    private static final double DB_TO_AMPLITUDE = 0.11512925464970229;

    private MaterialAttenuationMath() {}

    public static double accumulateLoss(double lossDb, double dbPerMeter, double meters) {
        return Math.fma(dbPerMeter, meters, lossDb);
    }

    public static double amplitudeFactor(double lossDb) {
        if (!Double.isFinite(lossDb) || lossDb < 0.0) {
            throw new IllegalArgumentException("Invalid attenuation");
        }

        return relativeAmplitudeFactor(lossDb);
    }

    public static double relativeAmplitudeFactor(double lossDb) {
        if (!Double.isFinite(lossDb)) {
            throw new IllegalArgumentException("Invalid attenuation");
        }

        return Math.exp(-DB_TO_AMPLITUDE * lossDb);
    }

    public static double scaledFactor(double factor, double worldMeters, double localMeters) {
        return Math.pow(factor, worldMeters / localMeters);
    }
}
