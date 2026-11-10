package com.krimzik.spectra.dev;

import com.krimzik.spectra.api.FrequencyApi;
import com.krimzik.spectra.api.FrequencyEngine;
import com.krimzik.spectra.api.FrequencySignal;
import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class FrequencyEmitterLifecycleDiagnostic {
    private static final FrequencySignal SIGNAL = new FrequencySignal(915_000_123.0, 1.0, 0.0, 299_792_458.0);

    private static Probe probe;

    private FrequencyEmitterLifecycleDiagnostic() {}

    private record TestEmitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {}

    private static final class Probe {
        final ServerLevel level;
        final ChunkPos chunk;
        final BlockPos ownerPos;
        final FrequencyEmitter emitter;
        FrequencyRegistration registration;
        boolean pendingLoad;
        int loads, unloads, registrations, closes;

        Probe(ServerLevel level, BlockPos position) {
            this.level = level;
            this.chunk = new ChunkPos(position);
            this.ownerPos = position.immutable();
            this.emitter = new TestEmitter(Vec3.atCenterOf(position), SIGNAL);
        }
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener(FrequencyEmitterLifecycleDiagnostic::onCommands);
        NeoForge.EVENT_BUS.addListener(FrequencyEmitterLifecycleDiagnostic::onLoad);
        NeoForge.EVENT_BUS.addListener(FrequencyEmitterLifecycleDiagnostic::onUnload);
        NeoForge.EVENT_BUS.addListener(FrequencyEmitterLifecycleDiagnostic::onTick);
        NeoForge.EVENT_BUS.addListener(FrequencyEmitterLifecycleDiagnostic::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(FrequencyEmitterLifecycleDiagnostic::onServerStopped);
    }

    private static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("freqtest")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("emitter")
                        .then(Commands.literal("start").executes(ctx -> start(ctx.getSource().getPlayerOrException())))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource().getPlayerOrException())))
                        .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource().getPlayerOrException())))));
    }

    private static void register(Probe p) {
        if (p.registration != null) return;
        p.registration = FrequencyApi.get(p.level).registerChunkBound(p.ownerPos, p.emitter);
        p.registrations++;
    }

    private static void close(Probe p) {
        if (p.registration == null) return;
        p.registration.close();
        p.registration = null;
        p.closes++;
    }

    private static int start(ServerPlayer player) {
        if (probe != null) close(probe);

        probe = new Probe(player.serverLevel(), player.blockPosition());
        register(probe);

        player.sendSystemMessage(Component.literal("[FreqTest] Probe started at chunk " + probe.chunk.x + ", " + probe.chunk.z));
        return status(player);
    }

    private static int status(ServerPlayer player) {
        Probe p = probe;

        if (p == null) {
            player.sendSystemMessage(Component.literal("[FreqTest] No active emitter probe."));
            return 1;
        }

        FrequencyEngine engine = FrequencyApi.get(p.level);
        int[] matches = {0};

        engine.forEachMatching(new FrequencyReceiver() {
            public Vec3 position() {
                return p.emitter.position();
            }

            public double tunedFrequencyHz() {
                return SIGNAL.frequencyHz();
            }

            public double toleranceHz() {
                return 0.0;
            }
        }, (emitter, signal) -> {
            if (emitter == p.emitter) matches[0]++;
        });

        boolean loaded = p.level.getChunkSource().hasChunk(p.chunk.x, p.chunk.z);
        boolean registered = p.registration != null;
        boolean consistent = matches[0] == (registered ? 1 : 0) && registered == loaded && p.registrations - p.closes == (registered ? 1 : 0);

        player.sendSystemMessage(Component.literal("[FreqTest] " + (consistent ? "PASS" : "CHECK") + " | chunkLoaded=" + loaded + " registered=" + registered + " indexed=" + matches[0] + " engineTotal=" + engine.emitterCount()));
        player.sendSystemMessage(Component.literal("[FreqTest] loads=" + p.loads + " unloads=" + p.unloads + " registrations=" + p.registrations + " closes=" + p.closes + " pendingLoad=" + p.pendingLoad));

        return 1;
    }

    private static int clear(ServerPlayer player) {
        if (probe != null) {
            close(probe);

            player.sendSystemMessage(Component.literal("[FreqTest] Cleared; engineTotal=" + FrequencyApi.get(probe.level).emitterCount()));
            probe = null;
        }
        return 1;
    }

    private static boolean targets(ServerLevel level, ChunkPos chunk) {
        return probe != null && probe.level == level && probe.chunk.equals(chunk);
    }

    private static void onUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && targets(level, event.getChunk().getPos())) {
            probe.unloads++;
            probe.pendingLoad = false;
            probe.registration = null;
            probe.closes++;
        }
    }

    private static void onLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && targets(level, event.getChunk().getPos())) {
            probe.loads++;
            probe.pendingLoad = true;
        }
    }

    private static void onTick(ServerTickEvent.Post event) {
        Probe p = probe;

        if (p != null && p.pendingLoad && p.level.getServer() == event.getServer() && p.level.getChunkSource().hasChunk(p.chunk.x, p.chunk.z)) {
            p.pendingLoad = false;
            register(p);
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (probe != null && event.getLevel() == probe.level) {
            close(probe);
            probe = null;
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        if (probe != null && probe.level.getServer() == event.getServer()) {
            close(probe);
            probe = null;
        }
    }
}
