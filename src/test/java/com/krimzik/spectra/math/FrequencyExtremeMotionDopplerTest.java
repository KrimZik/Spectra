package com.krimzik.spectra.math;

import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FrequencyExtremeMotionDopplerTest {
    private static final double HZ = 915_000_000.0;
    private static final double SPEED = 10_000.0;
    private static volatile double benchmarkSink;

    private static FrequencyMotionSnapshot point(double x, double y, double z, double vx, double vy, double vz) {
        return new FrequencyMotionSnapshot(x, y, z, vx, vy, vz);
    }

    private static double analyticalFrequency(FrequencyMotionSnapshot source, FrequencyMotionSnapshot receiver, double speed) {
        double dx = receiver.x() - source.x();
        double dy = receiver.y() - source.y();
        double dz = receiver.z() - source.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double radialSource = (dx * source.vx() + dy * source.vy() + dz * source.vz()) / distance;
        double radialReceiver = (dx * receiver.vx() + dy * receiver.vy() + dz * receiver.vz()) / distance;
        return HZ * (speed - radialReceiver) / (speed - radialSource);
    }

    private static double referenceDelay(FrequencyMotionSnapshot source, FrequencyMotionSnapshot receiver, double ax, double ay, double az) {
        double lower = 0.0;
        double upper = 1.0;
        for (int i = 0; i < 80; i++) {
            double t = upper;
            double dx = receiver.x() - source.x() + source.vx() * t - 0.5 * ax * t * t;
            double dy = receiver.y() - source.y() + source.vy() * t - 0.5 * ay * t * t;
            double dz = receiver.z() - source.z() + source.vz() * t - 0.5 * az * t * t;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= SPEED * t) break;
            upper *= 2.0;
        }
        for (int i = 0; i < 80; i++) {
            double t = (lower + upper) * 0.5;
            double dx = receiver.x() - source.x() + source.vx() * t - 0.5 * ax * t * t;
            double dy = receiver.y() - source.y() + source.vy() * t - 0.5 * ay * t * t;
            double dz = receiver.z() - source.z() + source.vz() * t - 0.5 * az * t * t;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > SPEED * t) lower = t;
            else upper = t;
        }
        return (lower + upper) * 0.5;
    }

    private static double referenceRetardedFrequency(FrequencyMotionSnapshot source, FrequencyMotionSnapshot receiver, double ax, double ay, double az, double delay) {
        double dx = receiver.x() - source.x() + source.vx() * delay - 0.5 * ax * delay * delay;
        double dy = receiver.y() - source.y() + source.vy() * delay - 0.5 * ay * delay * delay;
        double dz = receiver.z() - source.z() + source.vz() * delay - 0.5 * az * delay * delay;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double sourceRadial = (dx * (source.vx() - ax * delay) + dy * (source.vy() - ay * delay) + dz * (source.vz() - az * delay)) / distance;
        double receiverRadial = (dx * receiver.vx() + dy * receiver.vy() + dz * receiver.vz()) / distance;
        return HZ * (SPEED - receiverRadial) / (SPEED - sourceRadial);
    }

    @Test
    void fastRotationAndInstantDirectionChangesMatchIndependentDopplerReference() {
        Random random = new Random(0x532026L);
        int cases = 2_000;
        double maximumErrorHz = 0.0;

        for (int i = 0; i < cases; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 1.0 + random.nextDouble() * 32.0;
            double angularSpeed = (i % 2 == 0 ? 1.0 : -1.0) * (10.0 + random.nextDouble() * 90.0);
            double vx = -Math.sin(angle) * radius * angularSpeed;
            double vy = Math.cos(angle) * radius * angularSpeed;
            double x = 100.0 + Math.cos(angle) * radius;
            double y = 70.0 + Math.sin(angle) * radius;

            FrequencyMotionSnapshot source = point(x, y, 0.0, vx, vy, 0.0);
            FrequencyMotionSnapshot receiver = point(500.0, -100.0, 0.0, -50.0, 25.0, 0.0);
            double expected = analyticalFrequency(source, receiver, SPEED);
            double actual = FrequencyDoppler.receivedFrequencyHz(HZ, SPEED, source, receiver);
            maximumErrorHz = Math.max(maximumErrorHz, Math.abs(expected - actual));
            assertEquals(expected, actual, 0.0005, "Rotation scenario " + i);
        }

        System.out.printf(Locale.ROOT, "[SPECTRA-DOPPLER] rotationReversals=%d maxError=%.6f Hz PASS%n", cases, maximumErrorHz);
    }

    @Test
    void rapidRotationalAccelerationMatchesIndependentRetardedTimeReference() {
        Random random = new Random(0x332026L);
        int cases = 600;
        double maximumFrequencyErrorHz = 0.0;
        double maximumDelayErrorSeconds = 0.0;

        for (int i = 0; i < cases; i++) {
            double angle = random.nextDouble() * 2.0 * Math.PI;
            double radius = 1.0 + random.nextDouble() * 8.0;
            double omega = (i % 2 == 0 ? 1.0 : -1.0) * (5.0 + random.nextDouble() * 35.0);
            double x = 100.0 + Math.cos(angle) * radius;
            double y = 70.0 + Math.sin(angle) * radius;
            double vx = -Math.sin(angle) * radius * omega;
            double vy = Math.cos(angle) * radius * omega;
            double ax = -Math.cos(angle) * radius * omega * omega;
            double ay = -Math.sin(angle) * radius * omega * omega;

            FrequencyMotionSnapshot source = point(x, y, 0.0, vx, vy, 0.0);
            FrequencyMotionSnapshot receiver = point(200.0, 90.0, 0.0, 10.0, -15.0, 0.0);
            double delay = referenceDelay(source, receiver, ax, ay, 0.0);
            double expected = referenceRetardedFrequency(source, receiver, ax, ay, 0.0, delay);
            FrequencyRetardedDoppler.Result actual = FrequencyRetardedDoppler.calculate(HZ, SPEED, source, receiver, ax, ay, 0.0);
            maximumFrequencyErrorHz = Math.max(maximumFrequencyErrorHz, Math.abs(expected - actual.receivedFrequencyHz()));
            maximumDelayErrorSeconds = Math.max(maximumDelayErrorSeconds, Math.abs(delay - actual.travelTimeSeconds()));
            assertEquals(delay, actual.travelTimeSeconds(), 1.0e-9, "Travel time scenario " + i);
            assertEquals(expected, actual.receivedFrequencyHz(), 0.1, "Frequency scenario " + i);
        }

        System.out.printf(Locale.ROOT, "[SPECTRA-DOPPLER] retardedRotation=%d maxHzError=%.6f maxDelayError=%.3g s PASS%n", cases, maximumFrequencyErrorHz, maximumDelayErrorSeconds);
    }

    @Test
    void retardedSolverThroughputReportsMedianWithoutHardwareDependentLimits() {
        FrequencyMotionSnapshot receiver = point(200.0, 90.0, 0.0, 0.0, 0.0, 0.0);
        int iterations = 20_000;
        long[] timings = new long[7];
        double checksum = 0.0;

        for (int sample = 0; sample < timings.length; sample++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                double omega = 10.0 + (i & 15);
                FrequencyMotionSnapshot source = point(100.0, 70.0, 0.0, 0.0, 4.0 * omega, 0.0);
                FrequencyRetardedDoppler.Result result = FrequencyRetardedDoppler.calculate(HZ, SPEED, source, receiver, -4.0 * omega * omega, 0.0, 0.0);
                checksum += result.receivedFrequencyHz();
            }
            timings[sample] = System.nanoTime() - start;
        }

        benchmarkSink = checksum;
        Arrays.sort(timings);
        double medianMicroseconds = timings[timings.length / 2] / (iterations * 1_000.0);
        assertTrue(Double.isFinite(checksum));
        System.out.printf(Locale.ROOT, "[SPECTRA-PERF] retardedDoppler=%d median=%.3f us/call%n", iterations, medianMicroseconds);
    }
}
