package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencyReceiverLifecycleTest extends FrequencyEngineFixture {
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
}
