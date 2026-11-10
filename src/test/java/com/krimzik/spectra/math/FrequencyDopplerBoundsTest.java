package com.krimzik.spectra.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FrequencyDopplerBoundsTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void stationaryBoundsMatchReceiverTuning() {
        var range = FrequencyDopplerBounds.candidateRange(440.0, 5.0, 343.0, 0.0, 0.0);
        assertEquals(435.0, range.minHz(), EPSILON);
        assertEquals(445.0, range.maxHz(), EPSILON);
    }

    @Test
    void movingEmitterExpandsCandidateRange() {
        var range = FrequencyDopplerBounds.candidateRange(440.0, 5.0, 343.0, 40.0, 0.0);
        assertTrue(range.contains(480.0));
        assertTrue(range.contains(400.0));
        assertFalse(range.contains(600.0));
    }

    @Test
    void movingReceiverExpandsCandidateRange() {
        var range = FrequencyDopplerBounds.candidateRange(440.0, 0.0, 343.0, 0.0, 30.0);
        assertTrue(range.minHz() < 440.0);
        assertTrue(range.maxHz() > 440.0);
    }

    @Test
    void normalStationarySignalStillMatches() {
        var range = FrequencyDopplerBounds.candidateRange(440.0, 0.0, 343.0, 50.0, 20.0);
        assertTrue(range.contains(440.0));
    }

    @Test
    void actualDopplerShiftMatchesCandidateRange() {
        double sourceSpeed = -343.0 * (480.0 / 440.0 - 1.0);

        var emitter = new com.krimzik.spectra.api.motion.FrequencyMotionSnapshot(0, 0, 0, sourceSpeed, 0, 0);
        var receiver = new com.krimzik.spectra.api.motion.FrequencyMotionSnapshot(100, 0, 0, 0, 0, 0);

        double receivedHz = FrequencyDoppler.receivedFrequencyHz(480.0, 343.0, emitter, receiver);
        var range = FrequencyDopplerBounds.candidateRange(440.0, 0.0, 343.0, 40.0, 0.0);

        assertEquals(440.0, receivedHz, EPSILON);
        assertTrue(range.contains(480.0));
    }

    @Test
    void excessiveSpeedBoundsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FrequencyDopplerBounds.candidateRange(440.0, 5.0, 343.0, 343.0, 0.0));
    }
}
