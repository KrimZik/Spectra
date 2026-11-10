package com.krimzik.spectra.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Consumer;

final class FrequencySpatialIndex<T> {
    private static final double CELL_METERS = 32.0;
    private static final long MAX_ENUMERATED_CELLS = 512L;

    private record Cell(int x, int y, int z) {}

    private static final class Entry<T> {
        final T endpoint;

        double x;
        double y;
        double z;

        Cell cell;
        int slot;

        Entry(T endpoint, double x, double y, double z) {
            this.endpoint = endpoint;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final IdentityHashMap<T, Entry<T>> entries = new IdentityHashMap<>();
    private final HashMap<Cell, ArrayList<Entry<T>>> cells = new HashMap<>();
    private int activeQueries;

    int size() {
        return entries.size();
    }

    void update(T endpoint, double worldX, double worldY, double worldZ) {
        Objects.requireNonNull(endpoint);
        checkWritable();

        Cell targetCell = new Cell(cellCoordinate(worldX), cellCoordinate(worldY), cellCoordinate(worldZ));
        Entry<T> entry = entries.get(endpoint);

        if (entry == null) {
            entry = new Entry<>(endpoint, worldX, worldY, worldZ);

            entries.put(endpoint, entry);
            attach(entry, targetCell);
            return;
        }

        if (!entry.cell.equals(targetCell)) {
            detach(entry);
            attach(entry, targetCell);
        }

        entry.x = worldX;
        entry.y = worldY;
        entry.z = worldZ;
    }

    boolean remove(T endpoint) {
        Objects.requireNonNull(endpoint);
        checkWritable();

        Entry<T> entry = entries.remove(endpoint);

        if (entry == null) {
            return false;
        }

        detach(entry);
        return true;
    }

    void clear() {
        checkWritable();
        entries.clear();
        cells.clear();
    }

    void forEachWithin(double x, double y, double z, double radiusMeters, Consumer<? super T> consumer) {
        Objects.requireNonNull(consumer);

        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(radiusMeters) || radiusMeters < 0.0) {
            throw new IllegalArgumentException("Invalid world-space query");
        }

        activeQueries++;

        try {
            double minX = Math.floor((x - radiusMeters) / CELL_METERS);
            double minY = Math.floor((y - radiusMeters) / CELL_METERS);
            double minZ = Math.floor((z - radiusMeters) / CELL_METERS);

            double maxX = Math.floor((x + radiusMeters) / CELL_METERS);
            double maxY = Math.floor((y + radiusMeters) / CELL_METERS);
            double maxZ = Math.floor((z + radiusMeters) / CELL_METERS);

            if (!fitsCellCoordinate(minX) || !fitsCellCoordinate(minY) || !fitsCellCoordinate(minZ) || !fitsCellCoordinate(maxX) || !fitsCellCoordinate(maxY) || !fitsCellCoordinate(maxZ)) {
                scanAll(x, y, z, radiusMeters, consumer);
                return;
            }

            int x0 = (int) minX;
            int y0 = (int) minY;
            int z0 = (int) minZ;

            int x1 = (int) maxX;
            int y1 = (int) maxY;
            int z1 = (int) maxZ;

            long nx = (long) x1 - x0 + 1;
            long ny = (long) y1 - y0 + 1;
            long nz = (long) z1 - z0 + 1;

            if (nx > MAX_ENUMERATED_CELLS || ny > MAX_ENUMERATED_CELLS || nz > MAX_ENUMERATED_CELLS || nx * ny * nz > MAX_ENUMERATED_CELLS) {
                scanAll(x, y, z, radiusMeters, consumer);
                return;
            }

            for (long cx = x0; cx <= x1; cx++) {
                for (long cy = y0; cy <= y1; cy++) {
                    for (long cz = z0; cz <= z1; cz++) {
                        ArrayList<Entry<T>> bucket = cells.get(new Cell((int) cx, (int) cy, (int) cz));

                        if (bucket == null) {
                            continue;
                        }

                        for (int i = 0; i < bucket.size(); i++) {
                            Entry<T> entry = bucket.get(i);

                            if (withinRadius(entry, x, y, z, radiusMeters)) {
                                consumer.accept(entry.endpoint);
                            }
                        }
                    }
                }
            }
        } finally {
            activeQueries--;
        }
    }

    private void scanAll(double x, double y, double z, double radiusMeters, Consumer<? super T> consumer) {
        for (Entry<T> entry : entries.values()) {
            if (withinRadius(entry, x, y, z, radiusMeters)) {
                consumer.accept(entry.endpoint);
            }
        }
    }

    private static boolean withinRadius(Entry<?> entry, double x, double y, double z, double radius) {
        double dx = entry.x - x;
        double dy = entry.y - y;
        double dz = entry.z - z;

        if (radius > 1.0e154) {
            return Math.hypot(Math.hypot(dx, dy), dz) <= radius;
        }

        return Math.fma(dx, dx, Math.fma(dy, dy, dz * dz)) <= radius * radius;
    }

    private void attach(Entry<T> entry, Cell cell) {
        ArrayList<Entry<T>> bucket = cells.computeIfAbsent(cell, ignored -> new ArrayList<>());
        entry.cell = cell;
        entry.slot = bucket.size();

        bucket.add(entry);
    }

    private void detach(Entry<T> entry) {
        ArrayList<Entry<T>> bucket = cells.get(entry.cell);
        int lastIndex = bucket.size() - 1;

        if (entry.slot != lastIndex) {
            Entry<T> moved = bucket.get(lastIndex);
            bucket.set(entry.slot, moved);
            moved.slot = entry.slot;
        }

        bucket.remove(lastIndex);
        if (bucket.isEmpty()) {
            cells.remove(entry.cell);
        }
    }

    private static int cellCoordinate(double meters) {
        double coordinate = Math.floor(meters / CELL_METERS);

        if (!fitsCellCoordinate(coordinate)) {
            throw new IllegalArgumentException("World position cannot be indexed");
        }

        return (int) coordinate;
    }

    private static boolean fitsCellCoordinate(double value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    private void checkWritable() {
        if (activeQueries != 0) {
            throw new IllegalStateException("Cannot modify spatial index during a query");
        }
    }
}
