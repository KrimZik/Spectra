package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.WaveMedium;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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

class FrequencySableMaterialAttenuationTest {
    private static final Vec3 START = new Vec3(0.5, 64.5, 0.5);
    private static final Vec3 END = new Vec3(2.5, 64.5, 0.5);

    private final AtomicReference<BlockState> material = new AtomicReference<>(Blocks.IRON_BLOCK.defaultBlockState());

    private ServerLevel level;
    private SubLevelAccess sublevel;
    private Pose3d pose;
    private BoundingBox3d bounds;

    @BeforeEach
    void setup() {
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        FrequencyMaterialRules.replaceOverrides(List.of());
        FrequencyMaterialAttenuation.setEnabled(true);

        level = mock(ServerLevel.class);
        ServerChunkCache source = mock(ServerChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        LevelChunkSection section = mock(LevelChunkSection.class);

        when(level.getChunkSource()).thenReturn(source);
        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);
        when(source.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);

        LevelChunkSection[] sections = new LevelChunkSection[24];
        sections[8] = section;

        when(chunk.getSections()).thenReturn(sections);
        when(section.hasOnlyAir()).thenReturn(false);
        when(section.getBlockState(anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    int x = call.getArgument(0);
                    int y = call.getArgument(1);
                    int z = call.getArgument(2);

                    return x == 1 && y == 0 && z == 0 ? material.get() : Blocks.AIR.defaultBlockState();
                });

        pose = new Pose3d();
        bounds = new BoundingBox3d(0, 64, 0, 3, 65, 1);

        sublevel = mock(SubLevelAccess.class);
        when(sublevel.logicalPose()).thenReturn(pose);
        when(sublevel.boundingBox()).thenReturn(bounds);
    }

    @AfterEach
    void cleanup() {
        FrequencyMaterialAttenuation.setEnabled(false);
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        FrequencyMaterialRules.replaceOverrides(List.of());
    }

    private double factor() {
        return FrequencySableMaterialAttenuation.factorForSublevels(level, START, END, List.of(sublevel));
    }

    @Test
    void stationaryShipAppliesMaterialAttenuation() {
        double expected = FrequencyMaterialAttenuation.factorRelativeToAir(level, START.x, START.y, START.z, END.x, END.y, END.z);

        assertTrue(expected < 1.0);
        assertEquals(expected, factor(), 1.0e-12);
    }

    @Test
    void movingShipUpdatesAttenuationAndCandidates() {
        double original = factor();

        pose.position().set(0.0, 0.0, 8.0);
        bounds.set(0, 64, 8, 3, 65, 9);

        assertEquals(1.0, factor(), 1.0e-12);
        assertTrue(FrequencySableMaterialAttenuation.receiverCandidates(List.of(sublevel), END, 4.0).isEmpty());

        pose.position().zero();
        bounds.set(0, 64, 0, 3, 65, 1);

        assertEquals(original, factor(), 1.0e-12);
        assertEquals(1, FrequencySableMaterialAttenuation.receiverCandidates(List.of(sublevel), END, 4.0).size());
    }

    @Test
    void rotatingShipPreservesEquivalentMaterialThickness() {
        double original = factor();

        pose.orientation().rotationY(Math.PI / 2.0);
        bounds.set(new BoundingBox3d(0, 64, 0, 3, 65, 1).transform(pose));

        Vec3 rotatedStart = pose.transformPosition(START);
        Vec3 rotatedEnd = pose.transformPosition(END);

        double rotated = FrequencySableMaterialAttenuation.factorForSublevels(level, rotatedStart, rotatedEnd, List.of(sublevel));

        assertEquals(original, rotated, 1.0e-9);
    }

    @Test
    void blockChangesAndCandidateRemovalUpdateAttenuation() {
        double original = factor();

        material.set(Blocks.AIR.defaultBlockState());
        assertEquals(1.0, factor(), 1.0e-12);

        material.set(Blocks.IRON_BLOCK.defaultBlockState());
        assertEquals(original, factor(), 1.0e-12);

        assertEquals(1.0, FrequencySableMaterialAttenuation.factorForSublevels(level, START, END, List.of()), 1.0e-12);
    }

    @Test
    void shipSegmentsUseOriginalWorldPathFractions() {
        List<double[]> metalSegments = new ArrayList<>();

        double result = FrequencySableMaterialAttenuation.factorForSublevelsWithMaterials(
                level,
                new Vec3(-0.5, 64.5, 0.5),
                new Vec3(3.5, 64.5, 0.5),
                List.of(sublevel),
                (ship, materialId, from, to, axes) -> {
                    assertSame(sublevel, ship);

                    if (FrequencyMaterialClassifier.METAL.equals(materialId)) {
                        metalSegments.add(new double[]{from, to});
                    }
                }
        );

        assertEquals(FrequencyMaterialRules.amplitudeFactor(6.0), result, 1.0e-12);
        assertEquals(1, metalSegments.size());
        assertEquals(0.375, metalSegments.get(0)[0], 1.0e-12);
        assertEquals(0.625, metalSegments.get(0)[1], 1.0e-12);
    }

    @Test
    void shipBoundarySamplingStillWorksWhenAttenuationIsDisabled() {
        FrequencyMaterialAttenuation.setEnabled(false);

        List<double[]> metalSegments = new ArrayList<>();

        double result = FrequencySableMaterialAttenuation.factorForSublevelsWithMaterials(level, START, END, List.of(sublevel), (ship, materialId, from, to, axes) -> {
            if (FrequencyMaterialClassifier.METAL.equals(materialId)) {
                metalSegments.add(new double[]{from, to});
            }
        });

        assertEquals(1.0, result, 0.0);
        assertEquals(1, metalSegments.size());
        assertEquals(0.25, metalSegments.get(0)[0], 1.0e-12);
        assertEquals(0.75, metalSegments.get(0)[1], 1.0e-12);
    }

    @Test
    void movingShipRemovesBoundarySegmentsFromOldPosition() {
        pose.position().set(0.0, 0.0, 8.0);
        bounds.set(0, 64, 8, 3, 65, 9);

        List<String> materials = new ArrayList<>();
        double result = FrequencySableMaterialAttenuation.factorForSublevelsWithMaterials(level, START, END, List.of(sublevel), (ship, materialId, from, to, axes) -> materials.add(materialId.toString()));

        assertEquals(1.0, result, 0.0);
        assertTrue(materials.isEmpty());
    }

    @Test
    void combinedMaterialSegmentsFollowMovingShip() {
        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);

        pose.position().set(4.0, 0.0, 0.0);
        bounds.set(4, 64, 0, 7, 65, 1);

        Vec3 worldStart = START.add(4.0, 0.0, 0.0);
        Vec3 worldEnd = END.add(4.0, 0.0, 0.0);

        List<ResourceLocation> materials = new ArrayList<>();
        List<Double> starts = new ArrayList<>();
        List<Double> ends = new ArrayList<>();

        assertTrue(propagation.forEachCombinedMaterialSegment(worldStart, worldEnd, List.of(sublevel), (materialId, from, to, axes) -> {
            materials.add(materialId);
            starts.add(from);
            ends.add(to);
        }));

        assertEquals(List.of(FrequencyMaterialClassifier.AIR, FrequencyMaterialClassifier.METAL, FrequencyMaterialClassifier.AIR), materials);
        assertEquals(List.of(0.0, 0.25, 0.75), starts);
        assertEquals(List.of(0.25, 0.75, 1.0), ends);

        pose.position().set(4.0, 0.0, 8.0);
        bounds.set(4, 64, 8, 7, 65, 9);

        materials.clear();

        assertTrue(propagation.forEachCombinedMaterialSegment(worldStart, worldEnd, List.of(sublevel), (materialId, from, to, axes) -> materials.add(materialId)));
        assertEquals(List.of(FrequencyMaterialClassifier.AIR), materials);
    }

    @Test
    void combinedWavePathUsesExactShipLossAndFollowsMotion() {
        FrequencyMaterialRules.replaceOverrides(List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:iron_block"), 10.0)));

        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);
        MinecraftWaveMediumResolver resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), Map.of(FrequencyMaterialClassifier.METAL, new WaveMedium(3000.0, 3.0, 0.0)), List.of());
        MinecraftCombinedWavePath wave = new MinecraftCombinedWavePath(propagation, resolver);

        pose.position().set(4.0, 0.0, 0.0);
        bounds.set(4, 64, 0, 7, 65, 1);

        Vec3 worldStart = START.add(4.0, 0.0, 0.0);
        Vec3 worldEnd = END.add(4.0, 0.0, 0.0);

        double first = wave.evaluate(worldStart, worldEnd, List.of(sublevel), 915_000_000.0, true);

        assertEquals(FrequencyMaterialRules.amplitudeFactor(10.0) * 0.75, first, 1.0e-12);
        assertEquals(2, wave.boundaryCount());

        pose.position().set(4.0, 0.0, 8.0);
        bounds.set(4, 64, 8, 7, 65, 9);

        double moved = wave.evaluate(worldStart, worldEnd, List.of(sublevel), 915_000_000.0, true);

        assertEquals(1.0, moved, 1.0e-12);
        assertEquals(0, wave.boundaryCount());
    }

    @Test
    void combinedShipTransmissionStillWorksWithAttenuationDisabled() {
        FrequencyMaterialAttenuation.setEnabled(false);

        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);
        MinecraftWaveMediumResolver resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), Map.of(FrequencyMaterialClassifier.METAL, new WaveMedium(3000.0, 3.0, 0.0)), List.of());

        MinecraftCombinedWavePath wave = new MinecraftCombinedWavePath(propagation, resolver);
        double result = wave.evaluate(START, END, List.of(sublevel), 915_000_000.0, true);

        assertEquals(0.75, result, 1.0e-12);
        assertEquals(1.0, wave.attenuationFactor(), 0.0);
        assertEquals(2, wave.boundaryCount());
    }

    @Test
    void productionWaveReceptionFollowsMovingShip() {
        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);
        MinecraftWaveMediumResolver resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), Map.of(FrequencyMaterialClassifier.METAL, new WaveMedium(3000.0, 3.0, 0.0)), List.of());
        MinecraftCombinedWavePath wave = new MinecraftCombinedWavePath(propagation, resolver);

        pose.position().set(4.0, 0.0, 0.0);
        bounds.set(4, 64, 0, 7, 65, 1);

        Vec3 worldStart = START.add(4.0, 0.0, 0.0);
        Vec3 worldEnd = END.add(4.0, 0.0, 0.0);

        double original = propagation.factorForDirectWaveReception(worldStart, worldEnd, List.of(sublevel), 915_000_000.0, wave);

        assertEquals(FrequencyMaterialRules.amplitudeFactor(6.0) * 0.75, original, 1.0e-12);

        pose.position().set(4.0, 0.0, 8.0);
        bounds.set(4, 64, 8, 7, 65, 9);

        double moved = propagation.factorForDirectWaveReception(worldStart, worldEnd, List.of(sublevel), 915_000_000.0, wave);

        assertEquals(1.0, moved, 1.0e-12);
    }
}