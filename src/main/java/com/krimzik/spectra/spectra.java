package com.krimzik.spectra;

import com.krimzik.spectra.api.FrequencyApi;
import com.krimzik.spectra.dev.FrequencyDevTransmitter;
import com.krimzik.spectra.dev.FrequencyEmitterLifecycleDiagnostic;
import com.krimzik.spectra.dev.FrequencyMotionDiagnostic;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(spectra.MODID)
public final class spectra {
    public static final String MODID = "spectra";

    public spectra(IEventBus modEventBus, ModContainer modContainer) {
        FrequencyApi.init();

        if (!FMLEnvironment.production) {
            FrequencyDevTransmitter.register(modEventBus);
            FrequencyMotionDiagnostic.install();
            FrequencyEmitterLifecycleDiagnostic.install();
        }
    }
}