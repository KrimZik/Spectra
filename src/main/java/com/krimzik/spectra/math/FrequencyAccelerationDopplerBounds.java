package com.krimzik.spectra.math;

public final class FrequencyAccelerationDopplerBounds {
    private FrequencyAccelerationDopplerBounds() {}

    public static FrequencyDopplerBounds.Range candidateRange(double tunedFrequencyHz, double toleranceHz, double minPropagationSpeed, double rangeMeters, double maxCurrentEmitterSpeed, double maxEmitterAcceleration, double maxReceiverSpeed) {
        validateMotionBounds(minPropagationSpeed, rangeMeters, maxCurrentEmitterSpeed, maxEmitterAcceleration, maxReceiverSpeed);

        if (maxEmitterAcceleration == 0.0) {
            return FrequencyDopplerBounds.candidateRange(tunedFrequencyHz, toleranceHz, minPropagationSpeed, maxCurrentEmitterSpeed, maxReceiverSpeed);
        }

        double maxTravelTime = maxTravelTimeSeconds(minPropagationSpeed, rangeMeters, maxCurrentEmitterSpeed, maxEmitterAcceleration);
        double maxEmissionSpeed = Math.nextUp(Math.fma(maxEmitterAcceleration, maxTravelTime, maxCurrentEmitterSpeed));

        if (!Double.isFinite(maxEmissionSpeed) || maxEmissionSpeed >= minPropagationSpeed) {
            throw new IllegalArgumentException("Cannot bound emission-time speed below propagation speed");
        }

        return FrequencyDopplerBounds.candidateRange(tunedFrequencyHz, toleranceHz, minPropagationSpeed, maxEmissionSpeed, maxReceiverSpeed);
    }


    static double maxTravelTimeSeconds(double minPropagationSpeed, double rangeMeters, double maxCurrentEmitterSpeed, double maxEmitterAcceleration) {
        validateMotionBounds(minPropagationSpeed, rangeMeters, maxCurrentEmitterSpeed, maxEmitterAcceleration, 0.0);

        if (rangeMeters == 0.0) return 0.0;

        double speedMargin = minPropagationSpeed - maxCurrentEmitterSpeed;
        if (maxEmitterAcceleration == 0.0) {
            double t = Math.nextUp(rangeMeters / speedMargin);
            if (!Double.isFinite(t)) throw new IllegalArgumentException("Unbounded travel time");
            return t;
        }

        double ratio = 2.0 * (maxEmitterAcceleration / speedMargin) * (rangeMeters / speedMargin);
        if (!Double.isFinite(ratio) || ratio >= 1.0) {
            throw new IllegalArgumentException("No provably subsonic retarded-time horizon for declared bounds");
        }

        double t = Math.nextUp((2.0 * (rangeMeters / speedMargin)) / (1.0 + Math.sqrt(1.0 - ratio)));
        if (!Double.isFinite(t)) {
            throw new IllegalArgumentException("Unbounded travel time");
        }
        return t;
    }

    private static void validateMotionBounds(double minPropagationSpeed, double rangeMeters, double maxCurrentEmitterSpeed, double maxEmitterAcceleration, double maxReceiverSpeed) {
        if (!Double.isFinite(minPropagationSpeed) || minPropagationSpeed <= 0.0
                || !Double.isFinite(rangeMeters) || rangeMeters < 0.0
                || !Double.isFinite(maxCurrentEmitterSpeed) || maxCurrentEmitterSpeed < 0.0
                || !Double.isFinite(maxEmitterAcceleration) || maxEmitterAcceleration < 0.0
                || !Double.isFinite(maxReceiverSpeed) || maxReceiverSpeed < 0.0
                || maxCurrentEmitterSpeed >= minPropagationSpeed
                || maxReceiverSpeed >= minPropagationSpeed) {
            throw new IllegalArgumentException("Invalid retarded Doppler motion envelope");
        }
    }
}
