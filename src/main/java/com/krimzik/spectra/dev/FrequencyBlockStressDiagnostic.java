package com.krimzik.spectra.dev;

import com.krimzik.spectra.api.FrequencyApi;
import com.krimzik.spectra.api.FrequencyEngine;
import com.krimzik.spectra.api.FrequencySignal;
import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.consumers.FrequencyReceptionConsumer;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public final class FrequencyBlockStressDiagnostic {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COUNT = 50_000;
    private static final int FIXED = 25_000;
    private static final int REGISTER_PER_TICK = 256;
    private static final int STOP_PER_TICK = 512;
    private static final int WARMUP_TICKS = 40;
    private static final int MEASURE_TICKS = 600;
    private static final int QUERIES_PER_DIRECTION = 8;
    private static final double RANGE_METERS = 0.25;
    private static final FrequencySignal[] SIGNALS = new FrequencySignal[128];

    static {
        for (int i = 0; i < SIGNALS.length; i++) {
            SIGNALS[i] = new FrequencySignal(915_000_000.0 + i * 1000.0, 1.0, 0.0, 299_792_458.0);
        }
    }

    private static Session active;

    private FrequencyBlockStressDiagnostic() {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(FrequencyBlockStressDiagnostic::registerCommands);
        NeoForge.EVENT_BUS.addListener(FrequencyBlockStressDiagnostic::serverTick);
        NeoForge.EVENT_BUS.addListener(FrequencyBlockStressDiagnostic::levelUnload);
        NeoForge.EVENT_BUS.addListener(FrequencyBlockStressDiagnostic::serverStopped);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("freqstress")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> start(ctx.getSource().getPlayerOrException(), BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource().getPlayerOrException()))));
    }

    private static int start(ServerPlayer player, BlockPos pos) {
        if (active != null) {
            player.sendSystemMessage(Component.literal("[FreqStress] A run already exists; use /freqstress status or stop."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockEntity owner = level.getBlockEntity(pos);
        if (owner == null || owner.isRemoved()) {
            player.sendSystemMessage(Component.literal("[FreqStress] Position must contain a loaded block entity."));
            return 0;
        }
        active = new Session(level, pos.immutable(), owner, player.getUUID());
        player.sendSystemMessage(Component.literal("[FreqStress] Started: 50K TX + 50K RX on one block entity. " + "Registration is batched. Keep the chunk loaded; use /freqstress status."));
        return 1;
    }

    private static int status(ServerPlayer player) {
        Session session = active;
        if (session == null) {
            player.sendSystemMessage(Component.literal("[FreqStress] No active run."));
            return 0;
        }
        player.sendSystemMessage(Component.literal(session.statusLine()));
        player.sendSystemMessage(Component.literal(session.timingLine()));
        return 1;
    }

    private static int stop(ServerPlayer player) {
        Session session = active;
        if (session == null) {
            player.sendSystemMessage(Component.literal("[FreqStress] No active run."));
            return 0;
        }
        session.requestStop();
        player.sendSystemMessage(Component.literal("[FreqStress] Batched cleanup started; /freqstress status for progress."));
        return 1;
    }

    private static void serverTick(ServerTickEvent.Post event) {
        Session session = active;
        if (session == null || session.level.getServer() != event.getServer()) return;

        if (session.state != State.STOPPING && !session.ownerStillLoaded()) {
            LOGGER.warn("[FreqStress] Host block entity unloaded; cleaning up.");
            session.requestStop();
        }

        try {
            session.tick();
            if (session.state == State.DONE) {
                active = null;
                LOGGER.info("[FreqStress] All registrations closed.");
            }
        } catch (RuntimeException exception) {
            LOGGER.error("[FreqStress] Diagnostic failed; cleaning up", exception);
            session.requestStop();
        }
    }

    private static void levelUnload(LevelEvent.Unload event) {
        if (active != null && active.level == event.getLevel()) {
            active.closeAll();
            active = null;
        }
    }

    private static void serverStopped(ServerStoppedEvent event) {
        if (active != null && active.level.getServer() == event.getServer()) {
            active.closeAll();
            active = null;
        }
    }

    private enum State { REGISTERING, INDEXING, WARMUP, MEASURING, COMPLETE, STOPPING, DONE }

    private static final class Pair {
        final Vec3 home;
        Vec3 current;
        Pair(Vec3 home) {
            this.home = home;
            this.current = home;
        }
    }

    private record Tx(Pair pair, FrequencySignal signal) implements FrequencyEmitter {
        @Override public Vec3 position() { return pair.current; }
    }

    private record Rx(Pair pair, double tunedFrequencyHz) implements FrequencyReceiver {
        @Override public Vec3 position() { return pair.current; }
        @Override public double toleranceHz() { return 0.0; }
    }

    private static final class ReceiverCounter implements Consumer<FrequencyReceiver> {
        FrequencyReceiver expected;
        int matches;
        void reset(FrequencyReceiver expected) { this.expected = expected; matches = 0; }
        @Override public void accept(FrequencyReceiver receiver) {
            if (receiver == expected) matches++;
        }
    }

    private static final class EmitterCounter implements FrequencyReceptionConsumer {
        FrequencyEmitter expected;
        int matches;
        void reset(FrequencyEmitter expected) { this.expected = expected; matches = 0; }
        @Override public void accept(FrequencyEmitter emitter, FrequencySignal signal, double distanceSquared, double amplitude, double travelTime) {
            if (emitter == expected && distanceSquared <= RANGE_METERS * RANGE_METERS) matches++;
        }
    }

    private static final class Session {
        final ServerLevel level;
        final BlockPos blockPos;
        final BlockEntity owner;
        final UUID requester;
        final FrequencyEngine engine;

        final Pair[] pairs = new Pair[COUNT];
        final Tx[] tx = new Tx[COUNT];
        final Rx[] rx = new Rx[COUNT];
        final FrequencyRegistration[] txRegistrations = new FrequencyRegistration[COUNT];
        final FrequencyRegistration[] rxRegistrations = new FrequencyRegistration[COUNT];
        final FrequencyRegistration[] subscriptions = new FrequencyRegistration[COUNT];
        final ReceiverCounter receiverCounter = new ReceiverCounter();
        final EmitterCounter emitterCounter = new EmitterCounter();

        State state = State.REGISTERING;
        int registered;
        int closing = -1;
        int indexTicks;
        int warmupTicks;
        int measuredTicks;
        int movementCursor;
        long movedPairs;
        long registrationNanos;
        long txQueryNanos;
        long rxQueryNanos;
        long maxQueryTickNanos;
        long misses;
        long totalTxQueries;
        long totalRxQueries;

        Session(ServerLevel level, BlockPos blockPos, BlockEntity owner, UUID requester) {
            this.level = level;
            this.blockPos = blockPos;
            this.owner = owner;
            this.requester = requester;
            this.engine = FrequencyApi.get(level);
        }

        boolean ownerStillLoaded() {
            return !owner.isRemoved() && level.getChunkSource().hasChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4) && level.getBlockEntity(blockPos) == owner;
        }

        void tick() {
            switch (state) {
                case REGISTERING -> registerBatch();
                case INDEXING -> {
                    indexTicks++;
                    if (engine.pendingReceiverWorldPositionRefreshCount() == 0) {
                        state = State.WARMUP;
                        LOGGER.info("[FreqStress] 50K TX/RX indexed; beginning warmup.");
                    } else if (indexTicks > 1_000) {
                        throw new IllegalStateException("Spatial refresh queue stalled");
                    }
                }
                case WARMUP -> {
                    movePairs();
                    runQueries(false);
                    if (++warmupTicks == WARMUP_TICKS) {
                        state = State.MEASURING;
                        LOGGER.info("[FreqStress] Warmup complete; measuring 600 ticks.");
                    }
                }
                case MEASURING -> {
                    movePairs();
                    runQueries(true);
                    if (++measuredTicks == MEASURE_TICKS) {
                        state = State.COMPLETE;
                        LOGGER.info("{}", statusLine());
                        LOGGER.info("{}", timingLine());
                        var player = level.getServer().getPlayerList().getPlayer(requester);
                        if (player != null) {
                            player.sendSystemMessage(Component.literal("[FreqStress] COMPLETE. /freqstress status for results; /freqstress stop to clean up."));
                            player.sendSystemMessage(Component.literal(timingLine()));
                        }
                    }
                }
                case STOPPING -> closeBatch();
                case COMPLETE, DONE -> { }
            }
        }

        void registerBatch() {
            long start = System.nanoTime();
            int end = Math.min(COUNT, registered + REGISTER_PER_TICK);
            while (registered < end) {
                int i = registered++;
                Vec3 p = new Vec3(blockPos.getX() + 0.5 + (i & 127) * 2.0, blockPos.getY() + 1.5 + ((i >>> 7) & 15) * 2.0, blockPos.getZ() + 0.5 + (i >>> 11) * 2.0);
                Pair pair = new Pair(p);
                Tx emitter = new Tx(pair, SIGNALS[i & 127]);
                Rx receiver = new Rx(pair, emitter.signal().frequencyHz());
                pairs[i] = pair;
                tx[i] = emitter;
                rx[i] = receiver;
                txRegistrations[i] = engine.registerChunkBound(blockPos, emitter);
                rxRegistrations[i] = engine.registerReceiverChunkBound(blockPos, receiver);
                subscriptions[i] = i < FIXED ? engine.subscribeFixedWorldReceiver(receiver) : engine.subscribeReceiver(receiver);
            }
            registrationNanos += System.nanoTime() - start;
            if (registered == COUNT) {
                state = State.INDEXING;
                LOGGER.info("[FreqStress] Registered 50K TX + 50K RX; waiting for fixed snapshots.");
            }
        }

        void movePairs() {
            double offset = ((warmupTicks + measuredTicks) & 1) == 0 ? 0.5 : -0.5;
            for (int j = 0; j < 128; j++) {
                int i = FIXED + (movementCursor++ % (COUNT - FIXED));
                Pair pair = pairs[i];
                pair.current = pair.home.add(0.0, offset, 0.0);
            }
            movedPairs += 128;
        }

        void runQueries(boolean measured) {
            long txStart = System.nanoTime();
            int errors = 0;
            for (int q = 0; q < QUERIES_PER_DIRECTION; q++) {
                int i = (int) (((long) (warmupTicks + measuredTicks) * 397L + q * 131L) % COUNT);
                receiverCounter.reset(rx[i]);
                engine.forEachSubscribedReceiverForEmitterInRange(tx[i], RANGE_METERS, receiverCounter);
                if (receiverCounter.matches != 1) errors++;
            }
            long txElapsed = System.nanoTime() - txStart;

            long rxStart = System.nanoTime();
            for (int q = 0; q < QUERIES_PER_DIRECTION; q++) {
                int i = (int) (((long) (warmupTicks + measuredTicks) * 397L + q * 131L) % COUNT);
                emitterCounter.reset(tx[i]);
                engine.forEachReceivedInRange(rx[i], RANGE_METERS, emitterCounter);
                if (emitterCounter.matches != 1) errors++;
            }
            long rxElapsed = System.nanoTime() - rxStart;

            if (measured) {
                misses += errors;
                totalTxQueries += QUERIES_PER_DIRECTION;
                totalRxQueries += QUERIES_PER_DIRECTION;
                txQueryNanos += txElapsed;
                rxQueryNanos += rxElapsed;
                maxQueryTickNanos = Math.max(maxQueryTickNanos, txElapsed + rxElapsed);
            }
            if (errors != 0 && !measured) {
                throw new IllegalStateException("Warmup receiver/emitter mismatch: " + errors);
            }
        }

        String statusLine() {
            int pending = state == State.DONE ? 0 : engine.pendingReceiverWorldPositionRefreshCount();
            return String.format(Locale.ROOT, "[FreqStress] phase=%s TX=%d RX=%d fixed=%d moving=%d pending=%d measured=%d/%d misses=%d moved=%d cleanupRemaining=%d", state, registered, registered, Math.min(registered, FIXED), Math.max(0, registered - FIXED), pending, measuredTicks, MEASURE_TICKS, misses, movedPairs, state == State.STOPPING ? closing + 1 : 0);
        }

        String timingLine() {
            double txUs = totalTxQueries == 0 ? 0.0 : txQueryNanos / (1000.0 * totalTxQueries);
            double rxUs = totalRxQueries == 0 ? 0.0 : rxQueryNanos / (1000.0 * totalRxQueries);
            return String.format(Locale.ROOT, "[FreqStress] registration=%.2f ms indexingWait=%d ticks TX->RX=%.3f us/query RX->TX=%.3f us/query peakQueryBatch=%.3f ms totalQueries=%d", registrationNanos / 1_000_000.0, indexTicks, txUs, rxUs, maxQueryTickNanos / 1_000_000.0, totalTxQueries + totalRxQueries);
        }

        void requestStop() {
            if (state == State.STOPPING || state == State.DONE) return;
            closing = registered - 1;
            state = State.STOPPING;
        }

        void closeBatch() {
            for (int n = 0; n < STOP_PER_TICK && closing >= 0; n++, closing--) {
                closeIndex(closing);
            }
            if (closing < 0) state = State.DONE;
        }

        void closeAll() {
            for (int i = registered - 1; i >= 0; i--) closeIndex(i);
            state = State.DONE;
        }

        void closeIndex(int i) {
            if (subscriptions[i] != null) {
                subscriptions[i].close();
                subscriptions[i] = null;
            }
            if (rxRegistrations[i] != null) {
                rxRegistrations[i].close();
                rxRegistrations[i] = null;
            }
            if (txRegistrations[i] != null) {
                txRegistrations[i].close();
                txRegistrations[i] = null;
            }
            rx[i] = null;
            tx[i] = null;
            pairs[i] = null;
        }
    }
}
