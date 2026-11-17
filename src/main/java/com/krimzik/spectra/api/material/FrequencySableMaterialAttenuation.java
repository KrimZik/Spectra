package com.krimzik.spectra.api.material;

import com.krimzik.spectra.math.MaterialAttenuationMath;
import com.krimzik.spectra.propagation.geometry.SegmentAabbClip;
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
        SegmentAabbClip.Interval interval = null;

        for (SubLevelAccess sublevel : sublevels) {
            BoundingBox3dc bounds = sublevel.boundingBox();

            if (interval == null) {
                interval = new SegmentAabbClip.Interval();
            }

            if (!SegmentAabbClip.clip(
                    start.x, start.y, start.z,
                    end.x, end.y, end.z,
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                    interval
            )) {
                continue;
            }

            double enter = interval.enter();
            double exit = interval.exit();

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
                double worldDistance = Math.hypot(Math.hypot(dx, dy), dz) * (exit - enter);

                if (localDistance > 0.0 && Double.isFinite(localDistance) && Double.isFinite(worldDistance)) {
                    result *= MaterialAttenuationMath.scaledFactor(localFactor, worldDistance, localDistance);
                }
            }
        }

        return result;
    }
}
