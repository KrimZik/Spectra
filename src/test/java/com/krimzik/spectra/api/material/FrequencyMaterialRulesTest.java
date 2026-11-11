package com.krimzik.spectra.api.material;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrequencyMaterialRulesTest {
    @AfterEach
    void cleanup() {
        FrequencyMaterialRules.replaceOverrides(List.of());
        FrequencyMaterialAttenuation.setEnabled(false);
    }

    @Test
    void tenDecibelsReduceAmplitudeByExpectedRatio() {
        assertEquals(1.0, FrequencyMaterialRules.amplitudeFactor(0.0), 1.0e-12);
        assertEquals(Math.sqrt(0.1), FrequencyMaterialRules.amplitudeFactor(10.0), 1.0e-12);
        assertEquals(0.1, FrequencyMaterialRules.amplitudeFactor(20.0), 1.0e-12);

        assertThrows(IllegalArgumentException.class, () -> FrequencyMaterialRules.amplitudeFactor(-1.0));
    }

    @Test
    void customBlockRuleOverridesDefault() {
        double oldLoss = FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState());
        FrequencyMaterialRules.replaceOverrides(List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:stone"), 10.0)));

        assertEquals(10.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState()), 0.0);
        assertEquals(0.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.AIR.defaultBlockState()), 0.0);

        assertNotEquals(10.0, oldLoss);
    }

    @Test
    void blockOverridesTakePriorityOverTags() {
        FrequencyMaterialRules.replaceOverrides(List.of(FrequencyMaterialRules.tag(ResourceLocation.parse("minecraft:planks"), 7.0), FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:oak_planks"), 2.0)));
        assertEquals(2.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.OAK_PLANKS.defaultBlockState()), 0.0);
    }

    @Test
    void materialAttenuationDefaultsToDisabled() {
        assertFalse(FrequencyMaterialAttenuation.enabled());
    }
}
