
package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.motion.FrequencyAccelerationTracker;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import com.krimzik.spectra.math.FrequencyDoppler;
import com.krimzik.spectra.math.FrequencyRetardedDoppler;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencyAccelerationDopplerEngineTest {
    private static final double C = 100.0;
    private static final double F = 100.0;

    private static final FrequencyMotionSnapshot SOURCE_OLD =
            new FrequencyMotionSnapshot(0, 0, 0, 0, 0, 0);

    private static final FrequencyMotionSnapshot SOURCE_NOW =
            new FrequencyMotionSnapshot(0, 0, 0, 0.5, 0, 0);

    private static final FrequencyMotionSnapshot RECEIVER =
            new FrequencyMotionSnapshot(100, 0, 0, 0, 0, 0);

    private record Setup(
            MinecraftServer server,
            ServerLevel level,
            FrequencyEngine engine
    ) {}

    private static Setup setup() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        when(server.getTickCount()).thenReturn(100);
        when(level.dimension()).thenReturn(Level.OVERWORLD);

        return new Setup(server, level, new FrequencyEngine(level));
    }

    private static FrequencyEmitter emitter(
            ServerLevel level,
            FrequencyMotionSnapshot motion
    ) {
        FrequencyEmitter emitter = mock(FrequencyEmitter.class);

        when(emitter.isEmitting()).thenReturn(true);
        when(emitter.signal()).thenReturn(
                new FrequencySignal(F, 1.0, 0.0, C)
        );
        when(emitter.motionSnapshot(level)).thenReturn(motion);

        return emitter;
    }

    private static FrequencyReceiver receiver(
            ServerLevel level,
            double tunedFrequency
    ) {
        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        when(receiver.tunedFrequencyHz()).thenReturn(tunedFrequency);
        when(receiver.toleranceHz()).thenReturn(1.0e-6);
        when(receiver.motionSnapshot(level)).thenReturn(RECEIVER);

        return receiver;
    }

    @Test
    void acceleratedEmitterIsFoundOutsideOriginalFrequencyWindow() {
        Setup setup = setup();

        FrequencyEmitter emitter = emitter(setup.level(), SOURCE_NOW);
        setup.engine().register(emitter);
        setup.engine().sampleEmitterMotion(emitter, SOURCE_OLD, FrequencyAccelerationTracker.Frame.WORLD, null, true);
        when(setup.server().getTickCount()).thenReturn(101);
        setup.engine().sampleEmitterMotion(emitter, SOURCE_NOW, FrequencyAccelerationTracker.Frame.WORLD, null, true);

        double shifted = FrequencyRetardedDoppler.calculate(F, C, SOURCE_NOW, RECEIVER, 10.0, 0.0, 0.0).receivedFrequencyHz();
        FrequencyReceiver receiver = receiver(setup.level(), shifted);
        int[] originalMatches = {0};
        setup.engine().forEachDopplerMatchingInRange(receiver, 100.0, C, 0.5, 0.0, (source, signal, distance, hz) -> originalMatches[0]++);
        assertEquals(0, originalMatches[0]);
        int[] acceleratedMatches = {0};

        setup.engine().forEachAccelerationDopplerMatchingInRange(
                receiver, 100.0, C, 0.5, 10.0, 0.0,
                (source, signal, distance, hz) -> {
                    acceleratedMatches[0]++;
                    assertSame(emitter, source);
                    assertEquals(shifted, hz, 1.0e-9);
                    assertEquals(10000.0, distance, 1.0e-9);
                }
        );

        assertEquals(1, acceleratedMatches[0]);
    }

    @Test
    void missingMotionHistoryUsesOriginalDopplerCalculation() {
        Setup setup = setup();
        FrequencyEmitter emitter = emitter(setup.level(), SOURCE_NOW);
        setup.engine().register(emitter);
        double shifted = FrequencyDoppler.receivedFrequencyHz(F, C, SOURCE_NOW, RECEIVER);
        FrequencyReceiver receiver = receiver(setup.level(), shifted);
        int[] matches = {0};

        setup.engine().forEachAccelerationDopplerMatchingInRange(
                receiver, 100.0, C, 0.5, 10.0, 0.0,
                (source, signal, distance, hz) -> {
                    matches[0]++;
                    assertEquals(shifted, hz, 1.0e-9);
                }
        );

        assertEquals(1, matches[0]);
    }

    @Test
    void disabledAccelerationUsesExistingQuery() {
        Setup setup = setup();
        setup.engine().register(emitter(setup.level(), SOURCE_NOW));
        double shifted = FrequencyDoppler.receivedFrequencyHz(F, C, SOURCE_NOW, RECEIVER);
        FrequencyReceiver receiver = receiver(setup.level(), shifted);
        int[] matches = {0};

        setup.engine().forEachAccelerationDopplerMatchingInRange(
                receiver, 100.0, C, 0.5, 0.0, 0.0,
                (source, signal, distance, hz) -> {
                    matches[0]++;
                    assertEquals(shifted, hz, 1.0e-9);
                }
        );

        assertEquals(1, matches[0]);
    }

    @Test
    void engineFrameTransitionDropsPreviousAcceleration() {
        Setup setup = setup();

        FrequencyEmitter emitter = emitter(
                setup.level(), SOURCE_NOW
        );

        var registration = setup.engine().register(emitter);
        UUID ship = UUID.randomUUID();

        setup.engine().sampleEmitterMotion(emitter, SOURCE_OLD, FrequencyAccelerationTracker.Frame.TRACKED, ship, true);
        when(setup.server().getTickCount()).thenReturn(101);
        setup.engine().sampleEmitterMotion(emitter, SOURCE_NOW, FrequencyAccelerationTracker.Frame.TRACKED, ship, true);
        setup.engine().sampleEmitterMotion(emitter, SOURCE_NOW, FrequencyAccelerationTracker.Frame.WORLD, null, true);

        double originalHz = FrequencyDoppler.receivedFrequencyHz(F, C, SOURCE_NOW, RECEIVER);
        FrequencyReceiver receiver = receiver(setup.level(), originalHz);

        int[] matches = {0};

        setup.engine().forEachAccelerationDopplerMatchingInRange(
                receiver, 100.0, C, 1.0, 10.0, 0.0,
                (source, signal, distance, hz) -> {
                    matches[0]++;
                    assertSame(emitter, source);
                    assertEquals(originalHz, hz, 1.0e-9);
                }
        );

        assertEquals(1, matches[0]);
        registration.close();
    }

    @Test
    void reRegisteringEmitterDoesNotReuseOldAcceleration() {
        Setup setup = setup();

        FrequencyEmitter emitter = emitter(setup.level(), SOURCE_NOW);
        var firstRegistration = setup.engine().register(emitter);

        setup.engine().sampleEmitterMotion(emitter, SOURCE_OLD, FrequencyAccelerationTracker.Frame.WORLD, null, true);
        when(setup.server().getTickCount()).thenReturn(101);

        setup.engine().sampleEmitterMotion(emitter, SOURCE_NOW, FrequencyAccelerationTracker.Frame.WORLD, null, true);
        firstRegistration.close();

        var secondRegistration = setup.engine().register(emitter);
        double originalHz = FrequencyDoppler.receivedFrequencyHz(F, C, SOURCE_NOW, RECEIVER);
        FrequencyReceiver receiver = receiver(setup.level(), originalHz);

        int[] matches = {0};

        setup.engine().forEachAccelerationDopplerMatchingInRange(
                receiver, 100.0, C, 1.0, 10.0, 0.0,
                (source, signal, distance, hz) -> {
                    matches[0]++;
                    assertSame(emitter, source);
                    assertEquals(originalHz, hz, 1.0e-9);
                }
        );

        assertEquals(1, matches[0]);
        secondRegistration.close();
    }

    @Test
    void invalidAccelerationEnvelopeIsRejected() {
        Setup setup = setup();
        FrequencyReceiver receiver = receiver(setup.level(), F);
        assertThrows(IllegalArgumentException.class, () -> setup.engine().forEachAccelerationDopplerMatchingInRange(receiver, 100.0, C, 0.0, 1000.0, 0.0, (source, signal, distance, hz) -> {}));
    }
}
