package com.krimzik.spectra.api.material;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;

public final class FrequencyMaterialRules {
    public record Rule(ResourceLocation block, TagKey<Block> tag, double decibelsPerMeter) {
        public Rule {
            if ((block == null) == (tag == null) || !Double.isFinite(decibelsPerMeter) || decibelsPerMeter < 0.0) {
                throw new IllegalArgumentException("Invalid material rule");
            }
        }

        public boolean matches(BlockState state) {
            return block != null ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(block) : state.is(tag);
        }
    }

    private static volatile List<Rule> overrides = List.of();

    private FrequencyMaterialRules() {}

    public static Rule block(ResourceLocation id, double loss) {
        return new Rule(Objects.requireNonNull(id), null, loss);
    }

    public static Rule tag(ResourceLocation id, double loss) {
        return new Rule(null, TagKey.create(Registries.BLOCK, Objects.requireNonNull(id)), loss);
    }

    public static void replaceOverrides(List<Rule> rules) {
        overrides = List.copyOf(Objects.requireNonNull(rules));
    }

    public static double decibelsPerMeter(BlockState state) {
        Objects.requireNonNull(state);
        List<Rule> rules = overrides;

        for (Rule rule : rules) {
            if (rule.block() != null && rule.matches(state)) {
                return rule.decibelsPerMeter();
            }
        }

        for (Rule rule : rules) {
            if (rule.tag() != null && rule.matches(state)) {
                return rule.decibelsPerMeter();
            }
        }

        if (state.isAir()) return 0.0;
        if (state.is(BlockTags.WOOL)) return 4.0;
        if (state.is(BlockTags.LEAVES)) return 0.2;
        if (state.is(BlockTags.LOGS)) return 1.5;
        if (state.is(BlockTags.PLANKS)) return 1.0;
        if (state.is(BlockTags.STONE_BRICKS)) return 3.0;
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) return 0.4;
        if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)) return 3.0;
        if (state.is(Blocks.IRON_BLOCK)) return 6.0;

        return 0.5;
    }

    public static double amplitudeFactor(double lossDb) {
        if (!Double.isFinite(lossDb) || lossDb < 0.0) {
            throw new IllegalArgumentException("Invalid attenuation");
        }

        return Math.exp(-0.11512925464970229 * lossDb);
    }
}