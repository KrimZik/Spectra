package com.krimzik.spectra.api.material;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class FrequencyMaterialAttenuation {
    private static final int DEFAULT_LOADED_BLOCK_BUDGET = 1024;

    private static volatile boolean enabled;
    private static volatile int loadedBlockBudget = DEFAULT_LOADED_BLOCK_BUDGET;

    private FrequencyMaterialAttenuation() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static int loadedBlockBudget() {
        return loadedBlockBudget;
    }

    public static void setLoadedBlockBudget(int budget) {
        if (budget < 1 || budget > 65536) {
            throw new IllegalArgumentException("Invalid loaded block budget");
        }

        loadedBlockBudget = budget;
    }

    public static double factor(ServerLevel level, Vec3 start, Vec3 end) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        if (!enabled) {
            return 1.0;
        }

        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double distance = Math.hypot(Math.hypot(dx, dy), dz);

        if (!(distance > 0.0) || !Double.isFinite(distance)) {
            return 1.0;
        }

        int sx = Double.compare(dx, 0.0);
        int sy = Double.compare(dy, 0.0);
        int sz = Double.compare(dz, 0.0);

        int x = (int) Math.floor(start.x);
        int y = (int) Math.floor(start.y);
        int z = (int) Math.floor(start.z);

        int cachedChunkX = Integer.MIN_VALUE;
        int cachedChunkZ = Integer.MIN_VALUE;
        boolean chunkLoaded = false;

        double t = 0.0;
        double loss = 0.0;

        int remaining = loadedBlockBudget;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        while (t < 1.0 && remaining > 0) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;

            if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
                cachedChunkX = chunkX;
                cachedChunkZ = chunkZ;
                chunkLoaded = level.hasChunk(chunkX, chunkZ);
            }

            if (!chunkLoaded) {
                double tx = sx > 0 ? (((long) chunkX + 1L) * 16.0 - start.x) / dx : sx < 0 ? ((long) chunkX * 16.0 - start.x) / dx : Double.POSITIVE_INFINITY;
                double tz = sz > 0 ? (((long) chunkZ + 1L) * 16.0 - start.z) / dz : sz < 0 ? ((long) chunkZ * 16.0 - start.z) / dz : Double.POSITIVE_INFINITY;

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
                    double px = Math.fma(dx, next, start.x);
                    x = (int) Math.floor(Math.nextAfter(px, sx > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY));
                }

                if (crossZ) {
                    z = sz > 0 ? (chunkZ + 1) << 4 : (chunkZ << 4) - 1;
                } else if (sz != 0) {
                    double pz = Math.fma(dz, next, start.z);
                    z = (int) Math.floor(Math.nextAfter(pz, sz > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY));
                }

                if (sy != 0) {
                    double py = Math.fma(dy, next, start.y);
                    y = (int) Math.floor(Math.nextAfter(py, sy > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY));
                }

                t = next;
                remaining--;

                if (x >> 4 == chunkX && z >> 4 == chunkZ) {
                    break;
                }

                continue;
            }

            double tx = sx > 0 ? (x + 1.0 - start.x) / dx : sx < 0 ? (x - start.x) / dx : Double.POSITIVE_INFINITY;
            double ty = sy > 0 ? (y + 1.0 - start.y) / dy : sy < 0 ? (y - start.y) / dy : Double.POSITIVE_INFINITY;
            double tz = sz > 0 ? (z + 1.0 - start.z) / dz : sz < 0 ? (z - start.z) / dz : Double.POSITIVE_INFINITY;
            double next = Math.min(1.0, Math.min(tx, Math.min(ty, tz)));

            if (next < t || !Double.isFinite(next)) {
                break;
            }

            if (y >= minY && y < maxY && next > t) {
                pos.set(x, y, z);

                double lossPerMeter = FrequencyMaterialRules.decibelsPerMeter(level.getBlockState(pos));
                loss = Math.fma(lossPerMeter, distance * (next - t), loss);
            }

            remaining--;
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

        return FrequencyMaterialRules.amplitudeFactor(loss);
    }
}
