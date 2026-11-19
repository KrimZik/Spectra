package com.krimzik.spectra.api.material;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Consumer;

public final class FrequencyMaterialSectionSnapshot {
    public record Cell(double lossDbPerMeter, ResourceLocation material) {}

    private final PalettedContainer<BlockState> states;
    private final IdentityHashMap<BlockState, Cell> cells;
    private final long materialRevision;
    private volatile FrequencyMaterialSectionCache summary;

    private FrequencyMaterialSectionSnapshot(PalettedContainer<BlockState> states, IdentityHashMap<BlockState, Cell> cells, long materialRevision) {
        this.states = states;
        this.cells = cells;
        this.materialRevision = materialRevision;
    }

    public static FrequencyMaterialSectionSnapshot capture(LevelChunkSection section) {
        Objects.requireNonNull(section);

        long revision = FrequencyMaterialRules.revision();
        PalettedContainer<BlockState> states = section.getStates().copy();
        IdentityHashMap<BlockState, Cell> cells = new IdentityHashMap<>();

        states.getAll(state -> cells.put(state, new Cell(FrequencyMaterialRules.decibelsPerMeter(state), FrequencyMaterialClassifier.classify(state))));

        return new FrequencyMaterialSectionSnapshot(states, cells, revision);
    }

    public Cell cell(int x, int y, int z) {
        return cells.get(states.get(x, y, z));
    }

    public boolean currentRules() {
        return materialRevision == FrequencyMaterialRules.revision();
    }

    long revision() {
        return materialRevision;
    }

    void forEachCell(Consumer<Cell> consumer) {
        cells.values().forEach(consumer);
    }

    FrequencyMaterialSectionCache summary() {
        FrequencyMaterialSectionCache result = summary;

        if (result == null) {
            synchronized (this) {
                result = summary;

                if (result == null) {
                    result = FrequencyMaterialSectionCache.create(this);
                    summary = result;
                }
            }
        }

        return result;
    }
}