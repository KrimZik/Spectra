package com.krimzik.spectra.propagation.geometry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaterialBoundaryScannerTest {
    @Test
    void detectsMaterialTransitionsWithoutDuplicates() {
        List<String> transitions = new ArrayList<>();
        List<Double> fractions = new ArrayList<>();

        int count = MaterialBoundaryScanner.scan(
                0.5, 0.5, 0.5,
                4.5, 0.5, 0.5,
                (x, y, z) -> x == 1 || x == 2 ? "stone" : "air",
                (from, to, fraction, axes) -> {
                    transitions.add(from + ":" + to);
                    fractions.add(fraction);
                    assertEquals(MaterialBoundaryScanner.X_AXIS, axes);
                }
        );

        assertEquals(2, count);
        assertEquals(List.of("air:stone", "stone:air"), transitions);
        assertEquals(0.125, fractions.get(0), 1.0e-12);
        assertEquals(0.625, fractions.get(1), 1.0e-12);
    }

    @Test
    void reversedPathDetectsCorrectTransitions() {
        List<String> transitions = new ArrayList<>();

        int count = MaterialBoundaryScanner.scan(4.5, 0.5, 0.5, 0.5, 0.5, 0.5, (x, y, z) -> x == 1 || x == 2 ? "stone" : "air", (from, to, fraction, axes) -> transitions.add(from + ":" + to));

        assertEquals(2, count);
        assertEquals(List.of("air:stone", "stone:air"), transitions);
    }

    @Test
    void diagonalCornerCrossingIsReportedOnce() {
        List<Integer> axes = new ArrayList<>();

        int count = MaterialBoundaryScanner.scan(0.5, 0.5, 0.5, 2.5, 2.5, 0.5, (x, y, z) -> x == 1 && y == 1 ? "stone" : "air", (from, to, fraction, crossed) -> axes.add(crossed));

        assertEquals(2, count);
        assertEquals(List.of(MaterialBoundaryScanner.X_AXIS | MaterialBoundaryScanner.Y_AXIS, MaterialBoundaryScanner.X_AXIS | MaterialBoundaryScanner.Y_AXIS), axes);
    }

    @Test
    void missingMaterialAbortsTheScan() {
        int count = MaterialBoundaryScanner.scan(0.5, 0.5, 0.5, 4.5, 0.5, 0.5, (x, y, z) -> x >= 2 ? null : "air", (from, to, fraction, axes) -> fail("Unexpected boundary"));
        assertEquals(-1, count);
    }

    @Test
    void stationaryPathProducesNoBoundaries() {
        int count = MaterialBoundaryScanner.scan(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, (x, y, z) -> "stone", (from, to, fraction, axes) -> fail("Unexpected boundary"));
        assertEquals(0, count);
    }
}