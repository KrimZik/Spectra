package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.*;

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

    @Test
    void transmitterAndReceiverRegistrationsRemainIndependent() {
        TestEmitter transmitter = new TestEmitter(440.0);
        TestReceiver receiver = new TestReceiver(440.0, 0.0);

        assertEquals(0, engine.emitterCount());
        assertEquals(0, engine.receiverCount());

        FrequencyRegistration tx = engine.register(transmitter);

        assertEquals(1, engine.emitterCount());
        assertEquals(0, engine.receiverCount());

        FrequencyRegistration rx = engine.registerReceiver(receiver);

        assertEquals(1, engine.emitterCount());
        assertEquals(1, engine.receiverCount());

        tx.close();
        assertEquals(0, engine.emitterCount());
        assertEquals(1, engine.receiverCount());

        rx.close();
        assertEquals(0, engine.emitterCount());
        assertEquals(0, engine.receiverCount());

        tx.close();
        rx.close();

        FrequencyRegistration newTx = engine.register(new TestEmitter(440.0));
        assertEquals(1, countMatches(engine, 440.0, 0.0));
        assertEquals(0, engine.receiverCount());

        newTx.close();

        System.out.println("independentRegistration=PASS");
    }


    @Test
    void receiverRegistrationUsesObjectIdentity() {
        TestReceiver first = new TestReceiver(440.0, 0.0);
        TestReceiver second = new TestReceiver(440.0, 0.0);

        assertEquals(first, second);
        assertNotSame(first, second);

        FrequencyRegistration a = engine.registerReceiver(first);
        FrequencyRegistration b = engine.registerReceiver(second);

        assertEquals(2, engine.receiverCount());
        assertEquals(0, engine.emitterCount());

        assertThrows(IllegalStateException.class, () -> engine.registerReceiver(first));

        a.close();
        assertEquals(1, engine.receiverCount());

        b.close();
        assertEquals(0, engine.receiverCount());

        System.out.println("receiverIdentity=PASS");
    }


    @Test
    void transceiverSupportsIndependentTxAndRxLifecycles() {
        final class TestTransceiver implements FrequencyEmitter, FrequencyReceiver {
            private final FrequencySignal transmitted = new FrequencySignal(440.0, 1.0, 0.0, 343.0);
            private boolean transmitting = true;

            @Override
            public Vec3 position() {
                return Vec3.ZERO;
            }

            @Override
            public FrequencySignal signal() {
                return transmitted;
            }

            @Override
            public boolean isEmitting() {
                return transmitting;
            }

            @Override
            public double tunedFrequencyHz() {
                return 440.0;
            }

            @Override
            public double toleranceHz() {
                return 0.0;
            }
        }

        TestTransceiver device = new TestTransceiver();

        FrequencyRegistration tx = engine.register(device);
        FrequencyRegistration rx = engine.registerReceiver(device);

        assertEquals(1, engine.emitterCount());
        assertEquals(1, engine.receiverCount());
        assertEquals(1, countMatches(engine, 440.0, 0.0));

        device.transmitting = false;
        assertEquals(0, countMatches(engine, 440.0, 0.0));
        assertEquals(1, engine.receiverCount());

        device.transmitting = true;
        assertEquals(1, countMatches(engine, 440.0, 0.0));

        rx.close();
        assertEquals(1, engine.emitterCount());
        assertEquals(0, engine.receiverCount());
        assertEquals(1, countMatches(engine, 440.0, 0.0));

        FrequencyRegistration secondRx = engine.registerReceiver(device);
        assertEquals(1, engine.receiverCount());

        tx.close();
        assertEquals(0, engine.emitterCount());
        assertEquals(1, engine.receiverCount());
        assertEquals(0, countMatches(engine, 440.0, 0.0));

        secondRx.close();
        assertEquals(0, engine.receiverCount());

        System.out.println("transceiverIndependence=PASS");
    }

    @Test
    void chunkUnloadCleansTxAndRxIndependently() {
        BlockPos position = new BlockPos(32, 64, -17);

        TestEmitter tx = new TestEmitter(440.0);
        TestReceiver rx = new TestReceiver(440.0, 0.0);
        TestReceiver manual = new TestReceiver(880.0, 0.0);

        FrequencyRegistration txHandle = engine.registerChunkBound(position, tx);
        FrequencyRegistration rxHandle = engine.registerReceiverChunkBound(position, rx);
        FrequencyRegistration manualHandle = engine.registerReceiver(manual);

        assertEquals(1, engine.emitterCount());
        assertEquals(2, engine.receiverCount());

        engine.onChunkUnload(ChunkPos.asLong(2, -2));

        assertEquals(0, engine.emitterCount());
        assertEquals(1, engine.receiverCount());

        txHandle.close();
        rxHandle.close();

        manualHandle.close();
        assertEquals(0, engine.receiverCount());

        System.out.println("chunkCleanup=PASS");
    }

    @Test
    void entityLeaveCleansTxAndRxIndependently() {
        Entity owner = mock(Entity.class);
        when(owner.level()).thenReturn(level);

        TestEmitter tx = new TestEmitter(440.0);
        TestReceiver rx = new TestReceiver(440.0, 0.0);

        FrequencyRegistration txHandle = engine.registerEntityBound(owner, tx);
        FrequencyRegistration rxHandle = engine.registerReceiverEntityBound(owner, rx);

        assertEquals(1, engine.emitterCount());
        assertEquals(1, engine.receiverCount());

        engine.onEntityLeave(owner);

        assertEquals(0, engine.emitterCount());
        assertEquals(0, engine.receiverCount());

        txHandle.close();
        rxHandle.close();

        System.out.println("entityCleanup=PASS");
    }

    @Test
    void sableSublevelRemovalCleansTxAndRx() {
        UUID ship = UUID.randomUUID();

        TestEmitter tx = new TestEmitter(440.0);
        TestReceiver rx = new TestReceiver(440.0, 0.0);

        FrequencyRegistration txHandle = engine.registerSublevelBound(ship, tx);
        FrequencyRegistration rxHandle = engine.registerReceiverSublevelBound(ship, rx);

        assertEquals(1, engine.emitterCount());
        assertEquals(1, engine.receiverCount());

        engine.onSublevelRemoved(ship);

        assertEquals(0, engine.emitterCount());
        assertEquals(0, engine.receiverCount());

        txHandle.close();
        rxHandle.close();

        FrequencyRegistration reloaded = engine.registerReceiverSublevelBound(ship, rx);
        assertEquals(1, engine.receiverCount());

        engine.onSublevelRemoved(ship);
        assertEquals(0, engine.receiverCount());
        reloaded.close();

        System.out.println("sableCleanup=PASS");
    }


    @Test
    void receiverSubscriptionTracksExplicitRetuning() {
        final class MutableReceiver implements FrequencyReceiver {
            double frequency = 440.0;
            double tolerance = 0.5;

            @Override
            public Vec3 position() {
                return Vec3.ZERO;
            }

            @Override
            public double tunedFrequencyHz() {
                return frequency;
            }

            @Override
            public double toleranceHz() {
                return tolerance;
            }
        }

        MutableReceiver receiver = new MutableReceiver();

        FrequencyRegistration registration = engine.registerReceiver(receiver);

        assertEquals(1, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());
        assertEquals(0, engine.emitterCount());

        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);
        assertEquals(1, engine.subscribedReceiverCount());

        assertTrue(engine.receiverSubscriptionCovers(receiver, 440.0));
        assertTrue(engine.receiverSubscriptionCovers(receiver, 440.5));
        assertFalse(engine.receiverSubscriptionCovers(receiver, 441.0));

        receiver.frequency = 880.0;
        receiver.tolerance = 1.0;

        assertTrue(engine.receiverSubscriptionCovers(receiver, 440.0));
        assertFalse(engine.receiverSubscriptionCovers(receiver, 880.0));

        engine.updateReceiverSubscription(receiver);

        assertFalse(engine.receiverSubscriptionCovers(receiver, 440.0));
        assertTrue(engine.receiverSubscriptionCovers(receiver, 879.0));
        assertTrue(engine.receiverSubscriptionCovers(receiver, 881.0));
        assertFalse(engine.receiverSubscriptionCovers(receiver, 881.5));

        receiver.frequency = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> engine.updateReceiverSubscription(receiver));

        assertTrue(engine.receiverSubscriptionCovers(receiver, 880.0));
        subscription.close();

        assertEquals(0, engine.subscribedReceiverCount());
        assertEquals(1, engine.receiverCount());

        subscription.close();
        registration.close();

        assertEquals(0, engine.receiverCount());

        System.out.println("tuning=PASS subscriptionLifecycle=PASS");
    }


    @Test
    void receiverSubscriptionsCleanUpWithOwners() {
        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        BlockPos pos = new BlockPos(32, 64, -17);

        FrequencyRegistration chunkRegistration = engine.registerReceiverChunkBound(pos, receiver);
        FrequencyRegistration oldSubscription = engine.subscribeReceiver(receiver);

        assertEquals(1, engine.receiverCount());
        assertEquals(1, engine.subscribedReceiverCount());

        engine.onChunkUnload(ChunkPos.asLong(2, -2));

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());

        UUID ship = UUID.randomUUID();

        FrequencyRegistration shipRegistration = engine.registerReceiverSublevelBound(ship, receiver);
        FrequencyRegistration newSubscription = engine.subscribeReceiver(receiver);

        assertEquals(1, engine.receiverCount());
        assertEquals(1, engine.subscribedReceiverCount());

        oldSubscription.close();
        chunkRegistration.close();

        assertEquals(1, engine.subscribedReceiverCount());
        assertTrue(engine.receiverSubscriptionCovers(receiver, 440.0));

        engine.onSublevelRemoved(ship);

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());

        newSubscription.close();
        shipRegistration.close();

        System.out.println("ownerCleanup=PASS staleHandleIsolation=PASS");
    }


    @Test
    void receiverSubscriptionCannotChangeDuringSignalQuery() {
        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        FrequencyRegistration rxRegistration = engine.registerReceiver(receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);
        FrequencyRegistration txRegistration = engine.register(new TestEmitter(440.0));

        assertThrows(IllegalStateException.class, () -> engine.forEachMatching(receiver, (emitter, signal) -> subscription.close()));

        assertEquals(1, engine.subscribedReceiverCount());
        assertTrue(engine.receiverSubscriptionCovers(receiver, 440.0));

        assertThrows(IllegalStateException.class, () -> engine.forEachMatching(receiver, (emitter, signal) -> engine.updateReceiverSubscription(receiver)));
        assertEquals(1, engine.subscribedReceiverCount());

        subscription.close();
        rxRegistration.close();
        txRegistration.close();

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.emitterCount());

        System.out.println("queryMutationProtection=PASS");
    }


    @Test
    void indexedSubscriptionsFindOverlappingBands() {
        TestReceiver narrow = new TestReceiver(440.0, 0.0);
        TestReceiver equalButDistinct = new TestReceiver(440.0, 0.0);
        TestReceiver wide = new TestReceiver(450.0, 10.0);
        TestReceiver distant = new TestReceiver(880.0, 0.0);
        TestReceiver unsubscribed = new TestReceiver(440.0, 0.0);

        FrequencyRegistration[] registrations = {
                engine.registerReceiver(narrow),
                engine.registerReceiver(equalButDistinct),
                engine.registerReceiver(wide),
                engine.registerReceiver(distant),
                engine.registerReceiver(unsubscribed)
        };

        FrequencyRegistration[] subscriptions = {
                engine.subscribeReceiver(narrow),
                engine.subscribeReceiver(equalButDistinct),
                engine.subscribeReceiver(wide),
                engine.subscribeReceiver(distant)
        };

        List<FrequencyReceiver> found = new ArrayList<>();

        engine.forEachReceiverSubscribedTo(440.0, found::add);

        assertEquals(3, found.size());
        assertTrue(found.stream().anyMatch(r -> r == narrow));
        assertTrue(found.stream().anyMatch(r -> r == equalButDistinct));
        assertTrue(found.stream().anyMatch(r -> r == wide));
        assertFalse(found.stream().anyMatch(r -> r == unsubscribed));

        found.clear();
        engine.forEachReceiverSubscribedTo(460.0, found::add);

        assertEquals(1, found.size());
        assertSame(wide, found.getFirst());

        found.clear();
        engine.forEachReceiverSubscribedTo(460.0001, found::add);
        assertTrue(found.isEmpty());

        found.clear();
        engine.forEachReceiverSubscribedTo(880.0, found::add);

        assertEquals(1, found.size());
        assertSame(distant, found.getFirst());

        for (FrequencyRegistration subscription : subscriptions) {
            subscription.close();
        }

        for (FrequencyRegistration registration : registrations) {
            registration.close();
        }

        assertEquals(0, engine.subscribedReceiverCount());
        System.out.println("overlappingBands=PASS");
    }

    @Test
    void indexedSubscriptionsRetuneAndCleanUp() {
        final class MutableReceiver implements FrequencyReceiver {
            double frequency = 440.0;
            double tolerance = 1.0;

            @Override
            public Vec3 position() {
                return Vec3.ZERO;
            }

            @Override
            public double tunedFrequencyHz() {
                return frequency;
            }

            @Override
            public double toleranceHz() {
                return tolerance;
            }
        }

        MutableReceiver receiver = new MutableReceiver();

        FrequencyRegistration registration = engine.registerReceiver(receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);

        List<FrequencyReceiver> found = new ArrayList<>();

        engine.forEachReceiverSubscribedTo(440.0, found::add);
        assertEquals(1, found.size());

        receiver.frequency = 880.0;
        receiver.tolerance = 2.0;

        found.clear();
        engine.forEachReceiverSubscribedTo(440.0, found::add);
        assertEquals(1, found.size());

        engine.updateReceiverSubscription(receiver);

        found.clear();
        engine.forEachReceiverSubscribedTo(440.0, found::add);
        assertTrue(found.isEmpty());

        engine.forEachReceiverSubscribedTo(878.0, found::add);
        assertEquals(1, found.size());

        found.clear();
        engine.forEachReceiverSubscribedTo(882.0, found::add);
        assertEquals(1, found.size());

        assertThrows(IllegalStateException.class, () -> engine.forEachReceiverSubscribedTo(880.0, r -> subscription.close()));
        assertEquals(1, engine.subscribedReceiverCount());

        registration.close();

        found.clear();
        engine.forEachReceiverSubscribedTo(880.0, found::add);

        assertTrue(found.isEmpty());
        assertEquals(0, engine.subscribedReceiverCount());

        subscription.close();
        System.out.println("retuning=PASS cleanup=PASS");
    }

    @Test
    void indexedSubscriptionsScaleWithoutFalseMatches() {
        final int count = 4_096;
        final double base = 100_000.0;

        FrequencyRegistration[] registrations = new FrequencyRegistration[count];
        FrequencyRegistration[] subscriptions = new FrequencyRegistration[count];

        for (int i = 0; i < count; i++) {
            TestReceiver receiver = new TestReceiver(base + i * 2.0, 0.0);
            registrations[i] = engine.registerReceiver(receiver);
            subscriptions[i] = engine.subscribeReceiver(receiver);
        }

        assertEquals(count, engine.receiverCount());
        assertEquals(count, engine.subscribedReceiverCount());

        for (int i = 0; i < count; i += 127) {
            final int[] hits = {0};

            engine.forEachReceiverSubscribedTo(base + i * 2.0, receiver -> hits[0]++);
            assertEquals(1, hits[0], "Missing receiver " + i);

            hits[0] = 0;
            engine.forEachReceiverSubscribedTo(base + i * 2.0 + 1.0, receiver -> hits[0]++);

            assertEquals(0, hits[0], "False frequency match " + i);
        }

        for (FrequencyRegistration subscription : subscriptions) {
            subscription.close();
        }

        for (FrequencyRegistration registration : registrations) {
            registration.close();
        }

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());

        System.out.println("indexed4096=PASS");
    }


    @Test
    void subscribedReceiverSpatialSnapshotsTrackExplicitMovement() {
        final class MovingReceiver implements FrequencyReceiver {
            Vec3 location = new Vec3(4.0, 0.0, 0.0);
            double frequency = 440.0;

            @Override
            public Vec3 position() {
                return location;
            }

            @Override
            public double tunedFrequencyHz() {
                return frequency;
            }

            @Override
            public double toleranceHz() {
                return 1.0;
            }
        }

        MovingReceiver receiver = new MovingReceiver();
        FrequencyRegistration registration = engine.registerReceiver(receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);

        List<FrequencyReceiver> found = new ArrayList<>();
        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, Vec3.ZERO, 8.0, found::add);
            assertTrue(found.isEmpty());

            engine.updateReceiverWorldPosition(receiver);
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, Vec3.ZERO, 8.0, found::add);

            assertEquals(1, found.size());
            assertSame(receiver, found.getFirst());

            found.clear();

            receiver.location = new Vec3(100.0, 0.0, 0.0);
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, Vec3.ZERO, 8.0, found::add);

            assertEquals(1, found.size());
            engine.updateReceiverWorldPosition(receiver);

            found.clear();

            engine.forEachReceiverSubscribedSnapshotInRange(440.0, Vec3.ZERO, 8.0, found::add);
            assertTrue(found.isEmpty());
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, new Vec3(100.0, 0.0, 0.0), 0.0, found::add);

            assertEquals(1, found.size());
            receiver.frequency = 880.0;
            engine.updateReceiverSubscription(receiver);

            found.clear();

            engine.forEachReceiverSubscribedSnapshotInRange(440.0, receiver.location, 1.0, found::add);
            assertTrue(found.isEmpty());

            engine.forEachReceiverSubscribedSnapshotInRange(880.0, receiver.location, 1.0, found::add);
            assertEquals(1, found.size());
            assertThrows(IllegalStateException.class, () -> engine.forEachReceiverSubscribedSnapshotInRange(880.0, receiver.location, 1.0, engine::updateReceiverWorldPosition));

            subscription.close();
            found.clear();
            engine.forEachReceiverSubscribedSnapshotInRange(880.0, receiver.location, 1.0, found::add);

            assertTrue(found.isEmpty());
        }

        registration.close();

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());

        System.out.println("movement=PASS " + "retuning=PASS cleanup=PASS");
    }


    @Test
    void sableReceiverSpatialSnapshotsUseWorldProjection() {
        UUID ship = UUID.randomUUID();
        Vec3 localPosition = new Vec3(1.0, 0.0, 0.0);

        FrequencyReceiver receiver = new FrequencyReceiver() {
            @Override
            public Vec3 position() {
                return localPosition;
            }

            @Override
            public double tunedFrequencyHz() {
                return 440.0;
            }

            @Override
            public double toleranceHz() {
                return 0.0;
            }
        };

        FrequencyRegistration registration = engine.registerReceiverSublevelBound(ship, receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);

        Vec3[] worldPosition = {
                new Vec3(200.0, 70.0, 300.0)
        };

        List<FrequencyReceiver> found = new ArrayList<>();

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(level), eq(localPosition))).thenAnswer(invocation -> worldPosition[0]);

            engine.updateReceiverWorldPosition(receiver);
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, worldPosition[0], 0.0, found::add);

            assertEquals(1, found.size());
            worldPosition[0] = new Vec3(500.0, 70.0, 300.0);

            engine.updateReceiverWorldPosition(receiver);

            found.clear();
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, new Vec3(200.0, 70.0, 300.0), 0.0, found::add);

            assertTrue(found.isEmpty());
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, worldPosition[0], 0.0, found::add);

            assertEquals(1, found.size());
            engine.onSublevelRemoved(ship);

            found.clear();

            engine.forEachReceiverSubscribedSnapshotInRange(440.0, worldPosition[0], 0.0, found::add);
            assertTrue(found.isEmpty());

            assertEquals(0, engine.receiverCount());
            assertEquals(0, engine.subscribedReceiverCount());
        }

        subscription.close();
        registration.close();

        System.out.println("sableProjection=PASS " + "shipCleanup=PASS");
    }


    @Test
    void liveDiscoveryDoesNotTrustStaleSpatialSnapshots() {
        final class MovingReceiver implements FrequencyReceiver {
            Vec3 location;
            double frequency;

            MovingReceiver(Vec3 location, double frequency) {
                this.location = location;
                this.frequency = frequency;
            }

            @Override
            public Vec3 position() {
                return location;
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

        MovingReceiver movedAway = new MovingReceiver(new Vec3(3, 0, 0), 440.0);
        MovingReceiver movedCloser = new MovingReceiver(new Vec3(200, 0, 0), 440.0);
        MovingReceiver wrongFrequency = new MovingReceiver(Vec3.ZERO, 880.0);

        FrequencyRegistration a = engine.registerReceiver(movedAway);
        FrequencyRegistration b = engine.registerReceiver(movedCloser);
        FrequencyRegistration c = engine.registerReceiver(wrongFrequency);

        FrequencyRegistration sa = engine.subscribeReceiver(movedAway);
        FrequencyRegistration sb = engine.subscribeReceiver(movedCloser);
        FrequencyRegistration sc = engine.subscribeReceiver(wrongFrequency);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            engine.updateReceiverWorldPosition(movedAway);
            engine.updateReceiverWorldPosition(movedCloser);

            movedAway.location = new Vec3(300, 0, 0);
            movedCloser.location = new Vec3(2, 0, 0);

            List<FrequencyReceiver> found = new ArrayList<>();

            engine.forEachReceiverSubscribedLiveInRange(440.0, Vec3.ZERO, 5.0, found::add);

            assertEquals(1, found.size());
            assertSame(movedCloser, found.getFirst());

            found.clear();
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, Vec3.ZERO, 5.0, found::add);

            assertEquals(1, found.size());
            assertSame(movedAway, found.getFirst());

            found.clear();
            engine.forEachReceiverSubscribedLiveInRange(880.0, Vec3.ZERO, 0.0, found::add);

            assertEquals(1, found.size());
            assertSame(wrongFrequency, found.getFirst());
        }

        sa.close();
        sb.close();
        sc.close();
        a.close();
        b.close();
        c.close();

        System.out.println("staleSnapshotFallback=PASS");
    }


    @Test
    void liveEmitterDiscoveryTracksSableWorldMovement() {
        Vec3 txLocal = new Vec3(1, 0, 0);
        Vec3 rxLocal = new Vec3(2, 0, 0);

        final class MutableEmitter implements FrequencyEmitter {
            FrequencySignal wave = new FrequencySignal(440.0, 1.0, 0.0, 343.0);
            boolean enabled = true;

            @Override
            public Vec3 position() {
                return txLocal;
            }

            @Override
            public FrequencySignal signal() {
                return wave;
            }

            @Override
            public boolean isEmitting() {
                return enabled;
            }
        }

        final class MutableReceiver implements FrequencyReceiver {
            double frequency = 440.0;

            @Override
            public Vec3 position() {
                return rxLocal;
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

        MutableEmitter transmitter = new MutableEmitter();
        MutableReceiver receiver = new MutableReceiver();

        FrequencyRegistration tx = engine.register(transmitter);
        FrequencyRegistration rx = engine.registerReceiver(receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);

        Vec3[] txWorld = {new Vec3(400, 70, 500)};
        Vec3[] rxWorld = {new Vec3(402, 70, 500)};

        List<FrequencyReceiver> found = new ArrayList<>();

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class)))
                    .thenAnswer(invocation -> {
                        Vec3 local = invocation.getArgument(1);

                        if (local == txLocal) return txWorld[0];
                        if (local == rxLocal) return rxWorld[0];

                        throw new AssertionError("Unexpected position");
                    });

            engine.forEachSubscribedReceiverForEmitterInRange(transmitter, 2.0, found::add);
            assertEquals(1, found.size());
            rxWorld[0] = new Vec3(800, 70, 500);
            found.clear();

            engine.forEachSubscribedReceiverForEmitterInRange(transmitter, 2.0, found::add);
            assertTrue(found.isEmpty());
            txWorld[0] = new Vec3(798, 70, 500);

            engine.forEachSubscribedReceiverForEmitterInRange(transmitter, 2.0, found::add);
            assertEquals(1, found.size());
            transmitter.wave = new FrequencySignal(880.0, 1.0, 0.0, 343.0);
            engine.updateEmitter(transmitter);
            found.clear();

            engine.forEachSubscribedReceiverForEmitterInRange(transmitter, 2.0, found::add);
            assertTrue(found.isEmpty());

            receiver.frequency = 880.0;
            engine.updateReceiverSubscription(receiver);
            engine.forEachSubscribedReceiverForEmitterInRange(transmitter, 2.0, found::add);

            assertEquals(1, found.size());
            transmitter.enabled = false;
            found.clear();

            engine.forEachSubscribedReceiverForEmitterInRange(transmitter, 2.0, found::add);

            assertTrue(found.isEmpty());
        }

        subscription.close();
        rx.close();
        tx.close();

        System.out.println("movingSableTxRx=PASS " + "retuning=PASS transmissionState=PASS");
    }


    @Test
    void liveDiscoveryRejectsMutationDuringCallbacks() {
        TestReceiver receiver = new TestReceiver(440.0, 0.0);

        FrequencyRegistration registration = engine.registerReceiver(receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertThrows(IllegalStateException.class, () -> engine.forEachReceiverSubscribedLiveInRange(440.0, Vec3.ZERO, 1.0, r -> subscription.close()));
            assertEquals(1, engine.subscribedReceiverCount());
            assertThrows(IllegalStateException.class, () -> engine.forEachReceiverSubscribedLiveInRange(440.0, Vec3.ZERO, 1.0, engine::updateReceiverWorldPosition));

            assertEquals(1, engine.receiverCount());
            assertEquals(1, engine.subscribedReceiverCount());
        }

        subscription.close();
        registration.close();

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());

        System.out.println("mutationProtection=PASS");
    }


    @Test
    void spatialRefreshQueueRespectsBudgetsAndMovement() {
        final class MovingReceiver implements FrequencyReceiver {
            Vec3 position;

            MovingReceiver(double x) {
                position = new Vec3(x, 0, 0);
            }

            @Override
            public Vec3 position() {
                return position;
            }

            @Override
            public double tunedFrequencyHz() {
                return 440.0;
            }

            @Override
            public double toleranceHz() {
                return 0.0;
            }
        }

        MovingReceiver first = new MovingReceiver(10.0);
        MovingReceiver second = new MovingReceiver(20.0);

        FrequencyRegistration a = engine.registerReceiver(first);
        FrequencyRegistration b = engine.registerReceiver(second);
        FrequencyRegistration sa = engine.subscribeReceiver(first);
        FrequencyRegistration sb = engine.subscribeReceiver(second);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            engine.updateReceiverWorldPosition(first);
            engine.updateReceiverWorldPosition(second);

            first.position = new Vec3(100, 0, 0);
            second.position = new Vec3(200, 0, 0);

            engine.markReceiverWorldPositionDirty(first);
            engine.markReceiverWorldPositionDirty(first);
            engine.markReceiverWorldPositionDirty(second);

            assertEquals(2, engine.pendingReceiverWorldPositionRefreshCount());
            List<FrequencyReceiver> found = new ArrayList<>();
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, new Vec3(10, 0, 0), 0.0, found::add);

            assertTrue(found.isEmpty());
            assertEquals(0, engine.processDirtyReceiverWorldPositions(0));
            assertEquals(2, engine.pendingReceiverWorldPositionRefreshCount());
            assertEquals(1, engine.processDirtyReceiverWorldPositions(1));
            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

            engine.forEachReceiverSubscribedSnapshotInRange(440.0, new Vec3(100, 0, 0), 0.0, found::add);

            assertEquals(1, found.size());
            assertSame(first, found.getFirst());

            found.clear();
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, new Vec3(200, 0, 0), 0.0, found::add);

            assertTrue(found.isEmpty());
            engine.forEachReceiverSubscribedLiveInRange(440.0, new Vec3(200, 0, 0), 0.0, found::add);

            assertEquals(1, found.size());
            assertSame(second, found.getFirst());

            assertEquals(1, engine.processDirtyReceiverWorldPositions(1));
            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

            engine.markReceiverWorldPositionDirty(first);
            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

            engine.updateReceiverWorldPosition(first);

            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
            assertThrows(IllegalArgumentException.class, () -> engine.processDirtyReceiverWorldPositions(-1));
        }

        sa.close();
        sb.close();
        a.close();
        b.close();

        System.out.println("deduplication=PASS " + "budget=PASS movement=PASS " + "liveFallback=PASS");
    }

    @Test
    void spatialRefreshQueueHandlesCleanupAndFailures() {
        TestReceiver chunkReceiver = new TestReceiver(440.0, 0.0);
        TestReceiver shipReceiver = new TestReceiver(880.0, 0.0);

        FrequencyRegistration chunk = engine.registerReceiverChunkBound(new BlockPos(32, 64, -17), chunkReceiver);
        FrequencyRegistration ship = engine.registerReceiverSublevelBound(UUID.randomUUID(), shipReceiver);
        FrequencyRegistration chunkSub = engine.subscribeReceiver(chunkReceiver);
        FrequencyRegistration shipSub = engine.subscribeReceiver(shipReceiver);

        engine.markReceiverWorldPositionDirty(chunkReceiver);
        engine.markReceiverWorldPositionDirty(shipReceiver);
        assertEquals(2, engine.pendingReceiverWorldPositionRefreshCount());

        engine.onChunkUnload(ChunkPos.asLong(2, -2));
        assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());
        shipSub.close();

        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
        assertEquals(0, engine.processDirtyReceiverWorldPositions(10));

        chunkSub.close();
        chunk.close();
        ship.close();

        final class FailingReceiver implements FrequencyReceiver {
            boolean fail = true;

            @Override
            public Vec3 position() {
                if (fail) {
                    throw new IllegalStateException("Position unavailable");
                }
                return Vec3.ZERO;
            }

            @Override
            public double tunedFrequencyHz() {
                return 440.0;
            }

            @Override
            public double toleranceHz() {
                return 0.0;
            }
        }

        FailingReceiver receiver = new FailingReceiver();
        FrequencyRegistration registration = engine.registerReceiver(receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);

        engine.markReceiverWorldPositionDirty(receiver);

        assertThrows(IllegalStateException.class, () -> engine.processDirtyReceiverWorldPositions(1));
        assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

        receiver.fail = false;

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertEquals(1, engine.processDirtyReceiverWorldPositions(1));
        }

        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

        subscription.close();
        registration.close();

        System.out.println("cleanup=PASS " + "failedRefreshRetry=PASS");
    }

    @Test
    void entityMotionInvalidatesOnlyOwnedReceiverSnapshots() {
        final class MovingReceiver implements FrequencyReceiver {
            Vec3 location;

            MovingReceiver(double x) {
                location = new Vec3(x, 0, 0);
            }

            @Override
            public Vec3 position() {
                return location;
            }

            @Override
            public double tunedFrequencyHz() {
                return 440.0;
            }

            @Override
            public double toleranceHz() {
                return 0.0;
            }
        }

        Entity owner = mock(Entity.class);
        when(owner.level()).thenReturn(level);

        MovingReceiver owned = new MovingReceiver(10.0);
        MovingReceiver unrelated = new MovingReceiver(100.0);

        FrequencyRegistration ownedHandle = engine.registerReceiverEntityBound(owner, owned);
        FrequencyRegistration unrelatedHandle = engine.registerReceiver(unrelated);
        FrequencyRegistration ownedSub = engine.subscribeReceiver(owned);
        FrequencyRegistration unrelatedSub = engine.subscribeReceiver(unrelated);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            engine.updateReceiverWorldPosition(owned);
            engine.updateReceiverWorldPosition(unrelated);

            owned.location = new Vec3(200, 0, 0);
            engine.onEntityPositionChanged(owner);
            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());
            List<FrequencyReceiver> found = new ArrayList<>();

            engine.forEachReceiverSubscribedSnapshotInRange(440.0, new Vec3(10, 0, 0), 0.0, found::add);
            assertTrue(found.isEmpty());

            engine.forEachReceiverSubscribedSnapshotInRange(440.0, new Vec3(100, 0, 0), 0.0, found::add);
            assertEquals(1, found.size());
            assertSame(unrelated, found.getFirst());

            found.clear();

            engine.forEachReceiverSubscribedLiveInRange(440.0, new Vec3(200, 0, 0), 0.0, found::add);

            assertEquals(1, found.size());
            assertSame(owned, found.getFirst());

            assertEquals(1, engine.processDirtyReceiverWorldPositions(1));
            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
            found.clear();

            engine.forEachReceiverSubscribedSnapshotInRange(440.0, new Vec3(200, 0, 0), 0.0, found::add);

            assertEquals(1, found.size());
            assertSame(owned, found.getFirst());
        }

        engine.onEntityLeave(owner);

        assertEquals(1, engine.receiverCount());

        ownedSub.close();
        unrelatedSub.close();
        ownedHandle.close();
        unrelatedHandle.close();

        assertEquals(0, engine.receiverCount());

        System.out.println("entitySelectiveInvalidation=PASS");
    }

    @Test
    void sablePoseChangeInvalidatesOnlyAffectedReceivers() {
        UUID shipA = UUID.randomUUID();
        UUID shipB = UUID.randomUUID();

        TestReceiver receiverA = new TestReceiver(440.0, 0.0);
        TestReceiver receiverB = new TestReceiver(880.0, 0.0);

        FrequencyRegistration a = engine.registerReceiverSublevelBound(shipA, receiverA);
        FrequencyRegistration b = engine.registerReceiverSublevelBound(shipB, receiverB);

        FrequencyRegistration sa = engine.subscribeReceiver(receiverA);
        FrequencyRegistration sb = engine.subscribeReceiver(receiverB);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            engine.updateReceiverWorldPosition(receiverA);
            engine.updateReceiverWorldPosition(receiverB);
            engine.onSublevelPoseChanged(shipA);
            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

            engine.onSublevelPoseChanged(shipA);
            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

            List<FrequencyReceiver> found = new ArrayList<>();
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, Vec3.ZERO, 0.0, found::add);
            assertTrue(found.isEmpty());

            engine.forEachReceiverSubscribedSnapshotInRange(880.0, Vec3.ZERO, 0.0, found::add);
            assertEquals(1, found.size());
            assertSame(receiverB, found.getFirst());
        }

        engine.onSublevelRemoved(shipA);

        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
        assertEquals(1, engine.receiverCount());

        engine.onSublevelPoseChanged(shipA);
        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

        engine.onSublevelRemoved(shipB);
        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());

        sa.close();
        sb.close();
        a.close();
        b.close();

        System.out.println("sableSelectiveInvalidation=PASS " + "cleanup=PASS");
    }

    @Test
    void entityTickOnlyInvalidatesWhenOwnerMoves() {
        Entity owner = mock(Entity.class);
        when(owner.level()).thenReturn(level);
        when(owner.position()).thenReturn(new Vec3(10.0, 0.0, 0.0));

        TestReceiver receiver = new TestReceiver(440.0, 0.0);

        FrequencyRegistration registration = engine.registerReceiverEntityBound(owner, receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            engine.updateReceiverWorldPosition(receiver);

            engine.onTrackedEntityTick(owner);
            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

            engine.processDirtyReceiverWorldPositions(1);

            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

            engine.onTrackedEntityTick(owner);
            engine.onTrackedEntityTick(owner);

            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

            when(owner.position()).thenReturn(new Vec3(11.0, 0.0, 0.0));

            engine.onTrackedEntityTick(owner);
            engine.onTrackedEntityTick(owner);

            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

            engine.processDirtyReceiverWorldPositions(1);
            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
        }

        engine.onEntityLeave(owner);

        assertEquals(0, engine.receiverCount());
        assertEquals(
                0, engine.pendingReceiverWorldPositionRefreshCount());

        subscription.close();
        registration.close();

        System.out.println("entityMovement=PASS " + "stationarySkip=PASS cleanup=PASS");
    }

    @Test
    void entityTickTracksOnlyOwnersWithReceivers() {
        Entity owner = mock(Entity.class);
        when(owner.level()).thenReturn(level);
        doReturn(new Vec3(1.0, 2.0, 3.0)).when(owner).position();

        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration tx = engine.registerEntityBound(owner, emitter);

        engine.onTrackedEntityTick(owner);
        verify(owner, never()).position();

        TestReceiver first = new TestReceiver(440.0, 0.0);
        TestReceiver second = new TestReceiver(880.0, 0.0);

        FrequencyRegistration a = engine.registerReceiverEntityBound(owner, first);
        FrequencyRegistration b = engine.registerReceiverEntityBound(owner, second);

        FrequencyRegistration sa = engine.subscribeReceiver(first);
        FrequencyRegistration sb = engine.subscribeReceiver(second);

        engine.markReceiverWorldPositionDirty(first);
        engine.markReceiverWorldPositionDirty(second);
        engine.onTrackedEntityTick(owner);

        assertEquals(2, engine.pendingReceiverWorldPositionRefreshCount());
        a.close();

        assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());
        doReturn(new Vec3(4.0, 5.0, 6.0)).when(owner).position();
        engine.onTrackedEntityTick(owner);

        assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());
        b.close();

        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
        clearInvocations(owner);

        engine.onTrackedEntityTick(owner);
        verify(owner, never()).position();

        sa.close();
        sb.close();
        tx.close();

        System.out.println("ownerLookup=PASS " + "lastReceiverCleanup=PASS");
    }


    @Test
    void sablePoseObserverDetectsChangesWithoutUnnecessaryRefresh() {
        UUID shipA = UUID.randomUUID();
        UUID shipB = UUID.randomUUID();

        TestReceiver receiverA = new TestReceiver(440.0, 0.0);
        TestReceiver receiverB = new TestReceiver(880.0, 0.0);

        FrequencyRegistration a = engine.registerReceiverSublevelBound(shipA, receiverA);
        FrequencyRegistration b = engine.registerReceiverSublevelBound(shipB, receiverB);

        FrequencyRegistration sa = engine.subscribeReceiver(receiverA);
        FrequencyRegistration sb = engine.subscribeReceiver(receiverB);

        SubLevelContainer container = mock(SubLevelContainer.class);
        SubLevel bodyA = mock(SubLevel.class);
        SubLevel bodyB = mock(SubLevel.class);

        Pose3d poseA = new Pose3d();
        Pose3d poseB = new Pose3d();

        when(container.getSubLevel(shipA)).thenReturn(bodyA);
        when(container.getSubLevel(shipB)).thenReturn(bodyB);

        when(bodyA.logicalPose()).thenReturn(poseA);
        when(bodyB.logicalPose()).thenReturn(poseB);

        engine.markReceiverWorldPositionDirty(receiverA);
        engine.markReceiverWorldPositionDirty(receiverB);

        Map<UUID, Pose3d> snapshots = new HashMap<>();
        SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, snapshots);

        assertEquals(2, engine.pendingReceiverWorldPositionRefreshCount());

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertEquals(2, engine.processDirtyReceiverWorldPositions(2));

            SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, snapshots);
            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

            poseA.orientation().rotateY(Math.PI / 4.0);

            SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, snapshots);

            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());
            assertEquals(1, engine.processDirtyReceiverWorldPositions(1));

            SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, snapshots);
            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

            poseA.position().set(100.0, 70.0, 200.0);
            SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, snapshots);

            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());
            assertEquals(1, engine.processDirtyReceiverWorldPositions(1));

            poseA.scale().set(2.0, 1.0, 1.0);
            SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, snapshots);

            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());
            assertEquals(1, engine.processDirtyReceiverWorldPositions(1));

            poseB.rotationPoint().set(1.0, 0.0, 0.0);
            SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, snapshots);

            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());
        }

        engine.onSublevelRemoved(shipA);
        engine.onSublevelRemoved(shipB);

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

        sa.close();
        sb.close();
        a.close();
        b.close();

        System.out.println("rotation=PASS " + "translation=PASS " + "scale=PASS pivot=PASS " + "stationarySkip=PASS");
    }


    @Test
    void sablePoseObserverReleasesUnregisteredShipHistory() {
        UUID ship = UUID.randomUUID();
        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        FrequencyRegistration registration = engine.registerReceiverSublevelBound(ship, receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);

        SubLevelContainer container = mock(SubLevelContainer.class);
        SubLevel body = mock(SubLevel.class);
        Pose3d pose = new Pose3d();

        when(container.getSubLevel(ship)).thenReturn(body);
        when(body.logicalPose()).thenReturn(pose);

        engine.markReceiverWorldPositionDirty(receiver);

        Map<UUID, Pose3d> snapshots = new HashMap<>();
        SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, snapshots);

        assertEquals(1, snapshots.size());
        assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

        registration.close();
        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

        clearInvocations(container);
        SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, snapshots);

        assertTrue(snapshots.isEmpty());
        verify(container, never()).getSubLevel(ship);

        subscription.close();

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());

        System.out.println("manualCleanup=PASS " + "untrackedShipSkip=PASS");
    }

    @Test
    void levelTickRefreshesNewSubscriptionsWithinBudget() {
        FrequencyEngine managed = FrequencyApi.get(level);

        final int receiverCount = 130;

        try {
            for (int i = 0; i < receiverCount; i++) {
                TestReceiver receiver = new TestReceiver(440.0 + i, 0.0);

                managed.registerReceiverChunkBound(new BlockPos(i * 2, 0, 0), receiver);
                managed.subscribeFixedWorldReceiver(receiver);
            }

            assertEquals(receiverCount, managed.pendingReceiverWorldPositionRefreshCount());

            LevelTickEvent.Post tick = new LevelTickEvent.Post(() -> true, level);
            try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
                FrequencyApi.onLevelTickPost(tick);
                assertEquals(2, managed.pendingReceiverWorldPositionRefreshCount());

                FrequencyApi.onLevelTickPost(tick);
                assertEquals(0, managed.pendingReceiverWorldPositionRefreshCount());

                FrequencyApi.onLevelTickPost(tick);
                assertEquals(0, managed.pendingReceiverWorldPositionRefreshCount());
            }
        } finally {
            FrequencyApi.onLevelUnload(new LevelEvent.Unload(level));
        }

        assertThrows(IllegalStateException.class, managed::receiverCount);

        System.out.println("initialIndexing=PASS " + "tickBudget=PASS cleanup=PASS");
    }


    @Test
    void tickRefreshRetriesFailureWithoutStarvingOtherReceivers() {
        final class UnavailableReceiver implements FrequencyReceiver {
            boolean unavailable = true;

            @Override
            public Vec3 position() {
                if (unavailable) {
                    throw new IllegalStateException("Position temporarily unavailable");
                }

                return Vec3.ZERO;
            }

            @Override
            public double tunedFrequencyHz() {
                return 440.0;
            }

            @Override
            public double toleranceHz() {
                return 0.0;
            }
        }

        UnavailableReceiver failing = new UnavailableReceiver();
        TestReceiver healthy = new TestReceiver(880.0, 0.0);

        FrequencyRegistration a = engine.registerReceiver(failing);
        FrequencyRegistration b = engine.registerReceiver(healthy);

        FrequencyRegistration sa = engine.subscribeReceiver(failing);
        FrequencyRegistration sb = engine.subscribeReceiver(healthy);

        engine.markReceiverWorldPositionDirty(failing);
        engine.markReceiverWorldPositionDirty(healthy);
        assertEquals(2, engine.pendingReceiverWorldPositionRefreshCount());

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(2));
            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

            List<FrequencyReceiver> found = new ArrayList<>();

            engine.forEachReceiverSubscribedSnapshotInRange(880.0, Vec3.ZERO, 0.0, found::add);
            assertEquals(1, found.size());
            assertSame(healthy, found.getFirst());

            failing.unavailable = false;
            assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(1));
            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
            found.clear();

            engine.forEachReceiverSubscribedSnapshotInRange(440.0, Vec3.ZERO, 0.0, found::add);
            assertEquals(1, found.size());
            assertSame(failing, found.getFirst());
        }

        sa.close();
        sb.close();
        a.close();
        b.close();

        assertEquals(0, engine.receiverCount());

        System.out.println("failureIsolation=PASS " + "retry=PASS noStarvation=PASS");
    }


    @Test
    void hybridUsesFixedSnapshotsWithoutMissingMovingReceivers() {
        final class MovingReceiver implements FrequencyReceiver {
            Vec3 location;

            MovingReceiver(Vec3 location) {
                this.location = location;
            }

            @Override
            public Vec3 position() {
                return location;
            }

            @Override
            public double tunedFrequencyHz() {
                return 440.0;
            }

            @Override
            public double toleranceHz() {
                return 0.0;
            }
        }

        MovingReceiver fixed = new MovingReceiver(new Vec3(1, 0, 0));
        MovingReceiver dynamic = new MovingReceiver(new Vec3(100, 0, 0));

        FrequencyRegistration fixedOwner = engine.registerReceiverChunkBound(new BlockPos(1, 64, 0), fixed);
        FrequencyRegistration dynamicOwner = engine.registerReceiver(dynamic);
        FrequencyRegistration fixedSub = engine.subscribeFixedWorldReceiver(fixed);
        FrequencyRegistration dynamicSub = engine.subscribeReceiver(dynamic);

        List<FrequencyReceiver> found = new ArrayList<>();

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            engine.forEachReceiverSubscribedHybridInRange(440.0, Vec3.ZERO, 2.0, found::add);

            assertEquals(1, found.size());
            assertSame(fixed, found.getFirst());

            assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(2));

            found.clear();
            space.clearInvocations();

            engine.forEachReceiverSubscribedHybridInRange(440.0, Vec3.ZERO, 2.0, found::add);

            assertEquals(1, found.size());
            assertSame(fixed, found.getFirst());

            space.verify(() -> FrequencySpace.worldPosition(level, fixed.position()), never());

            dynamic.location = new Vec3(1.5, 0, 0);
            found.clear();

            engine.forEachReceiverSubscribedHybridInRange(440.0, Vec3.ZERO, 2.0, found::add);

            assertEquals(2, found.size());
            assertTrue(found.stream().anyMatch(r -> r == fixed));
            assertTrue(found.stream().anyMatch(r -> r == dynamic));

            fixed.location = new Vec3(200, 0, 0);
            engine.markReceiverWorldPositionDirty(fixed);

            found.clear();
            engine.forEachReceiverSubscribedHybridInRange(440.0, fixed.location, 0.0, found::add);

            assertEquals(1, found.size());
            assertSame(fixed, found.getFirst());

            assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(1));

            found.clear();
            space.clearInvocations();

            engine.forEachReceiverSubscribedHybridInRange(
                    440.0, fixed.location, 0.0, found::add);

            assertEquals(1, found.size());
            assertSame(fixed, found.getFirst());

            space.verify(() -> FrequencySpace.worldPosition(level, fixed.position()), never());
        }

        fixedSub.close();
        dynamicSub.close();
        fixedOwner.close();
        dynamicOwner.close();

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());

        System.out.println("fixedFastPath=PASS " + "movingFallback=PASS " + "dirtyFallback=PASS cleanup=PASS");
    }


    @Test
    void hybridRetunesFixedReceiversAndKeepsShipsLive() {
        final class MutableReceiver implements FrequencyReceiver {
            Vec3 location;
            double frequency;

            MutableReceiver(Vec3 location, double frequency) {
                this.location = location;
                this.frequency = frequency;
            }

            @Override
            public Vec3 position() {
                return location;
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

        MutableReceiver fixed = new MutableReceiver(new Vec3(1, 0, 0), 440.0);
        MutableReceiver ship = new MutableReceiver(new Vec3(100, 0, 0), 440.0);

        UUID shipId = UUID.randomUUID();
        FrequencyRegistration fixedOwner = engine.registerReceiverChunkBound(new BlockPos(1, 64, 0), fixed);
        FrequencyRegistration shipOwner = engine.registerReceiverSublevelBound(shipId, ship);
        assertThrows(IllegalArgumentException.class, () -> engine.subscribeFixedWorldReceiver(ship));

        FrequencyRegistration fixedSub = engine.subscribeFixedWorldReceiver(fixed);
        FrequencyRegistration shipSub = engine.subscribeReceiver(ship);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(2));

            fixed.frequency = 880.0;
            engine.updateReceiverSubscription(fixed);

            ship.location = new Vec3(1, 0, 0);

            List<FrequencyReceiver> found = new ArrayList<>();
            engine.forEachReceiverSubscribedHybridInRange(440.0, new Vec3(1, 0, 0), 0.0, found::add);

            assertEquals(1, found.size());
            assertSame(ship, found.getFirst());

            found.clear();

            engine.forEachReceiverSubscribedHybridInRange(880.0, new Vec3(1, 0, 0), 0.0, found::add);

            assertEquals(1, found.size());
            assertSame(fixed, found.getFirst());

            engine.onSublevelRemoved(shipId);

            found.clear();
            engine.forEachReceiverSubscribedHybridInRange(440.0, new Vec3(1, 0, 0), 0.0, found::add);

            assertTrue(found.isEmpty());
        }

        shipSub.close();
        shipOwner.close();
        fixedSub.close();
        fixedOwner.close();

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());

        System.out.println("retuning=PASS " + "sableLiveFallback=PASS cleanup=PASS");
    }


    @Test
    void dynamicReceiversDoNotAutomaticallyBuildSpatialSnapshots() {
        TestReceiver receiver = new TestReceiver(440.0, 0.0);

        FrequencyRegistration registration = engine.registerReceiver(receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);
        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

        List<FrequencyReceiver> found = new ArrayList<>();

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            engine.forEachReceiverSubscribedHybridInRange(440.0, Vec3.ZERO, 0.0, found::add);
            assertEquals(1, found.size());
            assertSame(receiver, found.getFirst());

            found.clear();
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, Vec3.ZERO, 0.0, found::add);
            assertTrue(found.isEmpty());

            engine.updateReceiverWorldPosition(receiver);
            engine.forEachReceiverSubscribedSnapshotInRange(440.0, Vec3.ZERO, 0.0, found::add);
            assertEquals(1, found.size());

            engine.markReceiverWorldPositionDirty(receiver);
            assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());
            assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(1));
            assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
        }

        subscription.close();
        registration.close();

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.subscribedReceiverCount());
    }


    @Test
    void liveOnlyEntityReceiversSkipPositionSampling() {
        Entity owner = mock(Entity.class);
        when(owner.level()).thenReturn(level);
        doReturn(new Vec3(10, 20, 30)).when(owner).position();

        TestReceiver receiver = new TestReceiver(440.0, 0.0);
        FrequencyRegistration registration = engine.registerReceiverEntityBound(owner, receiver);
        FrequencyRegistration subscription = engine.subscribeReceiver(receiver);
        clearInvocations(owner);

        engine.onTrackedEntityTick(owner);
        verify(owner, never()).position();
        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

        engine.markReceiverWorldPositionDirty(receiver);
        engine.onTrackedEntityTick(owner);
        verify(owner, times(1)).position();
        assertEquals(1, engine.pendingReceiverWorldPositionRefreshCount());

        subscription.close();
        registration.close();

        assertEquals(0, engine.receiverCount());
        assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
    }
}
