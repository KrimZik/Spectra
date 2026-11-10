package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencySableStressTest {
    private static final double HZ = 915_000_000.0;
    private static final double RANGE = 16.0;

    private record Tx(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {}
    private record Rx(Vec3 position, double tunedFrequencyHz, double toleranceHz) implements FrequencyReceiver {}

    private static ServerLevel level() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        return level;
    }

    private static FrequencySignal signal() {
        return new FrequencySignal(HZ, 1.0, 0.0, 343.0);
    }

    private static int discover(FrequencyEngine engine, FrequencyEmitter tx, FrequencyReceiver target) {
        int[] hits = {0};
        engine.forEachSubscribedReceiverForEmitterInRange(tx, RANGE, found -> {
            if (found == target) hits[0]++;
        });
        return hits[0];
    }

    private static int receive(FrequencyEngine engine, FrequencyReceiver rx, FrequencyEmitter target) {
        int[] hits = {0};
        engine.forEachReceivedInRange(rx, RANGE, (found, signal, distanceSquared, amplitude, travelTime) -> {
            if (found == target) hits[0]++;
        });
        return hits[0];
    }

    @Test
    void extremeShipTranslationAndRotationStayCorrect() {
        ServerLevel level = level();
        FrequencyEngine engine = new FrequencyEngine(level);
        UUID shipId = UUID.randomUUID();

        Vec3 localTx = new Vec3(4, 0, 0);
        Vec3 localRx = new Vec3(3, 0, 0);
        Tx shipTx = new Tx(localTx, signal());
        Rx shipRx = new Rx(localRx, HZ, 0.0);

        Vec3 stationPos = new Vec3(0, 64, 0);
        Tx stationTx = new Tx(stationPos, signal());
        Rx stationRx = new Rx(stationPos, HZ, 0.0);

        Pose3d pose = new Pose3d();
        pose.position().set(0, 64, 0);

        SubLevelContainer container = mock(SubLevelContainer.class);
        SubLevel ship = mock(SubLevel.class);
        when(container.getSubLevel(shipId)).thenReturn(ship);
        when(ship.logicalPose()).thenReturn(pose);
        Map<UUID, Pose3d> previousPoses = new HashMap<>();

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(invocation -> {
                Vec3 point = invocation.getArgument(1);
                return point == localTx || point == localRx ? pose.transformPosition(point) : point;
            });

            FrequencyRegistration a = engine.registerSublevelBound(shipId, shipTx);
            FrequencyRegistration b = engine.registerReceiverSublevelBound(shipId, shipRx);
            FrequencyRegistration c = engine.subscribeReceiver(shipRx);
            FrequencyRegistration d = engine.register(stationTx);
            FrequencyRegistration e = engine.registerReceiverChunkBound(new BlockPos(0, 64, 0), stationRx);
            FrequencyRegistration f = engine.subscribeFixedWorldReceiver(stationRx);

            try {
                assertEquals(1, engine.processDirtyReceiverWorldPositionsForTick(2));
                SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, previousPoses);
                assertEquals(0, engine.processDirtyReceiverWorldPositionsForTick(1));

                long start = System.nanoTime();
                int near = 0;
                int far = 0;

                for (int step = 0; step < 400; step++) {
                    boolean nearby = (step & 1) == 0;
                    double x = nearby ? 0.0 : (step % 4 == 1 ? 1_000_000.0 : -1_000_000.0);
                    double z = nearby ? 0.0 : (step % 8 < 4 ? 1_000_000.0 : -1_000_000.0);
                    pose.position().set(x, 64.0, z);
                    pose.orientation().rotateY(Math.PI / 2.0);

                    SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, previousPoses);
                    assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

                    Vec3 txWorld = pose.transformPosition(localTx);
                    Vec3 rxWorld = pose.transformPosition(localRx);
                    int txExpected = txWorld.distanceToSqr(stationPos) <= RANGE * RANGE ? 1 : 0;
                    int rxExpected = rxWorld.distanceToSqr(stationPos) <= RANGE * RANGE ? 1 : 0;

                    assertEquals(txExpected, discover(engine, shipTx, stationRx), "TX discovery at step " + step);
                    assertEquals(txExpected, receive(engine, stationRx, shipTx), "RX reception at step " + step);
                    assertEquals(rxExpected, discover(engine, stationTx, shipRx), "Ship RX discovery at step " + step);
                    assertEquals(rxExpected, receive(engine, shipRx, stationTx), "Ship RX reception at step " + step);

                    assertEquals(0, engine.processDirtyReceiverWorldPositionsForTick(1));
                    assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
                    assertEquals(rxExpected, discover(engine, stationTx, shipRx), "Post-refresh discovery at step " + step);

                    if (nearby) near++; else far++;
                }

                double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
                System.out.printf(Locale.ROOT, "[SPECTRA-SABLE] rotations/translations=400 nearby=%d far=%d elapsed=%.2f ms correctness=PASS%n", near, far, milliseconds);

                engine.onSublevelRemoved(shipId);
                assertEquals(1, engine.emitterCount());
                assertEquals(1, engine.receiverCount());
                assertEquals(1, engine.subscribedReceiverCount());
                assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());
                assertEquals(0, discover(engine, stationTx, shipRx));
                assertEquals(0, receive(engine, stationRx, shipTx));
                SableEmitterLifecycle.observeTrackedReceiverPoses(container, engine, previousPoses);
                assertTrue(previousPoses.isEmpty());
            } finally {
                f.close();
                e.close();
                d.close();
                c.close();
                b.close();
                a.close();
            }

            assertEquals(0, engine.emitterCount());
            assertEquals(0, engine.receiverCount());
            assertEquals(0, engine.subscribedReceiverCount());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void shipRemovalAndReregistrationDoNotLeaveGhosts() {
        ServerLevel level = level();
        FrequencyEngine engine = new FrequencyEngine(level);
        UUID shipId = UUID.randomUUID();
        Vec3 point = new Vec3(2, 64, 0);
        Tx tx = new Tx(point, signal());
        Rx rx = new Rx(point, HZ, 0.0);

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(invocation -> invocation.getArgument(1));

            for (int cycle = 0; cycle < 100; cycle++) {
                FrequencyRegistration a = engine.registerSublevelBound(shipId, tx);
                FrequencyRegistration b = engine.registerReceiverSublevelBound(shipId, rx);
                FrequencyRegistration subscription = engine.subscribeReceiver(rx);

                assertEquals(1, discover(engine, tx, rx));
                assertEquals(1, receive(engine, rx, tx));
                engine.onSublevelRemoved(shipId);
                assertEquals(0, engine.emitterCount());
                assertEquals(0, engine.receiverCount());
                assertEquals(0, engine.subscribedReceiverCount());
                assertEquals(0, engine.pendingReceiverWorldPositionRefreshCount());

                subscription.close();
                b.close();
                a.close();
            }

            System.out.println("[SPECTRA-SABLE] removal/re-registration=100 cycles ghosts=0 PASS");
        } finally {
            engine.shutdown();
        }
    }
}
