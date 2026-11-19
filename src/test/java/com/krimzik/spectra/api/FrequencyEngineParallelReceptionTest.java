package com.krimzik.spectra.api;

import com.krimzik.spectra.api.material.FrequencyMaterialAttenuation;
import com.krimzik.spectra.api.material.FrequencyMaterialConfig;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerChunkCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        FrequencyEngine.ReceptionTimings timings = engine.receptionTimings();

        assertEquals(0, timings.adaptiveSyncRequests());
        assertTrue(timings.activeTicks() > 0);
        assertTrue(timings.maxActiveTickMs() >= timings.p95ActiveTickMs());
    }
}