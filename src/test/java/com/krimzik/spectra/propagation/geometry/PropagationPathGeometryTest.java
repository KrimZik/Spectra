package com.krimzik.spectra.propagation.geometry;

import com.krimzik.spectra.math.FrequencyPropagation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PropagationPathGeometryTest {
    private static final double EPSILON = 1.0e-12;

    @Test
    void directPathHasCorrectLength() {
        var path = new PropagationPathGeometry(0.0, 0.0, 0.0);

        assertEquals(13.0, path.append(3.0, 4.0, 12.0), EPSILON);
        assertEquals(13.0, path.distanceMeters(), EPSILON);
        assertEquals(1, path.segmentCount());
    }

    @Test
    void connectedSegmentsAccumulateCorrectly() {
        var path = new PropagationPathGeometry(0.0, 0.0, 0.0);

        double first = path.append(3.0, 4.0, 0.0);
        double second = path.append(3.0, 4.0, 12.0);

        double travelTime = 0.0;
        travelTime = FrequencyPropagation.appendPathTravelTime(travelTime, first, 343.0);
        travelTime = FrequencyPropagation.appendPathTravelTime(travelTime, second, 1500.0);

        assertEquals(17.0, path.distanceMeters(), EPSILON);
        assertEquals(2, path.segmentCount());
        assertEquals(5.0 / 343.0 + 12.0 / 1500.0, travelTime, EPSILON);

        double amplitude = FrequencyPropagation.amplitudeThroughPath(8.0, path.distanceMeters(), 0.5);

        assertEquals(4.0 / 17.0, amplitude, EPSILON);
    }

    @Test
    void resetAllowsReuse() {
        var path = new PropagationPathGeometry(0.0, 0.0, 0.0);

        path.append(3.0, 4.0, 0.0);
        path.reset(10.0, 20.0, 30.0);

        assertEquals(0.0, path.distanceMeters(), EPSILON);
        assertEquals(0, path.segmentCount());

        assertEquals(5.0, path.append(13.0, 24.0, 30.0), EPSILON);
        assertEquals(13.0, path.currentX(), EPSILON);
        assertEquals(24.0, path.currentY(), EPSILON);
        assertEquals(30.0, path.currentZ(), EPSILON);
    }

    @Test
    void zeroLengthSegmentDoesNotAddPathSegment() {
        var path = new PropagationPathGeometry(1.0, 2.0, 3.0);

        assertEquals(0.0, path.append(1.0, 2.0, 3.0), EPSILON);
        assertEquals(0.0, path.distanceMeters(), EPSILON);
        assertEquals(0, path.segmentCount());
    }

    @Test
    void invalidCoordinatesDoNotCorruptPath() {
        var path = new PropagationPathGeometry(0.0, 0.0, 0.0);

        path.append(3.0, 4.0, 0.0);

        assertThrows(IllegalArgumentException.class, () -> path.append(Double.NaN, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> path.reset(Double.POSITIVE_INFINITY, 0.0, 0.0));

        assertEquals(5.0, path.distanceMeters(), EPSILON);
        assertEquals(1, path.segmentCount());
        assertEquals(3.0, path.currentX(), EPSILON);
    }

    @Test
    void overflowingSegmentIsRejected() {
        var path = new PropagationPathGeometry(-Double.MAX_VALUE, 0.0, 0.0);

        assertThrows(IllegalArgumentException.class, () -> path.append(Double.MAX_VALUE, 0.0, 0.0));

        assertEquals(0.0, path.distanceMeters(), EPSILON);
        assertEquals(0, path.segmentCount());
    }
}