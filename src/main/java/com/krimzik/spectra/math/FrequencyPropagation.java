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

    public static double appendPathDistance(double accumulatedMeters, double segmentMeters) {
        if (!Double.isFinite(accumulatedMeters) || accumulatedMeters < 0.0 || !Double.isFinite(segmentMeters) || segmentMeters < 0.0) {
            throw new IllegalArgumentException("Invalid path distance");
        }

        return accumulatedMeters + segmentMeters;
    }

    public static double appendPathTravelTime(double accumulatedSeconds, double segmentMeters, double speedMetersPerSecond) {
        if (!Double.isFinite(accumulatedSeconds) || accumulatedSeconds < 0.0) {
            throw new IllegalArgumentException("Invalid accumulated travel time");
        }

        return accumulatedSeconds + travelTimeSeconds(segmentMeters, speedMetersPerSecond);
    }

    public static double amplitudeThroughPath(double sourceAmplitude, double pathMeters, double transmissionFactor) {
        if (!Double.isFinite(transmissionFactor) || transmissionFactor < 0.0) {
            throw new IllegalArgumentException("Invalid path transmission");
        }

        return amplitudeAtDistance(sourceAmplitude, pathMeters) * transmissionFactor;
    }

    public static double sampleReceivedAtTick(double frequencyHz, double receivedAmplitude, double initialPhaseRadians, long tick, double travelTimeSeconds) {
        return FrequencyMath.sampleSineAtTick(frequencyHz, receivedAmplitude, initialPhaseRadians, tick, travelTimeSeconds);
    }

    public static double sampleReceived(double frequencyHz, double receivedAmplitude, double initialPhaseRadians, double timeSeconds, double travelTimeSeconds) {
        return receivedAmplitude * Math.sin(FrequencyMath.phaseAtTime(frequencyHz, timeSeconds, initialPhaseRadians, travelTimeSeconds));
    }
}