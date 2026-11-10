package com.krimzik.spectra.math;

public final class FrequencyPropagation {
    private static final double REFERENCE_DISTANCE = 1.0;

    private FrequencyPropagation() {}

    public static double amplitudeAtDistance(double amplitude, double distanceMeters) {
        if (!Double.isFinite(amplitude) || amplitude < 0.0)
            throw new IllegalArgumentException("Invalid amplitude");

        if (!Double.isFinite(distanceMeters) || distanceMeters < 0.0)
            throw new IllegalArgumentException("Invalid distance");

        return amplitude * REFERENCE_DISTANCE / Math.max(REFERENCE_DISTANCE, distanceMeters);
    }

    public static double travelTimeSeconds(double distanceMeters, double speedMetersPerSecond) {
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0.0)
            throw new IllegalArgumentException("Invalid distance");

        if (!Double.isFinite(speedMetersPerSecond) || speedMetersPerSecond <= 0.0)
            throw new IllegalArgumentException("Invalid propagation speed");

        return distanceMeters / speedMetersPerSecond;
    }

    public static double sampleReceivedAtTick(double frequencyHz, double receivedAmplitude, double initialPhaseRadians, long tick, double travelTimeSeconds) {
        return FrequencyMath.sampleSineAtTick(frequencyHz, receivedAmplitude, initialPhaseRadians, tick, travelTimeSeconds);
    }

    public static double sampleReceived(double frequencyHz, double receivedAmplitude, double initialPhaseRadians, double timeSeconds, double travelTimeSeconds) {
        return FrequencyMath.sampleSine(frequencyHz, receivedAmplitude, initialPhaseRadians, timeSeconds - travelTimeSeconds);
    }
}
