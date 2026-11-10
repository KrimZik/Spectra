package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencyReceiverSpatialTest extends FrequencyEngineFixture {
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
