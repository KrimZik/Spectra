package com.krimzik.spectra.api.material;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class FrequencyMaterialAttenuation {
    private static volatile boolean enabled;

    private FrequencyMaterialAttenuation() {}

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

    private static double factor(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ, boolean relativeToAir) {
        Objects.requireNonNull(level);

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

        int x = (int) Math.floor(startX);
        int y = (int) Math.floor(startY);
        int z = (int) Math.floor(startZ);

        int cachedChunkX = Integer.MIN_VALUE;
        int cachedChunkZ = Integer.MIN_VALUE;

        ServerChunkCache chunkSource = level.getChunkSource();
        LevelChunk chunk = null;
        LevelChunkSection[] sections = null;

        int cachedSectionY = Integer.MIN_VALUE;
        boolean emptySection = false;
        boolean skipAirSections = FrequencyMaterialRules.canSkipAirSections();

        double t = 0.0;
        double loss = 0.0;
        double airBaseline = relativeToAir ? FrequencyMaterialRules.decibelsPerMeter(Blocks.AIR.defaultBlockState()) : 0.0;

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        while (t < 1.0) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;

            if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
                cachedChunkX = chunkX;
                cachedChunkZ = chunkZ;
                chunk = chunkSource.getChunkNow(chunkX, chunkZ);
                sections = chunk == null || !skipAirSections ? null : chunk.getSections();
                cachedSectionY = Integer.MIN_VALUE;
            }

            if (chunk == null) {
                double tx = sx > 0 ? (((long) chunkX + 1L) * 16.0 - startX) / dx : sx < 0 ? ((long) chunkX * 16.0 - startX) / dx : Double.POSITIVE_INFINITY;
                double tz = sz > 0 ? (((long) chunkZ + 1L) * 16.0 - startZ) / dz : sz < 0 ? ((long) chunkZ * 16.0 - startZ) / dz : Double.POSITIVE_INFINITY;

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

            double tx = sx > 0 ? (x + 1.0 - startX) / dx : sx < 0 ? (x - startX) / dx : Double.POSITIVE_INFINITY;
            double ty = sy > 0 ? (y + 1.0 - startY) / dy : sy < 0 ? (y - startY) / dy : Double.POSITIVE_INFINITY;
            double tz = sz > 0 ? (z + 1.0 - startZ) / dz : sz < 0 ? (z - startZ) / dz : Double.POSITIVE_INFINITY;
            double next = Math.min(1.0, Math.min(tx, Math.min(ty, tz)));

            if (next < t || !Double.isFinite(next)) {
                break;
            }

            if (y >= minY && y < maxY && next > t) {
                int sectionY = y >> 4;

                if (sectionY != cachedSectionY) {
                    cachedSectionY = sectionY;
                    int index = sectionY - (minY >> 4);

                    emptySection = sections != null && index >= 0 && index < sections.length && sections[index] != null && sections[index].hasOnlyAir();
                }

                double length = distance * (next - t);
                if (emptySection) {
                    loss = Math.fma(FrequencyMaterialRules.airLossDbPerMeter() - airBaseline, length, loss);
                } else {
                    pos.set(x, y, z);
                    double lossPerMeter = FrequencyMaterialRules.decibelsPerMeter(chunk.getBlockState(pos));
                    loss = Math.fma(lossPerMeter - airBaseline, length, loss);
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
