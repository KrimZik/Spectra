package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.material.FrequencyMaterialAttenuation;
import com.krimzik.spectra.api.consumers.FrequencyReceptionConsumer;
import com.krimzik.spectra.api.material.FrequencyMaterialRules;
import com.krimzik.spectra.api.material.FrequencySableMaterialAttenuation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyMaterialBenchmarkTest {
    private static final Vec3 START = new Vec3(15.5, 64.5, 0.5);
    private static final Vec3 END = new Vec3(32.5, 64.5, 0.5);
    private static final int[] COUNTS = {100, 1_000, 5_000, 10_000};
    private static final double RANGE = 64.0;
    private static volatile double blackhole;

    private enum Terrain {
        LOADED,
        UNLOADED,
        MIXED
    }

    private record Emitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {}
    private record Receiver(Vec3 position, double tunedFrequencyHz, double toleranceHz) implements FrequencyReceiver {}
    private record Measurement(double micros, double bytes) {}

    private static final class World {
        final ServerLevel level;
        final MinecraftServer server;
        final ServerChunkCache chunkSource;
        final LevelChunk chunk;

        Terrain terrain = Terrain.LOADED;

        World() {
            server = mock(MinecraftServer.class, withSettings().stubOnly());
            level = mock(ServerLevel.class, withSettings().stubOnly());
            chunkSource = mock(ServerChunkCache.class, withSettings().stubOnly());
            chunk = mock(LevelChunk.class, withSettings().stubOnly());

            when(level.getServer()).thenReturn(server);
            when(server.isSameThread()).thenReturn(true);
            when(level.getMinBuildHeight()).thenReturn(-64);
            when(level.getMaxBuildHeight()).thenReturn(320);
            when(level.getChunkSource()).thenReturn(chunkSource);

            when(chunkSource.getChunkNow(anyInt(), anyInt())).thenAnswer(call -> {
                int cx = call.getArgument(0);
                int cz = call.getArgument(1);

                return switch (terrain) {
                    case LOADED -> chunk;
                    case UNLOADED -> null;
                    case MIXED -> cz == 0 && (cx == 0 || cx == 2) ? chunk : null;
                };
            });

            when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
                BlockPos pos = call.getArgument(0);

                if (terrain == Terrain.MIXED) {
                    return pos.getX() == 15 || pos.getX() == 32 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                }

                return Blocks.STONE.defaultBlockState();
            });
        }
    }

    @AfterEach
    void cleanup() {
        FrequencyMaterialAttenuation.setEnabled(false);
    }

    private static com.sun.management.ThreadMXBean memoryBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)) {
            return null;
        }

        if (!bean.isThreadAllocatedMemorySupported()) {
            return null;
        }

        if (!bean.isThreadAllocatedMemoryEnabled()) {
            try {
                bean.setThreadAllocatedMemoryEnabled(true);
            } catch (SecurityException | UnsupportedOperationException ex) {
                return null;
            }
        }

        return bean;
    }

    private static int samples(int count) {
        return count >= 5_000 ? 2 : 3;
    }

    private static Measurement measure(Runnable operation, int count, com.sun.management.ThreadMXBean memory) {
        int trials = samples(count);
        double[] times = new double[trials];
        double[] allocations = new double[trials];

        long threadId = Thread.currentThread().threadId();

        operation.run();

        for (int i = 0; i < trials; i++) {
            long beforeBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();
            operation.run();
            long elapsed = System.nanoTime() - start;
            long afterBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

            times[i] = elapsed / (count * 1_000.0);
            allocations[i] = memory == null ? -1.0 : (afterBytes - beforeBytes) / (double) count;
        }

        Arrays.sort(times);
        Arrays.sort(allocations);

        return new Measurement(times[trials / 2], allocations[trials / 2]);
    }

    private static void print(String mode, int count, Terrain terrain, Measurement baseline, Measurement material) {
        System.out.printf(Locale.ROOT, "[FREQ-MATERIAL] mode=%s count=%d terrain=%s " + "baseline=%.3f us/path material=%.3f us/path " + "overhead=%.3f us/path ratio=%.2fx " + "baselineAlloc=%.1f B/path materialAlloc=%.1f B/path%n", mode, count, terrain, baseline.micros(), material.micros(), material.micros() - baseline.micros(), material.micros() / Math.max(0.000001, baseline.micros()), baseline.bytes(), material.bytes());
    }

    @Test
    void profileMaterialTraversalScaling() {
        World world = new World();
        com.sun.management.ThreadMXBean memory = memoryBean();

        for (int count : COUNTS) {
            for (Terrain terrain : Terrain.values()) {
                world.terrain = terrain;

                Runnable operation = () -> {
                    double sum = 0.0;
                    for (int i = 0; i < count; i++) {
                        sum += FrequencyMaterialAttenuation.factor(world.level, START, END);
                    }

                    blackhole = sum;
                };

                FrequencyMaterialAttenuation.setEnabled(false);
                Measurement baseline = measure(operation, count, memory);

                FrequencyMaterialAttenuation.setEnabled(true);
                Measurement material = measure(operation, count, memory);

                assertTrue(Double.isFinite(blackhole));
                print("TRAVERSAL", count, terrain, baseline, material);
            }
        }
    }

    @Test
    void profileRealReceptionScaling() {
        World world = new World();
        com.sun.management.ThreadMXBean memory = memoryBean();

        FrequencySignal signal = new FrequencySignal(440.0, 1.0, 0.0, 343.0);
        Receiver receiver = new Receiver(END, 440.0, 0.0);

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class);
        MockedStatic<FrequencySableMaterialAttenuation> sable = mockStatic(FrequencySableMaterialAttenuation.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(world.level), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));
            sable.when(() -> FrequencySableMaterialAttenuation.factor(eq(world.level), any(Vec3.class), any(Vec3.class))).thenReturn(1.0);

            for (int count : COUNTS) {
                FrequencyEngine engine = new FrequencyEngine(world.level);

                try {
                    for (int i = 0; i < count; i++) {
                        engine.register(new Emitter(START, signal));
                    }

                    int[] received = {0};
                    double[] total = {0.0};

                    FrequencyReceptionConsumer consumer =
                            (emitter, wave, distanceSquared, amplitude, travelTime) -> {
                                received[0]++;
                                total[0] += amplitude;
                            };

                    Runnable operation = () -> {
                        received[0] = 0;
                        total[0] = 0.0;
                        engine.forEachReceivedInRange(receiver, RANGE, consumer);

                        assertEquals(count, received[0]);
                        blackhole = total[0];
                    };

                    for (Terrain terrain : Terrain.values()) {
                        world.terrain = terrain;

                        FrequencyMaterialAttenuation.setEnabled(false);
                        Measurement baseline = measure(operation, count, memory);
                        space.clearInvocations();

                        FrequencyMaterialAttenuation.setEnabled(true);
                        Measurement material = measure(operation, count, memory);

                        space.clearInvocations();
                        assertTrue(Double.isFinite(blackhole));

                        print("RECEPTION", count, terrain, baseline, material);
                    }
                } finally {
                    engine.shutdown();
                }
            }
        }
    }

    @Test
    void profileEmptySectionFastPath() {
        ServerLevel level = mock(ServerLevel.class, withSettings().stubOnly());
        ServerChunkCache chunkSource = mock(ServerChunkCache.class, withSettings().stubOnly());
        LevelChunk chunk = mock(LevelChunk.class, withSettings().stubOnly());
        LevelChunkSection section = mock(LevelChunkSection.class, withSettings().stubOnly());

        LevelChunkSection[] sections = new LevelChunkSection[24];
        sections[8] = section;

        int[] mode = {0};

        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);
        when(level.getChunkSource()).thenReturn(chunkSource);

        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getSections()).thenReturn(sections);

        when(section.hasOnlyAir()).thenAnswer(call -> mode[0] == 0);
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> mode[0] == 2 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());

        Vec3 start = new Vec3(0.5, 64.5, 0.5);
        Vec3 end = new Vec3(128.5, 64.5, 0.5);

        String[] names = {
                "EMPTY_SECTION",
                "AIR_IN_NONEMPTY_SECTION",
                "SOLID_SECTION"
        };

        FrequencyMaterialRules.replaceOverrides(java.util.List.of());
        FrequencyMaterialAttenuation.setEnabled(true);

        com.sun.management.ThreadMXBean memory = memoryBean();

        for (int count : new int[]{100, 1_000}) {
            for (int scenario = 0; scenario < names.length; scenario++) {
                mode[0] = scenario;

                double expected = scenario == 2 ? FrequencyMaterialRules.amplitudeFactor(384.0) : 1.0;
                double actual = FrequencyMaterialAttenuation.factor(level, start, end);
                assertEquals(expected, actual, 1.0e-12);

                Runnable operation = () -> {
                    double sum = 0.0;

                    for (int i = 0; i < count; i++) {
                        sum += FrequencyMaterialAttenuation.factor(level, start, end);
                    }

                    blackhole = sum;
                };

                Measurement result = measure(operation, count, memory);
                assertTrue(Double.isFinite(blackhole));

                System.out.printf(Locale.ROOT, "[FREQ-EMPTY-SECTION] count=%d scenario=%s " + "us/path=%.3f bytes/path=%.1f%n", count, names[scenario], result.micros(), result.bytes());
            }
        }
    }
}
