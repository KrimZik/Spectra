package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.PathAttenuation;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

public final class MinecraftMaterialPropagation implements PathAttenuation<Vec3> {
    private final ServerLevel level;

    public MinecraftMaterialPropagation(ServerLevel level) {
        this.level = Objects.requireNonNull(level);
    }

    public List<? extends SubLevelAccess> receiverCandidates(Vec3 receiver, double rangeMeters) {
        return FrequencySableMaterialAttenuation.receiverCandidates(level, receiver, rangeMeters);
    }

    public double factor(Vec3 start, Vec3 end, List<? extends SubLevelAccess> candidates) {
        if (candidates == null) {
            return factor(start, end);
        }

        if (!FrequencyMaterialAttenuation.enabled()) {
            return 1.0;
        }

        return MinecraftMaterialPathSampler.INSTANCE.factor(level, start.x, start.y, start.z, end.x, end.y, end.z, false) * FrequencySableMaterialAttenuation.factorForSublevels(level, start, end, candidates);
    }

    @Override
    public double factor(Vec3 start, Vec3 end) {
        if (!FrequencyMaterialAttenuation.enabled()) {
            return 1.0;
        }

        return MinecraftMaterialPathSampler.INSTANCE.factor(level, start.x, start.y, start.z, end.x, end.y, end.z, false) * FrequencySableMaterialAttenuation.factor(level, start, end);
    }
}
