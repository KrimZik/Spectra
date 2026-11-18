package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.WaveMedium;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Test
    void sharedTraversalReportsBoundariesAndAttenuation() {
        List<String> transitions = new ArrayList<>();
        List<Double> fractions = new ArrayList<>();

        MaterialBoundaryCollector collector = new MaterialBoundaryCollector((from, to, fraction, axes) -> {
            transitions.add(from + "->" + to);
            fractions.add(fraction);
            assertEquals(1, axes);
        });

        clearInvocations(section);

        double factor = FrequencyMaterialAttenuation.factorWithMaterials(level, start, end, collector);

        assertEquals(FrequencyMaterialRules.amplitudeFactor(3.0), factor, 1.0e-12);
        assertEquals(2, collector.boundaryCount());

        assertEquals(List.of("spectra:air->spectra:stone", "spectra:stone->spectra:air"), transitions);

        assertEquals(0.25, fractions.get(0), 1.0e-12);
        assertEquals(0.75, fractions.get(1), 1.0e-12);

        verify(section, times(3)).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void boundaryScanningWorksWithAttenuationDisabled() {
        FrequencyMaterialAttenuation.setEnabled(false);

        MaterialBoundaryCollector collector = new MaterialBoundaryCollector((from, to, fraction, axes) -> {});
        double factor = FrequencyMaterialAttenuation.factorWithMaterials(level, start, end, collector);

        assertEquals(1.0, factor, 0.0);
        assertEquals(2, collector.boundaryCount());
    }

    @Test
    void missingChunkDoesNotProduceCompleteBoundaryResult() {
        when(source.getChunkNow(anyInt(), anyInt())).thenReturn(null);

        MaterialBoundaryCollector collector = new MaterialBoundaryCollector((from, to, fraction, axes) -> fail("Unexpected boundary"));
        double factor = FrequencyMaterialAttenuation.factorWithMaterials(level, start, end, collector);

        assertTrue(Double.isNaN(factor));
    }

    @Test
    void boundaryGeometryIsReusedWithoutReadingBlocks() {
        List<String> boundaries = new ArrayList<>();

        double first = cache.factorWithBoundaries(start, end, (from, to, fraction, axes) -> boundaries.add(from + "->" + to));

        assertEquals(FrequencyMaterialRules.amplitudeFactor(3.0), first, 1.0e-12);
        assertEquals(List.of("spectra:air->spectra:stone", "spectra:stone->spectra:air"), boundaries);

        boundaries.clear();
        clearInvocations(section);

        double second = cache.factorWithBoundaries(start, end, (from, to, fraction, axes) -> boundaries.add(from + "->" + to));

        assertEquals(first, second, 0.0);
        assertEquals(2, boundaries.size());
        assertEquals(1, cache.stats().entries());

        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void attenuationReusesBoundaryPathTrace() {
        double first = cache.factorWithBoundaries(start, end, (from, to, fraction, axes) -> {});

        clearInvocations(section);

        assertEquals(first, cache.factor(start, end), 0.0);
        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void changedSectionInvalidatesCachedBoundaryGeometry() {
        List<String> boundaries = new ArrayList<>();

        cache.factorWithBoundaries(start, end, (from, to, fraction, axes) -> boundaries.add(from + "->" + to));

        material.set(Blocks.IRON_BLOCK.defaultBlockState());
        when(access.spectra$materialSummary()).thenReturn(secondSummary);

        boundaries.clear();

        double factor = cache.factorWithBoundaries(start, end, (from, to, fraction, axes) -> boundaries.add(from + "->" + to));

        assertEquals(FrequencyMaterialRules.amplitudeFactor(6.0), factor, 1.0e-12);
        assertEquals(List.of("spectra:air->spectra:metal", "spectra:metal->spectra:air"), boundaries);

        assertTrue(cache.stats().sectionInvalidations() > 0);
    }

    @Test
    void disabledAttenuationDoesNotCacheIncorrectFactor() {
        FrequencyMaterialAttenuation.setEnabled(false);

        double disabled = cache.factorWithBoundaries(start, end, (from, to, fraction, axes) -> {});
        assertEquals(1.0, disabled, 0.0);

        FrequencyMaterialAttenuation.setEnabled(true);
        assertEquals(FrequencyMaterialRules.amplitudeFactor(3.0), cache.factor(start, end), 1.0e-12);
    }

    @Test
    void incompleteBoundaryScanIsNotReplayedOrCached() {
        when(source.getChunkNow(anyInt(), anyInt())).thenReturn(null);

        List<String> boundaries = new ArrayList<>();

        double result = cache.factorWithBoundaries(start, end, (from, to, fraction, axes) -> boundaries.add(from + "->" + to));
        assertTrue(Double.isNaN(result));
        assertTrue(boundaries.isEmpty());
    }

    @Test
    void cachedWorldWavePathReusesGeometryAndCombinesLossOnce() {
        var resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), Map.of(FrequencyMaterialClassifier.STONE, new WaveMedium(3000.0, 3.0, 3.0)), List.of());
        var wave = new MinecraftWorldWavePath(new MinecraftMaterialPropagation(level), resolver);
        double first = wave.evaluate(start, end, 915_000_000.0, true);

        assertEquals(FrequencyMaterialRules.amplitudeFactor(3.0) * 0.75, first, 1.0e-12);
        assertEquals(FrequencyMaterialRules.amplitudeFactor(3.0), wave.attenuationFactor(), 1.0e-12);
        assertEquals(0.75, wave.transmissionAmplitudeFactor(), 1.0e-12);
        assertEquals(0.5625, wave.transmittedEnergyFraction(), 1.0e-12);
        assertEquals(0.4375, wave.reflectedEnergyFraction(), 1.0e-12);
        assertEquals(2, wave.boundaryCount());

        clearInvocations(section);

        double second = wave.evaluate(start, end, 915_000_000.0, true);

        assertEquals(first, second, 0.0);
        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void disablingReflectionPreservesAttenuation() {
        var resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), Map.of(FrequencyMaterialClassifier.STONE, new WaveMedium(3000.0, 3.0, 3.0)), List.of());
        var wave = new MinecraftWorldWavePath(new MinecraftMaterialPropagation(level), resolver);
        double result = wave.evaluate(start, end, 915_000_000.0, false);

        assertEquals(FrequencyMaterialRules.amplitudeFactor(3.0), result, 1.0e-12);
        assertEquals(1.0, wave.transmissionAmplitudeFactor(), 0.0);
        assertEquals(0.0, wave.reflectedEnergyFraction(), 0.0);
        assertEquals(2, wave.boundaryCount());
    }

    @Test
    void customMaterialPropertiesAffectWavePath() {
        ResourceLocation rubber = ResourceLocation.parse("test:rubber");

        FrequencyMaterialRules.replaceMaterials(List.of(new FrequencyMaterialRules.Rule(ResourceLocation.parse("minecraft:stone"), null, rubber, null)), Map.of(rubber, new FrequencyMaterialRules.Properties(2.5, 1200.0, 5.0)));

        var resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), List.of());
        WaveMedium properties = resolver.resolveMaterial(rubber, 915_000_000.0);

        assertEquals(1200.0, properties.speedMetersPerSecond(), 0.0);
        assertEquals(5.0, properties.impedance(), 0.0);
        assertEquals(2.5, properties.lossDbPerMeter(), 0.0);

        var wave = new MinecraftWorldWavePath(new MinecraftMaterialPropagation(level), resolver);
        double result = wave.evaluate(start, end, 915_000_000.0, true);

        assertEquals(FrequencyMaterialRules.amplitudeFactor(2.5) * (5.0 / 9.0), result, 1.0e-12);
        assertEquals(2, wave.boundaryCount());
    }

    @Test
    void cachedWavePathRejectsUnrepresentableStateOverrides() {
        var resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), List.of(MinecraftWaveMediumResolver.block(ResourceLocation.parse("minecraft:stone"), new WaveMedium(3000.0, 5.0, 3.0))));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftWorldWavePath(new MinecraftMaterialPropagation(level), resolver));
    }

    @Test
    void cachedPathReconstructsCompleteMaterialSegments() {
        List<ResourceLocation> materials = new ArrayList<>();
        List<Double> starts = new ArrayList<>();
        List<Double> ends = new ArrayList<>();

        double factor = cache.factorWithSegments(start, end, (materialId, from, to, axes) -> {
            materials.add(materialId);
            starts.add(from);
            ends.add(to);
        });

        assertEquals(FrequencyMaterialRules.amplitudeFactor(3.0), factor, 1.0e-12);
        assertEquals(List.of(FrequencyMaterialClassifier.AIR, FrequencyMaterialClassifier.STONE, FrequencyMaterialClassifier.AIR), materials);

        assertEquals(List.of(0.0, 0.25, 0.75), starts);
        assertEquals(List.of(0.25, 0.75, 1.0), ends);

        materials.clear();
        clearInvocations(section);

        cache.factorWithSegments(start, end, (materialId, from, to, axes) -> materials.add(materialId));

        assertEquals(3, materials.size());
        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void uniformMaterialPathCachesItsInitialMaterial() {
        material.set(Blocks.AIR.defaultBlockState());

        List<ResourceLocation> materials = new ArrayList<>();

        cache.factorWithSegments(start, end, (materialId, from, to, axes) -> {
            materials.add(materialId);
            assertEquals(0.0, from, 0.0);
            assertEquals(1.0, to, 0.0);
        });

        assertEquals(List.of(FrequencyMaterialClassifier.AIR), materials);

        materials.clear();
        clearInvocations(section);

        cache.factorWithSegments(
                start, end,
                (materialId, from, to, axes) -> materials.add(materialId)
        );

        assertEquals(List.of(FrequencyMaterialClassifier.AIR), materials);
        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void segmentCacheInvalidatesOnBlockChanges() {
        List<ResourceLocation> materials = new ArrayList<>();

        cache.factorWithSegments(start, end, (materialId, from, to, axes) -> materials.add(materialId));

        material.set(Blocks.IRON_BLOCK.defaultBlockState());
        when(access.spectra$materialSummary()).thenReturn(secondSummary);
        materials.clear();

        cache.factorWithSegments(start, end, (materialId, from, to, axes) -> materials.add(materialId));

        assertEquals(List.of(FrequencyMaterialClassifier.AIR, FrequencyMaterialClassifier.METAL, FrequencyMaterialClassifier.AIR), materials);
        assertTrue(cache.stats().sectionInvalidations() > 0);
    }
}