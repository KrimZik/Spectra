package com.krimzik.spectra.propagation;

import com.krimzik.spectra.propagation.geometry.MaterialBoundaryScanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaveBoundaryAccumulatorTest {
    private static final double EPSILON = 1.0e-12;

    private static final WaveMedium AIR = new WaveMedium(300.0, 1.0, 0.0);
    private static final WaveMedium STONE = new WaveMedium(3000.0, 3.0, 3.0);

    @Test
    void singleBoundarySplitsEnergy() {
        var path = new WaveBoundaryAccumulator();

        path.crossNormal(AIR, STONE, true);

        assertEquals(0.75, path.transmittedEnergyFraction(), EPSILON);
        assertEquals(0.25, path.reflectedEnergyFraction(), EPSILON);
        assertEquals(Math.sqrt(0.75), path.directAmplitudeFactor(), EPSILON);
        assertEquals(1, path.boundaryCount());
    }

    @Test
    void multipleBoundariesConserveFirstPassEnergy() {
        var path = new WaveBoundaryAccumulator();

        path.crossNormal(AIR, STONE, true);
        path.crossNormal(STONE, AIR, true);

        assertEquals(0.5625, path.transmittedEnergyFraction(), EPSILON);
        assertEquals(0.4375, path.reflectedEnergyFraction(), EPSILON);
        assertEquals(0.75, path.directAmplitudeFactor(), EPSILON);
        assertEquals(1.0, path.transmittedEnergyFraction() + path.reflectedEnergyFraction(), EPSILON);
    }

    @Test
    void disabledReflectionAllowsFullTransmission() {
        var path = new WaveBoundaryAccumulator();

        path.crossNormal(AIR, STONE, false);
        path.crossNormal(STONE, AIR, false);

        assertEquals(1.0, path.transmittedEnergyFraction(), EPSILON);
        assertEquals(0.0, path.reflectedEnergyFraction(), EPSILON);
        assertEquals(1.0, path.directAmplitudeFactor(), EPSILON);
        assertEquals(2, path.boundaryCount());
    }

    @Test
    void equalImpedanceHasNoReflection() {
        var path = new WaveBoundaryAccumulator();
        var differentSpeed = new WaveMedium(1000.0, 1.0, 5.0);

        path.crossNormal(AIR, differentSpeed, true);

        assertEquals(1.0, path.transmittedEnergyFraction(), EPSILON);
        assertEquals(0.0, path.reflectedEnergyFraction(), EPSILON);
    }

    @Test
    void scannerFeedsExistingBoundaryPhysics() {
        var path = new WaveBoundaryAccumulator();

        int count = MaterialBoundaryScanner.scan(
                0.5, 0.5, 0.5,
                3.5, 0.5, 0.5,
                (x, y, z) -> x == 1 || x == 2 ? STONE : AIR,
                (from, to, fraction, axes) -> {
                    assertEquals(MaterialBoundaryScanner.X_AXIS, axes);
                    path.crossNormal(from, to, true);
                }
        );

        assertEquals(2, count);
        assertEquals(2, path.boundaryCount());
        assertEquals(0.5625, path.transmittedEnergyFraction(), EPSILON);
    }

    @Test
    void accumulatorCanBeReused() {
        var path = new WaveBoundaryAccumulator();

        path.crossNormal(AIR, STONE, true);
        path.reset();

        assertEquals(1.0, path.transmittedEnergyFraction(), EPSILON);
        assertEquals(0.0, path.reflectedEnergyFraction(), EPSILON);
        assertEquals(0, path.boundaryCount());
    }
}