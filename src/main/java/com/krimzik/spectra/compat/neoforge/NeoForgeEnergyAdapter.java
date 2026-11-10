package com.krimzik.spectra.compat.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public final class NeoForgeEnergyAdapter {
    private NeoForgeEnergyAdapter() {}

    @Nullable
    public static IEnergyStorage findStorage(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(pos);

        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Energy capabilities must be accessed on the server thread");
        }

        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return null;
        }

        return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
    }

    public static int availableToExtract(IEnergyStorage storage, int requestedFe) {
        Objects.requireNonNull(storage);

        if (requestedFe < 0) {
            throw new IllegalArgumentException("Invalid FE request");
        }

        if (requestedFe == 0 || !storage.canExtract()) {
            return 0;
        }

        return storage.extractEnergy(requestedFe, true);
    }

    public static int extract(IEnergyStorage storage, int requestedFe) {
        Objects.requireNonNull(storage);

        if (requestedFe < 0) {
            throw new IllegalArgumentException("Invalid FE request");
        }

        if (requestedFe == 0 || !storage.canExtract()) {
            return 0;
        }

        return storage.extractEnergy(requestedFe, false);
    }
}
