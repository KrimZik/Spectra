package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyEngineMixedScaleTest {
    private static final int TOTAL = 50_000;
    private static final int FIXED = 25_000;
    private static final int QUERIES = 80;
    private static final int BANDS = 128;
    private static final double RANGE = 0.25;

    private record Tx(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {}

    private static final class Rx implements FrequencyReceiver {
        Vec3 location;
        final double hz;

        Rx(Vec3 location, double hz) {
            this.location = location;
            this.hz = hz;
        }

        @Override public Vec3 position() { return location; }
        @Override public double tunedFrequencyHz() { return hz; }
        @Override public double toleranceHz() { return 0.0; }
    }

    private record Measurement(double milliseconds, double bytesPerQuery) {}

    private static Measurement measure(MockedStatic<FrequencySpace> space, IntConsumer operation) {
        for (int i = 0; i < 12; i++) {
            operation.accept(i);
        }

        space.clearInvocations();

        var mx = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean memory = mx instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (memory != null && !memory.isThreadAllocatedMemoryEnabled()) {
            memory.setThreadAllocatedMemoryEnabled(true);
        }

        long threadId = Thread.currentThread().threadId();
        long beforeBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

        long start = System.nanoTime();
        for (int i = 0; i < QUERIES; i++) {
            operation.accept(i);
        }

        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        long afterBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

        space.clearInvocations();
        double bytesPerQuery = beforeBytes < 0 || afterBytes < 0 ? Double.NaN : (afterBytes - beforeBytes) / (double) QUERIES;

        return new Measurement(elapsedMs, bytesPerQuery);
    }

    private static void verifyQuery(FrequencyEngine engine, Tx[] transmitters, Rx[] receivers, int index, boolean live) {
        int[] hits = {0};
        java.util.function.Consumer<FrequencyReceiver> consumer =
                found -> {
                    assertSame(receivers[index], found);
                    hits[0]++;
                };

        if (live) {
            engine.forEachReceiverSubscribedLiveInRange(transmitters[index].signal().frequencyHz(), transmitters[index].position(), RANGE, consumer);
        } else {
            engine.forEachSubscribedReceiverForEmitterInRange(transmitters[index], RANGE, consumer);
        }

        assertEquals(1, hits[0], "Discovery mismatch at receiver " + index);
    }

    @Test
    void mixedFixedAndMovingReceiversAtScale() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);

        Tx[] transmitters = new Tx[TOTAL];
        Rx[] receivers = new Rx[TOTAL];

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));
            long start = System.nanoTime();

            for (int i = 0; i < TOTAL; i++) {
                double hz = 100_000.0 + (i % BANDS) * 100.0;
                Vec3 position = new Vec3(i * 2.0, 0.0, 0.0);
                transmitters[i] = new Tx(position, new FrequencySignal(hz, 1.0, 0.0, 343.0));
                receivers[i] = new Rx(position, hz);
                engine.register(transmitters[i]);

                if (i < FIXED) {
                    engine.registerReceiverChunkBound(new BlockPos(i * 2, 0, 0), receivers[i]);
                    engine.subscribeFixedWorldReceiver(receivers[i]);
                } else {
                    engine.registerReceiver(receivers[i]);
                    engine.subscribeReceiver(receivers[i]);
                }
            }

            double registrationMs = (System.nanoTime() - start) / 1_000_000.0;

            assertEquals(TOTAL, engine.emitterCount());
            assertEquals(TOTAL, engine.receiverCount());
            assertEquals(FIXED, engine.pendingReceiverWorldPositionRefreshCount());

            start = System.nanoTime();

            int ticks = 0;
            int updated = 0;

            while (engine.pendingReceiverWorldPositionRefreshCount() > 0) {
                int n = engine.processDirtyReceiverWorldPositionsForTick(128);

                assertTrue(n > 0 && n <= 128);

                updated += n;
                ticks++;
            }

            double refreshMs = (System.nanoTime() - start) / 1_000_000.0;

            assertEquals(FIXED, updated);
            assertEquals(196, ticks);

            space.clearInvocations();

            ArrayList<FrequencyReceiver> snapshots = new ArrayList<>();
            engine.forEachReceiverSubscribedSnapshotInRange(transmitters[FIXED].signal().frequencyHz(), transmitters[FIXED].position(), RANGE, snapshots::add);
            assertTrue(snapshots.isEmpty(), "Live-only receiver was automatically indexed");

            Measurement fixedHybrid = measure(space, q -> verifyQuery(engine, transmitters, receivers, (q * 397) % FIXED, false));
            Measurement movingHybrid = measure(space, q -> verifyQuery(engine, transmitters, receivers, FIXED + (q * 397) % FIXED, false));
            Measurement fixedLive = measure(space, q -> verifyQuery(engine, transmitters, receivers, (q * 397) % FIXED, true));
            Measurement movingLive = measure(space, q -> verifyQuery(engine, transmitters, receivers, FIXED + (q * 397) % FIXED, true));

            final int[] frequencyMatches = {0};

            Measurement frequencyOnly = measure(space, q -> {
                int index = FIXED + (q * 397) % FIXED;
                frequencyMatches[0] = 0;

                engine.forEachReceiverSubscribedTo(transmitters[index].signal().frequencyHz(), ignored -> frequencyMatches[0]++);

                assertTrue(frequencyMatches[0] >= TOTAL / BANDS);
                assertTrue(frequencyMatches[0] <= TOTAL / BANDS + 1);
            });

            Measurement distanceOnly = measure(space, q -> {
                int index = FIXED + (q * 397) % FIXED;
                int band = index % BANDS;
                int first = FIXED + Math.floorMod(band - FIXED % BANDS, BANDS);

                Vec3 center = transmitters[index].position();
                int hits = 0;

                for (int i = first; i < TOTAL; i += BANDS) {
                    Vec3 point = receivers[i].position();

                    double dx = point.x - center.x;
                    double dy = point.y - center.y;
                    double dz = point.z - center.z;

                    if (Math.abs(dx) > RANGE || Math.abs(dy) > RANGE || Math.abs(dz) > RANGE) {
                        continue;
                    }

                    if (Math.fma(dx, dx, Math.fma(dy, dy, dz * dz)) <= RANGE * RANGE) {
                        hits++;
                    }
                }

                assertEquals(1, hits);
            });

            Measurement movingProjectionOnly = measure(space, q -> {
                int index = FIXED + (q * 397) % FIXED;
                int band = index % BANDS;
                int first = FIXED + Math.floorMod(band - FIXED % BANDS, BANDS);

                Vec3 source = FrequencySpace.worldPosition(level, transmitters[index].position());
                double checksum = source.x;

                for (int i = first; i < TOTAL; i += BANDS) {
                    checksum += FrequencySpace.worldPosition(level, receivers[i].position()).x;
                }

                assertTrue(Double.isFinite(checksum));
            });

            Measurement allProjectionOnly = measure(space, q -> {
                int index = FIXED + (q * 397) % FIXED;
                int band = index % BANDS;

                Vec3 source = FrequencySpace.worldPosition(level, transmitters[index].position());

                double checksum = source.x;
                for (int i = band; i < TOTAL; i += BANDS) {
                    checksum += FrequencySpace.worldPosition(level, receivers[i].position()).x;
                }

                assertTrue(Double.isFinite(checksum));
            });

            System.out.printf(Locale.ROOT, "[FREQ-PROFILE] 80 queries " + "frequencyOnly=%.3f ms " + "distanceOnly=%.3f ms " + "movingMockOnly=%.3f ms " + "allMockOnly=%.3f ms%n", frequencyOnly.milliseconds(), distanceOnly.milliseconds(), movingProjectionOnly.milliseconds(), allProjectionOnly.milliseconds());
            System.out.printf(Locale.ROOT, "[FREQ-PROFILE] bytes/query " + "frequencyOnly=%.0f " + "distanceOnly=%.0f " + "movingMockOnly=%.0f " + "allMockOnly=%.0f%n", frequencyOnly.bytesPerQuery(), distanceOnly.bytesPerQuery(), movingProjectionOnly.bytesPerQuery(), allProjectionOnly.bytesPerQuery());

            long movementStart = System.nanoTime();
            for (int q = 0; q < QUERIES; q++) {
                int fixedIndex = (q * 397) % FIXED;
                int movingIndex = FIXED + Math.floorMod(fixedIndex - FIXED, BANDS);
                Rx mover = receivers[movingIndex];
                mover.location = receivers[fixedIndex].location;

                ArrayList<FrequencyReceiver> found = new ArrayList<>(2);
                engine.forEachSubscribedReceiverForEmitterInRange(transmitters[fixedIndex], RANGE, found::add);

                assertEquals(2, found.size());
                assertTrue(found.contains(receivers[fixedIndex]));
                assertTrue(found.contains(mover));

                mover.location = transmitters[movingIndex].position();
                found.clear();

                engine.forEachSubscribedReceiverForEmitterInRange(transmitters[fixedIndex], RANGE, found::add);

                assertEquals(1, found.size());
                assertSame(receivers[fixedIndex], found.getFirst());
            }

            double movementMs = (System.nanoTime() - movementStart) / 1_000_000.0;
            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

            System.out.printf(Locale.ROOT, "[FREQ-MIXED] tx=%d fixedRx=%d movingRx=%d " + "registration=%.3f ms refresh=%.3f ms " + "refreshTicks=%d%n", TOTAL, FIXED, TOTAL - FIXED, registrationMs, refreshMs, ticks);
            System.out.printf(Locale.ROOT, "[FREQ-MIXED] 80 queries: " + "fixedHybrid=%.3f ms movingHybrid=%.3f ms " + "fixedLive=%.3f ms movingLive=%.3f ms%n", fixedHybrid.milliseconds(), movingHybrid.milliseconds(), fixedLive.milliseconds(), movingLive.milliseconds());
            System.out.printf(Locale.ROOT, "[FREQ-MIXED] bytes/query (includes Mockito): " + "fixedHybrid=%.0f movingHybrid=%.0f " + "fixedLive=%.0f movingLive=%.0f%n", fixedHybrid.bytesPerQuery(), movingHybrid.bytesPerQuery(), fixedLive.bytesPerQuery(), movingLive.bytesPerQuery());
            System.out.printf(Locale.ROOT, "[FREQ-MIXED] 80 move-and-return checks=PASS " + "movement=%.3f ms pendingRefresh=0 " + "correctness=PASS%n", movementMs);
        } finally {
            engine.shutdown();
        }
    }
}
