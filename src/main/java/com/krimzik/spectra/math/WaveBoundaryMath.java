package com.krimzik.spectra.math;

public final class WaveBoundaryMath {
    private WaveBoundaryMath() {}

    public static double reflectance(double firstImpedance, double secondImpedance) {
        double ratio = impedanceRatio(firstImpedance, secondImpedance);
        double contrast = (1.0 - ratio) / (1.0 + ratio);
        return contrast * contrast;
    }

    public static double transmittance(double firstImpedance, double secondImpedance) {
        double ratio = impedanceRatio(firstImpedance, secondImpedance);
        double sum = 1.0 + ratio;
        return 4.0 * ratio / (sum * sum);
    }

    private static double impedanceRatio(double first, double second) {
        if (!Double.isFinite(first) || first <= 0.0 || !Double.isFinite(second) || second <= 0.0) {
            throw new IllegalArgumentException("Invalid wave impedance");
        }

        return Math.min(first, second) / Math.max(first, second);
    }
}