package com.krimzik.spectra.api.material;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyMaterialGeometryTest {
    private static final double EPSILON = 1.0e-10;
    private ServerLevel level;
    private ServerChunkCache chunkSource;
    private LevelChunk chunk;

    @BeforeEach
    void setup() {
        level = mock(ServerLevel.class);

        chunkSource = mock(ServerChunkCache.class);
        chunk = mock(LevelChunk.class);
        when(level.getChunkSource()).thenReturn(chunkSource);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);

        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);

        FrequencyMaterialRules.replaceOverrides(List.of());
        FrequencyMaterialAttenuation.setEnabled(true);
    }

    @AfterEach
    void cleanup() {
        FrequencyMaterialAttenuation.setEnabled(false);
        FrequencyMaterialRules.replaceOverrides(List.of());
    }

    private void materials(Predicate<BlockPos> stone) {
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return stone.test(pos) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });
    }

    private void assertLoss(double expectedDb, Vec3 start, Vec3 end) {
        assertEquals(FrequencyMaterialRules.amplitudeFactor(expectedDb), FrequencyMaterialAttenuation.factor(level, start, end), EPSILON);
    }

    @Test
    void diagonalTraversalUsesActualMaterialThickness() {
        materials(pos -> pos.getX() == 1 || pos.getX() == 2);

        Vec3 start = new Vec3(0.25, 64.25, 0.5);
        Vec3 end = new Vec3(4.25, 66.25, 0.5);

        double expectedLoss = 3.0 * Math.sqrt(5.0);

        assertLoss(expectedLoss, start, end);
        assertLoss(expectedLoss, end, start);
    }

    @Test
    void diagonalCornerCrossingCountsOnlyEnteredVoxels() {
        materials(pos -> pos.getX() == 16 && pos.getY() == 64 && pos.getZ() == 16);

        Vec3 start = new Vec3(14.5, 64.5, 14.5);
        Vec3 end = new Vec3(18.5, 64.5, 18.5);

        double expectedLoss = 3.0 * Math.sqrt(2.0);

        assertLoss(expectedLoss, start, end);
        assertLoss(expectedLoss, end, start);
    }

    @Test
    void cornerTouchWithoutEnteringBlockAddsNoLoss() {
        materials(pos -> pos.getX() == 15 && pos.getY() == 64 && pos.getZ() == 16);

        Vec3 start = new Vec3(14.5, 64.5, 14.5);
        Vec3 end = new Vec3(18.5, 64.5, 18.5);

        assertLoss(0.0, start, end);
        assertLoss(0.0, end, start);
    }

    @Test
    void negativeChunkCoordinatesPreserveMaterialThickness() {
        materials(pos -> pos.getX() == -16 || pos.getX() == -15);

        Vec3 start = new Vec3(-17.5, 64.5, 0.5);
        Vec3 end = new Vec3(-13.5, 64.5, 0.5);

        assertLoss(6.0, start, end);
        assertLoss(6.0, end, start);
    }

    @Test
    void startingOnExactBlockBoundaryWorksBothDirections() {
        materials(pos -> pos.getX() == -1);

        Vec3 left = new Vec3(-2.0, 64.5, 0.5);
        Vec3 middle = new Vec3(0.0, 64.5, 0.5);

        assertLoss(3.0, left, middle);
        assertLoss(3.0, middle, left);
    }

    @Test
    void unloadedGapPreservesBothLoadedEndpointMaterials() {
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenAnswer(call -> {
            int chunkX = call.getArgument(0);
            int chunkZ = call.getArgument(1);

            return chunkZ == 0 && (chunkX == 0 || chunkX == 2) ? chunk : null;
        });

        materials(pos -> {
            assertTrue(pos.getX() < 16 || pos.getX() >= 32);
            return pos.getX() == 15 || pos.getX() == 32;
        });

        Vec3 start = new Vec3(15.5, 64.5, 0.5);
        Vec3 end = new Vec3(32.5, 64.5, 0.5);

        assertLoss(3.0, start, end);
        assertLoss(3.0, end, start);
    }

    @Test
    void outOfBuildHeightSectionDoesNotHideValidTerrain() {
        materials(pos -> pos.getY() == 319);

        Vec3 inside = new Vec3(0.5, 319.5, 0.5);
        Vec3 outside = new Vec3(0.5, 321.5, 0.5);

        assertLoss(1.5, inside, outside);
        assertLoss(1.5, outside, inside);
    }

    @Test
    void primitiveCoordinatesMatchVec3Traversal() {
        materials(pos -> pos.getX() == 15 || pos.getX() == 16 || pos.getX() == 18);

        Vec3 start = new Vec3(14.25, 64.25, 0.5);
        Vec3 end = new Vec3(19.75, 66.75, 0.5);

        double objectResult = FrequencyMaterialAttenuation.factor(level, start, end);
        double primitiveResult = FrequencyMaterialAttenuation.factor(level, start.x, start.y, start.z, end.x, end.y, end.z);

        assertEquals(objectResult, primitiveResult, 0.0);

        double reverseObject = FrequencyMaterialAttenuation.factor(level, end, start);
        double reversePrimitive = FrequencyMaterialAttenuation.factor(level, end.x, end.y, end.z, start.x, start.y, start.z);

        assertEquals(reverseObject, reversePrimitive, 0.0);
    }

    @Test
    void loadedChunkIsResolvedOnceUntilCrossingBoundary() {
        materials(pos -> false);

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(10.5, 64.5, 0.5);

        assertLoss(0.0, start, end);

        verify(chunkSource, times(1)).getChunkNow(0, 0);
        verify(chunk, times(11)).getBlockState(any(BlockPos.class));

        clearInvocations(chunkSource, chunk);

        assertLoss(0.0, new Vec3(15.5, 64.5, 0.5), new Vec3(16.5, 64.5, 0.5));

        verify(chunkSource, times(1)).getChunkNow(0, 0);
        verify(chunkSource, times(1)).getChunkNow(1, 0);
        verify(chunk, times(2)).getBlockState(any(BlockPos.class));
    }
}
