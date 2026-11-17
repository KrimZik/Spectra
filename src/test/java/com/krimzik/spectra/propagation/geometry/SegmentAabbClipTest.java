package com.krimzik.spectra.propagation.geometry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SegmentAabbClipTest {
    private static final double EPSILON = 1.0e-12;

    private static boolean clip(double x1, double y1, double z1, double x2, double y2, double z2, SegmentAabbClip.Interval result) {
        return SegmentAabbClip.clip(
                x1, y1, z1,
                x2, y2, z2,
                2.0, 0.0, 0.0,
                4.0, 2.0, 2.0,
                result
        );
    }

    @Test
    void straightPathClipsToBounds() {
        var result = new SegmentAabbClip.Interval();

        assertTrue(clip(0, 1, 1, 10, 1, 1, result));
        assertEquals(0.2, result.enter(), EPSILON);
        assertEquals(0.4, result.exit(), EPSILON);
    }

    @Test
    void reversePathPreservesIntersectionLength() {
        var result = new SegmentAabbClip.Interval();

        assertTrue(clip(0, 1, 1, 10, 1, 1, result));
        double forward = result.length();

        assertTrue(clip(10, 1, 1, 0, 1, 1, result));
        assertEquals(forward, result.length(), EPSILON);
    }

    @Test
    void missedBoundsReturnFalse() {
        var result = new SegmentAabbClip.Interval();

        assertFalse(clip(0, 5, 1, 10, 5, 1, result));
    }

    @Test
    void touchingCornerHasNoThickness() {
        var result = new SegmentAabbClip.Interval();

        assertFalse(clip(0, 0, 1, 4, 4, 1, result));
    }

    @Test
    void startingInsideClipsCorrectly() {
        var result = new SegmentAabbClip.Interval();

        assertTrue(clip(3, 1, 1, 5, 1, 1, result));
        assertEquals(0.0, result.enter(), EPSILON);
        assertEquals(0.5, result.exit(), EPSILON);
    }

    @Test
    void resultContainerCanBeReused() {
        var result = new SegmentAabbClip.Interval();

        assertTrue(clip(0, 1, 1, 10, 1, 1, result));
        assertEquals(0.2, result.enter(), EPSILON);

        assertTrue(clip(3, 1, 1, 5, 1, 1, result));
        assertEquals(0.0, result.enter(), EPSILON);
        assertEquals(0.5, result.exit(), EPSILON);
    }
}
