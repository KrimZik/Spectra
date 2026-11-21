package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.WaveMedium;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyMaterialSnapshotTraversalTest {
    private final ServerLevel level = mock(ServerLevel.class);
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerChunkCache source = mock(ServerChunkCache.class);

    private record Segment(ResourceLocation material, double start, double end, int axes) {}

    @BeforeEach
    void setup() {
        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        when(level.getChunkSource()).thenReturn(source);
        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);
        FrequencyMaterialRules.replaceOverrides(List.of());
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        FrequencyMaterialAttenuation.setEnabled(true);
    }

    @AfterEach
    void cleanup() {
        FrequencyMaterialAttenuation.setEnabled(false);
        FrequencyMaterialRules.replaceOverrides(List.of());
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
    }

    private PalettedContainer<BlockState> states() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    private AtomicReference<FrequencyMaterialSectionSnapshot> chunk(int cx, int cz, PalettedContainer<BlockState> states) {
        Thread serverThread = Thread.currentThread();

        LevelChunkSection section = mock(LevelChunkSection.class, withSettings().extraInterfaces(FrequencyMaterialSectionAccess.class));

        when(section.getStates()).thenReturn(states);
        when(section.hasOnlyAir()).thenReturn(false);

        when(section.getBlockState(anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    assertSame(serverThread, Thread.currentThread());
                    return states.get(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                });

        FrequencyMaterialSectionAccess access = (FrequencyMaterialSectionAccess) section;

        when(access.spectra$materialSummary()).thenAnswer(call -> FrequencyMaterialSectionCache.create(section));

        AtomicReference<FrequencyMaterialSectionSnapshot> current = new AtomicReference<>(FrequencyMaterialSectionSnapshot.capture(section));

        when(access.spectra$materialSnapshot(server)).thenAnswer(call -> current.get());
        when(access.spectra$materialSnapshotCurrent(eq(server), any())).thenAnswer(call -> current.get() == call.getArgument(1));

        LevelChunk owner = mock(LevelChunk.class);
        LevelChunkSection[] sections = new LevelChunkSection[24];
        sections[8] = section;

        when(owner.getSections()).thenReturn(sections);

        when(source.getChunkNow(cx, cz)).thenAnswer(call -> {
            assertSame(serverThread, Thread.currentThread());
            return owner;
        });

        return current;
    }

    @Test
    void snapshotMatchesLiveTraversalAndMaterialBoundaries() throws Exception {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.STONE.defaultBlockState());
        states.set(2, 0, 0, Blocks.GLASS.defaultBlockState());
        states.set(3, 0, 0, Blocks.STONE.defaultBlockState());
        chunk(0, 0, states);

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(5.5, 64.5, 0.5);

        FrequencyMaterialWorldSnapshot world = FrequencyMaterialWorldSnapshot.builder(level).includePath(start, end).build();

        List<Segment> liveSegments = new ArrayList<>();
        List<Segment> snapshotSegments = new ArrayList<>();

        double live = FrequencyMaterialAttenuation.factorWithMaterials(level, start, end, (material, a, b, axes) -> liveSegments.add(new Segment(material, a, b, axes)));

        var task = CompletableFuture.supplyAsync(() -> FrequencyMaterialAttenuation.factorSnapshotWithMaterials(world, start, end, (material, a, b, axes) -> snapshotSegments.add(new Segment(material, a, b, axes))));

        assertEquals(live, task.get(), 1.0e-12);
        assertEquals(FrequencyMaterialRules.amplitudeFactor(6.4), live, 1.0e-12);
        assertEquals(liveSegments, snapshotSegments);
        assertTrue(world.currentOnServer());
    }

    @Test
    void workersKeepReadingTheCapturedBlocksAfterSourceChanges() throws Exception {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.STONE.defaultBlockState());

        AtomicReference<FrequencyMaterialSectionSnapshot> current = chunk(0, 0, states);

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(2.5, 64.5, 0.5);

        FrequencyMaterialWorldSnapshot world = FrequencyMaterialWorldSnapshot.builder(level).includePath(start, end).build();

        double expected = FrequencyMaterialRules.amplitudeFactor(3.0);
        assertEquals(expected, FrequencyMaterialAttenuation.factorSnapshot(world, start, end), 1.0e-12);

        states.set(1, 0, 0, Blocks.GLASS.defaultBlockState());

        ExecutorService executor = Executors.newFixedThreadPool(4);

        try {
            List<CompletableFuture<Double>> tasks = new ArrayList<>();

            for (int i = 0; i < 200; i++) {
                tasks.add(CompletableFuture.supplyAsync(() -> FrequencyMaterialAttenuation.factorSnapshot(world, start, end), executor));
            }

            for (var task : tasks) {
                assertEquals(expected, task.get(), 1.0e-12);
            }
        } finally {
            executor.shutdownNow();
        }

        current.set(FrequencyMaterialSectionSnapshot.capture(mockSection(states)));
        assertFalse(world.currentOnServer());
    }

    @Test
    void materialRuleReloadRejectsPreviousWorldSnapshot() {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.STONE.defaultBlockState());
        chunk(0, 0, states);

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(2.5, 64.5, 0.5);

        FrequencyMaterialWorldSnapshot world = FrequencyMaterialWorldSnapshot.builder(level).includePath(start, end).build();

        assertTrue(world.currentOnServer());

        FrequencyMaterialRules.setEnvironmentLoss(0.25, 0.5);

        assertFalse(world.currentOnServer());
    }

    @Test
    void uncapturedChunksCannotBeTreatedAsAir() {
        chunk(0, 0, states());

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(2.5, 64.5, 0.5);

        FrequencyMaterialWorldSnapshot world = FrequencyMaterialWorldSnapshot.builder(level).includePath(start, end).build();

        assertThrows(IllegalStateException.class, () -> FrequencyMaterialAttenuation.factorSnapshot(world, new Vec3(100.5, 64.5, 100.5), new Vec3(110.5, 64.5, 100.5)));
    }

    @Test
    void snapshotKeepsCapturedEnablement() {
        PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.STONE.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);

        chunk(0, 0, states);

        Vec3 start = new Vec3(1.5, 64.5, 1.5);
        Vec3 end = new Vec3(3.5, 64.5, 1.5);

        FrequencyMaterialWorldSnapshot world = FrequencyMaterialWorldSnapshot.builder(level).includePath(start, end).build();

        double expected = FrequencyMaterialAttenuation.factorSnapshot(world, start, end);

        assertEquals(FrequencyMaterialRules.amplitudeFactor(6.0), expected, 1.0e-12);

        FrequencyMaterialAttenuation.setEnabled(false);

        assertFalse(world.currentOnServer());
        assertEquals(expected, FrequencyMaterialAttenuation.factorSnapshot(world, start, end), 1.0e-12);
    }

    @Test
    void reflectedSnapshotMatchesSynchronousWavePath() throws Exception {
        PalettedContainer<BlockState> states = states();

        states.set(1, 0, 0, Blocks.STONE.defaultBlockState());
        states.set(2, 0, 0, Blocks.GLASS.defaultBlockState());
        chunk(0, 0, states);

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(4.5, 64.5, 0.5);

        FrequencyMaterialWorldSnapshot snapshot = FrequencyMaterialWorldSnapshot.builder(level).includePath(start, end).build();
        MinecraftWaveMediumResolver resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), Map.of(FrequencyMaterialClassifier.STONE, new WaveMedium(3000.0, 3.0, 0.0), FrequencyMaterialClassifier.METAL, new WaveMedium(3000.0, 6.0, 0.0)), List.of());
        MinecraftCombinedWavePath wave = new MinecraftCombinedWavePath(new MinecraftMaterialPropagation(level), resolver);

        double expected = wave.evaluate(start, end, List.of(), 1000.0, true);

        double actual = CompletableFuture.supplyAsync(() -> wave.factorSnapshot(snapshot, start, end, 1000.0)).get();

        assertTrue(Double.isFinite(expected));
        assertEquals(expected, actual, 1.0e-12);
        assertTrue(snapshot.currentOnServer());
    }

    private static LevelChunkSection mockSection(PalettedContainer<BlockState> states) {
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.getStates()).thenReturn(states);
        return section;
    }

    private void assertSnapshotMatchesLive(Vec3 start, Vec3 end, double expectedLossDb) throws Exception {
        FrequencyMaterialWorldSnapshot world = FrequencyMaterialWorldSnapshot.builder(level).includePath(start, end).build();

        List<Segment> liveSegments = new ArrayList<>();
        List<Segment> snapshotSegments = new ArrayList<>();

        double live = FrequencyMaterialAttenuation.factorWithMaterials(level, start, end, (material, a, b, axes) -> liveSegments.add(new Segment(material, a, b, axes)));
        double snapshot = CompletableFuture.supplyAsync(() -> FrequencyMaterialAttenuation.factorSnapshotWithMaterials(world, start, end, (material, a, b, axes) -> snapshotSegments.add(new Segment(material, a, b, axes)))).get();

        assertEquals(FrequencyMaterialRules.amplitudeFactor(expectedLossDb), live, 1.0e-12);
        assertEquals(live, snapshot, 1.0e-12);
        assertEquals(liveSegments, snapshotSegments);
        assertTrue(world.currentOnServer());
    }

    @Test
    void eightBlockRegionsMatchLiveAttenuationAndBoundaries() throws Exception {
        PalettedContainer<BlockState> states = states();

        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                for (int x = 0; x < 8; x++) {
                    states.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }

        chunk(0, 0, states);

        assertSnapshotMatchesLive(new Vec3(0.5, 64.5, 0.5), new Vec3(7.5, 64.5, 0.5), 21.0);
        assertSnapshotMatchesLive(new Vec3(7.5, 64.5, 0.5), new Vec3(9.5, 64.5, 0.5), 1.5);
    }

    @Test
    void snapshotWaveWorkspaceResetsAcrossPathsAndThreads() throws Exception {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.STONE.defaultBlockState());
        states.set(2, 0, 0, Blocks.GLASS.defaultBlockState());
        chunk(0, 0, states);

        Vec3 materialStart = new Vec3(0.5, 64.5, 0.5);
        Vec3 materialEnd = new Vec3(4.5, 64.5, 0.5);
        Vec3 airStart = new Vec3(5.5, 64.5, 0.5);
        Vec3 airEnd = new Vec3(6.5, 64.5, 0.5);

        FrequencyMaterialWorldSnapshot snapshot = FrequencyMaterialWorldSnapshot.builder(level).includePath(materialStart, materialEnd).includePath(airStart, airEnd).build();
        MinecraftWaveMediumResolver resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), Map.of(FrequencyMaterialClassifier.STONE, new WaveMedium(3000.0, 3.0, 0.0)), List.of());
        MinecraftCombinedWavePath wave = new MinecraftCombinedWavePath(new MinecraftMaterialPropagation(level), resolver);

        double materialExpected = wave.evaluate(materialStart, materialEnd, List.of(), 1000.0, true);
        double airExpected = wave.evaluate(airStart, airEnd, List.of(), 1000.0, true);

        ExecutorService executor = Executors.newFixedThreadPool(4);

        try {
            List<CompletableFuture<Double>> tasks = new ArrayList<>();

            for (int i = 0; i < 128; i++) {
                boolean material = (i & 1) == 0;

                tasks.add(CompletableFuture.supplyAsync(() -> material ? wave.factorSnapshot(snapshot, materialStart, materialEnd, 1000.0) : wave.factorSnapshot(snapshot, airStart, airEnd, 1000.0), executor));
            }

            for (int i = 0; i < tasks.size(); i++) {
                assertEquals((i & 1) == 0 ? materialExpected : airExpected, tasks.get(i).get(), 1.0e-12);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void negativeChunkBoundaryMatchesLiveTraversal() throws Exception {
        PalettedContainer<BlockState> left = states();
        left.set(15, 0, 0, Blocks.STONE.defaultBlockState());
        chunk(-1, 0, left);

        PalettedContainer<BlockState> right = states();
        right.set(0, 0, 0, Blocks.GLASS.defaultBlockState());
        chunk(0, 0, right);

        Vec3 start = new Vec3(-1.5, 64.5, 0.5);
        Vec3 end = new Vec3(1.5, 64.5, 0.5);

        assertSnapshotMatchesLive(start, end, 3.4);
        assertSnapshotMatchesLive(end, start, 3.4);
    }

    @Test
    void diagonalChunkCornerMatchesLiveTraversal() throws Exception {
        PalettedContainer<BlockState> first = states();
        first.set(15, 0, 15, Blocks.STONE.defaultBlockState());
        chunk(-1, -1, first);

        PalettedContainer<BlockState> second = states();
        second.set(0, 0, 0, Blocks.GLASS.defaultBlockState());
        chunk(0, 0, second);

        Vec3 start = new Vec3(-0.5, 64.5, -0.5);
        Vec3 end = new Vec3(0.5, 64.5, 0.5);

        double loss = 3.4 * Math.sqrt(2.0) * 0.5;

        assertSnapshotMatchesLive(start, end, loss);
        assertSnapshotMatchesLive(end, start, loss);
    }

    @Test
    void extremeWorldCoordinatesMatchLiveTraversal() throws Exception {
        for (int boundary : new int[]{29_999_984, -29_999_984}) {
            PalettedContainer<BlockState> left = states();
            left.set(15, 0, 0, Blocks.STONE.defaultBlockState());
            chunk((boundary >> 4) - 1, 0, left);

            PalettedContainer<BlockState> right = states();
            right.set(0, 0, 0, Blocks.GLASS.defaultBlockState());
            chunk(boundary >> 4, 0, right);

            Vec3 start = new Vec3(boundary - 0.5, 64.5, 0.5);
            Vec3 end = new Vec3(boundary + 0.5, 64.5, 0.5);

            assertSnapshotMatchesLive(start, end, 1.7);
            assertSnapshotMatchesLive(end, start, 1.7);
        }
    }

    @Test
    void exactIntegerBoundaryMatchesLiveTraversal() throws Exception {
        PalettedContainer<BlockState> left = states();
        left.set(15, 0, 0, Blocks.STONE.defaultBlockState());
        chunk(0, 0, left);
        chunk(1, 0, states());

        Vec3 start = new Vec3(16.0, 64.5, 0.5);
        Vec3 end = new Vec3(14.0, 64.5, 0.5);

        assertSnapshotMatchesLive(start, end, 3.0);
    }

    @Test
    void sableShipSnapshotMatchesLivePropagationAndDetectsMovement() throws Exception {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.IRON_BLOCK.defaultBlockState());
        chunk(0, 0, states);

        Pose3d pose = new Pose3d();
        pose.position().set(8.0, 0.0, 0.0);

        BoundingBox3d bounds = new BoundingBox3d(8, 64, 0, 11, 65, 1);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.logicalPose()).thenReturn(pose);
        when(ship.boundingBox()).thenReturn(bounds);

        Vec3 start = new Vec3(8.5, 64.5, 0.5);
        Vec3 end = new Vec3(10.5, 64.5, 0.5);

        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);
        MinecraftCombinedWavePath wave = new MinecraftCombinedWavePath(propagation, new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), java.util.Map.of(FrequencyMaterialClassifier.METAL, new WaveMedium(3000.0, 3.0, 0.0)), List.of()));

        double frequency = 915_000_000.0;
        double expectedReflection = wave.evaluate(start, end, List.of(ship), frequency, true);
        double expectedAttenuation = propagation.factorForReception(start, end, List.of(ship));

        FrequencySableReceptionSnapshot snapshot = FrequencySableReceptionSnapshot.capture(level, end, List.of(start), List.of(ship));

        double actualReflection = CompletableFuture.supplyAsync(() -> snapshot.factor(0, frequency, true, wave)).get();
        double actualAttenuation = CompletableFuture.supplyAsync(() -> snapshot.factor(0, frequency, false, wave)).get();

        assertEquals(expectedReflection, actualReflection, 1.0e-12);
        assertEquals(expectedAttenuation, actualAttenuation, 1.0e-12);
        assertTrue(snapshot.currentOnServer(List.of(ship)));

        pose.position().add(2.0, 0.0, 0.0);
        bounds.set(10, 64, 0, 13, 65, 1);

        assertFalse(snapshot.currentOnServer(List.of(ship)));
        assertFalse(snapshot.currentOnServer(List.of()));
    }

    @Test
    void multipleReceiversShareWorldAndShipCapture() {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.IRON_BLOCK.defaultBlockState());
        chunk(0, 0, states);

        Pose3d pose = new Pose3d();
        pose.position().set(8.0, 0.0, 0.0);

        BoundingBox3d bounds = new BoundingBox3d(8.0, 64.0, 0.0, 11.0, 65.0, 1.0);

        SubLevelAccess ship = mock(SubLevelAccess.class);

        when(ship.logicalPose()).thenReturn(pose);
        when(ship.boundingBox()).thenReturn(bounds);

        Vec3 emitter = new Vec3(8.5, 64.5, 0.5);
        Vec3 receiverA = new Vec3(10.5, 64.5, 0.5);
        Vec3 receiverB = new Vec3(10.5, 64.5, 0.6);

        List<FrequencySableReceptionSnapshot> shared = FrequencySableReceptionSnapshot.captureBatch(level, List.of(new FrequencySableReceptionSnapshot.BatchInput(receiverA, List.of(emitter), List.of(ship)), new FrequencySableReceptionSnapshot.BatchInput(receiverB, List.of(emitter), List.of(ship))));

        assertEquals(2, shared.size());
        assertTrue(shared.get(0).sharesWorldSnapshotWith(shared.get(1)));

        FrequencySableReceptionSnapshot independentA = FrequencySableReceptionSnapshot.capture(level, receiverA, List.of(emitter), List.of(ship));
        FrequencySableReceptionSnapshot independentB = FrequencySableReceptionSnapshot.capture(level, receiverB, List.of(emitter), List.of(ship));

        double frequency = 915_000_000.0;

        assertEquals(independentA.factor(0, frequency, false, null), shared.get(0).factor(0, frequency, false, null), 1.0e-12);
        assertEquals(independentB.factor(0, frequency, false, null), shared.get(1).factor(0, frequency, false, null), 1.0e-12);

        assertTrue(shared.get(0).currentOnServer(List.of(ship)));
        assertTrue(shared.get(1).currentOnServer(List.of(ship)));

        pose.position().add(3.0, 0.0, 0.0);
        bounds.set(11.0, 64.0, 0.0, 14.0, 65.0, 1.0);

        assertFalse(shared.get(0).currentOnServer(List.of(ship)));
        assertFalse(shared.get(1).currentOnServer(List.of(ship)));
    }

    @Test
    void sharedCaptureFallsBackToIndividualPathsForLargeRegions() {
        Vec3 nearStart = new Vec3(0.5, 64.5, 0.5);
        Vec3 nearEnd = new Vec3(1.5, 64.5, 0.5);

        Vec3 farStart = new Vec3(800.5, 64.5, 800.5);
        Vec3 farEnd = new Vec3(801.5, 64.5, 800.5);

        List<FrequencySableReceptionSnapshot> snapshots = FrequencySableReceptionSnapshot.captureBatch(level, List.of(new FrequencySableReceptionSnapshot.BatchInput(nearEnd, List.of(nearStart), List.of()), new FrequencySableReceptionSnapshot.BatchInput(farEnd, List.of(farStart), List.of())));

        assertEquals(2, snapshots.size());
        assertTrue(snapshots.get(0).sharesWorldSnapshotWith(snapshots.get(1)));

        double frequency = 915_000_000.0;

        assertEquals(FrequencySableReceptionSnapshot.capture(level, nearEnd, List.of(nearStart), List.of()).factor(0, frequency, false, null), snapshots.get(0).factor(0, frequency, false, null), 1.0e-12);
        assertEquals(FrequencySableReceptionSnapshot.capture(level, farEnd, List.of(farStart), List.of()).factor(0, frequency, false, null), snapshots.get(1).factor(0, frequency, false, null), 1.0e-12);
    }

    @Test
    void sampledSnapshotPathsDistinguishUniformAndMixedMaterials() {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.STONE.defaultBlockState());
        states.set(2, 0, 0, Blocks.GLASS.defaultBlockState());
        chunk(0, 0, states);

        Vec3 mixedStart = new Vec3(0.5, 64.5, 0.5);
        Vec3 mixedEnd = new Vec3(3.5, 64.5, 0.5);
        Vec3 uniformStart = new Vec3(4.5, 64.5, 0.5);
        Vec3 uniformEnd = new Vec3(5.5, 64.5, 0.5);

        FrequencyMaterialWorldSnapshot snapshot = FrequencyMaterialWorldSnapshot.builder(level).includePath(mixedStart, mixedEnd).includePath(uniformStart, uniformEnd).build();
        FrequencyMaterialAttenuation.MaterialSegmentConsumer discard = (material, from, to, axes) -> {};

        var uniform = FrequencyMaterialAttenuation.sampleSnapshotPath(() -> FrequencyMaterialAttenuation.factorSnapshotWithMaterials(snapshot, uniformStart, uniformEnd, discard));
        var mixed = FrequencyMaterialAttenuation.sampleSnapshotPath(() -> FrequencyMaterialAttenuation.factorSnapshotWithMaterials(snapshot, mixedStart, mixedEnd, discard));

        assertTrue(uniform.complete());
        assertTrue(uniform.uniform());
        assertTrue(uniform.steps() > 0);

        assertTrue(mixed.complete());
        assertFalse(mixed.uniform());
        assertTrue(mixed.segments() >= 2);
        assertTrue(mixed.steps() > 0);

        assertEquals(FrequencyMaterialAttenuation.factorSnapshotWithMaterials(snapshot, mixedStart, mixedEnd, discard), mixed.factor(), 1.0e-12);
    }

    @Test
    void compactGridPreservesEqualLossMaterialBoundaries() {
        FrequencyMaterialRules.replaceOverrides(List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:glass"), 3.0)));
        PalettedContainer<BlockState> states = states();

        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                for (int x = 0; x < 8; x++) {
                    states.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }

        states.set(1, 1, 1, Blocks.GLASS.defaultBlockState());

        FrequencyMaterialSectionSnapshot snapshot = FrequencyMaterialSectionSnapshot.capture(mockSection(states));

        var grid = snapshot.grid();

        assertEquals(-1, grid.region2Id(1, 1, 1));
        assertEquals(FrequencyMaterialClassifier.STONE, grid.paletteCell(grid.region2Id(3, 1, 1)).material());
        assertEquals(-1, grid.region4Id(1, 1, 1));
        assertEquals(-1, grid.region8Id(1, 1, 1));

        assertEquals(FrequencyMaterialClassifier.STONE, grid.paletteCell(grid.region4Id(5, 1, 1)).material());
        assertEquals(FrequencyMaterialClassifier.AIR, grid.paletteCell(grid.region8Id(9, 1, 1)).material());

        assertEquals(3.0, grid.cell(1, 1, 1).lossDbPerMeter(), 0.0);
        assertNotEquals(grid.cell(0, 1, 1).material(), grid.cell(1, 1, 1).material());
    }

    @Test
    void compactGridIsSharedSafelyAcrossWorkers() throws Exception {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.STONE.defaultBlockState());

        FrequencyMaterialSectionSnapshot snapshot = FrequencyMaterialSectionSnapshot.capture(mockSection(states));
        ExecutorService executor = Executors.newFixedThreadPool(4);

        try {
            List<CompletableFuture<FrequencyMaterialSectionSnapshot.MaterialGrid>> tasks = new ArrayList<>();

            for (int i = 0; i < 64; i++) {
                tasks.add(CompletableFuture.supplyAsync(snapshot::grid, executor));
            }

            var first = tasks.getFirst().get();

            for (var task : tasks) {
                assertSame(first, task.get());
            }

            assertEquals(FrequencyMaterialClassifier.STONE, first.cell(1, 0, 0).material());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void compactGridMatchesLiveMixedMaterialPropagation() throws Exception {
        FrequencyMaterialRules.replaceOverrides(List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:glass"), 3.0)));
        PalettedContainer<BlockState> states = states();

        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                for (int x = 0; x < 8; x++) {
                    states.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }

        states.set(1, 1, 1, Blocks.GLASS.defaultBlockState());
        chunk(0, 0, states);

        assertSnapshotMatchesLive(new Vec3(0.5, 65.5, 1.5), new Vec3(9.5, 65.5, 1.5), 22.5);
    }

    @Test
    void equalLossSectionStillPreservesMaterialBoundaries() throws Exception {
        FrequencyMaterialRules.replaceOverrides(List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:glass"), 3.0)));
        PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.STONE.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);

        states.set(1, 0, 0, Blocks.GLASS.defaultBlockState());

        FrequencyMaterialSectionSnapshot snapshot = FrequencyMaterialSectionSnapshot.capture(mockSection(states));

        assertEquals(3.0, snapshot.sectionLoss(), 0.0);
        assertNull(snapshot.sectionMaterial());

        chunk(0, 0, states);

        assertSnapshotMatchesLive(new Vec3(0.5, 64.5, 0.5), new Vec3(2.5, 64.5, 0.5), 6.0);
    }

    @Test
    void denseWorldCoveragePreservesEverySignalPath() {
        PalettedContainer<BlockState> states = states();
        states.set(4, 0, 3, Blocks.STONE.defaultBlockState());
        states.set(8, 0, 3, Blocks.GLASS.defaultBlockState());
        chunk(0, 0, states);

        Vec3 emitterA = new Vec3(2.5, 64.5, 3.5);
        Vec3 emitterB = new Vec3(7.5, 64.5, 3.5);
        Vec3 receiverA = new Vec3(10.5, 64.5, 3.5);
        Vec3 receiverB = new Vec3(11.5, 64.5, 3.5);

        List<Vec3> emitters = new ArrayList<>(java.util.Collections.nCopies(39, emitterA));
        emitters.add(emitterB);

        List<FrequencySableReceptionSnapshot> snapshots = FrequencySableReceptionSnapshot.captureBatch(level, List.of(new FrequencySableReceptionSnapshot.BatchInput(receiverA, emitters, List.of()), new FrequencySableReceptionSnapshot.BatchInput(receiverB, emitters, List.of())));
        assertEquals(2, snapshots.size());

        assertEquals(FrequencyMaterialAttenuation.factor(level, emitterA, receiverA), snapshots.get(0).factor(0, 1000.0, false, null), 1.0e-12);
        assertEquals(FrequencyMaterialAttenuation.factor(level, emitterB, receiverA), snapshots.get(0).factor(39, 1000.0, false, null), 1.0e-12);
        assertEquals(FrequencyMaterialAttenuation.factor(level, emitterA, receiverB), snapshots.get(1).factor(0, 1000.0, false, null), 1.0e-12);
        assertEquals(FrequencyMaterialAttenuation.factor(level, emitterB, receiverB), snapshots.get(1).factor(39, 1000.0, false, null), 1.0e-12);

        assertTrue(snapshots.get(0).currentOnServer(List.of()));
        assertTrue(snapshots.get(1).currentOnServer(List.of()));
    }

    @Test
    void denseSableReceptionPreservesShipPropagation() {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.IRON_BLOCK.defaultBlockState());
        chunk(0, 0, states);

        Pose3d pose = new Pose3d();
        pose.position().set(8.0, 0.0, 0.0);

        BoundingBox3d bounds = new BoundingBox3d(8.0, 64.0, 0.0, 11.0, 65.0, 1.0);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.logicalPose()).thenReturn(pose);
        when(ship.boundingBox()).thenReturn(bounds);

        Vec3 emitter = new Vec3(8.5, 64.5, 0.5);
        Vec3 receiver = new Vec3(10.5, 64.5, 0.5);

        FrequencySableReceptionSnapshot snapshot = FrequencySableReceptionSnapshot.capture(level, receiver, java.util.Collections.nCopies(40, emitter), List.of(ship));

        double expected = new MinecraftMaterialPropagation(level).factorForReception(emitter, receiver, List.of(ship));

        assertEquals(expected, snapshot.factor(0, 1000.0, false, null), 1.0e-12);
        assertEquals(expected, snapshot.factor(39, 1000.0, false, null), 1.0e-12);

        assertTrue(snapshot.currentOnServer(List.of(ship)));
    }

    @Test
    void sparseBatchCapturesPathCorridorsInsteadOfBoundingRectangle() {
        Vec3 receiver = new Vec3(248.5, 64.5, 248.5);
        Vec3 diagonal = new Vec3(8.5, 64.5, 8.5);
        Vec3 straight = new Vec3(248.5, 64.5, 8.5);

        FrequencySableReceptionSnapshot.captureBatch(level, List.of(new FrequencySableReceptionSnapshot.BatchInput(receiver, List.of(diagonal, straight), List.of())));

        verify(source, atLeastOnce()).getChunkNow(0, 0);
        verify(source, atLeastOnce()).getChunkNow(15, 0);
        verify(source, atLeastOnce()).getChunkNow(15, 15);

        verify(source, never()).getChunkNow(0, 15);
    }

    @Test
    void exactChunkBoundaryCorridorRetainsAdjacentChunks() {
        Vec3 start = new Vec3(16.0, 64.5, -16.0);
        Vec3 end = new Vec3(16.0, 64.5, 112.0);

        FrequencyMaterialWorldSnapshot snapshot = FrequencyMaterialWorldSnapshot.builder(level).includePath(start, end).build();

        assertNotNull(snapshot.chunk(0, 0));
        assertNotNull(snapshot.chunk(1, 0));
        assertNotNull(snapshot.chunk(0, 5));
        assertNotNull(snapshot.chunk(1, 5));

        assertThrows(IllegalStateException.class, () -> snapshot.chunk(2, 5));
    }

    @Test
    void sharedDenseBoundsMatchIndividualPathSnapshots() {
        PalettedContainer<BlockState> first = states();
        first.set(4, 0, 2, Blocks.STONE.defaultBlockState());
        chunk(0, 0, first);

        PalettedContainer<BlockState> second = states();
        second.set(5, 0, 2, Blocks.IRON_BLOCK.defaultBlockState());
        chunk(1, 0, second);

        Vec3 emitterA = new Vec3(2.5, 64.5, 2.5);
        Vec3 emitterB = new Vec3(22.5, 64.5, 2.5);
        Vec3 receiverA = new Vec3(9.5, 64.5, 2.5);
        Vec3 receiverB = new Vec3(27.5, 64.5, 2.5);

        List<Vec3> emitters = List.of(emitterA, emitterB);

        List<FrequencySableReceptionSnapshot.BatchInput> inputs = List.of(new FrequencySableReceptionSnapshot.BatchInput(receiverA, emitters, List.of()), new FrequencySableReceptionSnapshot.BatchInput(receiverB, emitters, List.of()));
        List<FrequencySableReceptionSnapshot> baseline = FrequencySableReceptionSnapshot.captureBatch(level, inputs);
        List<FrequencySableReceptionSnapshot> optimized = FrequencySableReceptionSnapshot.captureBatch(level, inputs, null, emitterA, emitterB);

        for (int receiver = 0; receiver < inputs.size(); receiver++) {
            for (int emitter = 0; emitter < emitters.size(); emitter++) {
                assertEquals(baseline.get(receiver).factor(emitter, 1000.0, false, null), optimized.get(receiver).factor(emitter, 1000.0, false, null), 1.0e-12);
            }

            assertTrue(optimized.get(receiver).currentOnServer(List.of()));
        }
    }

    @Test
    void denseSnapshotSkipsNonIntersectingSableCandidate() {
        PalettedContainer<BlockState> states = states();
        states.set(4, 0, 0, Blocks.STONE.defaultBlockState());
        chunk(0, 0, states);

        Pose3d pose = new Pose3d();
        pose.position().set(40.0, 0.0, 40.0);

        BoundingBox3d bounds = new BoundingBox3d(40.0, 64.0, 40.0, 44.0, 65.0, 44.0);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.logicalPose()).thenReturn(pose);
        when(ship.boundingBox()).thenReturn(bounds);

        Vec3 first = new Vec3(0.5, 64.5, 0.5);
        Vec3 second = new Vec3(2.5, 64.5, 0.5);
        Vec3 receiver = new Vec3(7.5, 64.5, 0.5);

        var input = new FrequencySableReceptionSnapshot.BatchInput(receiver, List.of(first, second), List.of(ship));
        var baseline = FrequencySableReceptionSnapshot.captureBatch(level, List.of(input)).getFirst();
        var timings = new FrequencySableReceptionSnapshot.CaptureTimings();
        var optimized = FrequencySableReceptionSnapshot.captureBatch(level, List.of(input), timings, first, second).getFirst();

        for (int i = 0; i < 2; i++) {
            assertEquals(baseline.factor(i, 1000.0, false, null), optimized.factor(i, 1000.0, false, null), 1.0e-12);
        }

        assertEquals(1, timings.fastReceivers());
        assertEquals(2, timings.fastPaths());
        assertFalse(optimized.hasShipIntersections(0));
        assertTrue(optimized.currentOnServer(List.of(ship)));

        bounds.set(2.0, 64.0, 0.0, 5.0, 65.0, 1.0);

        assertFalse(optimized.currentOnServer(List.of(ship)));
    }

    @Test
    void denseSnapshotRetainsIntersectingSableCandidate() {
        PalettedContainer<BlockState> states = states();
        states.set(1, 0, 0, Blocks.IRON_BLOCK.defaultBlockState());
        chunk(0, 0, states);

        Pose3d pose = new Pose3d();
        pose.position().set(8.0, 0.0, 0.0);

        BoundingBox3d bounds = new BoundingBox3d(8.0, 64.0, 0.0, 11.0, 65.0, 1.0);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.logicalPose()).thenReturn(pose);
        when(ship.boundingBox()).thenReturn(bounds);

        Vec3 emitter = new Vec3(8.5, 64.5, 0.5);
        Vec3 receiver = new Vec3(10.5, 64.5, 0.5);

        var input = new FrequencySableReceptionSnapshot.BatchInput(receiver, List.of(emitter), List.of(ship));
        var baseline = FrequencySableReceptionSnapshot.captureBatch(level, List.of(input)).getFirst();
        var timings = new FrequencySableReceptionSnapshot.CaptureTimings();
        var optimized = FrequencySableReceptionSnapshot.captureBatch(level, List.of(input), timings, emitter, emitter).getFirst();

        assertEquals(0, timings.fastReceivers());
        assertEquals(1, timings.shipBlockedReceivers());
        assertTrue(optimized.hasShipIntersections(0));

        assertEquals(baseline.factor(0, 1000.0, false, null), optimized.factor(0, 1000.0, false, null), 1.0e-12);
    }

    @Test
    void denseSableCaptureIncludesReceiverOutsideEmitterBounds() {
        chunk(0, 0, states());
        chunk(1, 0, states());
        chunk(2, 0, states());

        Pose3d pose = new Pose3d();
        BoundingBox3d bounds = new BoundingBox3d(17.0, 64.0, 0.0, 20.0, 65.0, 1.0);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.logicalPose()).thenReturn(pose);
        when(ship.boundingBox()).thenReturn(bounds);

        Vec3 emitter = new Vec3(2.5, 64.5, 0.5);
        Vec3 receiver = new Vec3(40.5, 64.5, 0.5);

        var input = new FrequencySableReceptionSnapshot.BatchInput(receiver, List.of(emitter), List.of(ship));
        var baseline = FrequencySableReceptionSnapshot.captureBatch(level, List.of(input)).getFirst();
        var optimized = FrequencySableReceptionSnapshot.captureBatch(level, List.of(input), new FrequencySableReceptionSnapshot.CaptureTimings(), emitter, emitter).getFirst();

        assertTrue(optimized.hasShipIntersections(0));
        assertEquals(baseline.factor(0, 1000.0, false, null), optimized.factor(0, 1000.0, false, null), 1.0e-12);
    }

    @Test
    void mixedSableHitsAndMissesPreserveMaterialAndReflection() {
        PalettedContainer<BlockState> states = states();
        states.set(5, 0, 0, Blocks.IRON_BLOCK.defaultBlockState());
        chunk(0, 0, states);

        Pose3d pose = new Pose3d();
        BoundingBox3d bounds = new BoundingBox3d(4.0, 64.0, 0.0, 6.0, 65.0, 1.0);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.logicalPose()).thenReturn(pose);
        when(ship.boundingBox()).thenReturn(bounds);

        Vec3 receiver = new Vec3(8.5, 64.5, 0.5);
        Vec3 hit = new Vec3(0.5, 64.5, 0.5);
        Vec3 miss = new Vec3(8.5, 64.5, 5.5);

        List<Vec3> emitters = List.of(miss, hit, miss);

        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);
        MinecraftCombinedWavePath wave = new MinecraftCombinedWavePath(propagation, new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), Map.of(FrequencyMaterialClassifier.METAL, new WaveMedium(3000.0, 3.0, 0.0)), List.of()));

        double frequency = 915_000_000.0;
        FrequencySableReceptionSnapshot snapshot = FrequencySableReceptionSnapshot.capture(level, receiver, emitters, List.of(ship));

        for (int i = 0; i < emitters.size(); i++) {
            Vec3 emitter = emitters.get(i);

            assertEquals(i == 1, snapshot.hasShipIntersections(i));
            assertEquals(propagation.factorForReception(emitter, receiver, List.of(ship)), snapshot.factor(i, frequency, false, wave), 1.0e-12);
            assertEquals(wave.evaluate(emitter, receiver, List.of(ship), frequency, true), snapshot.factor(i, frequency, true, wave), 1.0e-12);
        }

        assertTrue(snapshot.currentOnServer(List.of(ship)));
    }

    @Test
    void mixedSablePathsSurviveLargeCoverageFallback() {
        chunk(0, 0, states());

        Pose3d pose = new Pose3d();
        BoundingBox3d bounds = new BoundingBox3d(4.0, 64.0, 0.0, 6.0, 65.0, 1.0);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.logicalPose()).thenReturn(pose);
        when(ship.boundingBox()).thenReturn(bounds);

        Vec3 receiver = new Vec3(8.5, 64.5, 0.5);
        Vec3 hit = new Vec3(0.5, 64.5, 0.5);
        Vec3 miss = new Vec3(800.5, 64.5, 800.5);

        var input = new FrequencySableReceptionSnapshot.BatchInput(receiver, List.of(hit, miss), List.of(ship));

        FrequencySableReceptionSnapshot snapshot = FrequencySableReceptionSnapshot.captureBatch(level, List.of(input), null, hit, miss).getFirst();

        assertTrue(snapshot.hasShipIntersections(0));
        assertFalse(snapshot.hasShipIntersections(1));

        assertDoesNotThrow(() -> snapshot.factor(0, 1000.0, false, null));
        assertDoesNotThrow(() -> snapshot.factor(1, 1000.0, false, null));
    }

    @Test
    void deferredDenseShipPathsMatchExactSnapshotOffThread() throws Exception {
        PalettedContainer<BlockState> states = states();
        states.set(5, 0, 0, Blocks.IRON_BLOCK.defaultBlockState());
        chunk(0, 0, states);

        Pose3d pose = new Pose3d();
        BoundingBox3d bounds = new BoundingBox3d(4.0, 64.0, 0.0, 6.0, 65.0, 1.0);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.logicalPose()).thenReturn(pose);
        when(ship.boundingBox()).thenReturn(bounds);

        Vec3 hit = new Vec3(0.5, 64.5, 0.5);
        Vec3 miss = new Vec3(8.5, 64.5, 5.5);
        Vec3 receiver = new Vec3(8.5, 64.5, 0.5);

        var input = new FrequencySableReceptionSnapshot.BatchInput(receiver, List.of(hit, miss), List.of(ship));
        var exact = FrequencySableReceptionSnapshot.captureBatch(level, List.of(input)).getFirst();
        var deferred = FrequencySableReceptionSnapshot.captureBatch(level, List.of(input), new FrequencySableReceptionSnapshot.CaptureTimings(), hit, miss).getFirst();

        assertTrue(deferred.currentOnServer(List.of(ship)));

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var propagation = new MinecraftMaterialPropagation(level);
            var resolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), Map.of(FrequencyMaterialClassifier.METAL, new WaveMedium(3000.0, 3.0, 0.0)), List.of());

            var wave = new MinecraftCombinedWavePath(propagation, resolver);

            for (int i = 0; i < 2; i++) {
                final int index = i;

                double exactAttenuation = exact.factor(index, 915_000_000.0, false, wave);
                double exactReflection = exact.factor(index, 915_000_000.0, true, wave);

                var result = CompletableFuture.supplyAsync(() -> new double[]{
                        deferred.factor(index, 915_000_000.0, false, wave),
                        deferred.factor(index, 915_000_000.0, true, wave)
                }, executor).get();

                assertEquals(exactAttenuation, result[0], 1.0e-12);
                assertEquals(exactReflection, result[1], 1.0e-12);
                assertEquals(index == 0, deferred.hasShipIntersections(index));
            }
        } finally {
            executor.shutdownNow();
        }

        pose.position().set(0.0, 0.0, 8.0);
        bounds.set(4.0, 64.0, 8.0, 6.0, 65.0, 9.0);

        assertFalse(deferred.currentOnServer(List.of(ship)));
    }
}
