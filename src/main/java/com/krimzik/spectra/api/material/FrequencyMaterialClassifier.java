package com.krimzik.spectra.api.material;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

public final class FrequencyMaterialClassifier {
    public static final ResourceLocation AIR = id("air");
    public static final ResourceLocation WATER = id("water");
    public static final ResourceLocation WOOL = id("wool");
    public static final ResourceLocation FOLIAGE = id("foliage");
    public static final ResourceLocation LOG = id("logs");
    public static final ResourceLocation WOOD = id("wood");
    public static final ResourceLocation GLASS = id("glass");
    public static final ResourceLocation STONE = id("stone");
    public static final ResourceLocation METAL = id("metal");
    public static final ResourceLocation GENERIC = id("generic");

    private static final TagKey<Block> METAL_TAG = tag("metal");
    private static final TagKey<Block> STONE_TAG = tag("stone");
    private static final TagKey<Block> GLASS_TAG = tag("glass");
    private static final TagKey<Block> WOOL_TAG = tag("wool");
    private static final TagKey<Block> FOLIAGE_TAG = tag("foliage");
    private static final TagKey<Block> LOG_TAG = tag("logs");
    private static final TagKey<Block> WOOD_TAG = tag("wood");

    private static final VarHandle MATERIAL_HANDLE = MethodHandles.arrayElementVarHandle(ResourceLocation[].class);
    private static volatile ResourceLocation[] cache;

    private FrequencyMaterialClassifier() {}

    public static ResourceLocation classify(BlockState state) {
        Objects.requireNonNull(state);

        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0) {
            return detect(state);
        }

        ResourceLocation[] values = cache;

        if (values == null) {
            synchronized (FrequencyMaterialClassifier.class) {
                values = cache;

                if (values == null) {
                    values = new ResourceLocation[Block.BLOCK_STATE_REGISTRY.size()];
                    cache = values;
                }
            }
        }

        if (id >= values.length) {
            return detect(state);
        }

        ResourceLocation cached = (ResourceLocation) MATERIAL_HANDLE.getAcquire(values, id);
        if (cached != null) {
            return cached;
        }

        ResourceLocation material = detect(state);
        MATERIAL_HANDLE.setRelease(values, id, material);
        return material;
    }

    static void invalidate() {
        cache = null;
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("spectra", name);
    }

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("spectra", "materials/" + name));
    }

    private static ResourceLocation detect(BlockState state) {
        ResourceLocation assigned = FrequencyMaterialRules.assignedMaterial(state);

        if (assigned != null) {
            return assigned;
        }

        if (state.isAir()) {
            return AIR;
        }

        if (state.getFluidState().is(FluidTags.WATER) && (state.getBlock() instanceof LiquidBlock || state.is(Blocks.BUBBLE_COLUMN))) {
            return WATER;
        }

        if (state.is(METAL_TAG) || state.is(Blocks.IRON_BLOCK)) return METAL;
        if (state.is(STONE_TAG) || state.is(BlockTags.STONE_BRICKS) || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)) return STONE;
        if (state.is(GLASS_TAG) || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) return GLASS;
        if (state.is(WOOL_TAG) || state.is(BlockTags.WOOL)) return WOOL;
        if (state.is(FOLIAGE_TAG) || state.is(BlockTags.LEAVES)) return FOLIAGE;
        if (state.is(LOG_TAG) || state.is(BlockTags.LOGS)) return LOG;
        if (state.is(WOOD_TAG) || state.is(BlockTags.PLANKS)) return WOOD;

        return GENERIC;
    }
}