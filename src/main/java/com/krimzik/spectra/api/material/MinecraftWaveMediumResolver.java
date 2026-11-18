package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.WaveMedium;
import com.krimzik.spectra.propagation.WaveMediumResolver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class MinecraftWaveMediumResolver implements WaveMediumResolver<BlockState> {
    public record Rule(ResourceLocation block, TagKey<Block> tag, WaveMedium medium) {
        public Rule {
            if ((block == null) == (tag == null)) {
                throw new IllegalArgumentException("Expected one block or tag");
            }
            Objects.requireNonNull(medium);
        }
    }

    private final WaveMedium fallback;
    private final Map<ResourceLocation, WaveMedium> blocks;
    private final List<Rule> tags;
    private final Map<ResourceLocation, WaveMedium> materials;
    private volatile ConcurrentHashMap<BlockState, WaveMedium> cache = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<ResourceLocation, WaveMedium> materialCache = new ConcurrentHashMap<>();
    private volatile long cacheRevision = FrequencyMaterialRules.revision();

    public MinecraftWaveMediumResolver(WaveMedium fallback, List<Rule> rules) {
        this(fallback, Map.of(), rules);
    }

    public MinecraftWaveMediumResolver(WaveMedium fallback, Map<ResourceLocation, WaveMedium> materials, List<Rule> rules) {
        this.fallback = Objects.requireNonNull(fallback);
        this.materials = Map.copyOf(Objects.requireNonNull(materials));
        Objects.requireNonNull(rules);

        Map<ResourceLocation, WaveMedium> blocks = new HashMap<>();
        List<Rule> tags = new ArrayList<>();

        for (Rule rule : rules) {
            Objects.requireNonNull(rule);

            if (rule.block() != null) {
                blocks.putIfAbsent(rule.block(), rule.medium());
            } else {
                tags.add(rule);
            }
        }

        this.blocks = Map.copyOf(blocks);
        this.tags = List.copyOf(tags);
    }
    public static Rule block(ResourceLocation id, WaveMedium medium) {
        return new Rule(Objects.requireNonNull(id), null, medium);
    }

    public static Rule tag(ResourceLocation id, WaveMedium medium) {
        return new Rule(null, TagKey.create(Registries.BLOCK, Objects.requireNonNull(id)), medium);
    }

    @Override
    public WaveMedium resolve(BlockState state, double frequencyHz) {
        Objects.requireNonNull(state);

        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            throw new IllegalArgumentException("Invalid frequency");
        }

        refreshCaches();
        return cache.computeIfAbsent(state, this::resolveUncached);
    }

    public WaveMedium resolveMaterial(ResourceLocation material, double frequencyHz) {
        Objects.requireNonNull(material);

        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            throw new IllegalArgumentException("Invalid frequency");
        }

        refreshCaches();
        return namedMaterial(material);
    }

    public boolean hasStateSpecificRules() {
        return !blocks.isEmpty() || !tags.isEmpty();
    }

    private void refreshCaches() {
        long revision = FrequencyMaterialRules.revision();

        if (cacheRevision != revision) {
            cache = new ConcurrentHashMap<>();
            materialCache = new ConcurrentHashMap<>();
            cacheRevision = revision;
        }
    }

    private WaveMedium namedMaterial(ResourceLocation material) {
        WaveMedium cached = materialCache.get(material);
        return cached != null ? cached : materialCache.computeIfAbsent(material, this::resolveNamedMaterial);
    }

    private WaveMedium resolveNamedMaterial(ResourceLocation material) {
        WaveMedium base = materials.getOrDefault(material, fallback);
        FrequencyMaterialRules.Properties properties = FrequencyMaterialRules.properties(material);

        if (properties == null) {
            return base;
        }

        double speed = properties.speedMetersPerSecond() != null ? properties.speedMetersPerSecond() : base.speedMetersPerSecond();
        double impedance = properties.impedance() != null ? properties.impedance() : base.impedance();
        double loss = properties.decibelsPerMeter() != null ? properties.decibelsPerMeter() : base.lossDbPerMeter();

        return new WaveMedium(speed, impedance, loss);
    }

    private WaveMedium resolveUncached(BlockState state) {
        WaveMedium direct = blocks.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()));

        if (direct != null) {
            return direct;
        }

        for (Rule rule : tags) {
            if (state.is(rule.tag())) {
                return rule.medium();
            }
        }

        ResourceLocation material = FrequencyMaterialClassifier.classify(state);
        WaveMedium base = namedMaterial(material);
        Double overrideLoss = FrequencyMaterialRules.overrideLoss(state);

        if (overrideLoss == null) {
            return base;
        }

        return new WaveMedium(base.speedMetersPerSecond(), base.impedance(), overrideLoss);
    }
}