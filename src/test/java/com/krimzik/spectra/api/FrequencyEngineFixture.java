package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

abstract class FrequencyEngineFixture {
    protected MinecraftServer server;
    protected ServerLevel level;
    protected FrequencyEngine engine;
    protected static volatile double pooledInterferenceBenchmarkSink;

    @BeforeEach
    void setup() {
        server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        engine = new FrequencyEngine(level);
    }

    protected static int countMatches(FrequencyEngine engine, double frequency, double tolerance) {
        int[] count = {0};
        engine.forEachMatching(new TestReceiver(frequency, tolerance), (emitter, signal) -> count[0]++);
        return count[0];
    }

    protected static final class TestEmitter implements FrequencyEmitter {
        FrequencySignal signal;
        boolean emitting = true;

        TestEmitter(double frequency) {
            signal = new FrequencySignal(frequency, 1.0, 0.0, 343.0);
        }

        void setFrequency(double frequency) {
            signal = new FrequencySignal(frequency, signal.amplitude(), signal.phaseRadians(), signal.propagationSpeed());
        }

        void setAmplitude(double amplitude) {
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

    protected record TestReceiver(double tunedFrequencyHz, double toleranceHz) implements FrequencyReceiver {
        @Override
        public Vec3 position() {
            return Vec3.ZERO;
        }
    }

    protected static MockedStatic<FrequencySpace> mockWorldSpace(ServerLevel level) {
        MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class);
        space.when(() -> FrequencySpace.worldPosition(eq(level), any(Vec3.class))).thenAnswer(invocation -> invocation.getArgument(1));

        return space;
    }

    protected static void registerInterferenceEmitter(FrequencyEngine engine, Vec3 position, FrequencySignal signal) {
        FrequencyEmitter emitter = mock(FrequencyEmitter.class);

        when(emitter.signal()).thenReturn(signal);
        when(emitter.isEmitting()).thenReturn(true);
        when(emitter.position()).thenReturn(position);

        engine.register(emitter);
    }
}
