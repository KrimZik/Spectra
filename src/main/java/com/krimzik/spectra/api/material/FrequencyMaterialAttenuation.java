package com.krimzik.spectra.api.material;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class FrequencyMaterialAttenuation {
    private static final int MAX_BLOCKS = 256;
    private static volatile boolean enabled;

    private FrequencyMaterialAttenuation() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static double factor(ServerLevel level, Vec3 start, Vec3 end) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        if (!enabled) return 1.0;

        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double distance = Math.hypot(Math.hypot(dx, dy), dz);

        if (!(distance > 0.0) || !Double.isFinite(distance)) {
            return 1.0;
        }

        int x = (int) Math.floor(start.x);
        int y = (int) Math.floor(start.y);
        int z = (int) Math.floor(start.z);

        int sx = Double.compare(dx, 0.0);
        int sy = Double.compare(dy, 0.0);
        int sz = Double.compare(dz, 0.0);

        double stepX = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double stepY = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double stepZ = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double tx = sx > 0 ? (x + 1.0 - start.x) * stepX : sx < 0 ? (start.x - x) * stepX : Double.POSITIVE_INFINITY;
        double ty = sy > 0 ? (y + 1.0 - start.y) * stepY : sy < 0 ? (start.y - y) * stepY : Double.POSITIVE_INFINITY;
        double tz = sz > 0 ? (z + 1.0 - start.z) * stepZ : sz < 0 ? (start.z - z) * stepZ : Double.POSITIVE_INFINITY;

        double previous = 0.0;
        double loss = 0.0;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int visited = 0; visited < MAX_BLOCKS && previous < 1.0; visited++) {
            double next = Math.min(1.0, Math.min(tx, Math.min(ty, tz)));

            if (next < previous) return 1.0;

            if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
                return FrequencyMaterialRules.amplitudeFactor(loss);
            }

            if (!level.hasChunk(x >> 4, z >> 4)) {
                return 1.0;
            }

            pos.set(x, y, z);
            loss += FrequencyMaterialRules.decibelsPerMeter(level.getBlockState(pos)) * distance * (next - previous);
            previous = next;

            if (next >= 1.0) {
                return FrequencyMaterialRules.amplitudeFactor(loss);
            }

            if (tx <= next) {
                x += sx;
                tx += stepX;
            }

            if (ty <= next) {
                y += sy;
                ty += stepY;
            }

            if (tz <= next) {
                z += sz;
                tz += stepZ;
            }
        }

        return 1.0;
    }
}
