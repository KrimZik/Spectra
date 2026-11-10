package com.krimzik.spectra.math;

public final class FrequencyResonance {
    private FrequencyResonance() {}

    public static double gain(double frequencyHz, double naturalFrequencyHz, double dampingRatio) {
        validate(frequencyHz, naturalFrequencyHz, dampingRatio);

        double ratio = frequencyHz / naturalFrequencyHz;
        double detuning = 1.0 - ratio * ratio;
        double damping = 2.0 * dampingRatio * ratio;

        return 1.0 / Math.hypot(detuning, damping);
    }

    public static double phaseLagRadians(double frequencyHz, double naturalFrequencyHz, double dampingRatio) {
        validate(frequencyHz, naturalFrequencyHz, dampingRatio);
        double ratio = frequencyHz / naturalFrequencyHz;
        return Math.atan2(2.0 * dampingRatio * ratio, 1.0 - ratio * ratio);
    }

    public static double sampleReceivedAtTick(double frequencyHz, double amplitude, double initialPhaseRadians, long tick, double travelTimeSeconds, double naturalFrequencyHz, double dampingRatio) {
        validate(frequencyHz, naturalFrequencyHz, dampingRatio);

        double ratio = frequencyHz / naturalFrequencyHz;
        double detuning = 1.0 - ratio * ratio;
        double damping = 2.0 * dampingRatio * ratio;

        double magnitude = Math.hypot(detuning, damping);
        double phaseLag = Math.atan2(damping, detuning);

        return FrequencyPropagation.sampleReceivedAtTick(frequencyHz, amplitude / magnitude, initialPhaseRadians - phaseLag, tick, travelTimeSeconds);
    }

    public static double sampleReceived(double frequencyHz, double amplitude, double initialPhaseRadians, double timeSeconds, double travelTimeSeconds, double naturalFrequencyHz, double dampingRatio) {
        validate(frequencyHz, naturalFrequencyHz, dampingRatio);

        double ratio = frequencyHz / naturalFrequencyHz;
        double detuning = 1.0 - ratio * ratio;
        double damping = 2.0 * dampingRatio * ratio;

        double magnitude = Math.hypot(detuning, damping);
        double phaseLag = Math.atan2(damping, detuning);

        return FrequencyPropagation.sampleReceived(frequencyHz, amplitude / magnitude, initialPhaseRadians - phaseLag, timeSeconds, travelTimeSeconds);
    }

    private static void validate(double frequencyHz, double naturalFrequencyHz, double dampingRatio) {
        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0)
            throw new IllegalArgumentException("Invalid frequency");

        if (!Double.isFinite(naturalFrequencyHz) || naturalFrequencyHz <= 0.0)
            throw new IllegalArgumentException("Invalid natural frequency");

        if (!Double.isFinite(dampingRatio) || dampingRatio <= 0.0)
            throw new IllegalArgumentException("Invalid damping ratio");
    }
}
