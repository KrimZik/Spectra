package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FrequencyEngineTest {
    private MinecraftServer server;
    private ServerLevel level;
    private FrequencyEngine engine;
    private static volatile double pooledInterferenceBenchmarkSink;

    @BeforeEach
    void setup() {
        server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        engine = new FrequencyEngine(level);
    }

    @Test
    void entityEmitterAutomaticallyClosesOnLeave() {
        Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);

        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.registerEntityBound(entity, emitter);

        assertEquals(1, engine.emitterCount());
        assertEquals(1, countMatches(engine, 440.0, 0.0));

        engine.onEntityLeave(entity);

        assertEquals(0, engine.emitterCount());
        assertEquals(0, countMatches(engine, 440.0, 0.0));

        registration.close();
        engine.onEntityLeave(entity);

        assertEquals(0, engine.emitterCount());
    }

    @Test
    void entityLeaveDoesNotRemoveUnrelatedEmitters() {
        Entity firstOwner = mock(Entity.class);
        Entity secondOwner = mock(Entity.class);

        when(firstOwner.level()).thenReturn(level);
        when(secondOwner.level()).thenReturn(level);

        TestEmitter first = new TestEmitter(440.0);
        TestEmitter second = new TestEmitter(880.0);
        TestEmitter manual = new TestEmitter(660.0);

        FrequencyRegistration firstHandle = engine.registerEntityBound(firstOwner, first);
        FrequencyRegistration secondHandle = engine.registerEntityBound(secondOwner, second);
        FrequencyRegistration manualHandle = engine.register(manual);

        engine.onEntityLeave(firstOwner);

        assertEquals(2, engine.emitterCount());
        assertEquals(0, countMatches(engine, 440.0, 0.0));
        assertEquals(1, countMatches(engine, 880.0, 0.0));
        assertEquals(1, countMatches(engine, 660.0, 0.0));

        firstHandle.close();
        secondHandle.close();
        manualHandle.close();

        assertEquals(0, engine.emitterCount());
    }

    @Test
    void frequencyBucketsPreserveEmitterIdentity() {
        record EqualEmitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {}
        FrequencySignal signal = new FrequencySignal(440.0, 1.0, 0.0, 343.0);

        EqualEmitter first = new EqualEmitter(Vec3.ZERO, signal);
        EqualEmitter second = new EqualEmitter(Vec3.ZERO, signal);
        EqualEmitter third = new EqualEmitter(Vec3.ZERO, signal);

        assertEquals(first, second);
        assertNotSame(first, second);

        FrequencyRegistration a = engine.register(first);
        FrequencyRegistration b = engine.register(second);
        FrequencyRegistration c = engine.register(third);

        assertEquals(3, countMatches(engine, 440.0, 0.0));
        b.close();

        List<FrequencyEmitter> remaining = new ArrayList<>();
        engine.forEachMatching(new TestReceiver(440.0, 0.0), (emitter, receivedSignal) -> remaining.add(emitter));

        assertEquals(2, remaining.size());
        assertTrue(remaining.stream().anyMatch(e -> e == first));
        assertTrue(remaining.stream().anyMatch(e -> e == third));

        a.close();
        assertEquals(1, countMatches(engine, 440.0, 0.0));

        c.close();
        assertEquals(0, countMatches(engine, 440.0, 0.0));
    }

    @Test
    void registrationAndRemoval() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        assertEquals(1, engine.emitterCount());
        assertEquals(1, countMatches(engine, 440.0, 0.0));
        registration.close();

        assertEquals(0, engine.emitterCount());
        assertEquals(0, countMatches(engine, 440.0, 0.0));
        registration.close();

        assertEquals(0, engine.emitterCount());
    }

    @Test
    void duplicateRegistrationRejected() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);
        assertThrows(IllegalStateException.class, () -> engine.register(emitter));
        assertEquals(1, engine.emitterCount());
        registration.close();
    }

    @Test
    void sableSublevelRemovalCleansOnlyOwnedEmitters() {
        UUID shipA = UUID.randomUUID();
        UUID shipB = UUID.randomUUID();

        TestEmitter emitterA = new TestEmitter(440.0);
        TestEmitter emitterB = new TestEmitter(880.0);
        TestEmitter manual = new TestEmitter(660.0);

        FrequencyRegistration a = engine.registerSublevelBound(shipA, emitterA);
        FrequencyRegistration b = engine.registerSublevelBound(shipB, emitterB);
        FrequencyRegistration m = engine.register(manual);

        assertEquals(3, engine.emitterCount());
        engine.onSublevelRemoved(shipA);

        assertEquals(2, engine.emitterCount());
        assertEquals(0, countMatches(engine, 440.0, 0.0));
        assertEquals(1, countMatches(engine, 880.0, 0.0));
        assertEquals(1, countMatches(engine, 660.0, 0.0));

        engine.onSublevelRemoved(shipA);
        a.close();

        FrequencyRegistration reloaded = engine.registerSublevelBound(shipA, emitterA);
        assertEquals(3, engine.emitterCount());

        engine.onSublevelRemoved(shipA);
        assertEquals(2, engine.emitterCount());

        reloaded.close();
        b.close();
        m.close();

        assertEquals(0, engine.emitterCount());
    }

    @Test
    void chunkBoundRegistrationAutomaticallyClosesOnUnload() {
        TestEmitter emitter = new TestEmitter(440.0);

        FrequencyRegistration registration = engine.registerChunkBound(new BlockPos(32, 64, -17), emitter);
        assertEquals(1, engine.emitterCount());

        engine.onChunkUnload(ChunkPos.asLong(2, -2));

        assertEquals(0, engine.emitterCount());
        assertEquals(0, countMatches(engine, 440.0, 0.0));

        engine.onChunkUnload(ChunkPos.asLong(2, -2));
        registration.close();

        assertEquals(0, engine.emitterCount());
        FrequencyRegistration reloaded = engine.registerChunkBound(new BlockPos(32, 64, -17), emitter);
        assertEquals(1, engine.emitterCount());

        reloaded.close();
        assertEquals(0, engine.emitterCount());
    }

    @Test
    void chunkUnloadDoesNotRemoveOtherRegistrations() {
        TestEmitter owned = new TestEmitter(440.0);
        TestEmitter otherChunk = new TestEmitter(880.0);
        TestEmitter manual = new TestEmitter(660.0);

        FrequencyRegistration ownedHandle = engine.registerChunkBound(new BlockPos(32, 64, -17), owned);
        FrequencyRegistration otherHandle = engine.registerChunkBound(new BlockPos(48, 64, -17), otherChunk);
        FrequencyRegistration manualHandle = engine.register(manual);

        assertEquals(3, engine.emitterCount());

        engine.onChunkUnload(ChunkPos.asLong(2, -2));

        assertEquals(2, engine.emitterCount());
        assertEquals(0, countMatches(engine, 440.0, 0.0));
        assertEquals(1, countMatches(engine, 880.0, 0.0));
        assertEquals(1, countMatches(engine, 660.0, 0.0));

        ownedHandle.close();
        otherHandle.close();
        manualHandle.close();

        assertEquals(0, engine.emitterCount());
    }

    @Test
    void multipleEmittersShareFrequency() {
        TestEmitter first = new TestEmitter(440.0);
        TestEmitter second = new TestEmitter(440.0);

        FrequencyRegistration firstRegistration = engine.register(first);
        FrequencyRegistration secondRegistration = engine.register(second);

        List<FrequencyEmitter> matches = new ArrayList<>();

        engine.forEachMatching(new TestReceiver(440.0, 0.0), (emitter, signal) -> matches.add(emitter));

        assertEquals(2, matches.size());
        assertTrue(matches.contains(first));
        assertTrue(matches.contains(second));

        firstRegistration.close();
        assertEquals(1, countMatches(engine, 440.0, 0.0));

        secondRegistration.close();
        assertEquals(0, countMatches(engine, 440.0, 0.0));
    }

    @Test
    void frequencyIndexUpdates() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        assertEquals(1, countMatches(engine, 440.0, 0.0));
        assertEquals(0, countMatches(engine, 880.0, 0.0));

        emitter.setFrequency(880.0);
        engine.updateEmitter(emitter);

        assertEquals(0, countMatches(engine, 440.0, 0.0));
        assertEquals(1, countMatches(engine, 880.0, 0.0));

        registration.close();
    }

    @Test
    void amplitudeChangesDoNotRequireReindexing() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        emitter.setAmplitude(0.25);

        List<FrequencySignal> matches = new ArrayList<>();

        engine.forEachMatching(new TestReceiver(440.0, 0.0), (source, signal) -> matches.add(signal));

        assertEquals(1, matches.size());
        assertEquals(0.25, matches.getFirst().amplitude(), 1.0e-9);

        registration.close();
    }

    @Test
    void inactiveEmitterIsNotReturned() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        emitter.emitting = false;

        assertEquals(1, engine.emitterCount());
        assertEquals(0, countMatches(engine, 440.0, 0.0));

        emitter.emitting = true;

        assertEquals(1, countMatches(engine, 440.0, 0.0));

        registration.close();
    }

    @Test
    void registrationCannotChangeDuringQuery() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        assertThrows(IllegalStateException.class, () -> engine.forEachMatching(new TestReceiver(440.0, 0.0), (source, signal) -> registration.close()));
        assertEquals(1, engine.emitterCount());
        registration.close();
        assertEquals(0, engine.emitterCount());
    }

    @Test
    void engineShutdownClearsRegistrations() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        engine.shutdown();

        registration.close();

        assertThrows(IllegalStateException.class, engine::emitterCount);
        assertThrows(IllegalStateException.class, () -> engine.register(new TestEmitter(880.0)));
    }

    @Test
    void separateEnginesDoNotShareSignals() {
        ServerLevel otherLevel = mock(ServerLevel.class);

        when(otherLevel.getServer()).thenReturn(server);

        FrequencyEngine otherEngine = new FrequencyEngine(otherLevel);

        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        assertEquals(1, countMatches(engine, 440.0, 0.0));
        assertEquals(0, countMatches(otherEngine, 440.0, 0.0));

        registration.close();

        engine.shutdown();
        otherEngine.shutdown();
    }

    @Test
    void engineRejectsOffThreadAccess() {
        when(server.isSameThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> engine.register(new TestEmitter(440.0)));
        assertThrows(IllegalStateException.class, engine::emitterCount);
    }

    @Test
    void entitySamplingRejectsUnregisteredEmitter() {
        TestEmitter emitter = new TestEmitter(440.0);
        Entity entity = mock(Entity.class);

        assertThrows(IllegalStateException.class, () -> engine.sampleEmitterMotion(emitter, entity, true));

        verifyNoInteractions(entity);
    }

    @Test
    void entitySamplingRejectsRemovedEntity() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        Entity entity = mock(Entity.class);
        when(entity.isRemoved()).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> engine.sampleEmitterMotion(emitter, entity, true));

        verify(entity, never()).position();
        registration.close();
    }

    @Test
    void entitySamplingRejectsDifferentLevel() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        Entity entity = mock(Entity.class);
        ServerLevel otherLevel = mock(ServerLevel.class);

        when(entity.level()).thenReturn(otherLevel);
        assertThrows(IllegalArgumentException.class, () -> engine.sampleEmitterMotion(emitter, entity, true));

        verify(entity, never()).position();
        registration.close();
    }

    @Test
    void entitySamplingRejectsOffThreadAccess() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);
        Entity entity = mock(Entity.class);

        when(server.isSameThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> engine.sampleEmitterMotion(emitter, entity, true));

        verifyNoInteractions(entity);
        when(server.isSameThread()).thenReturn(true);
        registration.close();
    }

    @Test
    void entitySamplingRejectsUnregisteredAfterClose() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);
        registration.close();

        Entity entity = mock(Entity.class);
        assertThrows(IllegalStateException.class, () -> engine.sampleEmitterMotion(emitter, entity, true));

        verifyNoInteractions(entity);
    }

    private static int countMatches(FrequencyEngine engine, double frequency, double tolerance) {
        int[] count = {0};
        engine.forEachMatching(new TestReceiver(frequency, tolerance), (emitter, signal) -> count[0]++);
        return count[0];
    }

    private static final class TestEmitter implements FrequencyEmitter {
        private FrequencySignal signal;
        private boolean emitting = true;

        private TestEmitter(double frequency) {
            signal = new FrequencySignal(frequency, 1.0, 0.0, 343.0);
        }

        private void setFrequency(double frequency) {
            signal = new FrequencySignal(frequency, signal.amplitude(), signal.phaseRadians(), signal.propagationSpeed());
        }

        private void setAmplitude(double amplitude) {
            signal = new FrequencySignal(signal.frequencyHz(), amplitude, signal.phaseRadians(), signal.propagationSpeed());
        }

        @Override
        public Vec3 position() {
            return Vec3.ZERO;
        }

        @Override
        public FrequencySignal signal() {
            return signal;
        }

        @Override
        public boolean isEmitting() {
            return emitting;
        }
    }

    private record TestReceiver(double tunedFrequencyHz, double toleranceHz) implements FrequencyReceiver {
        @Override
        public Vec3 position() {
            return Vec3.ZERO;
        }
    }

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
    void equalEmitterInstancesMaintainSeparateRegistrations() {
        record EqualEmitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {}

        FrequencySignal signal = new FrequencySignal(440.0, 1.0, 0.0, 343.0);
        EqualEmitter first = new EqualEmitter(Vec3.ZERO, signal);
        EqualEmitter second = new EqualEmitter(Vec3.ZERO, signal);

        assertEquals(first, second);
        assertNotSame(first, second);

        FrequencyRegistration firstHandle = engine.register(first);
        FrequencyRegistration secondHandle = engine.register(second);

        assertEquals(2, countMatches(engine, 440.0, 0.0));

        firstHandle.close();
        assertEquals(1, countMatches(engine, 440.0, 0.0));

        secondHandle.close();
        assertEquals(0, countMatches(engine, 440.0, 0.0));
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
        }
    }

    private static MockedStatic<FrequencySpace> mockWorldSpace(ServerLevel level) {
        MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class);
        space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(invocation -> invocation.getArgument(1));

        return space;
    }

    private static void registerInterferenceEmitter(FrequencyEngine engine, Vec3 position, FrequencySignal signal) {
        FrequencyEmitter emitter = mock(FrequencyEmitter.class);

        when(emitter.signal()).thenReturn(signal);
        when(emitter.isEmitting()).thenReturn(true);
        when(emitter.position()).thenReturn(position);

        engine.register(emitter);
    }
}
