package com.krimzik.spectra.mixin;

import com.krimzik.spectra.api.material.FrequencyMaterialSectionAccess;
import com.krimzik.spectra.api.material.FrequencyMaterialSectionCache;
import com.krimzik.spectra.api.material.FrequencyMaterialSectionSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMaterialMixin implements FrequencyMaterialSectionAccess {
    @Unique
    private volatile FrequencyMaterialSectionCache spectra$cache;

    @Unique
    private volatile FrequencyMaterialSectionSnapshot spectra$snapshot;

    @Override
    public FrequencyMaterialSectionCache spectra$materialSummary() {
        FrequencyMaterialSectionCache cached = spectra$cache;

        if (cached == null || !cached.current()) {
            cached = FrequencyMaterialSectionCache.create((LevelChunkSection) (Object) this);
            spectra$cache = cached;
        }

        return cached;
    }

    @Override
    public FrequencyMaterialSectionSnapshot spectra$materialSnapshot(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Material snapshots must be captured on the server thread");
        }

        FrequencyMaterialSectionSnapshot snapshot = spectra$snapshot;
        if (snapshot == null || !snapshot.currentRules()) {
            snapshot = FrequencyMaterialSectionSnapshot.capture((LevelChunkSection) (Object) this);
            spectra$snapshot = snapshot;
        }

        return snapshot;
    }

    @Override
    public boolean spectra$materialSnapshotCurrent(MinecraftServer server, FrequencyMaterialSectionSnapshot snapshot) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Material snapshots must be validated on the server thread");
        }

        return snapshot != null && snapshot == spectra$snapshot && snapshot.currentRules();
    }

    @Inject(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN")
    )

    private void spectra$invalidate(int x, int y, int z, BlockState state, CallbackInfoReturnable<BlockState> callback) {
        spectra$cache = null;
        spectra$snapshot = null;
    }

    @Inject(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN")
    )

    private void spectra$invalidateLocked(int x, int y, int z, BlockState state, boolean lock, CallbackInfoReturnable<BlockState> callback) {
        spectra$cache = null;
        spectra$snapshot = null;
    }

    @Inject(
            method = "read(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At("RETURN")
    )

    private void spectra$invalidateRead(FriendlyByteBuf buffer, CallbackInfo callback) {
        spectra$cache = null;
        spectra$snapshot = null;
    }
}