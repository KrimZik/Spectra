package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.consumers.FrequencyMatchConsumer;
import com.krimzik.spectra.api.consumers.FrequencyReceptionConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class FrequencyEngineQueryBenchmarkTest {
    private static final double BASE_HZ = 915_000_000.0;
    private static final double RANGE_METERS = 512.0;
    private static final double WINDOW_SECONDS = 0.25;
    private static final long END_TICK = 4_000_000_001L;
    private static volatile double blackhole;
    private static final boolean FULL_BENCHMARK = Boolean.getBoolean("spectra.fullQueryBenchmark");

    private enum Scenario { SHARED, DISTINCT_ALL, DISTINCT_NARROW }
    private enum Stage { LOOKUP, RAW_DISTANCE, PROJECTION_CONTROL, SPATIAL, RECEPTION, FINITE_WINDOW }

    private record Emitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {
        @Override public boolean isEmitting() { return true; }
    }

    private record Receiver(double tunedFrequencyHz, double toleranceHz, Vec3 position) implements FrequencyReceiver {}
    private record Fixture(ServerLevel level, FrequencyEngine engine, Receiver receiver, Emitter[] emitters, int expectedMatches) {}

    @Test
    void receiverProjectionHappensOncePerRangeQuery() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        Fixture fixture = createFixture(level, 100, Scenario.SHARED);
        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));

            assertEquals(100.0, evaluate(fixture, Stage.SPATIAL), 0.0);
            space.verify(() -> FrequencySpace.worldPosition(level, Vec3.ZERO), times(1));
        }
        fixture.engine().shutdown();
    }

    private static Fixture createFixture(ServerLevel level, int count, Scenario scenario) {
        FrequencyEngine engine = new FrequencyEngine(level);
        Emitter[] emitters = new Emitter[count];
        boolean distinct = scenario != Scenario.SHARED;
        double tolerance = scenario == Scenario.DISTINCT_ALL ? count - 1.0 : 0.0;
        Receiver receiver = new Receiver(BASE_HZ, tolerance, Vec3.ZERO);

        for (int i = 0; i < count; i++) {
            double hz = BASE_HZ + (distinct ? i : 0);
            Vec3 pos = new Vec3(1 + (i % 9), (i / 9) % 11, (i / 99) % 7);
            Emitter emitter = new Emitter(pos, new FrequencySignal(hz, 1.0, (i % 7) * 0.07, 343.0));
            emitters[i] = emitter;
            engine.register(emitter);
        }
        int expected = scenario == Scenario.DISTINCT_NARROW ? 1 : count;
        return new Fixture(level, engine, receiver, emitters, expected);
    }

    private static double evaluate(Fixture f, Stage stage) {
        double[] sum = {0.0};
        switch (stage) {
            case LOOKUP -> f.engine().forEachMatching(f.receiver(), (emitter, signal) -> sum[0]++);
            case RAW_DISTANCE -> {
                Vec3 receiverPos = f.receiver().position();
                f.engine().forEachMatching(f.receiver(), (emitter, signal) -> sum[0] += emitter.position().distanceToSqr(receiverPos));
            }
            case PROJECTION_CONTROL -> {
                Vec3 receiverWorld = FrequencySpace.worldPosition(f.level(), f.receiver().position());

                f.engine().forEachMatching(f.receiver(), (emitter, signal) -> {
                    Vec3 world = FrequencySpace.worldPosition(f.level(), emitter.position());
                    sum[0] += world.distanceToSqr(receiverWorld);
                });
            }
            case SPATIAL -> f.engine().forEachMatchingInRange(f.receiver(), RANGE_METERS, (emitter, signal, squaredDistance) -> sum[0]++);
            case RECEPTION -> f.engine().forEachReceivedInRange(f.receiver(), RANGE_METERS, (emitter, signal, squaredDistance, amplitude, travelTime) -> sum[0] += amplitude);
            case FINITE_WINDOW -> {
                return f.engine().sampleInterferenceWindowMeanSquareInRange(f.receiver(), RANGE_METERS, END_TICK, WINDOW_SECONDS);
            }
        }
        return sum[0];
    }

    private static void benchmark(Fixture fixture, Scenario scenario, Stage stage) {
        int count = fixture.emitters().length;
        int warmup = count == 1_000 ? 6 : 30;
        int iterations = count == 1_000 ? 8 : count == 100 ? 40 : 100;

        for (int i = 0; i < warmup; i++) evaluate(fixture, stage);

        double[] samples = new double[3];
        double checksum = 0.0;
        for (int sample = 0; sample < samples.length; sample++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) checksum += evaluate(fixture, stage);
            samples[sample] = (System.nanoTime() - start) / (iterations * 1_000.0);
        }

        Arrays.sort(samples);
        blackhole = checksum;
        assertTrue(Double.isFinite(checksum));

        System.out.printf(Locale.ROOT, "[FREQ-QUERY-MOCK] emitters=%d matched=%d scenario=%s stage=%s median=%.3f us/query%n", count, fixture.expectedMatches(), scenario, stage, samples[1]);
    }

    @Test
    void profileRealRangeQueryScaling() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));

            com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;

            if (memory != null && !memory.isThreadAllocatedMemoryEnabled()) {
                memory.setThreadAllocatedMemoryEnabled(true);
            }

            long threadId = Thread.currentThread().threadId();
            double range = 128.0;

            int[] emitterCounts = FULL_BENCHMARK ? new int[]{1_000, 5_000, 10_000} : new int[]{1_000, 5_000};
            Scenario[] scenarios = FULL_BENCHMARK ? Scenario.values() : new Scenario[]{Scenario.SHARED, Scenario.DISTINCT_NARROW};

            for (int emitterCount : emitterCounts) {
                for (Scenario scenario : scenarios) {
                    FrequencyEngine engine = new FrequencyEngine(level);

                    boolean distinct = scenario != Scenario.SHARED;
                    double tolerance = scenario == Scenario.DISTINCT_ALL ? emitterCount - 1.0 : 0.0;

                    for (int i = 0; i < emitterCount; i++) {
                        boolean nearby = i % 10 == 0;
                        int nearIndex = i / 10;

                        Vec3 position = nearby ? new Vec3(5 + nearIndex % 16, 0, 5 + (nearIndex / 16) % 16) : new Vec3(2048 + i % 31, 0, 2048 + i % 29);
                        double hz = BASE_HZ + (distinct ? i : 0);
                        engine.register(new Emitter(position, new FrequencySignal(hz, 1.0, 0.0, 343.0)));
                    }

                    int expectedNearby = scenario == Scenario.DISTINCT_NARROW ? 1 : emitterCount / 10;
                    for (int receiverCount : FULL_BENCHMARK ? new int[]{1, 10, 50} : new int[]{1}) {
                        Receiver[] receivers = new Receiver[receiverCount];
                        for (int i = 0; i < receiverCount; i++) {
                            receivers[i] = new Receiver(BASE_HZ, tolerance, new Vec3((i % 5) * 3.0, 0.0, (i / 5) * 3.0));
                        }

                        int[] spatialHits = {0};
                        engine.forEachMatchingInRange(receivers[0], range, (emitter, signal, distanceSquared) -> spatialHits[0]++);
                        assertEquals(expectedNearby, spatialHits[0]);

                        int[] receptionHits = {0};
                        engine.forEachReceivedInRange(receivers[0], range, (emitter, signal, distanceSquared, amplitude, travelTime) -> receptionHits[0]++);
                        assertEquals(expectedNearby, receptionHits[0]);
                        space.clearInvocations();

                        for (Stage stage : new Stage[]{Stage.LOOKUP, Stage.RAW_DISTANCE, Stage.PROJECTION_CONTROL, Stage.SPATIAL, Stage.RECEPTION}) {
                            double[] sink = {0.0};

                            BiConsumer<FrequencyEmitter, FrequencySignal> lookup = (emitter, signal) -> sink[0]++;
                            FrequencyMatchConsumer spatial = (emitter, signal, distanceSquared) -> sink[0]++;
                            FrequencyReceptionConsumer reception = (emitter, signal, distanceSquared, amplitude, travelTime) -> sink[0] += amplitude + travelTime;

                            Runnable[] queries = new Runnable[receiverCount];

                            for (int i = 0; i < receiverCount; i++) {
                                Receiver receiver = receivers[i];
                                queries[i] = switch (stage) {
                                    case LOOKUP -> () -> engine.forEachMatching(receiver, lookup);
                                    case RAW_DISTANCE -> () -> {
                                        Vec3 receiverPosition = receiver.position();
                                        engine.forEachMatching(receiver, (emitter, signal) -> sink[0] += emitter.position().distanceToSqr(receiverPosition));
                                    };

                                    case PROJECTION_CONTROL -> () -> {
                                        Vec3 receiverWorld = FrequencySpace.worldPosition(level, receiver.position());
                                        engine.forEachMatching(receiver, (emitter, signal) -> {
                                            Vec3 emitterWorld = FrequencySpace.worldPosition(level, emitter.position());
                                            sink[0] += emitterWorld.distanceToSqr(receiverWorld);
                                        });
                                    };

                                    case SPATIAL -> () -> engine.forEachMatchingInRange(receiver, range, spatial);
                                    case RECEPTION -> () -> engine.forEachReceivedInRange(receiver, range, reception);

                                    default -> throw new AssertionError(stage);
                                };
                            }

                            for (int warmup = 0; warmup < (FULL_BENCHMARK ? 10 : 2); warmup++) {
                                for (Runnable query : queries) {
                                    query.run();
                                    space.clearInvocations();
                                }
                            }

                            int batches = FULL_BENCHMARK ? Math.max(2, 100 / receiverCount) : 3;
                            double[] timings = new double[FULL_BENCHMARK ? 5 : 3];
                            long allocated = 0L;

                            for (int sample = 0; sample < timings.length; sample++) {
                                long elapsed = 0L;

                                for (int batch = 0; batch < batches; batch++) {
                                    for (Runnable query : queries) {
                                        long bytesBefore = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

                                        long start = System.nanoTime();
                                        query.run();
                                        elapsed += System.nanoTime() - start;

                                        if (memory != null) {
                                            allocated += memory.getThreadAllocatedBytes(threadId) - bytesBefore;
                                        }

                                        space.clearInvocations();
                                    }
                                }

                                int queryCount = batches * receiverCount;
                                timings[sample] = elapsed / (queryCount * 1000.0);
                            }

                            Arrays.sort(timings);
                            blackhole = sink[0];
                            assertTrue(Double.isFinite(blackhole));

                            double bytesPerQuery = memory == null ? -1.0 : allocated / (double) (timings.length * batches * receiverCount);
                            System.out.printf(
                                    Locale.ROOT,
                                    "[FREQ] emitters=%d scenario=%s " + "nearby=%d receivers=%d stage=%s " + "median=%.3f us/query " + "allocated=%.1f bytes/query%n",
                                    emitterCount,
                                    scenario,
                                    expectedNearby,
                                    receiverCount,
                                    stage,
                                    timings[timings.length / 2],
                                    bytesPerQuery);
                        }
                    }

                    engine.shutdown();
                }
            }
        }
    }
}
