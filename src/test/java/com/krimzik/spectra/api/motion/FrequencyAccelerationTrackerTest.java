package com.krimzik.spectra.api.motion;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.krimzik.spectra.api.motion.FrequencyAccelerationTracker.Frame.*;
import static org.junit.jupiter.api.Assertions.*;

class FrequencyAccelerationTrackerTest {
    private static FrequencyMotionSnapshot motion(double vx, double vy, double vz) {
        return new FrequencyMotionSnapshot(20.0, 70.0, -10.0, vx, vy, vz);
    }

    @Test
    void firstSampleIsUnavailableAndStationaryMotionIsZero() {
        var tracker = new FrequencyAccelerationTracker();
        assertNull(tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true));
        var a = tracker.observe(motion(0, 0, 0), 101, Level.OVERWORLD, null, WORLD, true);
        assertNotNull(a);
        assertEquals(0.0, a.ax());
        assertEquals(0.0, a.ay());
        assertEquals(0.0, a.az());
    }

    @Test
    void acceleratingSourceUsesOneTickInSeconds() {
        var tracker = new FrequencyAccelerationTracker();
        tracker.observe(motion(1, -2, 0), 100, Level.OVERWORLD, null, WORLD, true);
        var a = tracker.observe(motion(1.5, -1.75, 0.25), 101, Level.OVERWORLD, null, WORLD, true);
        assertNotNull(a);
        assertEquals(10.0, a.ax(), 1.0e-12);
        assertEquals(5.0, a.ay(), 1.0e-12);
        assertEquals(5.0, a.az(), 1.0e-12);
    }

    @Test
    void equalSpeedWithChangedDirectionStillAccelerates() {
        var tracker = new FrequencyAccelerationTracker();
        UUID ship = UUID.randomUUID();
        tracker.observe(motion(10, 0, 0), 100, Level.OVERWORLD, ship, TRACKED, true);
        var a = tracker.observe(motion(0, 0, 10), 101, Level.OVERWORLD, ship, TRACKED, true);
        assertNotNull(a);
        assertEquals(-200.0, a.ax(), 1.0e-12);
        assertEquals(200.0, a.az(), 1.0e-12);
    }

    @Test
    void multipleCallsInOneTickReuseFirstAcceleration() {
        var tracker = new FrequencyAccelerationTracker();
        tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true);
        var expected = tracker.observe(motion(1, 0, 0), 101, Level.OVERWORLD, null, WORLD, true);
        assertSame(expected, tracker.observe(motion(500, 0, 0), 101, Level.OVERWORLD, null, WORLD, true));
        var next = tracker.observe(motion(2, 0, 0), 102, Level.OVERWORLD, null, WORLD, true);
        assertEquals(20.0, next.ax(), 1.0e-12);
    }

    @Test
    void missedTickInvalidatesThenReacquires() {
        var tracker = new FrequencyAccelerationTracker();
        tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true);
        assertNull(tracker.observe(motion(2, 0, 0), 102, Level.OVERWORLD, null, WORLD, true));
        assertEquals(20.0, tracker.observe(motion(3, 0, 0), 103, Level.OVERWORLD, null, WORLD, true).ax());
    }

    @Test
    void changingDimensionInvalidatesPreviousVelocity() {
        var tracker = new FrequencyAccelerationTracker();
        tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true);
        assertNull(tracker.observe(motion(100, 0, 0), 101, Level.NETHER, null, WORLD, true));
        assertEquals(20.0, tracker.observe(motion(101, 0, 0), 102, Level.NETHER, null, WORLD, true).ax());
    }

    @Test
    void changingShipOrFrameInvalidatesPreviousVelocity() {
        var tracker = new FrequencyAccelerationTracker();
        var shipA = UUID.randomUUID();
        var shipB = UUID.randomUUID();
        tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, shipA, TRACKED, true);
        assertNull(tracker.observe(motion(50, 0, 0), 101, Level.OVERWORLD, shipB, TRACKED, true));
        assertNull(tracker.observe(motion(51, 0, 0), 102, Level.OVERWORLD, shipB, PLOT, true));
        assertEquals(20.0, tracker.observe(motion(52, 0, 0), 103, Level.OVERWORLD, shipB, PLOT, true).ax());
    }

    @Test
    void declaredTeleportInvalidatesPreviousVelocity() {
        var tracker = new FrequencyAccelerationTracker();
        tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true);
        assertNull(tracker.observe(motion(90, 0, 0), 101, Level.OVERWORLD, null, WORLD, false));
        assertEquals(20.0, tracker.observe(motion(91, 0, 0), 102, Level.OVERWORLD, null, WORLD, true).ax());
    }

    @Test
    void resetReleasesHistory() {
        var tracker = new FrequencyAccelerationTracker();
        tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true);
        tracker.clear();
        assertNull(tracker.observe(motion(100, 0, 0), 101, Level.OVERWORLD, null, WORLD, true));
    }

    @Test
    void invalidSnapshotIsRejectedBeforeTracking() {
        var tracker = new FrequencyAccelerationTracker();
        tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true);
        assertThrows(IllegalArgumentException.class, () -> motion(Double.NaN, 0, 0));
        var acceleration = tracker.observe(motion(1, 0, 0), 101, Level.OVERWORLD, null, WORLD, true);
        assertNotNull(acceleration);
        assertEquals(20.0, acceleration.ax(), 1.0e-12);
    }

    @Test
    void thirdConsecutiveSampleKeepsFirstOrder() {
        var tracker = new FrequencyAccelerationTracker();
        assertNull(tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true));

        var first = tracker.observe(motion(1, 0, 0), 101, Level.OVERWORLD, null, WORLD, true);
        assertEquals(20.0, first.ax(), 1.0e-12);

        var second = tracker.observe(motion(4, 0, 0), 102, Level.OVERWORLD, null, WORLD, true);
        assertEquals(60.0, second.ax(), 1.0e-12);
    }

    @Test
    void missedTickResetsAccelerationHistory() {
        var tracker = new FrequencyAccelerationTracker();

        tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true);
        tracker.observe(motion(1, 0, 0), 101, Level.OVERWORLD, null, WORLD, true);
        tracker.observe(motion(4, 0, 0), 102, Level.OVERWORLD, null, WORLD, true);

        assertNull(tracker.observe(motion(9, 0, 0), 104, Level.OVERWORLD, null, WORLD, true));
        var first = tracker.observe(motion(10, 0, 0), 105, Level.OVERWORLD, null, WORLD, true);
        assertEquals(20.0, first.ax(), 1.0e-12);

        var second = tracker.observe(motion(13, 0, 0), 106, Level.OVERWORLD, null, WORLD, true);
        assertEquals(60.0, second.ax(), 1.0e-12);
    }

    @Test
    void trackedWorldTrackedTransitionsResetAcceleration() {
        var tracker = new FrequencyAccelerationTracker();
        UUID ship = UUID.randomUUID();

        assertNull(tracker.observe(
                motion(0, 0, 0), 100,
                Level.OVERWORLD, ship, TRACKED, true
        ));

        assertEquals(20.0, tracker.observe(
                motion(1, 0, 0), 101,
                Level.OVERWORLD, ship, TRACKED, true
        ).ax(), 1.0e-12);

        // Entity leaves the Sable ship.
        assertNull(tracker.observe(
                motion(20, 0, 0), 102,
                Level.OVERWORLD, null, WORLD, true
        ));

        // World motion establishes its own history.
        assertEquals(20.0, tracker.observe(
                motion(21, 0, 0), 103,
                Level.OVERWORLD, null, WORLD, true
        ).ax(), 1.0e-12);

        // Entity returns to the same ship.
        assertNull(tracker.observe(
                motion(40, 0, 0), 104,
                Level.OVERWORLD, ship, TRACKED, true
        ));

        assertEquals(20.0, tracker.observe(
                motion(41, 0, 0), 105,
                Level.OVERWORLD, ship, TRACKED, true
        ).ax(), 1.0e-12);
    }

    @Test
    void sameTickFrameTransitionInvalidatesCachedAcceleration() {
        var tracker = new FrequencyAccelerationTracker();
        UUID ship = UUID.randomUUID();

        tracker.observe(motion(0, 0, 0), 100, Level.OVERWORLD, null, WORLD, true);
        assertEquals(20.0, tracker.observe(motion(1, 0, 0), 101, Level.OVERWORLD, null, WORLD, true).ax(), 1.0e-12);

        assertNull(tracker.observe(motion(10, 0, 0), 101, Level.OVERWORLD, ship, TRACKED, true));
        assertNull(tracker.observe(motion(50, 0, 0), 101, Level.OVERWORLD, ship, TRACKED, true));

        assertEquals(20.0, tracker.observe(motion(11, 0, 0), 102, Level.OVERWORLD, ship, TRACKED, true).ax(), 1.0e-12);
    }
}
