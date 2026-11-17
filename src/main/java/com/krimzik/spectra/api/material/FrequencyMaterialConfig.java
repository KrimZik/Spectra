package com.krimzik.spectra.api.material;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class FrequencyMaterialConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.DoubleValue AIR_LOSS;
    private static final ModConfigSpec.DoubleValue WATER_LOSS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("materials");
        ENABLED = builder
                .comment("Enable material-dependent signal attenuation.")
                .define("enabled", true);

        AIR_LOSS = builder
                .comment("Additional attenuation through air, in dB per block.")
                .defineInRange("airLossDbPerMeter", 0.0, 0.0, 1000.0);

        WATER_LOSS = builder
                .comment("Attenuation through water, in dB per block.")
                .defineInRange("waterLossDbPerMeter", 0.5, 0.0, 1000.0);

        builder.pop();

        SPEC = builder.build();
    }

    private FrequencyMaterialConfig() {}

    public static void register(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        bus.addListener(FrequencyMaterialConfig::onLoading);
        bus.addListener(FrequencyMaterialConfig::onReloading);
        bus.addListener(FrequencyMaterialConfig::onUnloading);
    }

    private static void onLoading(ModConfigEvent.Loading event) {
        apply(event);
    }

    private static void onReloading(ModConfigEvent.Reloading event) {
        apply(event);
    }

    private static void onUnloading(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            FrequencyMaterialAttenuation.setEnabled(false);
            FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
        }
    }

    private static void apply(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            FrequencyMaterialRules.setEnvironmentLoss(AIR_LOSS.get(), WATER_LOSS.get());
            FrequencyMaterialAttenuation.setEnabled(ENABLED.get());
        }
    }
}