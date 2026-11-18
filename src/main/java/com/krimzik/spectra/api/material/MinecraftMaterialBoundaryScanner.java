package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.geometry.MaterialBoundaryScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class MinecraftMaterialBoundaryScanner {
    private MinecraftMaterialBoundaryScanner() {}

    public static int scan(ServerLevel level, Vec3 start, Vec3 end, MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> consumer) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(consumer);

        return MaterialBoundaryScanner.scan(start.x, start.y, start.z, end.x, end.y, end.z, new LoadedMaterialSampler(level), consumer);
    }

    private static final class LoadedMaterialSampler implements MaterialBoundaryScanner.MaterialSampler<ResourceLocation> {
        private final ServerLevel level;
        private final ServerChunkCache source;
        private final int minY;
        private final int maxY;

        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;
        private LevelChunk chunk;
        private BlockPos.MutableBlockPos position;

        private LoadedMaterialSampler(ServerLevel level) {
            this.level = level;
            this.source = level.getChunkSource();
            this.minY = level.getMinBuildHeight();
            this.maxY = level.getMaxBuildHeight();
        }

        @Override
        public ResourceLocation materialAt(int x, int y, int z) {
            int cx = x >> 4;
            int cz = z >> 4;

            if (cx != chunkX || cz != chunkZ) {
                chunkX = cx;
                chunkZ = cz;
                chunk = source.getChunkNow(cx, cz);
            }

            if (chunk == null) {
                return null;
            }

            if (y < minY || y >= maxY) {
                return FrequencyMaterialClassifier.AIR;
            }

            int sectionIndex = (y >> 4) - (minY >> 4);
            LevelChunkSection[] sections = chunk.getSections();

            BlockState state;
            if (sectionIndex >= 0 && sectionIndex < sections.length && sections[sectionIndex] != null) {
                state = sections[sectionIndex].getBlockState(x & 15, y & 15, z & 15);
            } else {
                if (position == null) {
                    position = new BlockPos.MutableBlockPos();
                }

                position.set(x, y, z);
                state = chunk.getBlockState(position);
            }

            return FrequencyMaterialClassifier.classify(state);
        }
    }
}