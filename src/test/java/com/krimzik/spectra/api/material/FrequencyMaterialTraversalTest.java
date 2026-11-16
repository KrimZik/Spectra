package com.krimzik.spectra.api.material;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyMaterialTraversalTest {
    private ServerLevel level;

    @BeforeEach
    void setup() {
        level = mock(ServerLevel.class);

        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);

        FrequencyMaterialRules.replaceOverrides(java.util.List.of());
        FrequencyMaterialAttenuation.setLoadedBlockBudget(1024);
        FrequencyMaterialAttenuation.setEnabled(true);
    }

    @AfterEach
    void cleanup() {
        FrequencyMaterialAttenuation.setEnabled(false);
        FrequencyMaterialAttenuation.setLoadedBlockBudget(1024);
        FrequencyMaterialRules.replaceOverrides(java.util.List.of());
    }

    @Test
    void loadedMaterialBeyondOldTraversalLimitStillAttenuates() {
        when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);

        when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 350 || pos.getX() == 351 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        double result = FrequencyMaterialAttenuation.factor(level, new Vec3(0.5, 64.5, 0.5), new Vec3(401.5, 64.5, 0.5));
        assertEquals(FrequencyMaterialRules.amplitudeFactor(6.0), result, 1.0e-12);
    }

    @Test
    void unloadedChunksAreTransparentWithoutDiscardingKnownLoss() {
        when(level.hasChunk(anyInt(), anyInt())).thenAnswer(call -> {
            int chunkX = call.getArgument(0);
            int chunkZ = call.getArgument(1);
            return chunkZ == 0 && (chunkX == 0 || chunkX == 37);
        });

        when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 0 || pos.getX() == 600 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        double result = FrequencyMaterialAttenuation.factor(level, new Vec3(0.5, 64.5, 0.5), new Vec3(601.5, 64.5, 0.5));
        assertEquals(FrequencyMaterialRules.amplitudeFactor(4.5), result, 1.0e-12);
        verify(level, atMost(32)).getBlockState(any(BlockPos.class));
    }

    @Test
    void reverseTraversalPreservesLoadedMaterialLoss() {
        when(level.hasChunk(anyInt(), anyInt())).thenAnswer(call -> {
            int chunkX = call.getArgument(0);
            return chunkX == 0 || chunkX == 37;
        });

        when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 0 || pos.getX() == 600 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        double result = FrequencyMaterialAttenuation.factor(level, new Vec3(601.5, 64.5, 0.5), new Vec3(0.5, 64.5, 0.5));
        assertEquals(FrequencyMaterialRules.amplitudeFactor(4.5), result, 1.0e-12);
    }

    @Test
    void workBudgetRetainsPartialMaterialLoss() {
        FrequencyMaterialAttenuation.setLoadedBlockBudget(4);

        when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());

        double result = FrequencyMaterialAttenuation.factor(level, new Vec3(0.5, 64.5, 0.5), new Vec3(10.5, 64.5, 0.5));
        assertEquals(FrequencyMaterialRules.amplitudeFactor(10.5), result, 1.0e-12);
        verify(level, times(4)).getBlockState(any(BlockPos.class));
    }

    @Test
    void disabledAttenuationDoesNotAccessTerrain() {
        FrequencyMaterialAttenuation.setEnabled(false);
        double result = FrequencyMaterialAttenuation.factor(level, Vec3.ZERO, new Vec3(100000.0, 0.0, 0.0));

        assertEquals(1.0, result, 0.0);
        verify(level, never()).hasChunk(anyInt(), anyInt());
        verify(level, never()).getBlockState(any(BlockPos.class));
    }
}
