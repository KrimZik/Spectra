package com.krimzik.spectra.math;

import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencyRetardedDopplerTest {
    private static FrequencyMotionSnapshot state(double x, double vx) {
        FrequencyMotionSnapshot s = mock(FrequencyMotionSnapshot.class);
        when(s.x()).thenReturn(x);
        when(s.vx()).thenReturn(vx);
        return s;
    }

    @Test
    void stationarySourceAndReceiver() {
        var result = FrequencyRetardedDoppler.calculate(915_000_000.0, 343.0, state(0, 0), state(100, 0), 0, 0, 0);
        assertEquals(915_000_000.0, result.receivedFrequencyHz(), 1.0e-6);
        assertEquals(100.0 / 343.0, result.travelTimeSeconds(), 1.0e-12);
    }

    @Test
    void approachingSourceMatchesExactConstantVelocityCase() {
        var result = FrequencyRetardedDoppler.calculate(100.0, 100.0, state(0, 10), state(100, 0), 0, 0, 0);
        assertEquals(100.0 / 90.0, result.travelTimeSeconds(), 1.0e-12);
        assertEquals(100.0 * 100.0 / 90.0, result.receivedFrequencyHz(), 1.0e-11);
    }

    @Test
    void acceleratingSourceUsesEmissionTimeVelocity() {
        double expectedDelay = Math.sqrt(120.0) - 10.0;
        var result = FrequencyRetardedDoppler.calculate(100.0, 100.0, state(0, 0), state(100, 0), 10, 0, 0);
        assertEquals(expectedDelay, result.travelTimeSeconds(), 1.0e-12);
        assertEquals(100.0 * 100.0 / (100.0 + 10.0 * expectedDelay), result.receivedFrequencyHz(), 1.0e-10);
    }

    @Test
    void approachingReceiverBlueShiftsFrequency() {
        var result = FrequencyRetardedDoppler.calculate(100.0, 100.0, state(0, 0), state(100, -10), 0, 0, 0);
        assertEquals(110.0, result.receivedFrequencyHz(), 1.0e-12);
    }

    @Test
    void nonPhysicalSourceSpeedIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FrequencyRetardedDoppler.calculate(100.0, 100.0, state(0, 100), state(100, 0), 0, 0, 0));
    }

    private record CircularReference(double receivedHz, double travelTimeSeconds) {}

    private static CircularReference exactCircularReception(double frequency, double c, double radius, double omega, double receiverX, double receiverY) {
        double low = 0.0;
        double high = Math.nextUp((Math.hypot(receiverX, receiverY) + radius) / c);

        for (int i = 0; i < 80; i++) {
            double tau = 0.5 * (low + high);
            double angle = omega * tau;

            double dx = receiverX - radius * Math.cos(angle);
            double dy = receiverY + radius * Math.sin(angle);

            double distance = Math.hypot(dx, dy);

            if (distance > c * tau) {
                low = tau;
            } else {
                high = tau;
            }
        }

        double tau = 0.5 * (low + high);
        double angle = omega * tau;

        double dx = receiverX - radius * Math.cos(angle);
        double dy = receiverY + radius * Math.sin(angle);

        double sourceVx = radius * omega * Math.sin(angle);
        double sourceVy = radius * omega * Math.cos(angle);

        double distance = Math.hypot(dx, dy);

        double sourceRadial = (dx * sourceVx + dy * sourceVy) / distance;
        double receivedHz = frequency * (c / (c - sourceRadial));

        return new CircularReference(receivedHz, tau);
    }

    private static void verifyCircularMotion(double omega, double maximumModelErrorHz) {
        double frequency = 915_000_000.0;
        double c = 10_000.0;
        double radius = 10.0;

        double receiverX = 120.0;
        double receiverY = 40.0;

        FrequencyMotionSnapshot source = new FrequencyMotionSnapshot(radius, 0.0, 0.0, 0.0, radius * omega, 0.0);
        FrequencyMotionSnapshot receiver = new FrequencyMotionSnapshot(receiverX, receiverY, 0.0, 0.0, 0.0, 0.0);

        double ax = -radius * omega * omega;
        CircularReference reference = exactCircularReception(frequency, c, radius, omega, receiverX, receiverY);

        double originalHz = FrequencyDoppler.receivedFrequencyHz(frequency, c, source, receiver);
        FrequencyRetardedDoppler.Result accelerated = FrequencyRetardedDoppler.calculate(frequency, c, source, receiver, ax, 0.0, 0.0);

        double originalError = Math.abs(originalHz - reference.receivedHz());
        double modelError = Math.abs(accelerated.receivedFrequencyHz() - reference.receivedHz());
        double travelError = Math.abs(accelerated.travelTimeSeconds() - reference.travelTimeSeconds());

        System.out.printf(
                Locale.ROOT,
                "[FREQ-CIRCLE] omega=%.2f rad/s | " + "speed=%.2f m/s | accel=%.2f m/s² | " + "Exact=%.3f Hz | OriginalError=%.3f Hz | " + "ModelError=%.3f Hz | TravelError=%.6f us%n",
                omega,
                radius * omega,
                radius * omega * omega,
                reference.receivedHz(),
                originalError,
                modelError,
                travelError * 1_000_000.0
        );

        assertTrue(Double.isFinite(modelError));
        assertTrue(Double.isFinite(travelError));

        assertTrue(modelError < originalError, "Retarded model should improve on instantaneous Doppler");
        assertTrue(modelError < maximumModelErrorHz, "Circular Doppler error exceeded test envelope: " + modelError + " Hz");
        assertTrue(travelError < 2.0e-6, "Circular propagation-time error exceeded 2 us");
    }

    @Test
    void circularMotionNearObservedShipSpeed() {
        verifyCircularMotion(2.7, 500.0);
    }

    @Test
    void rapidCircularRotationAccuracy() {
        verifyCircularMotion(12.0, 60_000.0);
        verifyCircularMotion(20.0, 300_000.0);
    }

    @Test
    void circularMotionPropagationSpeedAccuracyEnvelope() {
        double frequency = 915_000_000.0;
        double radius = 10.0;
        double receiverX = 120.0;
        double receiverY = 40.0;

        double[] propagationSpeeds = {
                10_000.0,
                100_000.0,
                1_000_000.0,
                299_792_458.0
        };

        double[] angularSpeeds = {
                2.7,
                12.0,
                20.0
        };

        FrequencyMotionSnapshot receiver = new FrequencyMotionSnapshot(receiverX, receiverY, 0.0, 0.0, 0.0, 0.0);

        for (double c : propagationSpeeds) {
            for (double omega : angularSpeeds) {
                double velocity = radius * omega;
                double acceleration = radius * omega * omega;

                FrequencyMotionSnapshot source = new FrequencyMotionSnapshot(radius, 0.0, 0.0, 0.0, velocity, 0.0);
                CircularReference exact = exactCircularReception(frequency, c, radius, omega, receiverX, receiverY);

                double originalHz = FrequencyDoppler.receivedFrequencyHz(frequency, c, source, receiver);

                FrequencyRetardedDoppler.Result result = FrequencyRetardedDoppler.calculate(frequency, c, source, receiver, -acceleration, 0.0, 0.0);

                double originalError = Math.abs(originalHz - exact.receivedHz());
                double modelError = Math.abs(result.receivedFrequencyHz() - exact.receivedHz());
                double travelError = Math.abs(result.travelTimeSeconds() - exact.travelTimeSeconds());

                System.out.printf(Locale.ROOT, "[FREQ-ENVELOPE] c=%.0f m/s | " + "Speed=%.1f m/s | Accel=%.1f m/s² | " + "OriginalError=%.6f Hz | " + "ModelError=%.9f Hz | " + "TravelError=%.9f us%n", c, velocity, acceleration, originalError, modelError, travelError * 1_000_000.0);

                String scenario = String.format(Locale.ROOT, "c=%.0f, omega=%.1f", c, omega);

                assertTrue(Double.isFinite(modelError), "Non-finite Doppler error: " + scenario);
                assertTrue(Double.isFinite(travelError), "Non-finite travel-time error: " + scenario);
                assertTrue(modelError <= originalError + 0.001, "Accelerated model regressed: " + scenario);

                if (c >= 1_000_000.0) {
                    assertTrue(modelError < 1.0, "Expected sub-Hz error: " + scenario);
                }

                if (c >= 299_792_458.0) {
                    assertTrue(modelError < 0.01, "Expected sub-0.01 Hz error: " + scenario);
                }
            }
        }
    }

    @Test
    void circularMotionTwentyHzAccelerationSamplingAccuracy() {
        final double frequency = 915_000_000.0;
        final double radius = 10.0;
        final double receiverX = 120.0;
        final double receiverY = 40.0;
        final double tickSeconds = 1.0 / 20.0;

        double[] propagationSpeeds = {
                10_000.0,
                100_000.0,
                1_000_000.0,
                299_792_458.0
        };

        double[] angularSpeeds = {
                2.7,
                12.0,
                20.0
        };

        FrequencyMotionSnapshot receiver = new FrequencyMotionSnapshot(receiverX, receiverY, 0.0, 0.0, 0.0, 0.0);

        for (double omega : angularSpeeds) {
            double speed = radius * omega;

            FrequencyMotionSnapshot current = new FrequencyMotionSnapshot(radius, 0.0, 0.0, 0.0, speed, 0.0);

            double previousAngle = -omega * tickSeconds;
            double previousVx = -speed * Math.sin(previousAngle);
            double previousVy = speed * Math.cos(previousAngle);

            double exactAx = -radius * omega * omega;
            double exactAy = 0.0;

            double sampledAx = (current.vx() - previousVx) / tickSeconds;
            double sampledAy = (current.vy() - previousVy) / tickSeconds;

            double exactAcceleration = Math.hypot(exactAx, exactAy);
            double sampledAcceleration = Math.hypot(sampledAx, sampledAy);

            assertTrue(Double.isFinite(sampledAcceleration));
            assertTrue(sampledAcceleration <= exactAcceleration + 1.0e-9);

            for (double c : propagationSpeeds) {
                CircularReference reference = exactCircularReception(frequency, c, radius, omega, receiverX, receiverY);
                double originalHz = FrequencyDoppler.receivedFrequencyHz(frequency, c, current, receiver);

                FrequencyRetardedDoppler.Result exact = FrequencyRetardedDoppler.calculate(frequency, c, current, receiver, exactAx, exactAy, 0.0);
                FrequencyRetardedDoppler.Result sampled = FrequencyRetardedDoppler.calculate(frequency, c, current, receiver, sampledAx, sampledAy, 0.0);

                double originalError = Math.abs(originalHz - reference.receivedHz());
                double exactError = Math.abs(exact.receivedFrequencyHz() - reference.receivedHz());
                double sampledError = Math.abs(sampled.receivedFrequencyHz() - reference.receivedHz());
                double samplingDelta = Math.abs(sampled.receivedFrequencyHz() - exact.receivedFrequencyHz());
                double addedError = sampledError - exactError;

                System.out.printf(
                        Locale.ROOT,
                        "[FREQ-20HZ] c=%.0f | speed=%.1f | " +
                                "omega=%.2f | ExactAccel=%.3f | " +
                                "SampledAccel=%.3f | " +
                                "OriginalError=%.6f Hz | " +
                                "ExactAccelError=%.6f Hz | " +
                                "TickAccelError=%.6f Hz | " +
                                "SamplingDelta=%.6f Hz | " +
                                "AddedError=%+.6f Hz%n",
                        c,
                        speed,
                        omega,
                        exactAcceleration,
                        sampledAcceleration,
                        originalError,
                        exactError,
                        sampledError,
                        samplingDelta,
                        addedError
                );

                String scenario = String.format(Locale.ROOT, "c=%.0f, omega=%.2f", c, omega);

                assertTrue(Double.isFinite(originalError), "Invalid original error: " + scenario);
                assertTrue(Double.isFinite(exactError), "Invalid exact-acceleration error: " + scenario);
                assertTrue(Double.isFinite(sampledError), "Invalid 20 Hz error: " + scenario);
                assertTrue(Double.isFinite(samplingDelta), "Invalid sampling difference: " + scenario);
                assertTrue(sampled.receivedFrequencyHz() > 0.0, "Invalid received frequency: " + scenario);
            }
        }
    }

    @Test
    void circularMotionSecondOrderAccelerationAccuracy() {
        final double frequency = 915_000_000.0;
        final double radius = 10.0;
        final double receiverX = 120.0;
        final double receiverY = 40.0;
        final double dt = 1.0 / 20.0;

        double[] propagationSpeeds = {
                10_000.0,
                100_000.0,
                1_000_000.0,
                299_792_458.0
        };

        double[] angularSpeeds = {
                2.7,
                12.0,
                20.0
        };

        FrequencyMotionSnapshot receiver = new FrequencyMotionSnapshot(receiverX, receiverY, 0.0, 0.0, 0.0, 0.0);

        for (double omega : angularSpeeds) {
            double speed = radius * omega;

            double vx0 = 0.0;
            double vy0 = speed;

            double angle1 = -omega * dt;
            double vx1 = -speed * Math.sin(angle1);
            double vy1 = speed * Math.cos(angle1);

            double angle2 = -omega * (2.0 * dt);
            double vx2 = -speed * Math.sin(angle2);
            double vy2 = speed * Math.cos(angle2);

            FrequencyMotionSnapshot source = new FrequencyMotionSnapshot(radius, 0.0, 0.0, vx0, vy0, 0.0);

            double exactAx = -radius * omega * omega;
            double exactAy = 0.0;

            double firstAx = (vx0 - vx1) / dt;
            double firstAy = (vy0 - vy1) / dt;

            double secondAx = (3.0 * vx0 - 4.0 * vx1 + vx2) / (2.0 * dt);
            double secondAy = (3.0 * vy0 - 4.0 * vy1 + vy2) / (2.0 * dt);

            double firstVectorError = Math.hypot(firstAx - exactAx, firstAy - exactAy);
            double secondVectorError = Math.hypot(secondAx - exactAx, secondAy - exactAy);

            System.out.printf(Locale.ROOT, "[FREQ-2ND-VECTOR] omega=%.2f | " + "speed=%.1f | ExactAccel=%.3f | " + "FirstVectorError=%.6f | " + "SecondVectorError=%.6f m/s²%n", omega, speed, -exactAx, firstVectorError, secondVectorError);

            assertTrue(secondVectorError < firstVectorError, "Second-order acceleration should be closer " + "to exact circular acceleration at omega=" + omega);

            for (double c : propagationSpeeds) {
                CircularReference reference = exactCircularReception(frequency, c, radius, omega, receiverX, receiverY);

                FrequencyRetardedDoppler.Result exact = FrequencyRetardedDoppler.calculate(frequency, c, source, receiver, exactAx, exactAy, 0.0);
                FrequencyRetardedDoppler.Result first = FrequencyRetardedDoppler.calculate(frequency, c, source, receiver, firstAx, firstAy, 0.0);
                FrequencyRetardedDoppler.Result second = FrequencyRetardedDoppler.calculate(frequency, c, source, receiver, secondAx, secondAy, 0.0);

                double exactError = Math.abs(exact.receivedFrequencyHz() - reference.receivedHz());
                double firstError = Math.abs(first.receivedFrequencyHz() - reference.receivedHz());
                double secondError = Math.abs(second.receivedFrequencyHz() - reference.receivedHz());
                double improvement = firstError - secondError;

                System.out.printf(
                        Locale.ROOT,
                        "[FREQ-2ND] c=%.0f | omega=%.2f | " +
                                "speed=%.1f | " +
                                "ExactError=%.9f Hz | " +
                                "FirstError=%.9f Hz | " +
                                "SecondError=%.9f Hz | " +
                                "Improvement=%+.9f Hz%n",
                        c,
                        omega,
                        speed,
                        exactError,
                        firstError,
                        secondError,
                        improvement
                );

                assertTrue(Double.isFinite(exactError));
                assertTrue(Double.isFinite(firstError));
                assertTrue(Double.isFinite(secondError));
                assertTrue(second.receivedFrequencyHz() > 0.0);
            }
        }
    }
}
