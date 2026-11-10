package com.krimzik.spectra.api;

import com.krimzik.spectra.math.FrequencyMath;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;

class InterferenceFftExperimentTest {
    private static final long END_TICK = 4_000_000_001L;
    private static final double BASE_HZ = 915_000_000.0;
    private static final double WINDOW = 0.25;

    private static volatile double blackhole;

    @Test
    void compareFftAgainstCurrentGridSolver() {
        for (int count : new int[]{64, 128, 256, 384, 512, 768, 1_000, 2_048, 4_096, 10_000}) {
            Random random = new Random(0x31F2026L);

            var accumulator = new FrequencyEngine.InterferenceAccumulator(END_TICK, WINDOW);

            double[] real = new double[count];
            double[] imaginary = new double[count];

            for (int i = 0; i < count; i++) {
                double hz = BASE_HZ + i;
                double amplitude = 0.1 + random.nextDouble() * 1.9;
                double phase = random.nextDouble() * Math.PI * 2.0;
                double delay = random.nextDouble() * 0.1;

                accumulator.add(new FrequencySignal(hz, 1.0, phase, 343.0), amplitude, delay);

                double centerPhase = FrequencyMath.phaseAtTick(hz, END_TICK, phase, delay) - FrequencyMath.phaseAdvanceRadians(hz, WINDOW * 0.5);
                real[i] = amplitude * Math.cos(centerPhase);
                imaginary[i] = amplitude * Math.sin(centerPhase);
            }

            DoubleSupplier existing = () -> accumulator.meanSquareWindow(WINDOW);
            DoubleSupplier fft = () -> fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW);

            double expected = existing.getAsDouble();
            double actual = fft.getAsDouble();

            assertTrue(Double.isFinite(actual));
            assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "FFT mismatch at " + count + " carriers");

            int warmup = count >= 4096 ? 6 : 25;
            for (int i = 0; i < warmup; i++) {
                blackhole = existing.getAsDouble() + fft.getAsDouble();
            }

            int repetitions = count >= 10_000 ? 2 : count >= 4096 ? 4 : count >= 1000 ? 16 : 100;
            double[] existingTimes = new double[7];
            double[] fftTimes = new double[7];

            for (int sample = 0; sample < 7; sample++) {
                if ((sample & 1) == 0) {
                    existingTimes[sample] = measure(existing, repetitions);
                    fftTimes[sample] = measure(fft, repetitions);
                } else {
                    fftTimes[sample] = measure(fft, repetitions);
                    existingTimes[sample] = measure(existing, repetitions);
                }
            }

            Arrays.sort(existingTimes);
            Arrays.sort(fftTimes);

            double existingUs = existingTimes[3];
            double fftUs = fftTimes[3];

            System.out.printf(Locale.ROOT, "[FREQ-FFT] carriers=%d " + "existing=%.3f us " + "fft=%.3f us " + "speedup=%.2fx " + "expected=%.10f " + "actual=%.10f%n", count, existingUs, fftUs, existingUs / fftUs, expected, actual);

            assertTrue(Double.isFinite(blackhole));
        }
    }

    private static double measure(DoubleSupplier calculation, int repetitions) {
        double checksum = 0.0;
        long start = System.nanoTime();

        for (int i = 0; i < repetitions; i++) {
            checksum += calculation.getAsDouble();
        }

        long elapsed = System.nanoTime() - start;
        blackhole = checksum;

        return elapsed / (repetitions * 1000.0);
    }

    private static final class FftScratch {
        private double[] spectrumReal = new double[0];
        private double[] spectrumImag = new double[0];
        private double[] convolutionReal = new double[0];
        private double[] convolutionImag = new double[0];

        void ensureLength(int length) {
            if (spectrumReal.length == length) {
                return;
            }

            spectrumReal = new double[length];
            spectrumImag = new double[length];
            convolutionReal = new double[length];
            convolutionImag = new double[length];
        }
    }

    private static double fftMeanSquare(double[] real, double[] imaginary, double baseHz, double stepHz, double windowSeconds) {
        return fftMeanSquare(real, imaginary, baseHz, stepHz, windowSeconds, new FftScratch());
    }

    private static double fftMeanSquare(double[] real, double[] imaginary, double baseHz, double stepHz, double windowSeconds, FftScratch scratch) {
        int count = real.length;
        int length = 1;

        while (length < count * 2 - 1) {
            length <<= 1;
        }

        scratch.ensureLength(length);

        double[] spectrumReal = scratch.spectrumReal;
        double[] spectrumImag = scratch.spectrumImag;

        Arrays.fill(spectrumReal, 0.0);
        Arrays.fill(spectrumImag, 0.0);

        System.arraycopy(real, 0, spectrumReal, 0, count);
        System.arraycopy(imaginary, 0, spectrumImag, 0, count);

        fft(spectrumReal, spectrumImag, false);

        double[] convolutionReal = scratch.convolutionReal;
        double[] convolutionImag = scratch.convolutionImag;

        for (int k = 0; k < length; k++) {
            double a = spectrumReal[k];
            double b = spectrumImag[k];

            convolutionReal[k] = Math.fma(a, a, -b * b);
            convolutionImag[k] = 2.0 * a * b;

            spectrumReal[k] = Math.fma(a, a, b * b);
            spectrumImag[k] = 0.0;
        }


        fft(convolutionReal, convolutionImag, true);
        fft(spectrumReal, spectrumImag, true);

        double total = 0.5 * spectrumReal[0];
        for (int difference = 1; difference < count; difference++) {
            total += spectrumReal[difference] * sincWindow(Math.abs(stepHz) * difference, windowSeconds);
        }

        for (int sum = 0; sum < count * 2 - 1; sum++) {
            double hz = 2.0 * baseHz + stepHz * sum;
            total -= 0.5 * convolutionReal[sum] * sincWindow(hz, windowSeconds);
        }

        if (!Double.isFinite(total)) {
            throw new IllegalArgumentException("FFT interference overflow");
        }

        return Math.max(0.0, total);
    }

    private static void fft(double[] real, double[] imaginary, boolean inverse) {
        int n = real.length;

        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >>> 1;

            while ((j & bit) != 0) {
                j ^= bit;
                bit >>>= 1;
            }

            j ^= bit;

            if (i < j) {
                double temp = real[i];
                real[i] = real[j];
                real[j] = temp;

                temp = imaginary[i];
                imaginary[i] = imaginary[j];
                imaginary[j] = temp;
            }
        }

        for (int length = 2; length <= n; length <<= 1) {
            double angle = (inverse ? 2.0 : -2.0) * Math.PI / length;

            double rotationReal = Math.cos(angle);
            double rotationImag = Math.sin(angle);

            int half = length >>> 1;

            for (int start = 0; start < n; start += length) {
                double weightReal = 1.0;
                double weightImag = 0.0;

                for (int j = 0; j < half; j++) {
                    int a = start + j;
                    int b = a + half;

                    double productReal = real[b] * weightReal - imaginary[b] * weightImag;
                    double productImag = real[b] * weightImag + imaginary[b] * weightReal;

                    double oldReal = real[a];
                    double oldImag = imaginary[a];

                    real[a] = oldReal + productReal;
                    imaginary[a] = oldImag + productImag;

                    real[b] = oldReal - productReal;
                    imaginary[b] = oldImag - productImag;

                    double nextWeightReal = weightReal * rotationReal - weightImag * rotationImag;
                    weightImag = weightReal * rotationImag + weightImag * rotationReal;
                    weightReal = nextWeightReal;
                }
            }
        }

        if (inverse) {
            double scale = 1.0 / n;

            for (int i = 0; i < n; i++) {
                real[i] *= scale;
                imaginary[i] *= scale;
            }
        }
    }

    private static double sincWindow(double frequency, double windowSeconds) {
        if (frequency == 0.0) {
            return 1.0;
        }

        double x = Math.PI * frequency * windowSeconds;

        if (!Double.isFinite(x)) {
            return 0.0;
        }

        if (Math.abs(x) <= Math.PI * 0.25) {
            double x2 = x * x;
            return 1.0 + x2 * (-1.0 / 6.0 + x2 * (1.0 / 120.0 + x2 * (-1.0 / 5040.0 + x2 * (1.0 / 362880.0 + x2 * (-1.0 / 39916800.0 + x2 * (1.0 / 6227020800.0 - x2 / 1307674368000.0))))));
        }

        double phase = FrequencyMath.phaseAdvanceRadians(frequency, windowSeconds * 0.5);

        return Math.sin(phase) / x;
    }

    @Test
    void measureFftAllocations() {
        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;

        if (memory == null) {
            System.out.println("[FREQ-FFT-MEM] unavailable");
            return;
        }

        if (!memory.isThreadAllocatedMemoryEnabled()) {
            memory.setThreadAllocatedMemoryEnabled(true);
        }

        long threadId = Thread.currentThread().threadId();
        for (int count : new int[]{256, 1_000, 4_096, 10_000}) {
            double[] real = new double[count];
            double[] imaginary = new double[count];

            for (int i = 0; i < count; i++) {
                real[i] = Math.cos(i * 0.1);
                imaginary[i] = Math.sin(i * 0.1);
            }

            for (int i = 0; i < 30; i++) {
                blackhole = fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW);
            }

            int iterations = count >= 10_000 ? 10 : 100;

            long before = memory.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                blackhole = fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW);
            }

            long elapsed = System.nanoTime() - start;
            long after = memory.getThreadAllocatedBytes(threadId);

            System.out.printf(Locale.ROOT, "[FREQ-FFT-MEM] carriers=%d " + "time=%.3f us " + "allocated=%.0f bytes/query%n", count, elapsed / (iterations * 1000.0), (after - before) / (double) iterations);
        }
    }

    @Test
    void fftMatchesAnalyticalSolverAcrossWindows() {
        Random random = new Random(0x31F2027L);

        for (int count : new int[]{64, 256, 1_000, 4_096}) {
            for (double spacing : new double[]{1.0, -3.0, 0.125}) {
                for (double window : new double[]{0.00001, 0.013, 0.25, 1.0}) {
                    var accumulator = new FrequencyEngine.InterferenceAccumulator(END_TICK, window);

                    double[] real = new double[count];
                    double[] imaginary = new double[count];

                    for (int i = 0; i < count; i++) {
                        double hz = BASE_HZ + spacing * i;
                        double amplitude = 0.1 + random.nextDouble() * 1.9;
                        double phase = random.nextDouble() * Math.PI * 2.0;
                        double delay = random.nextDouble() * 0.1;

                        FrequencySignal signal = new FrequencySignal(hz, 1.0, phase, 343.0);
                        accumulator.add(signal, amplitude, delay);
                        double centerPhase = FrequencyMath.phaseAtTick(hz, END_TICK, phase, delay) - FrequencyMath.phaseAdvanceRadians(hz, window * 0.5);

                        real[i] = amplitude * Math.cos(centerPhase);
                        imaginary[i] = amplitude * Math.sin(centerPhase);
                    }

                    double expected = accumulator.meanSquareWindow(window);
                    double actual = fftMeanSquare(real, imaginary, BASE_HZ, spacing, window);

                    assertTrue(Double.isFinite(actual));

                    assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "FFT mismatch count=" + count + " spacing=" + spacing + " window=" + window);
                }
            }
        }
    }

    @Test
    void fftRoundTripPreservesSignalAndEnergy() {
        Random random = new Random(0x31F7001L);

        for (int n : new int[]{2, 8, 64, 256, 1024, 4096}) {
            double[] real = new double[n];
            double[] imaginary = new double[n];

            double energy = 0.0;

            for (int i = 0; i < n; i++) {
                real[i] = random.nextDouble() * 2.0 - 1.0;
                imaginary[i] = random.nextDouble() * 2.0 - 1.0;

                energy += real[i] * real[i] + imaginary[i] * imaginary[i];
            }

            double[] originalReal = real.clone();
            double[] originalImaginary = imaginary.clone();

            fft(real, imaginary, false);

            double spectralEnergy = 0.0;

            for (int i = 0; i < n; i++) {
                spectralEnergy += real[i] * real[i] + imaginary[i] * imaginary[i];
            }

            assertEquals(energy, spectralEnergy / n, 1.0e-10 * Math.max(1.0, energy));

            fft(real, imaginary, true);

            double maxError = 0.0;

            for (int i = 0; i < n; i++) {
                maxError = Math.max(maxError, Math.abs(real[i] - originalReal[i]));
                maxError = Math.max(maxError, Math.abs(imaginary[i] - originalImaginary[i]));
            }

            assertTrue(maxError < 1.0e-9);

            System.out.printf(Locale.ROOT, "[FREQ-FFT-ROUNDTRIP] samples=%d maxError=%.3e%n", n, maxError);
        }
    }

    @Test
    void fftCrossCorrelationMatchesDirectAndDetectsDelay() {
        Random random = new Random(0x31F7002L);

        for (int n : new int[]{64, 256, 1024}) {
            int delay = 17;

            double[] reference = new double[n];
            double[] received = new double[n];

            for (int i = 0; i < n; i++) {
                reference[i] = random.nextDouble() * 2.0 - 1.0;
            }

            for (int i = 0; i < n; i++) {
                received[(i + delay) & (n - 1)] = reference[i];
            }

            double[] ar = reference.clone();
            double[] ai = new double[n];
            double[] br = received.clone();
            double[] bi = new double[n];

            fft(ar, ai, false);
            fft(br, bi, false);

            for (int i = 0; i < n; i++) {
                double a = ar[i];
                double b = ai[i];
                double c = br[i];
                double d = bi[i];

                ar[i] = Math.fma(a, c, b * d);
                ai[i] = Math.fma(a, d, -b * c);
            }

            fft(ar, ai, true);

            int strongestLag = 0;

            for (int lag = 0; lag < n; lag++) {
                double expected = 0.0;

                for (int j = 0; j < n; j++) {
                    expected += reference[j] * received[(j + lag) & (n - 1)];
                }

                assertEquals(expected, ar[lag], 1.0e-8 * Math.max(1.0, Math.abs(expected)));
                assertEquals(0.0, ai[lag], 1.0e-8);

                if (ar[lag] > ar[strongestLag]) {
                    strongestLag = lag;
                }
            }

            assertEquals(delay, strongestLag);

            System.out.printf(Locale.ROOT, "[FREQ-FFT-CORRELATION] samples=%d delay=%d " + "detected=%d%n", n, delay, strongestLag);
        }
    }

    @Test
    void fftMatchesDirectCalculationDuringCancellation() {
        double worstError = 0.0;

        for (int count : new int[]{64, 257, 1000}) {
            double[] real = new double[count];
            double[] imaginary = new double[count];

            for (int i = 0; i < count; i++) {
                double sign = (i & 1) == 0 ? 1.0 : -1.0;

                real[i] = sign / count;
                imaginary[i] = sign * 0.25 / count;
            }

            for (double spacing : new double[]{1.0, -3.0, 0.125}) {
                for (double window : new double[]{1.0e-9, 1.0e-6, 0.013, 0.25}) {
                    double expected = directWindowPower(real, imaginary, BASE_HZ, spacing, window);
                    double actual = fftMeanSquare(real, imaginary, BASE_HZ, spacing, window);

                    assertTrue(Double.isFinite(actual));
                    assertTrue(actual >= 0.0);

                    double error = Math.abs(expected - actual);
                    worstError = Math.max(worstError, error);

                    assertEquals(expected, actual, Math.max(5.0e-11, 5.0e-9 * Math.abs(expected)), "Cancellation mismatch count=" + count + " spacing=" + spacing + " window=" + window);
                }
            }
        }

        System.out.printf(Locale.ROOT, "[FREQ-FFT-CANCELLATION] worstError=%.3e%n", worstError);
    }

    private static double directWindowPower(double[] real, double[] imaginary, double baseHz, double stepHz, double windowSeconds) {
        double total = 0.0;

        for (int i = 0; i < real.length; i++) {
            double frequencyA = baseHz + stepHz * i;
            double realA = real[i];
            double imaginaryA = imaginary[i];

            double realSquared = realA * realA;
            double imaginarySquared = imaginaryA * imaginaryA;

            total += 0.5 * (realSquared + imaginarySquared - (realSquared - imaginarySquared) * sincWindow(2.0 * frequencyA, windowSeconds));
            for (int j = i + 1; j < real.length; j++) {
                double frequencyB = baseHz + stepHz * j;

                double realB = real[j];
                double imaginaryB = imaginary[j];

                double differenceComponent = Math.fma(realA, realB, imaginaryA * imaginaryB);
                double sumComponent = Math.fma(realA, realB, -imaginaryA * imaginaryB);

                total += differenceComponent * sincWindow(Math.abs(frequencyA - frequencyB), windowSeconds) - sumComponent * sincWindow(frequencyA + frequencyB, windowSeconds);
            }
        }

        return Math.max(0.0, total);
    }

    @Test
    void benchmarkCorrelationScaling() {
        Random random = new Random(0x31F7003L);

        for (int n : new int[]{64, 256, 1024, 4096}) {
            double[] reference = new double[n];
            double[] received = new double[n];

            for (int i = 0; i < n; i++) {
                reference[i] = random.nextDouble() * 2.0 - 1.0;
            }

            for (int i = 0; i < n; i++) {
                received[(i + 17) & (n - 1)] = reference[i];
            }

            DoubleSupplier direct = () -> directCorrelationChecksum(reference, received);
            DoubleSupplier accelerated = () -> fftCorrelationChecksum(reference, received);

            double expected = direct.getAsDouble();
            double actual = accelerated.getAsDouble();

            assertEquals(expected, actual, 1.0e-8 * Math.max(1.0, Math.abs(expected)));

            int warmup = n >= 4096 ? 2 : 15;

            for (int i = 0; i < warmup; i++) {
                blackhole = direct.getAsDouble() + accelerated.getAsDouble();
            }

            int repetitions = n >= 4096 ? 2 : n >= 1024 ? 10 : 50;

            double[] directUs = new double[5];
            double[] fftUs = new double[5];

            for (int sample = 0; sample < 5; sample++) {
                if ((sample & 1) == 0) {
                    directUs[sample] = measure(direct, repetitions);
                    fftUs[sample] = measure(accelerated, repetitions);
                } else {
                    fftUs[sample] = measure(accelerated, repetitions);
                    directUs[sample] = measure(direct, repetitions);
                }
            }

            Arrays.sort(directUs);
            Arrays.sort(fftUs);

            System.out.printf(Locale.ROOT, "[FREQ-FFT-CORR-BENCH] samples=%d " + "direct=%.3f us fft=%.3f us " + "speedup=%.2fx%n", n, directUs[2], fftUs[2], directUs[2] / fftUs[2]);
        }
    }

    private static double directCorrelationChecksum(double[] reference, double[] received) {
        int n = reference.length;
        double checksum = 0.0;

        for (int lag = 0; lag < n; lag++) {
            double sum = 0.0;

            for (int i = 0; i < n; i++) {
                sum += reference[i] * received[(i + lag) & (n - 1)];
            }

            checksum += sum * (lag + 1);
        }

        return checksum;
    }

    private static double fftCorrelationChecksum(double[] reference, double[] received) {
        int n = reference.length;

        double[] ar = reference.clone();
        double[] ai = new double[n];
        double[] br = received.clone();
        double[] bi = new double[n];

        fft(ar, ai, false);
        fft(br, bi, false);

        for (int i = 0; i < n; i++) {
            double a = ar[i];
            double b = ai[i];
            double c = br[i];
            double d = bi[i];

            ar[i] = Math.fma(a, c, b * d);
            ai[i] = Math.fma(a, d, -b * c);
        }

        fft(ar, ai, true);

        double checksum = 0.0;

        for (int lag = 0; lag < n; lag++) {
            checksum += ar[lag] * (lag + 1);
        }

        return checksum;
    }


    @Test
    void benchmarkReusableFftBuffers() {
        com.sun.management.ThreadMXBean memory = java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;

        if (memory != null && !memory.isThreadAllocatedMemoryEnabled()) {
            memory.setThreadAllocatedMemoryEnabled(true);
        }

        long threadId = Thread.currentThread().threadId();

        for (int count : new int[]{256, 1_000, 4_096, 10_000}) {
            double[] real = new double[count];
            double[] imaginary = new double[count];

            for (int i = 0; i < count; i++) {
                real[i] = Math.cos(i * 0.1);
                imaginary[i] = Math.sin(i * 0.1);
            }

            FftScratch scratch = new FftScratch();

            double expected = fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW);
            double actual = fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW, scratch);

            assertEquals(expected, actual, 1.0e-10 * Math.max(1.0, Math.abs(expected)));

            for (int i = 0; i < 20; i++) {
                blackhole = fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW, scratch);
            }

            int iterations = count >= 10_000 ? 12 : 60;
            double allocatingUs = measure(() -> fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW), iterations);
            double reusableUs = measure(() -> fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW, scratch), iterations);
            long bytesBefore = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

            for (int i = 0; i < iterations; i++) {
                blackhole = fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW, scratch);
            }

            long bytesAfter = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
            double bytesPerQuery = bytesBefore < 0 || bytesAfter < 0 ? -1.0 : (bytesAfter - bytesBefore) / (double) iterations;

            System.out.printf(Locale.ROOT, "[FREQ-FFT-REUSE] carriers=%d " + "allocating=%.3f us " + "reusable=%.3f us " + "reusableBytes=%.0f bytes/query%n", count, allocatingUs, reusableUs, bytesPerQuery);
        }
    }

    @Test
    void reusedFftScratchNeverRetainsOldCarrierData() {
        Random random = new Random(0x31F8001L);
        FftScratch scratch = new FftScratch();

        int[] counts = {
                513, 700, 999, 513,
                64, 700, 1000, 513
        };

        double[] windows = {
                0.000001, 0.013, 0.25, 1.0
        };

        for (double window : windows) {
            for (int count : counts) {
                double[] real = new double[count];
                double[] imaginary = new double[count];

                for (int i = 0; i < count; i++) {
                    real[i] = random.nextDouble() * 2.0 - 1.0;
                    imaginary[i] = random.nextDouble() * 2.0 - 1.0;
                }

                for (double step : new double[]{1.0, -3.0}) {
                    double expected = fftMeanSquare(real, imaginary, BASE_HZ, step, window);
                    double actual = fftMeanSquare(real, imaginary, BASE_HZ, step, window, scratch);

                    assertEquals(expected, actual, 1.0e-11 * Math.max(1.0, Math.abs(expected)), "Scratch reuse mismatch: count=" + count + " step=" + step + " window=" + window);
                }
            }
        }

        double[] zeroReal = new double[513];
        double[] zeroImaginary = new double[513];

        assertEquals(0.0, fftMeanSquare(zeroReal, zeroImaginary, BASE_HZ, 1.0, WINDOW, scratch), 1.0e-12);
    }

    @Test
    void benchmark50kFftWithReusableBuffers() {
        int count = 50_000;
        double[] real = new double[count];
        double[] imaginary = new double[count];

        for (int i = 0; i < count; i++) {
            real[i] = Math.cos(i * 0.137);
            imaginary[i] = Math.sin(i * 0.193);
        }

        FftScratch scratch = new FftScratch();

        for (int i = 0; i < 5; i++) {
            blackhole = fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW, scratch);
        }

        long[] times = new long[7];
        double result = 0.0;

        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();

            result = fftMeanSquare(real, imaginary, BASE_HZ, 1.0, WINDOW, scratch);

            times[i] = System.nanoTime() - start;
            blackhole = result;
        }

        Arrays.sort(times);

        long scratchBytes = 4L * scratch.spectrumReal.length * Double.BYTES;

        assertTrue(Double.isFinite(result));
        assertTrue(result >= 0.0);

        System.out.printf(Locale.ROOT, "[FREQ-FFT-50K] carriers=%d " + "median=%.3f ms " + "scratch=%.3f MiB " + "result=%.10f%n", count, times[3] / 1_000_000.0, scratchBytes / 1_048_576.0, result);
    }


    @Test
    void sparseFrequencyGridsMatchDirectAnalyticalPower() {
        Random random = new Random(0x31A501L);

        for (int count : new int[]{64, 256, 1_000}) {
            int[] positions = new int[count];
            double[] real = new double[count];
            double[] imaginary = new double[count];

            int position = 0;

            for (int i = 0; i < count; i++) {
                if (i != 0) {
                    position += 1 + random.nextInt(4);
                }

                positions[i] = position;

                double amplitude = 0.1 + 1.9 * random.nextDouble();
                double phase = 2.0 * Math.PI * random.nextDouble();

                real[i] = amplitude * Math.cos(phase);
                imaginary[i] = amplitude * Math.sin(phase);
            }

            int gridSize = position + 1;

            for (double spacing : new double[]{1.0, -3.0, 0.125}) {
                double[] frequencies = new double[count];
                double[] gridReal = new double[gridSize];
                double[] gridImaginary = new double[gridSize];

                for (int i = 0; i < count; i++) {
                    frequencies[i] = BASE_HZ + spacing * positions[i];
                    gridReal[positions[i]] = real[i];
                    gridImaginary[positions[i]] = imaginary[i];
                }

                FftScratch scratch = new FftScratch();

                for (double window : new double[]{0.013, 0.25}) {
                    double expected = directSparsePower(frequencies, real, imaginary, window);

                    double actual = fftMeanSquare(gridReal, gridImaginary, BASE_HZ, spacing, window, scratch);

                    assertTrue(Double.isFinite(actual));
                    assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Sparse FFT mismatch count=" + count + " spacing=" + spacing + " window=" + window);

                    System.out.printf(Locale.ROOT, "[FREQ-FFT-SPARSE] carriers=%d " + "grid=%d step=%.3f " + "window=%.3f " + "error=%.3e%n", count, gridSize, spacing, window, Math.abs(expected - actual));
                }
            }
        }
    }

    private static double directSparsePower(double[] frequencies, double[] real, double[] imaginary, double windowSeconds) {
        double total = 0.0;

        for (int i = 0; i < frequencies.length; i++) {
            double rA = real[i];
            double qA = imaginary[i];
            double hzA = frequencies[i];

            double rr = rA * rA;
            double qq = qA * qA;

            total += 0.5 * (rr + qq - (rr - qq) * sincWindow(2.0 * hzA, windowSeconds));

            for (int j = i + 1; j < frequencies.length; j++) {
                double rB = real[j];
                double qB = imaginary[j];
                double hzB = frequencies[j];

                double differenceTerm = Math.fma(rA, rB, qA * qB);
                double sumTerm = Math.fma(rA, rB, -qA * qB);

                total += differenceTerm * sincWindow(Math.abs(hzA - hzB), windowSeconds);
                total -= sumTerm * sincWindow(hzA + hzB, windowSeconds);
            }
        }

        return Math.max(0.0, total);
    }

    @Test
    void benchmarkSparseGridScaling() {
        Random random = new Random(0x31A502L);

        for (int count : new int[]{128, 512, 1_000}) {
            for (int gap : new int[]{1, 2, 4, 8, 16, 64}) {
                int[] positions = new int[count];
                double[] frequencies = new double[count];
                double[] real = new double[count];
                double[] imaginary = new double[count];

                int position = 0;

                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        position += ((i & 1) == 0) ? 1 : gap;
                    }

                    positions[i] = position;
                    frequencies[i] = BASE_HZ + position * 0.125;

                    double amplitude = 0.1 + random.nextDouble() * 1.9;
                    double phase = random.nextDouble() * Math.PI * 2.0;

                    real[i] = amplitude * Math.cos(phase);
                    imaginary[i] = amplitude * Math.sin(phase);
                }

                int gridSize = position + 1;

                double[] gridReal = new double[gridSize];
                double[] gridImaginary = new double[gridSize];

                for (int i = 0; i < count; i++) {
                    gridReal[positions[i]] = real[i];
                    gridImaginary[positions[i]] = imaginary[i];
                }

                FftScratch scratch = new FftScratch();

                double expected = directSparsePower(frequencies, real, imaginary, WINDOW);
                double actual = fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, WINDOW, scratch);

                assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)));

                int repetitions = count >= 1_000 ? 2 : count >= 512 ? 5 : 10;

                for (int i = 0; i < 3; i++) {
                    blackhole = directSparsePower(frequencies, real, imaginary, WINDOW);
                    blackhole = fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, WINDOW, scratch);
                }

                double[] directTimes = new double[5];
                double[] fftTimes = new double[5];

                for (int sample = 0; sample < 5; sample++) {
                    if ((sample & 1) == 0) {
                        directTimes[sample] = measure(() -> directSparsePower(frequencies, real, imaginary, WINDOW), repetitions);
                        fftTimes[sample] = measure(() -> fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, WINDOW, scratch), repetitions);
                    } else {
                        fftTimes[sample] = measure(() -> fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, WINDOW, scratch), repetitions);
                        directTimes[sample] = measure(() -> directSparsePower(frequencies, real, imaginary, WINDOW), repetitions);
                    }
                }

                Arrays.sort(directTimes);
                Arrays.sort(fftTimes);

                double density = count / (double) gridSize;
                double directUs = directTimes[2];
                double fftUs = fftTimes[2];

                double scratchMiB = 4.0 * scratch.spectrumReal.length * Double.BYTES / 1_048_576.0;

                System.out.printf(Locale.ROOT, "[FREQ-FFT-SPARSE-BENCH] " + "carriers=%d grid=%d " + "density=%.3f " + "direct=%.3f us " + "fft=%.3f us " + "speedup=%.2fx " + "scratch=%.3f MiB%n", count, gridSize, density, directUs, fftUs, directUs / fftUs, scratchMiB);
            }
        }
    }


    private static final class SparseLagScratch {
        final double[] differences;
        final double[] sums;

        final boolean[] differenceUsed;
        final boolean[] sumUsed;

        final int[] activeDifferences;
        final int[] activeSums;

        int differenceCount;
        int sumCount;

        SparseLagScratch(int gridSize) {
            int sumSize = 2 * gridSize - 1;

            differences = new double[gridSize];
            sums = new double[sumSize];

            differenceUsed = new boolean[gridSize];
            sumUsed = new boolean[sumSize];

            activeDifferences = new int[gridSize];
            activeSums = new int[sumSize];
        }

        void addDifference(int index, double value) {
            if (!differenceUsed[index]) {
                differenceUsed[index] = true;
                activeDifferences[differenceCount++] = index;
            }
            differences[index] += value;
        }

        void addSum(int index, double value) {
            if (!sumUsed[index]) {
                sumUsed[index] = true;
                activeSums[sumCount++] = index;
            }
            sums[index] += value;
        }

        void clear() {
            for (int i = 0; i < differenceCount; i++) {
                int index = activeDifferences[i];
                differences[index] = 0.0;
                differenceUsed[index] = false;
            }

            for (int i = 0; i < sumCount; i++) {
                int index = activeSums[i];
                sums[index] = 0.0;
                sumUsed[index] = false;
            }

            differenceCount = 0;
            sumCount = 0;
        }
    }

    private static double sparseLagMeanSquare(int[] positions, double[] real, double[] imaginary, double baseHz, double spacingHz, double windowSeconds, SparseLagScratch scratch) {
        double total = 0.0;
        int count = positions.length;

        for (int i = 0; i < count; i++) {
            double rA = real[i];
            double qA = imaginary[i];

            total += 0.5 * Math.fma(rA, rA, qA * qA);

            scratch.addSum(2 * positions[i], 0.5 * Math.fma(rA, rA, -qA * qA));

            for (int j = i + 1; j < count; j++) {
                double rB = real[j];
                double qB = imaginary[j];

                int difference = Math.abs(positions[j] - positions[i]);
                int sum = positions[i] + positions[j];

                scratch.addDifference(difference, Math.fma(rA, rB, qA * qB));
                scratch.addSum(sum, Math.fma(rA, rB, -qA * qB));
            }
        }

        double absoluteSpacing = Math.abs(spacingHz);

        for (int i = 0; i < scratch.differenceCount; i++) {
            int index = scratch.activeDifferences[i];
            double coefficient = scratch.differences[index];

            if (coefficient != 0.0) {
                total += coefficient * sincWindow(absoluteSpacing * index, windowSeconds);
            }
        }

        for (int i = 0; i < scratch.sumCount; i++) {
            int index = scratch.activeSums[i];
            double coefficient = scratch.sums[index];

            if (coefficient != 0.0) {
                total -= coefficient * sincWindow(2.0 * baseHz + spacingHz * index, windowSeconds);
            }
        }

        scratch.clear();
        return Math.max(0.0, total);
    }


    @Test
    void benchmarkSparseLagAgainstFft() {
        for (int count : new int[]{128, 512, 1_000}) {
            for (int gap : new int[]{1, 8, 64}) {
                Random random = new Random(0x31A504L + count * 131L + gap);

                int[] positions = new int[count];
                double[] frequencies = new double[count];
                double[] real = new double[count];
                double[] imaginary = new double[count];

                int position = 0;

                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        position += (i & 1) == 0 ? 1 : gap;
                    }

                    positions[i] = position;
                    frequencies[i] = BASE_HZ + position * 0.125;

                    double amplitude = 0.1 + 1.9 * random.nextDouble();
                    double phase = 2.0 * Math.PI * random.nextDouble();

                    real[i] = amplitude * Math.cos(phase);
                    imaginary[i] = amplitude * Math.sin(phase);
                }

                int gridSize = position + 1;
                double[] gridReal = new double[gridSize];
                double[] gridImaginary = new double[gridSize];

                for (int i = 0; i < count; i++) {
                    gridReal[positions[i]] = real[i];
                    gridImaginary[positions[i]] = imaginary[i];
                }

                SparseLagScratch sparseScratch = new SparseLagScratch(gridSize);
                FftScratch fftScratch = new FftScratch();

                for (double window : new double[]{0.013, 0.25}) {
                    double expected = directSparsePower(frequencies, real, imaginary, window);

                    double sparse = sparseLagMeanSquare(positions, real, imaginary, BASE_HZ, 0.125, window, sparseScratch);
                    double fft = fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, window, fftScratch);
                    double tolerance = 2.0e-9 * Math.max(1.0, Math.abs(expected));

                    assertTrue(Double.isFinite(sparse));
                    assertEquals(expected, sparse, tolerance, "Sparse lag count=" + count + " gap=" + gap + " window=" + window);
                    assertEquals(expected, fft, tolerance, "FFT count=" + count + " gap=" + gap + " window=" + window);
                }

                for (int i = 0; i < 5; i++) {
                    blackhole = sparseLagMeanSquare(positions, real, imaginary, BASE_HZ, 0.125, WINDOW, sparseScratch);
                    blackhole = fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, WINDOW, fftScratch);
                }

                int repetitions = count >= 1_000 ? 2 : count >= 512 ? 5 : 10;

                double[] sparseTimes = new double[5];
                double[] fftTimes = new double[5];
                double[] directTimes = new double[5];

                for (int sample = 0; sample < 5; sample++) {
                    if ((sample & 1) == 0) {
                        directTimes[sample] = measure(() -> directSparsePower(frequencies, real, imaginary, WINDOW), repetitions);
                        sparseTimes[sample] = measure(() -> sparseLagMeanSquare(positions, real, imaginary, BASE_HZ, 0.125, WINDOW, sparseScratch), repetitions);
                        fftTimes[sample] = measure(() -> fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, WINDOW, fftScratch), repetitions);
                    } else {
                        fftTimes[sample] = measure(() -> fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, WINDOW, fftScratch), repetitions);
                        sparseTimes[sample] = measure(() -> sparseLagMeanSquare(positions, real, imaginary, BASE_HZ, 0.125, WINDOW, sparseScratch), repetitions);
                        directTimes[sample] = measure(() -> directSparsePower(frequencies, real, imaginary, WINDOW), repetitions);
                    }
                }

                Arrays.sort(directTimes);
                Arrays.sort(sparseTimes);
                Arrays.sort(fftTimes);

                double directUs = directTimes[2];
                double sparseUs = sparseTimes[2];
                double fftUs = fftTimes[2];

                System.out.printf(Locale.ROOT, "[FREQ-SPARSE-LAG] " + "carriers=%d grid=%d " + "density=%.3f " + "direct=%.3f us " + "fft=%.3f us " + "sparse=%.3f us " + "sparseVsFft=%.2fx%n", count, gridSize, count / (double) gridSize, directUs, fftUs, sparseUs, fftUs / sparseUs);
            }
        }
    }


    private static final class LongLagTable {
        private static final int MAX_CAPACITY = 1 << 18;

        private final boolean sumSide;

        private long[] keys = new long[256];
        private double[] values = new double[256];
        private double[] kernels = new double[256];

        private boolean[] occupied = new boolean[256];
        private int[] stamps = new int[256];
        private int[] activeSlots = new int[256];

        private int size;
        private int activeCount;
        private int epoch = 1;

        private boolean hasParameters;
        private long baseBits;
        private long stepBits;
        private long windowBits;

        private double baseHz;
        private double stepHz;
        private double window;

        LongLagTable(boolean sumSide) {
            this.sumSide = sumSide;
        }

        void begin(double baseHz, double stepHz, double window) {
            long b = Double.doubleToLongBits(baseHz);
            long s = Double.doubleToLongBits(stepHz);
            long w = Double.doubleToLongBits(window);

            if (!hasParameters || b != baseBits || s != stepBits || w != windowBits) {
                Arrays.fill(occupied, false);
                size = 0;

                hasParameters = true;
                baseBits = b;
                stepBits = s;
                windowBits = w;

                this.baseHz = baseHz;
                this.stepHz = stepHz;
                this.window = window;
            }

            activeCount = 0;

            if (++epoch == Integer.MAX_VALUE) {
                Arrays.fill(stamps, 0);
                epoch = 1;
            }
        }

        void add(long key, double coefficient) {
            int slot = locate(key);

            if (!occupied[slot]) {
                if (size >= keys.length / 2) {
                    grow();
                    slot = locate(key);
                }

                occupied[slot] = true;
                keys[slot] = key;

                double hz = sumSide ? 2.0 * baseHz + (double) key * stepHz : Math.abs((double) key * stepHz);

                kernels[slot] = sincWindow(hz, window);
                size++;
            }

            if (stamps[slot] != epoch) {
                stamps[slot] = epoch;
                values[slot] = 0.0;
                activeSlots[activeCount++] = slot;
            }

            values[slot] += coefficient;
        }

        double weightedSum() {
            double result = 0.0;

            for (int i = 0; i < activeCount; i++) {
                int slot = activeSlots[i];

                result += values[slot] * kernels[slot];
            }

            return result;
        }

        int capacity() {
            return keys.length;
        }

        int keyCount() {
            return size;
        }

        private int locate(long key) {
            int mask = keys.length - 1;

            long hash = key;
            hash = (hash ^ (hash >>> 33)) * 0xff51afd7ed558ccdL;
            hash = (hash ^ (hash >>> 33)) * 0xc4ceb9fe1a85ec53L;

            int slot = (int) (hash ^ (hash >>> 33)) & mask;
            while (occupied[slot] && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }

            return slot;
        }

        private void grow() {
            if (keys.length >= MAX_CAPACITY) {
                throw new IllegalStateException("Sparse lag capacity reached; " + "select another solver");
            }

            long[] oldKeys = keys;
            double[] oldValues = values;
            double[] oldKernels = kernels;
            boolean[] oldOccupied = occupied;
            int[] oldStamps = stamps;

            int newCapacity = keys.length << 1;

            keys = new long[newCapacity];
            values = new double[newCapacity];
            kernels = new double[newCapacity];

            occupied = new boolean[newCapacity];
            stamps = new int[newCapacity];
            activeSlots = new int[newCapacity];
            activeCount = 0;

            for (int i = 0; i < oldKeys.length; i++) {
                if (!oldOccupied[i]) {
                    continue;
                }

                int slot = locate(oldKeys[i]);

                occupied[slot] = true;
                keys[slot] = oldKeys[i];
                kernels[slot] = oldKernels[i];
                stamps[slot] = oldStamps[i];

                if (oldStamps[i] == epoch) {
                    values[slot] = oldValues[i];
                    activeSlots[activeCount++] = slot;
                }
            }
        }
    }

    private static final class HashLagScratch {
        final LongLagTable differences = new LongLagTable(false);
        final LongLagTable sums = new LongLagTable(true);

        void begin(double baseHz, double stepHz, double window) {
            differences.begin(baseHz, stepHz, window);
            sums.begin(baseHz, stepHz, window);
        }
    }

    private static double hashLagMeanSquare(int[] positions, double[] real, double[] imaginary, double baseHz, double spacingHz, double windowSeconds, HashLagScratch scratch) {
        scratch.begin(baseHz, spacingHz, windowSeconds);

        double total = 0.0;

        for (int i = 0; i < positions.length; i++) {
            double rA = real[i];
            double qA = imaginary[i];

            total += 0.5 * Math.fma(rA, rA, qA * qA);

            scratch.sums.add(2L * positions[i], 0.5 * Math.fma(rA, rA, -qA * qA));

            for (int j = i + 1; j < positions.length; j++) {
                double rB = real[j];
                double qB = imaginary[j];

                long difference = Math.abs((long) positions[j] - positions[i]);
                long sum = (long) positions[i] + positions[j];

                scratch.differences.add(difference, Math.fma(rA, rB, qA * qB));
                scratch.sums.add(sum, Math.fma(rA, rB, -qA * qB));
            }
        }

        total += scratch.differences.weightedSum();
        total -= scratch.sums.weightedSum();

        return Math.max(0.0, total);
    }

    @Test
    void benchmarkCompactSparseHash() {
        runCompactSparseCase(128, 64, false);
        runCompactSparseCase(128, 1_000_000, true);
        runCompactSparseCase(256, 1_000_000, true);
        runCompactSparseCase(512, 64, false);
        runCompactSparseCase(1_000, 64, false);
    }

    private static void runCompactSparseCase(int count, int gap, boolean randomGaps) {
        Random random = new Random(0x31A505L + count * 131L + gap);

        int[] positions = new int[count];
        double[] real = new double[count];
        double[] imaginary = new double[count];
        double[] frequencies = new double[count];

        int position = 0;

        for (int i = 0; i < count; i++) {
            if (i > 0) {
                position += randomGaps ? 1 + random.nextInt(gap) : ((i & 1) == 0 ? 1 : gap);
            }

            positions[i] = position;

            real[i] = random.nextGaussian();
            imaginary[i] = random.nextGaussian();
        }

        int gridSize = position + 1;
        HashLagScratch scratch = new HashLagScratch();

        for (double spacing : new double[]{0.125, -3.0}) {
            for (int i = 0; i < count; i++) {
                frequencies[i] = BASE_HZ + spacing * positions[i];
            }

            for (double window : new double[]{0.013, 0.25}) {
                double expected = directSparsePower(frequencies, real, imaginary, window);
                double actual = hashLagMeanSquare(positions, real, imaginary, BASE_HZ, spacing, window, scratch);
                double tolerance = 2.0e-9 * Math.max(1.0, Math.abs(expected));

                assertTrue(Double.isFinite(actual));
                assertEquals(expected, actual, tolerance);


                assertEquals(expected, hashLagMeanSquare(positions, real, imaginary, BASE_HZ, spacing, window, scratch), tolerance);

                real[0] += 0.175;
                imaginary[count - 1] -= 0.125;

                double changedExpected = directSparsePower(frequencies, real, imaginary, window);
                double changedActual = hashLagMeanSquare(positions, real, imaginary, BASE_HZ, spacing, window, scratch);

                assertEquals(changedExpected, changedActual, 2.0e-9 * Math.max(1.0, Math.abs(changedExpected)));
            }
        }

        for (int i = 0; i < count; i++) {
            frequencies[i] = BASE_HZ + 0.125 * positions[i];
        }

        final double[] gridReal;
        final double[] gridImaginary;
        final FftScratch fftScratch;

        if (gridSize <= 65_536) {
            gridReal = new double[gridSize];
            gridImaginary = new double[gridSize];

            for (int i = 0; i < count; i++) {
                gridReal[positions[i]] = real[i];
                gridImaginary[positions[i]] = imaginary[i];
            }

            fftScratch = new FftScratch();
        } else {
            gridReal = null;
            gridImaginary = null;
            fftScratch = null;
        }

        for (int i = 0; i < 5; i++) {
            blackhole = hashLagMeanSquare(positions, real, imaginary, BASE_HZ, 0.125, WINDOW, scratch);
            blackhole = directSparsePower(frequencies, real, imaginary, WINDOW);

            if (gridReal != null) {
                blackhole = fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, WINDOW, fftScratch);
            }
        }

        int repetitions = count >= 1_000 ? 2 : count >= 512 ? 4 : 8;

        double[] directTimes = new double[5];
        double[] hashTimes = new double[5];
        double[] fftTimes = new double[5];

        for (int sample = 0; sample < 5; sample++) {
            if ((sample & 1) == 0) {
                directTimes[sample] = measure(() -> directSparsePower(frequencies, real, imaginary, WINDOW), repetitions);
                hashTimes[sample] = measure(() -> hashLagMeanSquare(positions, real, imaginary, BASE_HZ, 0.125, WINDOW, scratch), repetitions);
            } else {
                hashTimes[sample] = measure(() -> hashLagMeanSquare(positions, real, imaginary, BASE_HZ, 0.125, WINDOW, scratch), repetitions);
                directTimes[sample] = measure(() -> directSparsePower(frequencies, real, imaginary, WINDOW), repetitions);
            }

            if (gridReal != null) {
                fftTimes[sample] = measure(() -> fftMeanSquare(gridReal, gridImaginary, BASE_HZ, 0.125, WINDOW, fftScratch), repetitions);
            }
        }

        Arrays.sort(directTimes);
        Arrays.sort(hashTimes);
        Arrays.sort(fftTimes);

        double directUs = directTimes[2];
        double hashUs = hashTimes[2];

        String fftResult = gridReal == null ? "SKIPPED" : String.format(Locale.ROOT, "%.3f", fftTimes[2]);

        System.out.printf(Locale.ROOT, "[FREQ-SPARSE-HASH] " + "carriers=%d grid=%d random=%s " + "direct=%.3f us " + "hash=%.3f us " + "fft=%s us " + "directSpeedup=%.2fx " + "differenceKeys=%d sumKeys=%d " + "differenceCapacity=%d sumCapacity=%d%n", count, gridSize, randomGaps, directUs, hashUs, fftResult, directUs / hashUs, scratch.differences.keyCount(), scratch.sums.keyCount(), scratch.differences.capacity(), scratch.sums.capacity());
    }

    private static final class FastSincTree {
        private static final int ORDER = 20;
        private static final int LEAF_SIZE = 8;
        private static final double MAX_RATIO = 0.25;

        private final double[] frequency;
        private final double[] sine;
        private final double[] cosine;
        private final Node root;
        private final boolean adaptiveOrder;
        private final boolean bottomUpMoments;
        private boolean reuseLeafTrig;
        long lastPhaseNanos;
        long lastMomentNanos;
        long lastSelfNanos;
        long lastCrossNanos;

        private final double[] shiftPowers = new double[ORDER];
        private final double[] scalePowers = new double[ORDER];
        private final double[] pairDiffLeft = new double[ORDER];
        private final double[] pairDiffRight = new double[ORDER];
        private final double[] pairSumLeft = new double[ORDER];
        private final double[] pairSumRight = new double[ORDER];

        private long cachedWindowBits = Long.MIN_VALUE;

        private static final class Node {
            final int from;
            final int to;
            final double center;
            final double radius;

            final double[] moments = new double[4 * ORDER];

            final Node left;
            final Node right;

            Node(int from, int to, double[] frequency) {
                this.from = from;
                this.to = to;

                center = 0.5 * (frequency[from] + frequency[to - 1]);
                radius = 0.5 * (frequency[to - 1] - frequency[from]);

                if (to - from > LEAF_SIZE) {
                    int middle = (from + to) >>> 1;
                    left = new Node(from, middle, frequency);
                    right = new Node(middle, to, frequency);
                } else {
                    left = null;
                    right = null;
                }
            }
        }

        FastSincTree(double[] sortedFrequencies) {
            this(sortedFrequencies, false, false);
        }

        FastSincTree(double[] sortedFrequencies, boolean adaptiveOrder) {
            this(sortedFrequencies, adaptiveOrder, false);
        }

        FastSincTree(double[] sortedFrequencies, boolean adaptiveOrder, boolean bottomUpMoments) {
            this.adaptiveOrder = adaptiveOrder;
            this.bottomUpMoments = bottomUpMoments;

            this.frequency = sortedFrequencies;
            this.sine = new double[sortedFrequencies.length];
            this.cosine = new double[sortedFrequencies.length];

            root = new Node(0, sortedFrequencies.length, sortedFrequencies);
        }

        double meanSquare(double[] real, double[] imaginary, double windowSeconds) {
            long start = System.nanoTime();
            long windowBits = Double.doubleToLongBits(windowSeconds);

            if (windowBits != cachedWindowBits) {
                cachedWindowBits = windowBits;

                for (int i = 0; i < frequency.length; i++) {
                    double phase = FrequencyMath.phaseAdvanceRadians(frequency[i], windowSeconds * 0.5);

                    sine[i] = Math.sin(phase);
                    cosine[i] = Math.cos(phase);
                }
            }

            lastPhaseNanos = System.nanoTime() - start;
            start = System.nanoTime();
            updateMoments(root, real, imaginary);
            lastMomentNanos = System.nanoTime() - start;
            start = System.nanoTime();
            double self = 0.0;

            for (int i = 0; i < frequency.length; i++) {
                double r = real[i];
                double q = imaginary[i];

                self += 0.5 * (r * r + q * q - (r * r - q * q) * sincWindow(2.0 * frequency[i], windowSeconds));
            }

            lastSelfNanos = System.nanoTime() - start;
            start = System.nanoTime();
            double cross = 0.0;

            for (int i = 0; i < frequency.length; i++) {
                cross += visit(root, i, real, imaginary, windowSeconds);
            }

            lastCrossNanos = System.nanoTime() - start;
            return Math.max(0.0, self + 0.5 * cross);
        }

        private void addChildMoments(Node parent, Node child) {
            if (parent.radius == 0.0) {
                for (int component = 0; component < 4; component++) {
                    int offset = component * ORDER;
                    parent.moments[offset] += child.moments[offset];
                }
                return;
            }

            double shift = (child.center - parent.center) / parent.radius;
            double scale = child.radius / parent.radius;

            shiftPowers[0] = 1.0;
            scalePowers[0] = 1.0;

            for (int i = 1; i < ORDER; i++) {
                shiftPowers[i] = shiftPowers[i - 1] * shift;
                scalePowers[i] = scalePowers[i - 1] * scale;
            }

            for (int k = 0; k < ORDER; k++) {
                double binomial = 1.0;

                for (int j = 0; j <= k; j++) {
                    double weight = binomial * shiftPowers[k - j] * scalePowers[j];
                    for (int component = 0; component < 4; component++) {
                        int offset = component * ORDER;
                        parent.moments[offset + k] = Math.fma(child.moments[offset + j], weight, parent.moments[offset + k]);
                    }

                    if (j < k) {
                        binomial *= (double) (k - j) / (j + 1);
                    }
                }
            }
        }

        private void updateMoments(Node node, double[] real, double[] imaginary) {
            Arrays.fill(node.moments, 0.0);

            if (bottomUpMoments && node.left != null) {
                updateMoments(node.left, real, imaginary);
                updateMoments(node.right, real, imaginary);
                addChildMoments(node, node.left);
                addChildMoments(node, node.right);

                return;
            }

            for (int i = node.from; i < node.to; i++) {
                double coordinate = node.radius == 0.0 ? 0.0 : (frequency[i] - node.center) / node.radius;
                double power = 1.0;

                double rc = real[i] * cosine[i];
                double rs = real[i] * sine[i];
                double qc = imaginary[i] * cosine[i];
                double qs = imaginary[i] * sine[i];

                for (int k = 0; k < ORDER; k++) {
                    node.moments[k] += rc * power;
                    node.moments[ORDER + k] += rs * power;
                    node.moments[2 * ORDER + k] += qc * power;
                    node.moments[3 * ORDER + k] += qs * power;

                    power *= coordinate;
                }
            }

            if (node.left != null) {
                updateMoments(node.left, real, imaginary);
                updateMoments(node.right, real, imaginary);
            }
        }

        private static int expansionTerms(double ratio) {
            if (ratio <= 0.01) {
                return 8;
            }
            if (ratio <= 0.03) {
                return 10;
            }
            if (ratio <= 0.06) {
                return 13;
            }
            if (ratio <= 0.12) {
                return 17;
            }

            return ORDER;
        }

        private static double evaluateMoments(double[] moments, int component, double position, int terms) {
            int start = component * ORDER;
            double value = 0.0;

            for (int k = terms - 1; k >= 0; k--) {
                value = Math.fma(value, position, moments[start + k]);
            }

            return value;
        }

        private double visit(Node node, int target, double[] real, double[] imaginary, double windowSeconds) {
            double targetHz = frequency[target];

            double difference = targetHz - node.center;
            double sum = targetHz + node.center;

            boolean outside = target < node.from || target >= node.to;

            if (outside && difference != 0.0 && sum != 0.0 && node.radius <= MAX_RATIO * Math.abs(difference) && node.radius <= MAX_RATIO * Math.abs(sum)) {
                double differenceRatio = node.radius / difference;
                double sumRatio = -node.radius / sum;

                double maxRatio = Math.max(Math.abs(differenceRatio), Math.abs(sumRatio));
                int terms = adaptiveOrder ? expansionTerms(maxRatio) : ORDER;

                double drc = evaluateMoments(node.moments, 0, differenceRatio, terms) / difference;
                double drs = evaluateMoments(node.moments, 1, differenceRatio, terms) / difference;
                double dqc = evaluateMoments(node.moments, 2, differenceRatio, terms) / difference;
                double dqs = evaluateMoments(node.moments, 3, differenceRatio, terms) / difference;

                double src = evaluateMoments(node.moments, 0, sumRatio, terms) / sum;
                double srs = evaluateMoments(node.moments, 1, sumRatio, terms) / sum;
                double sqc = evaluateMoments(node.moments, 2, sumRatio, terms) / sum;
                double sqs = evaluateMoments(node.moments, 3, sumRatio, terms) / sum;

                double r = real[target];
                double q = imaginary[target];
                double si = sine[target];
                double co = cosine[target];

                double differenceContribution = r * si * drc - r * co * drs + q * si * dqc - q * co * dqs;
                double sumContribution = r * si * src + r * co * srs - q * si * sqc - q * co * sqs;

                return (differenceContribution - sumContribution) / (Math.PI * windowSeconds);
            }

            if (node.left != null) {
                return visit(node.left, target, real, imaginary, windowSeconds) + visit(node.right, target, real, imaginary, windowSeconds);
            }

            double result = 0.0;

            for (int j = node.from; j < node.to; j++) {
                if (j == target) {
                    continue;
                }

                double differenceCoefficient = Math.fma(real[target], real[j], imaginary[target] * imaginary[j]);
                double sumCoefficient = Math.fma(real[target], real[j], -imaginary[target] * imaginary[j]);

                double otherHz = frequency[j];
                double differenceHz = targetHz - otherHz;
                double sumHz = targetHz + otherHz;

                double differenceKernel;
                double sumKernel;

                if (reuseLeafTrig && windowSeconds > 0.0 && Double.isFinite(windowSeconds)) {
                    double first = sine[target] * cosine[j];
                    double second = cosine[target] * sine[j];

                    double sinDifference = first - second;
                    double sinSum = first + second;

                    double inversePiWindow = 1.0 / (Math.PI * windowSeconds);
                    double closeThreshold = 1.0e-6 * Math.max(Math.abs(targetHz), Math.abs(otherHz));

                    if (Math.abs(differenceHz) <= closeThreshold) {
                        differenceKernel = sincWindow(Math.abs(differenceHz), windowSeconds);
                    } else {
                        differenceKernel = (sinDifference / differenceHz) * inversePiWindow;
                    }

                    sumKernel = sumHz == 0.0 ? 1.0 : (sinSum / sumHz) * inversePiWindow;

                } else {
                    differenceKernel = sincWindow(Math.abs(differenceHz), windowSeconds);
                    sumKernel = sincWindow(sumHz, windowSeconds);
                }

                result += differenceCoefficient * differenceKernel;
                result -= sumCoefficient * sumKernel;
            }

            return result;
        }

        double dualTreeMeanSquare(double[] real, double[] imaginary, double windowSeconds) {
            if (!(windowSeconds > 0.0) || !Double.isFinite(windowSeconds)) {
                throw new IllegalArgumentException("Invalid window");
            }

            long windowBits = Double.doubleToLongBits(windowSeconds);

            if (windowBits != cachedWindowBits) {
                cachedWindowBits = windowBits;

                for (int i = 0; i < frequency.length; i++) {
                    double phase = FrequencyMath.phaseAdvanceRadians(frequency[i], windowSeconds * 0.5);

                    sine[i] = Math.sin(phase);
                    cosine[i] = Math.cos(phase);
                }
            }

            updateMoments(root, real, imaginary);

            double self = 0.0;

            for (int i = 0; i < frequency.length; i++) {
                double r = real[i];
                double q = imaginary[i];

                self += 0.5 * (r * r + q * q - (r * r - q * q) * sincWindow(2.0 * frequency[i], windowSeconds));
            }

            double inversePiWindow = 1.0 / (Math.PI * windowSeconds);
            double cross = dualPairNodes(root, root, real, imaginary, windowSeconds, inversePiWindow);

            return Math.max(0.0, self + cross);
        }

        private double dualPairNodes(Node a, Node b, double[] real, double[] imaginary, double windowSeconds, double inversePiWindow) {
            if (a == b) {
                if (a.left == null) {
                    return dualLeafPairs(a, b, real, imaginary, windowSeconds, inversePiWindow);
                }

                return dualPairNodes(a.left, a.left, real, imaginary, windowSeconds, inversePiWindow) + dualPairNodes(a.left, a.right, real, imaginary, windowSeconds, inversePiWindow) + dualPairNodes(a.right, a.right, real, imaginary, windowSeconds, inversePiWindow);
            }

            double difference = a.center - b.center;
            double sum = a.center + b.center;
            double radius = a.radius + b.radius;

            if (difference != 0.0 && sum != 0.0 && radius <= MAX_RATIO * Math.abs(difference) && radius <= MAX_RATIO * Math.abs(sum)) {
                return dualGroupContribution(a, b, difference, sum, radius, inversePiWindow);
            }


            if (a.left == null && b.left == null) {
                return dualLeafPairs(a, b, real, imaginary, windowSeconds, inversePiWindow);
            }

            if (b.left == null || (a.left != null && a.radius >= b.radius)) {
                return dualPairNodes(a.left, b, real, imaginary, windowSeconds, inversePiWindow) + dualPairNodes(a.right, b, real, imaginary, windowSeconds, inversePiWindow);
            }

            return dualPairNodes(a, b.left, real, imaginary, windowSeconds, inversePiWindow) + dualPairNodes(a, b.right, real, imaginary, windowSeconds, inversePiWindow);
        }

        private double dualGroupContribution(Node left, Node right, double difference, double sum, double combinedRadius, double inversePiWindow) {
            double ratio = Math.max(combinedRadius / Math.abs(difference), combinedRadius / Math.abs(sum));

            int terms = expansionTerms(ratio);

            double differenceLeftRatio = -left.radius / difference;
            double differenceRightRatio = right.radius / difference;

            double sumLeftRatio = -left.radius / sum;
            double sumRightRatio = -right.radius / sum;

            pairDiffLeft[0] = 1.0;
            pairDiffRight[0] = 1.0;
            pairSumLeft[0] = 1.0;
            pairSumRight[0] = 1.0;

            for (int k = 1; k < terms; k++) {
                pairDiffLeft[k] = pairDiffLeft[k - 1] * differenceLeftRatio;
                pairDiffRight[k] = pairDiffRight[k - 1] * differenceRightRatio;

                pairSumLeft[k] = pairSumLeft[k - 1] * sumLeftRatio;
                pairSumRight[k] = pairSumRight[k - 1] * sumRightRatio;
            }

            double[] a = left.moments;
            double[] b = right.moments;

            double differenceTotal = 0.0;
            double sumTotal = 0.0;

            for (int k = 0; k < terms; k++) {
                double binomial = 1.0;

                for (int p = 0; p <= k; p++) {
                    int q = k - p;

                    double differenceNumerator = a[ORDER + p] * b[q] - a[p] * b[ORDER + q] + a[3 * ORDER + p] * b[2 * ORDER + q] - a[2 * ORDER + p] * b[3 * ORDER + q];
                    double sumNumerator = a[ORDER + p] * b[q] + a[p] * b[ORDER + q] - a[3 * ORDER + p] * b[2 * ORDER + q] - a[2 * ORDER + p] * b[3 * ORDER + q];

                    double differenceWeight = binomial * pairDiffLeft[p] * pairDiffRight[q];
                    double sumWeight = binomial * pairSumLeft[p] * pairSumRight[q];

                    differenceTotal = Math.fma(differenceWeight, differenceNumerator, differenceTotal);
                    sumTotal = Math.fma(sumWeight, sumNumerator, sumTotal);

                    if (p < k) {
                        binomial *= (double) (k - p) / (p + 1);
                    }
                }
            }

            return (differenceTotal / difference - sumTotal / sum) * inversePiWindow;
        }

        private double dualLeafPairs(Node a, Node b, double[] real, double[] imaginary, double windowSeconds, double inversePiWindow) {
            double result = 0.0;

            if (a == b) {
                for (int i = a.from; i < a.to; i++) {
                    for (int j = i + 1; j < a.to; j++) {
                        result += dualPairContribution(i, j, real, imaginary, windowSeconds, inversePiWindow);
                    }
                }
            } else {
                for (int i = a.from; i < a.to; i++) {
                    for (int j = b.from; j < b.to; j++) {
                        result += dualPairContribution(i, j, real, imaginary, windowSeconds, inversePiWindow);
                    }
                }
            }

            return result;
        }

        private double dualPairContribution(int i, int j, double[] real, double[] imaginary, double windowSeconds, double inversePiWindow) {
            double differenceHz = frequency[i] - frequency[j];
            double sumHz = frequency[i] + frequency[j];

            double first = sine[i] * cosine[j];
            double second = cosine[i] * sine[j];

            double closeThreshold = 1.0e-6 * Math.max(Math.abs(frequency[i]), Math.abs(frequency[j]));

            double differenceKernel;

            if (Math.abs(differenceHz) <= closeThreshold) {
                differenceKernel = sincWindow(Math.abs(differenceHz), windowSeconds);
            } else {
                differenceKernel = ((first - second) / differenceHz) * inversePiWindow;
            }

            double sumKernel = sumHz == 0.0 ? 1.0 : ((first + second) / sumHz) * inversePiWindow;
            double differenceCoefficient = Math.fma(real[i], real[j], imaginary[i] * imaginary[j]);
            double sumCoefficient = Math.fma(real[i], real[j], -imaginary[i] * imaginary[j]);

            return differenceCoefficient * differenceKernel - sumCoefficient * sumKernel;
        }
    }


    @Test
    void benchmarkEverydayFrequenciesAgainstAllSolvers() {
        runEverydayFrequencyCase(128, 100_000, false);
        runEverydayFrequencyCase(256, 100_000, false);
        runEverydayFrequencyCase(512, 100_000, false);
        runEverydayFrequencyCase(128, 500, true);
        runEverydayFrequencyCase(256, 500, true);
    }

    private static void runEverydayFrequencyCase(int count, int stepHz, boolean preciseChannels) {
        final long minimumHz = 30_000_000L;
        final long maximumHz = 6_000_000_000L;

        Random random = new Random(0x31B001L + count * 131L + stepHz);

        int[] positions = new int[count];

        int[][] bandsMHz = {
                {30, 54},
                {88, 108},
                {118, 137},
                {144, 148},
                {156, 163},
                {400, 470},
                {700, 960},
                {1070, 1110},
                {1170, 1610},
                {1700, 2100},
                {2300, 2500},
                {3300, 3800},
                {4900, 5900},
                {5900, 6000}
        };

        long[] channelCentersHz = {
                30_000_000L,
                88_100_000L,
                98_700_000L,
                121_500_000L,
                144_390_000L,
                156_800_000L,
                162_400_000L,
                433_920_000L,
                462_562_500L,
                915_000_000L,
                1_090_000_000L,
                1_575_420_000L,
                1_760_000_000L,
                2_412_000_000L,
                2_437_000_000L,
                2_462_000_000L,
                3_500_000_000L,
                5_180_000_000L,
                5_500_000_000L,
                5_805_000_000L,
                6_000_000_000L
        };

        positions[0] = 0;
        positions[count - 1] = (int) ((maximumHz - minimumHz) / stepHz);

        for (int i = 1; i < count - 1; i++) {
            long frequencyHz;

            if (preciseChannels) {
                long center = channelCentersHz[i % channelCentersHz.length];
                long offset = (random.nextInt(801) - 400) * 500L;

                frequencyHz = Math.max(minimumHz, Math.min(maximumHz, center + offset));
            } else {
                int[] band = bandsMHz[i % bandsMHz.length];

                long low = band[0] * 1_000_000L;
                long high = band[1] * 1_000_000L;

                int slots = (int) ((high - low) / stepHz);

                frequencyHz = low + (long) random.nextInt(slots + 1) * stepHz;
            }

            positions[i] = (int) ((frequencyHz - minimumHz) / stepHz);
        }

        Arrays.sort(positions);

        double[] frequencies = new double[count];
        double[] real = new double[count];
        double[] imaginary = new double[count];

        int gridSize = positions[count - 1] + 1;

        for (int i = 0; i < count; i++) {
            frequencies[i] = minimumHz + (double) stepHz * positions[i];

            double amplitude = 0.25 + 1.5 * random.nextDouble();
            double phase = 2.0 * Math.PI * random.nextDouble();

            real[i] = amplitude * Math.cos(phase);
            imaginary[i] = amplitude * Math.sin(phase);
        }

        FastSincTree fastSinc = new FastSincTree(frequencies);
        HashLagScratch hashScratch = new HashLagScratch();

        boolean compactGrid = gridSize <= 65_536;

        SparseLagScratch sparseScratch = compactGrid ? new SparseLagScratch(gridSize) : null;
        FftScratch fftScratch = compactGrid ? new FftScratch() : null;

        double[] gridReal = null;
        double[] gridImaginary = null;

        if (compactGrid) {
            gridReal = new double[gridSize];
            gridImaginary = new double[gridSize];

            for (int i = 0; i < count; i++) {
                gridReal[positions[i]] += real[i];
                gridImaginary[positions[i]] += imaginary[i];
            }
        }

        final double[] fftReal = gridReal;
        final double[] fftImaginary = gridImaginary;
        for (double window : new double[]{0.0130137, 0.25}) {
            double expected = directSparsePower(frequencies, real, imaginary, window);
            double tolerance = 2.0e-9 * Math.max(1.0, Math.abs(expected));
            double fast = fastSinc.meanSquare(real, imaginary, window);
            double hash = hashLagMeanSquare(positions, real, imaginary, minimumHz, stepHz, window, hashScratch);

            assertTrue(Double.isFinite(fast));
            assertTrue(Double.isFinite(hash));

            assertEquals(expected, fast, tolerance, "Fast sinc mismatch count=" + count + " step=" + stepHz + " window=" + window);
            assertEquals(expected, hash, tolerance, "Hash mismatch count=" + count + " step=" + stepHz + " window=" + window);

            if (compactGrid) {
                double array = sparseLagMeanSquare(positions, real, imaginary, minimumHz, stepHz, window, sparseScratch);
                double fft = fftMeanSquare(fftReal, fftImaginary, minimumHz, stepHz, window, fftScratch);

                assertEquals(expected, array, tolerance, "Array sparse mismatch");
                assertEquals(expected, fft, tolerance, "FFT mismatch");
            }

            System.out.printf(Locale.ROOT, "[FREQ-REAL-ACCURACY] carriers=%d " + "stepHz=%d window=%.7f " + "fastError=%.3e hashError=%.3e%n", count, stepHz, window, Math.abs(fast - expected), Math.abs(hash - expected));
        }

        final double window = 0.0130137;
        int repetitions = count >= 512 ? 2 : count >= 256 ? 3 : 6;

        for (int i = 0; i < 3; i++) {
            blackhole = directSparsePower(frequencies, real, imaginary, window);
            blackhole = fastSinc.meanSquare(real, imaginary, window);
            blackhole = hashLagMeanSquare(positions, real, imaginary, minimumHz, stepHz, window, hashScratch);

            if (compactGrid) {
                blackhole = sparseLagMeanSquare(positions, real, imaginary, minimumHz, stepHz, window, sparseScratch);
                blackhole = fftMeanSquare(fftReal, fftImaginary, minimumHz, stepHz, window, fftScratch);
            }
        }

        double[] directTimes = new double[5];
        double[] fastTimes = new double[5];
        double[] hashTimes = new double[5];

        double[] arrayTimes = compactGrid ? new double[5] : null;
        double[] fftTimes = compactGrid ? new double[5] : null;

        for (int sample = 0; sample < 5; sample++) {
            directTimes[sample] = measure(() -> directSparsePower(frequencies, real, imaginary, window), repetitions);
            fastTimes[sample] = measure(() -> fastSinc.meanSquare(real, imaginary, window), repetitions);
            hashTimes[sample] = measure(() -> hashLagMeanSquare(positions, real, imaginary, minimumHz, stepHz, window, hashScratch), repetitions);

            if (compactGrid) {
                arrayTimes[sample] = measure(() -> sparseLagMeanSquare(positions, real, imaginary, minimumHz, stepHz, window, sparseScratch), repetitions);
                fftTimes[sample] = measure(() -> fftMeanSquare(fftReal, fftImaginary, minimumHz, stepHz, window, fftScratch), repetitions);
            }
        }

        Arrays.sort(directTimes);
        Arrays.sort(fastTimes);
        Arrays.sort(hashTimes);

        if (compactGrid) {
            Arrays.sort(arrayTimes);
            Arrays.sort(fftTimes);
        }

        String arrayText = compactGrid ? String.format(Locale.ROOT, "%.3f", arrayTimes[2]) : "SKIP";
        String fftText = compactGrid ? String.format(Locale.ROOT, "%.3f", fftTimes[2]) : "SKIP";

        System.out.printf(Locale.ROOT, "[FREQ-REAL-BENCH] " + "carriers=%d stepHz=%d " + "grid=%d precise=%s " + "direct=%.3f us " + "fft=%s us " + "array=%s us " + "hash=%.3f us " + "fastSinc=%.3f us%n", count, stepHz, gridSize, preciseChannels, directTimes[2], fftText, arrayText, hashTimes[2], fastTimes[2]);
    }


    @Test
    void benchmark50kEverydayRadioSpectrum() {
        for (int stepHz : new int[]{100_000, 500}) {
            final int radioCount = 50_000;
            final long minimumHz = 30_000_000L;
            final long maximumHz = 6_000_000_000L;
            final double window = 0.0130137;

            int[][] bandsMHz = {
                    {30, 54}, {88, 108}, {118, 137},
                    {144, 148}, {156, 163}, {400, 470},
                    {700, 960}, {1070, 1110},
                    {1170, 1610}, {1700, 2100},
                    {2300, 2500}, {3300, 3800},
                    {4900, 5900}, {5900, 6000}
            };

            Random random = new Random(0x31B050L + stepHz);

            long[] ordered = new long[radioCount];
            double[] rawReal = new double[radioCount];
            double[] rawImaginary = new double[radioCount];

            int gridSize = (int) ((maximumHz - minimumHz) / stepHz) + 1;

            for (int i = 0; i < radioCount; i++) {
                int position;

                if (i == 0) {
                    position = 0;
                } else if (i == radioCount - 1) {
                    position = gridSize - 1;
                } else {
                    int[] band = bandsMHz[i % bandsMHz.length];

                    long lowHz = band[0] * 1_000_000L;
                    long highHz = band[1] * 1_000_000L;

                    int low = (int) ((lowHz - minimumHz) / stepHz);
                    int high = (int) ((highHz - minimumHz) / stepHz);

                    position = low + random.nextInt(high - low + 1);
                }

                double amplitude = 0.25 + 1.5 * random.nextDouble();
                double phase = 2.0 * Math.PI * random.nextDouble();

                rawReal[i] = amplitude * Math.cos(phase);
                rawImaginary[i] = amplitude * Math.sin(phase);

                ordered[i] = ((long) position << 32) | (i & 0xffffffffL);
            }

            Arrays.sort(ordered);

            int[] positions = new int[radioCount];
            double[] sortedReal = new double[radioCount];
            double[] sortedImaginary = new double[radioCount];

            int unique = 0;

            for (long entry : ordered) {
                int position = (int) (entry >>> 32);
                int original = (int) entry;

                if (unique > 0 && positions[unique - 1] == position) {
                    sortedReal[unique - 1] += rawReal[original];
                    sortedImaginary[unique - 1] += rawImaginary[original];
                } else {
                    positions[unique] = position;
                    sortedReal[unique] = rawReal[original];
                    sortedImaginary[unique] = rawImaginary[original];
                    unique++;
                }
            }

            double[] frequencies = new double[unique];
            double[] real = Arrays.copyOf(sortedReal, unique);
            double[] imaginary = Arrays.copyOf(sortedImaginary, unique);

            for (int i = 0; i < unique; i++) {
                frequencies[i] = minimumHz + (double) stepHz * positions[i];
            }

            int referenceCount = Math.min(unique, 1_024);

            double[] referenceFrequencies = Arrays.copyOf(frequencies, referenceCount);
            double[] referenceReal = Arrays.copyOf(real, referenceCount);
            double[] referenceImaginary = Arrays.copyOf(imaginary, referenceCount);

            FastSincTree referenceTree = new FastSincTree(referenceFrequencies);
            FastSincTree cachedLeafReferenceTree = new FastSincTree(referenceFrequencies, true, true);
            cachedLeafReferenceTree.reuseLeafTrig = true;
            FastSincTree dualReferenceTree = new FastSincTree(referenceFrequencies, true, true);
            FastSincTree bottomUpReferenceTree = new FastSincTree(referenceFrequencies, true, true);

            for (double referenceWindow : new double[]{0.0130137, 0.25}) {
                double expected = directSparsePower(referenceFrequencies, referenceReal, referenceImaginary, referenceWindow);
                double actual = referenceTree.meanSquare(referenceReal, referenceImaginary, referenceWindow);
                assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Fast-sinc reference mismatch");

                double bottomUpReference = bottomUpReferenceTree.meanSquare(referenceReal, referenceImaginary, referenceWindow);
                assertTrue(Double.isFinite(bottomUpReference));
                assertEquals(expected, bottomUpReference, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Bottom-up moments failed exact-reference test");

                double cachedReference = cachedLeafReferenceTree.meanSquare(referenceReal, referenceImaginary, referenceWindow);
                assertTrue(Double.isFinite(cachedReference));
                assertEquals(expected, cachedReference, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Cached trigonometry failed exact analytical reference");

                double dualReference = dualReferenceTree.dualTreeMeanSquare(referenceReal, referenceImaginary, referenceWindow);
                assertTrue(Double.isFinite(dualReference));
                assertEquals(expected, dualReference, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Dual-tree failed exact analytical reference");
            }

            long buildStart = System.nanoTime();
            FastSincTree tree = new FastSincTree(frequencies);
            FastSincTree adaptiveTree = new FastSincTree(frequencies, true);
            FastSincTree bottomUpTree = new FastSincTree(frequencies, true, true);
            FastSincTree cachedLeafTree = new FastSincTree(frequencies, true, true);
            cachedLeafTree.reuseLeafTrig = true;
            FastSincTree dualTree = new FastSincTree(frequencies, true, true);
            double buildMs = (System.nanoTime() - buildStart) / 1_000_000.0;

            double[] gridReal = null;
            double[] gridImaginary = null;
            FftScratch fftScratch = null;

            if (gridSize <= 65_536) {
                gridReal = new double[gridSize];
                gridImaginary = new double[gridSize];

                for (int i = 0; i < unique; i++) {
                    gridReal[positions[i]] = real[i];
                    gridImaginary[positions[i]] = imaginary[i];
                }

                fftScratch = new FftScratch();
            }

            for (int i = 0; i < 2; i++) {
                blackhole = tree.meanSquare(real, imaginary, window);
                blackhole = adaptiveTree.meanSquare(real, imaginary, window);
                blackhole = bottomUpTree.meanSquare(real, imaginary, window);
                blackhole = cachedLeafTree.meanSquare(real, imaginary, window);
                blackhole = dualTree.dualTreeMeanSquare(real, imaginary, window);
                if (fftScratch != null) {
                    blackhole = fftMeanSquare(gridReal, gridImaginary, minimumHz, stepHz, window, fftScratch);
                }
            }

            long[] fastTimes = new long[3];
            long[] fftTimes = new long[3];

            long[] adaptiveTimes = new long[3];
            double adaptiveResult = 0.0;

            long[] bottomUpTimes = new long[3];
            double bottomUpResult = 0.0;

            long[] cachedLeafTimes = new long[3];
            double cachedLeafResult = 0.0;

            long[] stagePhaseTimes = new long[3];
            long[] stageMomentTimes = new long[3];
            long[] stageSelfTimes = new long[3];
            long[] stageCrossTimes = new long[3];

            double fastResult = 0.0;
            double fftResult = 0.0;

            long[] dualTimes = new long[3];
            double dualResult = 0.0;

            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();

                fastResult = tree.meanSquare(real, imaginary, window);

                fastTimes[i] = System.nanoTime() - start;
                blackhole = fastResult;

                start = System.nanoTime();
                adaptiveResult = adaptiveTree.meanSquare(real, imaginary, window);
                adaptiveTimes[i] = System.nanoTime() - start;
                blackhole = adaptiveResult;

                start = System.nanoTime();
                bottomUpResult = bottomUpTree.meanSquare(real, imaginary, window);

                bottomUpTimes[i] = System.nanoTime() - start;
                blackhole = bottomUpResult;

                start = System.nanoTime();
                cachedLeafResult = cachedLeafTree.meanSquare(real, imaginary, window);
                cachedLeafTimes[i] = System.nanoTime() - start;
                blackhole = cachedLeafResult;

                start = System.nanoTime();
                dualResult = dualTree.dualTreeMeanSquare(real, imaginary, window);
                dualTimes[i] = System.nanoTime() - start;
                blackhole = dualResult;

                stagePhaseTimes[i] = bottomUpTree.lastPhaseNanos;
                stageMomentTimes[i] = bottomUpTree.lastMomentNanos;
                stageSelfTimes[i] = bottomUpTree.lastSelfNanos;
                stageCrossTimes[i] = bottomUpTree.lastCrossNanos;

                if (fftScratch != null) {
                    start = System.nanoTime();

                    fftResult = fftMeanSquare(gridReal, gridImaginary, minimumHz, stepHz, window, fftScratch);

                    fftTimes[i] = System.nanoTime() - start;
                    blackhole = fftResult;
                }
            }

            Arrays.sort(fastTimes);
            Arrays.sort(fftTimes);
            Arrays.sort(adaptiveTimes);
            Arrays.sort(bottomUpTimes);
            Arrays.sort(cachedLeafTimes);
            Arrays.sort(stagePhaseTimes);
            Arrays.sort(stageMomentTimes);
            Arrays.sort(stageSelfTimes);
            Arrays.sort(stageCrossTimes);
            Arrays.sort(dualTimes);

            assertTrue(Double.isFinite(fastResult));
            assertTrue(fastResult >= 0.0);

            if (fftScratch != null) {
                assertEquals(fftResult, fastResult, 2.0e-8 * Math.max(1.0, Math.abs(fftResult)), "Full-spectrum FFT/fast-sinc mismatch");
            }

            String fftText = fftScratch == null ? "SKIPPED" : String.format(Locale.ROOT, "%.3f", fftTimes[1] / 1_000_000.0);

            System.out.printf(Locale.ROOT, "[FREQ-REAL-50K] " + "radios=%d carriers=%d " + "stepHz=%d grid=%d " + "build=%.3f ms " + "fastSinc=%.3f ms " + "fft=%s ms " + "result=%.8f%n", radioCount, unique, stepHz, gridSize, buildMs, fastTimes[1] / 1_000_000.0, fftText, fastResult);
            double tolerance = 2.0e-9 * Math.max(1.0, Math.abs(fastResult));

            assertTrue(Double.isFinite(adaptiveResult));
            assertEquals(fastResult, adaptiveResult, tolerance, "Adaptive fast-sinc differs from baseline");

            double baselineMs = fastTimes[1] / 1_000_000.0;
            double adaptiveMs = adaptiveTimes[1] / 1_000_000.0;

            System.out.printf(Locale.ROOT, "[FREQ-FASTSINC-ADAPTIVE] " + "radios=%d carriers=%d " + "stepHz=%d " + "baseline=%.3f ms " + "optimized=%.3f ms " + "speedup=%.2fx " + "error=%.3e%n", radioCount, unique, stepHz, baselineMs, adaptiveMs, baselineMs / adaptiveMs, Math.abs(fastResult - adaptiveResult));

            assertTrue(Double.isFinite(bottomUpResult));
            assertEquals(adaptiveResult, bottomUpResult, 2.0e-9 * Math.max(1.0, Math.abs(adaptiveResult)), "Bottom-up moments differ from adaptive baseline");

            double bottomUpMs = bottomUpTimes[1] / 1_000_000.0;

            System.out.printf(Locale.ROOT, "[FREQ-FASTSINC-BOTTOMUP] " + "radios=%d carriers=%d stepHz=%d " + "adaptive=%.3f ms " + "bottomUp=%.3f ms " + "speedup=%.2fx " + "error=%.3e%n", radioCount, unique, stepHz, adaptiveMs, bottomUpMs, adaptiveMs / bottomUpMs, Math.abs(adaptiveResult - bottomUpResult));
            System.out.printf(Locale.ROOT, "[FREQ-FASTSINC-PROFILE] " + "carriers=%d stepHz=%d " + "phase=%.3f ms " + "moments=%.3f ms " + "self=%.3f ms " + "cross=%.3f ms " + "total=%.3f ms%n", unique, stepHz, stagePhaseTimes[1] / 1_000_000.0, stageMomentTimes[1] / 1_000_000.0, stageSelfTimes[1] / 1_000_000.0, stageCrossTimes[1] / 1_000_000.0, bottomUpTimes[1] / 1_000_000.0);

            assertTrue(Double.isFinite(cachedLeafResult), "Cached trigonometry returned a non-finite result");
            assertEquals(bottomUpResult, cachedLeafResult, 2.0e-9 * Math.max(1.0, Math.abs(bottomUpResult)), "Cached trigonometry differs from bottom-up baseline");
            double cachedLeafMs = cachedLeafTimes[1] / 1_000_000.0;

            System.out.printf(Locale.ROOT, "[FREQ-FASTSINC-LEAF-TRIG] " + "carriers=%d stepHz=%d " + "original=%.3f ms " + "optimized=%.3f ms " + "speedup=%.2fx " + "error=%.3e " + "cross=%.3f ms%n", unique, stepHz, bottomUpMs, cachedLeafMs, bottomUpMs / cachedLeafMs, Math.abs(bottomUpResult - cachedLeafResult), cachedLeafTree.lastCrossNanos / 1_000_000.0);


            assertTrue(Double.isFinite(dualResult));
            assertEquals(cachedLeafResult, dualResult, 2.0e-9 * Math.max(1.0, Math.abs(cachedLeafResult)), "Dual-tree differs from cached-leaf baseline");

            if (fftScratch != null) {
                assertEquals(fftResult, dualResult, 2.0e-8 * Math.max(1.0, Math.abs(fftResult)), "Dual-tree differs from full-spectrum FFT");
            }

            double dualMs = dualTimes[1] / 1_000_000.0;

            System.out.printf(Locale.ROOT, "[FREQ-DUAL-TREE] " + "radios=%d carriers=%d stepHz=%d " + "cachedLeaf=%.3f ms " + "dualTree=%.3f ms " + "speedup=%.2fx " + "error=%.3e%n", radioCount, unique, stepHz, cachedLeafMs, dualMs, cachedLeafMs / dualMs, Math.abs(cachedLeafResult - dualResult));
            com.sun.management.ThreadMXBean allocationBean = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
            if (allocationBean != null) {
                if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                    allocationBean.setThreadAllocatedMemoryEnabled(true);
                }

                long threadId = Thread.currentThread().threadId();
                for (int i = 0; i < 6; i++) {
                    real[0] += (i & 1) == 0 ? 0.00001 : -0.00001;
                    blackhole = dualTree.dualTreeMeanSquare(real, imaginary, window);
                }

                final int repetitions = 12;

                long bytesBefore = allocationBean.getThreadAllocatedBytes(threadId);
                long startNanos = System.nanoTime();

                for (int i = 0; i < repetitions; i++) {

                    real[0] += (i & 1) == 0 ? 0.00001 : -0.00001;

                    blackhole = dualTree.dualTreeMeanSquare(real, imaginary, window);
                }

                long elapsedNanos = System.nanoTime() - startNanos;
                long bytesAfter = allocationBean.getThreadAllocatedBytes(threadId);

                double allocatedPerQuery = (bytesAfter - bytesBefore) / (double) repetitions;
                double millisecondsPerQuery = elapsedNanos / (repetitions * 1_000_000.0);

                System.out.printf(Locale.ROOT, "[FREQ-DUAL-MEMORY] " + "carriers=%d stepHz=%d " + "allocated=%.1f bytes/query " + "time=%.3f ms/query%n", unique, stepHz, allocatedPerQuery, millisecondsPerQuery);
            } else {
                System.out.println("[FREQ-DUAL-MEMORY] " + "Thread allocation measurement unavailable");
            }
        }
    }


    @Test
    void cachedLeafTrigHandlesCloseFrequencies() {
        double[] frequencies = {
                915_000_000.0,
                915_000_500.0,
                2_400_000_000.0,
                2_400_000_500.0,
                6_000_000_000.0
        };

        double[] real = {
                1.0, -0.7, 0.5, 0.8, -0.3
        };

        double[] imaginary = {
                0.2, 0.4, -0.6, 0.1, 0.9
        };

        FastSincTree tree = new FastSincTree(frequencies, true, true);

        tree.reuseLeafTrig = true;

        for (double window : new double[]{0.0130137, 0.25}) {
            double expected = directSparsePower(frequencies, real, imaginary, window);
            double actual = tree.meanSquare(real, imaginary, window);

            assertTrue(Double.isFinite(actual));

            assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Cached leaf trigonometry mismatch");
        }
    }

    @Test
    void dualTreeMatchesDirectAcrossRadioSpectrum() {
        Random random = new Random(0x31D00A1L);

        for (int count : new int[]{64, 256, 1_024}) {
            double[] frequencies = new double[count];
            double[] real = new double[count];
            double[] imaginary = new double[count];

            for (int i = 0; i < count; i++) {
                double hz = 30_000_000.0 + random.nextDouble() * 5_970_000_000.0;
                if (i > 0 && i % 8 == 0) {
                    hz = frequencies[i - 1] + 0.125;
                }

                frequencies[i] = hz;
                double scale = 0.2 + random.nextDouble();

                real[i] = (i & 1) == 0 ? scale : -scale;
                imaginary[i] = random.nextGaussian() * 0.2;
            }

            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
            }

            Arrays.sort(order, (a, b) -> Double.compare(frequencies[a], frequencies[b]));

            double[] sortedHz = new double[count];
            double[] sortedReal = new double[count];
            double[] sortedImaginary = new double[count];

            for (int i = 0; i < count; i++) {
                int source = order[i];
                sortedHz[i] = frequencies[source];
                sortedReal[i] = real[source];
                sortedImaginary[i] = imaginary[source];
            }

            FastSincTree dualTree = new FastSincTree(sortedHz, true, true);

            for (double window : new double[]{0.000001, 0.0130137, 0.25}) {
                double expected = directSparsePower(sortedHz, sortedReal, sortedImaginary, window);

                double actual = dualTree.dualTreeMeanSquare(sortedReal, sortedImaginary, window);
                double tolerance = 2.0e-9 * Math.max(1.0, Math.abs(expected));

                assertTrue(Double.isFinite(actual));
                assertEquals(expected, actual, tolerance, "Dual-tree mismatch count=" + count + " window=" + window);
                System.out.printf(Locale.ROOT, "[FREQ-DUAL-ACCURACY] " + "carriers=%d window=%.7f " + "error=%.3e%n", count, window, Math.abs(expected - actual));
            }
        }
    }

    @Test
    void dualTreeHandlesCancellationAndSignalUpdates() {
        double[] frequencies = {
                30_000_000.0,
                30_000_000.0,
                915_000_000.0,
                915_000_000.0,
                915_000_000.125,
                915_000_000.25,
                2_400_000_000.0,
                2_400_000_000.125,
                6_000_000_000.0,
                6_000_000_000.0
        };

        double[] real = {
                1.0, -1.0,
                0.9, -0.9,
                0.4, -0.3,
                0.7, -0.7,
                0.2, -0.2
        };

        double[] imaginary = {
                0.4, -0.4,
                0.2, -0.2,
                0.8, -0.5,
                0.3, -0.3,
                0.6, -0.6
        };

        FastSincTree tree = new FastSincTree(frequencies, true, true);
        for (int update = 0; update < 3; update++) {
            for (double window : new double[]{1.0e-9, 1.0e-6, 0.0130137, 0.25}) {
                double expected = directSparsePower(frequencies, real, imaginary, window);
                double actual = tree.dualTreeMeanSquare(real, imaginary, window);

                assertTrue(Double.isFinite(actual));
                assertTrue(actual >= 0.0);

                double tolerance = 2.0e-9 * Math.max(1.0, Math.abs(expected));
                assertEquals(expected, actual, tolerance, "Dual-tree mismatch update=" + update + " window=" + window);

                System.out.printf(Locale.ROOT, "[FREQ-DUAL-UPDATE] " + "update=%d window=%.9g " + "error=%.3e%n", update, window, Math.abs(expected - actual));
            }

            real[2] += 0.03125;
            imaginary[6] -= 0.015625;
        }

        Arrays.fill(real, 0.0);
        Arrays.fill(imaginary, 0.0);

        assertEquals(0.0, tree.dualTreeMeanSquare(real, imaginary, 0.0130137), 1.0e-12, "Old signal data survived a zero-amplitude update");
    }

    @Test
    void benchmarkDualTreeLayoutsAndDopplerRebuild() {
        final double window = 0.0130137;

        int[] counts = {50_000, 50_000, 4_096};
        String[] names = {"wide", "single-band", "sub-hertz"};

        for (int layout = 0; layout < counts.length; layout++) {
            int count = counts[layout];
            Random random = new Random(0x31D00B8L + layout);

            double[] frequencies = new double[count];
            double[] real = new double[count];
            double[] imaginary = new double[count];

            for (int i = 0; i < count; i++) {
                frequencies[i] = switch (layout) {
                    case 0 -> 30_000_000.0 + 5_970_000_000.0 * i / (count - 1);
                    case 1 -> 915_000_000.0 + 500.0 * i;
                    default -> 915_000_000.0 + 0.125 * i;
                };

                double amplitude = 0.25 + 1.5 * random.nextDouble();
                double phase = 2.0 * Math.PI * random.nextDouble();

                real[i] = amplitude * Math.cos(phase);
                imaginary[i] = amplitude * Math.sin(phase);
            }

            long start = System.nanoTime();
            FastSincTree tree = new FastSincTree(frequencies, true, true);
            double buildMs = (System.nanoTime() - start) / 1_000_000.0;

            for (int i = 0; i < 3; i++) {
                blackhole = tree.dualTreeMeanSquare(real, imaginary, window);
            }

            long[] times = new long[5];
            double result = 0.0;
            for (int i = 0; i < times.length; i++) {
                start = System.nanoTime();
                result = tree.dualTreeMeanSquare(real, imaginary, window);

                times[i] = System.nanoTime() - start;
                blackhole = result;
            }

            Arrays.sort(times);

            double[] shiftedFrequencies = frequencies.clone();
            for (int i = 0; i < count; i++) {
                shiftedFrequencies[i] += 3.0 + i * 1.0e-5;
            }

            start = System.nanoTime();
            FastSincTree shiftedTree = new FastSincTree(shiftedFrequencies, true, true);

            double rebuildMs = (System.nanoTime() - start) / 1_000_000.0;
            double shiftedResult = shiftedTree.dualTreeMeanSquare(real, imaginary, window);

            assertTrue(Double.isFinite(result));
            assertTrue(Double.isFinite(shiftedResult));
            assertTrue(result >= 0.0);
            assertTrue(shiftedResult >= 0.0);

            int referenceCount = Math.min(256, count);

            double[] referenceReal = Arrays.copyOf(real, referenceCount);
            double[] referenceImaginary = Arrays.copyOf(imaginary, referenceCount);

            for (double[] sourceFrequencies : new double[][]{frequencies, shiftedFrequencies}) {
                double[] referenceFrequencies = Arrays.copyOf(sourceFrequencies, referenceCount);
                double expected = directSparsePower(referenceFrequencies, referenceReal, referenceImaginary, window);

                FastSincTree referenceTree = new FastSincTree(referenceFrequencies, true, true);
                double actual = referenceTree.dualTreeMeanSquare(referenceReal, referenceImaginary, window);

                assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Dual-tree distribution/Doppler mismatch");
            }

            System.out.printf(Locale.ROOT, "[FREQ-DUAL-LAYOUT] " + "layout=%s carriers=%d " + "build=%.3f ms " + "query=%.3f ms " + "rebuild=%.3f ms " + "result=%.8f " + "shifted=%.8f%n", names[layout], count, buildMs, times[2] / 1_000_000.0, rebuildMs, result, shiftedResult);
        }
    }

    @Test
    void dualTreeHandlesDuplicateMergingAndDopplerReordering() {
        Random random = new Random(0x31D00B9L);

        int count = 1_024;
        double[] frequencies = new double[count];
        double[] real = new double[count];
        double[] imaginary = new double[count];

        for (int i = 0; i < count; i++) {
            frequencies[i] = 915_000_000.0 + (i / 8) * 0.125;

            double amplitude = 0.1 + random.nextDouble();
            double phase = random.nextDouble() * 2.0 * Math.PI;

            real[i] = amplitude * Math.cos(phase);
            imaginary[i] = amplitude * Math.sin(phase);
        }

        double[] mergedHz = new double[count];
        double[] mergedReal = new double[count];
        double[] mergedImaginary = new double[count];
        int unique = 0;

        for (int i = 0; i < count; i++) {
            if (unique > 0
                    && mergedHz[unique - 1] == frequencies[i]) {
                mergedReal[unique - 1] += real[i];
                mergedImaginary[unique - 1] += imaginary[i];
            } else {
                mergedHz[unique] = frequencies[i];
                mergedReal[unique] = real[i];
                mergedImaginary[unique] = imaginary[i];
                unique++;
            }
        }

        assertEquals(128, unique);

        double[] compactHz = Arrays.copyOf(mergedHz, unique);
        double[] compactReal = Arrays.copyOf(mergedReal, unique);
        double[] compactImaginary = Arrays.copyOf(mergedImaginary, unique);

        FastSincTree mergedTree = new FastSincTree(compactHz, true, true);

        for (double window : new double[]{1.0e-6, 0.0130137, 0.25}) {
            double expected = directSparsePower(frequencies, real, imaginary, window);
            double actual = mergedTree.dualTreeMeanSquare(compactReal, compactImaginary, window);

            assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Coherent duplicate merging changed signal power");
        }

        double[] originalHz = {
                915_000_000.0,
                915_000_000.25,
                915_000_000.5,
                915_000_000.75,
                2_400_000_000.125,
                2_400_000_000.25
        };

        double[] shifts = {
                0.6, -0.2, 0.0, -0.3, 0.5, -0.5
        };

        double[] originalReal = {
                1.0, -0.7, 0.2, 0.9, -0.3, 0.4
        };

        double[] originalImaginary = {
                0.2, 0.5, -0.6, 0.1, 0.8, -0.4
        };

        int n = originalHz.length;
        double[] shiftedHz = new double[n];

        Integer[] order = new Integer[n];

        for (int i = 0; i < n; i++) {
            shiftedHz[i] = originalHz[i] + shifts[i];
            order[i] = i;
        }

        Arrays.sort(order, (a, b) -> Double.compare(shiftedHz[a], shiftedHz[b]));

        double[] sortedHz = new double[n];
        double[] sortedReal = new double[n];
        double[] sortedImaginary = new double[n];

        for (int i = 0; i < n; i++) {
            int source = order[i];

            sortedHz[i] = shiftedHz[source];
            sortedReal[i] = originalReal[source];
            sortedImaginary[i] = originalImaginary[source];
        }

        assertNotEquals(0, (int) order[0], "Test did not exercise frequency reordering");
        FastSincTree shiftedTree = new FastSincTree(sortedHz, true, true);

        for (double window : new double[]{1.0e-6, 0.0130137, 0.25}) {
            double expected = directSparsePower(sortedHz, sortedReal, sortedImaginary, window);
            double actual = shiftedTree.dualTreeMeanSquare(sortedReal, sortedImaginary, window);

            assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Doppler reordering changed signal associations");
        }

        System.out.printf(Locale.ROOT, "[FREQ-DUAL-PREPROCESSED] " + "signals=%d unique=%d " + "duplicatesMerged=%d " + "dopplerReorder=PASS%n", count, unique, count - unique);
    }

    @Test
    void productionSortedCarriersPreservePhaseAndFrequency() {
        final long tick = 4_000_000_001L;
        final double window = 0.0130137;

        var accumulator = new FrequencyEngine.InterferenceAccumulator(tick, window);

        double[] frequencies = {
                2_400_000_000.0,
                915_000_000.125,
                915_000_000.0,
                915_000_000.125,
                30_000_000.0
        };

        double[] amplitudes = {0.5, 1.2, 0.7, 0.3, 0.9};
        double[] phases = {0.2, 1.1, 2.4, 0.6, 1.8};

        double[] expectedHz = {
                30_000_000.0,
                915_000_000.0,
                915_000_000.125,
                2_400_000_000.0
        };

        double[] expectedReal = new double[expectedHz.length];
        double[] expectedImaginary = new double[expectedHz.length];

        for (int i = 0; i < frequencies.length; i++) {
            double hz = frequencies[i];

            FrequencySignal signal = new FrequencySignal(hz, 1.0, phases[i], 343.0);
            accumulator.add(signal, amplitudes[i], 0.0);
            double phase = FrequencyMath.phaseAtTick(hz, tick, phases[i], 0.0);
            phase -= FrequencyMath.phaseAdvanceRadians(hz, window * 0.5);

            for (int j = 0; j < expectedHz.length; j++) {
                if (hz == expectedHz[j]) {
                    expectedReal[j] += amplitudes[i] * Math.cos(phase);
                    expectedImaginary[j] += amplitudes[i] * Math.sin(phase);
                    break;
                }
            }
        }

        var sorted = accumulator.sortedCarriers();
        assertArrayEquals(expectedHz, sorted.frequencies());

        for (int i = 0; i < expectedHz.length; i++) {
            assertEquals(expectedReal[i], sorted.real()[i], 1.0e-12);
            assertEquals(expectedImaginary[i], sorted.imaginary()[i], 1.0e-12);
        }

        double expectedPower = directSparsePower(expectedHz, expectedReal, expectedImaginary, window);
        double currentPower = accumulator.meanSquareWindow(window);

        assertEquals(expectedPower, currentPower, 2.0e-9 * Math.max(1.0, Math.abs(expectedPower)));

        System.out.printf(Locale.ROOT, "[FREQ-PRODUCTION-SORT] " + "inputs=%d unique=%d " + "phase=PASS ordering=PASS power=PASS%n", frequencies.length, sorted.frequencies().length);
    }

    @Test
    void extractedProductionDualTreeMatchesDirectAnalyticalPower() {
        Random random = new Random(0x32B2026L);

        for (int count : new int[]{64, 256, 1_024}) {
            double[] frequencies = new double[count];
            double[] real = new double[count];
            double[] imaginary = new double[count];

            for (int i = 0; i < count; i++) {
                double hz = 30_000_000.0 + 5_970_000_000.0 * i / (count - 1);
                if (i > 0 && i % 8 == 0) {
                    hz = frequencies[i - 1] + 0.125;
                }

                frequencies[i] = hz;
                real[i] = random.nextGaussian() * 0.4;
                imaginary[i] = random.nextGaussian() * 0.4;
            }

            FiniteWindowDualTree production = new FiniteWindowDualTree(frequencies);

            for (double window : new double[]{1.0e-9, 1.0e-6, 0.0130137, 0.25}) {
                double expected = directSparsePower(frequencies, real, imaginary, window);
                double actual = production.meanSquare(real, imaginary, window);

                assertTrue(Double.isFinite(actual));
                assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Production dual-tree mismatch: count=" + count + " window=" + window);
            }

            real[0] += 0.125;
            imaginary[count - 1] -= 0.0625;

            double expected = directSparsePower(frequencies, real, imaginary, 0.0130137);
            double actual = production.meanSquare(real, imaginary, 0.0130137);

            assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)));

            Arrays.fill(real, 0.0);
            Arrays.fill(imaginary, 0.0);

            assertEquals(0.0, production.meanSquare(real, imaginary, 0.0130137), 1.0e-12);

            System.out.printf(Locale.ROOT, "[FREQ-PRODUCTION-DUAL] " + "carriers=%d analytical=PASS " + "updates=PASS reset=PASS%n", count);
        }

        assertThrows(IllegalArgumentException.class, () -> new FiniteWindowDualTree(new double[]{915_000_000.0, 915_000_000.0}));
    }

    @Test
    void benchmarkProductionAccumulatorDualTreeCrossover() {
        final long tick = 4_000_000_001L;
        final double window = 0.0130137;

        for (int layout = 0; layout < 2; layout++) {
            for (int count : new int[]{8, 32, 64, 128, 256, 512, 1_024}) {
                Random random = new Random(0x32C2026L + count * 131L + layout);

                var accumulator = new FrequencyEngine.InterferenceAccumulator(tick, window);

                for (int i = 0; i < count; i++) {
                    int slot = (i * 73) & (count - 1);
                    double hz;

                    if (layout == 0) {
                        hz = 30_000_000.0 + 5_970_000_000.0 * slot / count + (slot % 7) * 0.125;
                    } else {
                        hz = 915_000_000.0 + slot * 0.125 + (slot % 7) * 0.015625;
                    }

                    double amplitude = 0.25 + random.nextDouble() * 1.5;
                    double phase = random.nextDouble() * Math.PI * 2.0;
                    double delay = random.nextDouble() * 0.025;

                    accumulator.add(new FrequencySignal(hz, 1.0, phase, 343.0), amplitude, delay);
                }

                double exact = accumulator.meanSquareWindow(window);
                double dual = accumulator.meanSquareWindowDualTreeProbe(window);
                double tolerance = 2.0e-9 * Math.max(1.0, Math.abs(exact));

                assertTrue(Double.isFinite(exact));
                assertTrue(Double.isFinite(dual));

                assertEquals(exact, dual, tolerance, "Production crossover mismatch layout=" + layout + " count=" + count);

                for (int i = 0; i < 4; i++) {
                    blackhole = accumulator.meanSquareWindow(window);
                    blackhole = accumulator.meanSquareWindowDualTreeProbe(window);
                }

                int repetitions = count >= 512 ? 4 : count >= 256 ? 8 : count >= 128 ? 16 : 40;

                double[] exactTimes = new double[5];
                double[] dualTimes = new double[5];

                for (int sample = 0; sample < 5; sample++) {
                    long start = System.nanoTime();

                    for (int j = 0; j < repetitions; j++) {
                        blackhole = accumulator.meanSquareWindow(window);
                    }

                    exactTimes[sample] = (System.nanoTime() - start) / (repetitions * 1000.0);
                    start = System.nanoTime();

                    for (int j = 0; j < repetitions; j++) {
                        blackhole = accumulator.meanSquareWindowDualTreeProbe(window);
                    }

                    dualTimes[sample] = (System.nanoTime() - start) / (repetitions * 1000.0);
                }

                Arrays.sort(exactTimes);
                Arrays.sort(dualTimes);

                double exactUs = exactTimes[2];
                double dualUs = dualTimes[2];

                System.out.printf(Locale.ROOT, "[FREQ-PRODUCTION-CROSSOVER] " + "layout=%s carriers=%d " + "exact=%.3f us " + "dual=%.3f us " + "speedup=%.2fx " + "error=%.3e%n", layout == 0 ? "wide" : "dense", count, exactUs, dualUs, exactUs / dualUs, Math.abs(exact - dual));
            }
        }
    }

    @Test
    void productionAutomaticDispatchMatchesAnalyticalPower() {
        final long tick = 4_000_000_001L;
        final double window = 0.0130137;

        for (int count : new int[]{63, 64, 512, 1_023, 1_024, 2_048}) {
            Random random = new Random(0x32D2026L + count);
            var accumulator = new FrequencyEngine.InterferenceAccumulator(tick, window);

            for (int i = 0; i < count; i++) {
                double hz = 30_000_000.0 + random.nextDouble() * 5_970_000_000.0;
                double amplitude = 0.25 + random.nextDouble() * 1.5;
                double phase = random.nextDouble() * 2.0 * Math.PI;
                double delay = random.nextDouble() * 0.025;

                accumulator.add(new FrequencySignal(hz, 1.0, phase, 343.0), amplitude, delay);
            }

            var carriers = accumulator.sortedCarriers();
            assertEquals(count, carriers.frequencies().length);

            double expected = directSparsePower(carriers.frequencies(), carriers.real(), carriers.imaginary(), window);
            double actual = accumulator.meanSquareWindow(window);
            double tolerance = 2.0e-9 * Math.max(1.0, Math.abs(expected));

            assertTrue(Double.isFinite(actual));
            assertEquals(expected, actual, tolerance, "Automatic dispatch mismatch at " + count);

            if (count == 1_024) {
                double otherWindow = 0.25;
                double expectedOther = directSparsePower(carriers.frequencies(), carriers.real(), carriers.imaginary(), otherWindow);
                double actualOther = accumulator.meanSquareWindow(otherWindow);

                assertEquals(expectedOther, actualOther, 2.0e-9 * Math.max(1.0, Math.abs(expectedOther)), "Different-window fallback mismatch");
            }

            System.out.printf(Locale.ROOT, "[FREQ-PRODUCTION-DISPATCH] " + "carriers=%d solver=%s " + "error=%.3e%n", count, count >= 1_024 ? "DUAL" : "EXACT", Math.abs(expected - actual));
        }
    }


    @Test
    void benchmarkProductionDispatchAllocationCost() {
        final long tick = 4_000_000_001L;
        final double window = 0.0130137;

        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (memory == null) {
            System.out.println("[FREQ-PRODUCTION-MEM] unavailable");
            return;
        }

        if (!memory.isThreadAllocatedMemoryEnabled()) {
            memory.setThreadAllocatedMemoryEnabled(true);
        }

        long threadId = Thread.currentThread().threadId();
        for (int count : new int[]{512, 1_024, 4_096}) {
            FrequencySignal[] signals = new FrequencySignal[count];
            double[] amplitudes = new double[count];
            double[] delays = new double[count];

            for (int i = 0; i < count; i++) {
                int slot = (i * 73) & (count - 1);

                double hz = 915_000_000.0 + slot * 0.125 + (slot % 7) * 0.015625;
                signals[i] = new FrequencySignal(hz, 1.0, i * 0.137, 343.0);

                amplitudes[i] = 0.25 + (i % 9) * 0.1;
                delays[i] = (i % 17) * 0.0001;
            }

            var accumulator = new FrequencyEngine.InterferenceAccumulator(tick, window);
            for (int i = 0; i < count; i++) {
                accumulator.add(signals[i], amplitudes[i], delays[i]);
            }

            int repetitions = count >= 4_096 ? 5 : 12;

            for (int i = 0; i < 5; i++) {
                blackhole = accumulator.meanSquareWindow(window);
            }

            long before = memory.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();

            for (int i = 0; i < repetitions; i++) {
                blackhole = accumulator.meanSquareWindow(window);
            }

            double existingMs = (System.nanoTime() - start) / (repetitions * 1_000_000.0);
            double existingBytes = (memory.getThreadAllocatedBytes(threadId) - before) / (double) repetitions;

            for (int warmup = 0; warmup < 3; warmup++) {
                var fresh = new FrequencyEngine.InterferenceAccumulator(tick, window);
                for (int i = 0; i < count; i++) {
                    fresh.add(signals[i], amplitudes[i], delays[i]);
                }

                blackhole = fresh.meanSquareWindow(window);
            }

            before = memory.getThreadAllocatedBytes(threadId);
            start = System.nanoTime();

            for (int iteration = 0; iteration < repetitions; iteration++) {
                var fresh = new FrequencyEngine.InterferenceAccumulator(tick, window);

                for (int i = 0; i < count; i++) {
                    fresh.add(signals[i], amplitudes[i], delays[i]);
                }

                blackhole = fresh.meanSquareWindow(window);
            }

            double freshMs = (System.nanoTime() - start) / (repetitions * 1_000_000.0);
            double freshBytes = (memory.getThreadAllocatedBytes(threadId) - before) / (double) repetitions;

            assertTrue(Double.isFinite(blackhole));

            System.out.printf(Locale.ROOT, "[FREQ-PRODUCTION-MEM] " + "carriers=%d " + "existing=%.3f ms " + "existingBytes=%.0f " + "fresh=%.3f ms " + "freshBytes=%.0f%n", count, existingMs, existingBytes, freshMs, freshBytes);
        }
    }

    @Test
    void productionDualTreeReusesGeometrySafely() {
        final long tick = 4_000_000_001L;
        final double window = 0.0130137;
        final int count = 1_024;

        var accumulator = new FrequencyEngine.InterferenceAccumulator(tick, window);
        for (int i = 0; i < count; i++) {
            int slot = (i * 73) & (count - 1);
            double hz = 915_000_000.0 + slot * 0.125 + (slot % 7) * 0.015625;
            accumulator.add(new FrequencySignal(hz, 1.0, i * 0.137, 343.0), 0.25 + (i % 9) * 0.1, (i % 17) * 0.0001);
        }

        var initial = accumulator.sortedCarriers();

        double expected = directSparsePower(initial.frequencies(), initial.real(), initial.imaginary(), window);
        assertEquals(expected, accumulator.meanSquareWindow(window), 2.0e-9 * Math.max(1.0, Math.abs(expected)));

        for (int i = 0; i < 8; i++) {
            blackhole = accumulator.meanSquareWindow(window);
        }

        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;

        if (memory != null) {
            if (!memory.isThreadAllocatedMemoryEnabled()) {
                memory.setThreadAllocatedMemoryEnabled(true);
            }

            long threadId = Thread.currentThread().threadId();
            long before = memory.getThreadAllocatedBytes(threadId);

            final int repetitions = 20;

            for (int i = 0; i < repetitions; i++) {
                blackhole = accumulator.meanSquareWindow(window);
            }

            double allocatedPerQuery = (memory.getThreadAllocatedBytes(threadId) - before) / (double) repetitions;
            System.out.printf(Locale.ROOT, "[FREQ-DUAL-REUSE-MEM] " + "carriers=%d allocated=%.1f bytes/query%n", count, allocatedPerQuery);
        }

        accumulator.add(new FrequencySignal(915_000_000.0, 1.0, 1.25, 343.0), 0.7, 0.004);

        var updated = accumulator.sortedCarriers();
        expected = directSparsePower(updated.frequencies(), updated.real(), updated.imaginary(), window);

        assertEquals(expected, accumulator.meanSquareWindow(window), 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Existing-frequency update was not applied");
        accumulator.add(new FrequencySignal(2_750_000_000.03125, 1.0, 0.75, 343.0), 0.8, 0.002);

        var expanded = accumulator.sortedCarriers();
        assertEquals(count + 1, expanded.frequencies().length);
        expected = directSparsePower(expanded.frequencies(), expanded.real(), expanded.imaginary(), window);

        assertEquals(expected, accumulator.meanSquareWindow(window), 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Frequency insertion did not rebuild geometry");

        System.out.println("[FREQ-DUAL-REUSE] " + "initial=PASS " + "amplitudeUpdate=PASS " + "geometryRebuild=PASS");
    }

    @Test
    void productionAccumulatorResetDoesNotLeakSignals() {
        final double firstWindow = 0.0130137;
        final double secondWindow = 0.25;
        final long firstTick = 4_000_000_001L;
        final long secondTick = firstTick + 17;

        var reused = new FrequencyEngine.InterferenceAccumulator(firstTick, firstWindow);

        for (int i = 0; i < 1_024; i++) {
            double hz = 915_000_000.0 + i * 0.125;
            reused.add(new FrequencySignal(hz, 1.0, i * 0.137, 343.0), 0.25 + (i % 9) * 0.1, (i % 17) * 0.0001);
        }

        blackhole = reused.meanSquareWindow(firstWindow);
        reused.reset(secondTick, secondWindow);
        var fresh = new FrequencyEngine.InterferenceAccumulator(secondTick, secondWindow);

        double[] hz = {
                30_000_000.0,
                915_000_000.125,
                915_000_000.125,
                2_400_000_000.0
        };

        for (int i = 0; i < hz.length; i++) {
            FrequencySignal signal = new FrequencySignal(hz[i], 1.0, i * 0.31, 343.0);

            double amplitude = 0.5 + i * 0.2;
            double delay = i * 0.001;

            reused.add(signal, amplitude, delay);
            fresh.add(signal, amplitude, delay);
        }

        double expected = fresh.meanSquareWindow(secondWindow);
        double actual = reused.meanSquareWindow(secondWindow);

        assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Reused accumulator retained previous signal data");

        reused.reset(secondTick + 1, secondWindow);
        assertEquals(0.0, reused.meanSquareWindow(secondWindow), 1.0e-12, "Empty receiver retained previous signal power");

        var freshLarge = new FrequencyEngine.InterferenceAccumulator(secondTick + 2, firstWindow);
        reused.reset(secondTick + 2, firstWindow);

        for (int i = 0; i < 1_024; i++) {
            double frequency = 2_400_000_000.0 + i * 0.125;
            FrequencySignal signal = new FrequencySignal(frequency, 1.0, i * 0.071, 343.0);
            double amplitude = 0.5 + (i % 5) * 0.1;

            reused.add(signal, amplitude, 0.0);
            freshLarge.add(signal, amplitude, 0.0);
        }

        expected = freshLarge.meanSquareWindow(firstWindow);
        actual = reused.meanSquareWindow(firstWindow);

        assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Large receiver state changed after reuse");

        System.out.println("[FREQ-WORKSPACE-RESET] " + "largeToSmall=PASS " + "emptyReceiver=PASS " + "smallToLarge=PASS " + "timeAndWindow=PASS");
    }

    @Test
    void productionDualTreeReusesGeometryAcrossResets() throws ReflectiveOperationException {
        final long tick = 4_000_000_001L;
        final double window = 0.0130137;
        final int count = 1_024;

        FrequencySignal[] signals = new FrequencySignal[count];

        for (int i = 0; i < count; i++) {
            int slot = (i * 73) & (count - 1);
            double hz = 915_000_000.0 + slot * 0.125;

            signals[i] = new FrequencySignal(hz, 1.0, i * 0.137, 343.0);
        }

        var accumulator = new FrequencyEngine.InterferenceAccumulator(tick, window);
        java.lang.reflect.Field treeField = FrequencyEngine.InterferenceAccumulator.class.getDeclaredField("cachedDualTree");
        treeField.setAccessible(true);

        for (int i = 0; i < count; i++) {
            accumulator.add(signals[i], 0.25 + (i % 9) * 0.1, (i % 17) * 0.0001);
        }

        blackhole = accumulator.meanSquareWindow(window);

        Object originalTree = treeField.get(accumulator);
        assertNotNull(originalTree);

        accumulator.reset(tick + 17, window);
        for (int i = count - 1; i >= 0; i--) {
            accumulator.add(signals[i], 0.5 + (i % 11) * 0.08, (i % 13) * 0.0002);
        }

        var reordered = accumulator.sortedCarriers();

        double expected = directSparsePower(reordered.frequencies(), reordered.real(), reordered.imaginary(), window);
        double actual = accumulator.meanSquareWindow(window);

        assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)));
        assertSame(originalTree, treeField.get(accumulator), "Identical frequency geometry was rebuilt");

        double secondWindow = 0.25;
        accumulator.reset(tick + 30, secondWindow);

        for (int i = 0; i < count; i++) {
            accumulator.add(signals[i], 0.4 + (i % 7) * 0.1, (i % 9) * 0.0001);
        }

        var changedWindow = accumulator.sortedCarriers();

        expected = directSparsePower(changedWindow.frequencies(), changedWindow.real(), changedWindow.imaginary(), secondWindow);

        actual = accumulator.meanSquareWindow(secondWindow);
        assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)));
        assertSame(originalTree, treeField.get(accumulator), "Window change unnecessarily rebuilt geometry");

        accumulator.reset(tick + 40, window);

        for (int i = 0; i < count; i++) {
            FrequencySignal signal = i == count - 1 ? new FrequencySignal(2_400_000_000.125, 1.0, 0.75, 343.0) : signals[i];
            accumulator.add(signal, 0.5 + (i % 5) * 0.1, 0.0001);
        }

        var replaced = accumulator.sortedCarriers();
        expected = directSparsePower(replaced.frequencies(), replaced.real(), replaced.imaginary(), window);

        actual = accumulator.meanSquareWindow(window);

        assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)));
        assertNotSame(originalTree, treeField.get(accumulator), "Different frequency geometry was incorrectly reused");

        for (int trial = 0; trial < 8; trial++) {
            accumulator.reset(tick + trial, window);

            for (int i = count - 1; i >= 0; i--) {
                accumulator.add(signals[i], 0.5, 0.0001);
            }

            blackhole = accumulator.meanSquareWindow(window);
        }

        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (memory != null) {
            if (!memory.isThreadAllocatedMemoryEnabled()) {
                memory.setThreadAllocatedMemoryEnabled(true);
            }

            long threadId = Thread.currentThread().threadId();
            long before = memory.getThreadAllocatedBytes(threadId);

            final int repetitions = 12;
            for (int trial = 0; trial < repetitions; trial++) {
                accumulator.reset(tick + trial, window);
                for (int i = count - 1; i >= 0; i--) {
                    accumulator.add(signals[i], 0.5, 0.0001);
                }

                blackhole = accumulator.meanSquareWindow(window);
            }

            double bytesPerQuery = (memory.getThreadAllocatedBytes(threadId) - before) / (double) repetitions;

            System.out.printf(Locale.ROOT, "[FREQ-CROSS-QUERY-MEM] " + "carriers=%d allocated=%.1f bytes/query%n", count, bytesPerQuery);
        }

        System.out.println("[FREQ-CROSS-QUERY] " + "sameGeometry=PASS " + "reordered=PASS " + "windowChange=PASS " + "differentGeometry=PASS");
    }

    @Test
    void benchmarkAccumulatorWithoutMockito() {
        final long tick = 4_000_000_001L;
        final double window = 0.0130137;

        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;

        if (memory == null) {
            System.out.println("[FREQ-NO-MOCK] Allocation tracking unavailable");
            return;
        }

        if (!memory.isThreadAllocatedMemoryEnabled()) {
            memory.setThreadAllocatedMemoryEnabled(true);
        }

        long threadId = Thread.currentThread().threadId();
        for (int count : new int[]{128, 512, 1024, 4096}) {
            FrequencySignal[] signals = new FrequencySignal[count];
            double[] amplitudes = new double[count];
            double[] delays = new double[count];

            for (int i = 0; i < count; i++) {
                int slot = (i * 73) & (count - 1);
                double hz = 915_000_000.0 + slot * 0.125 + (slot % 7) * 0.015625;

                signals[i] = new FrequencySignal(hz, 0.25 + (i % 9) * 0.1, i * 0.031, 343.0);
                double distance = 1.0 + (i % 3) * 0.25;

                amplitudes[i] = com.krimzik.spectra.math.FrequencyPropagation.amplitudeAtDistance(signals[i].amplitude(), distance);
                delays[i] = com.krimzik.spectra.math.FrequencyPropagation.travelTimeSeconds(distance, signals[i].propagationSpeed());
            }

            var accumulator = new FrequencyEngine.InterferenceAccumulator(tick, window);
            int repetitions = count >= 4096 ? 8 : 24;

            for (int pass = 0; pass < 12; pass++) {
                accumulator.reset(tick + pass, window);

                for (int i = 0; i < count; i++) {
                    accumulator.add(signals[i], amplitudes[i], delays[i]);
                }

                blackhole = accumulator.meanSquareWindow(window);
            }

            long before = memory.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();

            for (int pass = 0; pass < repetitions; pass++) {
                accumulator.reset(tick + pass, window);

                for (int i = 0; i < count; i++) {
                    accumulator.add(signals[i], amplitudes[i], delays[i]);
                }
            }

            double addMs = (System.nanoTime() - start) / (repetitions * 1_000_000.0);
            double addBytes = (memory.getThreadAllocatedBytes(threadId) - before) / (double) repetitions;

            for (int pass = 0; pass < 12; pass++) {
                accumulator.reset(tick + pass, window);

                for (int i = 0; i < count; i++) {
                    accumulator.add(signals[i], amplitudes[i], delays[i]);
                }

                blackhole = accumulator.meanSquareWindow(window);
            }

            before = memory.getThreadAllocatedBytes(threadId);
            start = System.nanoTime();

            for (int pass = 0; pass < repetitions; pass++) {
                accumulator.reset(tick + pass, window);

                for (int i = 0; i < count; i++) {
                    accumulator.add(signals[i], amplitudes[i], delays[i]);
                }

                blackhole = accumulator.meanSquareWindow(window);
            }

            double fullMs = (System.nanoTime() - start) / (repetitions * 1_000_000.0);
            double fullBytes = (memory.getThreadAllocatedBytes(threadId) - before) / (double) repetitions;

            var fresh = new FrequencyEngine.InterferenceAccumulator(tick + repetitions - 1, window);
            for (int i = 0; i < count; i++) {
                fresh.add(signals[i], amplitudes[i], delays[i]);
            }

            double expected = fresh.meanSquareWindow(window);
            assertEquals(expected, blackhole, 2.0e-9 * Math.max(1.0, Math.abs(expected)));
            System.out.printf(Locale.ROOT, "[FREQ-NO-MOCK] " + "carriers=%d " + "add=%.3f ms %.0f bytes " + "full=%.3f ms %.0f bytes%n", count, addMs, addBytes, fullMs, fullBytes);
        }
    }

    @Test
    void productionExactSolverReusesTrigScratch() throws ReflectiveOperationException {
        final long tick = 4_000_000_001L;
        final double window = 0.0130137;

        var accumulator = new FrequencyEngine.InterferenceAccumulator(tick, window);
        var scratchField = FrequencyEngine.InterferenceAccumulator.class.getDeclaredField("exactTrigScratch");
        scratchField.setAccessible(true);

        double[] smallScratch = null;
        double[] largeScratch = null;
        for (int count : new int[]{128, 512, 128}) {
            accumulator.reset(tick + count, window);

            for (int i = 0; i < count; i++) {
                double hz = 915_000_000.0 + i * 0.125 + (i % 7) * 0.015625;
                accumulator.add(new FrequencySignal(hz, 1.0, i * 0.113, 343.0), 0.5 + (i % 9) * 0.1, (i % 13) * 0.0001);
            }

            var carriers = accumulator.sortedCarriers();
            double expected = directSparsePower(carriers.frequencies(), carriers.real(), carriers.imaginary(), window);
            double actual = accumulator.meanSquareWindow(window);

            assertEquals(expected, actual, 2.0e-9 * Math.max(1.0, Math.abs(expected)), "Exact analytical power mismatch at " + count);

            double[] scratch = (double[]) scratchField.get(accumulator);
            assertNotNull(scratch);
            assertTrue(scratch.length >= (count - 1) * 2, "Trigonometry buffer is too small");

            if (smallScratch == null) {
                smallScratch = scratch;

            } else if (largeScratch == null) {
                largeScratch = scratch;
                assertNotSame(smallScratch, largeScratch, "Buffer failed to grow for larger workload");
            } else {
                assertSame(largeScratch, scratch, "Smaller workload allocated another buffer");
            }
        }

        for (int i = 0; i < 12; i++) {
            blackhole = accumulator.meanSquareWindow(window);
        }

        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (memory != null) {
            if (!memory.isThreadAllocatedMemoryEnabled()) {
                memory.setThreadAllocatedMemoryEnabled(true);
            }

            long threadId = Thread.currentThread().threadId();
            long before = memory.getThreadAllocatedBytes(threadId);

            final int repetitions = 32;

            for (int i = 0; i < repetitions; i++) {
                blackhole = accumulator.meanSquareWindow(window);
            }

            double bytesPerQuery = (memory.getThreadAllocatedBytes(threadId) - before) / (double) repetitions;

            System.out.printf(Locale.ROOT, "[FREQ-EXACT-SCRATCH] " + "allocated=%.1f bytes/query%n", bytesPerQuery);
        }

        System.out.println("[FREQ-EXACT-SCRATCH] " + "accuracy=PASS " + "bufferGrowth=PASS " + "bufferReuse=PASS");
    }
}
