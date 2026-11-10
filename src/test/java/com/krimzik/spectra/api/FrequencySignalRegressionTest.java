package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencySignalRegressionTest extends FrequencyEngineFixture {
    @Test
    void interferenceNowUsesPreciseTickPhase() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        long tick = 4_000_000_001L;
        when(server.getTickCount()).thenReturn((int) tick);

        FrequencyEmitter emitter = mock(FrequencyEmitter.class);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        FrequencySignal signal = new FrequencySignal(915_000_007.25, 2.0, 0.0, 4.0);

        when(emitter.signal()).thenReturn(signal);
        when(emitter.isEmitting()).thenReturn(true);
        when(emitter.position()).thenReturn(new Vec3(1, 0, 0));

        when(receiver.tunedFrequencyHz()).thenReturn(signal.frequencyHz());
        when(receiver.toleranceHz()).thenReturn(0.0);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        FrequencyEngine engine = new FrequencyEngine(level);
        engine.register(emitter);

        double expected = 2.0 * Math.sin(0.55 * Math.PI * 2.0);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            double actual = engine.sampleInterferenceNow(receiver, 2.0);
            assertEquals(expected, actual, 1.0e-12);
        }
    }

    @Test
    void resonanceNowUsesPreciseTickPhase() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        long tick = 4_000_000_001L;
        when(server.getTickCount()).thenReturn((int) tick);

        FrequencyEmitter emitter = mock(FrequencyEmitter.class);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        double hz = 915_000_007.25;

        FrequencySignal signal = new FrequencySignal(hz, 2.0, 0.0, 4.0);

        when(emitter.signal()).thenReturn(signal);
        when(emitter.isEmitting()).thenReturn(true);
        when(emitter.position()).thenReturn(new Vec3(1, 0, 0));

        when(receiver.tunedFrequencyHz()).thenReturn(hz);
        when(receiver.toleranceHz()).thenReturn(0.0);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        FrequencyEngine engine = new FrequencyEngine(level);
        engine.register(emitter);

        double expected = 2.0 * Math.sin(0.55 * Math.PI * 2.0 - Math.PI / 2.0);
        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            double actual = engine.sampleResonanceNow(receiver, 2.0, hz, 0.5);
            assertEquals(expected, actual, 1.0e-12);
        }
    }

    @Test
    void coherentGhzCarriersAddConstructively() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        double hz = 915_000_007.25;

        when(receiver.tunedFrequencyHz()).thenReturn(hz);
        when(receiver.toleranceHz()).thenReturn(0.0);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        Vec3 position = new Vec3(1, 0, 0);

        registerInterferenceEmitter(engine, position, new FrequencySignal(hz, 2.0, 0.0, 4.0));
        registerInterferenceEmitter(engine, position, new FrequencySignal(hz, 2.0, 0.0, 4.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertEquals(8.0, engine.sampleInterferenceMeanSquareInRange(receiver, 2.0), 1.0e-10);
            assertEquals(Math.sqrt(8.0), engine.sampleInterferenceRmsInRange(receiver, 2.0), 1.0e-10);
        }
    }

    @Test
    void propagationDelayCanCancelCoherentSignals() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        when(receiver.tunedFrequencyHz()).thenReturn(1.0);
        when(receiver.toleranceHz()).thenReturn(0.0);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        Vec3 near = new Vec3(1, 0, 0);
        Vec3 far = new Vec3(2, 0, 0);

        registerInterferenceEmitter(engine, near, new FrequencySignal(1.0, 2.0, 0.0, 2.0));
        registerInterferenceEmitter(engine, far, new FrequencySignal(1.0, 4.0, 0.0, 2.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertEquals(0.0, engine.sampleInterferenceMeanSquareInRange(receiver, 3.0), 1.0e-10);
        }
    }

    @Test
    void distinctGhzCarriersDoNotFalselyCombineCoherently() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        double hz = 915_000_000.0;

        when(receiver.tunedFrequencyHz()).thenReturn(hz + 10.0);
        when(receiver.toleranceHz()).thenReturn(10.0);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        Vec3 position = new Vec3(1, 0, 0);

        registerInterferenceEmitter(engine, position, new FrequencySignal(hz, 2.0, 0.0, 4.0));
        registerInterferenceEmitter(engine, position, new FrequencySignal(hz + 20.0, 2.0, 0.0, 4.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertEquals(4.0, engine.sampleInterferenceMeanSquareInRange(receiver, 2.0), 1.0e-10);
        }
    }

    @Test
    void lowFrequencyWindowPreservesWaveformVariation() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        when(receiver.tunedFrequencyHz()).thenReturn(1.0);
        when(receiver.toleranceHz()).thenReturn(0.0);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(1.0, 2.0, 0.0, 4.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            double expected = 2.0 - Math.sin(0.4 * Math.PI) / (0.2 * Math.PI);

            assertEquals(expected, engine.sampleInterferenceWindowMeanSquareInRange(receiver, 1.0, 0L, 0.1), 1.0e-12);
        }
    }

    @Test
    void nearbyGhzCarriersProduceAnalyticalBeating() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        when(server.getTickCount()).thenReturn(10);

        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        double frequency = 915_000_000.0;

        when(receiver.tunedFrequencyHz()).thenReturn(frequency + 0.5);
        when(receiver.toleranceHz()).thenReturn(0.5);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(frequency, 1.0, 0.0, 4.0));
        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(frequency + 1.0, 1.0, 0.0, 4.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            double early = engine.sampleInterferenceWindowMeanSquareInRange(receiver, 1.0, 0L, 0.25);
            double later = engine.sampleInterferenceWindowMeanSquareNow(receiver, 1.0, 0.25);

            assertEquals(1.0 + 2.0 / Math.PI, early, 1.0e-8);
            assertEquals(1.0 - 2.0 / Math.PI, later, 1.0e-8);
        }
    }

    @Test
    void oppositePhaseCarriersCancelAcrossEntireWindow() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        double frequency = 2_400_000_000.0;

        when(receiver.tunedFrequencyHz()).thenReturn(frequency);
        when(receiver.toleranceHz()).thenReturn(0.0);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(frequency, 2.0, 0.0, 4.0));
        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(frequency, 2.0, Math.PI, 4.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertEquals(0.0, engine.sampleInterferenceWindowMeanSquareInRange(receiver, 1.0, 4_000_000_001L, 0.001), 1.0e-12);
        }
    }

    @Test
    void picosecondWindowPreservesGhzWaveform() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        double hz = 1_000_000_000.0;
        double window = 1.0e-12;

        when(receiver.tunedFrequencyHz()).thenReturn(hz);
        when(receiver.toleranceHz()).thenReturn(0.0);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(hz, 1.0, 0.0, 4.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            double expected = 0.5 - Math.sin(4.0 * Math.PI * hz * window) / (8.0 * Math.PI * hz * window);
            double actual = engine.sampleInterferenceWindowMeanSquareInRange(receiver, 1.0, 0L, window);

            assertEquals(expected, actual, 1.0e-11);
        }
    }

    @Test
    void fractionalHertzSeparationWorksAtGhz() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        double frequency = 2_400_000_000.0;
        double difference = 0.125;

        when(receiver.tunedFrequencyHz()).thenReturn(frequency + 0.0625);
        when(receiver.toleranceHz()).thenReturn(0.0625);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(frequency, 1.0, 0.0, 4.0));
        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(frequency + difference, 1.0, 0.0, 4.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            double x = Math.PI * difference;
            double expected = 1.0 + Math.sin(x) / x;

            double actual = engine.sampleInterferenceWindowMeanSquareInRange(receiver, 1.0, 10L, 1.0);

            assertEquals(expected, actual, 1.0e-8);
        }
    }

    @Test
    void repeatedSecondaryFrequencyAccumulatesCoherently() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        double hz = 915_000_000.0;

        when(receiver.tunedFrequencyHz()).thenReturn(hz + 0.5);
        when(receiver.toleranceHz()).thenReturn(0.5);
        when(receiver.position()).thenReturn(Vec3.ZERO);

        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(hz, 1.0, 0.0, 4.0));
        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(hz + 1.0, 1.0, 0.0, 4.0));
        registerInterferenceEmitter(engine, Vec3.ZERO, new FrequencySignal(hz + 1.0, 1.0, 0.0, 4.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertEquals(2.5, engine.sampleInterferenceMeanSquareInRange(receiver, 1.0), 1.0e-12);
        }
    }
    @Test
    void pooledReceiverQueriesRemainIndependentAndMeasureAllocations() throws ReflectiveOperationException {
        final int count = 1_024;
        final long tick = 4_000_000_001L;
        final double window = 0.0130137;
        final double range = 4.0;

        record FixedEmitter(
                Vec3 position,
                FrequencySignal signal) implements FrequencyEmitter {
        }

        record PositionedReceiver(
                Vec3 position,
                double tunedFrequencyHz,
                double toleranceHz) implements FrequencyReceiver {
        }

        Vec3 sourcePosition = new Vec3(1.0, 0.0, 0.0);
        for (int i = 0; i < count; i++) {
            double hz = 915_000_000.0 + i * 0.125;

            engine.register(new FixedEmitter(sourcePosition, new FrequencySignal(hz, 0.25 + (i % 9) * 0.1, i * 0.031, 343.0)));
        }

        FrequencyReceiver receiverA = new PositionedReceiver(Vec3.ZERO, 915_000_064.0, 64.0);
        FrequencyReceiver receiverB = new PositionedReceiver(new Vec3(0.0, 0.0, 1.0), 915_000_064.0, 64.0);
        FrequencyReceiver emptyReceiver = new PositionedReceiver(Vec3.ZERO, 920_000_000.0, 0.1);

        java.lang.reflect.Field poolField = FrequencyEngine.class.getDeclaredField("interferenceWorkspaces");
        poolField.setAccessible(true);
        java.util.Deque<?> pool = (java.util.Deque<?>) poolField.get(engine);

        try (MockedStatic<FrequencySpace> ignored = mockWorldSpace(level)) {
            FrequencyReceiver[] receivers = {
                    receiverA,
                    receiverB,
                    receiverA,
                    emptyReceiver,
                    receiverB,
                    receiverA
            };

            long[] ticks = {
                    tick,
                    tick,
                    tick + 17,
                    tick + 17,
                    tick + 30,
                    tick + 40
            };

            double[] windows = {
                    window,
                    window,
                    window,
                    window,
                    0.25,
                    window
            };

            Object firstWorkspace = null;

            for (int i = 0; i < receivers.length; i++) {
                FrequencyReceiver receiver = receivers[i];
                long endTick = ticks[i];
                double observationWindow = windows[i];
                var reference = new FrequencyEngine.InterferenceAccumulator(endTick, observationWindow);

                engine.forEachReceivedInRange(receiver, range, (emitter, signal, distanceSquared, amplitude, travelTime) -> reference.add(signal, amplitude, travelTime));

                double expected = reference.meanSquareWindow(observationWindow);
                double actual = engine.sampleInterferenceWindowMeanSquareInRange(receiver, range, endTick, observationWindow);

                assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Pooled receiver mismatch at scenario " + i);
                assertEquals(1, pool.size(), "Unexpected workspace pool size");

                if (firstWorkspace == null) {
                    firstWorkspace = pool.peekFirst();
                    assertNotNull(firstWorkspace);
                } else {
                    assertSame(firstWorkspace, pool.peekFirst(), "Engine failed to reuse its accumulator");
                }
            }

            for (int i = 0; i < 12; i++) {
                pooledInterferenceBenchmarkSink = engine.sampleInterferenceWindowMeanSquareInRange(receiverA, range, tick + i, window);
            }

            com.sun.management.ThreadMXBean memory = java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
            final int repetitions = 20;
            long threadId = Thread.currentThread().threadId();
            if (memory != null && !memory.isThreadAllocatedMemoryEnabled()) {
                memory.setThreadAllocatedMemoryEnabled(true);
            }

            long beforeBytes = memory == null ? 0L : memory.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();
            for (int i = 0; i < repetitions; i++) {
                pooledInterferenceBenchmarkSink = engine.sampleInterferenceWindowMeanSquareInRange(receiverA, range, tick + i, window);
            }

            long elapsed = System.nanoTime() - start;
            double milliseconds = elapsed / (repetitions * 1_000_000.0);
            double bytesPerQuery = memory == null ? Double.NaN : (memory.getThreadAllocatedBytes(threadId) - beforeBytes) / (double) repetitions;

            System.out.printf(java.util.Locale.ROOT, "[FREQ-ENGINE-POOL] " + "carriers=%d " + "time=%.3f ms/query " + "allocated=%.1f bytes/query " + "workspaceReuse=PASS " + "receiverIsolation=PASS%n", count, milliseconds, bytesPerQuery);
            com.krimzik.spectra.api.consumers.FrequencyReceptionConsumer noOpReception = (emitter, signal, distanceSquared, amplitude, travelTime) -> {};

            for (int warmup = 0; warmup < 8; warmup++) {
                FrequencySpace.worldPosition(level, receiverA.position());
                for (int j = 0; j < count; j++) {
                    FrequencySpace.worldPosition(level, sourcePosition);
                }

                engine.forEachReceivedInRange(receiverA, range, noOpReception);
            }

            long mockBeforeBytes = memory == null ? 0L : memory.getThreadAllocatedBytes(threadId);
            long mockStart = System.nanoTime();
            double coordinateChecksum = 0.0;

            for (int iteration = 0;
                 iteration < repetitions; iteration++) {
                Vec3 receiverWorld = FrequencySpace.worldPosition(level, receiverA.position());
                coordinateChecksum += receiverWorld.x;

                for (int j = 0; j < count; j++) {
                    Vec3 transmitterWorld = FrequencySpace.worldPosition(level, sourcePosition);
                    coordinateChecksum += transmitterWorld.x;
                }
            }

            long mockElapsed = System.nanoTime() - mockStart;
            pooledInterferenceBenchmarkSink = coordinateChecksum;
            double mockMs = mockElapsed / (repetitions * 1_000_000.0);
            double mockBytes = memory == null ? Double.NaN : (memory.getThreadAllocatedBytes(threadId) - mockBeforeBytes) / (double) repetitions;

            long traversalBeforeBytes = memory == null ? 0L : memory.getThreadAllocatedBytes(threadId);
            long traversalStart = System.nanoTime();

            for (int iteration = 0;
                 iteration < repetitions; iteration++) {
                engine.forEachReceivedInRange(receiverA, range, noOpReception);
            }

            long traversalElapsed = System.nanoTime() - traversalStart;
            double traversalMs = traversalElapsed / (repetitions * 1_000_000.0);
            double traversalBytes = memory == null ? Double.NaN : (memory.getThreadAllocatedBytes(threadId) - traversalBeforeBytes) / (double) repetitions;

            System.out.printf(java.util.Locale.ROOT, "[FREQ-BREAKDOWN] " + "carriers=%d " + "mockOnly=%.3f ms %.0f bytes " + "traversal=%.3f ms %.0f bytes " + "full=%.3f ms %.0f bytes%n", count, mockMs, mockBytes, traversalMs, traversalBytes, milliseconds, bytesPerQuery);
        }
    }
}
