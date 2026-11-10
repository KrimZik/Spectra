package com.krimzik.spectra.api;

import com.krimzik.spectra.math.FrequencyMath;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

public final class FrequencySimulationTime {
    private FrequencySimulationTime() {}

    public static long ticks(MinecraftServer server) {
        Objects.requireNonNull(server);

        if (!server.isSameThread()) {
            throw new IllegalStateException("Simulation time must be read on the server thread");
        }

        return Integer.toUnsignedLong(server.getTickCount());
    }

    public static long ticks(ServerLevel level) {
        Objects.requireNonNull(level);
        return ticks(level.getServer());
    }

    public static double seconds(MinecraftServer server) {
        return secondsFromTicks(ticks(server));
    }

    public static double seconds(ServerLevel level) {
        Objects.requireNonNull(level);
        return seconds(level.getServer());
    }

    public static double secondsFromTicks(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Invalid tick count");
        }

        return ticks / FrequencyMath.TICKS_PER_SECOND;
    }
}
