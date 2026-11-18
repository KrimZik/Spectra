
package com.krimzik.spectra.api.material;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencyMaterialClassifierTest {
    private static final TagKey<Block> METAL = TagKey.create(Registries.BLOCK, ResourceLocation.parse("spectra:materials/metal"));

    @AfterEach
    void cleanup() {
        FrequencyMaterialRules.replaceOverrides(List.of());
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
    }

    @Test
    void vanillaMaterialsKeepExistingAttenuation() {
        assertEquals(3.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState()));
        assertEquals(6.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.IRON_BLOCK.defaultBlockState()));
        assertEquals(0.4, FrequencyMaterialRules.decibelsPerMeter(Blocks.GLASS.defaultBlockState()));

        BlockState log = mock(BlockState.class);
        BlockState planks = mock(BlockState.class);
        BlockState leaves = mock(BlockState.class);

        when(log.getFluidState()).thenReturn(Fluids.EMPTY.defaultFluidState());
        when(planks.getFluidState()).thenReturn(Fluids.EMPTY.defaultFluidState());
        when(leaves.getFluidState()).thenReturn(Fluids.EMPTY.defaultFluidState());

        when(log.is(net.minecraft.tags.BlockTags.LOGS)).thenReturn(true);
        when(planks.is(net.minecraft.tags.BlockTags.PLANKS)).thenReturn(true);
        when(leaves.is(net.minecraft.tags.BlockTags.LEAVES)).thenReturn(true);

        assertEquals(1.5, FrequencyMaterialRules.decibelsPerMeter(log));
        assertEquals(1.0, FrequencyMaterialRules.decibelsPerMeter(planks));
        assertEquals(0.2, FrequencyMaterialRules.decibelsPerMeter(leaves));
    }

    @Test
    void unknownBlocksUseGenericFallback() {
        BlockState state = mock(BlockState.class);
        when(state.getFluidState()).thenReturn(Fluids.EMPTY.defaultFluidState());

        assertEquals(FrequencyMaterialClassifier.GENERIC, FrequencyMaterialClassifier.classify(state));
        assertEquals(0.5, FrequencyMaterialRules.decibelsPerMeter(state));
    }

    @Test
    void customMaterialTagsClassifyUnknownBlocks() {
        BlockState state = mock(BlockState.class);
        when(state.getFluidState()).thenReturn(Fluids.EMPTY.defaultFluidState());
        when(state.is(METAL)).thenReturn(true);

        assertEquals(FrequencyMaterialClassifier.METAL, FrequencyMaterialClassifier.classify(state));
        assertEquals(6.0, FrequencyMaterialRules.decibelsPerMeter(state));
    }

    @Test
    void materialReloadInvalidatesClassification() {
        BlockState state = mock(BlockState.class);
        when(state.getFluidState()).thenReturn(Fluids.EMPTY.defaultFluidState());
        assertEquals(FrequencyMaterialClassifier.GENERIC, FrequencyMaterialClassifier.classify(state));

        when(state.is(METAL)).thenReturn(true);
        assertEquals(FrequencyMaterialClassifier.GENERIC, FrequencyMaterialClassifier.classify(state));

        FrequencyMaterialRules.replaceOverrides(List.of());
        assertEquals(FrequencyMaterialClassifier.METAL, FrequencyMaterialClassifier.classify(state));
    }
}
