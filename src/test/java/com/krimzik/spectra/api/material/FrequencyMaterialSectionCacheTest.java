package com.krimzik.spectra.api.material;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrequencyMaterialSectionCacheTest {
    @AfterEach
    void cleanup() {
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        FrequencyMaterialRules.replaceOverrides(List.of());
    }

    @SuppressWarnings("unchecked")
    private static LevelChunkSection section(BlockState... paletteStates) {
        LevelChunkSection section = mock(LevelChunkSection.class);
        PalettedContainer<BlockState> palette = mock(PalettedContainer.class);

        when(section.getStates()).thenReturn(palette);

        doAnswer(call -> {
            Consumer<BlockState> consumer = call.getArgument(0);
            for (BlockState state : paletteStates) {
                consumer.accept(state);
            }
            return null;
        }).when(palette).getAll(any());

        return section;
    }

    @Test
    void equalMaterialCoefficientsProduceUniformSection() {
        LevelChunkSection section = section(Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState());

        FrequencyMaterialSectionCache cache = FrequencyMaterialSectionCache.create(section);

        assertNotNull(cache);
        assertEquals(3.0, cache.sectionLoss(), 0.0);
        assertEquals(3.0, cache.regionLoss(7, 3, 12), 0.0);

        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    void mixedSectionOnlyFallsBackInsideMixedRegions() {
        LevelChunkSection section = section(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState());

        when(section.getBlockState(anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    int x = call.getArgument(0);
                    return x == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                });

        FrequencyMaterialSectionCache cache = FrequencyMaterialSectionCache.create(section);

        assertNotNull(cache);
        assertTrue(Double.isNaN(cache.sectionLoss()));
        assertTrue(Double.isNaN(cache.regionLoss(0, 0, 0)));
        assertEquals(0.0, cache.regionLoss(5, 0, 0), 0.0);
    }

    @Test
    void materialRuleChangesInvalidateOldSummaries() {
        LevelChunkSection section = section(Blocks.AIR.defaultBlockState());

        FrequencyMaterialSectionCache original = FrequencyMaterialSectionCache.create(section);

        assertNotNull(original);
        assertEquals(0.0, original.sectionLoss(), 0.0);

        FrequencyMaterialRules.setEnvironmentLoss(0.25, 0.5);

        assertFalse(original.current());

        FrequencyMaterialSectionCache updated = FrequencyMaterialSectionCache.create(section);

        assertNotNull(updated);
        assertTrue(updated.current());
        assertEquals(0.25, updated.sectionLoss(), 0.0);
    }

    @Test
    void equalAttenuationDoesNotHideDifferentMaterials() {
        FrequencyMaterialRules.replaceOverrides(List.of(FrequencyMaterialRules.block(ResourceLocation.parse("minecraft:glass"), 3.0)));
        LevelChunkSection section = section(Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState());

        when(section.getBlockState(anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    int x = call.getArgument(0);
                    return x == 1 ? Blocks.GLASS.defaultBlockState() : Blocks.STONE.defaultBlockState();
                });

        FrequencyMaterialSectionCache cache = FrequencyMaterialSectionCache.create(section);

        assertNotNull(cache);
        assertEquals(3.0, cache.sectionLoss(), 0.0);
        assertNull(cache.sectionMaterial());
        assertNull(cache.regionMaterial(0, 0, 0));
        assertEquals(FrequencyMaterialClassifier.STONE, cache.regionMaterial(5, 0, 0));
    }

    @Test
    void uniformMaterialSectionAvoidsRegionBlockReads() {
        LevelChunkSection section = section(Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState());

        FrequencyMaterialSectionCache cache = FrequencyMaterialSectionCache.create(section);

        assertNotNull(cache);
        assertEquals(FrequencyMaterialClassifier.STONE, cache.sectionMaterial());
        assertEquals(FrequencyMaterialClassifier.STONE, cache.regionMaterial(3, 5, 7));

        verify(section, never()).getBlockState(anyInt(), anyInt(), anyInt());
    }
}
