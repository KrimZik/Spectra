package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.consumers.FrequencyDopplerMatchConsumer;
import com.krimzik.spectra.api.consumers.FrequencyMatchConsumer;
import com.krimzik.spectra.api.consumers.FrequencyReceptionConsumer;
import com.krimzik.spectra.api.motion.FrequencyMotion;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import com.krimzik.spectra.api.motion.FrequencyAccelerationTracker;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import com.krimzik.spectra.math.*;

import it.unimi.dsi.fastutil.doubles.Double2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class FrequencyEngine {
    private final IdentityHashMap<FrequencyEmitter, Double> emitters = new IdentityHashMap<>();
    private final NavigableMap<Double, FrequencyBucket> byFrequency = new TreeMap<>();
    private final IdentityHashMap<FrequencyEmitter, EmitterMotionSample> emitterMotionSamples = new IdentityHashMap<>();
    private final Map<Long, Set<EmitterRegistration>> chunkRegistrations = new HashMap<>();
    private final Map<Entity, Set<EmitterRegistration>> entityRegistrations = new IdentityHashMap<>();
    private final Map<UUID, Set<FrequencyRegistration>> sublevelRegistrations = new HashMap<>();

    boolean sableObserverAttached;

    private static final class EmitterMotionSample {
        final FrequencyAccelerationTracker tracker = new FrequencyAccelerationTracker();

        long tick = Long.MIN_VALUE;
        FrequencyMotionSnapshot motion;
        FrequencyAccelerationTracker.Acceleration acceleration;
        FrequencyAccelerationTracker.Frame frame;
        UUID sublevelId;
        UUID entityId;
        long teleportEpoch = Long.MIN_VALUE;
    }

    private final ServerLevel level;
    private boolean stopped;
    private int activeQueries;

    FrequencyEngine(ServerLevel level) {
        this.level = Objects.requireNonNull(level);
    }

    private final class EmitterRegistration implements FrequencyRegistration {
        private final FrequencyEmitter emitter;
        private final Long ownerChunk;
        private final Entity ownerEntity;
        private boolean removed;

        private EmitterRegistration(
                FrequencyEmitter emitter,
                Long ownerChunk,
                Entity ownerEntity) {

            this.emitter = emitter;
            this.ownerChunk = ownerChunk;
            this.ownerEntity = ownerEntity;
        }

        @Override
        public void close() {
            if (removed) return;

            if (!stopped) {
                checkWritable();

                Double frequency = emitters.remove(emitter);
                if (frequency != null) {
                    removeFromIndex(emitter, frequency);
                }

                emitterMotionSamples.remove(emitter);

                if (ownerChunk != null) {
                    Set<EmitterRegistration> bucket = chunkRegistrations.get(ownerChunk);
                    if (bucket != null) {
                        bucket.remove(this);

                        if (bucket.isEmpty()) {
                            chunkRegistrations.remove(ownerChunk);
                        }
                    }
                }

                if (ownerEntity != null) {
                    Set<EmitterRegistration> bucket = entityRegistrations.get(ownerEntity);
                    if (bucket != null) {
                        bucket.remove(this);

                        if (bucket.isEmpty()) {
                            entityRegistrations.remove(ownerEntity);
                        }
                    }
                }
            }

            removed = true;
        }
    }

    public FrequencyRegistration register(FrequencyEmitter emitter) {
        return registerInternal(emitter, null, null);
    }

    public FrequencyRegistration registerChunkBound(BlockPos ownerPos, FrequencyEmitter emitter) {
        Objects.requireNonNull(ownerPos);
        long chunkKey = ChunkPos.asLong(ownerPos.getX() >> 4, ownerPos.getZ() >> 4);
        return registerInternal(emitter, chunkKey, null);
    }

    FrequencyRegistration registerEntityBound(Entity owner, FrequencyEmitter emitter) {
        Objects.requireNonNull(owner);

        if (owner.isRemoved() || owner.level() != level) {
            throw new IllegalArgumentException("Emitter owner is removed or belongs to another level");
        }

        return registerInternal(emitter, null, owner);
    }

    private FrequencyRegistration registerInternal(FrequencyEmitter emitter, Long ownerChunk, Entity ownerEntity) {
        Objects.requireNonNull(emitter);
        checkWritable();

        if (emitters.containsKey(emitter)) {
            throw new IllegalStateException("Emitter already registered");
        }

        FrequencySignal signal = Objects.requireNonNull(emitter.signal());
        double frequency = signal.frequencyHz();

        EmitterRegistration registration = new EmitterRegistration(emitter, ownerChunk, ownerEntity);

        emitters.put(emitter, frequency);
        addToIndex(emitter, frequency);

        if (ownerChunk != null) {
            chunkRegistrations.computeIfAbsent(ownerChunk, unused -> new HashSet<>()).add(registration);
        }

        if (ownerEntity != null) {
            entityRegistrations.computeIfAbsent(ownerEntity, unused -> new HashSet<>()).add(registration);
        }

        return registration;
    }

    void onEntityLeave(Entity entity) {
        Objects.requireNonNull(entity);
        checkWritable();

        Set<EmitterRegistration> registrations = entityRegistrations.remove(entity);
        if (registrations == null) {
            return;
        }

        for (EmitterRegistration registration : registrations) {
            registration.close();
        }
    }

    void onChunkUnload(long chunkKey) {
        checkWritable();
        Set<EmitterRegistration> registrations = chunkRegistrations.remove(chunkKey);
        if (registrations == null) return;
        for (EmitterRegistration registration : registrations) {
            registration.close();
        }
    }

    FrequencyRegistration registerSublevelBound(UUID sublevelId, FrequencyEmitter emitter) {
        Objects.requireNonNull(sublevelId);
        Objects.requireNonNull(emitter);
        checkWritable();

        FrequencyRegistration delegate = register(emitter);

        final class BoundRegistration implements FrequencyRegistration {
            private boolean closed;

            @Override
            public void close() {
                if (closed) return;

                delegate.close();
                closed = true;

                Set<FrequencyRegistration> registrations = sublevelRegistrations.get(sublevelId);
                if (registrations != null) {
                    registrations.remove(this);

                    if (registrations.isEmpty()) {
                        sublevelRegistrations.remove(sublevelId);
                    }
                }
            }
        }

        FrequencyRegistration bound = new BoundRegistration();
        sublevelRegistrations.computeIfAbsent(sublevelId, unused -> new HashSet<>()).add(bound);

        return bound;
    }

    void onSublevelRemoved(UUID sublevelId) {
        if (stopped) return;

        Objects.requireNonNull(sublevelId);
        checkWritable();

        Set<FrequencyRegistration> registrations = sublevelRegistrations.remove(sublevelId);
        if (registrations == null) return;

        for (FrequencyRegistration registration : registrations) {
            registration.close();
        }
    }

    public void updateEmitter(FrequencyEmitter emitter) {
        Objects.requireNonNull(emitter);
        checkWritable();

        Double previousFrequency = emitters.get(emitter);
        if (previousFrequency == null) throw new IllegalStateException("Emitter is not registered");

        FrequencySignal signal = Objects.requireNonNull(emitter.signal());
        double newFrequency = signal.frequencyHz();

        if (Double.compare(previousFrequency, newFrequency) == 0) return;

        removeFromIndex(emitter, previousFrequency);
        addToIndex(emitter, newFrequency);

        emitters.put(emitter, newFrequency);
    }

    public void sampleEmitterMotion(FrequencyEmitter emitter, FrequencyMotionSnapshot motion, FrequencyAccelerationTracker.Frame frame, UUID sublevelId, boolean continuous) {
        Objects.requireNonNull(emitter);
        Objects.requireNonNull(motion);
        Objects.requireNonNull(frame);
        checkWritable();

        if (!emitters.containsKey(emitter)) {
            throw new IllegalStateException("Emitter is not registered");
        }

        long tick = level.getServer().getTickCount();

        EmitterMotionSample sample = emitterMotionSamples.computeIfAbsent(emitter, unused -> new EmitterMotionSample());
        if (continuous && sample.tick == tick && sample.frame == frame && Objects.equals(sample.sublevelId, sublevelId)) {
            return;
        }

        FrequencyAccelerationTracker.Acceleration acceleration = sample.tracker.observe(motion, tick, level.dimension(), sublevelId, frame, continuous);

        sample.tick = tick;
        sample.motion = motion;
        sample.acceleration = acceleration;
        sample.frame = frame;
        sample.sublevelId = sublevelId;
        sample.entityId = null;
        sample.teleportEpoch = Long.MIN_VALUE;
    }

    public void sampleEmitterMotion(FrequencyEmitter emitter, Entity entity, boolean continuous) {
        Objects.requireNonNull(emitter);
        Objects.requireNonNull(entity);
        checkWritable();

        if (!emitters.containsKey(emitter)) {
            throw new IllegalStateException("Emitter is not registered");
        }

        if (entity.isRemoved() || entity.level() != level) {
            throw new IllegalArgumentException("Emitter entity is removed or belongs to another level");
        }

        long epoch = FrequencyMotion.teleportEpoch(entity);
        UUID entityId = entity.getUUID();

        EmitterMotionSample previous = emitterMotionSamples.get(emitter);
        boolean sourceContinuous = continuous && (previous == null || (Objects.equals(previous.entityId, entityId) && previous.teleportEpoch == epoch));

        FrequencyMotion.EntityCapture captured = FrequencyMotion.captureEntityDetailed(level, entity);
        sampleEmitterMotion(emitter, captured.motion(), captured.frame(), captured.sublevelId(), sourceContinuous && !captured.unavailableBody());

        EmitterMotionSample current = emitterMotionSamples.get(emitter);
        current.entityId = entityId;
        current.teleportEpoch = epoch;
    }

    public void forEachMatching(FrequencyReceiver receiver, BiConsumer<FrequencyEmitter, FrequencySignal> consumer) {
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(consumer);
        checkRunning();

        double frequency = receiver.tunedFrequencyHz();
        double tolerance = receiver.toleranceHz();

        if (!Double.isFinite(frequency) || frequency <= 0.0 || !Double.isFinite(tolerance) || tolerance < 0.0)
            throw new IllegalArgumentException("Invalid receiver tuning");

        double minFrequency = frequency - tolerance;
        double maxFrequency = frequency + tolerance;

        activeQueries++;

        try {
            for (FrequencyBucket bucket : byFrequency.subMap(minFrequency, true, maxFrequency, true).values()) {
                for (int i = 0; i < bucket.size; i++) {
                    FrequencyEmitter emitter = bucket.get(i);
                    if (!emitter.isEmitting()) continue;

                    FrequencySignal signal = Objects.requireNonNull(emitter.signal());
                    if (Math.abs(signal.frequencyHz() - frequency) > tolerance)
                        continue;

                    consumer.accept(emitter, signal);
                }
            }
        } finally {
            activeQueries--;
        }
    }

    public void forEachMatchingInRange(FrequencyReceiver receiver, double rangeMeters, FrequencyMatchConsumer consumer) {
        Objects.requireNonNull(consumer);
        forEachInRange(receiver, rangeMeters, consumer, null);
    }

    public void forEachDopplerMatchingInRange(FrequencyReceiver receiver, double rangeMeters, double minPropagationSpeed, double maxEmitterSpeed, double maxReceiverSpeed, FrequencyDopplerMatchConsumer consumer) {
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(consumer);
        checkRunning();

        FrequencyMotionSnapshot receiverMotion = Objects.requireNonNull(receiver.motionSnapshot(level));

        forEachDopplerMatchingInRange(
                receiver,
                receiverMotion,
                rangeMeters,
                minPropagationSpeed,
                maxEmitterSpeed,
                maxReceiverSpeed,
                emitter -> Objects.requireNonNull(emitter.motionSnapshot(level)),
                consumer
        );
    }

    void forEachDopplerMatchingInRange(
            FrequencyReceiver receiver,
            FrequencyMotionSnapshot receiverMotion,
            double rangeMeters,
            double minPropagationSpeed,
            double maxEmitterSpeed,
            double maxReceiverSpeed,
            Function<FrequencyEmitter, FrequencyMotionSnapshot> emitterMotion,
            FrequencyDopplerMatchConsumer consumer
    ) {
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(receiverMotion);
        Objects.requireNonNull(emitterMotion);
        Objects.requireNonNull(consumer);
        checkRunning();

        if (!Double.isFinite(rangeMeters) || rangeMeters < 0.0) {
            throw new IllegalArgumentException("Invalid signal range");
        }

        double frequency = receiver.tunedFrequencyHz();
        double tolerance = receiver.toleranceHz();

        FrequencyDopplerBounds.Range candidates = FrequencyDopplerBounds.candidateRange(frequency, tolerance, minPropagationSpeed, maxEmitterSpeed, maxReceiverSpeed);

        double receiverSpeed = Math.hypot(Math.hypot(receiverMotion.vx(), receiverMotion.vy()), receiverMotion.vz());
        if (receiverSpeed > maxReceiverSpeed) {
            throw new IllegalArgumentException("Receiver velocity exceeds the declared Doppler bound");
        }

        double rangeSquared = rangeMeters * rangeMeters;

        activeQueries++;

        try {
            for (FrequencyBucket bucket : byFrequency.subMap(candidates.minHz(), true, candidates.maxHz(), true).values()) {
                for (int i = 0; i < bucket.size; i++) {
                    FrequencyEmitter emitter = bucket.get(i);
                    if (!emitter.isEmitting()) continue;

                    FrequencySignal signal = Objects.requireNonNull(emitter.signal());
                    if (!candidates.contains(signal.frequencyHz())) continue;

                    if (signal.propagationSpeed() < minPropagationSpeed) {
                        throw new IllegalArgumentException("Signal propagation speed is below the declared minimum");
                    }

                    FrequencyMotionSnapshot sourceMotion = Objects.requireNonNull(emitterMotion.apply(emitter));

                    double dx = receiverMotion.x() - sourceMotion.x();
                    double dy = receiverMotion.y() - sourceMotion.y();
                    double dz = receiverMotion.z() - sourceMotion.z();

                    double distanceSquared = dx * dx + dy * dy + dz * dz;
                    if (!(distanceSquared <= rangeSquared)) continue;

                    double emitterSpeed = Math.hypot(Math.hypot(sourceMotion.vx(), sourceMotion.vy()), sourceMotion.vz());
                    if (emitterSpeed > maxEmitterSpeed) {
                        throw new IllegalArgumentException("Emitter velocity exceeds the declared Doppler bound");
                    }

                    double receivedFrequency = FrequencyDoppler.receivedFrequencyHz(signal.frequencyHz(), signal.propagationSpeed(), sourceMotion, receiverMotion);

                    if (Math.abs(receivedFrequency - frequency) > tolerance) {
                        continue;
                    }

                    consumer.accept(emitter, signal, distanceSquared, receivedFrequency);
                }
            }
        } finally {
            activeQueries--;
        }
    }

    public void forEachAccelerationDopplerMatchingInRange(FrequencyReceiver receiver, double rangeMeters, double minPropagationSpeed, double maxEmitterSpeed, double maxEmitterAcceleration, double maxReceiverSpeed, FrequencyDopplerMatchConsumer consumer) {
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(consumer);
        checkRunning();

        if (maxEmitterAcceleration == 0.0) {
            forEachDopplerMatchingInRange(receiver, rangeMeters, minPropagationSpeed, maxEmitterSpeed, maxReceiverSpeed, consumer);
            return;
        }

        FrequencyMotionSnapshot receiverMotion = Objects.requireNonNull(receiver.motionSnapshot(level));

        double frequency = receiver.tunedFrequencyHz();
        double tolerance = receiver.toleranceHz();

        FrequencyDopplerBounds.Range candidates = FrequencyAccelerationDopplerBounds.candidateRange(frequency, tolerance, minPropagationSpeed, rangeMeters, maxEmitterSpeed, maxEmitterAcceleration, maxReceiverSpeed);

        double receiverSpeed = Math.hypot(Math.hypot(receiverMotion.vx(), receiverMotion.vy()), receiverMotion.vz());
        if (receiverSpeed > maxReceiverSpeed) {
            throw new IllegalArgumentException("Receiver velocity exceeds the declared Doppler bound");
        }

        double rangeSquared = rangeMeters * rangeMeters;
        long tick = level.getServer().getTickCount();

        activeQueries++;

        try {
            for (FrequencyBucket bucket : byFrequency.subMap(candidates.minHz(), true, candidates.maxHz(), true).values()) {
                for (int i = 0; i < bucket.size; i++) {
                    FrequencyEmitter emitter = bucket.get(i);
                    if (!emitter.isEmitting()) continue;

                    FrequencySignal signal = Objects.requireNonNull(emitter.signal());

                    if (!candidates.contains(signal.frequencyHz())) {
                        continue;
                    }

                    if (signal.propagationSpeed() < minPropagationSpeed) {
                        throw new IllegalArgumentException("Signal propagation speed is below the declared minimum");
                    }

                    EmitterMotionSample sampled = emitterMotionSamples.get(emitter);
                    boolean currentSample = sampled != null && sampled.tick == tick;
                    FrequencyMotionSnapshot sourceMotion = currentSample ? sampled.motion : Objects.requireNonNull(emitter.motionSnapshot(level));

                    double dx = receiverMotion.x() - sourceMotion.x();
                    double dy = receiverMotion.y() - sourceMotion.y();
                    double dz = receiverMotion.z() - sourceMotion.z();

                    double distanceSquared = dx * dx + dy * dy + dz * dz;

                    if (!(distanceSquared <= rangeSquared)) {
                        continue;
                    }

                    double emitterSpeed = Math.hypot(Math.hypot(sourceMotion.vx(), sourceMotion.vy()), sourceMotion.vz());
                    if (emitterSpeed > maxEmitterSpeed) {
                        throw new IllegalArgumentException("Emitter velocity exceeds the declared Doppler bound");
                    }

                    FrequencyAccelerationTracker.Acceleration acceleration = currentSample ? sampled.acceleration : null;
                    double receivedFrequency;

                    if (acceleration == null || (acceleration.ax() == 0.0 && acceleration.ay() == 0.0 && acceleration.az() == 0.0) || distanceSquared == 0.0) {
                        receivedFrequency = FrequencyDoppler.receivedFrequencyHz(signal.frequencyHz(), signal.propagationSpeed(), sourceMotion, receiverMotion);
                    } else {
                        double ax = acceleration.ax();
                        double ay = acceleration.ay();
                        double az = acceleration.az();

                        double magnitude = Math.hypot(Math.hypot(ax, ay), az);

                        if (!Double.isFinite(magnitude) || magnitude > maxEmitterAcceleration) {
                            throw new IllegalArgumentException("Emitter acceleration exceeds the declared Doppler bound");
                        }

                        receivedFrequency = FrequencyRetardedDoppler.calculate(signal.frequencyHz(), signal.propagationSpeed(), sourceMotion, receiverMotion, ax, ay, az).receivedFrequencyHz();
                    }

                    if (Math.abs(receivedFrequency - frequency) > tolerance) {
                        continue;
                    }

                    consumer.accept(emitter, signal, distanceSquared, receivedFrequency);
                }
            }
        } finally {
            activeQueries--;
        }
    }

    public void forEachReceivedInRange(FrequencyReceiver receiver, double rangeMeters, FrequencyReceptionConsumer consumer) {
        Objects.requireNonNull(consumer);
        forEachInRange(receiver, rangeMeters, null, consumer);
    }

    private void forEachInRange(FrequencyReceiver receiver, double rangeMeters, FrequencyMatchConsumer matchConsumer, FrequencyReceptionConsumer receptionConsumer) {
        Objects.requireNonNull(receiver);
        checkRunning();

        if (!Double.isFinite(rangeMeters) || rangeMeters < 0.0) {
            throw new IllegalArgumentException("Invalid signal range");
        }

        double frequency = receiver.tunedFrequencyHz();
        double tolerance = receiver.toleranceHz();

        if (!Double.isFinite(frequency) || frequency <= 0.0 || !Double.isFinite(tolerance) || tolerance < 0.0) {
            throw new IllegalArgumentException("Invalid receiver tuning");
        }

        double minFrequency = frequency - tolerance;
        double maxFrequency = frequency + tolerance;
        double rangeSquared = rangeMeters * rangeMeters;

        Vec3 receiverPos = Objects.requireNonNull(receiver.position());
        Vec3 receiverWorldPos = FrequencySpace.worldPosition(level, receiverPos);

        activeQueries++;

        try {
            for (FrequencyBucket bucket : byFrequency.subMap(minFrequency, true, maxFrequency, true).values()) {
                for (int i = 0; i < bucket.size; i++) {
                    FrequencyEmitter emitter = bucket.get(i);
                    if (!emitter.isEmitting()) {
                        continue;
                    }

                    FrequencySignal signal = Objects.requireNonNull(emitter.signal());
                    if (Math.abs(signal.frequencyHz() - frequency) > tolerance) {
                        continue;
                    }

                    Vec3 emitterPos = Objects.requireNonNull(emitter.position());
                    Vec3 emitterWorldPos = FrequencySpace.worldPosition(level, emitterPos);
                    double distanceSquared = emitterWorldPos.distanceToSqr(receiverWorldPos);

                    if (!(distanceSquared <= rangeSquared)) {
                        continue;
                    }

                    if (receptionConsumer == null) {
                        matchConsumer.accept(emitter, signal, distanceSquared);
                    } else {
                        double distance = Math.sqrt(distanceSquared);
                        double amplitude = FrequencyPropagation.amplitudeAtDistance(signal.amplitude(), distance);
                        double travelTime = FrequencyPropagation.travelTimeSeconds(distance, signal.propagationSpeed());

                        receptionConsumer.accept(emitter, signal, distanceSquared, amplitude, travelTime);
                    }
                }
            }
        } finally {
            activeQueries--;
        }
    }

    public double sampleResonanceInRange(FrequencyReceiver receiver, double rangeMeters, double timeSeconds, double naturalFrequencyHz, double dampingRatio) {
        if (!Double.isFinite(timeSeconds))
            throw new IllegalArgumentException("Invalid simulation time");

        if (!Double.isFinite(naturalFrequencyHz) || naturalFrequencyHz <= 0.0)
            throw new IllegalArgumentException("Invalid natural frequency");

        if (!Double.isFinite(dampingRatio) || dampingRatio <= 0.0)
            throw new IllegalArgumentException("Invalid damping ratio");

        final double[] sum = {0.0};

        forEachReceivedInRange(
                receiver,
                rangeMeters,
                (emitter, signal, distanceSquared, amplitude, travelTime) -> {
                    sum[0] += FrequencyResonance.sampleReceived(signal.frequencyHz(), amplitude, signal.phaseRadians(), timeSeconds, travelTime, naturalFrequencyHz, dampingRatio);
                }
        );

        return sum[0];
    }

    public double sampleResonanceNow(FrequencyReceiver receiver, double rangeMeters, double naturalFrequencyHz, double dampingRatio) {
        checkRunning();

        if (!Double.isFinite(naturalFrequencyHz) || naturalFrequencyHz <= 0.0) {
            throw new IllegalArgumentException("Invalid natural frequency");
        }

        if (!Double.isFinite(dampingRatio) || dampingRatio <= 0.0) {
            throw new IllegalArgumentException("Invalid damping ratio");
        }

        long tick = FrequencySimulationTime.ticks(level);
        final double[] sum = {0.0};

        forEachReceivedInRange(
                receiver,
                rangeMeters,
                (emitter, signal, distanceSquared, amplitude, travelTime) -> {
                    sum[0] += FrequencyResonance.sampleReceivedAtTick(signal.frequencyHz(), amplitude, signal.phaseRadians(), tick, travelTime, naturalFrequencyHz, dampingRatio);
                }
        );

        return sum[0];
    }

    public double sampleInterferenceInRange(FrequencyReceiver receiver, double rangeMeters, double timeSeconds) {
        if (!Double.isFinite(timeSeconds))
            throw new IllegalArgumentException("Invalid simulation time");

        final double[] sum = {0.0};

        forEachReceivedInRange(
                receiver,
                rangeMeters,
                (emitter, signal, distanceSquared, amplitude, travelTime) -> {
                    sum[0] += FrequencyPropagation.sampleReceived(signal.frequencyHz(), amplitude, signal.phaseRadians(), timeSeconds, travelTime);
                }
        );

        return sum[0];
    }

    /**
     * Mean-square received amplitude over a finite observation
     * window ending at endTick.
     */
    public double sampleInterferenceWindowMeanSquareInRange(FrequencyReceiver receiver, double rangeMeters, long endTick, double windowSeconds) {
        if (endTick < 0L) {
            throw new IllegalArgumentException("Invalid end tick");
        }

        if (!Double.isFinite(windowSeconds) || windowSeconds <= 0.0) {
            throw new IllegalArgumentException("Observation window must be finite and positive");
        }

        InterferenceAccumulator accumulator = new InterferenceAccumulator(endTick, windowSeconds);

        forEachReceivedInRange(
                receiver,
                rangeMeters,
                (emitter, signal, distanceSquared, amplitude, travelTime) -> accumulator.add(signal, amplitude, travelTime)
        );

        return accumulator.meanSquareWindow(windowSeconds);
    }

    /**
     * Evaluates finite-window received strength at the
     * current server tick.
     */
    public double sampleInterferenceWindowMeanSquareNow(FrequencyReceiver receiver, double rangeMeters, double windowSeconds) {
        checkRunning();
        return sampleInterferenceWindowMeanSquareInRange(receiver, rangeMeters, FrequencySimulationTime.ticks(level), windowSeconds);
    }

    public double sampleInterferenceMeanSquareInRange(FrequencyReceiver receiver, double rangeMeters) {
        InterferenceAccumulator accumulator = new InterferenceAccumulator();
        forEachReceivedInRange(receiver, rangeMeters, (emitter, signal, distanceSquared, amplitude, travelTime) -> accumulator.add(signal, amplitude, travelTime));

        return accumulator.meanSquare();
    }

    public double sampleInterferenceRmsInRange(FrequencyReceiver receiver, double rangeMeters) {
        return Math.sqrt(sampleInterferenceMeanSquareInRange(receiver, rangeMeters));
    }

    public double sampleInterferenceNow(FrequencyReceiver receiver, double rangeMeters) {
        checkRunning();

        long tick = FrequencySimulationTime.ticks(level);
        final double[] sum = {0.0};

        forEachReceivedInRange(
                receiver,
                rangeMeters,
                (emitter, signal, distanceSquared, amplitude, travelTime) -> {
                    sum[0] += FrequencyPropagation.sampleReceivedAtTick(signal.frequencyHz(), amplitude, signal.phaseRadians(), tick, travelTime);
                }
        );

        return sum[0];
    }

    public int emitterCount() {
        checkRunning();
        return emitters.size();
    }

    void shutdown() {
        stopped = true;
        emitters.clear();
        byFrequency.clear();
        emitterMotionSamples.clear();
        chunkRegistrations.clear();
        entityRegistrations.clear();
        sublevelRegistrations.clear();
    }

    private static final class FrequencyBucket {
        private FrequencyEmitter single;
        private FrequencyEmitter[] shared;
        private IdentityHashMap<FrequencyEmitter, Integer> indices;
        private int size;

        private FrequencyEmitter get(int index) {
            return shared == null ? single : shared[index];
        }

        private void add(FrequencyEmitter emitter) {
            if (size == 0) {
                single = emitter;
                size = 1;
                return;
            }

            if (size == 1) {
                if (single == emitter) return;

                shared = new FrequencyEmitter[4];
                shared[0] = single;
                shared[1] = emitter;

                indices = new IdentityHashMap<>(4);
                indices.put(single, 0);
                indices.put(emitter, 1);

                single = null;
                size = 2;
                return;
            }

            if (indices.containsKey(emitter)) return;

            if (size == shared.length) {
                shared = Arrays.copyOf(shared, shared.length * 2);
            }

            indices.put(emitter, size);
            shared[size++] = emitter;
        }

        private boolean remove(FrequencyEmitter emitter) {
            if (size == 0) return false;
            if (size == 1) {
                if (single != emitter) return false;

                single = null;
                size = 0;
                return true;
            }

            Integer index = indices.remove(emitter);
            if (index == null) return false;

            int last = --size;
            if (index != last) {
                FrequencyEmitter moved = shared[last];
                shared[index] = moved;
                indices.put(moved, index);
            }

            shared[last] = null;
            if (size == 1) {
                single = shared[0];
                shared = null;
                indices = null;
            }

            return true;
        }
    }


    private void addToIndex(FrequencyEmitter emitter, double frequency) {
        byFrequency.computeIfAbsent(frequency, unused -> new FrequencyBucket()).add(emitter);
    }

    private void removeFromIndex(FrequencyEmitter emitter, double frequency) {
        FrequencyBucket bucket = byFrequency.get(frequency);

        if (bucket != null && bucket.remove(emitter) && bucket.size == 0) {
            byFrequency.remove(frequency);
        }
    }

    static final class InterferenceAccumulator {
        private final long endTick;
        private final double halfWindowSeconds;

        InterferenceAccumulator() {
            this(0L, 0.0);
        }

        InterferenceAccumulator(long endTick, double windowSeconds) {
            this.endTick = endTick;
            this.halfWindowSeconds = windowSeconds * 0.5;
        }

        private double primaryFrequency = Double.NaN;
        private double primaryReal;
        private double primaryImaginary;
        private double primaryWindowPhaseShift;
        private Double2IntOpenHashMap additionalIndices;
        private double[] additionalCarriers;
        private int additionalCount;

        void add(FrequencySignal signal, double receivedAmplitude, double travelTimeSeconds) {
            if (receivedAmplitude == 0.0) {
                return;
            }

            double frequency = signal.frequencyHz();

            if (Double.isNaN(primaryFrequency)) {
                primaryFrequency = frequency;

                if (halfWindowSeconds > 0.0) {
                    primaryWindowPhaseShift = FrequencyMath.phaseAdvanceRadians(frequency, halfWindowSeconds);
                }
            }

            int index = -1;
            double windowPhaseShift;

            if (frequency == primaryFrequency) {
                windowPhaseShift = primaryWindowPhaseShift;
            } else {
                if (additionalIndices == null) {
                    additionalIndices = new Double2IntOpenHashMap(8);
                    additionalIndices.defaultReturnValue(-1);
                    additionalCarriers = new double[32];
                }

                index = additionalIndices.get(frequency);

                if (index < 0) {
                    index = additionalCount * 4;

                    if (index + 4 > additionalCarriers.length) {
                        additionalCarriers = Arrays.copyOf(additionalCarriers, additionalCarriers.length * 2);
                    }

                    additionalCarriers[index] = frequency;
                    additionalCarriers[index + 3] = halfWindowSeconds > 0.0 ? FrequencyMath.phaseAdvanceRadians(frequency, halfWindowSeconds) : 0.0;

                    additionalIndices.put(frequency, index);
                    additionalCount++;
                }

                windowPhaseShift = additionalCarriers[index + 3];
            }

            double phase = FrequencyMath.phaseAtTick(frequency, endTick, signal.phaseRadians(), travelTimeSeconds);
            if (halfWindowSeconds > 0.0) {
                phase -= windowPhaseShift;
            }

            double real = receivedAmplitude * Math.cos(phase);
            double imaginary = receivedAmplitude * Math.sin(phase);

            if (index < 0) {
                primaryReal += real;
                primaryImaginary += imaginary;
                return;
            }

            additionalCarriers[index + 1] += real;
            additionalCarriers[index + 2] += imaginary;
        }

        private double meanSquare() {
            double total = Math.fma(primaryReal, primaryReal, primaryImaginary * primaryImaginary);

            int limit = additionalCount * 4;

            for (int i = 0; i < limit; i += 4) {
                double real = additionalCarriers[i + 1];
                double imaginary = additionalCarriers[i + 2];

                total += Math.fma(real, real, imaginary * imaginary);
            }

            double result = 0.5 * total;

            if (!Double.isFinite(result)) {
                throw new IllegalArgumentException("Received interference power overflow");
            }

            return result;
        }

        double meanSquareWindow(double windowSeconds) {
            if (additionalCount >= 63) {
                double step = additionalCarriers[0] - primaryFrequency;
                if (isExactIntegerFrequencyGrid(step)) {
                    return meanSquareIntegerFrequencyGrid(windowSeconds, step);
                }

                InterferenceAccumulator ordered = reorderedIntegerFrequencyGrid();

                if (ordered != null) {
                    double orderedStep = ordered.additionalCarriers[0] - ordered.primaryFrequency;
                    return ordered.meanSquareIntegerFrequencyGrid(windowSeconds, orderedStep);
                }
            }

            double total = windowSelf(primaryFrequency, primaryReal, primaryImaginary, windowSeconds);

            if (additionalCount != 0) {
                int limit = additionalCount * 4;
                double halfWindow = windowSeconds * 0.5;
                boolean reusePhase = halfWindow == halfWindowSeconds;

                double[] trig = new double[additionalCount * 2];

                for (int src = 0; src < limit; src += 4) {
                    double hz = additionalCarriers[src];
                    double real = additionalCarriers[src + 1];
                    double imaginary = additionalCarriers[src + 2];

                    double phase = Double.isFinite(hz * halfWindow) ? (reusePhase ? additionalCarriers[src + 3] : FrequencyMath.phaseAdvanceRadians(hz, halfWindow)) : Double.NaN;
                    int phaseOffset = src / 2;
                    trig[phaseOffset] = Math.sin(phase);
                    trig[phaseOffset + 1] = Math.cos(phase);

                    total += windowSelf(hz, real, imaginary, windowSeconds);
                }

                double primaryPhase = Double.isFinite(primaryFrequency * halfWindow) ? (reusePhase ? primaryWindowPhaseShift : FrequencyMath.phaseAdvanceRadians(primaryFrequency, halfWindow)) : Double.NaN;
                double sinPrimary = Math.sin(primaryPhase);
                double cosPrimary = Math.cos(primaryPhase);
                double piWindow = Math.PI * windowSeconds;

                for (int j = 0; j < limit; j += 4) {
                    total += windowCrossCached(primaryFrequency, primaryReal, primaryImaginary, sinPrimary, cosPrimary, additionalCarriers, j, trig, j / 2, windowSeconds, piWindow);
                }

                for (int i = 0; i < limit; i += 4) {
                    double frequencyA = additionalCarriers[i];
                    double realA = additionalCarriers[i + 1];
                    double imaginaryA = additionalCarriers[i + 2];

                    double sinA = trig[i / 2];
                    double cosA = trig[i / 2 + 1];

                    for (int j = i + 4; j < limit; j += 4) {
                        total += windowCrossCached(frequencyA, realA, imaginaryA, sinA, cosA, additionalCarriers, j, trig, j / 2, windowSeconds, piWindow);
                    }
                }
            }

            if (!Double.isFinite(total)) {
                throw new IllegalArgumentException("Finite-window interference overflow");
            }

            return Math.max(0.0, total);
        }

        private boolean isExactIntegerFrequencyGrid(double step) {
            if (!Double.isFinite(step) || step == 0.0 || step != Math.rint(step) || !Double.isFinite(primaryFrequency) || primaryFrequency != Math.rint(primaryFrequency) || primaryFrequency <= 0.0 || primaryFrequency > 0x1.0p51) {
                return false;
            }

            for (int i = 1; i <= additionalCount; i++) {
                double actual = additionalCarriers[(i - 1) * 4];

                if (actual <= 0.0 || actual > 0x1.0p51 || actual != primaryFrequency + step * i) {
                    return false;
                }
            }

            return true;
        }

        private InterferenceAccumulator reorderedIntegerFrequencyGrid() {
            int count = additionalCount + 1;
            double min = primaryFrequency;
            double max = primaryFrequency;

            if (!validIntegerGridFrequency(primaryFrequency)) {
                return null;
            }

            for (int i = 0; i < additionalCount; i++) {
                double hz = additionalCarriers[i * 4];

                if (!validIntegerGridFrequency(hz)) {
                    return null;
                }

                min = Math.min(min, hz);
                max = Math.max(max, hz);
            }

            double span = max - min;
            double step = span / (count - 1);

            if (!(step >= 1.0) || step != Math.rint(step) || step * (count - 1) != span) {
                return null;
            }

            double[] data = new double[count * 4];
            boolean[] occupied = new boolean[count];

            if (!placeGridCarrier(data, occupied, primaryFrequency, primaryReal, primaryImaginary, min, step)) {
                return null;
            }

            for (int i = 0; i < additionalCount; i++) {
                int offset = i * 4;

                if (!placeGridCarrier(data, occupied, additionalCarriers[offset], additionalCarriers[offset + 1], additionalCarriers[offset + 2], min, step)) {
                    return null;
                }
            }

            for (boolean filled : occupied) {
                if (!filled) {
                    return null;
                }
            }

            InterferenceAccumulator ordered = new InterferenceAccumulator();
            int primaryOffset = (count - 1) * 4;

            ordered.primaryFrequency = data[primaryOffset];
            ordered.primaryReal = data[primaryOffset + 1];
            ordered.primaryImaginary = data[primaryOffset + 2];

            ordered.additionalCarriers = data;
            ordered.additionalCount = count - 1;

            return ordered;
        }

        private static boolean validIntegerGridFrequency(double frequency) {
            return Double.isFinite(frequency) && frequency > 0.0 && frequency <= 0x1.0p51 && frequency == Math.rint(frequency);
        }

        private static boolean placeGridCarrier(double[] data, boolean[] occupied, double frequency, double real, double imaginary, double minimum, double step) {
            double position = (frequency - minimum) / step;
            if (!(position >= 0.0 && position < occupied.length) || position != Math.rint(position)) {
                return false;
            }

            int index = (int) position;
            if (occupied[index] || frequency != minimum + step * index) {
                return false;
            }

            occupied[index] = true;

            int offset = (index == 0 ? occupied.length - 1 : index - 1) * 4;
            data[offset] = frequency;
            data[offset + 1] = real;
            data[offset + 2] = imaginary;

            return true;
        }

        private double meanSquareIntegerFrequencyGrid(double windowSeconds, double step) {
            int count = additionalCount + 1;
            double[] differenceSinc = new double[count];
            double[] sumSinc = new double[count * 2 - 1];
            double absoluteStep = Math.abs(step);

            for (int delta = 1; delta < count; delta++) {
                differenceSinc[delta] = sincWindow(absoluteStep * delta, windowSeconds);
            }

            double twiceBase = primaryFrequency * 2.0;
            for (int index = 0; index < sumSinc.length; index++) {
                sumSinc[index] = sincWindow(twiceBase + step * index, windowSeconds);
            }

            double total = 0.0;
            for (int i = 0; i < count; i++) {
                int offset = (i - 1) * 4;

                double real = i == 0 ? primaryReal : additionalCarriers[offset + 1];
                double imaginary = i == 0 ? primaryImaginary : additionalCarriers[offset + 2];

                double realSquared = real * real;
                double imaginarySquared = imaginary * imaginary;

                total += 0.5 * (realSquared + imaginarySquared - (realSquared - imaginarySquared) * sumSinc[i * 2]);
            }

            for (int i = 0; i < count; i++) {
                int offsetA = (i - 1) * 4;

                double realA = i == 0 ? primaryReal : additionalCarriers[offsetA + 1];
                double imaginaryA = i == 0 ? primaryImaginary : additionalCarriers[offsetA + 2];

                for (int j = i + 1; j < count; j++) {
                    int offsetB = (j - 1) * 4;

                    double realB = additionalCarriers[offsetB + 1];
                    double imaginaryB = additionalCarriers[offsetB + 2];
                    double imaginaryProduct = imaginaryA * imaginaryB;

                    double differenceComponent = Math.fma(realA, realB, imaginaryProduct);
                    double sumComponent = Math.fma(realA, realB, -imaginaryProduct);

                    total += Math.fma(differenceComponent, differenceSinc[j - i], -sumComponent * sumSinc[i + j]);
                }
            }

            if (!Double.isFinite(total)) {
                throw new IllegalArgumentException("Finite-window interference overflow");
            }

            return Math.max(0.0, total);
        }

        private static double windowCrossCached(double frequencyA, double realA, double imaginaryA, double sinA, double cosA, double[] data, int b, double[] trig, int phaseOffset, double windowSeconds, double piWindow) {
            double frequencyB = data[b];
            double realB = data[b + 1];
            double imaginaryB = data[b + 2];
            double sinB = trig[phaseOffset];
            double cosB = trig[phaseOffset + 1];

            if (!Double.isFinite(sinA) || !Double.isFinite(sinB)) {
                return windowCross(frequencyA, realA, imaginaryA, frequencyB, realB, imaginaryB, windowSeconds);
            }

            double differenceHz = frequencyA - frequencyB;
            double sumHz = frequencyA + frequencyB;

            double xDifference;
            double xSum;

            if (Double.isFinite(piWindow) && piWindow > 0.0) {
                xDifference = differenceHz * piWindow;
                xSum = sumHz * piWindow;
            } else {
                xDifference = Math.PI * differenceHz * windowSeconds;
                xSum = Math.PI * sumHz * windowSeconds;
            }

            double sinAcosB = sinA * cosB;
            double cosAsinB = cosA * sinB;

            double differenceSinc;

            if (Math.abs(xDifference) <= Math.PI * 0.25) {
                differenceSinc = sincWindow(differenceHz, windowSeconds);
            } else if (!Double.isFinite(xDifference)) {
                differenceSinc = 0.0;
            } else {
                differenceSinc = Math.fma(sinA, cosB, -cosAsinB) / xDifference;
            }

            double sumSinc;

            if (Math.abs(xSum) <= Math.PI * 0.25) {
                sumSinc = sincWindow(sumHz, windowSeconds);
            } else if (!Double.isFinite(xSum)) {
                sumSinc = 0.0;
            } else {
                sumSinc = Math.fma(sinA, cosB, cosAsinB) / xSum;
            }

            double differenceComponent = Math.fma(realA, realB, imaginaryA * imaginaryB);
            double sumComponent = Math.fma(realA, realB, -imaginaryA * imaginaryB);

            return Math.fma(differenceComponent, differenceSinc, -sumComponent * sumSinc);
        }

        private static double windowSelf(double frequency, double real, double imaginary, double windowSeconds) {
            double realSquared = real * real;
            double imaginarySquared = imaginary * imaginary;

            return 0.5 * (realSquared + imaginarySquared - (realSquared - imaginarySquared) * sincWindow(2.0 * frequency, windowSeconds));
        }

        private static double windowCross(double frequencyA, double realA, double imaginaryA, double frequencyB, double realB, double imaginaryB, double windowSeconds) {
            double difference = Math.abs(frequencyA - frequencyB);
            double sum = frequencyA + frequencyB;

            double differenceComponent = Math.fma(realA, realB, imaginaryA * imaginaryB);
            double sumComponent = Math.fma(realA, realB, -imaginaryA * imaginaryB);

            return differenceComponent * sincWindow(difference, windowSeconds) - sumComponent * sincWindow(sum, windowSeconds);
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
    }

    private void checkRunning() {
        if (stopped) {
            throw new IllegalStateException("Frequency engine stopped");
        }

        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Frequency engine must be accessed from the server thread");
        }
    }

    private void checkWritable() {
        checkRunning();
        if (activeQueries != 0) throw new IllegalStateException("Cannot change registrations during a signal query");
    }
}
