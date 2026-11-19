package com.krimzik.spectra.api.material;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencyMaterialSectionSnapshotTest {
    @AfterEach
    void cleanup() {
        FrequencyMaterialRules.replaceOverrides(List.of());
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
    }

    private static PalettedContainer<BlockState> states() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    @Test
    void sourceChangesDoNotModifyExistingSnapshot() {
        PalettedContainer<BlockState> states = states();
        states.set(1, 2, 3, Blocks.STONE.defaultBlockState());

        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.getStates()).thenReturn(states);

        FrequencyMaterialSectionSnapshot original = FrequencyMaterialSectionSnapshot.capture(section);

        assertEquals(3.0, original.cell(1, 2, 3).lossDbPerMeter(), 0.0);
        assertEquals(FrequencyMaterialClassifier.STONE, original.cell(1, 2, 3).material());

        states.set(1, 2, 3, Blocks.GLASS.defaultBlockState());

        assertEquals(3.0, original.cell(1, 2, 3).lossDbPerMeter(), 0.0);
        assertEquals(FrequencyMaterialClassifier.STONE, original.cell(1, 2, 3).material());

        FrequencyMaterialSectionSnapshot updated = FrequencyMaterialSectionSnapshot.capture(section);

        assertEquals(0.4, updated.cell(1, 2, 3).lossDbPerMeter(), 0.0);
        assertEquals(FrequencyMaterialClassifier.GLASS, updated.cell(1, 2, 3).material());
    }

    @Test
    void materialRuleChangesInvalidateSnapshotRevision() {
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.getStates()).thenReturn(states());

        FrequencyMaterialSectionSnapshot original = FrequencyMaterialSectionSnapshot.capture(section);

        assertTrue(original.currentRules());
        assertEquals(0.0, original.cell(0, 0, 0).lossDbPerMeter(), 0.0);

        FrequencyMaterialRules.setEnvironmentLoss(0.75, 0.5);

        assertFalse(original.currentRules());
        assertEquals(0.0, original.cell(0, 0, 0).lossDbPerMeter(), 0.0);

        FrequencyMaterialSectionSnapshot updated = FrequencyMaterialSectionSnapshot.capture(section);

        assertTrue(updated.currentRules());
        assertEquals(0.75, updated.cell(0, 0, 0).lossDbPerMeter(), 0.0);
    }

    @Test
    void snapshotCanBeReadFromWorkerThread() throws Exception {
        PalettedContainer<BlockState> states = states();
        states.set(4, 5, 6, Blocks.IRON_BLOCK.defaultBlockState());

        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.getStates()).thenReturn(states);

        FrequencyMaterialSectionSnapshot snapshot = FrequencyMaterialSectionSnapshot.capture(section);

        var result = CompletableFuture.supplyAsync(() -> snapshot.cell(4, 5, 6));

        assertEquals(6.0, result.get().lossDbPerMeter(), 0.0);
        assertEquals(FrequencyMaterialClassifier.METAL, result.get().material());
    }
}