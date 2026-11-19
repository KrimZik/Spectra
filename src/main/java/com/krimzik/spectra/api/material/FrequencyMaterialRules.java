package com.krimzik.spectra.api.material;

import com.krimzik.spectra.math.MaterialAttenuationMath;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class FrequencyMaterialRules {
    public record Rule(ResourceLocation block, TagKey<Block> tag, ResourceLocation material, Double decibelsPerMeter) {
        public Rule {
            if ((block == null) == (tag == null)) {
                throw new IllegalArgumentException("Expected one block or tag");
            }

            if (material == null && decibelsPerMeter == null) {
                throw new IllegalArgumentException("Material rule has no properties");
            }

            if (decibelsPerMeter != null && (!Double.isFinite(decibelsPerMeter) || decibelsPerMeter < 0.0)) {
                throw new IllegalArgumentException("Invalid attenuation");
            }
        }

        public boolean matches(BlockState state) {
            return block != null ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(block) : state.is(tag);
        }
    }

    public record Properties(Double decibelsPerMeter, Double speedMetersPerSecond, Double impedance) {
        public Properties {
            if (decibelsPerMeter != null && (!Double.isFinite(decibelsPerMeter) || decibelsPerMeter < 0.0)) {
                throw new IllegalArgumentException("Invalid attenuation");
            }

            if (speedMetersPerSecond != null && (!Double.isFinite(speedMetersPerSecond) || speedMetersPerSecond <= 0.0)) {
                throw new IllegalArgumentException("Invalid propagation speed");
            }

            if (impedance != null && (!Double.isFinite(impedance) || impedance <= 0.0)) {
                throw new IllegalArgumentException("Invalid impedance");
            }
        }

        public Properties merge(Properties other) {
            return new Properties(combine(decibelsPerMeter, other.decibelsPerMeter), combine(speedMetersPerSecond, other.speedMetersPerSecond), combine(impedance, other.impedance));
        }

        private static Double combine(Double first, Double second) {
            if (first != null && second != null && !first.equals(second)) {
                throw new IllegalArgumentException("Conflicting material properties");
            }

            return first != null ? first : second;
        }
    }

    private static volatile List<Rule> overrides = List.of();
    private static volatile Map<ResourceLocation, Properties> materials = Map.of();
    private static final VarHandle LOSS_HANDLE = MethodHandles.arrayElementVarHandle(double[].class);
    private static volatile double[] attenuationCache;
    private static volatile double airLossDbPerMeter;
    private static volatile double waterLossDbPerMeter = 0.5;
    private static volatile long materialRevision;

    public static long revision() {
        return materialRevision;
    }

    private FrequencyMaterialRules() {}

    public static synchronized void setEnvironmentLoss(double air, double water) {
        if (!Double.isFinite(air) || air < 0.0 || !Double.isFinite(water) || water < 0.0) {
            throw new IllegalArgumentException("Invalid environmental attenuation");
        }

        airLossDbPerMeter = air;
        waterLossDbPerMeter = water;
        attenuationCache = null;
        materialRevision++;
    }

    public static double airLossDbPerMeter() {
        return airLossDbPerMeter;
    }

    public static double waterLossDbPerMeter() {
        return waterLossDbPerMeter;
    }

    static boolean canSkipAirSections() {
        Properties air = materials.get(FrequencyMaterialClassifier.AIR);
        return overrides.isEmpty() && (air == null || air.decibelsPerMeter() == null);
    }

    public static Rule block(ResourceLocation id, double loss) {
        return new Rule(Objects.requireNonNull(id), null, null, loss);
    }

    public static Rule tag(ResourceLocation id, double loss) {
        return new Rule(null, TagKey.create(Registries.BLOCK, Objects.requireNonNull(id)), null, loss);
    }

    public static void replaceOverrides(List<Rule> rules) {
        replaceMaterials(rules, Map.of());
    }

    public static synchronized void replaceMaterials(List<Rule> rules, Map<ResourceLocation, Properties> definitions) {
        List<Rule> nextRules = List.copyOf(Objects.requireNonNull(rules));
        Map<ResourceLocation, Properties> nextMaterials = Map.copyOf(Objects.requireNonNull(definitions));

        overrides = nextRules;
        materials = nextMaterials;
        FrequencyMaterialClassifier.invalidate();
        attenuationCache = null;
        materialRevision++;
    }

    public static Properties properties(ResourceLocation material) {
        return materials.get(material);
    }

    static ResourceLocation assignedMaterial(BlockState state) {
        List<Rule> rules = overrides;
        for (Rule rule : rules) {
            if (rule.block() != null && rule.material() != null && rule.matches(state)) {
                return rule.material();
            }
        }

        for (Rule rule : rules) {
            if (rule.tag() != null && rule.material() != null && rule.matches(state)) {
                return rule.material();
            }
        }

        return null;
    }

    static Double overrideLoss(BlockState state) {
        List<Rule> rules = overrides;
        for (Rule rule : rules) {
            if (rule.block() != null && rule.decibelsPerMeter() != null && rule.matches(state)) {
                return rule.decibelsPerMeter();
            }
        }

        for (Rule rule : rules) {
            if (rule.tag() != null && rule.decibelsPerMeter() != null && rule.matches(state)) {
                return rule.decibelsPerMeter();
            }
        }

        return null;
    }

    public static double decibelsPerMeter(BlockState state) {
        Objects.requireNonNull(state);

        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0) {
            return calculateLoss(state);
        }

        double[] values = attenuationCache;
        if (values == null) {
            synchronized (FrequencyMaterialRules.class) {
                values = attenuationCache;

                if (values == null) {
                    values = new double[Block.BLOCK_STATE_REGISTRY.size()];
                    Arrays.fill(values, -1.0);
                    attenuationCache = values;
                }
            }
        }

        if (id >= values.length) {
            return calculateLoss(state);
        }

        double cached = (double) LOSS_HANDLE.getAcquire(values, id);
        if (cached >= 0.0) {
            return cached;
        }

        double calculated = calculateLoss(state);
        LOSS_HANDLE.setRelease(values, id, calculated);
        return calculated;
    }

    private static double calculateLoss(BlockState state) {
        Double override = overrideLoss(state);

        if (override != null) {
            return override;
        }

        ResourceLocation material = FrequencyMaterialClassifier.classify(state);
        Properties properties = materials.get(material);

        if (properties != null && properties.decibelsPerMeter() != null) {
            return properties.decibelsPerMeter();
        }

        if (material.equals(FrequencyMaterialClassifier.AIR)) return airLossDbPerMeter;
        if (material.equals(FrequencyMaterialClassifier.WATER)) return waterLossDbPerMeter;
        if (material.equals(FrequencyMaterialClassifier.WOOL)) return 4.0;
        if (material.equals(FrequencyMaterialClassifier.FOLIAGE)) return 0.2;
        if (material.equals(FrequencyMaterialClassifier.LOG)) return 1.5;
        if (material.equals(FrequencyMaterialClassifier.WOOD)) return 1.0;
        if (material.equals(FrequencyMaterialClassifier.GLASS)) return 0.4;
        if (material.equals(FrequencyMaterialClassifier.STONE)) return 3.0;
        if (material.equals(FrequencyMaterialClassifier.METAL)) return 6.0;

        return 0.5;
    }

    public static double amplitudeFactor(double lossDb) {
        return MaterialAttenuationMath.amplitudeFactor(lossDb);
    }

    static double relativeAmplitudeFactor(double lossDb) {
        return MaterialAttenuationMath.relativeAmplitudeFactor(lossDb);
    }
}