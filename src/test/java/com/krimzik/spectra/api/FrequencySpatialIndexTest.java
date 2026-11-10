package com.krimzik.spectra.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class FrequencySpatialIndexTest {

    @Test
    void identityBoundariesMovementAndRemoval() {
        record EqualEndpoint(int value) {}

        var index = new FrequencySpatialIndex<EqualEndpoint>();

        EqualEndpoint first = new EqualEndpoint(1);
        EqualEndpoint second = new EqualEndpoint(1);

        assertEquals(first, second);
        assertNotSame(first, second);

        index.update(first, 31.0, 0.0, 0.0);
        index.update(second, 32.0, 0.0, 0.0);

        assertEquals(2, index.size());

        List<EqualEndpoint> found = new ArrayList<>();
        index.forEachWithin(31.0, 0.0, 0.0, 1.0, found::add);

        assertEquals(2, found.size());
        assertTrue(found.stream().anyMatch(e -> e == first));
        assertTrue(found.stream().anyMatch(e -> e == second));

        found.clear();
        index.forEachWithin(31.0, 0.0, 0.0, 0.0, found::add);

        assertEquals(1, found.size());
        assertSame(first, found.getFirst());

        index.update(first, 96.0, 0.0, 0.0);

        found.clear();
        index.forEachWithin(96.0, 0.0, 0.0, 0.0, found::add);

        assertEquals(1, found.size());
        assertSame(first, found.getFirst());

        assertThrows(IllegalStateException.class, () -> index.forEachWithin(96.0, 0.0, 0.0, 1.0, endpoint -> index.remove(endpoint)));
        assertEquals(2, index.size());

        assertTrue(index.remove(second));
        assertFalse(index.remove(second));
        assertEquals(1, index.size());

        index.clear();
        assertEquals(0, index.size());

        System.out.println("identity=PASS " + "boundaries=PASS " + "movement=PASS " + "mutationProtection=PASS");
    }

    @Test
    void fiftyThousandSpatialSnapshotsMatchBruteForce() {
        final int count = 50_000;
        final int queries = 80;

        var index = new FrequencySpatialIndex<Object>();
        var random = new SplittableRandom(0x32B2026L);

        Object[] endpoints = new Object[count];
        double[] xs = new double[count];
        double[] ys = new double[count];
        double[] zs = new double[count];
        boolean[] active = new boolean[count];

        long start = System.nanoTime();

        for (int i = 0; i < count; i++) {
            endpoints[i] = new Object();

            xs[i] = random.nextDouble(-256.0, 256.0);
            ys[i] = random.nextDouble(-256.0, 256.0);
            zs[i] = random.nextDouble(-256.0, 256.0);
            active[i] = true;

            index.update(endpoints[i], xs[i], ys[i], zs[i]);
        }

        double registrationMs = (System.nanoTime() - start) / 1_000_000.0;
        for (int i = 0; i < count; i++) {
            if (i % 5 == 0) {
                xs[i] += 128.0;
                ys[i] -= 96.0;

                index.update(endpoints[i], xs[i], ys[i], zs[i]);
            }

            if (i % 7 == 0) {
                active[i] = false;
                assertTrue(index.remove(endpoints[i]));
            }
        }

        start = System.nanoTime();

        for (int q = 0; q < queries; q++) {
            double x = random.nextDouble(-256.0, 256.0);
            double y = random.nextDouble(-256.0, 256.0);
            double z = random.nextDouble(-256.0, 256.0);

            double radius = q % 9 == 0 ? 600.0 : q % 3 == 0 ? 64.0 : 48.0;
            double radiusSquared = radius * radius;
            int expected = 0;

            for (int i = 0; i < count; i++) {
                if (!active[i]) {
                    continue;
                }

                double dx = xs[i] - x;
                double dy = ys[i] - y;
                double dz = zs[i] - z;

                double distanceSquared = dx * dx + dy * dy + dz * dz;
                if (distanceSquared <= radiusSquared) {
                    expected++;
                }
            }

            final int[] actual = {0};
            index.forEachWithin(x, y, z, radius, endpoint -> actual[0]++);

            assertEquals(expected, actual[0], "Spatial mismatch at query " + q);
        }

        double verificationMs = (System.nanoTime() - start) / 1_000_000.0;
        int remaining = 0;

        for (boolean present : active) {
            if (present) {
                remaining++;
            }
        }

        assertEquals(remaining, index.size());

        for (int i = 0; i < count; i++) {
            if (active[i]) {
                assertTrue(index.remove(endpoints[i]));
            }
        }

        assertEquals(0, index.size());
        System.out.printf(Locale.ROOT, "entries=%d " + "queries=%d " + "registration=%.3f ms " + "verification=%.3f ms " + "bruteForceComparison=PASS " + "cleanup=PASS%n", count, queries, registrationMs, verificationMs);
    }
}
