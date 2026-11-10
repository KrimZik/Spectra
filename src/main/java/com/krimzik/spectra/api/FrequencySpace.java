package com.krimzik.spectra.api;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Position;

import java.util.Objects;

public final class FrequencySpace {
    private FrequencySpace() {}

    public static double distanceSquaredMeters(Level level, Vec3 a, Vec3 b) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);

        return SableCompanion.INSTANCE.distanceSquaredWithSubLevels(level, a.x, a.y, a.z, b.x, b.y, b.z);
    }

    public static Vec3 worldPosition(Level level, Vec3 position) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(position);

        return SableCompanion.INSTANCE.projectOutOfSubLevel(level, (Position) position);
    }

    public static double distanceMeters(Level level, Vec3 a, Vec3 b) {
        return Math.sqrt(distanceSquaredMeters(level, a, b));
    }
}
