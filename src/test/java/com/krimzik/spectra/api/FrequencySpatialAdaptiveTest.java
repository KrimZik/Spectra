package com.krimzik.spectra.api;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrequencySpatialAdaptiveTest {
    private record Endpoint(int id) {}

    @Test
    void adaptiveQueriesMatchExactDistanceChecks() {
        for (int count : new int[]{1_000, 10_000, 50_000}) {
            FrequencySpatialIndex<Endpoint> index = new FrequencySpatialIndex<>();
            IdentityHashMap<Endpoint, Vec3> positions = new IdentityHashMap<>();

            Endpoint[] endpoints = new Endpoint[count];

            for (int i = 0; i < count; i++) {
                boolean nearby = i % 10 == 0;
                int local = i / 10;

                double x = nearby ? (local % 32) - 16.0 : 2048.0 + i % 31;
                double y = nearby ? ((local / 32) % 8) - 4.0 : 0.0;
                double z = nearby ? ((local / 256) % 32) - 16.0 : 2048.0 + i % 29;

                Endpoint endpoint = new Endpoint(i);
                Vec3 position = new Vec3(x, y, z);

                endpoints[i] = endpoint;
                positions.put(endpoint, position);
                index.update(endpoint, x, y, z);
            }

            verify(index, positions);

            for (int i = 0; i < count / 10; i++) {
                Endpoint endpoint = endpoints[i];
                Vec3 previous = positions.get(endpoint);

                Vec3 moved = previous.add(i % 2 == 0 ? 256.0 : -256.0, 0.0, 0.0);

                positions.put(endpoint, moved);
                index.update(endpoint, moved.x, moved.y, moved.z);
            }

            verify(index, positions);

            for (int i = 0; i < count / 10; i++) {
                Endpoint endpoint = endpoints[i];
                index.remove(endpoint);
                positions.remove(endpoint);
            }

            verify(index, positions);
            assertEquals(positions.size(), index.size());
        }
    }

    @Test
    void handlesCellBoundariesAndZeroRadius() {
        FrequencySpatialIndex<Endpoint> index = new FrequencySpatialIndex<>();
        IdentityHashMap<Endpoint, Vec3> positions = new IdentityHashMap<>();

        double[][] coordinates = {
                {0.0, 0.0, 0.0},
                {32.0, 0.0, 0.0},
                {-32.0, 0.0, 0.0},
                {32.000001, 0.0, 0.0},
                {-32.000001, 0.0, 0.0},
                {256.0, 0.0, 0.0},
                {-256.0, 0.0, 0.0},
                {256.000001, 0.0, 0.0}
        };

        for (int i = 0; i < coordinates.length; i++) {
            Endpoint endpoint = new Endpoint(i);
            double[] p = coordinates[i];

            index.update(endpoint, p[0], p[1], p[2]);
            positions.put(endpoint, new Vec3(p[0], p[1], p[2]));
        }

        verify(index, positions);
    }

    private static void verify(FrequencySpatialIndex<Endpoint> index, IdentityHashMap<Endpoint, Vec3> positions) {
        for (double radius : new double[]{0.0, 32.0, 64.0, 128.0, 256.0, 512.0}) {
            Set<Endpoint> expected = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Endpoint> actual = Collections.newSetFromMap(new IdentityHashMap<>());

            double radiusSquared = radius * radius;

            positions.forEach((endpoint, position) -> {
                double distanceSquared = Math.fma(position.x, position.x, Math.fma(position.y, position.y, position.z * position.z));
                if (distanceSquared <= radiusSquared) {
                    expected.add(endpoint);
                }
            });

            index.forEachWithin(0.0, 0.0, 0.0, radius, actual::add);
            assertEquals(expected, actual);
        }
    }

    @Test
    void adaptiveSelectionHandlesModerateAndDensePopulations() {
        for (int nearby : new int[]{1_000, 5_000}) {
            FrequencySpatialIndex<Object> index = new FrequencySpatialIndex<>();

            for (int i = 0; i < 10_000; i++) {
                boolean close = i < nearby;

                double x = close ? i % 23 - 11.0 : 512.0 + i % 29;
                double y = close ? i / 529 - 11.0 : 0.0;
                double z = close ? (i / 23) % 23 - 11.0 : 512.0 + i % 31;

                index.update(new Object(), x, y, z);
            }

            assertEquals(nearby == 1_000, index.favorsIndex(0.0, 0.0, 0.0, 64.0, 10_000));
        }
    }
}
