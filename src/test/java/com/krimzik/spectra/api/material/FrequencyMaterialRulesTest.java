package com.krimzik.spectra.api.material;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrequencyMaterialRulesTest {
    @AfterEach
    void cleanup() {
        FrequencyMaterialRules.replaceOverrides(List.of());
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
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
    void cachedBlockLossUpdatesWhenOverridesChange() {
        var stone = Blocks.STONE.defaultBlockState();

        FrequencyMaterialRules.replaceOverrides(List.of());
        assertEquals(3.0, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);
        assertEquals(3.0, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);

        FrequencyMaterialRules.replaceOverrides(List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:stone"), 8.0)));

        assertEquals(8.0, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);

        FrequencyMaterialRules.replaceOverrides(List.of());
        assertEquals(3.0, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);
    }

    @Test
    void cachedEnvironmentLossUpdatesWhenSettingsChange() {
        var air = Blocks.AIR.defaultBlockState();

        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        assertEquals(0.0, FrequencyMaterialRules.decibelsPerMeter(air), 0.0);

        FrequencyMaterialRules.setEnvironmentLoss(1.5, 2.0);
        assertEquals(1.5, FrequencyMaterialRules.decibelsPerMeter(air), 0.0);

        FrequencyMaterialRules.setEnvironmentLoss(0.25, 0.5);
        assertEquals(0.25, FrequencyMaterialRules.decibelsPerMeter(air), 0.0);
    }

    @Test
    void indexedCacheUpdatesWhenCustomMaterialPropertiesChange() {
        ResourceLocation stoneId = ResourceLocation.parse("minecraft:stone");
        ResourceLocation customId = ResourceLocation.parse("test:custom");
        var stone = Blocks.STONE.defaultBlockState();

        FrequencyMaterialRules.Rule rule = new FrequencyMaterialRules.Rule(stoneId, null, customId, null);
        FrequencyMaterialRules.replaceMaterials(List.of(rule), Map.of(customId, new FrequencyMaterialRules.Properties(2.5, 1200.0, 3.0)));

        assertEquals(2.5, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);
        assertEquals(2.5, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);
        assertEquals(customId, FrequencyMaterialClassifier.classify(stone));

        FrequencyMaterialRules.replaceMaterials(List.of(rule), Map.of(customId, new FrequencyMaterialRules.Properties(7.5, 1200.0, 3.0)));

        assertEquals(7.5, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);
        assertEquals(customId, FrequencyMaterialClassifier.classify(stone));

        FrequencyMaterialRules.replaceOverrides(List.of());

        assertEquals(3.0, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);
        assertEquals(FrequencyMaterialClassifier.STONE, FrequencyMaterialClassifier.classify(stone));
    }

    @Test
    void indexedClassificationUpdatesAcrossMaterialReloads() {
        ResourceLocation stoneId = ResourceLocation.parse("minecraft:stone");
        ResourceLocation firstId = ResourceLocation.parse("test:first");
        ResourceLocation secondId = ResourceLocation.parse("test:second");

        var stone = Blocks.STONE.defaultBlockState();
        var glass = Blocks.GLASS.defaultBlockState();

        FrequencyMaterialRules.replaceMaterials(List.of(new FrequencyMaterialRules.Rule(stoneId, null, firstId, null)), Map.of(firstId, new FrequencyMaterialRules.Properties(3.0, null, null)));

        assertEquals(firstId, FrequencyMaterialClassifier.classify(stone));
        assertEquals(firstId, FrequencyMaterialClassifier.classify(stone));
        assertEquals(FrequencyMaterialClassifier.GLASS, FrequencyMaterialClassifier.classify(glass));
        assertEquals(3.0, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);

        FrequencyMaterialRules.replaceMaterials(List.of(new FrequencyMaterialRules.Rule(stoneId, null, secondId, null)), Map.of(secondId, new FrequencyMaterialRules.Properties(3.0, null, null)));

        assertEquals(secondId, FrequencyMaterialClassifier.classify(stone));
        assertEquals(FrequencyMaterialClassifier.GLASS, FrequencyMaterialClassifier.classify(glass));
        assertEquals(3.0, FrequencyMaterialRules.decibelsPerMeter(stone), 0.0);

        FrequencyMaterialRules.replaceOverrides(List.of());

        assertEquals(FrequencyMaterialClassifier.STONE, FrequencyMaterialClassifier.classify(stone));
        assertEquals(FrequencyMaterialClassifier.GLASS, FrequencyMaterialClassifier.classify(glass));
    }
}
