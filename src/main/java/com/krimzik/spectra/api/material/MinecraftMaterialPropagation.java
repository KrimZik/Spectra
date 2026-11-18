package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.PathAttenuation;
import com.krimzik.spectra.propagation.geometry.MaterialBoundaryScanner;
import com.krimzik.spectra.propagation.geometry.SegmentAabbClip;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

public final class MinecraftMaterialPropagation implements PathAttenuation<Vec3> {
    private final ServerLevel level;
    private final FrequencyMaterialPathCache worldCache;
    private MaterialPathOverlay combinedWorkspace;
    private boolean combinedWorkspaceBusy;
    private final SegmentAabbClip.Interval waveShipInterval = new SegmentAabbClip.Interval();

    public MinecraftMaterialPropagation(ServerLevel level) {
        this.level = Objects.requireNonNull(level);
        this.worldCache = new FrequencyMaterialPathCache(level);
    }

    public double factorForReception(Vec3 start, Vec3 end, List<? extends SubLevelAccess> candidates)  {
        if (!FrequencyMaterialAttenuation.enabled()) {
            return 1.0;
        }

        double world = worldCache.factor(start, end);
        if (candidates != null && candidates.isEmpty()) {
            return world;
        }
        double ships = candidates == null ? FrequencySableMaterialAttenuation.factor(level, start, end) : FrequencySableMaterialAttenuation.factorForSublevels(level, start, end, candidates);

        return world * ships;
    }

    public double factorForDirectWaveReception(Vec3 start, Vec3 end, List<? extends SubLevelAccess> candidates, double frequencyHz, MinecraftCombinedWavePath wave) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(candidates);
        Objects.requireNonNull(wave);

        List<? extends SubLevelAccess> intersecting = candidates;

        if (!candidates.isEmpty()) {
            boolean intersects = false;

            for (SubLevelAccess sublevel : candidates) {
                var bounds = sublevel.boundingBox();

                if (SegmentAabbClip.clip(start.x, start.y, start.z, end.x, end.y, end.z, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), waveShipInterval)) {
                    intersects = true;
                    break;
                }
            }

            if (!intersects) {
                intersecting = List.of();
            }
        }

        double result = wave.evaluate(start, end, intersecting, frequencyHz, true);

        if (Double.isFinite(result) && result >= 0.0) {
            return result;
        }

        double fallback = factorForReception(start, end, intersecting);
        return Double.isFinite(fallback) && fallback >= 0.0 ? fallback : 0.0;
    }

    public double factorWithWorldBoundaries(Vec3 start, Vec3 end, MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> consumer) {
        return worldCache.factorWithBoundaries(start, end, consumer);
    }

    public double factorWithWorldSegments(Vec3 start, Vec3 end, FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
        return worldCache.factorWithSegments(start, end, consumer);
    }

    public double factorWithWorldLossSegments(Vec3 start, Vec3 end, FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
        return worldCache.factorWithLossSegments(start, end, consumer);
    }

    public boolean forEachCombinedMaterialSegment(Vec3 start, Vec3 end, List<? extends SubLevelAccess> candidates, FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
        return forEachCombinedSegment(start, end, candidates, consumer, false);
    }

    public boolean forEachCombinedMaterialLossSegment(Vec3 start, Vec3 end, List<? extends SubLevelAccess> candidates, FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
        return forEachCombinedSegment(start, end, candidates, consumer, true);
    }

    private boolean forEachCombinedSegment(Vec3 start, Vec3 end, List<? extends SubLevelAccess> candidates, FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer, boolean detailed) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(candidates);
        Objects.requireNonNull(consumer);

        if (start.equals(end)) {
            return true;
        }

        if (candidates.isEmpty()) {
            double result = detailed ? worldCache.factorWithLossSegments(start, end, consumer) : worldCache.factorWithSegments(start, end, consumer);
            return Double.isFinite(result);
        }

        boolean reusable = !combinedWorkspaceBusy;
        MaterialPathOverlay workspace;

        if (reusable) {
            if (combinedWorkspace == null) {
                combinedWorkspace = new MaterialPathOverlay();
            }

            workspace = combinedWorkspace;
            combinedWorkspaceBusy = true;
        } else {
            workspace = new MaterialPathOverlay();
        }

        try {
            workspace.reset();

            double world = detailed ? worldCache.factorWithLossSegments(start, end, workspace) : worldCache.factorWithSegments(start, end, workspace);
            if (!Double.isFinite(world)) {
                return false;
            }

            double ships = detailed ? FrequencySableMaterialAttenuation.factorForSublevelsWithLossSegments(level, start, end, candidates, workspace) : FrequencySableMaterialAttenuation.factorForSublevelsWithMaterials(level, start, end, candidates, workspace);
            if (!Double.isFinite(ships)) {
                return false;
            }

            return detailed ? workspace.replayWithLoss(consumer) : workspace.replay(consumer);
        } finally {
            if (reusable) {
                combinedWorkspaceBusy = false;
            }
        }
    }

    public void beginReceptionChunkBatch() {
        FrequencyMaterialAttenuation.beginChunkBatch(level);
    }

    public void endReceptionChunkBatch() {
        FrequencyMaterialAttenuation.endChunkBatch();
    }

    public void clearCachedPaths() {
        worldCache.clear();
    }

    public record WorldCacheStats(long cacheHits, long coldMisses, long traceBuilds, long rulesInvalidations, long sectionInvalidations, long failedTraces, long evictions, int entries) {}
    public WorldCacheStats worldCacheStats() {
        var stats = worldCache.stats();
        return new WorldCacheStats(stats.cacheHits(), stats.coldMisses(), stats.traceBuilds(), stats.rulesInvalidations(), stats.sectionInvalidations(), stats.failedTraces(), stats.evictions(), stats.entries());
    }

    public List<? extends SubLevelAccess> receiverCandidates(Vec3 receiver, double rangeMeters) {
        return FrequencySableMaterialAttenuation.receiverCandidates(level, receiver, rangeMeters);
    }

    public List<? extends SubLevelAccess> waveReceiverCandidates(Vec3 receiver, double rangeMeters) {
        return FrequencySableMaterialAttenuation.waveReceiverCandidates(level, receiver, rangeMeters);
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