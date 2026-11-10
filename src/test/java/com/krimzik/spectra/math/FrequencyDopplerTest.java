package com.krimzik.spectra.math;

import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FrequencyDopplerTest {
    private static final double EPSILON = 1.0e-9;
    private static final double SPEED = 343.0;

    private static FrequencyMotionSnapshot point(double x, double y, double z, double vx, double vy, double vz) {
        return new FrequencyMotionSnapshot(x, y, z, vx, vy, vz);
    }

    @Test
    void stationaryObjects() {
        double result = FrequencyDoppler.receivedFrequencyHz(440.0, SPEED, point(0, 0, 0, 0, 0, 0), point(100, 0, 0, 0, 0, 0));
        assertEquals(440.0, result, EPSILON);
    }

    @Test
    void approachingEmitter() {
        double result = FrequencyDoppler.receivedFrequencyHz(440.0, SPEED, point(0, 0, 0, 20, 0, 0), point(100, 0, 0, 0, 0, 0));
        assertEquals(440.0 * 343.0 / 323.0, result, EPSILON);
    }

    @Test
    void recedingEmitter() {
        double result = FrequencyDoppler.receivedFrequencyHz(440.0, SPEED, point(0, 0, 0, -20, 0, 0), point(100, 0, 0, 0, 0, 0));
        assertEquals(440.0 * 343.0 / 363.0, result, EPSILON);
    }

    @Test
    void recedingReceiver() {
        double result = FrequencyDoppler.receivedFrequencyHz(440.0, SPEED, point(0, 0, 0, 0, 0, 0), point(100, 0, 0, 20, 0, 0));
        assertEquals(440.0 * 323.0 / 343.0, result, EPSILON);
    }

    @Test
    void matchingVelocities() {
        double result = FrequencyDoppler.receivedFrequencyHz(440.0, SPEED, point(0, 0, 0, 20, 0, 0), point(100, 0, 0, 20, 0, 0));
        assertEquals(440.0, result, EPSILON);
    }

    @Test
    void perpendicularMovement() {
        double result = FrequencyDoppler.receivedFrequencyHz(440.0, SPEED, point(0, 0, 0, 0, 20, 0), point(100, 0, 0, 0, -10, 0));
        assertEquals(440.0, result, EPSILON);
    }

    @Test
    void coincidentPositions() {
        double result = FrequencyDoppler.receivedFrequencyHz(440.0, SPEED, point(0, 0, 0, 20, 0, 0), point(0, 0, 0, 0, 0, 0));
        assertEquals(440.0, result, EPSILON);
    }

    @Test
    void excessiveRadialVelocityRejected() {
        assertThrows(IllegalArgumentException.class, () -> FrequencyDoppler.receivedFrequencyHz(440.0, SPEED, point(0, 0, 0, 343, 0, 0), point(100, 0, 0, 0, 0, 0)));
    }

    @Test
    void invalidSnapshotRejected() {
        assertThrows(IllegalArgumentException.class, () -> point(Double.NaN, 0, 0, 0, 0, 0));
    }
}
