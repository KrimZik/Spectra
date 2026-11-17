package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.material.FrequencyMaterialAttenuation;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencyEngineCoreRegressionTest extends FrequencyEngineFixture {
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

    @Test
    void fixedWorldIndexPreservesLiveEmittersRetuningAndChunkCleanup() {
        class Fixed implements FrequencyEmitter {
            final Vec3 position;
            FrequencySignal signal;

            Fixed(Vec3 position, FrequencySignal signal) {
                this.position = position;
                this.signal = signal;
            }

            @Override
            public Vec3 position() {
                return position;
            }

            @Override
            public FrequencySignal signal() {
                return signal;
            }

            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        }

        class Moving implements FrequencyEmitter {
            Vec3 position = new Vec3(4.0, 0.0, 0.0);

            @Override
            public Vec3 position() {
                return position;
            }

            @Override
            public FrequencySignal signal() {
                return new FrequencySignal(440.0, 1.0, 0.0, 343.0);
            }
        }

        FrequencySignal signal = new FrequencySignal(440.0, 1.0, 0.0, 343.0);
        TestReceiver receiver = new TestReceiver(440.0, 0.0);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            for (int i = 0; i < 1024; i++) {
                engine.registerChunkBound(BlockPos.ZERO, new Fixed(new Vec3(512.0 + i, 0.0, 0.0), signal));
            }

            Fixed nearby = new Fixed(new Vec3(3.0, 0.0, 0.0), signal);
            FrequencyRegistration fixedRegistration = engine.registerChunkBound(BlockPos.ZERO, nearby);

            Moving moving = new Moving();
            FrequencyRegistration movingRegistration = engine.register(moving);

            int[] found = {0};
            engine.forEachMatchingInRange(receiver, 16.0, (emitter, current, distance) -> found[0]++);
            assertEquals(2, found[0]);

            moving.position = new Vec3(500.0, 0.0, 0.0);
            found[0] = 0;
            engine.forEachMatchingInRange(receiver, 16.0, (emitter, current, distance) -> found[0]++);
            assertEquals(1, found[0]);

            nearby.signal = new FrequencySignal(880.0, 1.0, 0.0, 343.0);
            engine.updateEmitter(nearby);

            found[0] = 0;
            engine.forEachMatchingInRange(receiver, 16.0, (emitter, current, distance) -> found[0]++);
            assertEquals(0, found[0]);

            found[0] = 0;
            engine.forEachMatchingInRange(new TestReceiver(880.0, 0.0), 16.0, (emitter, current, distance) -> found[0]++);
            assertEquals(1, found[0]);

            nearby.signal = signal;
            engine.updateEmitter(nearby);
            moving.position = new Vec3(4.0, 0.0, 0.0);

            found[0] = 0;
            engine.forEachMatchingInRange(receiver, 16.0, (emitter, current, distance) -> found[0]++);
            assertEquals(2, found[0]);

            fixedRegistration.close();
            engine.onChunkUnload(ChunkPos.asLong(0, 0));

            found[0] = 0;
            engine.forEachMatchingInRange(receiver, 16.0, (emitter, current, distance) -> found[0]++);
            assertEquals(1, found[0]);

            movingRegistration.close();
            assertEquals(0, engine.emitterCount());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void sublevelEmitterNeverUsesFixedWorldSnapshot() {
        class MovingShipEmitter implements FrequencyEmitter {
            Vec3 position = new Vec3(100.0, 0.0, 0.0);

            @Override
            public Vec3 position() {
                return position;
            }

            @Override
            public FrequencySignal signal() {
                return new FrequencySignal(440.0, 1.0, 0.0, 343.0);
            }

            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        }

        MovingShipEmitter emitter = new MovingShipEmitter();
        UUID sublevelId = UUID.randomUUID();
        TestReceiver receiver = new TestReceiver(440.0, 0.0);

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            engine.registerSublevelBound(sublevelId, emitter);

            int[] found = {0};
            engine.forEachMatchingInRange(receiver, 16.0, (source, signal, distance) -> found[0]++);
            assertEquals(0, found[0]);

            emitter.position = new Vec3(3.0, 0.0, 0.0);

            found[0] = 0;
            engine.forEachMatchingInRange(receiver, 16.0, (source, signal, distance) -> found[0]++);
            assertEquals(1, found[0]);

            engine.onSublevelRemoved(sublevelId);

            found[0] = 0;
            engine.forEachMatchingInRange(receiver, 16.0, (source, signal, distance) -> found[0]++);
            assertEquals(0, found[0]);
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void indexedReceptionPreservesExactDistanceAmplitudeAndTravelTime() {
        record Fixed(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {
            @Override
            public boolean hasFixedWorldPosition() {
                return true;
            }
        }

        boolean previousMaterials = FrequencyMaterialAttenuation.enabled();
        FrequencySignal distantSignal = new FrequencySignal(440.0, 1.0, 0.0, 343.0);
        Fixed nearby = new Fixed(new Vec3(3.0, 4.0, 0.0), new FrequencySignal(440.0, 2.0, 0.0, 100.0));

        try (MockedStatic<FrequencySpace> space = mockWorldSpace(level)) {
            FrequencyMaterialAttenuation.setEnabled(false);

            for (int i = 0; i < 1024; i++) {
                engine.registerChunkBound(BlockPos.ZERO, new Fixed(new Vec3(512.0 + i, 0.0, 0.0), distantSignal));
            }

            engine.registerChunkBound(BlockPos.ZERO, nearby);
            int[] received = {0};

            engine.forEachReceivedInRange(
                    new TestReceiver(440.0, 0.0),
                    5.0,
                    (emitter, signal, distanceSquared, amplitude, travelTime) -> {
                        received[0]++;
                        assertSame(nearby, emitter);
                        assertEquals(25.0, distanceSquared, 0.0);
                        assertEquals(0.4, amplitude, 1.0e-12);
                        assertEquals(0.05, travelTime, 1.0e-12);
                    }
            );

            assertEquals(1, received[0]);
        } finally {
            FrequencyMaterialAttenuation.setEnabled(previousMaterials);
            engine.shutdown();
        }
    }
}
