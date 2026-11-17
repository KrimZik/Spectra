package com.krimzik.spectra.api.material;

import com.krimzik.spectra.math.MaterialAttenuationMath;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class FrequencyMaterialAttenuation {
    private static volatile boolean enabled;

    private static final ThreadLocal<TraversalStats> ACTIVE_STATS = new ThreadLocal<>();

    public static final class TraversalStats {
        long paths;
        long unloadedChunks;
        long sections;
        long uniformSections;
        long uniformRegions;
        long blockReads;

        @Override
        public String toString() {
            return "paths=" + paths + " unloadedChunks=" + unloadedChunks + " sections=" + sections + " uniformSections=" + uniformSections + " uniformRegions=" + uniformRegions + " blockReads=" + blockReads;
        }
    }

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

    private FrequencyMaterialAttenuation() {}

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

    static double factorWithTrace(ServerLevel level, Vec3 start, Vec3 end, FrequencyMaterialPathCache.Trace trace) {
        return factor(level, start.x, start.y, start.z, end.x, end.y, end.z, false, trace);
    }

    private static double factor(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ, boolean relativeToAir) {
        return factor(level, startX, startY, startZ, endX, endY, endZ, relativeToAir, null);
    }

    private static double factor(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ, boolean relativeToAir, FrequencyMaterialPathCache.Trace trace) {
        Objects.requireNonNull(level);

        TraversalStats stats = ACTIVE_STATS.get();
        if (stats != null) {
            stats.paths++;
        }

        if (!enabled) {
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

        ServerChunkCache chunkSource = level.getChunkSource();
        LevelChunk chunk = null;
        LevelChunkSection[] sections = null;
        LevelChunkSection section = null;
        FrequencyMaterialSectionCache materialCache = null;

        int cachedSectionY = Integer.MIN_VALUE;
        boolean emptySection = false;
        double uniformSectionLoss = Double.NaN;
        boolean skipAirSections = FrequencyMaterialRules.canSkipAirSections();

        double t = 0.0;
        double loss = 0.0;
        double airBaseline = relativeToAir ? FrequencyMaterialRules.decibelsPerMeter(Blocks.AIR.defaultBlockState()) : 0.0;

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        BlockPos.MutableBlockPos pos = null;

        while (t < 1.0) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;

            if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
                cachedChunkX = chunkX;
                cachedChunkZ = chunkZ;
                chunk = chunkSource.getChunkNow(chunkX, chunkZ);
                sections = chunk == null ? null : chunk.getSections();
                section = null;
                materialCache = null;
                cachedSectionY = Integer.MIN_VALUE;
                emptySection = false;
                uniformSectionLoss = Double.NaN;
            }

            if (chunk == null) {
                if (stats != null) {
                    stats.unloadedChunks++;
                }

                if (trace != null) {
                    trace.missing();
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

                section = sections != null && index >= 0 && index < sections.length ? sections[index] : null;
                emptySection = skipAirSections && section != null && section.hasOnlyAir();
                uniformSectionLoss = emptySection ? FrequencyMaterialRules.airLossDbPerMeter() : Double.NaN;
                materialCache = !emptySection && section instanceof FrequencyMaterialSectionAccess access ? access.spectra$materialSummary() : null;
                if (trace != null) {
                    trace.observe(section, materialCache);
                }
            }

            if (y >= minY && y < maxY) {
                int shift = 4;
                double regionLoss = uniformSectionLoss;

                if (Double.isNaN(regionLoss) && materialCache != null) {
                    regionLoss = materialCache.sectionLoss();

                    if (Double.isNaN(regionLoss)) {
                        shift = 2;
                        regionLoss = materialCache.regionLoss(x & 15, y & 15, z & 15);
                    }
                }

                if (regionLoss >= 0.0) {
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
                            } else {
                                stats.uniformRegions++;
                            }
                        }

                        loss = MaterialAttenuationMath.accumulateLoss(loss, regionLoss - airBaseline, distance * (exit - t));

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

                        t = exit;
                        continue;
                    }
                }
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
                    loss = MaterialAttenuationMath.accumulateLoss(loss, FrequencyMaterialRules.airLossDbPerMeter() - airBaseline, length);
                } else {
                    BlockState state;

                    if (stats != null) {
                        stats.blockReads++;
                    }

                    if (section != null) {
                        state = section.getBlockState(x & 15, y & 15, z & 15);
                    } else {
                        if (pos == null) {
                            pos = new BlockPos.MutableBlockPos();
                        }

                        pos.set(x, y, z);
                        state = chunk.getBlockState(pos);
                    }

                    double lossPerMeter = FrequencyMaterialRules.decibelsPerMeter(state);
                    loss = MaterialAttenuationMath.accumulateLoss(loss, lossPerMeter - airBaseline, length);
                }
            }

            t = next;
            if (t >= 1.0) {
                break;
            }

            if (tx <= next) {
                x += sx;
            }

            if (ty <= next) {
                y += sy;
            }

            if (tz <= next) {
                z += sz;
            }
        }

        return relativeToAir ? FrequencyMaterialRules.relativeAmplitudeFactor(loss) : FrequencyMaterialRules.amplitudeFactor(loss);
    }
}