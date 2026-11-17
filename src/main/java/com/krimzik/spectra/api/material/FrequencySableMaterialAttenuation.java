package com.krimzik.spectra.api.material;

import com.krimzik.spectra.math.MaterialAttenuationMath;
import com.krimzik.spectra.propagation.geometry.SegmentAabbClip;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FrequencySableMaterialAttenuation {
    private FrequencySableMaterialAttenuation() {}

    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private static final class Workspace {
        final Vector3d vector = new Vector3d();
        final SegmentAabbClip.Interval interval = new SegmentAabbClip.Interval();
        boolean busy;
    }

    public static double factor(ServerLevel level, Vec3 start, Vec3 end) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        if (!FrequencyMaterialAttenuation.enabled() || start.equals(end)) {
            return 1.0;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || container.getLoadedCount() == 0) {
            return 1.0;
        }

        return factorForSublevels(level, start, end, container.getAllSubLevels());
    }

    static List<? extends SubLevelAccess> receiverCandidates(ServerLevel level, Vec3 receiver, double rangeMeters) {
        if (!FrequencyMaterialAttenuation.enabled()) {
            return null;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || container.getLoadedCount() == 0) {
            return List.of();
        }

        return receiverCandidates(container.getAllSubLevels(), receiver, rangeMeters);
    }

    static List<? extends SubLevelAccess> receiverCandidates(List<? extends SubLevelAccess> sublevels, Vec3 receiver, double rangeMeters) {
        double minX = receiver.x - rangeMeters;
        double minY = receiver.y - rangeMeters;
        double minZ = receiver.z - rangeMeters;
        double maxX = receiver.x + rangeMeters;
        double maxY = receiver.y + rangeMeters;
        double maxZ = receiver.z + rangeMeters;

        ArrayList<SubLevelAccess> candidates = null;
        for (int i = 0, size = sublevels.size(); i < size; i++) {
            SubLevelAccess sublevel = sublevels.get(i);
            BoundingBox3dc bounds = sublevel.boundingBox();

            if (bounds.maxX() < minX || bounds.minX() > maxX || bounds.maxY() < minY || bounds.minY() > maxY || bounds.maxZ() < minZ || bounds.minZ() > maxZ) {
                continue;
            }

            if (candidates == null) {
                candidates = new ArrayList<>();
            }

            candidates.add(sublevel);
        }

        return candidates == null ? List.of() : candidates;
    }

    public static double factorForSublevels(ServerLevel level, Vec3 start, Vec3 end, List<? extends SubLevelAccess> sublevels) {
        if (!FrequencyMaterialAttenuation.enabled() || start.equals(end) || sublevels.isEmpty()) {
            return 1.0;
        }

        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double result = 1.0;
        double worldPathLength = Double.NaN;
        Workspace work = null;

        double minX = Math.min(start.x, end.x);
        double minY = Math.min(start.y, end.y);
        double minZ = Math.min(start.z, end.z);
        double maxX = Math.max(start.x, end.x);
        double maxY = Math.max(start.y, end.y);
        double maxZ = Math.max(start.z, end.z);

        try {
            for (int i = 0, size = sublevels.size(); i < size; i++) {
                SubLevelAccess sublevel = sublevels.get(i);
                BoundingBox3dc bounds = sublevel.boundingBox();

                if (bounds.maxX() < minX || bounds.minX() > maxX || bounds.maxY() < minY || bounds.minY() > maxY || bounds.maxZ() < minZ || bounds.minZ() > maxZ) {
                    continue;
                }

                if (work == null) {
                    Workspace cached = WORKSPACE.get();
                    work = cached.busy ? new Workspace() : cached;
                    work.busy = true;
                }

                if (!SegmentAabbClip.clip(
                        start.x, start.y, start.z,
                        end.x, end.y, end.z,
                        bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                        work.interval
                )) {
                    continue;
                }

                double enter = work.interval.enter();
                double exit = work.interval.exit();

                Vector3d scratch = work.vector;

                Pose3dc pose = sublevel.logicalPose();

                scratch.set(
                        Math.fma(dx, enter, start.x),
                        Math.fma(dy, enter, start.y),
                        Math.fma(dz, enter, start.z)
                );

                pose.transformPositionInverse(scratch);

                double localStartX = scratch.x;
                double localStartY = scratch.y;
                double localStartZ = scratch.z;

                scratch.set(
                        Math.fma(dx, exit, start.x),
                        Math.fma(dy, exit, start.y),
                        Math.fma(dz, exit, start.z)
                );

                pose.transformPositionInverse(scratch);

                double localEndX = scratch.x;
                double localEndY = scratch.y;
                double localEndZ = scratch.z;

                double localFactor = MinecraftMaterialPathSampler.INSTANCE.factor(level, localStartX, localStartY, localStartZ, localEndX, localEndY, localEndZ, true);
                if (localFactor == 1.0) {
                    continue;
                }

                var scale = pose.scale();
                if (scale.x() == 1.0 && scale.y() == 1.0 && scale.z() == 1.0) {
                    result *= localFactor;
                } else {
                    double localDx = localEndX - localStartX;
                    double localDy = localEndY - localStartY;
                    double localDz = localEndZ - localStartZ;

                    double localDistance = Math.hypot(Math.hypot(localDx, localDy), localDz);
                    if (Double.isNaN(worldPathLength)) {
                        worldPathLength = Math.hypot(Math.hypot(dx, dy), dz);
                    }

                    double worldDistance = worldPathLength * (exit - enter);

                    if (localDistance > 0.0 && Double.isFinite(localDistance) && Double.isFinite(worldDistance)) {
                        result *= MaterialAttenuationMath.scaledFactor(localFactor, worldDistance, localDistance);
                    }
                }
            }

            return result;
        } finally {
            if (work != null) {
                work.busy = false;
            }
        }
    }
}