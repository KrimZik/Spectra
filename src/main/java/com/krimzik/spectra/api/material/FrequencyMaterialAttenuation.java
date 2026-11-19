package com.krimzik.spectra.api.material;

import com.krimzik.spectra.math.MaterialAttenuationMath;
import com.krimzik.spectra.propagation.geometry.MaterialBoundaryScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.DoubleSupplier;

public final class FrequencyMaterialAttenuation {
    private static volatile boolean enabled;

    private static final ThreadLocal<TraversalStats> ACTIVE_STATS = new ThreadLocal<>();
    private static final ThreadLocal<BlockPos.MutableBlockPos> BLOCK_LOOKUP_POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private static final int CHUNK_CACHE_SIZE = 64;
    private static final ThreadLocal<ChunkLookupBatch> ACTIVE_CHUNK_BATCH = new ThreadLocal<>();

    private static final class ChunkLookupBatch {
        final long[] keys = new long[CHUNK_CACHE_SIZE];
        final LevelChunk[] chunks = new LevelChunk[CHUNK_CACHE_SIZE];

        ServerLevel level;
        int depth;

        LevelChunk get(ServerChunkCache source, int x, int z) {
            long key = ChunkPos.asLong(x, z);
            long hash = key * 0x9E3779B97F4A7C15L;
            int slot = (int) (hash ^ (hash >>> 32)) & (CHUNK_CACHE_SIZE - 1);

            LevelChunk chunk = chunks[slot];
            if (chunk != null && keys[slot] == key) {
                return chunk;
            }

            chunk = source.getChunkNow(x, z);
            keys[slot] = key;
            chunks[slot] = chunk;
            return chunk;
        }
    }

    static void beginChunkBatch(ServerLevel level) {
        Objects.requireNonNull(level);
        ChunkLookupBatch batch = ACTIVE_CHUNK_BATCH.get();
        if (batch == null) {
            batch = new ChunkLookupBatch();
            ACTIVE_CHUNK_BATCH.set(batch);
        }

        if (batch.depth == 0) {
            batch.level = level;
        }

        batch.depth++;
    }

    static void endChunkBatch() {
        ChunkLookupBatch batch = ACTIVE_CHUNK_BATCH.get();
        if (batch == null || batch.depth == 0) {
            throw new IllegalStateException("No active reception chunk batch");
        }

        if (--batch.depth == 0) {
            Arrays.fill(batch.chunks, null);
            batch.level = null;
        }
    }

    public static final class TraversalStats {
        long paths;
        long unloadedChunks;
        long sections;
        long uniformSections;
        long uniformEightRegions;
        long uniformRegions;
        long blockReads;
        long sameSectionPaths;
        long steps;
        long regionChecks;
        long regionFallbacks;
        long materialSegments;
        long twoBlockChecks;
        long twoBlockSkips;
        long twoBlockBuilds;
        long twoBlockBuildReads;
        MaterialProbe probe;

        @Override
        public String toString() {
            return "paths=" + paths + " unloadedChunks=" + unloadedChunks + " sections=" + sections + " uniformSections=" + uniformSections + " uniformRegions=" + uniformRegions + " blockReads=" + blockReads + " sameSectionPaths=" + sameSectionPaths + " steps=" + steps + " regionChecks=" + regionChecks + " regionFallbacks=" + regionFallbacks + " materialSegments=" + materialSegments + " twoBlockChecks=" + twoBlockChecks + " twoBlockSkips=" + twoBlockSkips + " twoBlockBuilds=" + twoBlockBuilds + " twoBlockBuildReads=" + twoBlockBuildReads + " uniformEightRegions=" + uniformEightRegions;
        }
    }

    private static final class MaterialProbe implements MaterialSegmentConsumer {
        MaterialSegmentConsumer delegate;
        ResourceLocation firstMaterial;
        double firstLoss;
        long segments;
        boolean mixed;

        @Override
        public void accept(ResourceLocation material, double from, double to, int axes) {
            delegate.accept(material, from, to, axes);
        }

        @Override
        public void acceptWithLoss(ResourceLocation material, double from, double to, int axes, double loss) {
            if (segments == 0L) {
                firstMaterial = material;
                firstLoss = loss;
            } else if (!firstMaterial.equals(material) || Double.compare(firstLoss, loss) != 0) {
                mixed = true;
            }

            segments++;
            delegate.acceptWithLoss(material, from, to, axes, loss);
        }
    }

    public record SnapshotPathSample(double factor, boolean complete, boolean uniform, long segments, long steps, long sections, long regionChecks, long uniformSections, long uniformEightRegions, long uniformRegions, long blockReads) {}

    public static TraversalStats beginTraversalStats() {
        if (ACTIVE_STATS.get() != null) {
            throw new IllegalStateException("Material traversal profiling already active");
        }

        TraversalStats stats = new TraversalStats();
        ACTIVE_STATS.set(stats);
        return stats;
    }

    public static void endTraversalStats() {
        ACTIVE_STATS.remove();
    }

    public static SnapshotPathSample sampleSnapshotPath(DoubleSupplier calculation) {
        Objects.requireNonNull(calculation);

        TraversalStats stats = beginTraversalStats();
        stats.probe = new MaterialProbe();

        try {
            double factor = calculation.getAsDouble();
            MaterialProbe probe = stats.probe;

            return new SnapshotPathSample(factor, Double.isFinite(factor) && probe.segments > 0, probe.segments > 0 && !probe.mixed, probe.segments, stats.steps, stats.sections, stats.regionChecks, stats.uniformSections, stats.uniformEightRegions, stats.uniformRegions, stats.blockReads);
        } finally {
            endTraversalStats();
        }
    }

    private FrequencyMaterialAttenuation() {}

    @FunctionalInterface
    public interface MaterialSegmentConsumer {
        void accept(ResourceLocation material, double startFraction, double endFraction, int enteredAxes);

        default void acceptWithLoss(ResourceLocation material, double startFraction, double endFraction, int enteredAxes, double decibelsPerMeter) {
            accept(material, startFraction, endFraction, enteredAxes);
        }
    }

    private static double boundaryTime(double offset, double direction, double inverseDirection) {
        return Double.isFinite(inverseDirection) ? offset * inverseDirection : offset / direction;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static double factor(ServerLevel level, Vec3 start, Vec3 end) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        return factor(level, start.x, start.y, start.z, end.x, end.y, end.z);
    }

    static double factor(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ) {
        return factor(level, startX, startY, startZ, endX, endY, endZ, false);
    }

    static double factorRelativeToAir(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ) {
        return factor(level, startX, startY, startZ, endX, endY, endZ, true);
    }

    static double factorRelativeToAirWithMaterials(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ, MaterialSegmentConsumer consumer) {
        Objects.requireNonNull(consumer);
        return factor(level, startX, startY, startZ, endX, endY, endZ, true, null, consumer);
    }

    static double factorWithTrace(ServerLevel level, Vec3 start, Vec3 end, FrequencyMaterialPathCache.Trace trace) {
        return factor(level, start.x, start.y, start.z, end.x, end.y, end.z, false, trace);
    }

    public static double factorWithMaterials(ServerLevel level, Vec3 start, Vec3 end, MaterialSegmentConsumer consumer) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(consumer);

        return factor(level, start.x, start.y, start.z, end.x, end.y, end.z, false, null, consumer);
    }

    public static double factorSnapshot(FrequencyMaterialWorldSnapshot snapshot, Vec3 start, Vec3 end) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        return scan(null, snapshot, start.x, start.y, start.z, end.x, end.y, end.z, false, null, null);
    }

    static double factorSnapshotRelativeToAir(FrequencyMaterialWorldSnapshot snapshot, Vec3 start, Vec3 end) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        return scan(null, snapshot, start.x, start.y, start.z, end.x, end.y, end.z, true, null, null);
    }

    static double factorSnapshotRelativeToAirWithMaterials(FrequencyMaterialWorldSnapshot snapshot, Vec3 start, Vec3 end, MaterialSegmentConsumer consumer) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(consumer);

        return scan(null, snapshot, start.x, start.y, start.z, end.x, end.y, end.z, true, null, consumer);
    }

    public static double factorSnapshotWithMaterials(FrequencyMaterialWorldSnapshot snapshot, Vec3 start, Vec3 end, MaterialSegmentConsumer consumer) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(consumer);

        return scan(null, snapshot, start.x, start.y, start.z, end.x, end.y, end.z, false, null, consumer);
    }

    static double factorWithTraceAndMaterials(ServerLevel level, Vec3 start, Vec3 end, FrequencyMaterialPathCache.Trace trace, MaterialSegmentConsumer consumer) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(consumer);

        return factor(level, start.x, start.y, start.z, end.x, end.y, end.z, false, trace, consumer);
    }

    private static double factor(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ, boolean relativeToAir) {
        return factor(level, startX, startY, startZ, endX, endY, endZ, relativeToAir, null);
    }

    private static double factor(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ, boolean relativeToAir, FrequencyMaterialPathCache.Trace trace) {
        return factor(level, startX, startY, startZ, endX, endY, endZ, relativeToAir, trace, null);
    }

    private static double factor(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ, boolean relativeToAir, FrequencyMaterialPathCache.Trace trace, MaterialSegmentConsumer consumer) {
        return scan(level, null, startX, startY, startZ, endX, endY, endZ, relativeToAir, trace, consumer);
    }

    private static double scan(ServerLevel level, FrequencyMaterialWorldSnapshot world, double startX, double startY, double startZ, double endX, double endY, double endZ, boolean relativeToAir, FrequencyMaterialPathCache.Trace trace, MaterialSegmentConsumer consumer) {
        if (world == null) {
            Objects.requireNonNull(level);
        }

        boolean active = world == null ? enabled : world.enabled();
        double airLoss = world == null ? FrequencyMaterialRules.airLossDbPerMeter() : world.airLoss();

        TraversalStats stats = ACTIVE_STATS.get();
        if (stats != null) {
            stats.paths++;

            if (stats.probe != null && consumer != null) {
                stats.probe.delegate = consumer;
                consumer = stats.probe;
            }
        }

        if (!active && consumer == null) {
            return 1.0;
        }

        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        double distance = Math.hypot(Math.hypot(dx, dy), dz);

        if (!(distance > 0.0) || !Double.isFinite(distance)) {
            return 1.0;
        }

        int sx = Double.compare(dx, 0.0);
        int sy = Double.compare(dy, 0.0);
        int sz = Double.compare(dz, 0.0);

        double inverseX = 1.0 / dx;
        double inverseY = 1.0 / dy;
        double inverseZ = 1.0 / dz;

        int x = (int) Math.floor(startX);
        int y = (int) Math.floor(startY);
        int z = (int) Math.floor(startZ);

        int cachedChunkX = Integer.MIN_VALUE;
        int cachedChunkZ = Integer.MIN_VALUE;

        ServerChunkCache chunkSource = world == null ? level.getChunkSource() : null;
        ChunkLookupBatch chunkBatch = world == null ? ACTIVE_CHUNK_BATCH.get() : null;
        if (chunkBatch != null && (chunkBatch.depth == 0 || chunkBatch.level != level)) {
            chunkBatch = null;
        }

        LevelChunk chunk = null;
        LevelChunkSection[] sections = null;
        LevelChunkSection section = null;
        FrequencyMaterialWorldSnapshot.Chunk snapshotChunk = null;
        FrequencyMaterialSectionSnapshot snapshotSection = null;
        FrequencyMaterialSectionCache materialCache = null;
        FrequencyMaterialSectionSnapshot.MaterialGrid materialGrid = null;
        boolean chunkAvailable = false;

        int cachedSectionY = Integer.MIN_VALUE;
        boolean emptySection = false;
        double uniformSectionLoss = Double.NaN;
        boolean skipAirSections = world == null ? FrequencyMaterialRules.canSkipAirSections() : world.skipAir();

        double t = 0.0;
        double loss = 0.0;
        int enteredAxes = 0;
        double airBaseline = !relativeToAir ? 0.0 : world == null ? FrequencyMaterialRules.decibelsPerMeter(Blocks.AIR.defaultBlockState()) : world.effectiveAirLoss();

        int minY = world == null ? level.getMinBuildHeight() : world.minY();
        int maxY = world == null ? level.getMaxBuildHeight() : world.maxY();

        BlockPos.MutableBlockPos pos = null;

        while (t < 1.0) {
            if (stats != null) {
                stats.steps++;
            }

            int chunkX = x >> 4;
            int chunkZ = z >> 4;

            if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
                cachedChunkX = chunkX;
                cachedChunkZ = chunkZ;
                if (world == null) {
                    chunk = chunkBatch == null ? chunkSource.getChunkNow(chunkX, chunkZ) : chunkBatch.get(chunkSource, chunkX, chunkZ);
                    sections = chunk == null ? null : chunk.getSections();
                    chunkAvailable = chunk != null;
                } else {
                    snapshotChunk = world.chunk(chunkX, chunkZ);
                    chunkAvailable = snapshotChunk.loaded();
                }

                section = null;
                snapshotSection = null;
                materialCache = null;
                materialGrid = null;
                cachedSectionY = Integer.MIN_VALUE;
                emptySection = false;
                uniformSectionLoss = Double.NaN;
            }

            if (!chunkAvailable) {
                if (stats != null) {
                    stats.unloadedChunks++;
                }

                if (trace != null) {
                    trace.missing();
                }

                if (consumer != null) {
                    return Double.NaN;
                }

                double tx = sx > 0 ? boundaryTime(((long) chunkX + 1L) * 16.0 - startX, dx, inverseX) : sx < 0 ? boundaryTime((long) chunkX * 16.0 - startX, dx, inverseX) : Double.POSITIVE_INFINITY;
                double tz = sz > 0 ? boundaryTime(((long) chunkZ + 1L) * 16.0 - startZ, dz, inverseZ) : sz < 0 ? boundaryTime((long) chunkZ * 16.0 - startZ, dz, inverseZ) : Double.POSITIVE_INFINITY;

                double next = Math.min(1.0, Math.min(tx, tz));
                if (next >= 1.0) {
                    break;
                }

                if (next < t || !Double.isFinite(next)) {
                    break;
                }

                boolean crossX = tx <= tz;
                boolean crossZ = tz <= tx;

                if (crossX) {
                    x = sx > 0 ? (chunkX + 1) << 4 : (chunkX << 4) - 1;
                } else if (sx != 0) {
                    double px = Math.fma(dx, next, startX);
                    x = (int) Math.floor(Math.nextAfter(px, sx > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY));
                }

                if (crossZ) {
                    z = sz > 0 ? (chunkZ + 1) << 4 : (chunkZ << 4) - 1;
                } else if (sz != 0) {
                    double pz = Math.fma(dz, next, startZ);
                    z = (int) Math.floor(Math.nextAfter(pz, sz > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY));
                }

                if (sy != 0) {
                    double py = Math.fma(dy, next, startY);
                    y = (int) Math.floor(Math.nextAfter(py, sy > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY));
                }

                t = next;
                if (x >> 4 == chunkX && z >> 4 == chunkZ) {
                    break;
                }

                continue;
            }

            if (y >= minY && y < maxY && (y >> 4) != cachedSectionY) {
                if (stats != null) {
                    stats.sections++;
                }

                cachedSectionY = y >> 4;
                int index = cachedSectionY - (minY >> 4);
                if (world == null) {
                    section = sections != null && index >= 0 && index < sections.length ? sections[index] : null;
                    emptySection = skipAirSections && section != null && section.hasOnlyAir();
                    materialCache = !emptySection && section instanceof FrequencyMaterialSectionAccess access ? access.spectra$materialSummary() : null;

                    snapshotSection = null;
                    materialGrid = null;
                } else {
                    snapshotSection = snapshotChunk.section(index);
                    emptySection = skipAirSections && snapshotChunk.onlyAir(index);
                    materialCache = null;
                    materialGrid = null;

                    if (snapshotSection != null && (Double.isNaN(snapshotSection.sectionLoss()) || (consumer != null && snapshotSection.sectionMaterial() == null))) {
                        materialGrid = snapshotSection.grid();
                    }
                }

                uniformSectionLoss = emptySection ? airLoss : Double.NaN;
                if (trace != null) {
                    trace.observe(section, materialCache);
                }

                if (t == 0.0 && (((int) Math.floor(endX)) >> 4) == chunkX && (((int) Math.floor(endY)) >> 4) == cachedSectionY && (((int) Math.floor(endZ)) >> 4) == chunkZ) {
                    double sectionLoss = emptySection ? airLoss : world == null ? (materialCache == null ? Double.NaN : materialCache.sectionLoss()) : (snapshotSection == null ? Double.NaN : snapshotSection.sectionLoss());
                    if (Double.isFinite(sectionLoss)) {
                        ResourceLocation material = consumer == null ? null : emptySection ? FrequencyMaterialClassifier.AIR : world == null ? (materialCache == null ? null : materialCache.sectionMaterial()) : (snapshotSection == null ? null : snapshotSection.sectionMaterial());
                        if (consumer == null || material != null) {
                            if (stats != null) {
                                stats.sameSectionPaths++;
                            }

                            if (consumer != null) {
                                if (stats != null) {
                                    stats.materialSegments++;
                                }

                                consumer.acceptWithLoss(material, 0.0, 1.0, 0, sectionLoss);
                            }

                            if (!active) {
                                return 1.0;
                            }

                            double totalLoss = (sectionLoss - airBaseline) * distance;
                            return relativeToAir ? FrequencyMaterialRules.relativeAmplitudeFactor(totalLoss) : FrequencyMaterialRules.amplitudeFactor(totalLoss);
                        }
                    }
                }
            }

            if (y >= minY && y < maxY) {
                if (stats != null) {
                    stats.regionChecks++;
                }

                int shift = 4;
                double regionLoss = uniformSectionLoss;

                if (Double.isNaN(regionLoss)) {
                    if (world != null && snapshotSection != null) {
                        regionLoss = snapshotSection.sectionLoss();
                    } else if (materialCache != null) {
                        regionLoss = materialCache.sectionLoss();
                    }
                }

                ResourceLocation regionMaterial = null;
                if (consumer != null && regionLoss >= 0.0) {
                    if (emptySection) {
                        regionMaterial = FrequencyMaterialClassifier.AIR;
                    } else if (world != null && snapshotSection != null) {
                        regionMaterial = snapshotSection.sectionMaterial();
                    } else if (materialCache != null) {
                        regionMaterial = materialCache.sectionMaterial();
                    }
                }

                if ((materialGrid != null || materialCache != null) && (!(regionLoss >= 0.0) || (consumer != null && regionMaterial == null))) {
                    if (materialGrid != null) {
                        int id = materialGrid.region8Id(x & 15, y & 15, z & 15);

                        if (id >= 0) {
                            FrequencyMaterialSectionSnapshot.Cell cell = materialGrid.paletteCell(id);
                            shift = 3;
                            regionLoss = cell.lossDbPerMeter();
                            regionMaterial = cell.material();
                        } else {
                            shift = 2;
                            id = materialGrid.region4Id(x & 15, y & 15, z & 15);

                            if (id >= 0) {
                                FrequencyMaterialSectionSnapshot.Cell cell = materialGrid.paletteCell(id);
                                regionLoss = cell.lossDbPerMeter();
                                regionMaterial = cell.material();
                            } else {
                                regionLoss = Double.NaN;
                                regionMaterial = null;
                            }
                        }
                    } else {
                        FrequencyMaterialSectionCache.Region8 large = materialCache.region8(x & 15, y & 15, z & 15);
                        if (large != null) {
                            shift = 3;
                            regionLoss = large.loss();
                            regionMaterial = large.material();
                        } else {
                            shift = 2;
                            regionLoss = materialCache.regionLoss(x & 15, y & 15, z & 15);

                            if (consumer != null && regionLoss >= 0.0) {
                                regionMaterial = materialCache.regionMaterial(x & 15, y & 15, z & 15);
                            }
                        }
                    }
                }

                if ((materialGrid != null || materialCache != null) && (!(regionLoss >= 0.0) || (consumer != null && regionMaterial == null))) {
                    if (stats != null) {
                        stats.twoBlockChecks++;
                    }

                    if (materialGrid != null) {
                        int id = materialGrid.region2Id(x & 15, y & 15, z & 15);

                        if (id >= 0) {
                            FrequencyMaterialSectionSnapshot.Cell cell = materialGrid.paletteCell(id);

                            shift = 1;
                            regionLoss = cell.lossDbPerMeter();
                            regionMaterial = cell.material();
                        }
                    } else {
                        double smallerLoss = materialCache.twoBlockLoss(x & 15, y & 15, z & 15, stats);

                        if (smallerLoss >= 0.0) {
                            ResourceLocation smallerMaterial = consumer == null ? null : materialCache.twoBlockMaterial(x & 15, y & 15, z & 15);

                            if (consumer == null || smallerMaterial != null) {
                                shift = 1;
                                regionLoss = smallerLoss;
                                regionMaterial = smallerMaterial;
                            }
                        }
                    }
                }

                if (regionLoss >= 0.0 && (consumer == null || regionMaterial != null)) {
                    int regionX = x >> shift;
                    int regionY = y >> shift;
                    int regionZ = z >> shift;

                    double exitX = sx > 0 ? boundaryTime(((long) regionX + 1L) * (1 << shift) - startX, dx, inverseX) : sx < 0 ? boundaryTime((long) regionX * (1 << shift) - startX, dx, inverseX) : Double.POSITIVE_INFINITY;
                    double exitY = sy > 0 ? boundaryTime(((long) regionY + 1L) * (1 << shift) - startY, dy, inverseY) : sy < 0 ? boundaryTime((long) regionY * (1 << shift) - startY, dy, inverseY) : Double.POSITIVE_INFINITY;
                    double exitZ = sz > 0 ? boundaryTime(((long) regionZ + 1L) * (1 << shift) - startZ, dz, inverseZ) : sz < 0 ? boundaryTime((long) regionZ * (1 << shift) - startZ, dz, inverseZ) : Double.POSITIVE_INFINITY;
                    double exit = Math.min(1.0, Math.min(exitX, Math.min(exitY, exitZ)));

                    if (exit > t && Double.isFinite(exit)) {
                        if (stats != null) {
                            if (shift == 4) {
                                stats.uniformSections++;
                            } else if (shift == 3) {
                                stats.uniformEightRegions++;
                            } else if (shift == 2) {
                                stats.uniformRegions++;
                            } else {
                                stats.twoBlockSkips++;
                            }
                        }

                        loss = MaterialAttenuationMath.accumulateLoss(loss, regionLoss - airBaseline, distance * (exit - t));

                        if (consumer != null) {
                            if (stats != null) {
                                stats.materialSegments++;
                            }

                            consumer.acceptWithLoss(regionMaterial, t, exit, enteredAxes, regionLoss);
                        }

                        if (exit >= 1.0) {
                            break;
                        }

                        if (sx != 0) {
                            if (exitX <= exit) {
                                x = sx > 0 ? (regionX + 1) << shift : (regionX << shift) - 1;
                            } else {
                                double px = Math.fma(dx, exit, startX);
                                x = (int) Math.floor(Math.nextAfter(px, sx > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY));
                            }
                        }

                        if (sy != 0) {
                            if (exitY <= exit) {
                                y = sy > 0 ? (regionY + 1) << shift : (regionY << shift) - 1;
                            } else {
                                double py = Math.fma(dy, exit, startY);
                                y = (int) Math.floor(Math.nextAfter(py, sy > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY));
                            }
                        }

                        if (sz != 0) {
                            if (exitZ <= exit) {
                                z = sz > 0 ? (regionZ + 1) << shift : (regionZ << shift) - 1;
                            } else {
                                double pz = Math.fma(dz, exit, startZ);
                                z = (int) Math.floor(Math.nextAfter(pz, sz > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY));
                            }
                        }

                        enteredAxes = (exitX <= exit ? MaterialBoundaryScanner.X_AXIS : 0) | (exitY <= exit ? MaterialBoundaryScanner.Y_AXIS : 0) | (exitZ <= exit ? MaterialBoundaryScanner.Z_AXIS : 0);

                        t = exit;
                        continue;
                    }
                }
            }

            if (stats != null && y >= minY && y < maxY) {
                stats.regionFallbacks++;
            }

            double tx = sx > 0 ? boundaryTime(x + 1.0 - startX, dx, inverseX) : sx < 0 ? boundaryTime(x - startX, dx, inverseX) : Double.POSITIVE_INFINITY;
            double ty = sy > 0 ? boundaryTime(y + 1.0 - startY, dy, inverseY) : sy < 0 ? boundaryTime(y - startY, dy, inverseY) : Double.POSITIVE_INFINITY;
            double tz = sz > 0 ? boundaryTime(z + 1.0 - startZ, dz, inverseZ) : sz < 0 ? boundaryTime(z - startZ, dz, inverseZ) : Double.POSITIVE_INFINITY;

            double next = Math.min(1.0, Math.min(tx, Math.min(ty, tz)));
            if (next < t || !Double.isFinite(next)) {
                break;
            }

            if (y >= minY && y < maxY && next > t) {
                double length = distance * (next - t);
                if (emptySection) {
                    loss = MaterialAttenuationMath.accumulateLoss(loss, airLoss - airBaseline, length);

                    if (consumer != null) {
                        if (stats != null) {
                            stats.materialSegments++;
                        }
                        consumer.acceptWithLoss(FrequencyMaterialClassifier.AIR, t, next, enteredAxes, airLoss);
                    }
                } else {
                    if (stats != null) {
                        stats.blockReads++;
                    }

                    double lossPerMeter;
                    ResourceLocation material = null;

                    if (world != null) {
                        FrequencyMaterialSectionSnapshot.Cell cell = snapshotSection == null ? null : snapshotSection.cell(x & 15, y & 15, z & 15);

                        lossPerMeter = cell == null ? world.effectiveAirLoss() : cell.lossDbPerMeter();
                        if (consumer != null) {
                            material = cell == null ? FrequencyMaterialClassifier.AIR : cell.material();
                        }
                    } else {
                        BlockState state;

                        if (section != null) {
                            state = section.getBlockState(x & 15, y & 15, z & 15);
                        } else {
                            if (pos == null) {
                                pos = BLOCK_LOOKUP_POS.get();
                            }

                            pos.set(x, y, z);
                            state = chunk.getBlockState(pos);
                        }

                        lossPerMeter = FrequencyMaterialRules.decibelsPerMeter(state);

                        if (consumer != null) {
                            material = FrequencyMaterialClassifier.classify(state);
                        }
                    }

                    loss = MaterialAttenuationMath.accumulateLoss(loss, lossPerMeter - airBaseline, length);
                    if (consumer != null) {
                        if (stats != null) {
                            stats.materialSegments++;
                        }

                        consumer.acceptWithLoss(material, t, next, enteredAxes, lossPerMeter);
                    }
                }
            }

            if (consumer != null && next > t && (y < minY || y >= maxY)) {
                consumer.acceptWithLoss(FrequencyMaterialClassifier.AIR, t, next, enteredAxes, airLoss);
            }

            t = next;
            if (t >= 1.0) {
                break;
            }

            enteredAxes = 0;
            if (tx <= next) {
                x += sx;
                enteredAxes |= MaterialBoundaryScanner.X_AXIS;
            }

            if (ty <= next) {
                y += sy;
                enteredAxes |= MaterialBoundaryScanner.Y_AXIS;
            }

            if (tz <= next) {
                z += sz;
                enteredAxes |= MaterialBoundaryScanner.Z_AXIS;
            }
        }

        if (!active) {
            return 1.0;
        }

        return relativeToAir ? FrequencyMaterialRules.relativeAmplitudeFactor(loss) : FrequencyMaterialRules.amplitudeFactor(loss);
    }
}