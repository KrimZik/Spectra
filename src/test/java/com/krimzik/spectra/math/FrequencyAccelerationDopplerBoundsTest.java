package com.krimzik.spectra.math;

import org.junit.jupiter.api.Test;

import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FrequencyAccelerationDopplerBoundsTest {
    private static final double F = 915_000_000.0;

    @Test
    void zeroAccelerationMatchesExistingCandidateRange() {
        var existing = FrequencyDopplerBounds.candidateRange(F, 1000.0, 300.0, 30.0, 12.0);
        var accelerated = FrequencyAccelerationDopplerBounds.candidateRange(F, 1000.0, 300.0, 100.0, 30.0, 0.0, 12.0);
        assertEquals(existing.minHz(), accelerated.minHz());
        assertEquals(existing.maxHz(), accelerated.maxHz());
    }

    @Test
    void positiveAccelerationWidensBothFrequencyBounds() {
        var stationary = FrequencyAccelerationDopplerBounds.candidateRange(F, 100.0, 300.0, 100.0, 25.0, 0.0, 10.0);
        var accelerated = FrequencyAccelerationDopplerBounds.candidateRange(F, 100.0, 300.0, 100.0, 25.0, 25.0, 10.0);
        assertTrue(accelerated.minHz() < stationary.minHz());
        assertTrue(accelerated.maxHz() > stationary.maxHz());
    }

    @Test
    void accelerationEnvelopeBoundsRadialDopplerExtremes() {
        double c = 300.0, range = 100.0, vCurrent = 15.0, a = 20.0, vReceiver = 10.0;
        double t = FrequencyAccelerationDopplerBounds.maxTravelTimeSeconds(c, range, vCurrent, a);
        double vEmission = vCurrent + a * t;
        var candidates = FrequencyAccelerationDopplerBounds.candidateRange(F, 0.0, c, range, vCurrent, a, vReceiver);

        double lowestTransmitted = F * (c - vEmission) / (c + vReceiver);
        double highestTransmitted = F * (c + vEmission) / (c - vReceiver);
        assertTrue(candidates.minHz() <= lowestTransmitted, "The lower frequency index bound must not exclude an approaching source");
        assertTrue(candidates.maxHz() >= highestTransmitted, "The upper frequency index bound must not exclude a receding source");
    }

    @Test
    void indexedBoundsContainRetardedSolverResultsAcrossRandomizedMotion() {
        var random = new Random(0x28D0BEEFL);
        for (int i = 0; i < 500; i++) {
            double sourceVx = (random.nextDouble() * 2.0 - 1.0) * 15.0;
            double sourceAx = (random.nextDouble() * 2.0 - 1.0) * 20.0;
            double receiverVx = (random.nextDouble() * 2.0 - 1.0) * 10.0;
            var source = new FrequencyMotionSnapshot(0, 0, 0, sourceVx, 0, 0);
            var receiver = new FrequencyMotionSnapshot(100, 0, 0, receiverVx, 0, 0);
            double received = FrequencyRetardedDoppler.calculate(F, 300.0, source, receiver, sourceAx, 0, 0).receivedFrequencyHz();
            var candidates = FrequencyAccelerationDopplerBounds.candidateRange(received, 0.0, 300.0, 100.0, 15.0, 20.0, 10.0);
            assertTrue(candidates.contains(F), "Retarded Doppler candidate excluded at sample " + i);
        }
    }

    @Test
    void travelTimeBoundIncludesConstantVelocity() {
        assertEquals(100.0 / 280.0, FrequencyAccelerationDopplerBounds.maxTravelTimeSeconds(300.0, 100.0, 20.0, 0.0), 1.0e-12);
    }

    @Test
    void accelerationIncreasesBoundedTravelTime() {
        double constant = FrequencyAccelerationDopplerBounds.maxTravelTimeSeconds(300, 100, 20, 0);
        double accelerated = FrequencyAccelerationDopplerBounds.maxTravelTimeSeconds(300, 100, 20, 10);
        assertTrue(accelerated > constant);
        assertTrue(300.0 * accelerated >= 100.0 + 20.0 * accelerated + 0.5 * 10.0 * accelerated * accelerated - 1.0e-10);
    }

    @Test
    void zeroRangeHasZeroDelayBound() {
        assertEquals(0.0, FrequencyAccelerationDopplerBounds.maxTravelTimeSeconds(300, 0, 20, 10));
    }

    @Test
    void impossibleSubsonicEnvelopeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FrequencyAccelerationDopplerBounds.candidateRange(F, 1.0, 100, 100, 0, 1000, 0));
    }

    @Test
    void invalidBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FrequencyAccelerationDopplerBounds.candidateRange(F, 1, 300, 100, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> FrequencyAccelerationDopplerBounds.candidateRange(F, 1, 300, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FrequencyAccelerationDopplerBounds.candidateRange(F, 1, 300, 100, 300, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FrequencyAccelerationDopplerBounds.candidateRange(F, 1, 300, 100, 0, 0, 300));
        assertThrows(IllegalArgumentException.class, () -> FrequencyAccelerationDopplerBounds.candidateRange(F, 1, Double.NaN, 100, 0, 0, 0));
    }
}
