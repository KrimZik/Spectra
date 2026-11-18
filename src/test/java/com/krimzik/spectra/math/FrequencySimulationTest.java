package com.krimzik.spectra.math;

import com.krimzik.spectra.api.FrequencySimulationTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FrequencySimulationTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void tickTimeConversion() {
        assertEquals(5.0, FrequencySimulationTime.secondsFromTicks(100), EPSILON);
        assertEquals(0.05, FrequencySimulationTime.secondsFromTicks(1), EPSILON);
    }

    @Test
    void frequencyConversion() {
        assertEquals(2.0, FrequencyPowerMath.rpmToHz(120), EPSILON);
        assertEquals(2000.0, FrequencyPowerMath.fePerSecond(100), EPSILON);
    }

    @Test
    void distanceAttenuation() {
        assertEquals(1.0, FrequencyPropagation.amplitudeAtDistance(1.0, 1.0), EPSILON);
        assertEquals(0.1, FrequencyPropagation.amplitudeAtDistance(1.0, 10.0), EPSILON);
    }

    @Test
    void constructiveInterference() {
        double a = FrequencyPropagation.sampleReceived(440.0, 1.0, Math.PI / 2.0, 0.0, 0.0);
        double b = FrequencyPropagation.sampleReceived(440.0, 1.0, Math.PI / 2.0, 0.0, 0.0);
        assertEquals(2.0, a + b, EPSILON);
    }

    @Test
    void destructiveInterference() {
        double a = FrequencyPropagation.sampleReceived(440.0, 1.0, Math.PI / 2.0, 0.0, 0.0);
        double b = FrequencyPropagation.sampleReceived(440.0, 1.0, -Math.PI / 2.0, 0.0, 0.0);
        assertEquals(0.0, a + b, EPSILON);
    }

    @Test
    void propagationPhaseDelay() {
        double delay = FrequencyPropagation.travelTimeSeconds(0.25, 343.0);
        double received = FrequencyPropagation.sampleReceived(343.0, 1.0, 0.0, 0.0, delay);
        assertEquals(-1.0, received, EPSILON);
    }

    @Test
    void resonancePeak() {
        assertEquals(5.0, FrequencyResonance.gain(440.0, 440.0, 0.1), EPSILON);
        assertEquals(Math.PI / 2.0, FrequencyResonance.phaseLagRadians(440.0, 440.0, 0.1), EPSILON);
    }

    @Test
    void invalidResonanceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FrequencyResonance.gain(440.0, 440.0, 0.0));
    }

    @Test
    void highFrequencyPhaseRemainsPreciseAfterBillionsOfTicks() {
        long tick = 4_000_000_001L;
        double hz = 915_000_007.25;
        double expected = 0.3625 * FrequencyMath.TWO_PI;

        assertEquals(expected, FrequencyMath.phaseAtTick(hz, tick, 0.0), 1.0e-12);
    }

    @Test
    void tickPhasePreservesHighFrequencyPropagationDelay() {
        long tick = 4_000_000_001L;
        double hz = 915_000_007.25;
        double expected = 0.55 * FrequencyMath.TWO_PI;

        assertEquals(expected, FrequencyMath.phaseAtTick(hz, tick, 0.0, 0.25), 1.0e-12);
        assertEquals(2.0 * Math.sin(expected), FrequencyPropagation.sampleReceivedAtTick(hz, 2.0, 0.0, tick, 0.25), 1.0e-12);
    }

    @Test
    void tickPhaseSupportsFullLongTickCounts() {
        long tick = Long.MAX_VALUE;
        double hz = 915_000_007.25;

        long remainder = ((tick % 80L) * 29L) % 80L;
        double expected = (remainder / 80.0) * FrequencyMath.TWO_PI;

        assertEquals(expected, FrequencyMath.phaseAtTick(hz, tick, 0.0), 1.0e-12);
    }

    @Test
    void tickPhaseMatchesOrdinarySamplingAtSmallTimes() {
        double hz = 3.5;
        long tick = 137L;
        double initialPhase = 0.4;

        double expected = FrequencyMath.phase(hz, tick / FrequencyMath.TICKS_PER_SECOND, initialPhase);
        double actual = FrequencyMath.phaseAtTick(hz, tick, initialPhase);

        assertEquals(expected, actual, 1.0e-12);
    }

    @Test
    void highFrequencyResonancePreservesTickPhase() {
        double hz = 915_000_007.25;
        long tick = 4_000_000_001L;
        double expected = 2.0 * Math.sin(0.55 * FrequencyMath.TWO_PI - Math.PI / 2.0);
        double actual = FrequencyResonance.sampleReceivedAtTick(hz, 2.0, 0.0, tick, 0.25, hz, 0.5);

        assertEquals(expected, actual, 1.0e-12);
    }

    @Test
    void continuousPhasePreservesInitialPhaseAfterLongRuntime() {
        double phase = FrequencyMath.phase(1_000_000_000.0, 10_000_000.0, Math.PI / 2.0);
        assertEquals(Math.PI / 2.0, phase, 1.0e-12);
    }

    @Test
    void continuousReceptionPreservesSubNanosecondDelay() {
        double frequency = 1_000_000_000.0;
        double delay = 0.25 / frequency;
        double sample = FrequencyPropagation.sampleReceived(frequency, 1.0, 0.0, 10_000_000.0, delay);

        assertEquals(-1.0, sample, 1.0e-12);
    }

    @Test
    void segmentedPathAccumulatesDistanceAndTravelTime() {
        double distance = 0.0;
        double travelTime = 0.0;

        distance = FrequencyPropagation.appendPathDistance(distance, 3.0);
        travelTime = FrequencyPropagation.appendPathTravelTime(travelTime, 3.0, 343.0);

        distance = FrequencyPropagation.appendPathDistance(distance, 7.0);
        travelTime = FrequencyPropagation.appendPathTravelTime(travelTime, 7.0, 1500.0);

        assertEquals(10.0, distance, 1.0e-12);
        assertEquals(3.0 / 343.0 + 7.0 / 1500.0, travelTime, 1.0e-12);
    }

    @Test
    void pathTransmissionAppliesSpreadingOnce() {
        double transmission = MaterialAttenuationMath.amplitudeFactor(5.0);

        double expected = FrequencyPropagation.amplitudeAtDistance(8.0, 10.0) * transmission;
        double actual = FrequencyPropagation.amplitudeThroughPath(8.0, 10.0, transmission);

        assertEquals(expected, actual, 1.0e-12);
    }

    @Test
    void directPathRemainsEquivalentToExistingPropagation() {
        double amplitude = 4.0;
        double distance = 25.0;

        assertEquals(FrequencyPropagation.amplitudeAtDistance(amplitude, distance), FrequencyPropagation.amplitudeThroughPath(amplitude, distance, 1.0), 1.0e-12);
        assertEquals(FrequencyPropagation.travelTimeSeconds(distance, 343.0), FrequencyPropagation.appendPathTravelTime(0.0, distance, 343.0), 1.0e-12);
    }
}