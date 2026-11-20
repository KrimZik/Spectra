package com.krimzik.spectra.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaterialAttenuationMathTest {
    private static final double EPSILON = 1.0e-12;

    @Test
    void accumulatedLossMatchesExistingFormula() {
        double expected = Math.fma(3.0, 2.5, 1.25);
        assertEquals(expected, MaterialAttenuationMath.accumulateLoss(1.25, 3.0, 2.5), 0.0);
    }

    @Test
    void amplitudeFactorPreservesDecibelConversion() {
        assertEquals(1.0, MaterialAttenuationMath.amplitudeFactor(0.0), EPSILON);
        assertEquals(Math.sqrt(0.1), MaterialAttenuationMath.amplitudeFactor(10.0), EPSILON);
        assertEquals(0.1, MaterialAttenuationMath.amplitudeFactor(20.0), EPSILON);
    }

    @Test
    void relativeLossAllowsLessAttenuatingMaterials() {
        double factor = MaterialAttenuationMath.relativeAmplitudeFactor(-2.0);

        assertTrue(factor > 1.0);
        assertEquals(1.0, factor * MaterialAttenuationMath.relativeAmplitudeFactor(2.0), EPSILON);
        assertThrows(IllegalArgumentException.class, () -> MaterialAttenuationMath.amplitudeFactor(-2.0));
    }

    @Test
    void scaledFactorPreservesExistingFormula() {
        double localFactor = MaterialAttenuationMath.amplitudeFactor(6.0);

        assertEquals(Math.pow(localFactor, 2.0), MaterialAttenuationMath.scaledFactor(localFactor, 4.0, 2.0), 0.0);
        assertEquals(localFactor, MaterialAttenuationMath.scaledFactor(localFactor, 2.0, 2.0), EPSILON);
    }

    @Test
    void invalidAttenuationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MaterialAttenuationMath.amplitudeFactor(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> MaterialAttenuationMath.amplitudeFactor(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> MaterialAttenuationMath.relativeAmplitudeFactor(Double.NEGATIVE_INFINITY));
    }

    @Test
    void fastDistanceMatchesRobustHypot() {
        java.util.Random random = new java.util.Random(51L);

        for (int i = 0; i < 100_000; i++) {
            double dx = random.nextDouble(-256.0, 256.0);
            double dy = random.nextDouble(-256.0, 256.0);
            double dz = random.nextDouble(-256.0, 256.0);

            double expected = Math.hypot(Math.hypot(dx, dy), dz);
            double actual = MaterialAttenuationMath.distanceMeters(dx, dy, dz);

            assertEquals(expected, actual, 1.0e-12);
        }
    }

    @Test
    void fastDistancePreservesExtremeValues() {
        double[][] vectors = {
                {1.0e200, 1.0e200, 1.0e200},
                {1.0e-200, 1.0e-200, 1.0e-200},
                {Double.MIN_VALUE, 0.0, 0.0},
                {Double.MAX_VALUE, 0.0, 0.0},
                {0.0, 0.0, 0.0},
                {-256.0, 256.0, -256.0}
        };

        for (double[] vector : vectors) {
            double expected = Math.hypot(Math.hypot(vector[0], vector[1]), vector[2]);
            double actual = MaterialAttenuationMath.distanceMeters(vector[0], vector[1], vector[2]);

            assertEquals(expected, actual, Math.abs(expected) * 1.0e-15);
        }
    }
}
