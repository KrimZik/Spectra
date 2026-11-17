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
}
