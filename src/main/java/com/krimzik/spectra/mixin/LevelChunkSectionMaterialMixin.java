package com.krimzik.spectra.mixin;

import com.krimzik.spectra.api.material.FrequencyMaterialSectionAccess;
import com.krimzik.spectra.api.material.FrequencyMaterialSectionCache;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMaterialMixin implements FrequencyMaterialSectionAccess {
    @Unique
    private volatile FrequencyMaterialSectionCache spectra$cache;

    @Override
    public FrequencyMaterialSectionCache spectra$materialSummary() {
        FrequencyMaterialSectionCache cached = spectra$cache;

        if (cached == null || !cached.current()) {
            cached = FrequencyMaterialSectionCache.create((LevelChunkSection) (Object) this);
            spectra$cache = cached;
        }

        return cached;
    }

    @Inject(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN")
    )

    private void spectra$invalidate(int x, int y, int z, BlockState state, CallbackInfoReturnable<BlockState> callback) {
        spectra$cache = null;
    }

    @Inject(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN")
    )

    private void spectra$invalidateLocked(int x, int y, int z, BlockState state, boolean lock, CallbackInfoReturnable<BlockState> callback) {
        spectra$cache = null;
    }
}