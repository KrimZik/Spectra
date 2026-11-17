package com.krimzik.spectra.api.material;

import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MinecraftMaterialPathSamplerTest {
    @Test
    void ordinaryPathUsesWorldAttenuation() {
        ServerLevel level = mock(ServerLevel.class);

        try (MockedStatic<FrequencyMaterialAttenuation> material = mockStatic(FrequencyMaterialAttenuation.class)) {
            material.when(() -> FrequencyMaterialAttenuation.factor(level, 0, 0, 0, 3, 0, 0)).thenReturn(0.8);
            assertEquals(0.8, MinecraftMaterialPathSampler.INSTANCE.factor(level, 0, 0, 0, 3, 0, 0, false), 0.0);
            material.verify(() -> FrequencyMaterialAttenuation.factor(level, 0, 0, 0, 3, 0, 0), times(1));
        }
    }

    @Test
    void shipPathUsesAirRelativeAttenuation() {
        ServerLevel level = mock(ServerLevel.class);

        try (MockedStatic<FrequencyMaterialAttenuation> material = mockStatic(FrequencyMaterialAttenuation.class)) {
            material.when(() -> FrequencyMaterialAttenuation.factorRelativeToAir(level, 0, 0, 0, 3, 0, 0)).thenReturn(1.1);
            assertEquals(1.1, MinecraftMaterialPathSampler.INSTANCE.factor(level, 0, 0, 0, 3, 0, 0, true), 0.0);
            material.verify(() -> FrequencyMaterialAttenuation.factorRelativeToAir(level, 0, 0, 0, 3, 0, 0), times(1));
        }
    }
}
