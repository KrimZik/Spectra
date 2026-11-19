package com.krimzik.spectra.api.material;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class FrequencyMaterialWorldSnapshot {
    private static final int MAX_CHUNKS = 1024;
    private static final int MAX_SECTIONS = 8192;

    private record Section(LevelChunkSection source, FrequencyMaterialSectionSnapshot snapshot, boolean onlyAir) {}

    static final class Chunk {
        private final LevelChunk source;
        private final Section[] sections;

        private Chunk(LevelChunk source, Section[] sections) {
            this.source = source;
            this.sections = sections;
        }

        boolean loaded() {
            return source != null;
        }

        private Section captured(int index) {
            if (index < 0 || index >= sections.length || sections[index] == null) {
                throw new IllegalStateException("Material section was not captured");
            }
            return sections[index];
        }

        FrequencyMaterialSectionSnapshot section(int index) {
            return captured(index).snapshot();
        }

        boolean onlyAir(int index) {
            return captured(index).onlyAir();
        }
    }

    private final ServerLevel level;
    private final MinecraftServer server;
    private final Map<Long, Chunk> chunks;
    private final int minY;
    private final int maxY;
    private final long revision;
    private final boolean enabled;
    private final boolean skipAir;
    private final double airLoss;
    private final double effectiveAirLoss;

    private FrequencyMaterialWorldSnapshot(ServerLevel level, Map<Long, Chunk> chunks, long revision, boolean enabled, boolean skipAir, double airLoss, double effectiveAirLoss) {
        this.level = level;
        this.server = level.getServer();
        this.chunks = Map.copyOf(chunks);
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();
        this.revision = revision;
        this.enabled = enabled;
        this.skipAir = skipAir;
        this.airLoss = airLoss;
        this.effectiveAirLoss = effectiveAirLoss;
    }

    public static Builder builder(ServerLevel level) {
        return new Builder(level);
    }

    Chunk chunk(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        Chunk result = chunks.get(key);

        if (result == null) {
            throw new IllegalStateException("Material chunk was not captured");
        }

        return result;
    }

    int minY() {
        return minY;
    }

    int maxY() {
        return maxY;
    }

    boolean enabled() {
        return enabled;
    }

    boolean skipAir() {
        return skipAir;
    }

    double airLoss() {
        return airLoss;
    }

    double effectiveAirLoss() {
        return effectiveAirLoss;
    }

    public boolean currentOnServer() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Snapshot validation requires the server thread");
        }

        if (revision != FrequencyMaterialRules.revision() || enabled != FrequencyMaterialAttenuation.enabled() || skipAir != FrequencyMaterialRules.canSkipAirSections()) {
            return false;
        }

        var source = level.getChunkSource();

        for (var entry : chunks.entrySet()) {
            long key = entry.getKey();
            Chunk captured = entry.getValue();

            LevelChunk current = source.getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (current != captured.source) {
                return false;
            }

            if (current == null) {
                continue;
            }

            LevelChunkSection[] sections = current.getSections();

            for (int i = 0; i < captured.sections.length; i++) {
                Section saved = captured.sections[i];
                if (saved == null) {
                    continue;
                }

                if (i >= sections.length || sections[i] != saved.source()) {
                    return false;
                }

                if (saved.snapshot() != null) {
                    if (!(saved.source() instanceof FrequencyMaterialSectionAccess access) || !access.spectra$materialSnapshotCurrent(server, saved.snapshot())) {
                        return false;
                    }
                } else if (saved.source() != null && saved.onlyAir() != saved.source().hasOnlyAir()) {
                    return false;
                }
            }
        }

        return true;
    }

    public static final class Builder {
        private final ServerLevel level;
        private final MinecraftServer server;
        private final long revision;
        private final boolean enabled;
        private final boolean skipAir;
        private final double airLoss;
        private final double effectiveAirLoss;
        private final Map<Long, MutableChunk> chunks = new HashMap<>();
        private int capturedSections;
        private boolean built;

        private static final class MutableChunk {
            final LevelChunk source;
            final LevelChunkSection[] sections;
            final Section[] captured;

            MutableChunk(LevelChunk source) {
                this.source = source;
                this.sections = source == null ? new LevelChunkSection[0] : source.getSections();
                this.captured = new Section[sections.length];
            }

            Chunk freeze() {
                return new Chunk(source, captured.clone());
            }
        }

        private Builder(ServerLevel level) {
            this.level = Objects.requireNonNull(level);
            this.server = level.getServer();
            this.revision = FrequencyMaterialRules.revision();
            this.enabled = FrequencyMaterialAttenuation.enabled();
            this.skipAir = FrequencyMaterialRules.canSkipAirSections();
            this.airLoss = FrequencyMaterialRules.airLossDbPerMeter();
            this.effectiveAirLoss = FrequencyMaterialRules.decibelsPerMeter(Blocks.AIR.defaultBlockState());
            requireServerThread();
        }

        private void requireServerThread() {
            if (!server.isSameThread() || built) {
                throw new IllegalStateException("Invalid material snapshot builder access");
            }
        }

        public Builder includePath(Vec3 start, Vec3 end) {
            requireServerThread();
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);

            if (!Double.isFinite(start.x) || !Double.isFinite(start.y) || !Double.isFinite(start.z) || !Double.isFinite(end.x) || !Double.isFinite(end.y) || !Double.isFinite(end.z)) {
                throw new IllegalArgumentException("Nonfinite material path");
            }

            int minX = (int) Math.floor(Math.min(start.x, end.x));
            int maxX = (int) Math.floor(Math.max(start.x, end.x));
            int minZ = (int) Math.floor(Math.min(start.z, end.z));
            int maxZ = (int) Math.floor(Math.max(start.z, end.z));

            int minChunkX = (minX - 1) >> 4;
            int maxChunkX = (maxX + 1) >> 4;
            int minChunkZ = (minZ - 1) >> 4;
            int maxChunkZ = (maxZ + 1) >> 4;

            long count = ((long) maxChunkX - minChunkX + 1) * ((long) maxChunkZ - minChunkZ + 1);
            if (count > MAX_CHUNKS) {
                throw new IllegalArgumentException("Material snapshot coverage too large");
            }

            int minSectionY = ((int) Math.floor(Math.min(start.y, end.y)) - 1) >> 4;
            int maxSectionY = ((int) Math.floor(Math.max(start.y, end.y)) + 1) >> 4;
            int baseSectionY = level.getMinBuildHeight() >> 4;

            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    long key = ChunkPos.asLong(cx, cz);

                    MutableChunk chunk = chunks.get(key);
                    if (chunk == null) {
                        if (chunks.size() >= MAX_CHUNKS) {
                            throw new IllegalArgumentException("Material snapshot chunk limit exceeded");
                        }

                        chunk = new MutableChunk(level.getChunkSource().getChunkNow(cx, cz));
                        chunks.put(key, chunk);
                    }

                    if (chunk.source == null) {
                        continue;
                    }

                    int first = Math.max(0, minSectionY - baseSectionY);
                    int last = Math.min(chunk.sections.length - 1, maxSectionY - baseSectionY);

                    for (int i = first; i <= last; i++) {
                        if (chunk.captured[i] != null) {
                            continue;
                        }

                        LevelChunkSection section = chunk.sections[i];

                        if (section == null) {
                            chunk.captured[i] = new Section(null, null, true);
                            continue;
                        }

                        boolean onlyAir = section.hasOnlyAir();
                        FrequencyMaterialSectionSnapshot snapshot = null;

                        if (!onlyAir || !skipAir) {
                            if (!(section instanceof FrequencyMaterialSectionAccess access)) {
                                throw new IllegalStateException("Material snapshot Mixin unavailable");
                            }

                            snapshot = access.spectra$materialSnapshot(server);
                        }

                        chunk.captured[i] = new Section(section, snapshot, onlyAir);

                        if (++capturedSections > MAX_SECTIONS) {
                            throw new IllegalArgumentException("Material snapshot section limit exceeded");
                        }
                    }
                }
            }

            return this;
        }

        public FrequencyMaterialWorldSnapshot build() {
            requireServerThread();
            built = true;

            if (revision != FrequencyMaterialRules.revision() || enabled != FrequencyMaterialAttenuation.enabled() || skipAir != FrequencyMaterialRules.canSkipAirSections()) {
                throw new IllegalStateException("Material rules changed during capture");
            }

            Map<Long, Chunk> frozen = new HashMap<>(chunks.size());
            for (var entry : chunks.entrySet()) {
                frozen.put(entry.getKey(), entry.getValue().freeze());
            }

            return new FrequencyMaterialWorldSnapshot(level, frozen, revision, enabled, skipAir, airLoss, effectiveAirLoss);
        }
    }
}