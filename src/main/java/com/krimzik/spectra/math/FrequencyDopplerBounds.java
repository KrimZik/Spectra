package com.krimzik.spectra.math;

public final class FrequencyDopplerBounds {
    private FrequencyDopplerBounds() {}

    public record Range(double minHz, double maxHz) {
        public Range {
            if (!Double.isFinite(minHz) || minHz < 0.0 || Double.isNaN(maxHz) || maxHz < minHz) {
                throw new IllegalArgumentException("Invalid frequency range");
            }
        }

        public boolean contains(double frequencyHz) {
            return frequencyHz >= minHz && frequencyHz <= maxHz;
        }
    }

    public static Range candidateRange(double tunedFrequencyHz, double toleranceHz, double minPropagationSpeed, double maxEmitterSpeed, double maxReceiverSpeed) {
        if (!Double.isFinite(tunedFrequencyHz)
                || tunedFrequencyHz <= 0.0
                || !Double.isFinite(toleranceHz)
                || toleranceHz < 0.0
                || !Double.isFinite(minPropagationSpeed)
                || minPropagationSpeed <= 0.0
                || !Double.isFinite(maxEmitterSpeed)
                || maxEmitterSpeed < 0.0
                || maxEmitterSpeed >= minPropagationSpeed
                || !Double.isFinite(maxReceiverSpeed)
                || maxReceiverSpeed < 0.0
                || maxReceiverSpeed >= minPropagationSpeed) {
            throw new IllegalArgumentException("Invalid Doppler search parameters");
        }

        double lowerReceivedHz = Math.max(0.0, tunedFrequencyHz - toleranceHz);
        double upperReceivedHz = tunedFrequencyHz + toleranceHz;

        if (!Double.isFinite(upperReceivedHz)) {
            throw new IllegalArgumentException("Doppler search frequency overflow");
        }

        double minimumEmittedHz = lowerReceivedHz * (minPropagationSpeed - maxEmitterSpeed) / (minPropagationSpeed + maxReceiverSpeed);
        double maximumEmittedHz = upperReceivedHz * (minPropagationSpeed + maxEmitterSpeed) / (minPropagationSpeed - maxReceiverSpeed);

        if (maxEmitterSpeed > 0.0 || maxReceiverSpeed > 0.0) {
            if (!Double.isFinite(minimumEmittedHz) || !Double.isFinite(maximumEmittedHz)) {
                throw new IllegalArgumentException("Doppler candidate range overflow");
            }

            minimumEmittedHz = Math.max(0.0, Math.nextDown(minimumEmittedHz));
            maximumEmittedHz = Math.nextUp(maximumEmittedHz);
        }

        return new Range(minimumEmittedHz, maximumEmittedHz);
    }
}
