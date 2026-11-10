package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyMovingEmitterMockitoTest {
    private static final double HZ = 915_000_000.0;
    private static final double RANGE = 4.0;

    private static final class Tx implements FrequencyEmitter {
        Vec3 position;
        FrequencySignal signal = new FrequencySignal(HZ, 1.0, 0.0, 343.0);
        boolean enabled = true;

        Tx(Vec3 position) {
            this.position = position;
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
        public boolean isEmitting() {
            return enabled;
        }
    }

    private static final class Rx implements FrequencyReceiver {
        Vec3 position;
        double hz = HZ;

        Rx(Vec3 position) {
            this.position = position;
        }

        @Override
        public Vec3 position() {
            return position;
        }

        @Override
        public double tunedFrequencyHz() {
            return hz;
        }

        @Override
        public double toleranceHz() {
            return 0.0;
        }
    }

    private static ServerLevel level() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        return level;
    }

    private static MockedStatic<FrequencySpace> worldSpace(ServerLevel level) {
        MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class);
        space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class)))
                .thenAnswer(call -> call.getArgument(1));
        return space;
    }

    private static int discover(FrequencyEngine engine, FrequencyEmitter transmitter, FrequencyReceiver receiver) {
        int[] hits = {0};
        engine.forEachSubscribedReceiverForEmitterInRange(transmitter, RANGE, found -> {
            if (found == receiver) hits[0]++;
        });
        return hits[0];
    }

    private static int receive(FrequencyEngine engine, FrequencyReceiver receiver, FrequencyEmitter transmitter) {
        int[] hits = {0};
        engine.forEachReceivedInRange(receiver, RANGE, (found, signal, distanceSquared, amplitude, travelTime) -> {
            if (found == transmitter) hits[0]++;
        });
        return hits[0];
    }

    private static void expect(FrequencyEngine engine, Tx tx, Rx rx, int expected) {
        assertEquals(expected, discover(engine, tx, rx));
        assertEquals(expected, receive(engine, rx, tx));
    }

    @Test
    void independentMovementRetuningAndTransmissionState() {
        ServerLevel level = level();
        FrequencyEngine engine = new FrequencyEngine(level);
        Tx tx = new Tx(new Vec3(0, 64, 0));
        Rx rx = new Rx(new Vec3(3, 64, 0));

        try (MockedStatic<FrequencySpace> space = worldSpace(level)) {
            FrequencyRegistration transmitter = engine.register(tx);
            FrequencyRegistration receiver = engine.registerReceiver(rx);
            FrequencyRegistration subscription = engine.subscribeReceiver(rx);

            try {
                expect(engine, tx, rx, 1);

                tx.position = new Vec3(100, 64, 0);
                expect(engine, tx, rx, 0);

                rx.position = new Vec3(102, 64, 0);
                expect(engine, tx, rx, 1);

                rx.position = new Vec3(-100, 64, 0);
                expect(engine, tx, rx, 0);

                tx.position = new Vec3(200, 64, 0);
                rx.position = new Vec3(202, 64, 0);
                expect(engine, tx, rx, 1);

                rx.hz = HZ + 100.0;
                engine.updateReceiverSubscription(rx);
                expect(engine, tx, rx, 0);

                tx.signal = new FrequencySignal(HZ + 100.0, 1.0, 0.0, 343.0);
                engine.updateEmitter(tx);
                expect(engine, tx, rx, 1);

                tx.enabled = false;
                expect(engine, tx, rx, 0);

                tx.enabled = true;
                expect(engine, tx, rx, 1);
                assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

                subscription.close();
                assertEquals(0, discover(engine, tx, rx));
                assertEquals(1, receive(engine, rx, tx));
                assertEquals(0, engine.subscribedReceiverCount());
            } finally {
                subscription.close();
                receiver.close();
                transmitter.close();
            }

            assertEquals(0, engine.emitterCount());
            assertEquals(0, engine.receiverCount());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void movingChunkReceiverAndEmitterReenterRange() {
        ServerLevel level = level();
        FrequencyEngine engine = new FrequencyEngine(level);
        Tx tx = new Tx(new Vec3(0, 64, 0));
        Rx rx = new Rx(new Vec3(3, 64, 0));

        try (MockedStatic<FrequencySpace> space = worldSpace(level)) {
            FrequencyRegistration transmitter = engine.register(tx);
            FrequencyRegistration receiver = engine.registerReceiverChunkBound(new BlockPos(3, 64, 0), rx);
            FrequencyRegistration subscription = engine.subscribeFixedWorldReceiver(rx);

            try {
                assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(1));
                expect(engine, tx, rx, 1);

                tx.position = new Vec3(100, 64, 0);
                expect(engine, tx, rx, 0);

                tx.position = new Vec3(4, 64, 0);
                expect(engine, tx, rx, 1);

                assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
            } finally {
                subscription.close();
                receiver.close();
                transmitter.close();
            }
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void entityRemovalClearsEmitterWithoutRemovingReceiver() {
        ServerLevel level = level();
        Entity owner = mock(Entity.class);
        when(owner.level()).thenReturn(level);
        FrequencyEngine engine = new FrequencyEngine(level);
        Tx tx = new Tx(Vec3.ZERO);
        Rx rx = new Rx(new Vec3(1, 0, 0));

        try (MockedStatic<FrequencySpace> space = worldSpace(level)) {
            FrequencyRegistration transmitter = engine.registerEntityBound(owner, tx);
            FrequencyRegistration receiver = engine.registerReceiver(rx);
            FrequencyRegistration subscription = engine.subscribeReceiver(rx);

            try {
                expect(engine, tx, rx, 1);
                tx.position = new Vec3(100, 0, 0);
                expect(engine, tx, rx, 0);
                tx.position = new Vec3(2, 0, 0);
                expect(engine, tx, rx, 1);

                engine.onEntityLeave(owner);
                assertEquals(0, engine.emitterCount());
                assertEquals(1, engine.receiverCount());
                assertEquals(1, engine.subscribedReceiverCount());
                assertEquals(0, receive(engine, rx, tx));
            } finally {
                subscription.close();
                receiver.close();
                transmitter.close();
            }

            assertEquals(0, engine.receiverCount());
        } finally {
            engine.shutdown();
        }
    }
}
