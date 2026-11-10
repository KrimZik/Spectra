package com.krimzik.spectra.api.motion;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

public final class FrequencyAccelerationTracker {
    public enum Frame { WORLD, TRACKED, PLOT }
    public record Acceleration(double ax, double ay, double az) {}

    private long previousTick = Long.MIN_VALUE;
    private ResourceKey<Level> previousDimension;
    private UUID previousSublevel;
    private Frame previousFrame;
    private double previousVx;
    private double previousVy;
    private double previousVz;
    private Acceleration lastAcceleration;

    public Acceleration observe(FrequencyMotionSnapshot snapshot, long tick, ResourceKey<Level> dimension, UUID sublevelId, Frame frame, boolean continuous) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(frame, "frame");
        if (tick < 0L) {
            throw new IllegalArgumentException("Negative simulation tick");
        }

        if ((frame == Frame.WORLD) != (sublevelId == null)) {
            throw new IllegalArgumentException("WORLD requires no sublevel; ship frames require a sublevel ID");
        }

        double vx = snapshot.vx();
        double vy = snapshot.vy();
        double vz = snapshot.vz();

        boolean sameFrame = previousFrame == frame && dimension.equals(previousDimension) && Objects.equals(sublevelId, previousSublevel);
        if (continuous && sameFrame && tick == previousTick) {
            return lastAcceleration;
        }

        boolean adjacent = continuous && sameFrame && previousTick >= 0L && tick - previousTick == 1L;

        Acceleration acceleration = null;

        if (adjacent) {
            double ax = (vx - previousVx) * 20.0;
            double ay = (vy - previousVy) * 20.0;
            double az = (vz - previousVz) * 20.0;

            if (Double.isFinite(ax) && Double.isFinite(ay) && Double.isFinite(az)) {
                acceleration = new Acceleration(ax, ay, az);
            }
        }

        previousTick = tick;
        previousDimension = dimension;
        previousSublevel = sublevelId;
        previousFrame = frame;
        previousVx = vx;
        previousVy = vy;
        previousVz = vz;
        lastAcceleration = acceleration;
        return acceleration;
    }

    public void clear() {
        previousTick = Long.MIN_VALUE;
        previousDimension = null;
        previousSublevel = null;
        previousFrame = null;
        previousVx = previousVy = previousVz = 0.0;
        lastAcceleration = null;
    }
}
