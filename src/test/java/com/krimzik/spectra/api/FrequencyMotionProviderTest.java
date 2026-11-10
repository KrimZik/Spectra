package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FrequencyMotionProviderTest {
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
    void publicQueryUsesProvidedSnapshots() {
        double sourceSpeed = -343.0 * (480.0 / 440.0 - 1.0);

        TestEmitter emitter = new TestEmitter(480.0, point(0, sourceSpeed));
        TestReceiver receiver = new TestReceiver(440.0, 0.001, point(100, 0));

        engine.register(emitter);
        AtomicInteger matches = new AtomicInteger();

        engine.forEachDopplerMatchingInRange(
                receiver,
                200.0,
                343.0,
                40.0,
                0.0,
                (source, signal, distanceSquared, receivedHz) -> {
                    assertSame(emitter, source);
                    assertEquals(480.0, signal.frequencyHz());
                    assertEquals(10000.0, distanceSquared);
                    assertEquals(440.0, receivedHz, 0.001);
                    matches.incrementAndGet();
                }
        );

        assertEquals(1, matches.get());
        assertEquals(1, emitter.snapshotCalls);
        assertEquals(1, receiver.snapshotCalls);
    }

    @Test
    void ordinaryMatchingDoesNotCaptureMotion() {
        TestEmitter emitter = new TestEmitter(480.0, point(0, -30));
        TestReceiver receiver = new TestReceiver(480.0, 0.001, point(100, 0));

        engine.register(emitter);
        AtomicInteger matches = new AtomicInteger();

        engine.forEachMatching(
                receiver,
                (source, signal) -> matches.incrementAndGet()
        );

        assertEquals(1, matches.get());
        assertEquals(0, emitter.snapshotCalls);
        assertEquals(0, receiver.snapshotCalls);
    }

    @Test
    void unrelatedFrequenciesDoNotCaptureEmitterMotion() {
        double sourceSpeed = -343.0 * (480.0 / 440.0 - 1.0);

        TestEmitter matching = new TestEmitter(480.0, point(0, sourceSpeed));

        TestEmitter unrelated = new TestEmitter(1000.0, point(0, 0));
        TestReceiver receiver = new TestReceiver(440.0, 0.001, point(100, 0));

        engine.register(matching);
        engine.register(unrelated);

        AtomicInteger matches = new AtomicInteger();

        engine.forEachDopplerMatchingInRange(
                receiver,
                200.0,
                343.0,
                40.0,
                0.0,
                (source, signal, distanceSquared, receivedHz) -> matches.incrementAndGet()
        );

        assertEquals(1, matches.get());
        assertEquals(1, matching.snapshotCalls);
        assertEquals(0, unrelated.snapshotCalls);
        assertEquals(1, receiver.snapshotCalls);
    }

    private static FrequencyMotionSnapshot point(double x, double vx) {
        return new FrequencyMotionSnapshot(x, 0, 0, vx, 0, 0);
    }

    private static final class TestEmitter implements FrequencyEmitter {
        private final FrequencySignal signal;
        private final FrequencyMotionSnapshot motion;
        private int snapshotCalls;

        private TestEmitter(double frequency, FrequencyMotionSnapshot motion) {
            this.signal = new FrequencySignal(frequency, 1.0, 0.0, 343.0);
            this.motion = motion;
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
        public FrequencyMotionSnapshot motionSnapshot(ServerLevel level) {
            snapshotCalls++;
            return motion;
        }
    }

    private static final class TestReceiver implements FrequencyReceiver {
        private final double frequency;
        private final double tolerance;
        private final FrequencyMotionSnapshot motion;
        private int snapshotCalls;

        private TestReceiver(double frequency, double tolerance, FrequencyMotionSnapshot motion) {
            this.frequency = frequency;
            this.tolerance = tolerance;
            this.motion = motion;
        }

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

        @Override
        public FrequencyMotionSnapshot motionSnapshot(ServerLevel level) {
            snapshotCalls++;
            return motion;
        }
    }
}
