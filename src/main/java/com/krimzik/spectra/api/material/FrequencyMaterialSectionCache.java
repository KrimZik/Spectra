package com.krimzik.spectra.api.material;

import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Arrays;

public final class FrequencyMaterialSectionCache {
    private final long revision;
    private final LevelChunkSection section;
    private final double sectionLoss;
    private final double[] regionLosses;

    private FrequencyMaterialSectionCache(LevelChunkSection section, long revision, double sectionLoss) {
        this.section = section;
        this.revision = revision;
        this.sectionLoss = sectionLoss;
        this.regionLosses = Double.isNaN(sectionLoss) ? new double[64] : null;

        if (regionLosses != null) {
            Arrays.fill(regionLosses, Double.NEGATIVE_INFINITY);
        }
    }

    public static FrequencyMaterialSectionCache create(LevelChunkSection section) {
        PalettedContainer<?> states = section.getStates();

        if (states == null) {
            return null;
        }

        double[] first = {-1.0};
        boolean[] mixed = {false};

        section.getStates().getAll(state -> {
            double loss = FrequencyMaterialRules.decibelsPerMeter(state);

            if (first[0] < 0.0) {
                first[0] = loss;
            } else if (loss != first[0]) {
                mixed[0] = true;
            }
        });

        if (first[0] < 0.0) {
            return null;
        }

        return new FrequencyMaterialSectionCache(section, FrequencyMaterialRules.revision(), mixed[0] ? Double.NaN : first[0]);
    }

    public boolean current() {
        return revision == FrequencyMaterialRules.revision();
    }

    public double sectionLoss() {
        return sectionLoss;
    }

    public double regionLoss(int x, int y, int z) {
        if (!Double.isNaN(sectionLoss)) {
            return sectionLoss;
        }

        int rx = x >> 2;
        int ry = y >> 2;
        int rz = z >> 2;
        int index = (ry << 4) | (rz << 2) | rx;

        double cached = regionLosses[index];

        if (cached != Double.NEGATIVE_INFINITY) {
            return cached;
        }

        int baseX = rx << 2;
        int baseY = ry << 2;
        int baseZ = rz << 2;

        double loss = FrequencyMaterialRules.decibelsPerMeter(section.getBlockState(baseX, baseY, baseZ));
        for (int dy = 0; dy < 4; dy++) {
            for (int dz = 0; dz < 4; dz++) {
                for (int dx = 0; dx < 4; dx++) {
                    if (FrequencyMaterialRules.decibelsPerMeter(section.getBlockState(baseX + dx, baseY + dy, baseZ + dz)) != loss) {
                        regionLosses[index] = Double.NaN;
                        return Double.NaN;
                    }
                }
            }
        }

        regionLosses[index] = loss;
        return loss;
    }
}
