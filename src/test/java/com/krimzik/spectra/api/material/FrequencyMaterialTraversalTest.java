package com.krimzik.spectra.api.material;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyMaterialTraversalTest {
    private ServerLevel level;
    private ServerChunkCache chunkSource;
    private LevelChunk chunk;

    @BeforeEach
    void setup() {
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        level = mock(ServerLevel.class);

        chunkSource = mock(ServerChunkCache.class);
        chunk = mock(LevelChunk.class);

        when(level.getChunkSource()).thenReturn(chunkSource);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);

        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);

        FrequencyMaterialRules.replaceOverrides(java.util.List.of());
        FrequencyMaterialAttenuation.setEnabled(true);
    }

    @AfterEach
    void cleanup() {
        FrequencyMaterialAttenuation.setEnabled(false);
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        FrequencyMaterialRules.replaceOverrides(java.util.List.of());
    }

    @Test
    void loadedMaterialBeyondOldTraversalLimitStillAttenuates() {
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 350 || pos.getX() == 351 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        double result = FrequencyMaterialAttenuation.factor(level, new Vec3(0.5, 64.5, 0.5), new Vec3(401.5, 64.5, 0.5));
        assertEquals(FrequencyMaterialRules.amplitudeFactor(6.0), result, 1.0e-12);
    }

    @Test
    void unloadedChunksAreTransparentWithoutDiscardingKnownLoss() {
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenAnswer(call -> {
            int chunkX = call.getArgument(0);
            int chunkZ = call.getArgument(1);

            return chunkZ == 0 && (chunkX == 0 || chunkX == 37) ? chunk : null;
        });

        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 0 || pos.getX() == 600 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        double result = FrequencyMaterialAttenuation.factor(level, new Vec3(0.5, 64.5, 0.5), new Vec3(601.5, 64.5, 0.5));
        assertEquals(FrequencyMaterialRules.amplitudeFactor(4.5), result, 1.0e-12);

        verify(chunk, atMost(32)).getBlockState(any(BlockPos.class));
    }

    @Test
    void reverseTraversalPreservesLoadedMaterialLoss() {
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenAnswer(call -> {
            int chunkX = call.getArgument(0);
            return chunkX == 0 || chunkX == 37 ? chunk : null;
        });

        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 0 || pos.getX() == 600 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        double result = FrequencyMaterialAttenuation.factor(level, new Vec3(601.5, 64.5, 0.5), new Vec3(0.5, 64.5, 0.5));
        assertEquals(FrequencyMaterialRules.amplitudeFactor(4.5), result, 1.0e-12);
    }

    @Test
    void disabledAttenuationDoesNotAccessTerrain() {
        FrequencyMaterialAttenuation.setEnabled(false);
        double result = FrequencyMaterialAttenuation.factor(level, Vec3.ZERO, new Vec3(100000.0, 0.0, 0.0));

        assertEquals(1.0, result, 0.0);

        verify(level, never()).getChunkSource();
        verify(chunkSource, never()).getChunkNow(anyInt(), anyInt());
        verify(chunk, never()).getBlockState(any(BlockPos.class));
    }

    @Test
    void diagonalPathWithinReceptionRangeIsFullySampled() {
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 140 && pos.getY() == 204 && pos.getZ() == 140 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(145.5, 209.5, 145.5);

        double expected = FrequencyMaterialRules.amplitudeFactor(3.0 * Math.sqrt(3.0));
        double forward = FrequencyMaterialAttenuation.factor(level, start, end);
        double reverse = FrequencyMaterialAttenuation.factor(level, end, start);

        assertEquals(expected, forward, 1.0e-10);
        assertEquals(expected, reverse, 1.0e-10);
    }

    @Test
    void traversalReachesMaterialBeyondOldBudget() {
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 1100 || pos.getX() == 1101 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(1200.5, 64.5, 0.5);

        double expected = FrequencyMaterialRules.amplitudeFactor(6.0);

        assertEquals(expected, FrequencyMaterialAttenuation.factor(level, start, end), 1.0e-10);
        assertEquals(expected, FrequencyMaterialAttenuation.factor(level, end, start), 1.0e-10);
    }

    @Test
    void unloadedChunkSkippingReachesDistantLoadedMaterials() {
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenAnswer(call -> {
            int chunkX = call.getArgument(0);
            int chunkZ = call.getArgument(1);
            return chunkZ == 0 && (chunkX == 0 || chunkX == 1250) ? chunk : null;
        });

        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 0 || pos.getX() == 20000 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(20000.5, 64.5, 0.5);

        double expected = FrequencyMaterialRules.amplitudeFactor(3.0);

        assertEquals(expected, FrequencyMaterialAttenuation.factor(level, start, end), 1.0e-10);
        assertEquals(expected, FrequencyMaterialAttenuation.factor(level, end, start), 1.0e-10);
    }

    @Test
    void emptySectionAvoidsBlockLookups() {
        LevelChunkSection empty = mock(LevelChunkSection.class);
        when(empty.hasOnlyAir()).thenReturn(true);

        LevelChunkSection[] sections = new LevelChunkSection[24];
        sections[8] = empty;

        when(chunk.getSections()).thenReturn(sections);
        double result = FrequencyMaterialAttenuation.factor(level, new Vec3(0.5, 64.5, 0.5), new Vec3(10.5, 64.5, 0.5));
        assertEquals(1.0, result, 0.0);

        verify(chunk, never()).getBlockState(any(BlockPos.class));
        verify(empty, times(1)).hasOnlyAir();
    }

    @Test
    void customAirAttenuationDisablesEmptySectionSkipping() {
        LevelChunkSection empty = mock(LevelChunkSection.class);
        when(empty.hasOnlyAir()).thenReturn(true);

        LevelChunkSection[] sections = new LevelChunkSection[24];
        sections[8] = empty;

        when(chunk.getSections()).thenReturn(sections);
        when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());

        FrequencyMaterialRules.replaceOverrides(java.util.List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:air"), 2.0)));
        double result = FrequencyMaterialAttenuation.factor(level, new Vec3(0.5, 64.5, 0.5), new Vec3(10.5, 64.5, 0.5));
        assertEquals(FrequencyMaterialRules.amplitudeFactor(20.0), result, 1.0e-12);

        verify(chunk, times(11)).getBlockState(any(BlockPos.class));
        verify(empty, never()).hasOnlyAir();
    }


    @Test
    void airAndWaterUseConfiguredLossWithoutDoubleCounting() {
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 1 || pos.getX() == 2 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(3.5, 64.5, 0.5);

        double expected = FrequencyMaterialRules.amplitudeFactor(1.0);
        assertEquals(expected, FrequencyMaterialAttenuation.factor(level, start, end), 1.0e-12);
        assertEquals(expected, FrequencyMaterialAttenuation.factor(level, end, start), 1.0e-12);

        FrequencyMaterialRules.replaceOverrides(java.util.List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:water"), 2.0)));
        double overridden = FrequencyMaterialRules.amplitudeFactor(4.0);

        assertEquals(overridden, FrequencyMaterialAttenuation.factor(level, start, end), 1.0e-12);
    }

    @Test
    void emptyAirSectionRetainsConfiguredEnvironmentalLoss() {
        FrequencyMaterialRules.setEnvironmentLoss(0.25, 0.5);

        LevelChunkSection empty = mock(LevelChunkSection.class);
        when(empty.hasOnlyAir()).thenReturn(true);

        LevelChunkSection[] sections = new LevelChunkSection[24];
        sections[8] = empty;

        when(chunk.getSections()).thenReturn(sections);

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(10.5, 64.5, 0.5);

        double expected = FrequencyMaterialRules.amplitudeFactor(2.5);
        assertEquals(expected, FrequencyMaterialAttenuation.factor(level, start, end), 1.0e-12);

        verify(chunk, never()).getBlockState(any(BlockPos.class));
    }
}
