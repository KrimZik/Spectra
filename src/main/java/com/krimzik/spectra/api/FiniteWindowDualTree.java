package com.krimzik.spectra.api;

import com.krimzik.spectra.math.FrequencyMath;
import java.util.Arrays;
import java.util.Objects;

final class FiniteWindowDualTree {
    private static final int ORDER = 20;
    private static final int LEAF_SIZE = 8;
    private static final double MAX_RATIO = 0.25;

    private final double[] frequency;
    private final double[] sine;
    private final double[] cosine;
    private final Node root;
    private long cachedWindowBits = Long.MIN_VALUE;

    private final double[] shiftPowers = new double[ORDER];
    private final double[] scalePowers = new double[ORDER];
    private final double[] pairDiffLeft = new double[ORDER];
    private final double[] pairDiffRight = new double[ORDER];
    private final double[] pairSumLeft = new double[ORDER];
    private final double[] pairSumRight = new double[ORDER];

    private static final class Node {
        final int from, to;
        final double center, radius;
        final double[] moments = new double[4 * ORDER];
        final Node left, right;

        Node(int from, int to, double[] frequencies) {
            this.from = from;
            this.to = to;

            center = 0.5 * (frequencies[from] + frequencies[to - 1]);
            radius = 0.5 * (frequencies[to - 1] - frequencies[from]);

            if (to - from > LEAF_SIZE) {
                int middle = (from + to) >>> 1;
                left = new Node(from, middle, frequencies);
                right = new Node(middle, to, frequencies);
            } else {
                left = null;
                right = null;
            }
        }
    }

    FiniteWindowDualTree(double[] sortedDistinctFrequencies) {
        Objects.requireNonNull(sortedDistinctFrequencies, "frequencies");

        if (sortedDistinctFrequencies.length == 0) {
            throw new IllegalArgumentException("No carriers");
        }

        frequency = sortedDistinctFrequencies.clone();
        for (int i = 0; i < frequency.length; i++) {
            if (!(frequency[i] > 0.0) || !Double.isFinite(frequency[i]) || (i > 0 && !(frequency[i] > frequency[i - 1]))) {
                throw new IllegalArgumentException("Frequencies must be positive, sorted and distinct");
            }
        }

        sine = new double[frequency.length];
        cosine = new double[frequency.length];
        root = new Node(0, frequency.length, frequency);
    }

    double meanSquare(double[] real, double[] imaginary, double windowSeconds) {
        Objects.requireNonNull(real, "real");
        Objects.requireNonNull(imaginary, "imaginary");

        if (real.length != frequency.length || imaginary.length != frequency.length) {
            throw new IllegalArgumentException("Carrier data length mismatch");
        }

        if (!(windowSeconds > 0.0) || !Double.isFinite(windowSeconds)) {
            throw new IllegalArgumentException("Invalid window");
        }

        long bits = Double.doubleToLongBits(windowSeconds);
        if (bits != cachedWindowBits) {
            for (int i = 0; i < frequency.length; i++) {
                double phase = FrequencyMath.phaseAdvanceRadians(frequency[i], windowSeconds * 0.5);
                sine[i] = Math.sin(phase);
                cosine[i] = Math.cos(phase);
            }
            cachedWindowBits = bits;
        }

        updateMoments(root, real, imaginary);
        double self = 0.0;

        for (int i = 0; i < frequency.length; i++) {
            double r = real[i];
            double q = imaginary[i];
            self += 0.5 * (r * r + q * q - (r * r - q * q) * sincWindow(2.0 * frequency[i], windowSeconds));
        }

        double cross = pairNodes(root, root, real, imaginary, windowSeconds, 1.0 / (Math.PI * windowSeconds));
        double result = self + cross;

        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Finite-window interference overflow");
        }

        return Math.max(0.0, result);
    }

    private void updateMoments(Node node, double[] real, double[] imaginary) {
        Arrays.fill(node.moments, 0.0);

        if (node.left != null) {
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

        shiftPowers[0] = scalePowers[0] = 1.0;

        for (int k = 1; k < ORDER; k++) {
            shiftPowers[k] = shiftPowers[k - 1] * shift;
            scalePowers[k] = scalePowers[k - 1] * scale;
        }

        for (int k = 0; k < ORDER; k++) {
            double binomial = 1.0;
            for (int j = 0; j <= k; j++) {
                double weight = binomial * shiftPowers[k - j] * scalePowers[j];

                for (int c = 0; c < 4; c++) {
                    int offset = c * ORDER;
                    parent.moments[offset + k] = Math.fma(child.moments[offset + j], weight, parent.moments[offset + k]);
                }

                if (j < k) {
                    binomial *= (double) (k - j) / (j + 1);
                }
            }
        }
    }

    private double pairNodes(Node a, Node b, double[] real, double[] imaginary, double windowSeconds, double inversePiWindow) {
        if (a == b) {
            if (a.left == null) {
                return leafPairs(a, b, real, imaginary, windowSeconds, inversePiWindow);
            }

            return pairNodes(a.left, a.left, real, imaginary, windowSeconds, inversePiWindow) + pairNodes(a.left, a.right, real, imaginary, windowSeconds, inversePiWindow) + pairNodes(a.right, a.right, real, imaginary, windowSeconds, inversePiWindow);
        }

        double difference = a.center - b.center;
        double sum = a.center + b.center;
        double radius = a.radius + b.radius;

        if (difference != 0.0 && sum != 0.0 && radius <= MAX_RATIO * Math.abs(difference) && radius <= MAX_RATIO * Math.abs(sum)) {
            return groupContribution(a, b, difference, sum, radius, inversePiWindow);
        }

        if (a.left == null && b.left == null) {
            return leafPairs(a, b, real, imaginary, windowSeconds, inversePiWindow);
        }

        if (b.left == null || (a.left != null && a.radius >= b.radius)) {
            return pairNodes(a.left, b, real, imaginary, windowSeconds, inversePiWindow) + pairNodes(a.right, b, real, imaginary, windowSeconds, inversePiWindow);
        }

        return pairNodes(a, b.left, real, imaginary, windowSeconds, inversePiWindow) + pairNodes(a, b.right, real, imaginary, windowSeconds, inversePiWindow);
    }

    private static int expansionTerms(double ratio) {
        if (ratio <= 0.01) return 8;
        if (ratio <= 0.03) return 10;
        if (ratio <= 0.06) return 13;
        if (ratio <= 0.12) return 17;
        return ORDER;
    }

    private double groupContribution(Node left, Node right, double difference, double sum, double combinedRadius, double inversePiWindow) {
        int terms = expansionTerms(Math.max(combinedRadius / Math.abs(difference), combinedRadius / Math.abs(sum)));

        double diffLeftRatio = -left.radius / difference;
        double diffRightRatio = right.radius / difference;
        double sumLeftRatio = -left.radius / sum;
        double sumRightRatio = -right.radius / sum;

        pairDiffLeft[0] = pairDiffRight[0] = pairSumLeft[0] = pairSumRight[0] = 1.0;
        for (int k = 1; k < terms; k++) {
            pairDiffLeft[k] = pairDiffLeft[k - 1] * diffLeftRatio;
            pairDiffRight[k] = pairDiffRight[k - 1] * diffRightRatio;
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

                differenceTotal = Math.fma(binomial * pairDiffLeft[p] * pairDiffRight[q], differenceNumerator, differenceTotal);
                sumTotal = Math.fma(binomial * pairSumLeft[p] * pairSumRight[q], sumNumerator, sumTotal);

                if (p < k) {
                    binomial *= (double) (k - p) / (p + 1);
                }
            }
        }

        return (differenceTotal / difference - sumTotal / sum) * inversePiWindow;
    }

    private double leafPairs(Node a, Node b, double[] real, double[] imaginary, double windowSeconds, double inversePiWindow) {
        double result = 0.0;
        for (int i = a.from; i < a.to; i++) {
            for (int j = a == b ? i + 1 : b.from; j < b.to; j++) {
                result += pairContribution(i, j, real, imaginary, windowSeconds, inversePiWindow);
            }
        }

        return result;
    }

    private double pairContribution(int i, int j, double[] real, double[] imaginary, double windowSeconds, double inversePiWindow) {
        double differenceHz = frequency[i] - frequency[j];
        double sumHz = frequency[i] + frequency[j];

        double first = sine[i] * cosine[j];
        double second = cosine[i] * sine[j];

        double closeThreshold = 1.0e-6 * Math.max(frequency[i], frequency[j]);

        double differenceKernel = Math.abs(differenceHz) <= closeThreshold ? sincWindow(Math.abs(differenceHz), windowSeconds) : ((first - second) / differenceHz) * inversePiWindow;
        double sumKernel = ((first + second) / sumHz) * inversePiWindow;

        double differenceCoefficient = Math.fma(real[i], real[j], imaginary[i] * imaginary[j]);
        double sumCoefficient = Math.fma(real[i], real[j], -imaginary[i] * imaginary[j]);

        return differenceCoefficient * differenceKernel - sumCoefficient * sumKernel;
    }

    private static double sincWindow(double frequency, double windowSeconds) {
        if (frequency == 0.0) return 1.0;

        double x = Math.PI * frequency * windowSeconds;
        if (!Double.isFinite(x)) return 0.0;

        if (Math.abs(x) <= Math.PI * 0.25) {
            double x2 = x * x;
            return 1.0 + x2 * (-1.0 / 6.0 + x2 * (1.0 / 120.0 + x2 * (-1.0 / 5040.0 + x2 * (1.0 / 362880.0 + x2 * (-1.0 / 39916800.0 + x2 * (1.0 / 6227020800.0 - x2 / 1307674368000.0))))));
        }

        return Math.sin(FrequencyMath.phaseAdvanceRadians(frequency, windowSeconds * 0.5)) / x;
    }
}
