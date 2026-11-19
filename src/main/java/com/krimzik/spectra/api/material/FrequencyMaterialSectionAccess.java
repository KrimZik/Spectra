package com.krimzik.spectra.api.material;

import net.minecraft.server.MinecraftServer;

public interface FrequencyMaterialSectionAccess {
    FrequencyMaterialSectionCache spectra$materialSummary();

    FrequencyMaterialSectionSnapshot spectra$materialSnapshot(MinecraftServer server);

    boolean spectra$materialSnapshotCurrent(MinecraftServer server, FrequencyMaterialSectionSnapshot snapshot);
}