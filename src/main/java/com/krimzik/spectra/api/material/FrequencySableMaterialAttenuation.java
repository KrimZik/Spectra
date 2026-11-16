package com.krimzik.spectra.api.material;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class FrequencySableMaterialAttenuation {
    private FrequencySableMaterialAttenuation() {}

    public static double factor(ServerLevel level, Vec3 start, Vec3 end) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        if (!FrequencyMaterialAttenuation.enabled() || start.equals(end)) {
            return 1.0;
        }

        BoundingBox3d query = new BoundingBox3d(
                Math.min(start.x, end.x),
                Math.min(start.y, end.y),
                Math.min(start.z, end.z),
                Math.max(start.x, end.x),
                Math.max(start.y, end.y),
                Math.max(start.z, end.z)
        );

        return factorForSublevels(level, start, end, SableCompanion.INSTANCE.getAllIntersecting(level, query));
    }

    static double factorForSublevels(ServerLevel level, Vec3 start, Vec3 end, Iterable<? extends SubLevelAccess> sublevels) {
        if (!FrequencyMaterialAttenuation.enabled() || start.equals(end)) {
            return 1.0;
        }

        double result = 1.0;
        double[] interval = null;

        for (SubLevelAccess sublevel : sublevels) {
            if (interval == null) {
                interval = new double[2];
            }

            if (!intersection(start, end, sublevel.boundingBox(), interval)) {
                continue;
            }

            Vec3 worldStart = start.lerp(end, interval[0]);
            Vec3 worldEnd = start.lerp(end, interval[1]);

            Pose3dc pose = sublevel.logicalPose();

            Vec3 localStart = pose.transformPositionInverse(worldStart);
            Vec3 localEnd = pose.transformPositionInverse(worldEnd);

            double localFactor = FrequencyMaterialAttenuation.factor(level, localStart, localEnd);
            if (localFactor >= 1.0) {
                continue;
            }

            var scale = pose.scale();
            if (scale.x() == 1.0 && scale.y() == 1.0 && scale.z() == 1.0) {
                result *= localFactor;
            } else {
                double localDistance = localStart.distanceTo(localEnd);
                double worldDistance = worldStart.distanceTo(worldEnd);

                if (localDistance > 0.0 && Double.isFinite(localDistance) && Double.isFinite(worldDistance)) {
                    result *= Math.pow(localFactor, worldDistance / localDistance);
                }
            }
        }

        return result;
    }

    static double[] intersection(Vec3 start, Vec3 end, BoundingBox3dc bounds) {
        double[] result = new double[2];
        return intersection(start, end, bounds, result) ? result : null;
    }

    private static boolean intersection(Vec3 start, Vec3 end, BoundingBox3dc bounds, double[] interval) {
        interval[0] = 0.0;
        interval[1] = 1.0;

        if (!clipAxis(start.x, end.x - start.x, bounds.minX(), bounds.maxX(), interval)) {
            return false;
        }

        if (!clipAxis(start.y, end.y - start.y, bounds.minY(), bounds.maxY(), interval)) {
            return false;
        }

        if (!clipAxis(start.z, end.z - start.z, bounds.minZ(), bounds.maxZ(), interval)) {
            return false;
        }

        return interval[1] > interval[0];
    }

    private static boolean clipAxis(double origin, double direction, double min, double max, double[] interval) {
        if (direction == 0.0) {
            return origin >= min && origin <= max;
        }

        double entry = (min - origin) / direction;
        double exit = (max - origin) / direction;

        if (entry > exit) {
            double swap = entry;
            entry = exit;
            exit = swap;
        }

        interval[0] = Math.max(interval[0], entry);
        interval[1] = Math.min(interval[1], exit);

        return interval[0] <= interval[1];
    }
}
