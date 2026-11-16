package com.krimzik.spectra.api.material;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FrequencySableMaterialAttenuationTest {
    private static final double EPSILON = 1.0e-12;

    @Test
    void straightPathIsClippedToShipBounds() {
        BoundingBox3d bounds = new BoundingBox3d(2, 0, 0, 4, 2, 2);
        double[] interval = FrequencySableMaterialAttenuation.intersection(new Vec3(0, 1, 1), new Vec3(10, 1, 1), bounds);

        assertNotNull(interval);
        assertEquals(0.2, interval[0], EPSILON);
        assertEquals(0.4, interval[1], EPSILON);
    }

    @Test
    void reversePathHasSameIntersectionLength() {
        BoundingBox3d bounds = new BoundingBox3d(2, 0, 0, 4, 2, 2);

        double[] forward = FrequencySableMaterialAttenuation.intersection(new Vec3(0, 1, 1), new Vec3(10, 1, 1), bounds);
        double[] reverse = FrequencySableMaterialAttenuation.intersection(new Vec3(10, 1, 1), new Vec3(0, 1, 1), bounds);

        assertNotNull(forward);
        assertNotNull(reverse);

        assertEquals(forward[1] - forward[0], reverse[1] - reverse[0], EPSILON);
    }

    @Test
    void nearbyShipOutsideRayIsIgnored() {
        BoundingBox3d bounds = new BoundingBox3d(2, 3, 0, 4, 5, 2);
        assertNull(FrequencySableMaterialAttenuation.intersection(new Vec3(0, 1, 1), new Vec3(10, 1, 1), bounds));
    }

    @Test
    void diagonalIntersectionIsClippedCorrectly() {
        BoundingBox3d bounds = new BoundingBox3d(2, 2, 0, 4, 4, 2);
        double[] interval = FrequencySableMaterialAttenuation.intersection(new Vec3(0, 0, 1), new Vec3(6, 6, 1), bounds);

        assertNotNull(interval);
        assertEquals(1.0 / 3.0, interval[0], EPSILON);
        assertEquals(2.0 / 3.0, interval[1], EPSILON);
    }

    @Test
    void cornerTouchHasZeroMaterialThickness() {
        BoundingBox3d bounds = new BoundingBox3d(2, 0, 0, 4, 2, 2);
        assertNull(FrequencySableMaterialAttenuation.intersection(new Vec3(0, 0, 1), new Vec3(4, 4, 1), bounds));
    }

    @Test
    void pathStartingInsideShipIsClippedCorrectly() {
        BoundingBox3d bounds = new BoundingBox3d(2, 0, 0, 4, 2, 2);
        double[] interval = FrequencySableMaterialAttenuation.intersection(new Vec3(3, 1, 1), new Vec3(5, 1, 1), bounds);

        assertNotNull(interval);
        assertEquals(0.0, interval[0], EPSILON);
        assertEquals(0.5, interval[1], EPSILON);
    }

    private static ServerLevel materialWorld() {
        ServerLevel level = mock(ServerLevel.class);

        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);
        when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);

        when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);

            return pos.getX() == 1000 || pos.getX() == 1001 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        return level;
    }

    private static SubLevelAccess ship(Pose3d pose, BoundingBox3d bounds) {
        SubLevelAccess sublevel = mock(SubLevelAccess.class);
        when(sublevel.logicalPose()).thenReturn(pose);
        when(sublevel.boundingBox()).thenReturn(bounds);

        return sublevel;
    }

    private static void assertShipLoss(ServerLevel level, SubLevelAccess sublevel, Vec3 start, Vec3 end, double decibels) {
        double expected = FrequencyMaterialRules.amplitudeFactor(decibels);

        assertEquals(expected, FrequencySableMaterialAttenuation.factorForSublevels(level, start, end, List.of(sublevel)), 1.0e-9);
        assertEquals(expected, FrequencySableMaterialAttenuation.factorForSublevels(level, end, start, List.of(sublevel)), 1.0e-9);
    }

    @Test
    void translatedShipSamplesPlotBlocks() {
        ServerLevel level = materialWorld();

        Pose3d pose = new Pose3d();
        pose.position().set(-990.0, 0.0, 0.0);

        SubLevelAccess sublevel = ship(pose, new BoundingBox3d(10, 64, 0, 12, 65, 1));

        FrequencyMaterialAttenuation.setEnabled(true);

        try {
            assertShipLoss(level, sublevel, new Vec3(9.5, 64.5, 0.5), new Vec3(12.5, 64.5, 0.5), 6.0);
        } finally {
            FrequencyMaterialAttenuation.setEnabled(false);
        }
    }

    @Test
    void rotatedShipSamplesPlotBlocks() {
        ServerLevel level = materialWorld();

        Pose3d pose = new Pose3d();
        pose.position().set(10.0, 0.0, 1010.0);
        pose.orientation().rotateY(Math.PI / 2.0);

        SubLevelAccess sublevel = ship(pose, new BoundingBox3d(9, 64, 8, 11, 65, 10));
        Vec3 worldStart = pose.transformPosition(new Vec3(999.5, 64.5, 0.5));
        Vec3 worldEnd = pose.transformPosition(new Vec3(1002.5, 64.5, 0.5));

        FrequencyMaterialAttenuation.setEnabled(true);

        try {
            assertShipLoss(level, sublevel, worldStart, worldEnd, 6.0);
        } finally {
            FrequencyMaterialAttenuation.setEnabled(false);
        }
    }

    @Test
    void missedShipDoesNotAccessPlotBlocks() {
        ServerLevel level = materialWorld();
        Pose3d pose = new Pose3d();
        pose.position().set(-990.0, 0.0, 0.0);
        SubLevelAccess sublevel = ship(pose, new BoundingBox3d(10, 64, 0, 12, 65, 1));
        FrequencyMaterialAttenuation.setEnabled(true);

        try {
            double factor = FrequencySableMaterialAttenuation.factorForSublevels(level, new Vec3(9.5, 70.5, 0.5), new Vec3(12.5, 70.5, 0.5), List.of(sublevel));
            assertEquals(1.0, factor, 0.0);
            verify(level, never()).getBlockState(any(BlockPos.class));
        } finally {
            FrequencyMaterialAttenuation.setEnabled(false);
        }
    }

    @Test
    void profileSableMaterialSamplingAllocations() {
        ServerLevel level = materialWorld();

        Pose3d pose = new Pose3d();
        pose.position().set(-990.0, 0.0, 0.0);

        SubLevelAccess hit = ship(pose, new BoundingBox3d(10, 64, 0, 12, 65, 1));
        SubLevelAccess miss = ship(pose, new BoundingBox3d(10, 80, 0, 12, 81, 1));

        Vec3 start = new Vec3(9.5, 64.5, 0.5);
        Vec3 end = new Vec3(12.5, 64.5, 0.5);

        List<SubLevelAccess> empty = List.of();
        List<SubLevelAccess> missed = List.of(miss, miss, miss, miss, miss, miss, miss, miss);
        List<SubLevelAccess> intersecting = List.of(hit, hit, hit, hit, hit, hit, hit, hit);

        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (memory != null && !memory.isThreadAllocatedMemoryEnabled()) {
            memory.setThreadAllocatedMemoryEnabled(true);
        }

        FrequencyMaterialAttenuation.setEnabled(true);

        try {
            for (int count : new int[]{250, 1000}) {
                for (List<SubLevelAccess> sublevels : List.of(empty, missed, intersecting)) {
                    for (int i = 0; i < 20; i++) {
                        FrequencySableMaterialAttenuation.factorForSublevels(level, start, end, sublevels);
                    }

                    long threadId = Thread.currentThread().threadId();
                    long beforeBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

                    long startTime = System.nanoTime();
                    double checksum = 0.0;

                    for (int i = 0; i < count; i++) {
                        checksum += FrequencySableMaterialAttenuation.factorForSublevels(level, start, end, sublevels);
                    }

                    long elapsed = System.nanoTime() - startTime;
                    long afterBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
                    double bytesPerQuery = memory == null ? -1.0 : (afterBytes - beforeBytes) / (double) count;

                    assertTrue(Double.isFinite(checksum));
                    System.out.printf(Locale.ROOT, "[FREQ-SABLE-MATERIAL] queries=%d ships=%d " + "us/query=%.3f bytes/query=%.1f%n", count, sublevels.size(), elapsed / (count * 1000.0), bytesPerQuery);
                }
            }
        } finally {
            FrequencyMaterialAttenuation.setEnabled(false);
        }
    }
}
