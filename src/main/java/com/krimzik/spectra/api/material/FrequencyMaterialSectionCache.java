package com.krimzik.spectra.api.material;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

public final class FrequencyMaterialSectionCache {
    private final long revision;
    private final LevelChunkSection section;
    private final double sectionLoss;
    private final double[] regionLosses;
    private static final Object UNRESOLVED = new Object();
    private static final Object MIXED = new Object();
    private volatile Object uniformMaterial = UNRESOLVED;
    private volatile Object[] regionMaterials;
    private static final VarHandle TWO_BLOCK_LOSS = MethodHandles.arrayElementVarHandle(double[].class);

    private volatile double[] twoBlockLosses;
    private ResourceLocation[] twoBlockMaterials;

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

    double twoBlockLoss(int x, int y, int z, FrequencyMaterialAttenuation.TraversalStats stats) {
        int index = ((y >> 1) << 6) | ((z >> 1) << 3) | (x >> 1);

        double[] losses = twoBlockLosses;
        if (losses != null) {
            double cached = (double) TWO_BLOCK_LOSS.getAcquire(losses, index);
            if (cached != Double.NEGATIVE_INFINITY) {
                return cached;
            }
        }

        synchronized (this) {
            losses = twoBlockLosses;

            if (losses == null) {
                losses = new double[512];
                Arrays.fill(losses, Double.NEGATIVE_INFINITY);
                twoBlockMaterials = new ResourceLocation[512];
                twoBlockLosses = losses;
            }

            double cached = (double) TWO_BLOCK_LOSS.getAcquire(losses, index);
            if (cached != Double.NEGATIVE_INFINITY) {
                return cached;
            }

            int baseX = x & ~1;
            int baseY = y & ~1;
            int baseZ = z & ~1;

            BlockState first = section.getBlockState(baseX, baseY, baseZ);
            double loss = FrequencyMaterialRules.decibelsPerMeter(first);
            ResourceLocation material = FrequencyMaterialClassifier.classify(first);

            boolean sameLoss = true;
            boolean sameMaterial = true;
            int reads = 1;

            outer:
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }

                        BlockState state = section.getBlockState(baseX + dx, baseY + dy, baseZ + dz);
                        reads++;

                        if (FrequencyMaterialRules.decibelsPerMeter(state) != loss) {
                            sameLoss = false;
                            break outer;
                        }

                        if (sameMaterial && !FrequencyMaterialClassifier.classify(state).equals(material)) {
                            sameMaterial = false;
                        }
                    }
                }
            }

            double result = sameLoss ? loss : Double.NaN;

            twoBlockMaterials[index] = sameLoss && sameMaterial ? material : null;
            TWO_BLOCK_LOSS.setRelease(losses, index, result);

            if (stats != null) {
                stats.twoBlockBuilds++;
                stats.twoBlockBuildReads += reads;
            }

            return result;
        }
    }

    ResourceLocation twoBlockMaterial(int x, int y, int z) {
        ResourceLocation[] materials = twoBlockMaterials;

        if (materials == null) {
            return null;
        }

        int index = ((y >> 1) << 6) | ((z >> 1) << 3) | (x >> 1);
        return materials[index];
    }

    public ResourceLocation sectionMaterial() {
        Object value = uniformMaterial;

        if (value == UNRESOLVED) {
            synchronized (this) {
                value = uniformMaterial;

                if (value == UNRESOLVED) {
                    ResourceLocation[] first = {null};
                    boolean[] mixed = {false};

                    section.getStates().getAll(state -> {
                        ResourceLocation material = FrequencyMaterialClassifier.classify(state);

                        if (first[0] == null) {
                            first[0] = material;
                        } else if (!first[0].equals(material)) {
                            mixed[0] = true;
                        }
                    });

                    value = first[0] != null && !mixed[0] ? first[0] : MIXED;
                    uniformMaterial = value;
                }
            }
        }

        return value instanceof ResourceLocation material ? material : null;
    }

    public ResourceLocation regionMaterial(int x, int y, int z) {
        ResourceLocation whole = sectionMaterial();

        if (whole != null) {
            return whole;
        }

        int rx = x >> 2;
        int ry = y >> 2;
        int rz = z >> 2;
        int index = (ry << 4) | (rz << 2) | rx;

        Object[] regions = regionMaterials;

        if (regions == null) {
            synchronized (this) {
                if (regionMaterials == null) {
                    regionMaterials = new Object[64];
                }

                regions = regionMaterials;
            }
        }

        Object value = regions[index];

        if (value == null) {
            synchronized (this) {
                value = regions[index];

                if (value == null) {
                    int baseX = rx << 2;
                    int baseY = ry << 2;
                    int baseZ = rz << 2;

                    ResourceLocation first = FrequencyMaterialClassifier.classify(section.getBlockState(baseX, baseY, baseZ));
                    value = first;

                    outer:
                    for (int dy = 0; dy < 4; dy++) {
                        for (int dz = 0; dz < 4; dz++) {
                            for (int dx = 0; dx < 4; dx++) {
                                ResourceLocation material = FrequencyMaterialClassifier.classify(section.getBlockState(baseX + dx, baseY + dy, baseZ + dz));
                                if (!first.equals(material)) {
                                    value = MIXED;
                                    break outer;
                                }
                            }
                        }
                    }

                    regions[index] = value;
                }
            }
        }

        return value instanceof ResourceLocation material ? material : null;
    }
}