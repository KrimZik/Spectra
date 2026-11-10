package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import com.krimzik.spectra.api.registration.FrequencyRegistration;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FrequencyDopplerEngineTest {

    private FrequencyEngine engine;

    @BeforeEach
    void setup() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        engine = new FrequencyEngine(level);
    }

    @Test
    void detectsFrequencyShiftedIntoTuningRange() {
        TestEmitter emitter = new TestEmitter(480.0);
        engine.register(emitter);

        double speed = -343.0 * (480.0 / 440.0 - 1.0);

        List<Double> received = new ArrayList<>();

        engine.forEachDopplerMatchingInRange(
                new TestReceiver(440.0, 0.001),
                snapshot(100, 0),
                200.0,
                343.0,
                40.0,
                0.0,
                source -> snapshot(0, speed),
                (source, signal, distanceSquared, receivedHz) -> {
                    assertSame(emitter, source);assertEquals(480.0, signal.frequencyHz());assertEquals(10000.0, distanceSquared);received.add(receivedHz);
                }
        );

        assertEquals(1, received.size());
        assertEquals(440.0, received.getFirst(), 0.001);
    }

    @Test
    void unshiftedOutOfTuneSignalIsRejected() {
        engine.register(new TestEmitter(480.0));

        AtomicInteger matches = new AtomicInteger();

        engine.forEachDopplerMatchingInRange(
                new TestReceiver(440.0, 0.001),
                snapshot(100, 0),
                200.0,
                343.0,
                40.0,
                0.0,
                source -> snapshot(0, 0),
                (source, signal, distanceSquared, receivedHz) -> matches.incrementAndGet()
        );

        assertEquals(0, matches.get());
    }

    @Test
    void receiverMotionCanShiftFrequencyIntoRange() {
        engine.register(new TestEmitter(440.0));

        double expectedHz = 440.0 * 323.0 / 343.0;

        AtomicInteger matches = new AtomicInteger();

        engine.forEachDopplerMatchingInRange(
                new TestReceiver(expectedHz, 0.001),
                snapshot(100, 20),
                200.0,
                343.0,
                0.0,
                30.0,
                source -> snapshot(0, 0),
                (source, signal, distanceSquared, receivedHz) -> {
                    assertEquals(expectedHz, receivedHz, 0.001);matches.incrementAndGet();
                }
        );

        assertEquals(1, matches.get());
    }

    @Test
    void spatialRangeIsEnforced() {
        engine.register(new TestEmitter(440.0));

        AtomicInteger matches = new AtomicInteger();

        engine.forEachDopplerMatchingInRange(
                new TestReceiver(440.0, 0.001),
                snapshot(100, 0),
                50.0,
                343.0,
                40.0,
                0.0,
                source -> snapshot(0, 0),
                (source, signal, distanceSquared, receivedHz) -> matches.incrementAndGet()
        );

        assertEquals(0, matches.get());
    }

    @Test
    void frequencyIndexExcludesUnrelatedEmitters() {
        engine.register(new TestEmitter(1000.0));

        AtomicInteger captures = new AtomicInteger();

        engine.forEachDopplerMatchingInRange(
                new TestReceiver(440.0, 1.0),
                snapshot(100, 0),
                200.0,
                343.0,
                40.0,
                0.0,
                source -> {
                    captures.incrementAndGet();
                    return snapshot(0, 0);
                },
                (source, signal, distanceSquared, receivedHz) -> fail("Unrelated frequency was returned")
        );

        assertEquals(0, captures.get());
    }

    @Test
    void registrationCannotChangeDuringDopplerQuery() {
        TestEmitter emitter = new TestEmitter(440.0);
        FrequencyRegistration registration = engine.register(emitter);

        assertThrows(
                IllegalStateException.class,
                () -> engine.forEachDopplerMatchingInRange(
                        new TestReceiver(440.0, 0.001),
                        snapshot(100, 0),
                        200.0,
                        343.0,
                        40.0,
                        0.0,
                        source -> snapshot(0, 0),
                        (source, signal, distanceSquared, receivedHz) -> registration.close()
                )
        );

        assertEquals(1, engine.emitterCount());
        registration.close();
        assertEquals(0, engine.emitterCount());
    }

    private static FrequencyMotionSnapshot snapshot(double x, double vx) {
        return new FrequencyMotionSnapshot(x, 0, 0, vx, 0, 0);
    }

    private static final class TestEmitter implements FrequencyEmitter {
        private final FrequencySignal signal;

        private TestEmitter(double frequency) {
            signal = new FrequencySignal(frequency, 1.0, 0.0, 343.0);
        }

        @Override
        public Vec3 position() {
            return Vec3.ZERO;
        }

        @Override
        public FrequencySignal signal() {
            return signal;
        }
    }

    private record TestReceiver(double tunedFrequencyHz, double toleranceHz) implements FrequencyReceiver {
        @Override
        public Vec3 position() {
            return Vec3.ZERO;
        }
    }
}
