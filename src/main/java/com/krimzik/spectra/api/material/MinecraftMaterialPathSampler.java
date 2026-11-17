package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.MaterialPathSampler;
import net.minecraft.server.level.ServerLevel;

public enum MinecraftMaterialPathSampler implements MaterialPathSampler<ServerLevel> {
    INSTANCE;

    @Override
    public double factor(ServerLevel level, double startX, double startY, double startZ, double endX, double endY, double endZ, boolean relativeToAir) {
        if (relativeToAir) {
            return FrequencyMaterialAttenuation.factorRelativeToAir(level, startX, startY, startZ, endX, endY, endZ);
        }

        return FrequencyMaterialAttenuation.factor(level, startX, startY, startZ, endX, endY, endZ);
    }
}