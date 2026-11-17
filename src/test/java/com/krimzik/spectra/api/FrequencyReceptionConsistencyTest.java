package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.material.FrequencyMaterialAttenuation;
import com.krimzik.spectra.api.material.FrequencyMaterialRules;
import com.krimzik.spectra.api.material.FrequencySableMaterialAttenuation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyReceptionConsistencyTest {
    private static final double RANGE = 16.0;
    private static final double EPSILON = 1.0e-10;

    private record Emitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {}
    private record Receiver(double tunedFrequencyHz, double toleranceHz, Vec3 position) implements FrequencyReceiver {}
    private record Fixture(ServerLevel level, FrequencyEngine engine, Receiver receiver) {}

    @BeforeEach
    void setup() {
        FrequencyMaterialAttenuation.setEnabled(false);
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        FrequencyMaterialRules.replaceOverrides(List.of());
    }

    @AfterEach
    void cleanup() {
        FrequencyMaterialAttenuation.setEnabled(false);
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        FrequencyMaterialRules.replaceOverrides(List.of());
    }

    private static Fixture fixture() {
        return fixture(1.0);
    }

    private static Fixture fixture(double amplitude) {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        when(level.getChunkSource()).thenReturn(chunks);
        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);

        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getX() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        FrequencyEngine engine = new FrequencyEngine(level);
        engine.register(new Emitter(new Vec3(0.5, 64.5, 0.5), new FrequencySignal(123.0, amplitude, 0.7, 100.0)));

        Receiver receiver = new Receiver(123.0, 0.0, new Vec3(3.5, 64.5, 0.5));

        return new Fixture(level, engine, receiver);
    }

    private static double receivedAmplitude(Fixture f) {
        double[] result = {Double.NaN};
        int[] count = {0};

        f.engine().forEachReceivedInRange(f.receiver(), RANGE, (emitter, signal, distanceSquared, amplitude, travelTime) -> {
            result[0] = amplitude;
            count[0]++;
            assertEquals(9.0, distanceSquared, EPSILON);
            assertEquals(0.03, travelTime, EPSILON);
        });

        assertEquals(1, count[0]);
        return result[0];
    }

    @Test
    void receptionUsesCurrentMaterialSettingsAndOverrides() {
        Fixture f = fixture();

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(f.level()), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));

            assertEquals(1.0 / 3.0, receivedAmplitude(f), EPSILON);

            FrequencyMaterialAttenuation.setEnabled(true);

            double expected = FrequencyMaterialRules.amplitudeFactor(3.0);
            assertEquals(expected / 3.0, receivedAmplitude(f), EPSILON);

            FrequencyMaterialRules.setEnvironmentLoss(0.25, 0.5);

            expected = FrequencyMaterialRules.amplitudeFactor(3.5);
            assertEquals(expected / 3.0, receivedAmplitude(f), EPSILON);

            FrequencyMaterialRules.replaceOverrides(List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:stone"), 6.0)));

            expected = FrequencyMaterialRules.amplitudeFactor(6.5);
            assertEquals(expected / 3.0, receivedAmplitude(f), EPSILON);

            FrequencyMaterialAttenuation.setEnabled(false);

            assertEquals(1.0 / 3.0, receivedAmplitude(f), EPSILON);
        } finally {
            f.engine().shutdown();
        }
    }

    @Test
    void interferenceUsesAttenuatedAmplitudeExactlyOnce() {
        Fixture f = fixture();

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(f.level()), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));

            double baselineSample = f.engine().sampleInterferenceInRange(f.receiver(), RANGE, 0.123);
            double baselineMeanSquare = f.engine().sampleInterferenceMeanSquareInRange(f.receiver(), RANGE);
            double baselineWindow = f.engine().sampleInterferenceWindowMeanSquareInRange(f.receiver(), RANGE, 200L, 0.25);

            assertTrue(Math.abs(baselineSample) > 1.0e-8);
            assertTrue(baselineMeanSquare > 0.0);
            assertTrue(baselineWindow > 0.0);

            FrequencyMaterialAttenuation.setEnabled(true);

            double factor = FrequencyMaterialRules.amplitudeFactor(3.0);

            assertEquals(baselineSample * factor, f.engine().sampleInterferenceInRange(f.receiver(), RANGE, 0.123), EPSILON);
            assertEquals(baselineMeanSquare * factor * factor, f.engine().sampleInterferenceMeanSquareInRange(f.receiver(), RANGE), EPSILON);
            assertEquals(baselineWindow * factor * factor, f.engine().sampleInterferenceWindowMeanSquareInRange(f.receiver(), RANGE, 200L, 0.25), EPSILON);
        } finally {
            f.engine().shutdown();
        }
    }

    @Test
    void matchingRemainsIndependentOfAttenuation() {
        Fixture f = fixture();

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            space.when(() -> FrequencySpace.worldPosition(eq(f.level()), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));
            FrequencyMaterialAttenuation.setEnabled(true);

            int[] matches = {0};
            f.engine().forEachMatchingInRange(f.receiver(), RANGE, (emitter, signal, distanceSquared) -> matches[0]++);

            assertEquals(1, matches[0]);
        } finally {
            f.engine().shutdown();
        }
    }

    @Test
    void unrelatedFrequencyDoesNotProjectReceiverPosition() {
        Fixture f = fixture();
        Receiver unmatched = new Receiver(999.0, 0.0, f.receiver().position());

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class)) {
            f.engine().forEachReceivedInRange(unmatched, RANGE, (emitter, signal, distanceSquared, amplitude, travelTime) -> fail("Unexpected reception"));
            space.verifyNoInteractions();
        } finally {
            f.engine().shutdown();
        }
    }

    @Test
    void zeroAmplitudePreservesReceptionWithoutMaterialTraversal() {
        Fixture f = fixture(0.0);

        try (MockedStatic<FrequencySpace> space = mockStatic(FrequencySpace.class);
             MockedStatic<FrequencySableMaterialAttenuation> sable = mockStatic(FrequencySableMaterialAttenuation.class)) {

            space.when(() -> FrequencySpace.worldPosition(eq(f.level()), any(Vec3.class))).thenAnswer(call -> call.getArgument(1));
            FrequencyMaterialAttenuation.setEnabled(true);
            clearInvocations(f.level());

            int[] received = {0};

            f.engine().forEachReceivedInRange(
                    f.receiver(), RANGE,
                    (emitter, signal, distanceSquared, amplitude, travelTime) -> {
                        received[0]++;
                        assertEquals(9.0, distanceSquared, EPSILON);
                        assertEquals(0.0, amplitude, 0.0);
                        assertEquals(0.03, travelTime, EPSILON);
                    }
            );

            assertEquals(1, received[0]);
            verify(f.level(), never()).getChunkSource();
            sable.verifyNoInteractions();
        } finally {
            f.engine().shutdown();
        }
    }
}
