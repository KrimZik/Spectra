package com.krimzik.spectra.math;

public final class FrequencyPowerMath {

    private static final double SECONDS_PER_MINUTE = 60.0;
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double TWO_PI = 2.0 * Math.PI;

    private FrequencyPowerMath() {}

    public static double rpmToHz(double rpm) {
        requireFinite(rpm);
        return Math.abs(rpm) / SECONDS_PER_MINUTE;
    }

    public static double hzToRpm(double hz) {
        requireNonNegative(hz);
        return hz * SECONDS_PER_MINUTE;
    }

    public static double angularVelocity(double rpm) {
        requireFinite(rpm);
        return rpm * TWO_PI / SECONDS_PER_MINUTE;
    }

    public static double createStress(double baseStressPerRpm, double rpm) {
        requireNonNegative(baseStressPerRpm);
        requireFinite(rpm);

        return baseStressPerRpm * Math.abs(rpm);
    }

    public static double fePerSecond(double fePerTick) {
        requireNonNegative(fePerTick);
        return fePerTick * TICKS_PER_SECOND;
    }

    public static double fePerTick(double fePerSecond) {
        requireNonNegative(fePerSecond);
        return fePerSecond / TICKS_PER_SECOND;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Value must be finite");
    }

    private static void requireNonNegative(double value) {
        if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Value must be finite and non-negative");
    }
}
