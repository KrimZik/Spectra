package com.krimzik.spectra.compat.create;

import com.krimzik.spectra.math.FrequencyPowerMath;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

public final class CreatePowerAdapter {
    private CreatePowerAdapter() {}

    public static double rpm(KineticBlockEntity kinetic) {
        Objects.requireNonNull(kinetic);

        if (!(kinetic.getLevel() instanceof ServerLevel level)) {
            throw new IllegalStateException("Kinetic source must belong to a server level");
        }

        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Kinetic source must be read on the server thread");
        }

        if (kinetic.isRemoved()) {
            return 0.0;
        }

        return kinetic.getSpeed();
    }

    public static double signalFrequencyHz(KineticBlockEntity kinetic, double cyclesPerRevolution) {
        if (!Double.isFinite(cyclesPerRevolution) || cyclesPerRevolution < 0.0) {
            throw new IllegalArgumentException("Invalid cycles per revolution");
        }

        return FrequencyPowerMath.rpmToHz(rpm(kinetic)) * cyclesPerRevolution;
    }
}
