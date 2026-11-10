package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class SableEmitterLifecycle {
    private SableEmitterLifecycle() {}

    static FrequencyRegistration registerIfShip(ServerLevel level, BlockPos ownerPos, FrequencyEmitter emitter, FrequencyEngine engine) {

        SubLevelAccess ship = SableCompanion.INSTANCE.getContaining(level, ownerPos);
        if (ship == null) {
            return null;
        }

        ensureInstalled(level, engine);
        return engine.registerSublevelBound(ship.getUniqueId(), emitter);
    }

    static FrequencyRegistration registerReceiverIfShip(ServerLevel level, BlockPos ownerPos, FrequencyReceiver receiver, FrequencyEngine engine) {
        SubLevelAccess ship = SableCompanion.INSTANCE.getContaining(level, ownerPos);
        if (ship == null) {
            return null;
        }

        ensureInstalled(level, engine);
        return engine.registerReceiverSublevelBound(ship.getUniqueId(), receiver);
    }

    static void ensureInstalled(ServerLevel level, FrequencyEngine engine) {
        if (engine.sableObserverAttached) return;

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            throw new IllegalStateException("Sable sublevel container is unavailable");
        }

        final Map<UUID, Pose3d> receiverPoses = new HashMap<>();
        container.addObserver(new SubLevelObserver() {
            @Override
            public void onSubLevelRemoved(SubLevel sublevel, SubLevelRemovalReason reason) {
                UUID id = sublevel.getUniqueId();
                Runnable cleanup = () -> {
                    receiverPoses.remove(id);
                    engine.onSublevelRemoved(id);
                };

                if (level.getServer().isSameThread()) {
                    cleanup.run();
                } else {
                    level.getServer().execute(cleanup);
                }
            }

            @Override
            public void tick(SubLevelContainer subLevels) {
                Runnable update = () -> observeTrackedReceiverPoses(subLevels, engine, receiverPoses);
                if (level.getServer().isSameThread()) {
                    update.run();
                } else {
                    level.getServer().execute(update);
                }
            }
        });

        engine.sableObserverAttached = true;
    }

    static void observeTrackedReceiverPoses(SubLevelContainer container, FrequencyEngine engine, Map<UUID, Pose3d> snapshots) {
        Objects.requireNonNull(container);
        Objects.requireNonNull(engine);
        Objects.requireNonNull(snapshots);

        snapshots.keySet().removeIf(id -> !engine.hasReceiverSublevel(id));
        engine.forEachReceiverSublevelId(id -> {
            SubLevel ship = container.getSubLevel(id);

            if (ship == null || ship.isRemoved()) {
                return;
            }

            Pose3dc current = Objects.requireNonNull(ship.logicalPose());
            Pose3d previous = snapshots.get(id);

            if (previous == null) {
                snapshots.put(id, new Pose3d(current));
                engine.onSublevelPoseChanged(id);
                return;
            }

            boolean changed = !previous.position().equals(current.position()) || !previous.orientation().equals(current.orientation()) || !previous.rotationPoint().equals(current.rotationPoint()) || !previous.scale().equals(current.scale());
            if (!changed) {
                return;
            }

            previous.set(current);
            engine.onSublevelPoseChanged(id);
        });
    }
}
