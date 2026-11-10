package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.motion.FrequencyMotion;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

public final class FrequencyApi {
    private static final Map<ServerLevel, FrequencyEngine> ENGINES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, FrequencyBridgeRegistry> BRIDGES = new IdentityHashMap<>();

    private FrequencyApi() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(FrequencyApi::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(FrequencyApi::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(FrequencyApi::onServerStopped);
        NeoForge.EVENT_BUS.addListener(FrequencyApi::onEntityTeleport);
        NeoForge.EVENT_BUS.addListener(FrequencyApi::onEntityLeaveLevel);
        NeoForge.EVENT_BUS.addListener(FrequencyApi::onEntityTickPost);
    }

    public static FrequencyRegistration registerBlockEmitter(BlockEntity owner, FrequencyEmitter emitter) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(emitter);

        if (!(owner.getLevel() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Emitter owner must belong to a server level");
        }

        if (owner.isRemoved()) {
            throw new IllegalStateException("Cannot register a removed block entity");
        }

        FrequencyEngine engine = get(level);
        if (ModList.get().isLoaded("sable")) {
            FrequencyRegistration registration = SableEmitterLifecycle.registerIfShip(level, owner.getBlockPos(), emitter, engine);

            if (registration != null) {
                return registration;
            }
        }

        return engine.registerChunkBound(owner.getBlockPos(), emitter);
    }

    public static FrequencyRegistration registerEntityEmitter(Entity owner, FrequencyEmitter emitter) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(emitter);

        if (!(owner.level() instanceof ServerLevel level)) throw new IllegalArgumentException("Emitter owner must belong to a server level");
        return get(level).registerEntityBound(owner, emitter);
    }

    public static FrequencyRegistration registerEntityReceiver(Entity owner, FrequencyReceiver receiver) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(receiver);

        if (!(owner.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Receiver owner must belong to a server level");
        }

        return get(level).registerReceiverEntityBound(owner, receiver);
    }

    public static FrequencyRegistration registerBlockReceiver(BlockEntity owner, FrequencyReceiver receiver) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(receiver);

        if (!(owner.getLevel() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Receiver owner must belong to a server level");
        }

        if (owner.isRemoved()) {
            throw new IllegalStateException("Cannot register a removed block entity");
        }

        FrequencyEngine engine = get(level);
        if (ModList.get().isLoaded("sable")) {
            FrequencyRegistration shipRegistration = SableEmitterLifecycle.registerReceiverIfShip(level, owner.getBlockPos(), receiver, engine);
            if (shipRegistration != null) {
                return shipRegistration;
            }
        }

        return engine.registerReceiverChunkBound(owner.getBlockPos(), receiver);
    }

    public static FrequencyEngine get(ServerLevel level) {
        Objects.requireNonNull(level);
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Frequency engine must be accessed from the server thread");
        return ENGINES.computeIfAbsent(level, FrequencyEngine::new);
    }

    public static FrequencyBridgeRegistry bridges(MinecraftServer server) {
        Objects.requireNonNull(server);

        if (!server.isSameThread()) {
            throw new IllegalStateException("Bridge registry must be accessed from the server thread");
        }

        return BRIDGES.computeIfAbsent(server, FrequencyBridgeRegistry::new);
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        long chunkKey = event.getChunk().getPos().toLong();

        Runnable cleanup = () -> {
            FrequencyEngine engine = ENGINES.get(level);

            if (engine != null) {
                engine.onChunkUnload(chunkKey);
            }
        };

        if (level.getServer().isSameThread()) {
            cleanup.run();
        } else {
            level.getServer().execute(cleanup);
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        FrequencyEngine engine = ENGINES.remove(level);
        if (engine != null) engine.shutdown();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        MinecraftServer server = event.getServer();
        FrequencyBridgeRegistry registry = BRIDGES.remove(server);

        if (registry != null) {
            registry.shutdown();
        }

        var iterator = ENGINES.entrySet().iterator();

        while (iterator.hasNext()) {
            var entry = iterator.next();

            if (entry.getKey().getServer() == server) {
                entry.getValue().shutdown();
                iterator.remove();
            }
        }
    }

    private static void onEntityTeleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (!event.isCanceled() && entity.level() instanceof ServerLevel) {
            FrequencyMotion.markTeleported(entity);

            FrequencyEngine engine = ENGINES.get((ServerLevel) entity.level());
            if (engine != null) {
                engine.onEntityPositionChanged(entity);
            }
        }
    }

    private static void onEntityTickPost(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        FrequencyEngine engine = ENGINES.get(level);
        if (engine != null) {
            engine.onTrackedEntityTick(entity);
        }
    }

    private static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        Entity entity = event.getEntity();
        Runnable cleanup = () -> {
            FrequencyEngine engine = ENGINES.get(level);

            if (engine != null) {
                engine.onEntityLeave(entity);
            }

            FrequencyMotion.forgetEntity(entity);
        };

        if (level.getServer().isSameThread()) {
            cleanup.run();
        } else {
            level.getServer().execute(cleanup);
        }
    }
}
