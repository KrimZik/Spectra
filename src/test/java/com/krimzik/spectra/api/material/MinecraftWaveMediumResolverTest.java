package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.WaveMedium;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinecraftWaveMediumResolverTest {
    private static final WaveMedium DEFAULT = new WaveMedium(200.0, 1.0, 0.0);
    private static final WaveMedium STONE = new WaveMedium(400.0, 3.0, 2.0);
    private static final WaveMedium TAGGED = new WaveMedium(300.0, 2.0, 1.0);

    @Test
    void unmatchedBlocksUseFallback() {
        var resolver = new MinecraftWaveMediumResolver(DEFAULT, List.of());
        assertSame(DEFAULT, resolver.resolve(Blocks.STONE.defaultBlockState(), 100.0));
    }

    @Test
    void explicitBlockRuleOverridesFallback() {
        var resolver = new MinecraftWaveMediumResolver(DEFAULT, List.of(MinecraftWaveMediumResolver.block(ResourceLocation.parse("minecraft:stone"), STONE)));

        assertSame(STONE, resolver.resolve(Blocks.STONE.defaultBlockState(), 100.0));
        assertSame(DEFAULT, resolver.resolve(Blocks.GLASS.defaultBlockState(), 100.0));
    }

    @Test
    void blockRulesTakePriorityOverTags() {
        BlockState state = mock(BlockState.class);

        when(state.getBlock()).thenReturn(Blocks.OAK_PLANKS);
        when(state.is(BlockTags.PLANKS)).thenReturn(true);

        var resolver = new MinecraftWaveMediumResolver(DEFAULT, List.of(MinecraftWaveMediumResolver.tag(ResourceLocation.parse("minecraft:planks"), TAGGED), MinecraftWaveMediumResolver.block(ResourceLocation.parse("minecraft:oak_planks"), STONE)));

        assertSame(STONE, resolver.resolve(state, 100.0));
    }

    @Test
    void tagRulesResolveMatchingMaterials() {
        BlockState state = mock(BlockState.class);

        when(state.getBlock()).thenReturn(Blocks.STONE);
        when(state.is(BlockTags.PLANKS)).thenReturn(true);

        var resolver = new MinecraftWaveMediumResolver(DEFAULT, List.of(MinecraftWaveMediumResolver.tag(ResourceLocation.parse("minecraft:planks"), TAGGED)));

        assertSame(TAGGED, resolver.resolve(state, 100.0));
    }

    @Test
    void independentModelsCanUseDifferentProperties() {
        var first = new MinecraftWaveMediumResolver(DEFAULT, List.of());
        var second = new MinecraftWaveMediumResolver(STONE, List.of());

        BlockState state = Blocks.GLASS.defaultBlockState();

        assertSame(DEFAULT, first.resolve(state, 100.0));
        assertSame(STONE, second.resolve(state, 100.0));
    }

    @Test
    void invalidFrequenciesAreRejected() {
        var resolver = new MinecraftWaveMediumResolver(DEFAULT, List.of());

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(Blocks.STONE.defaultBlockState(), 0.0));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(Blocks.STONE.defaultBlockState(), Double.NaN));
    }
}