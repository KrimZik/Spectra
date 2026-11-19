package com.krimzik.spectra.api.material;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.*;

public final class FrequencyMaterialSectionSnapshot {
    public record Cell(double lossDbPerMeter, ResourceLocation material) {}

    static final class MaterialGrid {
        private final short[] voxels;
        private final Cell[] palette;
        private final short[] twoRegions;
        private final short[] fourRegions;
        private final short[] eightRegions;
        private final long mixedFourMask;
        private final int mixedEightMask;

        MaterialGrid(short[] voxels, Cell[] palette, short[] twoRegions, short[] fourRegions, short[] eightRegions, long mixedFourMask, int mixedEightMask) {
            this.voxels = voxels;
            this.palette = palette;
            this.twoRegions = twoRegions;
            this.fourRegions = fourRegions;
            this.eightRegions = eightRegions;
            this.mixedFourMask = mixedFourMask;
            this.mixedEightMask = mixedEightMask;
        }

        Cell cell(int x, int y, int z) {
            int index = (y << 8) | (z << 4) | x;
            return palette[Short.toUnsignedInt(voxels[index])];
        }

        Cell paletteCell(int id) {
            return palette[id];
        }

        int region4Id(int x, int y, int z) {
            int index = ((y >> 2) << 4) | ((z >> 2) << 2) | (x >> 2);
            return (mixedFourMask & (1L << index)) != 0 ? -1 : Short.toUnsignedInt(fourRegions[index]);
        }

        int region8Id(int x, int y, int z) {
            int index = ((y >> 3) << 2) | ((z >> 3) << 1) | (x >> 3);
            return (mixedEightMask & (1 << index)) != 0 ? -1 : Short.toUnsignedInt(eightRegions[index]);
        }

        int region2Id(int x, int y, int z) {
            int index = ((y >> 1) << 6) | ((z >> 1) << 3) | (x >> 1);
            int id = twoRegions[index];
            return id < 0 ? -1 : id;
        }
    }

    private final PalettedContainer<BlockState> states;
    private final IdentityHashMap<BlockState, Cell> cells;
    private final long materialRevision;
    private final double sectionLoss;
    private final ResourceLocation sectionMaterial;
    private volatile MaterialGrid materialGrid;

    private FrequencyMaterialSectionSnapshot(PalettedContainer<BlockState> states, IdentityHashMap<BlockState, Cell> cells, long materialRevision, double sectionLoss, ResourceLocation sectionMaterial) {
        this.states = states;
        this.cells = cells;
        this.materialRevision = materialRevision;
        this.sectionLoss = sectionLoss;
        this.sectionMaterial = sectionMaterial;
    }

    public static FrequencyMaterialSectionSnapshot capture(LevelChunkSection section) {
        Objects.requireNonNull(section);

        long revision = FrequencyMaterialRules.revision();
        PalettedContainer<BlockState> states = section.getStates().copy();
        IdentityHashMap<BlockState, Cell> cells = new IdentityHashMap<>();

        states.getAll(state -> cells.computeIfAbsent(state, key -> new Cell(FrequencyMaterialRules.decibelsPerMeter(key), FrequencyMaterialClassifier.classify(key))));

        Cell first = cells.values().iterator().next();
        double loss = first.lossDbPerMeter();
        ResourceLocation material = first.material();

        for (Cell cell : cells.values()) {
            if (loss != cell.lossDbPerMeter()) {
                loss = Double.NaN;
            }

            if (material != null && !material.equals(cell.material())) {
                material = null;
            }
        }

        return new FrequencyMaterialSectionSnapshot(states, cells, revision, loss, material);
    }

    public Cell cell(int x, int y, int z) {
        MaterialGrid grid = materialGrid;
        return grid == null ? cells.get(states.get(x, y, z)) : grid.cell(x, y, z);
    }

    double sectionLoss() {
        return sectionLoss;
    }

    ResourceLocation sectionMaterial() {
        return sectionMaterial;
    }

    public boolean currentRules() {
        return materialRevision == FrequencyMaterialRules.revision();
    }

    MaterialGrid grid() {
        MaterialGrid result = materialGrid;

        if (result == null) {
            synchronized (this) {
                result = materialGrid;

                if (result == null) {
                    short[] voxels = new short[4096];
                    short[] twoRegions = new short[512];
                    short[] fourRegions = new short[64];
                    short[] eightRegions = new short[8];

                    Arrays.fill(twoRegions, (short) -1);
                    Arrays.fill(fourRegions, (short) -1);
                    Arrays.fill(eightRegions, (short) -1);

                    HashMap<Cell, Short> indices = new HashMap<>();
                    ArrayList<Cell> palette = new ArrayList<>();

                    long mixedFourMask = 0L;
                    int mixedEightMask = 0;

                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                Cell cell = Objects.requireNonNull(cells.get(states.get(x, y, z)));
                                Short id = indices.get(cell);

                                if (id == null) {
                                    id = (short) palette.size();
                                    indices.put(cell, id);
                                    palette.add(cell);
                                }

                                voxels[(y << 8) | (z << 4) | x] = id;

                                int twoIndex = ((y >> 1) << 6) | ((z >> 1) << 3) | (x >> 1);
                                if (twoRegions[twoIndex] == -1) {
                                    twoRegions[twoIndex] = id;
                                } else if (twoRegions[twoIndex] != id) {
                                    twoRegions[twoIndex] = -2;
                                }

                                int fourIndex = ((y >> 2) << 4) | ((z >> 2) << 2) | (x >> 2);
                                if (fourRegions[fourIndex] == -1) {
                                    fourRegions[fourIndex] = id;
                                } else if (fourRegions[fourIndex] != id) {
                                    mixedFourMask |= 1L << fourIndex;
                                }

                                int eightIndex = ((y >> 3) << 2) | ((z >> 3) << 1) | (x >> 3);
                                if (eightRegions[eightIndex] == -1) {
                                    eightRegions[eightIndex] = id;
                                } else if (eightRegions[eightIndex] != id) {
                                    mixedEightMask |= 1 << eightIndex;
                                }
                            }
                        }
                    }

                    result = new MaterialGrid(voxels, palette.toArray(Cell[]::new), twoRegions, fourRegions, eightRegions, mixedFourMask, mixedEightMask);
                    materialGrid = result;
                }
            }
        }

        return result;
    }
}