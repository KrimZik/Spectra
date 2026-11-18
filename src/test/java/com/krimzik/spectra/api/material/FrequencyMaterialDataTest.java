package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.WaveMedium;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    void customMaterialPropertiesAreSharedAcrossBlocks() {
        listener.apply(
                Map.of(
                        ResourceLocation.parse("spectra:rubber_definition"),
                        JsonParser.parseString("""
                            {
                              "material": "mymod:rubber",
                              "db_loss": 2.5,
                              "speed_mps": 1200.0,
                              "impedance": 2.0
                            }
                            """),
                        ResourceLocation.parse("spectra:rubber_stone"),
                        JsonParser.parseString("""
                            {
                              "block": "minecraft:stone",
                              "material": "mymod:rubber"
                            }
                            """),
                        ResourceLocation.parse("spectra:rubber_glass"),
                        JsonParser.parseString("""
                            {
                              "block": "minecraft:glass",
                              "material": "mymod:rubber"
                            }
                            """)
                ),
                resources,
                profiler
        );

        ResourceLocation rubber = ResourceLocation.parse("mymod:rubber");

        assertEquals(rubber, FrequencyMaterialClassifier.classify(Blocks.STONE.defaultBlockState()));
        assertEquals(rubber, FrequencyMaterialClassifier.classify(Blocks.GLASS.defaultBlockState()));

        assertEquals(2.5, FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState()));
        assertEquals(2.5, FrequencyMaterialRules.decibelsPerMeter(Blocks.GLASS.defaultBlockState()));

        var resolver = new MinecraftWaveMediumResolver(
                new WaveMedium(300.0, 1.0, 0.5),
                List.of()
        );

        WaveMedium medium = resolver.resolve(Blocks.STONE.defaultBlockState(), 100.0);

        assertEquals(1200.0, medium.speedMetersPerSecond());
        assertEquals(2.0, medium.impedance());
        assertEquals(2.5, medium.lossDbPerMeter());
    }

    @Test
    void missingMaterialPropertiesUseDefaults() {
        listener.apply(
                Map.of(
                        ResourceLocation.parse("spectra:unknown_material"),
                        JsonParser.parseString("""
                            {
                              "block": "minecraft:glass",
                              "material": "mymod:unknown"
                            }
                            """)
                ),
                resources,
                profiler
        );

        assertEquals(
                ResourceLocation.parse("mymod:unknown"),
                FrequencyMaterialClassifier.classify(Blocks.GLASS.defaultBlockState())
        );

        assertEquals(0.5, FrequencyMaterialRules.decibelsPerMeter(Blocks.GLASS.defaultBlockState()));

        WaveMedium fallback = new WaveMedium(300.0, 1.0, 0.75);
        var resolver = new MinecraftWaveMediumResolver(fallback, List.of());

        assertSame(fallback, resolver.resolve(Blocks.GLASS.defaultBlockState(), 100.0));
    }

    @Test
    void explicitAttenuationOverridesCustomMaterialDefault() {
        listener.apply(
                Map.of(
                        ResourceLocation.parse("spectra:rubber"),
                        JsonParser.parseString("""
                            {
                              "block": "minecraft:stone",
                              "material": "mymod:rubber",
                              "db_loss": 2.5
                            }
                            """),
                        ResourceLocation.parse("spectra:stone_override"),
                        JsonParser.parseString("""
                            {
                              "block": "minecraft:stone",
                              "db_loss": 9.0
                            }
                            """)
                ),
                resources,
                profiler
        );

        assertEquals(
                ResourceLocation.parse("mymod:rubber"),
                FrequencyMaterialClassifier.classify(Blocks.STONE.defaultBlockState())
        );

        assertEquals(9.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void conflictingMaterialDefinitionsDoNotReplaceActiveRules() {
        listener.apply(
                Map.of(
                        ResourceLocation.parse("spectra:valid"),
                        JsonParser.parseString("""
                            {
                              "block": "minecraft:stone",
                              "db_loss": 7.0
                            }
                            """)
                ),
                resources,
                profiler
        );

        assertThrows(IllegalArgumentException.class, () ->
                listener.apply(
                        Map.of(
                                ResourceLocation.parse("spectra:first"),
                                JsonParser.parseString("""
                                    {
                                      "material": "mymod:rubber",
                                      "db_loss": 2.5
                                    }
                                    """),
                                ResourceLocation.parse("spectra:second"),
                                JsonParser.parseString("""
                                    {
                                      "material": "mymod:rubber",
                                      "db_loss": 4.0
                                    }
                                    """)
                        ),
                        resources,
                        profiler
                )
        );

        assertEquals(7.0, FrequencyMaterialRules.decibelsPerMeter(Blocks.STONE.defaultBlockState()));
    }
}
