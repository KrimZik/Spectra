package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.material.FrequencyMaterialAttenuation;
import com.krimzik.spectra.api.consumers.FrequencyReceptionConsumer;
import com.krimzik.spectra.api.material.FrequencyMaterialRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
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
        Terrain terrain = Terrain.LOADED;

        World() {
            server = mock(MinecraftServer.class, withSettings().stubOnly());
            level = mock(ServerLevel.class, withSettings().stubOnly());

            when(level.getServer()).thenReturn(server);
            when(server.isSameThread()).thenReturn(true);
            when(level.getMinBuildHeight()).thenReturn(-64);
            when(level.getMaxBuildHeight()).thenReturn(320);

            when(level.hasChunk(anyInt(), anyInt())).thenAnswer(call -> {
                int cx = call.getArgument(0);
                int cz = call.getArgument(1);

                return switch (terrain) {
                    case LOADED -> true;
                    case UNLOADED -> false;
                    case MIXED -> cz == 0 && (cx == 0 || cx == 2);
                };
            });

            when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
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
        FrequencyMaterialAttenuation.setLoadedBlockBudget(1024);
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

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(world.level), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));

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
    void profileMockitoMaterialOperationCosts() {
        World world = new World();
        world.terrain = Terrain.LOADED;

        com.sun.management.ThreadMXBean memory = memoryBean();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(16, 64, 0);

        for (int count : new int[]{1_000, 10_000}) {
            Runnable chunkLookup = () -> {
                int found = 0;

                for (int i = 0; i < count; i++) {
                    if (world.level.hasChunk(1, 0)) {
                        found++;
                    }
                }

                blackhole = found;
            };

            Runnable blockLookup = () -> {
                int found = 0;

                for (int i = 0; i < count; i++) {
                    if (world.level.getBlockState(pos) != null) {
                        found++;
                    }
                }

                blackhole = found;
            };

            Runnable classification = () -> {
                double loss = 0.0;
                for (int i = 0; i < count; i++) {
                    loss += FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState());
                }

                blackhole = loss;
            };

            Runnable combined = () -> {
                double loss = 0.0;
                for (int i = 0; i < count; i++) {
                    loss += FrequencyMaterialRules.decibelsPerMeter(world.level.getBlockState(pos));
                }

                blackhole = loss;
            };

            Measurement chunks = measure(chunkLookup, count, memory);
            Measurement blocks = measure(blockLookup, count, memory);
            Measurement rules = measure(classification, count, memory);
            Measurement both = measure(combined, count, memory);

            assertTrue(Double.isFinite(blackhole));

            System.out.printf(Locale.ROOT, "[FREQ-MATERIAL-OPS] count=%d " + "chunk=%.3f us/%.1f B " + "block=%.3f us/%.1f B " + "rules=%.3f us/%.1f B " + "combined=%.3f us/%.1f B%n", count, chunks.micros(), chunks.bytes(), blocks.micros(), blocks.bytes(), rules.micros(), rules.bytes(), both.micros(), both.bytes());
        }
    }

    @Test
    void profileTraversalArithmeticVersusWorldAccess() {
        World world = new World();
        world.terrain = Terrain.LOADED;

        FrequencyMaterialAttenuation.setEnabled(true);
        FrequencyMaterialAttenuation.setLoadedBlockBudget(1024);

        com.sun.management.ThreadMXBean memory = memoryBean();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int count : new int[]{1_000, 5_000}) {
            Runnable arithmetic = () -> {
                double checksum = 0.0;

                for (int i = 0; i < count; i++) {
                    double dx = END.x - START.x;
                    double distance = Math.abs(dx);
                    double step = 1.0 / dx;

                    int x = (int) Math.floor(START.x);
                    double tx = (x + 1.0 - START.x) * step;
                    double previous = 0.0;
                    double loss = 0.0;

                    while (previous < 1.0) {
                        double next = Math.min(1.0, tx);

                        loss += 3.0 * distance * (next - previous);
                        previous = next;
                        if (next >= 1.0) {
                            break;
                        }

                        x++;
                        tx += step;
                    }

                    checksum += FrequencyMaterialRules.amplitudeFactor(loss);
                }

                blackhole = checksum;
            };

            Runnable worldAccess = () -> {
                double checksum = 0.0;

                for (int i = 0; i < count; i++) {
                    for (int cx = 0; cx <= 2; cx++) {
                        world.level.hasChunk(cx, 0);
                    }

                    for (int x = 15; x <= 32; x++) {
                        pos.set(x, 64, 0);

                        checksum += FrequencyMaterialRules.decibelsPerMeter(world.level.getBlockState(pos));
                    }
                }

                blackhole = checksum;
            };

            Runnable fullTraversal = () -> {
                double checksum = 0.0;

                for (int i = 0; i < count; i++) {
                    checksum += FrequencyMaterialAttenuation.factor(world.level, START, END);
                }

                blackhole = checksum;
            };

            Measurement math = measure(arithmetic, count, memory);
            Measurement access = measure(worldAccess, count, memory);
            Measurement full = measure(fullTraversal, count, memory);

            assertTrue(Double.isFinite(blackhole));

            System.out.printf(Locale.ROOT, "[FREQ-MATERIAL-DDA] count=%d " + "math=%.3f us/%.1f B " + "world=%.3f us/%.1f B " + "full=%.3f us/%.1f B%n", count, math.micros(), math.bytes(), access.micros(), access.bytes(), full.micros(), full.bytes());
        }
    }
}
