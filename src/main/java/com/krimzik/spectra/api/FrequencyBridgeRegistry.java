package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.consumers.FrequencyBridgedMatchConsumer;
import com.krimzik.spectra.api.dimension.FrequencyDimensionBridge;
import com.krimzik.spectra.api.registration.FrequencyRegistration;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class FrequencyBridgeRegistry {
    private final MinecraftServer server;
    private final Map<ResourceKey<Level>, ArrayList<BridgeEntry>> incoming = new HashMap<>();

    private boolean stopped;
    private int activeQueries;

    FrequencyBridgeRegistry(MinecraftServer server) {
        this.server = Objects.requireNonNull(server);
    }

    public FrequencyRegistration register(FrequencyDimensionBridge bridge) {
        Objects.requireNonNull(bridge);
        checkWritable();

        BridgeEntry entry = new BridgeEntry(bridge);
        incoming.computeIfAbsent(bridge.destinationDimension(), unused -> new ArrayList<>()).add(entry);

        return entry;
    }

    public void forEachMatching(ServerLevel receiverLevel, FrequencyReceiver receiver, double rangeMeters, FrequencyBridgedMatchConsumer consumer) {
        Objects.requireNonNull(receiverLevel);
        Objects.requireNonNull(receiver);
        Objects.requireNonNull(consumer);
        checkRunning();

        if (receiverLevel.getServer() != server) {
            throw new IllegalArgumentException("Receiver belongs to another server");
        }

        if (!Double.isFinite(rangeMeters) || rangeMeters < 0.0) {
            throw new IllegalArgumentException("Invalid signal range");
        }

        ArrayList<BridgeEntry> bridges = incoming.get(receiverLevel.dimension());

        if (bridges == null || bridges.isEmpty()) {
            return;
        }

        Vec3 receiverPosition = FrequencySpace.worldPosition(receiverLevel, Objects.requireNonNull(receiver.position()));
        double rangeSquared = rangeMeters * rangeMeters;

        activeQueries++;

        try {
            for (BridgeEntry entry : bridges) {
                FrequencyDimensionBridge bridge = entry.bridge;

                ServerLevel sourceLevel = server.getLevel(bridge.sourceDimension());

                if (sourceLevel == null) {
                    continue;
                }

                FrequencyEngine engine = FrequencyApi.get(sourceLevel);

                engine.forEachMatching(receiver, (emitter, signal) -> {
                    Vec3 emitterPosition = FrequencySpace.worldPosition(sourceLevel, Objects.requireNonNull(emitter.position()));

                    Vec3 mappedPosition = Objects.requireNonNull(bridge.transform().toDestination(sourceLevel, receiverLevel, emitterPosition));

                    if (!Double.isFinite(mappedPosition.x) || !Double.isFinite(mappedPosition.y) || !Double.isFinite(mappedPosition.z)) {
                        throw new IllegalStateException("Dimension bridge returned invalid coordinates");
                    }

                    double distanceSquared = receiverPosition.distanceToSqr(mappedPosition);
                    if (!(distanceSquared <= rangeSquared)) {
                        return;
                    }

                    consumer.accept(sourceLevel, emitter, signal, distanceSquared, bridge.transmissionFactor());
                });
            }
        } finally {
            activeQueries--;
        }
    }

    public int bridgeCount() {
        checkRunning();

        int count = 0;

        for (ArrayList<BridgeEntry> entries : incoming.values()) {
            count += entries.size();
        }

        return count;
    }

    void shutdown() {
        stopped = true;
        incoming.clear();
    }

    private void checkRunning() {
        if (stopped) {
            throw new IllegalStateException("Bridge registry stopped");
        }

        if (!server.isSameThread()) {
            throw new IllegalStateException("Bridge registry must be accessed from the server thread");
        }
    }

    private void checkWritable() {
        checkRunning();

        if (activeQueries != 0) {
            throw new IllegalStateException("Cannot change bridges during a signal query");
        }
    }

    private final class BridgeEntry implements FrequencyRegistration {
        private final FrequencyDimensionBridge bridge;
        private boolean removed;

        private BridgeEntry(FrequencyDimensionBridge bridge) {
            this.bridge = bridge;
        }

        @Override
        public void close() {
            if (removed) {
                return;
            }

            if (!stopped) {
                checkWritable();

                ArrayList<BridgeEntry> entries = incoming.get(bridge.destinationDimension());

                if (entries != null) {
                    entries.remove(this);

                    if (entries.isEmpty()) {
                        incoming.remove(bridge.destinationDimension());
                    }
                }
            }

            removed = true;
        }
    }
}
