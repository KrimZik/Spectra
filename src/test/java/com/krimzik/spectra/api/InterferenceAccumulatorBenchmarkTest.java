package com.krimzik.spectra.api;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterferenceAccumulatorBenchmarkTest {
    private static final long END_TICK = 4_000_000_001L;
    private static final double WINDOW = 0.25;

    @Test
    void measurePureInterferenceMath() {
        for (int count : new int[]{10, 100, 1_000}) {
            measure(count, false);
            measure(count, true);
        }
    }

    private static void measure(int count, boolean distinct) {
        FrequencySignal[] signals = new FrequencySignal[count];

        for (int i = 0; i < count; i++) {
            double hz = 915_000_000.0 + (distinct ? i : 0);

            signals[i] = new FrequencySignal(hz, 1.0, 0.0, 4.0);
        }

        for (int i = 0; i < 60; i++) {
            evaluate(signals);
        }

        int iterations = count == 1_000 ? 80 : 300;
        double checksum = 0.0;

        long start = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            checksum += evaluate(signals);
        }

        long elapsed = System.nanoTime() - start;
        double microsecondsPerQuery = elapsed / 1_000.0 / iterations;
        double result = checksum / iterations;

        System.out.printf(Locale.ROOT, "[FREQ-MATH] emitters=%d carriers=%d " + "avg=%.3f us/query result=%.8f%n", count, distinct ? count : 1, microsecondsPerQuery, result);

        assertTrue(Double.isFinite(result));

        if (!distinct) {
            assertEquals(0.5 * count * count, result, 1.0e-7);
        }
    }

    private static double evaluate(FrequencySignal[] signals) {
        FrequencyEngine.InterferenceAccumulator accumulator = new FrequencyEngine.InterferenceAccumulator(END_TICK, WINDOW);

        for (FrequencySignal signal : signals) {
            accumulator.add(signal, 1.0, 0.25);
        }

        return accumulator.meanSquareWindow(WINDOW);
    }

    @Test
    void repeatedPrimaryFrequencyPreservesWindowPhase() {
        double frequency = 7.25;
        double window = 0.037;
        double travelTime = 0.02;
        double initialPhase = Math.PI / 7.0;

        FrequencySignal signal = new FrequencySignal(frequency, 1.0, initialPhase, 343.0);
        FrequencyEngine.InterferenceAccumulator accumulator = new FrequencyEngine.InterferenceAccumulator(0L, window);

        accumulator.add(signal, 1.0, travelTime);
        accumulator.add(signal, 1.0, travelTime);

        double centerPhase = initialPhase - 2.0 * Math.PI * frequency * travelTime - Math.PI * frequency * window;

        double x = 2.0 * Math.PI * frequency * window;
        double sinc = Math.sin(x) / x;

        double expected = 2.0 * (1.0 - Math.cos(2.0 * centerPhase) * sinc);

        assertEquals(expected, accumulator.meanSquareWindow(window), 1.0e-12);
    }

    @Test
    void repeatedSecondaryFrequencyPreservesWindowPhase() {
        double window = 0.037;
        long tick = 17L;

        FrequencySignal primary = new FrequencySignal(7.25, 1.0, 0.2, 343.0);
        FrequencySignal secondaryA = new FrequencySignal(11.75, 1.0, 0.0, 343.0);
        FrequencySignal secondaryB = new FrequencySignal(11.75, 1.0, Math.PI / 2.0, 343.0);

        var repeated = new FrequencyEngine.InterferenceAccumulator(tick, window);
        repeated.add(primary, 0.6, 0.02);
        repeated.add(secondaryA, 1.0, 0.011);
        repeated.add(secondaryB, 1.0, 0.011);

        FrequencySignal combinedSecondary = new FrequencySignal(11.75, Math.sqrt(2.0), Math.PI / 4.0, 343.0);

        var combined = new FrequencyEngine.InterferenceAccumulator(tick, window);
        combined.add(primary, 0.6, 0.02);
        combined.add(combinedSecondary, Math.sqrt(2.0), 0.011);

        assertEquals(combined.meanSquareWindow(window), repeated.meanSquareWindow(window), 1.0e-11);
    }

    @Test
    void measureRepeatedSecondaryCarriers() {
        for (int count : new int[]{100, 1_000}) {
            FrequencySignal[] signals = new FrequencySignal[count];

            for (int i = 0; i < count; i++) {
                signals[i] = new FrequencySignal(915_000_000.0 + (i % 10), 1.0, 0.0, 4.0);
            }

            for (int i = 0; i < 100; i++) {
                evaluate(signals);
            }

            int iterations = 150;
            double checksum = 0.0;

            long start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                checksum += evaluate(signals);
            }

            long elapsed = System.nanoTime() - start;
            double microseconds = elapsed / 1_000.0 / iterations;
            double result = checksum / iterations;
            assertTrue(Double.isFinite(result));

            System.out.printf(Locale.ROOT, "[FREQ-MATH-GROUPED] emitters=%d carriers=10 " + "avg=%.3f us/query result=%.8f%n", count, microseconds, result);
        }
    }


    @Test
    void profileAccumulatorStages() {
        final int emitterCount = 1_000;
        final int iterations = 100;

        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (memory != null && !memory.isThreadAllocatedMemoryEnabled()) {
            memory.setThreadAllocatedMemoryEnabled(true);
        }

        long threadId = Thread.currentThread().threadId();
        for (int carrierCount : new int[]{1, 10, 100, 1_000}) {
            FrequencySignal[] signals = new FrequencySignal[emitterCount];

            for (int i = 0; i < emitterCount; i++) {
                signals[i] = new FrequencySignal(915_000_000.0 + i % carrierCount, 1.0, 0.0, 4.0);
            }

            for (int i = 0; i < 150; i++) {
                evaluate(signals);
            }

            long buildNanos = 0;
            long windowNanos = 0;
            double checksum = 0.0;

            long bytesBefore = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
            for (int iteration = 0; iteration < iterations; iteration++) {
                long start = System.nanoTime();

                var accumulator = new FrequencyEngine.InterferenceAccumulator(END_TICK, WINDOW);

                for (FrequencySignal signal : signals) {
                    accumulator.add(signal, 1.0, 0.25);
                }

                long afterBuild = System.nanoTime();

                checksum += accumulator.meanSquareWindow(WINDOW);

                long afterWindow = System.nanoTime();

                buildNanos += afterBuild - start;
                windowNanos += afterWindow - afterBuild;
            }

            long bytesAfter = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
            double buildUs = buildNanos / (iterations * 1000.0);
            double windowUs = windowNanos / (iterations * 1000.0);

            double allocatedBytes = bytesBefore < 0 || bytesAfter < 0 ? -1.0 : (bytesAfter - bytesBefore) / (double) iterations;

            assertTrue(Double.isFinite(checksum));

            System.out.printf(Locale.ROOT, "[FREQ] emitters=%d carriers=%d " + "build=%.3f us " + "window=%.3f us " + "allocated=%.0f bytes/query%n", emitterCount, carrierCount, buildUs, windowUs, allocatedBytes);
        }
    }
}