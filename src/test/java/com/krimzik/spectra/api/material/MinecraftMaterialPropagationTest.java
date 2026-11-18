package com.krimzik.spectra.api.material;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MinecraftMaterialPropagationTest {
    @AfterEach
    void cleanup() {
        FrequencyMaterialAttenuation.setEnabled(false);
    }

    @Test
    void disabledAttenuationSkipsWorldSampling() {
        ServerLevel level = mock(ServerLevel.class);
        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);

        FrequencyMaterialAttenuation.setEnabled(false);

        try (MockedStatic<FrequencyMaterialAttenuation> material = mockStatic(FrequencyMaterialAttenuation.class, CALLS_REAL_METHODS);
             MockedStatic<FrequencySableMaterialAttenuation> sable = mockStatic(FrequencySableMaterialAttenuation.class)) {

            assertEquals(1.0, propagation.factor(Vec3.ZERO, new Vec3(1.0, 1.0, 1.0)), 0.0);
            material.verify(() -> FrequencyMaterialAttenuation.factor(level, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0), never());

            sable.verifyNoInteractions();
        }
    }

    @Test
    void enabledAttenuationCombinesBothSourcesOnce() {
        ServerLevel level = mock(ServerLevel.class);
        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);

        Vec3 start = Vec3.ZERO;
        Vec3 end = new Vec3(3.0, 0.0, 0.0);

        FrequencyMaterialAttenuation.setEnabled(true);

        try (MockedStatic<FrequencyMaterialAttenuation> material = mockStatic(FrequencyMaterialAttenuation.class, CALLS_REAL_METHODS);
             MockedStatic<FrequencySableMaterialAttenuation> sable = mockStatic(FrequencySableMaterialAttenuation.class)) {

            material.when(() -> FrequencyMaterialAttenuation.factor(level, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0)).thenReturn(0.8);
            sable.when(() -> FrequencySableMaterialAttenuation.factor(level, start, end)).thenReturn(0.5);

            material.clearInvocations();

            assertEquals(0.4, propagation.factor(start, end), 1.0e-12);

            material.verify(() -> FrequencyMaterialAttenuation.factor(level, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0), times(1));
            sable.verify(() -> FrequencySableMaterialAttenuation.factor(level, start, end), times(1));
        }
    }


    @Test
    void waveReceptionSkipsShipsThatDoNotIntersectTheRay() {
        ServerLevel level = mock(ServerLevel.class);
        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);
        MinecraftCombinedWavePath wave = mock(MinecraftCombinedWavePath.class);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.boundingBox()).thenReturn(new BoundingBox3d(20, 0, 20, 25, 5, 25));

        Vec3 start = Vec3.ZERO;
        Vec3 end = new Vec3(3.0, 0.0, 0.0);
        double frequency = 915_000_000.0;

        when(wave.evaluate(start, end, List.of(), frequency, true)).thenReturn(0.75);
        double result = propagation.factorForDirectWaveReception(start, end, List.of(ship), frequency, wave);
        assertEquals(0.75, result, 0.0);

        verify(wave).evaluate(start, end, List.of(), frequency, true);
        verify(ship, never()).logicalPose();
    }

    @Test
    void waveReceptionKeepsShipsThatIntersectTheRay() {
        ServerLevel level = mock(ServerLevel.class);
        MinecraftMaterialPropagation propagation = new MinecraftMaterialPropagation(level);
        MinecraftCombinedWavePath wave = mock(MinecraftCombinedWavePath.class);

        SubLevelAccess ship = mock(SubLevelAccess.class);
        when(ship.boundingBox()).thenReturn(new BoundingBox3d(1, -1, -1, 2, 1, 1));

        Vec3 start = Vec3.ZERO;
        Vec3 end = new Vec3(3.0, 0.0, 0.0);
        double frequency = 915_000_000.0;

        List<SubLevelAccess> candidates = List.of(ship);
        when(wave.evaluate(start, end, candidates, frequency, true)).thenReturn(0.5);

        double result = propagation.factorForDirectWaveReception(start, end, candidates, frequency, wave);
        assertEquals(0.5, result, 0.0);
        verify(wave).evaluate(start, end, candidates, frequency, true);
    }
}
