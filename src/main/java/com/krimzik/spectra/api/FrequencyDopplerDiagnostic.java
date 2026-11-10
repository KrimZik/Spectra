package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.consumers.FrequencyDopplerMatchConsumer;
import com.krimzik.spectra.api.motion.FrequencyAccelerationTracker;
import com.krimzik.spectra.api.motion.FrequencyMotion;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import com.krimzik.spectra.math.FrequencyDoppler;
import com.krimzik.spectra.math.FrequencyRetardedDoppler;
import com.mojang.brigadier.Command;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public final class FrequencyDopplerDiagnostic {
    private static final double F = 915_000_000.0;
    private static final double C = 10_000.0;
    private static final double RANGE = 1_000.0;
    private static final double TOLERANCE = 1.0;

    private static final double MAX_SOURCE_SPEED = 1_000.0;
    private static final double MAX_RECEIVER_SPEED = 1_000.0;
    private static final double MAX_SOURCE_ACCEL = 2_000.0;

    private static final int[] COUNTS = {10, 100, 1_000};
    private static final int WARMUP_TICKS = 40;
    private static final int TOTAL_TICKS = 200;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private FrequencyDopplerDiagnostic() {}
    private record Capture(FrequencyMotionSnapshot motion, FrequencyAccelerationTracker.Frame frame, UUID sublevelId, double shipSpeed, double relativeSpeed, boolean unavailableBody) {}

    private static final class TestEmitter implements FrequencyEmitter {
        private final FrequencySignal signal;
        private FrequencyMotionSnapshot motion;

        TestEmitter(FrequencySignal signal, FrequencyMotionSnapshot motion) {
            this.signal = signal;
            this.motion = motion;
        }

        @Override
        public boolean isEmitting() {
            return true;
        }

        @Override
        public FrequencySignal signal() {
            return signal;
        }

        @Override
        public Vec3 position() {
            return new Vec3(motion.x(), motion.y(), motion.z());
        }

        @Override
        public FrequencyMotionSnapshot motionSnapshot(ServerLevel level) {
            return motion;
        }
    }

    private static final class TestReceiver implements FrequencyReceiver {
        private FrequencyMotionSnapshot motion;
        private double tunedFrequency = F;

        @Override
        public Vec3 position() {
            return new Vec3(motion.x(), motion.y(), motion.z());
        }

        @Override
        public FrequencyMotionSnapshot motionSnapshot(ServerLevel level) {
            return motion;
        }

        @Override
        public double tunedFrequencyHz() {
            return tunedFrequency;
        }

        @Override
        public double toleranceHz() {
            return TOLERANCE;
        }
    }

    private static final class Counter implements FrequencyDopplerMatchConsumer {
        int count;
        int failures;
        double expectedHz;

        void reset(double expectedHz) {
            count = 0;
            failures = 0;
            this.expectedHz = expectedHz;
        }

        @Override
        public void accept(FrequencyEmitter emitter, FrequencySignal signal, double distanceSquared, double receivedFrequencyHz) {
            count++;

            double error = Math.abs(receivedFrequencyHz - expectedHz);
            double allowedError = Math.max(1.0e-5, Math.abs(expectedHz) * 1.0e-12);

            if (!Double.isFinite(receivedFrequencyHz) || error > allowedError) {
                failures++;
            }
        }
    }

    private static final class Session {
        final ServerLevel level;
        final Entity source;
        final FrequencyEngine[] engines = new FrequencyEngine[3];
        final TestEmitter[][] emitters = new TestEmitter[3][];
        final TestReceiver receiver = new TestReceiver();
        final Counter counter = new Counter();

        private Vec3 forecastFirst;
        private Vec3 forecastSecond;
        private long forecastTick = Long.MIN_VALUE;

        private int forecastChecks;
        private int secondOrderWins;

        private double firstErrorSum;
        private double secondErrorSum;
        private double firstErrorMax;
        private double secondErrorMax;

        private int pairedFrequencyChecks;
        private int pairedFrequencySkipped;
        private double pairedFrequencyDeltaSum;
        private double pairedFrequencyDeltaMax;

        final FrequencyEngine entityProbeEngine;
        final TestEmitter entityProbe;
        int entityProbeChecks;

        final long[] sampleNanos = new long[3];
        final long[] originalNanos = new long[3];
        final long[] acceleratedNanos = new long[3];

        FrequencyMotionSnapshot previousMotion;
        FrequencyAccelerationTracker.Frame previousFrame;
        UUID previousSublevel;
        long previousTick = Long.MIN_VALUE;
        boolean teleportPending;
        int teleportEvents;

        int ticks;
        int measuredTicks;
        int accelerationTicks;
        int nonzeroAccelerationTicks;
        int retardedTicks;
        int discontinuities;

        double maxShipSpeed;
        double maxRelativeSpeed;
        double sumRelativeSpeed;
        int relativeSamples;

        int worldSamples;
        int trackedSamples;
        int plotSamples;

        int frameTransitions;
        int worldToShipTransitions;
        int shipToWorldTransitions;
        int shipIdentityChanges;

        UUID lastSeenShipId;

        private final FrequencyMotionSnapshot[] history = new FrequencyMotionSnapshot[8];
        private final long[] historyTicks = new long[8];

        private boolean previousAccelerationValid;
        private double previousAx;
        private double previousAy;
        private double previousAz;

        private double maxAcceleration;
        private double maxJerk;
        private double maxFrequencyDifferenceHz;

        private double maxLinearErrorHz;
        private double maxHermiteErrorHz;
        private double maxLinearHermiteGapHz;

        private double sumLinearErrorHz;
        private double sumHermiteErrorHz;

        private double maxModelTravelSeconds;
        private double maxHermiteTravelSeconds;
        private double maxTravelTimeGapSeconds;

        private int historyChecks;
        private int historyUnavailable;

        private int kinematicChecks;
        private int radialChecks;

        private double sumVelocityMismatch;
        private double maxVelocityMismatch;
        private double maxPositionResidual;

        private double sumRadialMismatch;
        private double maxRadialMismatch;

        private record HistoryEstimate(double frequencyHz, double travelTimeSeconds) {}

        Session(ServerLevel level, Entity source) {
            this.level = level;
            this.source = source;

            Capture initial = capture(level, source);
            FrequencySignal signal = new FrequencySignal(F, 1.0, 0.0, C);

            entityProbeEngine = new FrequencyEngine(level);
            entityProbe = new TestEmitter(signal, initial.motion());
            entityProbeEngine.register(entityProbe);

            for (int group = 0; group < COUNTS.length; group++) {
                int count = COUNTS[group];

                engines[group] = new FrequencyEngine(level);
                emitters[group] = new TestEmitter[count];

                for (int i = 0; i < count; i++) {
                    TestEmitter emitter = new TestEmitter(signal, initial.motion());

                    emitters[group][i] = emitter;
                    engines[group].register(emitter);
                }
            }
        }

        boolean tick(ServerPlayer player, long tick) {
            Capture captured = capture(level, source);

            if (previousMotion != null && previousTick == tick - 1) {
                if (previousFrame != captured.frame()) {
                    frameTransitions++;
                }

                boolean wasOnShip = previousFrame != FrequencyAccelerationTracker.Frame.WORLD;
                boolean isOnShip = captured.frame() != FrequencyAccelerationTracker.Frame.WORLD;

                if (!wasOnShip && isOnShip) {
                    worldToShipTransitions++;
                } else if (wasOnShip && !isOnShip) {
                    shipToWorldTransitions++;
                }
            }

            UUID currentShipId = captured.sublevelId();

            if (currentShipId != null) {
                if (lastSeenShipId != null && !lastSeenShipId.equals(currentShipId)) {
                    shipIdentityChanges++;
                }

                lastSeenShipId = currentShipId;
            }

            switch (captured.frame()) {
                case WORLD -> worldSamples++;
                case TRACKED -> trackedSamples++;
                case PLOT -> plotSamples++;
            }

            maxShipSpeed = Math.max(maxShipSpeed, captured.shipSpeed());
            maxRelativeSpeed = Math.max(maxRelativeSpeed, captured.relativeSpeed());

            sumRelativeSpeed += captured.relativeSpeed();
            relativeSamples++;

            FrequencyMotionSnapshot sourceMotion = captured.motion();
            FrequencyMotionSnapshot receiverMotion = FrequencyMotion.captureEntity(level, player);

            receiver.motion = receiverMotion;

            double dx = receiverMotion.x() - sourceMotion.x();
            double dy = receiverMotion.y() - sourceMotion.y();
            double dz = receiverMotion.z() - sourceMotion.z();

            double distanceSquared = dx * dx + dy * dy + dz * dz;

            if (!(distanceSquared <= RANGE * RANGE)) {
                throw new IllegalStateException("Source moved outside the 1000 m test range");
            }

            if (speed(sourceMotion) > MAX_SOURCE_SPEED || speed(receiverMotion) > MAX_RECEIVER_SPEED) {
                throw new IllegalStateException("Motion exceeds diagnostic speed envelope");
            }

            boolean continuous = !captured.unavailableBody() && !teleportPending && previousMotion != null && previousTick == tick - 1 && previousFrame == captured.frame() && Objects.equals(previousSublevel, captured.sublevelId()) && positionDistanceSquared(previousMotion, sourceMotion) <= 4096.0;
            teleportPending = false;

            if (continuous && forecastTick == tick) {
                Vec3 actual = new Vec3(sourceMotion.vx(), sourceMotion.vy(), sourceMotion.vz());

                double firstError = actual.distanceTo(forecastFirst);
                double secondError = actual.distanceTo(forecastSecond);

                if (ticks >= WARMUP_TICKS) {
                    forecastChecks++;

                    firstErrorSum += firstError;
                    secondErrorSum += secondError;

                    firstErrorMax = Math.max(firstErrorMax, firstError);
                    secondErrorMax = Math.max(secondErrorMax, secondError);

                    if (secondError < firstError) {
                        secondOrderWins++;
                    }
                }
            }

            if (continuous) {
                double dxTick = sourceMotion.x() - previousMotion.x();
                double dyTick = sourceMotion.y() - previousMotion.y();
                double dzTick = sourceMotion.z() - previousMotion.z();

                double measuredVx = dxTick * 20.0;
                double measuredVy = dyTick * 20.0;
                double measuredVz = dzTick * 20.0;

                double averageVx = 0.5 * (previousMotion.vx() + sourceMotion.vx());
                double averageVy = 0.5 * (previousMotion.vy() + sourceMotion.vy());
                double averageVz = 0.5 * (previousMotion.vz() + sourceMotion.vz());

                double mismatchX = measuredVx - averageVx;
                double mismatchY = measuredVy - averageVy;
                double mismatchZ = measuredVz - averageVz;

                double mismatch = Math.hypot(Math.hypot(mismatchX, mismatchY), mismatchZ);

                kinematicChecks++;
                sumVelocityMismatch += mismatch;
                maxVelocityMismatch = Math.max(maxVelocityMismatch, mismatch);

                maxPositionResidual = Math.max(maxPositionResidual, mismatch * 0.05);

                if (distanceSquared > 0.0) {
                    double inverseDistance = 1.0 / Math.sqrt(distanceSquared);
                    double radialMismatch = Math.abs(Math.fma(mismatchX, dx, Math.fma(mismatchY, dy, mismatchZ * dz))) * inverseDistance;

                    radialChecks++;
                    sumRadialMismatch += radialMismatch;
                    maxRadialMismatch = Math.max(maxRadialMismatch, radialMismatch);
                }
            }

            if (!continuous) {
                Arrays.fill(history, null);
                previousAccelerationValid = false;
            }

            int historySlot = (int) tick & 7;
            history[historySlot] = sourceMotion;
            historyTicks[historySlot] = tick;

            double ax = 0.0;
            double ay = 0.0;
            double az = 0.0;
            boolean hasAcceleration = continuous;

            if (continuous) {
                ax = (sourceMotion.vx() - previousMotion.vx()) * 20.0;
                ay = (sourceMotion.vy() - previousMotion.vy()) * 20.0;
                az = (sourceMotion.vz() - previousMotion.vz()) * 20.0;

                double acceleration = Math.hypot(Math.hypot(ax, ay), az);
                if (!Double.isFinite(acceleration) || acceleration > MAX_SOURCE_ACCEL) {
                    throw new IllegalStateException("Measured acceleration exceeds 2000 m/s^2");
                }

                accelerationTicks++;

                if (acceleration > 0.0) {
                    nonzeroAccelerationTicks++;
                }

                maxAcceleration = Math.max(maxAcceleration, acceleration);

                if (previousAccelerationValid) {
                    double jx = (ax - previousAx) * 20.0;
                    double jy = (ay - previousAy) * 20.0;
                    double jz = (az - previousAz) * 20.0;

                    double jerk = Math.hypot(Math.hypot(jx, jy), jz);
                    maxJerk = Math.max(maxJerk, jerk);
                }

                previousAx = ax;
                previousAy = ay;
                previousAz = az;
                previousAccelerationValid = true;
            } else {
                discontinuities++;
            }

            boolean zeroAcceleration = !hasAcceleration || (ax == 0.0 && ay == 0.0 && az == 0.0) || distanceSquared == 0.0;
            if (!zeroAcceleration) {
                retardedTicks++;
            }

            double originalHz = FrequencyDoppler.receivedFrequencyHz(F, C, sourceMotion, receiverMotion);

            double acceleratedHz = originalHz;
            double modelTravelSeconds = Double.NaN;

            if (!zeroAcceleration) {
                FrequencyRetardedDoppler.Result result = FrequencyRetardedDoppler.calculate(F, C, sourceMotion, receiverMotion, ax, ay, az);

                acceleratedHz = result.receivedFrequencyHz();
                modelTravelSeconds = result.travelTimeSeconds();
            }

            forecastTick = Long.MIN_VALUE;

            if (continuous) {
                FrequencyMotionSnapshot older = historyAtTick(tick - 2L);

                if (older != null) {
                    double firstAx = (sourceMotion.vx() - previousMotion.vx()) * 20.0;
                    double firstAy = (sourceMotion.vy() - previousMotion.vy()) * 20.0;
                    double firstAz = (sourceMotion.vz() - previousMotion.vz()) * 20.0;

                    double secondAx = (3.0 * sourceMotion.vx() - 4.0 * previousMotion.vx() + older.vx()) * 10.0;
                    double secondAy = (3.0 * sourceMotion.vy() - 4.0 * previousMotion.vy() + older.vy()) * 10.0;
                    double secondAz = (3.0 * sourceMotion.vz() - 4.0 * previousMotion.vz() + older.vz()) * 10.0;

                    double firstMagnitude = Math.hypot(Math.hypot(firstAx, firstAy), firstAz);
                    double secondMagnitude = Math.hypot(Math.hypot(secondAx, secondAy), secondAz);

                    if (Double.isFinite(firstMagnitude) && firstMagnitude <= MAX_SOURCE_ACCEL && Double.isFinite(secondMagnitude) && secondMagnitude <= MAX_SOURCE_ACCEL) {
                        double secondHz = originalHz;

                        if (secondMagnitude > 0.0 && distanceSquared > 0.0) {
                            secondHz = FrequencyRetardedDoppler.calculate(F, C, sourceMotion, receiverMotion, secondAx, secondAy, secondAz).receivedFrequencyHz();
                        }

                        double frequencyDelta = Math.abs(secondHz - acceleratedHz);

                        if (ticks >= WARMUP_TICKS) {
                            pairedFrequencyChecks++;
                            pairedFrequencyDeltaSum += frequencyDelta;
                            pairedFrequencyDeltaMax = Math.max(pairedFrequencyDeltaMax, frequencyDelta);
                        }
                        forecastFirst = new Vec3(Math.fma(firstAx, 0.05, sourceMotion.vx()), Math.fma(firstAy, 0.05, sourceMotion.vy()), Math.fma(firstAz, 0.05, sourceMotion.vz()));
                        forecastSecond = new Vec3(Math.fma(secondAx, 0.05, sourceMotion.vx()), Math.fma(secondAy, 0.05, sourceMotion.vy()), Math.fma(secondAz, 0.05, sourceMotion.vz()));
                        forecastTick = tick + 1L;
                    } else if (ticks >= WARMUP_TICKS) {
                        pairedFrequencySkipped++;
                    }
                }
            }

            if (!zeroAcceleration) {
                maxFrequencyDifferenceHz = Math.max(maxFrequencyDifferenceHz, Math.abs(acceleratedHz - originalHz));

                HistoryEstimate linear = historicalFrequency(tick, receiverMotion, false);
                HistoryEstimate hermite = historicalFrequency(tick, receiverMotion, true);

                if (linear != null && hermite != null) {
                    historyChecks++;

                    double linearError = Math.abs(acceleratedHz - linear.frequencyHz());
                    double hermiteError = Math.abs(acceleratedHz - hermite.frequencyHz());
                    double interpolationGap = Math.abs(linear.frequencyHz() - hermite.frequencyHz());

                    maxLinearErrorHz = Math.max(maxLinearErrorHz, linearError);
                    maxHermiteErrorHz = Math.max(maxHermiteErrorHz, hermiteError);
                    maxLinearHermiteGapHz = Math.max(maxLinearHermiteGapHz, interpolationGap);

                    sumLinearErrorHz += linearError;
                    sumHermiteErrorHz += hermiteError;

                    maxModelTravelSeconds = Math.max(maxModelTravelSeconds, modelTravelSeconds);

                    maxHermiteTravelSeconds = Math.max(maxHermiteTravelSeconds, hermite.travelTimeSeconds());
                    maxTravelTimeGapSeconds = Math.max(maxTravelTimeGapSeconds, Math.abs(modelTravelSeconds - hermite.travelTimeSeconds()));
                } else {
                    historyUnavailable++;
                }
            }

            boolean measuring = ticks >= WARMUP_TICKS;

            for (int group = 0; group < COUNTS.length; group++) {
                FrequencyEngine engine = engines[group];
                TestEmitter[] sources = emitters[group];

                long samplingStart = System.nanoTime();

                for (TestEmitter emitter : sources) {
                    emitter.motion = sourceMotion;
                    engine.sampleEmitterMotion(emitter, sourceMotion, captured.frame(), captured.sublevelId(), continuous);
                }

                long samplingElapsed = System.nanoTime() - samplingStart;

                long originalElapsed;
                long acceleratedElapsed;

                if ((tick & 1L) == 0L) {
                    originalElapsed = query(engine, false, originalHz, sources.length);
                    acceleratedElapsed = query(engine, true, acceleratedHz, sources.length);
                } else {
                    acceleratedElapsed = query(engine, true, acceleratedHz, sources.length);
                    originalElapsed = query(engine, false, originalHz, sources.length);
                }

                if (measuring) {
                    sampleNanos[group] += samplingElapsed;
                    originalNanos[group] += originalElapsed;
                    acceleratedNanos[group] += acceleratedElapsed;
                }
            }

            entityProbe.motion = sourceMotion;
            entityProbeEngine.sampleEmitterMotion(entityProbe, source, continuous);

            query(entityProbeEngine, true, acceleratedHz, 1);
            entityProbeChecks++;

            previousMotion = sourceMotion;
            previousFrame = captured.frame();
            previousSublevel = captured.sublevelId();
            previousTick = tick;

            ticks++;
            if (measuring) measuredTicks++;

            return ticks >= TOTAL_TICKS;
        }


        private FrequencyMotionSnapshot historyAtTick(long tick) {
            int slot = (int) tick & 7;
            return historyTicks[slot] == tick ? history[slot] : null;
        }

        private FrequencyMotionSnapshot historyAt(long currentTick, double delaySeconds, boolean hermite) {
            if (!Double.isFinite(delaySeconds) || delaySeconds < 0.0) {
                return null;
            }

            double sampleTick = currentTick - delaySeconds * 20.0;

            if (sampleTick >= currentTick) {
                return historyAtTick(currentTick);
            }

            long beforeTick = (long) Math.floor(sampleTick);
            long afterTick = beforeTick + 1L;

            if (beforeTick < currentTick - 7L || afterTick > currentTick) {
                return null;
            }

            FrequencyMotionSnapshot before = historyAtTick(beforeTick);
            FrequencyMotionSnapshot after = historyAtTick(afterTick);

            if (before == null || after == null) {
                return null;
            }

            double u = sampleTick - beforeTick;

            if (!hermite) {
                return new FrequencyMotionSnapshot(
                        Math.fma(u, after.x() - before.x(), before.x()),
                        Math.fma(u, after.y() - before.y(), before.y()),
                        Math.fma(u, after.z() - before.z(), before.z()),
                        Math.fma(u, after.vx() - before.vx(), before.vx()),
                        Math.fma(u, after.vy() - before.vy(), before.vy()),
                        Math.fma(u, after.vz() - before.vz(), before.vz())
                );
            }

            double u2 = u * u;
            double oneMinusU = 1.0 - u;

            double h01 = u2 * (3.0 - 2.0 * u);
            double h10dt = u * oneMinusU * oneMinusU * 0.05;
            double h11dt = -u2 * oneMinusU * 0.05;

            double dh01 = 120.0 * u * oneMinusU;
            double dh10 = 3.0 * u2 - 4.0 * u + 1.0;
            double dh11 = 3.0 * u2 - 2.0 * u;

            return new FrequencyMotionSnapshot(
                    hermitePosition(before.x(), after.x(), before.vx(), after.vx(), h01, h10dt, h11dt),
                    hermitePosition(before.y(), after.y(), before.vy(), after.vy(), h01, h10dt, h11dt),
                    hermitePosition(before.z(), after.z(), before.vz(), after.vz(), h01, h10dt, h11dt),
                    hermiteVelocity(before.x(), after.x(), before.vx(), after.vx(), dh01, dh10, dh11),
                    hermiteVelocity(before.y(), after.y(), before.vy(), after.vy(), dh01, dh10, dh11),
                    hermiteVelocity(before.z(), after.z(), before.vz(), after.vz(), dh01, dh10, dh11)
            );
        }

        private static double hermitePosition(double p0, double p1, double v0, double v1, double h01, double h10dt, double h11dt) {
            return Math.fma(h01, p1 - p0, Math.fma(h10dt, v0, Math.fma(h11dt, v1, p0)));
        }

        private static double hermiteVelocity(double p0, double p1, double v0, double v1, double dh01, double dh10, double dh11) {
            return Math.fma(dh01, p1 - p0, Math.fma(dh10, v0, dh11 * v1));
        }

        private double historicalGap(long tick, double delaySeconds, FrequencyMotionSnapshot receiverMotion, boolean hermite) {
            FrequencyMotionSnapshot past = historyAt(tick, delaySeconds, hermite);

            if (past == null) return Double.NaN;

            double dx = receiverMotion.x() - past.x();
            double dy = receiverMotion.y() - past.y();
            double dz = receiverMotion.z() - past.z();

            return Math.hypot(Math.hypot(dx, dy), dz) - C * delaySeconds;
        }

        private HistoryEstimate historicalFrequency(long tick, FrequencyMotionSnapshot receiverMotion, boolean hermite) {
            FrequencyMotionSnapshot current = historyAtTick(tick);

            if (current == null) return null;

            double dx = receiverMotion.x() - current.x();
            double dy = receiverMotion.y() - current.y();
            double dz = receiverMotion.z() - current.z();

            double currentDistance = Math.hypot(Math.hypot(dx, dy), dz);
            if (!(currentDistance > 0.0) || !Double.isFinite(currentDistance)) {
                return null;
            }

            double low = 0.0;
            double high = Math.nextUp(currentDistance / (C - MAX_SOURCE_SPEED));
            double upperGap = historicalGap(tick, high, receiverMotion, hermite);

            if (!Double.isFinite(upperGap) || upperGap > 0.0) {
                return null;
            }

            for (int i = 0; i < 36; i++) {
                double mid = (low + high) * 0.5;

                double gap = historicalGap(
                        tick, mid, receiverMotion, hermite
                );

                if (!Double.isFinite(gap)) {
                    return null;
                }

                if (gap > 0.0) {
                    low = mid;
                } else {
                    high = mid;
                }
            }

            double delay = (low + high) * 0.5;

            FrequencyMotionSnapshot emission = historyAt(tick, delay, hermite);
            if (emission == null) return null;

            dx = receiverMotion.x() - emission.x();
            dy = receiverMotion.y() - emission.y();
            dz = receiverMotion.z() - emission.z();

            double distance = Math.hypot(Math.hypot(dx, dy), dz);
            if (!(distance > 0.0) || !Double.isFinite(distance)) {
                return null;
            }

            double nx = dx / distance;
            double ny = dy / distance;
            double nz = dz / distance;

            double sourceRadial = Math.fma(emission.vx(), nx, Math.fma(emission.vy(), ny, emission.vz() * nz));
            double receiverRadial = Math.fma(receiverMotion.vx(), nx, Math.fma(receiverMotion.vy(), ny, receiverMotion.vz() * nz));

            if (Math.abs(sourceRadial) >= C || Math.abs(receiverRadial) >= C) {
                return null;
            }

            double receivedHz = F * ((C - receiverRadial) / (C - sourceRadial));

            if (!Double.isFinite(receivedHz) || receivedHz <= 0.0) {
                return null;
            }

            return new HistoryEstimate(receivedHz, delay);
        }

        private long query(FrequencyEngine engine, boolean accelerated, double expectedHz, int expectedCount) {
            receiver.tunedFrequency = expectedHz;
            counter.reset(expectedHz);

            long start = System.nanoTime();

            if (accelerated) {
                engine.forEachAccelerationDopplerMatchingInRange(receiver, RANGE, C, MAX_SOURCE_SPEED, MAX_SOURCE_ACCEL, MAX_RECEIVER_SPEED, counter);
            } else {
                engine.forEachDopplerMatchingInRange(receiver, RANGE, C, MAX_SOURCE_SPEED, MAX_RECEIVER_SPEED, counter);
            }

            long elapsed = System.nanoTime() - start;

            if (counter.count != expectedCount || counter.failures != 0) {
                throw new IllegalStateException(String.format(Locale.ROOT, "%s N=%d matches=%d failures=%d", accelerated ? "Accelerated" : "Original", expectedCount, counter.count, counter.failures));
            }

            return elapsed;
        }

        void report(ServerPlayer player) {
            if (accelerationTicks + discontinuities != ticks || retardedTicks > nonzeroAccelerationTicks || historyChecks > retardedTicks || entityProbeChecks != ticks) {
                throw new IllegalStateException("Doppler diagnostic counters are inconsistent");
            }

            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] PASS | %d ticks | %d measured | " + "validAccel=%d | nonzeroAccel=%d | " + "retarded=%d | discontinuities=%d", ticks, measuredTicks, accelerationTicks, nonzeroAccelerationTicks, retardedTicks, discontinuities)));
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] PeakAccel=%.3f m/s² | " + "PeakJerk=%.3f m/s³ | OriginalVsRetarded=%.3f Hz", maxAcceleration, maxJerk, maxFrequencyDifferenceHz)));
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] HistoryChecks=%d | Unavailable=%d", historyChecks, historyUnavailable)));

            if (historyChecks > 0) {
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] Linear: MaxError=%.3f Hz | MeanError=%.3f Hz", maxLinearErrorHz, sumLinearErrorHz / historyChecks)));
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] Hermite: MaxError=%.3f Hz | MeanError=%.3f Hz", maxHermiteErrorHz, sumHermiteErrorHz / historyChecks)));
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] LinearHermiteGap=%.3f Hz", maxLinearHermiteGapHz)));
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] Flight: ModelMax=%.4f ms | HermiteMax=%.4f ms | MaxGap=%.4f ms", maxModelTravelSeconds * 1000.0, maxHermiteTravelSeconds * 1000.0, maxTravelTimeGapSeconds * 1000.0)));

                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] Kinematics: Samples=%d | AvgMismatch=%.4f m/s | MaxMismatch=%.4f m/s", kinematicChecks, kinematicChecks == 0 ? 0.0 : sumVelocityMismatch / kinematicChecks, maxVelocityMismatch)));
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] MaxPositionResidual=%.6f m", maxPositionResidual)));
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] Radial: AvgMismatch=%.4f m/s | MaxMismatch=%.4f m/s", radialChecks == 0 ? 0.0 : sumRadialMismatch / radialChecks, maxRadialMismatch)));
            } else {
                player.sendSystemMessage(Component.literal("[FreqDoppler] Historical comparison unavailable"));
            }

            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] Sable frames: WORLD=%d | TRACKED=%d | PLOT=%d", worldSamples, trackedSamples, plotSamples)));
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] FrameTransitions=%d | " + "WorldToShip=%d | ShipToWorld=%d | " + "ShipIdChanges=%d", frameTransitions, worldToShipTransitions, shipToWorldTransitions, shipIdentityChanges)));
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] EntityOverloadChecks=%d/%d", entityProbeChecks, ticks)));
            player.sendSystemMessage(Component.literal("[FreqDoppler] TeleportEvents=" + teleportEvents));

            if (forecastChecks > 0) {
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] A/B Forecast: N=%d | " + "FirstMAE=%.5f m/s | SecondMAE=%.5f m/s", forecastChecks, firstErrorSum / forecastChecks, secondErrorSum / forecastChecks)));
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] A/B Peaks: " + "First=%.5f m/s | Second=%.5f m/s | " + "SecondWins=%d/%d", firstErrorMax, secondErrorMax, secondOrderWins, forecastChecks)));
            }

            if (pairedFrequencyChecks > 0) {
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] A/B Doppler: N=%d | " + "MeanDelta=%.3f Hz | MaxDelta=%.3f Hz | " + "Skipped=%d", pairedFrequencyChecks, pairedFrequencyDeltaSum / pairedFrequencyChecks, pairedFrequencyDeltaMax, pairedFrequencySkipped)));
            }
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] ShipPeak=%.4f m/s | RelativeAvg=%.4f m/s | RelativePeak=%.4f m/s", maxShipSpeed, relativeSamples == 0 ? 0.0 : sumRelativeSpeed / relativeSamples, maxRelativeSpeed)));

            for (int i = 0; i < COUNTS.length; i++) {
                double divisor = measuredTicks * 1000.0;
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqDoppler] N=%d Sampling=%.2f us | " + "Original=%.2f us | Accelerated=%.2f us", COUNTS[i], sampleNanos[i] / divisor, originalNanos[i] / divisor, acceleratedNanos[i] / divisor)));
            }
        }

        void close() {
            entityProbeEngine.shutdown();

            for (FrequencyEngine engine : engines) {
                if (engine != null) {
                    engine.shutdown();
                }
            }
        }
    }

    public static void noteTeleport(Entity entity) {
        for (Session session : SESSIONS.values()) {
            if (session.source == entity) {
                session.teleportPending = true;
                session.teleportEvents++;
            }
        }
    }

    static String sourceAbortReason(Entity source, ServerLevel level) {
        if (source instanceof LivingEntity living && living.isDeadOrDying()) {
            return "source died";
        }

        if (source.isRemoved()) {
            return "source was removed";
        }

        if (source.level() != level) {
            return "source left the test level";
        }

        return null;
    }

    public static int start(ServerPlayer player, Entity target) {
        ServerLevel level = player.serverLevel();

        if (target == player || target.isRemoved() || target.level() != level) {
            player.sendSystemMessage(Component.literal("[FreqDoppler] Select a different entity " + "in the same dimension."));
            return Command.SINGLE_SUCCESS;
        }

        stop(player.getUUID());

        Session session = new Session(level, target);
        SESSIONS.put(player.getUUID(), session);

        player.sendSystemMessage(Component.literal("[FreqDoppler] Started: 10/100/1000 emitters, " + "200 ticks, 40 warmup ticks. " + "Move or rotate the source's Sable ship."));

        return Command.SINGLE_SUCCESS;
    }

    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;

        long tick = server.getTickCount();
        Iterator<Map.Entry<UUID, Session>> iterator = SESSIONS.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            Session session = entry.getValue();

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());

            if (player == null || player.serverLevel() != session.level) {
                session.close();
                iterator.remove();
                continue;
            }

            String abortReason = sourceAbortReason(session.source, session.level);
            if (abortReason != null) {player.sendSystemMessage(Component.literal("[FreqDoppler] ABORT: " + abortReason + " after " + session.ticks + "/" + TOTAL_TICKS + " ticks."));
                session.close();
                iterator.remove();
                continue;
            }

            boolean finished = false;

            try {
                finished = session.tick(player, tick);

                if (finished) {
                    session.report(player);
                }
            } catch (RuntimeException exception) {
                player.sendSystemMessage(Component.literal("[FreqDoppler] FAIL: " + exception.getMessage()));
                finished = true;
            }

            if (finished) {
                session.close();
                iterator.remove();
            }
        }
    }

    public static void stop(UUID playerId) {
        Session session = SESSIONS.remove(playerId);
        if (session != null) session.close();
    }

    public static void clear() {
        for (Session session : SESSIONS.values()) {
            session.close();
        }
        SESSIONS.clear();
    }

    private static Capture capture(ServerLevel level, Entity entity) {
        FrequencyMotion.EntityCapture captured = FrequencyMotion.captureEntityDetailed(level, entity);

        FrequencyMotionSnapshot motion = captured.motion();
        Vec3 shipVelocity = captured.shipVelocity();

        double relativeVx = motion.vx() - shipVelocity.x;
        double relativeVy = motion.vy() - shipVelocity.y;
        double relativeVz = motion.vz() - shipVelocity.z;

        return new Capture(motion, captured.frame(), captured.sublevelId(), shipVelocity.length(), Math.hypot(Math.hypot(relativeVx, relativeVy), relativeVz), captured.unavailableBody());
    }

    private static double speed(FrequencyMotionSnapshot motion) {
        return Math.hypot(Math.hypot(motion.vx(), motion.vy()), motion.vz());
    }

    private static double positionDistanceSquared(FrequencyMotionSnapshot a, FrequencyMotionSnapshot b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double dz = b.z() - a.z();

        return Math.fma(dx, dx, Math.fma(dy, dy, dz * dz));
    }
}
