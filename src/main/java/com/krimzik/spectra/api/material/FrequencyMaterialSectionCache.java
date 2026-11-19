package com.krimzik.spectra.api.material;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;
import java.util.Arrays;

public final class FrequencyMaterialSectionCache {
    record Region8(double loss, ResourceLocation material) {}
    private final long revision;
    private final LevelChunkSection section;
    private final FrequencyMaterialSectionSnapshot snapshot;
    private final double sectionLoss;
    private final double[] regionLosses;
    private static final Object UNRESOLVED = new Object();
    private static final Object MIXED = new Object();
    private volatile Object uniformMaterial = UNRESOLVED;
    private volatile Object[] regionMaterials;
    private static final VarHandle TWO_BLOCK_LOSS = MethodHandles.arrayElementVarHandle(double[].class);
    private static final VarHandle REGION_LOSS = MethodHandles.arrayElementVarHandle(double[].class);
    private static final VarHandle REGION_MATERIAL = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final VarHandle REGION_8 = MethodHandles.arrayElementVarHandle(Object[].class);
    private volatile Object[] eightBlockRegions;

    private volatile double[] twoBlockLosses;
    private ResourceLocation[] twoBlockMaterials;

    private FrequencyMaterialSectionCache(LevelChunkSection section, FrequencyMaterialSectionSnapshot snapshot, long revision, double sectionLoss) {
        this.section = section;
        this.snapshot = snapshot;
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

        return new FrequencyMaterialSectionCache(section, null, FrequencyMaterialRules.revision(), mixed[0] ? Double.NaN : first[0]);
    }

    static FrequencyMaterialSectionCache create(FrequencyMaterialSectionSnapshot snapshot) {
        double[] first = {-1.0};
        boolean[] mixed = {false};

        snapshot.forEachCell(cell -> {
            double loss = cell.lossDbPerMeter();

            if (first[0] < 0.0) {
                first[0] = loss;
            } else if (loss != first[0]) {
                mixed[0] = true;
            }
        });

        return new FrequencyMaterialSectionCache(null, snapshot, snapshot.revision(), mixed[0] ? Double.NaN : first[0]);
    }

    private double lossAt(int x, int y, int z) {
        return snapshot != null ? snapshot.cell(x, y, z).lossDbPerMeter() : FrequencyMaterialRules.decibelsPerMeter(section.getBlockState(x, y, z));
    }

    private ResourceLocation materialAt(int x, int y, int z) {
        return snapshot != null ? snapshot.cell(x, y, z).material() : FrequencyMaterialClassifier.classify(section.getBlockState(x, y, z));
    }

    private void forEachDistinctMaterial(Consumer<ResourceLocation> consumer) {
        if (snapshot != null) {
            snapshot.forEachCell(cell -> consumer.accept(cell.material()));
        } else {
            section.getStates().getAll(state -> consumer.accept(FrequencyMaterialClassifier.classify(state)));
        }
    }

    public boolean current() {
        return revision == FrequencyMaterialRules.revision();
    }

    public double sectionLoss() {
        return sectionLoss;
    }

    Region8 region8(int x, int y, int z) {
        int index = ((y >> 3) << 2) | ((z >> 3) << 1) | (x >> 3);

        Object[] regions = eightBlockRegions;

        if (regions == null) {
            synchronized (this) {
                regions = eightBlockRegions;
                if (regions == null) {
                    regions = new Object[8];
                    eightBlockRegions = regions;
                }
            }
        }

        Object value = REGION_8.getAcquire(regions, index);
        if (value == null) {
            synchronized (this) {
                value = REGION_8.getAcquire(regions, index);

                if (value == null) {
                    int baseX = x & 8;
                    int baseY = y & 8;
                    int baseZ = z & 8;

                    double loss = regionLoss(baseX, baseY, baseZ);
                    ResourceLocation material = loss >= 0.0 ? regionMaterial(baseX, baseY, baseZ) : null;
                    boolean uniform = loss >= 0.0 && material != null;

                    outer:
                    for (int dy = 0; dy < 8 && uniform; dy += 4) {
                        for (int dz = 0; dz < 8; dz += 4) {
                            for (int dx = 0; dx < 8; dx += 4) {
                                if (dx == 0 && dy == 0 && dz == 0) {
                                    continue;
                                }

                                int bx = baseX + dx;
                                int by = baseY + dy;
                                int bz = baseZ + dz;

                                if (Double.compare(regionLoss(bx, by, bz), loss) != 0 || !material.equals(regionMaterial(bx, by, bz))) {
                                    uniform = false;
                                    break outer;
                                }
                            }
                        }
                    }

                    value = uniform ? new Region8(loss, material) : MIXED;
                    REGION_8.setRelease(regions, index, value);
                }
            }
        }

        return value instanceof Region8 region ? region : null;
    }

    public double regionLoss(int x, int y, int z) {
        if (!Double.isNaN(sectionLoss)) {
            return sectionLoss;
        }

        int rx = x >> 2;
        int ry = y >> 2;
        int rz = z >> 2;
        int index = (ry << 4) | (rz << 2) | rx;

        double cached = (double) REGION_LOSS.getAcquire(regionLosses, index);
        if (cached != Double.NEGATIVE_INFINITY) {
            return cached;
        }

        synchronized (this) {
            cached = (double) REGION_LOSS.getAcquire(regionLosses, index);
            if (cached != Double.NEGATIVE_INFINITY) {
                return cached;
            }

            int baseX = rx << 2;
            int baseY = ry << 2;
            int baseZ = rz << 2;
            double loss = lossAt(baseX, baseY, baseZ);

            for (int dy = 0; dy < 4; dy++) {
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        if (lossAt(baseX + dx, baseY + dy, baseZ + dz) != loss) {
                            REGION_LOSS.setRelease(regionLosses, index, Double.NaN);
                            return Double.NaN;
                        }
                    }
                }
            }

            REGION_LOSS.setRelease(regionLosses, index, loss);
            return loss;
        }
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

            FrequencyMaterialSectionSnapshot.Cell firstCell = snapshot == null ? null : snapshot.cell(baseX, baseY, baseZ);
            BlockState firstState = snapshot == null ? section.getBlockState(baseX, baseY, baseZ) : null;

            double loss = firstCell != null ? firstCell.lossDbPerMeter() : FrequencyMaterialRules.decibelsPerMeter(firstState);
            ResourceLocation material = firstCell != null ? firstCell.material() : FrequencyMaterialClassifier.classify(firstState);

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

                        int bx = baseX + dx;
                        int by = baseY + dy;
                        int bz = baseZ + dz;
                        reads++;

                        FrequencyMaterialSectionSnapshot.Cell cell = snapshot == null ? null : snapshot.cell(bx, by, bz);
                        BlockState state = snapshot == null ? section.getBlockState(bx, by, bz) : null;

                        double currentLoss = cell != null ? cell.lossDbPerMeter() : FrequencyMaterialRules.decibelsPerMeter(state);
                        if (currentLoss != loss) {
                            sameLoss = false;
                            break outer;
                        }

                        if (sameMaterial) {
                            ResourceLocation currentMaterial = cell != null ? cell.material() : FrequencyMaterialClassifier.classify(state);

                            if (!material.equals(currentMaterial)) {
                                sameMaterial = false;
                            }
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
        double[] losses = twoBlockLosses;
        if (losses == null) {
            return null;
        }

        int index = ((y >> 1) << 6) | ((z >> 1) << 3) | (x >> 1);
        if ((double) TWO_BLOCK_LOSS.getAcquire(losses, index) == Double.NEGATIVE_INFINITY) {
            return null;
        }

        ResourceLocation[] materials = twoBlockMaterials;
        return materials == null ? null : materials[index];
    }

    public ResourceLocation sectionMaterial() {
        Object value = uniformMaterial;

        if (value == UNRESOLVED) {
            synchronized (this) {
                value = uniformMaterial;

                if (value == UNRESOLVED) {
                    ResourceLocation[] first = {null};
                    boolean[] mixed = {false};

                    forEachDistinctMaterial(material -> {
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

        Object value = REGION_MATERIAL.getAcquire(regions, index);

        if (value == null) {
            synchronized (this) {
                value = REGION_MATERIAL.getAcquire(regions, index);

                if (value == null) {
                    int baseX = rx << 2;
                    int baseY = ry << 2;
                    int baseZ = rz << 2;

                    ResourceLocation first = materialAt(baseX, baseY, baseZ);
                    value = first;

                    outer:
                    for (int dy = 0; dy < 4; dy++) {
                        for (int dz = 0; dz < 4; dz++) {
                            for (int dx = 0; dx < 4; dx++) {
                                ResourceLocation material = materialAt(baseX + dx, baseY + dy, baseZ + dz);
                                if (!first.equals(material)) {
                                    value = MIXED;
                                    break outer;
                                }
                            }
                        }
                    }

                    REGION_MATERIAL.setRelease(regions, index, value);
                }
            }
        }

        return value instanceof ResourceLocation material ? material : null;
    }
}