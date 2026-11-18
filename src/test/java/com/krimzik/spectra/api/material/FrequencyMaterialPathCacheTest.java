package com.krimzik.spectra.api.material;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyMaterialPathCacheTest {
    private ServerLevel level;
    private ServerChunkCache source;
    private LevelChunkSection section;
    private FrequencyMaterialSectionAccess access;
    private FrequencyMaterialSectionCache firstSummary;
    private FrequencyMaterialSectionCache secondSummary;
    private FrequencyMaterialPathCache cache;

    private final Vec3 start = new Vec3(0.5, 64.5, 0.5);
    private final Vec3 end = new Vec3(2.5, 64.5, 0.5);

    private final AtomicReference<net.minecraft.world.level.block.state.BlockState> material = new AtomicReference<>(Blocks.STONE.defaultBlockState());

    @BeforeEach
    void setup() {
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        FrequencyMaterialRules.replaceOverrides(List.of());
        FrequencyMaterialAttenuation.setEnabled(true);

        level = mock(ServerLevel.class);
        source = mock(ServerChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);

        section = mock(LevelChunkSection.class, withSettings().extraInterfaces(FrequencyMaterialSectionAccess.class));
        access = (FrequencyMaterialSectionAccess) section;

        firstSummary = mock(FrequencyMaterialSectionCache.class);
        secondSummary = mock(FrequencyMaterialSectionCache.class);

        for (FrequencyMaterialSectionCache summary : List.of(firstSummary, secondSummary)) {
            when(summary.sectionLoss()).thenReturn(Double.NaN);
            when(summary.regionLoss(anyInt(), anyInt(), anyInt())).thenReturn(Double.NaN);
        }

        when(access.spectra$materialSummary()).thenReturn(firstSummary);
        when(level.getChunkSource()).thenReturn(source);
        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);
        when(source.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);

        LevelChunkSection[] sections = new LevelChunkSection[24];
        sections[8] = section;

        when(chunk.getSections()).thenReturn(sections);
        when(section.getBlockState(anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    int x = call.getArgument(0);
                    return x == 1 ? material.get() : Blocks.AIR.defaultBlockState();
                });

        cache = new FrequencyMaterialPathCache(level);
    }

    @AfterEach
    void cleanup() {
        cache.clear();
        FrequencyMaterialAttenuation.setEnabled(false);
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        FrequencyMaterialRules.replaceOverrides(List.of());
    }

    @Test
    void unchangedPathReusesMaterialResult() {
        double expected = FrequencyMaterialRules.amplitudeFactor(3.0);

        assertEquals(expected, cache.factor(start, end), 1.0e-12);
        assertEquals(expected, cache.factor(start, end), 1.0e-12);

        clearInvocations(section);

        assertEquals(expected, cache.factor(start, end), 1.0e-12);
        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void changedSectionInvalidatesCachedPath() {
        double original = FrequencyMaterialRules.amplitudeFactor(3.0);

        cache.factor(start, end);
        cache.factor(start, end);

        assertEquals(original, cache.factor(start, end), 1.0e-12);

        material.set(Blocks.IRON_BLOCK.defaultBlockState());
        when(access.spectra$materialSummary()).thenReturn(secondSummary);

        double updated = FrequencyMaterialRules.amplitudeFactor(6.0);

        assertEquals(updated, cache.factor(start, end), 1.0e-12);
    }

    @Test
    void changedMaterialRulesInvalidateCachedPath() {
        cache.factor(start, end);
        cache.factor(start, end);

        FrequencyMaterialRules.setEnvironmentLoss(0.25, 0.5);

        double expected = FrequencyMaterialRules.amplitudeFactor(3.25);

        assertEquals(expected, cache.factor(start, end), 1.0e-12);
    }

    @Test
    void movingEndpointsCannotReusePreviousPath() {
        cache.factor(start, end);
        cache.factor(start, end);

        clearInvocations(section);
        cache.factor(start, new Vec3(3.5, 64.5, 0.5));

        verify(section, atLeastOnce()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void stationaryPairBecomesCacheHit() {
        cache.factor(start, end);
        cache.factor(start, end);
        cache.factor(start, end);

        var stats = cache.stats();

        assertEquals(1, stats.coldMisses());
        assertEquals(1, stats.traceBuilds());
        assertEquals(1, stats.cacheHits());
        assertEquals(0, stats.sectionInvalidations());
    }

    @Test
    void differentCoordinateObjectsReuseIdenticalMaterialPath() {
        double expected = FrequencyMaterialRules.amplitudeFactor(3.0);

        assertEquals(expected, cache.factor(start, end), 1.0e-12);
        assertEquals(expected, cache.factor(new Vec3(start.x, start.y, start.z), new Vec3(end.x, end.y, end.z)), 1.0e-12);

        clearInvocations(section);

        assertEquals(expected, cache.factor(start, end), 1.0e-12);

        assertEquals(1, cache.stats().entries());
        assertEquals(1, cache.stats().cacheHits());

        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void overflowScanPreservesFrequentlyUsedGeometry() {
        double expected = FrequencyMaterialRules.amplitudeFactor(3.0);

        cache.factor(start, end);
        cache.factor(start, end);
        cache.factor(start, end);

        long hitsBefore = cache.stats().cacheHits();

        for (int i = 0; i < 20_000; i++) {
            Vec3 otherStart = new Vec3(0.5 + (i + 1) * 1.0e-9, 64.5, 0.5);
            cache.factor(otherStart, end);
        }

        assertEquals(16_384, cache.stats().entries());
        assertTrue(cache.stats().evictions() < 32);

        assertEquals(expected, cache.factor(start, end), 1.0e-12);
        assertEquals(hitsBefore + 1, cache.stats().cacheHits());
    }
}
