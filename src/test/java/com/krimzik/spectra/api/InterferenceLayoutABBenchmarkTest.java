package com.krimzik.spectra.api;

import com.krimzik.spectra.math.FrequencyMath;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterferenceLayoutABBenchmarkTest {
    private static final long END_TICK = 4_000_000_001L;
    private static final double WINDOW = 0.25;
    private static volatile double sink;

    @Test
    void packedVersusCurrentSameJvm() throws ReflectiveOperationException {
        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;

        if (memory != null && !memory.isThreadAllocatedMemoryEnabled()) {
            memory.setThreadAllocatedMemoryEnabled(true);
        }

        long threadId = Thread.currentThread().threadId();

        for (int carriers : new int[]{1, 10, 100, 1_000}) {
            var accumulator = new FrequencyEngine.InterferenceAccumulator(END_TICK, WINDOW);

            for (int i = 0; i < carriers; i++) {
                accumulator.add(new FrequencySignal(915_000_000.0 + i, 1.0, 0.0, 4.0), 1.0, 0.25);
            }

            Snapshot state = snapshot(accumulator);

            DoubleSupplier packed = () -> packedReference(state, WINDOW);
            DoubleSupplier current = () -> accumulator.meanSquareWindow(WINDOW);

            double expected = packed.getAsDouble();
            double actual = current.getAsDouble();

            assertEquals(expected, actual, Math.max(1.0e-11, Math.abs(expected) * 1.0e-10));

            for (int i = 0; i < 80; i++) {
                sink = packed.getAsDouble() + current.getAsDouble();
            }

            int repetitions = carriers == 1_000 ? 30 : 200;

            double[] packedUs = new double[5];
            double[] currentUs = new double[5];
            double packedBytes = 0.0;
            double currentBytes = 0.0;

            for (int sample = 0; sample < 5; sample++) {
                Measurement first;
                Measurement second;

                if ((sample & 1) == 0) {
                    first = measure(packed, repetitions, memory, threadId);
                    second = measure(current, repetitions, memory, threadId);

                    packedUs[sample] = first.us();
                    currentUs[sample] = second.us();
                    packedBytes += first.bytes();
                    currentBytes += second.bytes();
                } else {
                    first = measure(current, repetitions, memory, threadId);
                    second = measure(packed, repetitions, memory, threadId);

                    currentUs[sample] = first.us();
                    packedUs[sample] = second.us();
                    currentBytes += first.bytes();
                    packedBytes += second.bytes();
                }
            }

            Arrays.sort(packedUs);
            Arrays.sort(currentUs);

            System.out.printf(Locale.ROOT, "[FREQ-AB] carriers=%d packed=%.3f us " + "current=%.3f us " + "packedBytes=%.0f currentBytes=%.0f " + "result=%.10f%n", carriers, packedUs[2], currentUs[2], packedBytes / 5.0, currentBytes / 5.0, actual);

            assertTrue(Double.isFinite(sink));
        }
    }

    private record Measurement(double us, double bytes) {}

    private static Measurement measure(DoubleSupplier calculation, int repetitions, com.sun.management.ThreadMXBean memory, long threadId) {
        long bytesBefore = memory == null ? -1 : memory.getThreadAllocatedBytes(threadId);
        long start = System.nanoTime();

        double checksum = 0.0;
        for (int i = 0; i < repetitions; i++) {
            checksum += calculation.getAsDouble();
        }

        long duration = System.nanoTime() - start;
        long bytesAfter = memory == null ? -1 : memory.getThreadAllocatedBytes(threadId);

        sink = checksum;

        return new Measurement(duration / (repetitions * 1_000.0), bytesBefore < 0 || bytesAfter < 0 ? -1.0 : (bytesAfter - bytesBefore) / (double) repetitions);
    }

    private record Snapshot(double primaryHz, double primaryReal, double primaryImag, int secondaryCount, double[] secondary) {}

    private static Snapshot snapshot(FrequencyEngine.InterferenceAccumulator a) throws ReflectiveOperationException {
        Class<?> type = a.getClass();
        return new Snapshot(readDouble(type, a, "primaryFrequency"), readDouble(type, a, "primaryReal"), readDouble(type, a, "primaryImaginary"), readInt(type, a, "additionalCount"), (double[]) readObject(type, a, "additionalCarriers"));
    }

    private static Object readObject(Class<?> type, Object instance, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static double readDouble(Class<?> type, Object instance, String name) throws ReflectiveOperationException {
        return (Double) readObject(type, instance, name);
    }

    private static int readInt(Class<?> type, Object instance, String name) throws ReflectiveOperationException {
        return (Integer) readObject(type, instance, name);
    }

    private static double packedReference(Snapshot state, double windowSeconds) {
        double total = self(state.primaryHz, state.primaryReal, state.primaryImag, windowSeconds);

        if (state.secondaryCount != 0) {
            double[] data = new double[(state.secondaryCount + 1) * 5];
            double halfWindow = windowSeconds * 0.5;
            pack(data, 0, state.primaryHz, state.primaryReal, state.primaryImag, halfWindow);

            int end = 5;

            for (int src = 0; src < state.secondaryCount * 4; src += 4) {
                double hz = state.secondary[src];
                double real = state.secondary[src + 1];
                double imag = state.secondary[src + 2];

                pack(data, end, hz, real, imag, halfWindow);
                total += self(hz, real, imag, windowSeconds);
                end += 5;
            }

            double piWindow = Math.PI * windowSeconds;

            for (int i = 0; i < end; i += 5) {
                double hz = data[i];
                double real = data[i + 1];
                double imag = data[i + 2];
                double sin = data[i + 3];
                double cos = data[i + 4];

                for (int j = i + 5; j < end; j += 5) {
                    total += cross(hz, real, imag, sin, cos, data, j, windowSeconds, piWindow);
                }
            }
        }

        if (!Double.isFinite(total)) {
            throw new IllegalArgumentException("Overflow");
        }

        return Math.max(0.0, total);
    }

    private static void pack(double[] data, int offset, double hz, double real, double imag, double halfWindow) {
        data[offset] = hz;
        data[offset + 1] = real;
        data[offset + 2] = imag;

        if (!Double.isFinite(hz * halfWindow)) {
            data[offset + 3] = Double.NaN;
            data[offset + 4] = Double.NaN;
            return;
        }

        double phase = FrequencyMath.phaseAdvanceRadians(hz, halfWindow);

        data[offset + 3] = Math.sin(phase);
        data[offset + 4] = Math.cos(phase);
    }

    private static double cross(double hzA, double realA, double imagA, double sinA, double cosA, double[] data, int b, double window, double piWindow) {
        double hzB = data[b];
        double realB = data[b + 1];
        double imagB = data[b + 2];
        double sinB = data[b + 3];
        double cosB = data[b + 4];

        if (!Double.isFinite(sinA) || !Double.isFinite(sinB)) {
            double difference = Math.abs(hzA - hzB);
            double sum = hzA + hzB;

            double differenceComponent = Math.fma(realA, realB, imagA * imagB);
            double sumComponent = Math.fma(realA, realB, -imagA * imagB);

            return differenceComponent * sinc(difference, window) - sumComponent * sinc(sum, window);
        }

        double diff = hzA - hzB;
        double sum = hzA + hzB;

        double xDiff = Double.isFinite(piWindow) && piWindow > 0.0 ? diff * piWindow : Math.PI * diff * window;
        double xSum = Double.isFinite(piWindow) && piWindow > 0.0 ? sum * piWindow : Math.PI * sum * window;

        double cosAsinB = cosA * sinB;
        double diffSinc = Math.abs(xDiff) <= Math.PI * 0.25 ? sinc(diff, window) : !Double.isFinite(xDiff) ? 0.0 : Math.fma(sinA, cosB, -cosAsinB) / xDiff;
        double sumSinc = Math.abs(xSum) <= Math.PI * 0.25 ? sinc(sum, window) : !Double.isFinite(xSum) ? 0.0 : Math.fma(sinA, cosB, cosAsinB) / xSum;

        double diffComponent = Math.fma(realA, realB, imagA * imagB);
        double sumComponent = Math.fma(realA, realB, -imagA * imagB);

        return Math.fma(diffComponent, diffSinc, -sumComponent * sumSinc);
    }

    private static double self(double hz, double real, double imag, double window) {
        double real2 = real * real;
        double imag2 = imag * imag;
        return 0.5 * (real2 + imag2 - (real2 - imag2) * sinc(2.0 * hz, window));
    }

    private static double sinc(double hz, double window) {
        if (hz == 0.0) return 1.0;

        double x = Math.PI * hz * window;
        if (!Double.isFinite(x)) return 0.0;

        if (Math.abs(x) <= Math.PI * 0.25) {
            double x2 = x * x;

            return 1.0 + x2 * (-1.0 / 6.0 + x2 * (1.0 / 120.0 + x2 * (-1.0 / 5040.0 + x2 * (1.0 / 362880.0 + x2 * (-1.0 / 39916800.0 + x2 * (1.0 / 6227020800.0 - x2 / 1307674368000.0))))));
        }

        return Math.sin(FrequencyMath.phaseAdvanceRadians(hz, window * 0.5)) / x;
    }
}
