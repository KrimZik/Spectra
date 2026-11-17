package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.PathAttenuation;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

public final class MinecraftMaterialPropagation implements PathAttenuation<Vec3> {
    private final ServerLevel level;
    private final FrequencyMaterialPathCache worldCache;

    public MinecraftMaterialPropagation(ServerLevel level) {
        this.level = Objects.requireNonNull(level);
        this.worldCache = new FrequencyMaterialPathCache(level);
    }

    public double factorForReception(Object receiver, Object emitter, Vec3 start, Vec3 end, List<? extends SubLevelAccess> candidates) {
        if (!FrequencyMaterialAttenuation.enabled()) {
            return 1.0;
        }

        double world = worldCache.factor(receiver, emitter, start, end);
        if (candidates != null && candidates.isEmpty()) {
            return world;
        }
        double ships = candidates == null ? FrequencySableMaterialAttenuation.factor(level, start, end) : FrequencySableMaterialAttenuation.factorForSublevels(level, start, end, candidates);

        return world * ships;
    }

    public void clearCachedPaths() {
        worldCache.clear();
    }

    public record WorldCacheStats(long cacheHits, long coldMisses, long movedPaths, long traceBuilds, long rulesInvalidations, long sectionInvalidations, long failedTraces, long evictions, int entries) {}
    public WorldCacheStats worldCacheStats() {
        var stats = worldCache.stats();

        return new WorldCacheStats(stats.cacheHits(), stats.coldMisses(), stats.movedPaths(), stats.traceBuilds(), stats.rulesInvalidations(), stats.sectionInvalidations(), stats.failedTraces(), stats.evictions(), stats.entries());
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