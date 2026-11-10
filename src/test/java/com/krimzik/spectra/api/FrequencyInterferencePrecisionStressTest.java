package com.krimzik.spectra.api;

import com.krimzik.spectra.math.FrequencyMath;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FrequencyInterferencePrecisionStressTest {
    private static final double[] WINDOWS = {
            1.0e-12, 1.0e-9, 1.0e-6,
            0.013, 0.25, 1.0, 2.75
    };

    @Test
    void randomizedFiniteWindowMatchesDirectAnalyticalSum() {
        Random random = new Random(0x5EED24L);

        for (int trial = 0; trial < 600; trial++) {
            double window = WINDOWS[trial % WINDOWS.length];

            long endTick = switch (trial % 3) {
                case 0 -> 0L;
                case 1 -> 17L;
                default -> 4_000_000_001L;
            };

            int count = 2 + random.nextInt(11);

            double[] frequencies = new double[count];
            double[] real = new double[count];
            double[] imaginary = new double[count];

            var accumulator = new FrequencyEngine.InterferenceAccumulator(endTick, window);

            for (int i = 0; i < count; i++) {
                double frequency = switch (trial % 6) {
                    case 0 -> 0.75 + 0.25 * i;
                    case 1 -> 915_000_000.0 + 0.125 * i;
                    case 2 -> 2_400_000_000.0 + i;
                    case 3 -> i % 2 == 0 ? 1.25 : 2_400_000_000.125;
                    case 4 -> 915_000_000.0 + (i % 3);
                    default -> 7.25;
                };

                double phaseRadians = random.nextDouble() * (2.0 * Math.PI);
                double receivedAmplitude = 0.1 + 1.9 * random.nextDouble();
                double travelTimeSeconds = 0.1 * random.nextDouble();

                FrequencySignal signal = new FrequencySignal(frequency, 1.0, phaseRadians, 343.0);
                accumulator.add(signal, receivedAmplitude, travelTimeSeconds);

                frequencies[i] = frequency;
                double centerPhase = FrequencyMath.phaseAtTick(frequency, endTick, phaseRadians, travelTimeSeconds) - FrequencyMath.phaseAdvanceRadians(frequency, window * 0.5);

                real[i] = receivedAmplitude * Math.cos(centerPhase);
                imaginary[i] = receivedAmplitude * Math.sin(centerPhase);
            }

            double expected = directAnalyticalMeanSquare(frequencies, real, imaginary, window);
            double actual = accumulator.meanSquareWindow(window);

            assertTrue(Double.isFinite(actual), "Non-finite result at trial " + trial);
            assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Finite-window mismatch at trial " + trial);
        }
    }

    @Test
    void regularIntegerFrequencyGridsMatchAnalyticalReference() {
        Random random = new Random(0x31F2026L);

        for (int count : new int[]{64, 256, 1_000}) {
            for (double step : new double[]{1.0, -2.0, 0.125}) {
                double window = count == 1_000 ? 0.25 : 0.013;
                long endTick = 4_000_000_001L;

                double[] frequencies = new double[count];
                double[] real = new double[count];
                double[] imaginary = new double[count];

                var accumulator = new FrequencyEngine.InterferenceAccumulator(endTick, window);
                for (int i = 0; i < count; i++) {
                    double frequency = 915_000_000.0 + step * i;
                    double initialPhase = random.nextDouble() * (2.0 * Math.PI);
                    double amplitude = 0.1 + random.nextDouble() * 1.9;
                    double travelTime = random.nextDouble() * 0.1;

                    FrequencySignal signal = new FrequencySignal(frequency, 1.0, initialPhase, 343.0);
                    accumulator.add(signal, amplitude, travelTime);

                    double phase = FrequencyMath.phaseAtTick(frequency, endTick, initialPhase, travelTime) - FrequencyMath.phaseAdvanceRadians(frequency, window * 0.5);

                    frequencies[i] = frequency;
                    real[i] = amplitude * Math.cos(phase);
                    imaginary[i] = amplitude * Math.sin(phase);
                }

                double expected = directAnalyticalMeanSquare(frequencies, real, imaginary, window);
                double actual = accumulator.meanSquareWindow(window);

                assertTrue(Double.isFinite(actual));
                assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Grid mismatch: count=" + count + " step=" + step);
            }
        }
    }

    @Test
    void shuffledIntegerGridsMatchAnalyticalReference() throws ReflectiveOperationException {
        Random random = new Random(0x31F6007L);

        Method gridMethod = FrequencyEngine.InterferenceAccumulator.class.getDeclaredMethod("reorderedIntegerFrequencyGrid");
        gridMethod.setAccessible(true);

        for (int count : new int[]{64, 256, 1_000}) {
            for (double step : new double[]{1.0, -3.0}) {
                double window = 0.25;
                long endTick = 4_000_000_001L;

                double[] frequencies = new double[count];
                double[] amplitudes = new double[count];
                double[] phases = new double[count];
                double[] delays = new double[count];

                double[] real = new double[count];
                double[] imaginary = new double[count];
                int[] order = new int[count];

                for (int i = 0; i < count; i++) {
                    frequencies[i] = 915_000_000.0 + step * i;
                    amplitudes[i] = 0.1 + random.nextDouble() * 1.9;
                    phases[i] = random.nextDouble() * Math.PI * 2.0;
                    delays[i] = random.nextDouble() * 0.1;
                    order[i] = i;
                }

                for (int i = count - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1);

                    int temp = order[i];
                    order[i] = order[j];
                    order[j] = temp;
                }

                var accumulator = new FrequencyEngine.InterferenceAccumulator(endTick, window);
                for (int index : order) {
                    double hz = frequencies[index];
                    accumulator.add(new FrequencySignal(hz, 1.0, phases[index], 343.0), amplitudes[index], delays[index]);
                    double centerPhase = FrequencyMath.phaseAtTick(hz, endTick, phases[index], delays[index]) - FrequencyMath.phaseAdvanceRadians(hz, window * 0.5);

                    real[index] = amplitudes[index] * Math.cos(centerPhase);
                    imaginary[index] = amplitudes[index] * Math.sin(centerPhase);
                }

                assertNotNull(gridMethod.invoke(accumulator), "Shuffled integer grid not detected");

                double expected = directAnalyticalMeanSquare(frequencies, real, imaginary, window);
                double actual = accumulator.meanSquareWindow(window);

                assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Shuffled grid mismatch: count=" + count + " step=" + step);
            }
        }

        var irregular = new FrequencyEngine.InterferenceAccumulator(17L, 0.25);
        for (int i = 0; i < 64; i++) {
            double hz = 915_000_000.0 + i + (i >= 32 ? 1 : 0);
            irregular.add(new FrequencySignal(hz, 1.0, 0.0, 343.0), 1.0, 0.0);
        }

        assertNull(gridMethod.invoke(irregular));
    }

    private static double directAnalyticalMeanSquare(double[] frequency, double[] real, double[] imaginary, double window) {
        double total = 0.0;

        for (int i = 0; i < frequency.length; i++) {
            double a = real[i];
            double b = imaginary[i];

            total += 0.5 * (a * a + b * b - (a * a - b * b) * directSinc(2.0 * frequency[i], window));
            for (int j = i + 1; j < frequency.length; j++) {
                double r = real[j];
                double s = imaginary[j];

                double difference = Math.abs(frequency[i] - frequency[j]);
                double sum = frequency[i] + frequency[j];

                total += (a * r + b * s) * directSinc(difference, window) - (a * r - b * s) * directSinc(sum, window);
            }
        }

        return Math.max(0.0, total);
    }

    private static double directSinc(double frequency, double window) {
        if (frequency == 0.0) {
            return 1.0;
        }

        double x = Math.PI * frequency * window;
        if (!Double.isFinite(x)) {
            return 0.0;
        }

        if (x == 0.0) {
            return 1.0;
        }

        return Math.sin(FrequencyMath.phaseAdvanceRadians(frequency, window * 0.5)) / x;
    }
}
