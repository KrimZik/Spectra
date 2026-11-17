package com.krimzik.spectra.api.material;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

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
}
