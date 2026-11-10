package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

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

    static void ensureInstalled(ServerLevel level, FrequencyEngine engine) {
        if (engine.sableObserverAttached) return;

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            throw new IllegalStateException("Sable sublevel container is unavailable");
        }

        container.addObserver(new SubLevelObserver() {
            @Override
            public void onSubLevelRemoved(SubLevel sublevel, SubLevelRemovalReason reason) {
                var id = sublevel.getUniqueId();
                if (level.getServer().isSameThread()) {
                    engine.onSublevelRemoved(id);
                } else {
                    level.getServer().execute(() -> engine.onSublevelRemoved(id));
                }
            }
        });

        engine.sableObserverAttached = true;
    }
}
