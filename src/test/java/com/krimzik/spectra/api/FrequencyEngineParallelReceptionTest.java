package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.material.FrequencyMaterialAttenuation;
import com.krimzik.spectra.api.material.FrequencyMaterialConfig;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.Mockito.*;

class FrequencyEngineParallelReceptionTest extends FrequencyEngineFixture {
    private MockedStatic<FrequencySpace> space;
    private MockedStatic<SubLevelContainer> sable;

    @BeforeEach
    void prepare() {
        space = mockWorldSpace(level);
        sable = mockStatic(SubLevelContainer.class);
        sable.when(() -> SubLevelContainer.getContainer(level)).thenReturn(null);

        when(level.getChunkSource()).thenReturn(mock(ServerChunkCache.class));

        FrequencyMaterialAttenuation.setEnabled(true);
    }

    @AfterEach
    void cleanup() {
        engine.shutdown();
        FrequencyApi.workers(server).shutdown();
        FrequencyMaterialAttenuation.setEnabled(false);

        if (sable != null) {
            sable.close();
        }

        if (space != null) {
            space.close();
        }
    }

    private void registerEmitters(int count) {
        for (int i = 0; i < count; i++) {
            engine.register(new TestEmitter(440.0));
        }
    }

    private void requireWorkers() {
        assumeTrue(FrequencyMaterialConfig.workerThreads() > 0);
    }

    private void submitFirstWorkerBatch() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (engine.submittedReceptionBatchCount() == 0 && System.nanoTime() < deadline) {
            engine.processCompletedReceptionBatches();
            Thread.sleep(1);
        }

        assertTrue(engine.submittedReceptionBatchCount() > 0);
    }

    private void drain() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (engine.pendingReceptionBatchCount() != 0 && System.nanoTime() < deadline) {
            engine.processCompletedReceptionBatches();
            Thread.sleep(1);
        }

        assertEquals(0, engine.pendingReceptionBatchCount(), "Reception batch did not complete");
    }

    @Test
    void parallelReceptionDeliversOnServerThread() throws Exception {
        requireWorkers();
        registerEmitters(1024);

        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        engine.registerReceiver(receiver);

        Thread serverThread = Thread.currentThread();
        AtomicInteger callbacks = new AtomicInteger();

        engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> {
            assertSame(serverThread, Thread.currentThread());
            callbacks.incrementAndGet();
        });

        assertEquals(1, engine.pendingReceptionBatchCount());
        assertEquals(0, callbacks.get());

        drain();

        assertEquals(1024, callbacks.get());
    }

    @Test
    void newlyRegisteredEmitterInvalidatesPendingResult() throws Exception {
        requireWorkers();
        registerEmitters(1024);

        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        engine.registerReceiver(receiver);

        AtomicInteger callbacks = new AtomicInteger();

        engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());

        assertEquals(1, engine.pendingReceptionBatchCount());

        engine.register(new TestEmitter(440.0));

        drain();

        assertEquals(1025, callbacks.get());
    }

    @Test
    void denseReceptionDetectsDeactivatedEmitter() throws Exception {
        requireWorkers();
        registerEmitters(1023);

        TestEmitter changed = new TestEmitter(440.0);
        engine.register(changed);

        AtomicInteger callbacks = new AtomicInteger();

        for (int i = 0; i < 25; i++) {
            TestReceiver receiver = new TestReceiver(440.0, 0.0);
            engine.registerReceiver(receiver);

            engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());
        }

        submitFirstWorkerBatch();
        changed.emitting = false;
        drain();

        assertEquals(25 * 1023, callbacks.get());
    }

    @Test
    void denseReceptionDetectsEmitterReplacement() throws Exception {
        requireWorkers();

        registerEmitters(1023);

        TestEmitter removed = new TestEmitter(440.0);
        var registration = engine.register(removed);

        TestEmitter replacement = new TestEmitter(440.0);

        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger removedCallbacks = new AtomicInteger();
        AtomicInteger replacementCallbacks = new AtomicInteger();

        for (int i = 0; i < 25; i++) {
            TestReceiver receiver = new TestReceiver(440.0, 0.0);
            engine.registerReceiver(receiver);

            engine.scheduleReceptionInRange(
                    receiver,
                    1.0,
                    (emitter, signal, distance, amplitude, travelTime) -> {
                        callbacks.incrementAndGet();

                        if (emitter == removed) {
                            removedCallbacks.incrementAndGet();
                        }

                        if (emitter == replacement) {
                            replacementCallbacks.incrementAndGet();
                        }
                    }
            );
        }

        submitFirstWorkerBatch();

        registration.close();
        engine.register(replacement);

        drain();

        assertEquals(25 * 1024, callbacks.get());
        assertEquals(0, removedCallbacks.get());
        assertEquals(25, replacementCallbacks.get());
    }

    @Test
    void newlyActivatedEmitterInvalidatesPendingResult() throws Exception {
        requireWorkers();
        registerEmitters(1024);

        TestEmitter inactive = new TestEmitter(440.0);
        inactive.emitting = false;
        engine.register(inactive);

        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        engine.registerReceiver(receiver);

        AtomicInteger callbacks = new AtomicInteger();

        engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());

        assertEquals(1, engine.pendingReceptionBatchCount());

        inactive.emitting = true;

        drain();

        assertEquals(1025, callbacks.get());
    }

    @Test
    void removedReceiverNeverReceivesPendingCallbacks() throws Exception {
        requireWorkers();
        registerEmitters(1024);

        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        var registration = engine.registerReceiver(receiver);

        AtomicInteger callbacks = new AtomicInteger();

        engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());

        assertEquals(1, engine.pendingReceptionBatchCount());

        registration.close();

        drain();

        assertEquals(0, callbacks.get());
    }

    @Test
    void smallGroupUsesBudgetedSynchronousReception() throws Exception {
        requireWorkers();
        registerEmitters(1024);

        TestReceiver first = new TestReceiver(440.0, 0.0);
        TestReceiver second = new TestReceiver(440.0, 0.0);
        TestReceiver third = new TestReceiver(440.0, 0.0);

        engine.registerReceiver(first);
        engine.registerReceiver(second);
        engine.registerReceiver(third);

        AtomicInteger callbacks = new AtomicInteger();

        engine.scheduleReceptionInRange(first, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());
        engine.scheduleReceptionInRange(second, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());
        engine.scheduleReceptionInRange(third, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());

        assertEquals(3, engine.pendingReceptionBatchCount());
        assertEquals(0, callbacks.get());

        drain();

        assertEquals(3072, callbacks.get());
        assertEquals(0, engine.submittedReceptionBatchCount());
        assertEquals(3, engine.receptionTimings().adaptiveSyncRequests());
        assertEquals(0, engine.pendingReceptionBatchCount());
    }

    @Test
    void shutdownDiscardsPendingReceptionCallbacks() {
        requireWorkers();
        registerEmitters(1024);

        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        engine.registerReceiver(receiver);

        AtomicInteger callbacks = new AtomicInteger();

        engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());

        assertEquals(1, engine.pendingReceptionBatchCount());

        engine.shutdown();

        assertEquals(0, callbacks.get());
        assertThrows(IllegalStateException.class, engine::processCompletedReceptionBatches);
    }

    @Test
    void largeReceiverGroupUsesSharedWorkerBatches() throws Exception {
        requireWorkers();
        registerEmitters(1024);

        AtomicInteger callbacks = new AtomicInteger();

        for (int i = 0; i < 32; i++) {
            TestReceiver receiver = new TestReceiver(440.0, 0.0);
            engine.registerReceiver(receiver);
            engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());
        }

        assertEquals(32, engine.pendingReceptionBatchCount());
        assertEquals(0, callbacks.get());

        drain();

        assertEquals(32_768, callbacks.get());
        assertEquals(0, engine.pendingReceptionBatchCount());

        assertTrue(engine.submittedReceptionBatchCount() > 0);
        assertTrue(engine.submittedReceptionBatchCount() < 32);
        assertTrue(engine.peakInFlightReceptionBatchCount() <= 4);

        FrequencyEngine.ReceptionTimings timings = engine.receptionTimings();

        assertEquals(0, timings.adaptiveSyncRequests());
        assertTrue(timings.activeTicks() > 0);
        assertTrue(timings.maxActiveTickMs() >= timings.p95ActiveTickMs());

        FrequencyEngine.ReceptionSchedulerDiagnostics scheduler = engine.receptionSchedulerDiagnostics();

        assertTrue(scheduler.readyBehindHeadTicks() <= scheduler.headBlockedTicks());
        assertTrue(scheduler.peakReadyBehindHead() <= 3);
        assertTrue(scheduler.overBudgetTicks() <= timings.activeTicks());
        assertTrue(scheduler.worstTickOtherMs() >= 0.0);
    }

    @Test
    void sharedFixedFramePreservesFrequencyAndRange() throws Exception {
        requireWorkers();

        record FixedEmitter(Vec3 position, FrequencySignal signal)
                implements FrequencyEmitter {
            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        }

        for (int i = 0; i < 1024; i++) {
            Vec3 position = i % 4 == 0
                    ? new Vec3(300.0, 0.0, 0.0)
                    : Vec3.ZERO;

            double frequency = i % 2 == 0 ? 440.0 : 880.0;

            engine.registerChunkBound(
                    BlockPos.containing(position),
                    new FixedEmitter(
                            position,
                            new FrequencySignal(frequency, 1.0, 0.0, 343.0)
                    )
            );
        }

        AtomicInteger callbacks = new AtomicInteger();

        for (int i = 0; i < 25; i++) {
            TestReceiver receiver = new TestReceiver(440.0, 0.0);
            engine.registerReceiver(receiver);

            engine.scheduleReceptionInRange(
                    receiver,
                    256.0,
                    (emitter, signal, distance, amplitude, travelTime) ->
                            callbacks.incrementAndGet()
            );
        }

        drain();

        assertEquals(25 * 256, callbacks.get());
        assertTrue(engine.submittedReceptionBatchCount() > 0);
    }

    @Test
    void sharedFixedFrameRejectsDeactivatedEmitter() throws Exception {
        requireWorkers();
        FrequencySignal signal = new FrequencySignal(440.0, 1.0, 0.0, 343.0);

        record FixedEmitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {
            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        }

        for (int i = 0; i < 1023; i++) {
            engine.registerChunkBound(BlockPos.ZERO, new FixedEmitter(Vec3.ZERO, signal));
        }

        AtomicBoolean active = new AtomicBoolean(true);
        FrequencyEmitter changed = new FrequencyEmitter() {
            @Override
            public Vec3 position() {
                return Vec3.ZERO;
            }

            @Override
            public FrequencySignal signal() {
                return signal;
            }

            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }

            @Override
            public boolean isEmitting() {
                return active.get();
            }
        };

        engine.registerChunkBound(BlockPos.ZERO, changed);
        AtomicInteger callbacks = new AtomicInteger();

        for (int i = 0; i < 25; i++) {
            TestReceiver receiver = new TestReceiver(440.0, 0.0);
            engine.registerReceiver(receiver);
            engine.scheduleReceptionInRange(receiver, 1.0, (emitter, current, distance, amplitude, travelTime) -> callbacks.incrementAndGet());
        }

        submitFirstWorkerBatch();
        active.set(false);
        drain();

        assertEquals(25 * 1023, callbacks.get());
    }

    @Test
    void oversizedReceiverUsesParallelReceptionWithoutLosingSignals() throws Exception {
        requireWorkers();
        int emitterCount = 16_385;
        registerEmitters(emitterCount);

        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        engine.registerReceiver(receiver);

        AtomicInteger callbacks = new AtomicInteger();
        engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());
        drain();

        assertEquals(emitterCount, callbacks.get());
        assertTrue(engine.submittedReceptionBatchCount() > 0);
        assertEquals(0, engine.receptionTimings().adaptiveSyncRequests());
        assertEquals(0.0, engine.receptionTimings().synchronousMs());
        assertEquals(0, engine.pendingReceptionBatchCount());
    }

    @Test
    void denseReceptionDetectsRegisteredFrequencyChange() throws Exception {
        requireWorkers();

        FrequencySignal original = new FrequencySignal(440.0, 1.0, 0.0, 343.0);
        FrequencySignal[] current = {original};

        record FixedEmitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {
            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        }

        for (int i = 0; i < 1023; i++) {
            engine.registerChunkBound(BlockPos.ZERO, new FixedEmitter(Vec3.ZERO, original));
        }

        FrequencyEmitter changed = new FrequencyEmitter() {
            @Override
            public Vec3 position() {
                return Vec3.ZERO;
            }

            @Override
            public FrequencySignal signal() {
                return current[0];
            }

            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        };

        engine.registerChunkBound(BlockPos.ZERO, changed);
        AtomicInteger callbacks = new AtomicInteger();

        for (int i = 0; i < 25; i++) {
            TestReceiver receiver = new TestReceiver(440.0, 0.0);
            engine.registerReceiver(receiver);
            engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> callbacks.incrementAndGet());
        }

        submitFirstWorkerBatch();

        current[0] = new FrequencySignal(880.0, 1.0, 0.0, 343.0);
        engine.updateEmitter(changed);

        drain();

        assertEquals(25 * 1023, callbacks.get());
    }

    @Test
    void denseReceptionRevalidatesAfterCallbackChangesSignal() throws Exception {
        requireWorkers();

        FrequencySignal original = new FrequencySignal(440.0, 1.0, 0.0, 343.0);
        FrequencySignal[] current = {original};

        record FixedEmitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {
            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        }

        for (int i = 0; i < 1023; i++) {
            engine.registerChunkBound(BlockPos.ZERO, new FixedEmitter(Vec3.ZERO, original));
        }

        FrequencyEmitter changed = new FrequencyEmitter() {
            @Override
            public Vec3 position() {
                return Vec3.ZERO;
            }

            @Override
            public FrequencySignal signal() {
                return current[0];
            }

            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        };

        engine.registerChunkBound(BlockPos.ZERO, changed);

        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger changedCallbacks = new AtomicInteger();

        for (int i = 0; i < 25; i++) {
            TestReceiver receiver = new TestReceiver(440.0, 0.0);
            engine.registerReceiver(receiver);

            engine.scheduleReceptionInRange(receiver, 1.0, (emitter, signal, distance, amplitude, travelTime) -> {
                callbacks.incrementAndGet();

                if (emitter == changed) {
                    if (changedCallbacks.getAndIncrement() == 0) {
                        current[0] = new FrequencySignal(440.0, 0.5, 0.0, 343.0);
                    } else {
                        assertEquals(0.5, signal.amplitude(), 0.0);
                    }
                }
            });
        }

        drain();

        assertEquals(25 * 1024, callbacks.get());
        assertEquals(25, changedCallbacks.get());
    }

    @Test
    void denseBoundsRemainValidAfterBoundaryEmitterReplacement() throws Exception {
        requireWorkers();
        FrequencySignal signal = new FrequencySignal(440.0, 1.0, 0.0, 343.0);

        record FixedEmitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {
            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        }

        for (int i = 0; i < 1024; i++) {
            engine.registerChunkBound(BlockPos.ZERO, new FixedEmitter(Vec3.ZERO, signal));
        }

        Vec3 firstPosition = new Vec3(8.0, 0.0, 0.0);
        var first = engine.registerChunkBound(BlockPos.containing(firstPosition), new FixedEmitter(firstPosition, signal));

        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        engine.registerReceiver(receiver);

        AtomicInteger callbacks = new AtomicInteger();

        for (int i = 0; i < 25; i++) {
            engine.scheduleReceptionInRange(receiver, 32.0, (emitter, current, distance, amplitude, travelTime) -> callbacks.incrementAndGet());
        }

        drain();
        assertEquals(25 * 1025, callbacks.get());

        first.close();

        Vec3 secondPosition = new Vec3(-8.0, 0.0, 0.0);
        engine.registerChunkBound(BlockPos.containing(secondPosition), new FixedEmitter(secondPosition, signal));

        for (int i = 0; i < 25; i++) {
            engine.scheduleReceptionInRange(receiver, 32.0, (emitter, current, distance, amplitude, travelTime) -> callbacks.incrementAndGet());
        }

        drain();

        assertEquals(50 * 1025, callbacks.get());
        assertEquals(0.0, engine.receptionTimings().synchronousMs());
        assertEquals(0, engine.pendingReceptionBatchCount());
    }

    @Test
    void receiverMovementInvalidatesQueuedReception() throws Exception {
        requireWorkers();
        registerEmitters(1024);

        AtomicReference<Vec3> position = new AtomicReference<>(Vec3.ZERO);

        FrequencyReceiver receiver = mock(FrequencyReceiver.class);
        when(receiver.position()).thenAnswer(invocation -> position.get());
        when(receiver.tunedFrequencyHz()).thenReturn(440.0);
        when(receiver.toleranceHz()).thenReturn(0.0);

        engine.registerReceiver(receiver);

        Thread serverThread = Thread.currentThread();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger staleResults = new AtomicInteger();

        engine.scheduleReceptionInRange(receiver, 1.0,
                (emitter, signal, distanceSquared, amplitude, travelTime) -> {
                    if (Thread.currentThread() != serverThread || Math.abs(distanceSquared - 0.0625) > 1.0e-12) {
                        staleResults.incrementAndGet();
                    }

                    callbacks.incrementAndGet();
                });

        submitFirstWorkerBatch();
        position.set(new Vec3(0.25, 0.0, 0.0));
        drain();

        assertEquals(1024, callbacks.get());
        assertEquals(0, staleResults.get());
    }
}