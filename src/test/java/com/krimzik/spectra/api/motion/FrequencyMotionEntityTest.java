package com.krimzik.spectra.api.motion;

import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FrequencyMotionEntityTest {
    private static final double EPSILON = 1.0e-9;

    private static Pose3d pose(double x, double y, double z) {
        Pose3d result = new Pose3d();
        result.position().set(x, y, z);
        return result;
    }

    @Test
    void ordinaryEntityVelocity() {
        Vec3 velocity = FrequencyMotion.sampleWorldVelocity(new Vec3(4.1, 0, 0), Vec3.ZERO, new Vec3(4.0, 0, 0), Vec3.ZERO, 1);

        assertEquals(2.0, velocity.x, EPSILON);
    }

    @Test
    void entityCaptureRejectsOffThreadAccess() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        Entity entity = mock(Entity.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> FrequencyMotion.captureEntity(level, entity));
    }

    @Test
    void entityCaptureRejectsAnotherLevel() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerLevel other = mock(ServerLevel.class);
        Entity entity = mock(Entity.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        when(entity.level()).thenReturn(other);

        assertThrows(IllegalArgumentException.class, () -> FrequencyMotion.captureEntity(level, entity));
    }

    @Test
    void worldVelocityUsesConsecutiveTickDisplacement() {
        Vec3 velocity = FrequencyMotion.sampleWorldVelocity(new Vec3(5.4, 3.2, 0.0), Vec3.ZERO, new Vec3(5.0, 3.0, 0.0), Vec3.ZERO, 1);

        assertEquals(8.0, velocity.x, EPSILON);
        assertEquals(4.0, velocity.y, EPSILON);
        assertEquals(0.0, velocity.z, EPSILON);
    }

    @Test
    void worldVelocityDoesNotUseUnreliableOldPositionFields() {
        Vec3 velocity = FrequencyMotion.sampleWorldVelocity(new Vec3(4.0, 10.0, 2.0), Vec3.ZERO, new Vec3(4.0, 9.5, 2.0), Vec3.ZERO, 1);
        assertEquals(10.0, velocity.y, EPSILON);
    }

    @Test
    void worldVelocityReusesSameTickVelocity() {
        Vec3 velocity = FrequencyMotion.sampleWorldVelocity(new Vec3(5.25, 0, 0), new Vec3(0.4, 0, 0), new Vec3(5.0, 0, 0), new Vec3(3.0, 0, 0), 0);
        assertEquals(3.0, velocity.x, EPSILON);
    }

    @Test
    void worldVelocityFallsBackAfterSamplingGapOrModeChange() {
        Vec3 fallback = new Vec3(0.2, 0.3, -0.1);

        Vec3 fromGap = FrequencyMotion.sampleWorldVelocity(new Vec3(5.0, 10.0, 4.0), fallback, new Vec3(1.0, 1.0, 1.0), Vec3.ZERO, 5);
        Vec3 fromTransition = FrequencyMotion.sampleWorldVelocity(new Vec3(5.0, 10.0, 4.0), fallback, null, null, Integer.MIN_VALUE);

        assertEquals(4.0, fromGap.x, EPSILON);
        assertEquals(6.0, fromTransition.y, EPSILON);
        assertEquals(-2.0, fromTransition.z, EPSILON);
    }

    @Test
    void shipExitUsesConsecutiveWorldPositions() {
        Vec3 previousShipWorld = new Vec3(100, 40, 20);
        Vec3 currentWorld = new Vec3(100.2, 39.9, 20.3);

        Vec3 previousShipVelocity = new Vec3(40, 0, 0);
        Vec3 fallbackMovement = Vec3.ZERO;

        Vec3 velocity = FrequencyMotion.sampleWorldVelocity(currentWorld, fallbackMovement, previousShipWorld, previousShipVelocity, 1);

        assertEquals(4.0, velocity.x, EPSILON);
        assertEquals(-2.0, velocity.y, EPSILON);
        assertEquals(6.0, velocity.z, EPSILON);
    }

    @Test
    void sameTickShipExitDoesNotReuseShipVelocity() {
        Vec3 velocity = FrequencyMotion.sampleWorldVelocity(new Vec3(100, 40, 20), new Vec3(0, -0.2, 0), new Vec3(99, 41, 20), null, 0);

        assertEquals(0.0, velocity.x, EPSILON);
        assertEquals(-4.0, velocity.y, EPSILON);
        assertEquals(0.0, velocity.z, EPSILON);
    }

    @Test
    void stationaryTrackedSampleIgnoresOldPositionArtifacts() {
        var currentPose = pose(0, 0, 0);
        currentPose.orientation().rotateX(0.4).rotateZ(-0.2);
        Vec3 local = new Vec3(4, 2, -7);
        Vec3 velocity = FrequencyMotion.sampleTrackedRelativeVelocity(currentPose, local, local, 1);

        assertEquals(0.0, velocity.length(), EPSILON);
    }

    @Test
    void trackedSampleUsesActualLocalMovement() {
        var currentPose = pose(0, 0, 0);
        currentPose.orientation().rotateX(0.4).rotateZ(-0.15);

        Vec3 previousLocal = new Vec3(1, 2, -3);
        Vec3 currentLocal = new Vec3(1.1, 2.2, -2.9);

        Vec3 expected = currentPose.transformNormal(currentLocal.subtract(previousLocal)).scale(20.0);
        Vec3 velocity = FrequencyMotion.sampleTrackedRelativeVelocity(currentPose, currentLocal, previousLocal, 1);

        assertEquals(expected.x, velocity.x, EPSILON);
        assertEquals(expected.y, velocity.y, EPSILON);
        assertEquals(expected.z, velocity.z, EPSILON);
    }

    @Test
    void trackedSampleHandlesMissingHistoryAndSamplingGaps() {
        var currentPose = pose(0, 0, 0);
        Vec3 missing = FrequencyMotion.sampleTrackedRelativeVelocity(currentPose, new Vec3(2, 0, 0), null, Integer.MIN_VALUE);
        Vec3 gap = FrequencyMotion.sampleTrackedRelativeVelocity(currentPose, new Vec3(2, 0, 0), new Vec3(1, 0, 0), 5);

        assertEquals(Vec3.ZERO, missing);
        assertEquals(4.0, gap.x, EPSILON);
        assertEquals(0.0, gap.y, EPSILON);
        assertEquals(0.0, gap.z, EPSILON);
    }

    @Test
    void trackedSamplingGapRespectsShipRotation() {
        var currentPose = pose(0, 0, 0);
        currentPose.orientation().rotateY(Math.PI / 2.0);

        Vec3 velocity = FrequencyMotion.sampleTrackedRelativeVelocity(currentPose, new Vec3(1, 0, 0), Vec3.ZERO, 4);

        assertEquals(0.0, velocity.x, EPSILON);
        assertEquals(0.0, velocity.y, EPSILON);
        assertEquals(-5.0, velocity.z, EPSILON);
    }

    @Test
    void invalidSablePhysicsBodyIsTheOnlySuppressedFailure() {
        assertTrue(FrequencyMotion.isRemovedSableBody(new RuntimeException("Body has been removed")));
        assertFalse(FrequencyMotion.isRemovedSableBody(new RuntimeException("Ship pose unavailable")));
        assertFalse(FrequencyMotion.isRemovedSableBody(new IllegalStateException("Entity belongs to another level")));
    }

    @Test
    void teleportEpochInvalidatesAndCanBeCleared() {
        Entity entity = mock(Entity.class);

        assertEquals(0L, FrequencyMotion.teleportEpoch(entity));

        FrequencyMotion.markTeleported(entity);
        assertEquals(1L, FrequencyMotion.teleportEpoch(entity));

        FrequencyMotion.markTeleported(entity);
        assertEquals(2L, FrequencyMotion.teleportEpoch(entity));

        FrequencyMotion.forgetEntity(entity);
        assertEquals(0L, FrequencyMotion.teleportEpoch(entity));
    }
}
