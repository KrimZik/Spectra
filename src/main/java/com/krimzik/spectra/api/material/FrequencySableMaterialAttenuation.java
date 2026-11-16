package com.krimzik.spectra.api.material;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

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

        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double result = 1.0;
        Vector3d scratch = null;

        for (SubLevelAccess sublevel : sublevels) {
            BoundingBox3dc bounds = sublevel.boundingBox();

            double enter = 0.0;
            double exit = 1.0;

            if (dx == 0.0) {
                if (start.x < bounds.minX() || start.x > bounds.maxX()) {
                    continue;
                }
            } else {
                double a = (bounds.minX() - start.x) / dx;
                double b = (bounds.maxX() - start.x) / dx;
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
                if (enter >= exit) {
                    continue;
                }
            }

            if (dy == 0.0) {
                if (start.y < bounds.minY() || start.y > bounds.maxY()) {
                    continue;
                }
            } else {
                double a = (bounds.minY() - start.y) / dy;
                double b = (bounds.maxY() - start.y) / dy;
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
                if (enter >= exit) {
                    continue;
                }
            }

            if (dz == 0.0) {
                if (start.z < bounds.minZ() || start.z > bounds.maxZ()) {
                    continue;
                }
            } else {
                double a = (bounds.minZ() - start.z) / dz;
                double b = (bounds.maxZ() - start.z) / dz;
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
                if (enter >= exit) {
                    continue;
                }
            }

            if (enter >= exit) {
                continue;
            }

            if (scratch == null) {
                scratch = new Vector3d();
            }

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

            double localFactor = FrequencyMaterialAttenuation.factorRelativeToAir(level, localStartX, localStartY, localStartZ, localEndX, localEndY, localEndZ);
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
                double worldDistance = Math.hypot(Math.hypot(dx, dy), dz) * (exit - enter);

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
