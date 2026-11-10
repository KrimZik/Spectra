package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyEngineScaleTest {

    private record Tx(
            Vec3 position,
            FrequencySignal signal
    ) implements FrequencyEmitter {}

    private static final class Rx implements FrequencyReceiver {
        Vec3 position;
        final double frequency;

        Rx(Vec3 position, double frequency) {
            this.position = position;
            this.frequency = frequency;
        }

        @Override
        public Vec3 position() {
            return position;
        }

        @Override
        public double tunedFrequencyHz() {
            return frequency;
        }

        @Override
        public double toleranceHz() {
            return 0.0;
        }
    }

    @Test
    void combinedFiftyThousandTxRxDiscovery() {
        final int count = 50_000;
        final int queries = 80;
        final int budget = 128;
        final double range = 0.25;

        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);

        Tx[] transmitters = new Tx[count];
        Rx[] receivers = new Rx[count];

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(invocation -> invocation.getArgument(1));
            System.out.println("[FREQ-50K] Starting registration...");

            long registrationStart = System.nanoTime();

            for (int i = 0; i < count; i++) {
                double hz = 100_000.0 + (i % 128) * 100.0;
                Vec3 position = new Vec3(i * 2.0, 0.0, 0.0);

                transmitters[i] = new Tx(position, new FrequencySignal(hz, 1.0, 0.0, 343.0));
                receivers[i] = new Rx(position, hz);

                engine.register(transmitters[i]);
                engine.registerReceiverChunkBound(new BlockPos(i * 2, 0, 0), receivers[i]);
                engine.subscribeFixedWorldReceiver(receivers[i]);
            }

            long registrationNanos = System.nanoTime() - registrationStart;
            System.out.printf(Locale.ROOT, "[FREQ-50K] Registration finished: %.3f ms%n", registrationNanos / 1_000_000.0);

            assertEquals(count, engine.emitterCount());
            assertEquals(count, engine.receiverCount());
            assertEquals(count, engine.subscribedReceiverCount());
            assertEquals(count, engine.pendingReceiverWorldPositionRefreshCount());

            long refreshStart = System.nanoTime();
            int refreshTicks = 0;
            int refreshedTotal = 0;

            while (engine.pendingReceiverWorldPositionRefreshCount() > 0) {
                int refreshed = engine.processDirtyReceiverWorldPositionsForTick(budget);

                assertTrue(refreshed <= budget);
                assertTrue(refreshed > 0);

                refreshedTotal += refreshed;
                refreshTicks++;

                if (refreshTicks % 32 == 0) {
                    System.out.printf(Locale.ROOT, "[FREQ-50K] Refresh progress: %d/%d receivers, " + "%d ticks, %.2f seconds%n", refreshedTotal, count, refreshTicks, (System.nanoTime() - refreshStart) / 1_000_000_000.0);
                }
            }

            long refreshNanos = System.nanoTime() - refreshStart;
            System.out.printf(Locale.ROOT, "[FREQ-50K] Refresh finished: %.3f ms, ticks=%d%n", refreshNanos / 1_000_000.0, refreshTicks);

            assertEquals(count, refreshedTotal);
            assertEquals((count + budget - 1) / budget, refreshTicks);

            for (int q = 0; q < 8; q++) {
                Tx tx = transmitters[q];
                engine.forEachSubscribedReceiverForEmitterInRange(tx, range, receiver -> {});
            }

            long liveStart = System.nanoTime();
            int[] hits = {0};

            for (int q = 0; q < queries; q++) {
                int index = (q * 613) % count;
                Tx tx = transmitters[index];
                Rx expected = receivers[index];

                hits[0] = 0;

                engine.forEachSubscribedReceiverForEmitterInRange(
                        tx, range, receiver -> {
                            assertSame(expected, receiver);
                            hits[0]++;
                        });

                assertEquals(1, hits[0], "Live query " + q);
            }

            long liveNanos = System.nanoTime() - liveStart;
            long snapshotStart = System.nanoTime();

            for (int q = 0; q < queries; q++) {
                int index = (q * 613) % count;
                Tx tx = transmitters[index];
                Rx expected = receivers[index];

                hits[0] = 0;

                engine.forEachReceiverSubscribedSnapshotInRange(
                        tx.signal().frequencyHz(),
                        tx.position(),
                        range,
                        receiver -> {
                            assertSame(expected, receiver);hits[0]++;
                        });

                assertEquals(1, hits[0], "Snapshot query " + q);
            }

            long snapshotNanos = System.nanoTime() - snapshotStart;
            long bruteStart = System.nanoTime();

            for (int q = 0; q < queries; q++) {
                int index = (q * 613) % count;
                Tx tx = transmitters[index];

                int matches = 0;
                for (Rx receiver : receivers) {
                    if (receiver.frequency != tx.signal().frequencyHz()) {
                        continue;
                    }

                    double dx = receiver.position.x - tx.position().x;
                    if (Math.abs(dx) <= range) {
                        matches++;
                    }
                }

                assertEquals(1, matches, "Brute-force query " + q);
            }

            long bruteNanos = System.nanoTime() - bruteStart;

            System.out.printf(Locale.ROOT, "[FREQ-50K] tx=%d rx=%d registration=%.3f ms " + "refresh=%.3f ms refreshTicks=%d " + "live=%.3f ms/%d " + "snapshot=%.3f ms/%d " + "brute=%.3f ms/%d " + "correctness=PASS%n", count, count, registrationNanos / 1_000_000.0, refreshNanos / 1_000_000.0, refreshTicks, liveNanos / 1_000_000.0, queries, snapshotNanos / 1_000_000.0, queries, bruteNanos / 1_000_000.0, queries);

            int sourceIndex = 17;
            int destinationIndex = 145;

            receivers[sourceIndex].position = transmitters[destinationIndex].position();
            engine.markReceiverWorldPositionDirty(receivers[sourceIndex]);

            hits[0] = 0;
            engine.forEachSubscribedReceiverForEmitterInRange(transmitters[destinationIndex], range, receiver -> hits[0]++);
            assertEquals(2, hits[0], "Live moved-receiver query");

            hits[0] = 0;
            engine.forEachReceiverSubscribedSnapshotInRange(transmitters[destinationIndex].signal().frequencyHz(), transmitters[destinationIndex].position(), range, receiver -> hits[0]++);
            assertEquals(1, hits[0]);
            assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(1));

            hits[0] = 0;
            engine.forEachReceiverSubscribedSnapshotInRange(transmitters[destinationIndex].signal().frequencyHz(), transmitters[destinationIndex].position(), range, receiver -> hits[0]++);
            assertEquals(2, hits[0]);

            System.out.println("[FREQ-50K] movedReceiver=PASS " + "dirtyFallback=PASS " + "refreshRecovery=PASS");
        } finally {
            engine.shutdown();
        }
    }
}
