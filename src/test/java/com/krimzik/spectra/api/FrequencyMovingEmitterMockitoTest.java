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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyMovingEmitterMockitoTest {
    private static final double HZ = 915_000_000.0;
    private static final double RANGE = 4.0;

    private static final class MovingTx implements FrequencyEmitter {
        Vec3 location;
        FrequencySignal wave = new FrequencySignal(HZ, 1.0, 0.0, 343.0);
        boolean enabled = true;

        MovingTx(Vec3 location) {
            this.location = location;
        }

        @Override
        public Vec3 position() {
            return location;
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

    private record Rx(Vec3 position, double tunedFrequencyHz, double toleranceHz) implements FrequencyReceiver {}

    private static FrequencyEngine newEngine(ServerLevel level) {
        return new FrequencyEngine(level);
    }

    private static MockedStatic<FrequencySpace> identitySpace(ServerLevel level) {
        MockedStatic<FrequencySpace> mock = mockStatic(FrequencySpace.class);
        mock.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));

        return mock;
    }

    private static int countTxDiscovery(FrequencyEngine engine, MovingTx tx, Rx rx) {
        int[] hits = {0};
        engine.forEachSubscribedReceiverForEmitterInRange(
                tx, RANGE, found -> {
                    assertSame(rx, found);
                    hits[0]++;
                });

        return hits[0];
    }

    private static int countRxReception(FrequencyEngine engine, MovingTx tx, Rx rx) {
        int[] hits = {0};
        engine.forEachReceivedInRange(
                rx, RANGE,
                (emitter, signal, distanceSquared,
                 amplitude, travelTime) -> {
                    assertSame(tx, emitter);
                    assertTrue(distanceSquared <= RANGE * RANGE);
                    hits[0]++;
                });

        return hits[0];
    }

    @Test
    void movingTransmitterUpdatesRangeWithoutReindexing() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = newEngine(level);

        MovingTx tx = new MovingTx(new Vec3(0, 64, 0));
        Rx rx = new Rx(new Vec3(3, 64, 0), HZ, 0.0);

        try (MockedStatic<FrequencySpace> space = identitySpace(level)) {
            FrequencyRegistration txHandle = engine.register(tx);
            FrequencyRegistration rxHandle = engine.registerReceiverChunkBound(new BlockPos(3, 64, 0), rx);
            FrequencyRegistration subscription = engine.subscribeFixedWorldReceiver(rx);

            try {
                assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(2));

                assertEquals(1, countTxDiscovery(engine, tx, rx));
                assertEquals(1, countRxReception(engine, tx, rx));

                tx.location = new Vec3(100, 64, 0);

                assertEquals(0, countTxDiscovery(engine, tx, rx));
                assertEquals(0, countRxReception(engine, tx, rx));

                tx.location = new Vec3(4, 64, 0);

                assertEquals(1, countTxDiscovery(engine, tx, rx));
                assertEquals(1, countRxReception(engine, tx, rx));
                assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

                tx.wave = new FrequencySignal(HZ + 100.0, 1.0, 0.0, 343.0);

                engine.updateEmitter(tx);

                assertEquals(0, countTxDiscovery(engine, tx, rx));
                assertEquals(0, countRxReception(engine, tx, rx));

                tx.wave = new FrequencySignal(HZ, 1.0, 0.0, 343.0);

                engine.updateEmitter(tx);
                tx.enabled = false;

                assertEquals(0, countTxDiscovery(engine, tx, rx));
                assertEquals(0, countRxReception(engine, tx, rx));

                tx.enabled = true;

                assertEquals(1, countTxDiscovery(engine, tx, rx));

            } finally {
                subscription.close();
                rxHandle.close();
                txHandle.close();
            }

            assertEquals(0, engine.emitterCount());
            assertEquals(0, engine.receiverCount());

        } finally {
            engine.shutdown();
        }

        System.out.println("[FREQ-MOVING-TX] movement=PASS " + "range=PASS retuning=PASS " + "transmissionState=PASS cleanup=PASS");
    }

    @Test
    void sableTransmitterUsesUpdatedWorldProjection() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        FrequencyEngine engine = newEngine(level);

        UUID shipId = UUID.randomUUID();
        Vec3 shipLocal = new Vec3(7, 2, 3);

        Rx rx = new Rx(new Vec3(32, 70, 0), HZ, 0.0);
        MovingTx tx = new MovingTx(shipLocal);

        Vec3[] projectedTx = {
                new Vec3(33, 70, 0)
        };

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(call -> {
                        Vec3 position = call.getArgument(1);

                        if (position == shipLocal) {
                            return projectedTx[0];
                        }

                        return position;
                    });

            FrequencyRegistration txHandle = engine.registerSublevelBound(shipId, tx);
            FrequencyRegistration rxHandle = engine.registerReceiverChunkBound(new BlockPos(32, 70, 0), rx);
            FrequencyRegistration subscription = engine.subscribeFixedWorldReceiver(rx);

            try {
                assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(1));

                assertEquals(1, countTxDiscovery(engine, tx, rx));
                assertEquals(1, countRxReception(engine, tx, rx));

                projectedTx[0] = new Vec3(100, 70, 0);

                assertEquals(0, countTxDiscovery(engine, tx, rx));
                assertEquals(0, countRxReception(engine, tx, rx));

                projectedTx[0] = new Vec3(31, 70, 0);

                assertEquals(1, countTxDiscovery(engine, tx, rx));
                assertEquals(1, countRxReception(engine, tx, rx));
                assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

                engine.onSublevelRemoved(shipId);

                assertEquals(0, engine.emitterCount());
                assertEquals(0, countRxReception(engine, tx, rx));

            } finally {
                subscription.close();
                rxHandle.close();
                txHandle.close();
            }

        } finally {
            engine.shutdown();
        }

        System.out.println("[FREQ-MOVING-TX] sableProjection=PASS " + "shipTranslation=PASS " + "shipRemoval=PASS");
    }

    @Test
    void entityTransmitterMovesAndCleansUpOnLeave() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        Entity owner = mock(Entity.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        when(owner.level()).thenReturn(level);

        FrequencyEngine engine = newEngine(level);

        MovingTx tx = new MovingTx(Vec3.ZERO);
        Rx rx = new Rx(new Vec3(1, 0, 0), HZ, 0.0);

        try (MockedStatic<FrequencySpace> space = identitySpace(level)) {
            FrequencyRegistration txHandle = engine.registerEntityBound(owner, tx);
            FrequencyRegistration rxHandle = engine.registerReceiver(rx);
            FrequencyRegistration subscription = engine.subscribeReceiver(rx);

            try {
                assertEquals(1, countTxDiscovery(engine, tx, rx));
                tx.location = new Vec3(100, 0, 0);
                assertEquals(0, countTxDiscovery(engine, tx, rx));

                tx.location = new Vec3(2, 0, 0);
                assertEquals(1, countTxDiscovery(engine, tx, rx));

                engine.onEntityLeave(owner);
                assertEquals(0, engine.emitterCount());
                assertEquals(0, countRxReception(engine, tx, rx));

            } finally {
                subscription.close();
                rxHandle.close();
                txHandle.close();
            }

        } finally {
            engine.shutdown();
        }

        System.out.println("[FREQ-MOVING-TX] entityMovement=PASS " + "entityCleanup=PASS");
    }
}
