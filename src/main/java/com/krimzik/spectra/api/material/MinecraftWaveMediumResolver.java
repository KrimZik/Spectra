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
    private final ConcurrentHashMap<BlockState, WaveMedium> cache = new ConcurrentHashMap<>();

    public MinecraftWaveMediumResolver(WaveMedium fallback, List<Rule> rules) {
        this.fallback = Objects.requireNonNull(fallback);
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

        return cache.computeIfAbsent(state, this::resolveUncached);
    }

    private WaveMedium resolveUncached(BlockState state) {
        WaveMedium medium = blocks.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (medium != null) {
            return medium;
        }

        for (Rule rule : tags) {
            if (state.is(rule.tag())) {
                return rule.medium();
            }
        }

        return fallback;
    }
}