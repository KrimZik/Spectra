package com.krimzik.spectra.propagation.geometry;

import java.util.Objects;

public final class MaterialBoundaryScanner {
    public static final int X_AXIS = 1;
    public static final int Y_AXIS = 2;
    public static final int Z_AXIS = 4;

    private static final int MAX_STEPS = 1_048_576;

    @FunctionalInterface
    public interface MaterialSampler<M> {
        M materialAt(int x, int y, int z);
    }

    @FunctionalInterface
    public interface BoundaryConsumer<M> {
        void onBoundary(M from, M to, double fraction, int crossedAxes);
    }

    private MaterialBoundaryScanner() {}

    public static <M> int scan(double startX, double startY, double startZ, double endX, double endY, double endZ, MaterialSampler<M> sampler, BoundaryConsumer<M> consumer) {
        Objects.requireNonNull(sampler);
        Objects.requireNonNull(consumer);

        if (!Double.isFinite(startX) || !Double.isFinite(startY) || !Double.isFinite(startZ) || !Double.isFinite(endX) || !Double.isFinite(endY) || !Double.isFinite(endZ)) {
            throw new IllegalArgumentException("Invalid path coordinates");
        }

        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;

        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            throw new IllegalArgumentException("Invalid path displacement");
        }

        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return 0;
        }

        int x = (int) Math.floor(startX);
        int y = (int) Math.floor(startY);
        int z = (int) Math.floor(startZ);

        int sx = Double.compare(dx, 0.0);
        int sy = Double.compare(dy, 0.0);
        int sz = Double.compare(dz, 0.0);

        M previous = sampler.materialAt(x, y, z);

        if (previous == null) {
            return -1;
        }

        int boundaries = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            double tx = sx > 0 ? (x + 1.0 - startX) / dx : sx < 0 ? (x - startX) / dx : Double.POSITIVE_INFINITY;
            double ty = sy > 0 ? (y + 1.0 - startY) / dy : sy < 0 ? (y - startY) / dy : Double.POSITIVE_INFINITY;
            double tz = sz > 0 ? (z + 1.0 - startZ) / dz : sz < 0 ? (z - startZ) / dz : Double.POSITIVE_INFINITY;

            double next = Math.min(tx, Math.min(ty, tz));

            if (next >= 1.0) {
                return boundaries;
            }

            if (!Double.isFinite(next) || next < 0.0) {
                return -1;
            }

            int crossedAxes = 0;

            if (tx <= next) {
                x += sx;
                crossedAxes |= X_AXIS;
            }

            if (ty <= next) {
                y += sy;
                crossedAxes |= Y_AXIS;
            }

            if (tz <= next) {
                z += sz;
                crossedAxes |= Z_AXIS;
            }

            M current = sampler.materialAt(x, y, z);
            if (current == null) {
                return -1;
            }

            if (!Objects.equals(previous, current)) {
                consumer.onBoundary(previous, current, next, crossedAxes);
                boundaries++;
            }

            previous = current;
        }

        return -1;
    }
}