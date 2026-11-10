package com.krimzik.spectra.api.motion;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

public final class FrequencyMotion {
    private static final double TICKS_PER_SECOND = 20.0;

    private static final Map<Entity, WorldSample> WORLD_SAMPLES = new WeakHashMap<>();
    private static final Map<Entity, Long> TELEPORT_EPOCHS = new WeakHashMap<>();

    public static long teleportEpoch(Entity entity) {
        return TELEPORT_EPOCHS.getOrDefault(Objects.requireNonNull(entity), 0L);
    }

    public static void markTeleported(Entity entity) {
        Objects.requireNonNull(entity);

        WORLD_SAMPLES.remove(entity);
        TELEPORT_EPOCHS.merge(entity, 1L, Long::sum);
    }

    public static void forgetEntity(Entity entity) {
        Objects.requireNonNull(entity);

        WORLD_SAMPLES.remove(entity);
        TELEPORT_EPOCHS.remove(entity);
    }

    private FrequencyMotion() {}

    public static FrequencyMotionSnapshot capture(ServerLevel level, Vec3 position) {
        return capture(level, position, Vec3.ZERO);
    }

    public static FrequencyMotionSnapshot capture(ServerLevel level, Vec3 position, Vec3 additionalWorldVelocity) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(position);
        Objects.requireNonNull(additionalWorldVelocity);

        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Motion must be captured on the server thread");
        }

        Vector3d local = new Vector3d(position.x, position.y, position.z);

        Vector3d world = new Vector3d();
        Vector3d velocity = new Vector3d();

        SableCompanion.INSTANCE.projectOutOfSubLevel(level, local, world);
        SableCompanion.INSTANCE.getVelocity(level, local, velocity);

        return new FrequencyMotionSnapshot(
            world.x, world.y, world.z,
            velocity.x + additionalWorldVelocity.x,
            velocity.y + additionalWorldVelocity.y,
            velocity.z + additionalWorldVelocity.z
        );
    }

    public static final class EntityCapture {
        private FrequencyMotionSnapshot motion;
        private FrequencyAccelerationTracker.Frame frame = FrequencyAccelerationTracker.Frame.WORLD;
        private UUID sublevelId;
        private Vec3 shipVelocity = Vec3.ZERO;
        private boolean unavailableBody;

        private EntityCapture() {}

        public FrequencyMotionSnapshot motion() { return motion; }
        public FrequencyAccelerationTracker.Frame frame() { return frame; }
        public UUID sublevelId() { return sublevelId; }
        public Vec3 shipVelocity() { return shipVelocity; }
        public boolean unavailableBody() { return unavailableBody; }
    }

    public static EntityCapture captureEntityDetailed(ServerLevel level, Entity entity) {
        EntityCapture details = new EntityCapture();
        details.motion = captureEntity(level, entity, details);
        return details;
    }

    public static FrequencyMotionSnapshot captureEntity(ServerLevel level, Entity entity) {
        return captureEntity(level, entity, null);
    }

    private static FrequencyMotionSnapshot captureEntity(ServerLevel level, Entity entity, EntityCapture details) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(entity);

        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Motion must be captured on the server thread");
        }

        if (entity.level() != level) {
            throw new IllegalArgumentException("Entity belongs to another level");
        }

        Vec3 current = Objects.requireNonNull(entity.position());
        SableCompanion companion = SableCompanion.INSTANCE;

        try {
            SubLevelAccess containing = companion.getContaining(level, current);
            SubLevelAccess ship = containing != null ? containing : companion.getTrackingOrVehicleSubLevel(entity);

            if (ship != null) {
                Pose3dc pose = ship.logicalPose();

                Vec3 currentLocal = containing != null ? current : pose.transformPositionInverse(current);
                Vec3 worldPosition = containing != null ? pose.transformPosition(currentLocal) : current;
                Vec3 carrierVelocity = pointVelocity(companion, level, ship, currentLocal);

                UUID shipId = ship.getUniqueId();

                int tick = level.getServer().getTickCount();
                WorldSample last = WORLD_SAMPLES.get(entity);

                boolean sameShip = last != null && !last.worldMode() && last.dimension().equals(level.dimension()) && shipId.equals(last.shipId()) && last.localPosition() != null;
                int elapsedTicks = sameShip ? tick - last.tick() : Integer.MIN_VALUE;
                Vec3 totalVelocity;

                if (sameShip && elapsedTicks == 0) {
                    totalVelocity = last.velocity();
                } else {
                    Vec3 relativeVelocity = sampleTrackedRelativeVelocity(pose, currentLocal, sameShip ? last.localPosition() : null, elapsedTicks);
                    totalVelocity = carrierVelocity.add(relativeVelocity);
                }

                FrequencyMotionSnapshot motion = snapshot(worldPosition, totalVelocity);
                rememberSample(level, entity, motion, false, shipId, currentLocal);

                if (details != null) {
                    details.frame = containing == null ? FrequencyAccelerationTracker.Frame.TRACKED : FrequencyAccelerationTracker.Frame.PLOT;
                    details.sublevelId = shipId;
                    details.shipVelocity = carrierVelocity;
                }

                return motion;
            }
        } catch (RuntimeException exception) {
            if (!isRemovedSableBody(exception)) {
                throw exception;
            }

            WORLD_SAMPLES.remove(entity);

            if (details != null) {
                details.unavailableBody = true;
            }
        }

        int tick = level.getServer().getTickCount();
        WorldSample last = WORLD_SAMPLES.get(entity);
        boolean sameWorld = last != null && last.dimension().equals(level.dimension());
        int elapsedTicks = sameWorld ? tick - last.tick() : Integer.MIN_VALUE;

        Vec3 previousVelocity = sameWorld && last.worldMode() ? last.velocity() : null;
        Vec3 velocity = sampleWorldVelocity(current, Objects.requireNonNull(entity.getDeltaMovement()), sameWorld ? last.position() : null, previousVelocity, elapsedTicks);
        FrequencyMotionSnapshot motion = snapshot(current, velocity);
        rememberSample(level, entity, motion, true, null, null);

        return motion;
    }

    static boolean isRemovedSableBody(RuntimeException exception) {
        return "Body has been removed".equals(exception.getMessage());
    }

    static Vec3 sampleWorldVelocity(Vec3 current, Vec3 fallbackMovement, Vec3 previous, Vec3 previousVelocity, int elapsedTicks) {
        Objects.requireNonNull(current);
        Objects.requireNonNull(fallbackMovement);

        if (previous != null) {
            if (elapsedTicks == 0 && previousVelocity != null) {
                return previousVelocity;
            }

            if (elapsedTicks == 1) {
                return current.subtract(previous).scale(TICKS_PER_SECOND);
            }
        }

        return fallbackMovement.scale(TICKS_PER_SECOND);
    }

    static Vec3 sampleTrackedRelativeVelocity(Pose3dc currentPose, Vec3 currentLocal, Vec3 previousLocal, int elapsedTicks) {
        if (previousLocal == null || elapsedTicks <= 0) {
            return Vec3.ZERO;
        }

        return currentPose.transformNormal(
                currentLocal.subtract(previousLocal)
        ).scale(TICKS_PER_SECOND / elapsedTicks);
    }

    private static Vec3 pointVelocity(SableCompanion companion, ServerLevel level, SubLevelAccess subLevel, Vec3 localPosition) {
        Vector3d velocity = companion.getVelocity(level, subLevel, new Vector3d(localPosition.x, localPosition.y, localPosition.z), new Vector3d());
        return new Vec3(velocity.x, velocity.y, velocity.z);
    }

    private static void rememberSample(ServerLevel level, Entity entity, FrequencyMotionSnapshot motion, boolean worldMode, UUID shipId, Vec3 localPosition) {
        WORLD_SAMPLES.put(
                entity,
                new WorldSample(level.dimension(), level.getServer().getTickCount(),
                new Vec3(motion.x(), motion.y(), motion.z()),
                new Vec3(motion.vx(), motion.vy(), motion.vz()), worldMode, shipId, localPosition)
        );
    }

    private static FrequencyMotionSnapshot snapshot(Vec3 position, Vec3 velocity) {
        return new FrequencyMotionSnapshot(
                position.x, position.y, position.z,
                velocity.x, velocity.y, velocity.z
        );
    }

    private record WorldSample(ResourceKey<Level> dimension, int tick, Vec3 position, Vec3 velocity, boolean worldMode, UUID shipId, Vec3 localPosition) {}
}
