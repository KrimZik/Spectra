package com.krimzik.spectra.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaveBoundaryMathTest {
    private static final double EPSILON = 1.0e-12;

    @Test
    void matchingImpedancesTransmitAllEnergy() {
        assertEquals(0.0, WaveBoundaryMath.reflectance(1500.0, 1500.0), EPSILON);
        assertEquals(1.0, WaveBoundaryMath.transmittance(1500.0, 1500.0), EPSILON);
    }

    @Test
    void mismatchedImpedancesSplitEnergy() {
        assertEquals(0.25, WaveBoundaryMath.reflectance(1.0, 3.0), EPSILON);
        assertEquals(0.75, WaveBoundaryMath.transmittance(1.0, 3.0), EPSILON);
    }

    @Test
    void idealBoundaryConservesEnergy() {
        double[] impedances = {0.01, 1.0, 25.0, 1000.0, 1_000_000.0};

        for (double first : impedances) {
            for (double second : impedances) {
                double reflected = WaveBoundaryMath.reflectance(first, second);
                double transmitted = WaveBoundaryMath.transmittance(first, second);

                assertEquals(1.0, reflected + transmitted, EPSILON);
                assertTrue(reflected >= 0.0 && reflected <= 1.0);
                assertTrue(transmitted >= 0.0 && transmitted <= 1.0);
            }
        }
    }

    @Test
    void reversingMediaPreservesEnergyFractions() {
        assertEquals(WaveBoundaryMath.reflectance(1.0, 3.0), WaveBoundaryMath.reflectance(3.0, 1.0), EPSILON);
        assertEquals(WaveBoundaryMath.transmittance(1.0, 3.0), WaveBoundaryMath.transmittance(3.0, 1.0), EPSILON);
    }

    @Test
    void extremeImpedanceRatiosStayFinite() {
        double reflected = WaveBoundaryMath.reflectance(1.0e-300, 1.0e300);
        double transmitted = WaveBoundaryMath.transmittance(1.0e-300, 1.0e300);

        assertEquals(1.0, reflected, EPSILON);
        assertEquals(0.0, transmitted, EPSILON);
        assertTrue(Double.isFinite(reflected));
        assertTrue(Double.isFinite(transmitted));
    }

    @Test
    void invalidImpedancesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WaveBoundaryMath.reflectance(0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> WaveBoundaryMath.transmittance(-1.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> WaveBoundaryMath.reflectance(Double.NaN, 1.0));
        assertThrows(IllegalArgumentException.class, () -> WaveBoundaryMath.transmittance(Double.POSITIVE_INFINITY, 1.0));
    }
}
