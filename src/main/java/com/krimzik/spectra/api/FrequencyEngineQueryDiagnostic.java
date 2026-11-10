package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.math.FrequencyPropagation;
import com.mojang.brigadier.Command;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Locale;

public final class FrequencyEngineQueryDiagnostic {
    private static final double FREQUENCY = 915_000_000.0;
    private static final double RANGE = 100_000_000.0;
    private static final double RANGE_SQUARED = RANGE * RANGE;
    private static final double WINDOW_SECONDS = 0.05;

    private static final int WARMUP = 64;
    private static final int BATCHES = 5;
    private static final int QUERIES_PER_BATCH = 40;

    private static volatile double benchmarkSink;

    private FrequencyEngineQueryDiagnostic() {}

    private enum Mode {
        LOOKUP,
        CACHED_DISTANCE,
        DIRECT_DISTANCE,
        CACHED_RECEPTION,
        DIRECT_RECEPTION,
        FINITE_WINDOW
    }

    private record BenchEmitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {
        @Override
        public boolean isEmitting() {
            return true;
        }
    }

    private record BenchReceiver(Vec3 position) implements FrequencyReceiver {
        @Override
        public double tunedFrequencyHz() {
            return FREQUENCY;
        }

        @Override
        public double toleranceHz() {
            return 0.0;
        }
    }

    private static final class Result {
        double sum;
        int count;

        void reset() {
            sum = 0.0;
            count = 0;
        }
    }

    public static int run(ServerPlayer player, Entity target) {
        ServerLevel level = player.serverLevel();

        if (target != null && target.level() != level) {
            player.sendSystemMessage(Component.literal("[FreqQuery] Target must be in the same dimension."));
            return Command.SINGLE_SUCCESS;
        }

        Entity source = target == null ? player : target;

        Vec3 receiverPos = spatialPosition(player);
        Vec3 emitterBase = spatialPosition(source);

        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqQuery] A=%s B=%s | Real Sable | 915 MHz", locationType(player), locationType(source))));
        player.sendSystemMessage(Component.literal("[FreqQuery] Profiling 10, 100 and 1000 matching emitters."));

        for (int count : new int[]{10, 100, 1_000}) {
            profileCount(player, level, receiverPos, emitterBase, count);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static Vec3 spatialPosition(Entity entity) {
        SableCompanion companion = SableCompanion.INSTANCE;
        Vec3 position = entity.position();

        if (companion.getContaining(entity.level(), position) != null) {
            return position;
        }

        SubLevelAccess tracked = companion.getTrackingOrVehicleSubLevel(entity);

        return tracked == null ? position : tracked.logicalPose().transformPositionInverse(position);
    }

    private static String locationType(Entity entity) {
        SableCompanion companion = SableCompanion.INSTANCE;

        if (companion.getContaining(
                entity.level(), entity.position()) != null) {
            return "PLOT";
        }

        return companion.getTrackingOrVehicleSubLevel(entity) != null ? "TRACKED" : "WORLD";
    }

    private static void profileCount(ServerPlayer player, ServerLevel level, Vec3 receiverPos, Vec3 emitterBase, int count) {
        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencySignal signal = new FrequencySignal(FREQUENCY, 1.0, 0.0, 343.0);

        try {
            for (int i = 0; i < count; i++) {
                double dx = ((i % 10) + 1) * 0.02;
                double dy = ((i / 10) % 10) * 0.02;
                double dz = ((i / 100) % 10) * 0.02;

                Vec3 position = emitterBase.add(dx, dy, dz);

                engine.register(new BenchEmitter(position, signal));
            }

            BenchReceiver receiver = new BenchReceiver(receiverPos);
            long tick = level.getGameTime();

            Result cached = new Result();
            Result direct = new Result();

            DistanceCheck distanceCheck = verifyEachDistance(engine, receiver, level, receiverPos);

            player.sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "[FreqQuery] N=%d Checked=%d Failures=%d MaxError=%.3e",
                    count,
                    distanceCheck.checked(),
                    distanceCheck.failures(),
                    distanceCheck.maxError()
            )));

            if (distanceCheck.checked() != count || distanceCheck.failures() != 0) {
                player.sendSystemMessage(Component.literal("[FreqQuery] Per-emitter distance verification failed."));
                return;
            }

            execute(Mode.CACHED_DISTANCE, engine, receiver, level, receiverPos, tick, cached);
            execute(Mode.DIRECT_DISTANCE, engine, receiver, level, receiverPos, tick, direct);

            if (!equivalent(cached, direct, count)) {
                player.sendSystemMessage(Component.literal("[FreqQuery] Distance mismatch at N=" + count + " cached=" + cached.sum + " direct=" + direct.sum + " counts=" + cached.count + "/" + direct.count));
                return;
            }

            execute(Mode.CACHED_RECEPTION, engine, receiver, level, receiverPos, tick, cached);

            execute(Mode.DIRECT_RECEPTION, engine, receiver, level, receiverPos, tick, direct);

            if (!equivalent(cached, direct, count)) {
                player.sendSystemMessage(Component.literal("[FreqQuery] Reception mismatch at N=" + count + " cached=" + cached.sum + " direct=" + direct.sum + " counts=" + cached.count + "/" + direct.count));
                return;
            }

            double[] timings = measureAll(engine, receiver, level, receiverPos, tick);

            double lookup = timings[Mode.LOOKUP.ordinal()];
            double spatial = timings[Mode.CACHED_DISTANCE.ordinal()];
            double directSpatial = timings[Mode.DIRECT_DISTANCE.ordinal()];
            double reception = timings[Mode.CACHED_RECEPTION.ordinal()];
            double directReception = timings[Mode.DIRECT_RECEPTION.ordinal()];
            double window = timings[Mode.FINITE_WINDOW.ordinal()];

            player.sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "[FreqQuery] N=%d Lookup=%.2f us | Spatial=%.2f us | Reception=%.2f us",
                    count, lookup, spatial, reception
            )));

            player.sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "[FreqQuery] N=%d DirectSpatial=%.2f us | DirectReception=%.2f us | Window=%.2f us",
                    count, directSpatial, directReception, window
            )));

            player.sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "[FreqQuery] N=%d Verified=true | SpatialSpeedup=%.2fx | ReceptionSpeedup=%.2fx",
                    count,
                    spatial > 0.0 ? directSpatial / spatial : Double.NaN,
                    reception > 0.0 ? directReception / reception : Double.NaN
            )));
        } finally {
            engine.shutdown();
        }
    }

    private record DistanceCheck(int checked, int failures, double maxError) {}

    private static DistanceCheck verifyEachDistance(FrequencyEngine engine, BenchReceiver receiver, ServerLevel level, Vec3 receiverPos) {
        Vec3 receiverWorld =
                FrequencySpace.worldPosition(level, receiverPos);

        int[] checked = {0};
        int[] failures = {0};
        double[] maxError = {0.0};

        engine.forEachMatching(receiver, (emitter, signal) -> {
            Vec3 emitterPos = emitter.position();

            double direct = FrequencySpace.distanceSquaredMeters(level, emitterPos, receiverPos);
            double cached = FrequencySpace.worldPosition(level, emitterPos).distanceToSqr(receiverWorld);

            checked[0]++;

            if (!Double.isFinite(direct) || !Double.isFinite(cached)) {
                failures[0]++;
                return;
            }

            double error = Math.abs(direct - cached);
            maxError[0] = Math.max(maxError[0], error);

            double tolerance = 1.0e-9 * Math.max(1.0, Math.abs(direct));

            if (error > tolerance) {
                failures[0]++;
            }
        });

        return new DistanceCheck(checked[0], failures[0], maxError[0]);
    }

    private static boolean equivalent(Result a, Result b, int expectedCount) {
        if (a.count != expectedCount || b.count != expectedCount) {
            return false;
        }

        if (!Double.isFinite(a.sum) || !Double.isFinite(b.sum)) {
            return false;
        }

        double scale = Math.max(1.0, Math.max(Math.abs(a.sum), Math.abs(b.sum)));

        return Math.abs(a.sum - b.sum) <= 1.0e-9 * scale;
    }

    private static void execute(Mode mode, FrequencyEngine engine, BenchReceiver receiver, ServerLevel level, Vec3 receiverPos, long tick, Result result) {
        result.reset();

        switch (mode) {
            case LOOKUP -> engine.forEachMatching(
                    receiver,
                    (emitter, signal) -> {
                        result.sum += signal.amplitude();
                        result.count++;
                    }
            );

            case CACHED_DISTANCE -> engine.forEachMatchingInRange(
                    receiver,
                    RANGE,
                    (emitter, signal, distanceSquared) -> {
                        result.sum += distanceSquared;
                        result.count++;
                    }
            );

            case DIRECT_DISTANCE -> engine.forEachMatching(
                    receiver,
                    (emitter, signal) -> {
                        double distanceSquared = FrequencySpace.distanceSquaredMeters(level, emitter.position(), receiverPos);

                        if (!(distanceSquared <= RANGE_SQUARED)) {
                            return;
                        }

                        result.sum += distanceSquared;
                        result.count++;
                    }
            );

            case CACHED_RECEPTION -> engine.forEachReceivedInRange(
                    receiver,
                    RANGE,
                    (emitter, signal, distanceSquared,
                     amplitude, travelTime) -> {
                        result.sum += amplitude + travelTime;
                        result.count++;
                    }
            );

            case DIRECT_RECEPTION -> engine.forEachMatching(
                    receiver,
                    (emitter, signal) -> {
                        double distanceSquared = FrequencySpace.distanceSquaredMeters(level, emitter.position(), receiverPos);

                        if (!(distanceSquared <= RANGE_SQUARED)) {
                            return;
                        }

                        double distance = Math.sqrt(distanceSquared);
                        double amplitude = FrequencyPropagation.amplitudeAtDistance(signal.amplitude(), distance);
                        double travelTime = FrequencyPropagation.travelTimeSeconds(distance, signal.propagationSpeed());

                        result.sum += amplitude + travelTime;
                        result.count++;
                    }
            );

            case FINITE_WINDOW -> {
                result.sum = engine.sampleInterferenceWindowMeanSquareInRange(receiver, RANGE, tick, WINDOW_SECONDS);
                result.count = 1;
            }
        }

        benchmarkSink = result.sum;
    }

    private static double[] measureAll(FrequencyEngine engine, BenchReceiver receiver, ServerLevel level, Vec3 receiverPos, long tick) {
        Mode[] modes = Mode.values();
        Result result = new Result();

        for (int round = 0; round < 2; round++) {
            for (Mode mode : modes) {
                for (int i = 0; i < WARMUP; i++) {
                    execute(mode, engine, receiver, level, receiverPos, tick, result);
                }
            }
        }

        double[][] samples = new double[modes.length][BATCHES];

        for (int batch = 0; batch < BATCHES; batch++) {
            for (int j = 0; j < modes.length; j++) {
                Mode mode = modes[(j + batch) % modes.length];

                long start = System.nanoTime();

                for (int i = 0; i < QUERIES_PER_BATCH; i++) {
                    execute(mode, engine, receiver, level, receiverPos, tick, result);
                }

                samples[mode.ordinal()][batch] = (System.nanoTime() - start) / (1_000.0 * QUERIES_PER_BATCH);
            }
        }

        double[] medians = new double[modes.length];

        for (Mode mode : modes) {
            double[] times = samples[mode.ordinal()];
            Arrays.sort(times);
            medians[mode.ordinal()] = times[BATCHES / 2];
        }

        return medians;
    }
}
