package com.krimzik.spectra.api.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FrequencyMaterialDataTest {
    private final FrequencyMaterialData listener = new FrequencyMaterialData();
    private final ResourceManager resources = mock(ResourceManager.class);
    private final ProfilerFiller profiler = mock(ProfilerFiller.class);

    @AfterEach
    void cleanup() {
        FrequencyMaterialRules.replaceOverrides(java.util.List.of());
        FrequencyMaterialRules.setEnvironmentLoss(0.0, 0.5);
    }

    @Test
    void reloadReplacesPreviousRules() {
        listener.apply(
                Map.of(
                        ResourceLocation.parse("spectra:first"),
                        JsonParser.parseString("""
                                {"block":"minecraft:stone","db_loss":9.0}
                                """)
                ),
                resources,
                profiler
        );

        assertEquals(9.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState()), 0.0);

        listener.apply(
                Map.of(
                        ResourceLocation.parse("spectra:second"),
                        JsonParser.parseString("""
                                {"block":"minecraft:glass","db_loss":2.0}
                                """)
                ),
                resources,
                profiler
        );

        assertEquals(3.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState()), 0.0);
        assertEquals(2.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.GLASS.defaultBlockState()), 0.0);
    }

    @Test
    void emptyReloadClearsOldRules() {
        listener.apply(
                Map.of(
                        ResourceLocation.parse("spectra:override"),
                        JsonParser.parseString("""
                                {"block":"minecraft:stone","db_loss":9.0}
                                """)
                ),
                resources,
                profiler
        );

        listener.apply(Map.of(), resources, profiler);

        assertEquals(3.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState()), 0.0);
    }

    @Test
    void invalidReloadDoesNotReplaceExistingRules() {
        listener.apply(
                Map.of(
                        ResourceLocation.parse("spectra:valid"),
                        JsonParser.parseString("""
                                {"block":"minecraft:stone","db_loss":9.0}
                                """)
                ),
                resources,
                profiler
        );

        assertThrows(IllegalArgumentException.class, () ->
                listener.apply(
                        Map.of(
                                ResourceLocation.parse("spectra:invalid"),
                                JsonParser.parseString("""
                                        {
                                          "block":"minecraft:stone",
                                          "tag":"minecraft:stone",
                                          "db_loss":5.0
                                        }
                                        """)
                        ),
                        resources,
                        profiler
                )
        );

        assertEquals(9.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState()), 0.0);
    }
}
