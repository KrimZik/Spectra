package com.krimzik.spectra.dev;

import com.krimzik.spectra.api.material.FrequencyMaterialAttenuation;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

public final class FrequencyMaterialBenchmarkDiagnostic {
    private static final int WARMUP = 3000;
    private static final int SAMPLES = 9;
    private static final int QUERIES = 1000;
    private static final int[] LENGTHS = {16, 64, 128, 256};

    private static volatile double checksum;

    private FrequencyMaterialBenchmarkDiagnostic() {}

    private record Path(int y, int z) {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(FrequencyMaterialBenchmarkDiagnostic::registerCommands);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("freqmaterial")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("benchmark")
                        .executes(context -> benchmark(context.getSource().getPlayerOrException()))));
    }

    private static int benchmark(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();

        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            message(player, "Player chunk is not loaded.");
            return 0;
        }

        int x = chunkX << 4;
        int z = chunkZ << 4;

        LevelChunkSection[] sections = chunk.getSections();

        Path empty = null;
        Path nonemptyAir = null;
        Path solid = null;
        Path mixed = null;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int sectionIndex = 0;
             sectionIndex < sections.length;
             sectionIndex++) {

            LevelChunkSection section = sections[sectionIndex];

            if (section == null) {
                continue;
            }

            int baseY = level.getMinBuildHeight() + (sectionIndex << 4);

            if (section.hasOnlyAir()) {
                if (empty == null) {
                    empty = new Path(baseY + 8, z + 8);
                }
                continue;
            }

            if (nonemptyAir != null && solid != null && mixed != null) {
                continue;
            }

            for (int localY = 0; localY < 16; localY++) {
                int y = baseY + localY;

                for (int localZ = 0; localZ < 16; localZ++) {
                    int worldZ = z + localZ;
                    boolean allAir = true;
                    boolean allSolid = true;

                    for (int localX = 0; localX < 16; localX++) {
                        pos.set(x + localX, y, worldZ);

                        boolean air = chunk.getBlockState(pos).isAir();

                        allAir &= air;
                        allSolid &= !air;

                        if (!allAir && !allSolid) {
                            break;
                        }
                    }

                    if (allAir && nonemptyAir == null) {
                        nonemptyAir = new Path(y, worldZ);
                    }

                    if (allSolid && solid == null) {
                        solid = new Path(y, worldZ);
                    }

                    if (!allAir && !allSolid && mixed == null) {
                        mixed = new Path(y, worldZ);
                    }

                    if (nonemptyAir != null && solid != null) {
                        break;
                    }
                }

                if (nonemptyAir != null && solid != null) {
                    break;
                }
            }
        }

        message(player, String.format(Locale.ROOT, "Real chunk (%d, %d), queries=%d, samples=%d", chunkX, chunkZ, QUERIES, SAMPLES));

        boolean previousEnabled = FrequencyMaterialAttenuation.enabled();

        try {
            FrequencyMaterialAttenuation.setEnabled(true);
            measureAll(player, level, x, new String[]{"EMPTY_SECTION", "NONEMPTY_AIR", "SOLID"}, new Path[]{empty, nonemptyAir, solid});
            measureLookups(player, level, chunk, x, "EMPTY", empty);
            measureLookups(player, level, chunk, x, "AIR", nonemptyAir);
            measureLookups(player, level, chunk, x, "SOLID", solid);
            measureLookups(player, level, chunk, x, "MIXED", mixed);
            measureLongPaths(player, level, origin, empty, solid);
        } finally {
            FrequencyMaterialAttenuation.setEnabled(previousEnabled);
        }

        return 1;
    }

    private static void measureAll(ServerPlayer player, ServerLevel level, int x, String[] names, Path[] paths) {
        Vec3[] starts = new Vec3[paths.length];
        Vec3[] ends = new Vec3[paths.length];
        double[] factors = new double[paths.length];

        double[][] timings = new double[paths.length][SAMPLES];
        double[][] allocations = new double[paths.length][SAMPLES];

        for (int i = 0; i < paths.length; i++) {
            Path path = paths[i];

            if (path == null) {
                continue;
            }

            starts[i] = new Vec3(x + 0.5, path.y + 0.5, path.z + 0.5);
            ends[i] = new Vec3(x + 15.5, path.y + 0.5, path.z + 0.5);

            factors[i] = FrequencyMaterialAttenuation.factor(level, starts[i], ends[i]);
        }

        for (int warmup = 0; warmup < WARMUP; warmup++) {
            for (int offset = 0; offset < paths.length; offset++) {
                int index = (warmup + offset) % paths.length;

                if (paths[index] != null) {
                    checksum = FrequencyMaterialAttenuation.factor(level, starts[index], ends[index]);
                }
            }
        }

        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (memory != null && !memory.isThreadAllocatedMemoryEnabled()) {
            try {
                memory.setThreadAllocatedMemoryEnabled(true);
            } catch (SecurityException | UnsupportedOperationException ex) {
                memory = null;
            }
        }

        long threadId = Thread.currentThread().threadId();

        for (int sample = 0; sample < SAMPLES; sample++) {
            for (int offset = 0; offset < paths.length; offset++) {
                int index = (sample + offset) % paths.length;

                if (paths[index] == null) {
                    continue;
                }

                Vec3 start = starts[index];
                Vec3 end = ends[index];

                long beforeBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
                long startTime = System.nanoTime();
                double sum = 0.0;

                for (int i = 0; i < QUERIES; i++) {
                    sum += FrequencyMaterialAttenuation.factor(level, start, end);
                }

                long elapsed = System.nanoTime() - startTime;
                long afterBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

                checksum = sum;

                timings[index][sample] = elapsed / (QUERIES * 1000.0);
                allocations[index][sample] = memory == null ? -1.0 : (afterBytes - beforeBytes) / (double) QUERIES;
            }
        }

        for (int i = 0; i < paths.length; i++) {
            Path path = paths[i];
            if (path == null) {
                message(player, names[i] + ": no matching path in this chunk.");
                continue;
            }

            Arrays.sort(timings[i]);
            Arrays.sort(allocations[i]);

            message(player, String.format(Locale.ROOT, "%s y=%d z=%d factor=%.6f " + "median=%.3f us/path min=%.3f max=%.3f " + "alloc=%.1f B/path", names[i], path.y, path.z, factors[i], timings[i][SAMPLES / 2], timings[i][0], timings[i][SAMPLES - 1], allocations[i][SAMPLES / 2]));
        }
    }

    private static int readChunk(LevelChunk chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, int reads, int phase) {
        int air = 0;

        for (int i = 0; i < reads; i++) {
            pos.set(x + ((i + phase) & 15), y, z);
            if (chunk.getBlockState(pos).isAir()) {
                air++;
            }
        }

        return air;
    }

    private static int readSection(LevelChunkSection section, int y, int z, int reads, int phase) {
        int air = 0;

        for (int i = 0; i < reads; i++) {
            if (section.getBlockState((i + phase) & 15, y & 15, z & 15).isAir()) {
                air++;
            }
        }

        return air;
    }

    private static com.sun.management.ThreadMXBean memoryBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
            return null;
        }

        if (!bean.isThreadAllocatedMemoryEnabled()) {
            try {
                bean.setThreadAllocatedMemoryEnabled(true);
            } catch (SecurityException | UnsupportedOperationException ex) {
                return null;
            }
        }

        return bean;
    }

    private static void measureLookups(ServerPlayer player, ServerLevel level, LevelChunk chunk, int x, String name, Path path) {
        if (path == null) {
            message(player, "LOOKUP " + name + ": no matching row.");
            return;
        }

        int y = path.y();
        int z = path.z();
        int sectionIndex = (y - level.getMinBuildHeight()) >> 4;
        LevelChunkSection[] sections = chunk.getSections();

        if (sectionIndex < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null) {
            return;
        }

        LevelChunkSection section = sections[sectionIndex];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < 16; i++) {
            if (chunk.getBlockState(pos.set(x + i, y, z)) != section.getBlockState(i, y & 15, z & 15)) {
                message(player, "LOOKUP " + name + ": chunk and section results differ.");
                return;
            }
        }

        com.sun.management.ThreadMXBean memory = memoryBean();
        long threadId = Thread.currentThread().threadId();

        for (int reads : LENGTHS) {
            double[][] times = new double[2][SAMPLES];
            double[][] allocations = new double[2][SAMPLES];

            for (int i = 0; i < WARMUP; i++) {
                checksum = readChunk(chunk, pos, x, y, z, reads, i & 15);
                checksum = readSection(section, y, z, reads, i & 15);
            }

            for (int sample = 0; sample < SAMPLES; sample++) {
                for (int turn = 0; turn < 2; turn++) {
                    int mode = (sample + turn) & 1;

                    long before = memory == null ? 0L : memory.getThreadAllocatedBytes(threadId);
                    long started = System.nanoTime();
                    int sum = 0;

                    for (int q = 0; q < QUERIES; q++) {
                        int phase = q & 15;

                        sum += mode == 0 ? readChunk(chunk, pos, x, y, z, reads, phase) : readSection(section, y, z, reads, phase);
                    }

                    long elapsed = System.nanoTime() - started;
                    long after = memory == null ? 0L : memory.getThreadAllocatedBytes(threadId);

                    checksum = sum;
                    times[mode][sample] = elapsed / (double) (QUERIES * reads);
                    allocations[mode][sample] = memory == null ? -1.0 : (after - before) / (double) (QUERIES * reads);
                }
            }

            Arrays.sort(times[0]);
            Arrays.sort(times[1]);
            Arrays.sort(allocations[0]);
            Arrays.sort(allocations[1]);

            double chunkNs = times[0][SAMPLES / 2];
            double sectionNs = times[1][SAMPLES / 2];

            message(player, String.format(Locale.ROOT, "LOOKUP %s reads=%d chunk=%.2f ns/block section=%.2f ns/block ratio=%.2fx chunkAlloc=%.2f sectionAlloc=%.2f B/read", name, reads, chunkNs, sectionNs, chunkNs / sectionNs, allocations[0][SAMPLES / 2], allocations[1][SAMPLES / 2]));
        }
    }

    private static void measureLongPaths(ServerPlayer player, ServerLevel level, BlockPos origin, Path empty, Path solid) {
        Path[] paths = {empty, solid};
        String[] names = {"AIR_ROW", "SOLID_ROW"};

        com.sun.management.ThreadMXBean memory = memoryBean();
        long threadId = Thread.currentThread().threadId();

        for (int length : LENGTHS) {
            double startX = origin.getX() + 0.5 - length * 0.5;
            double endX = startX + length;

            int firstChunk = ((int) Math.floor(startX)) >> 4;
            int lastChunk = ((int) Math.floor(endX)) >> 4;
            int chunkZ = origin.getZ() >> 4;

            boolean loaded = true;

            for (int chunkX = firstChunk; chunkX <= lastChunk; chunkX++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    loaded = false;
                    break;
                }
            }

            if (!loaded) {
                message(player, "DISTANCE " + length + ": skipped, unloaded chunk.");
                continue;
            }

            for (int pathIndex = 0; pathIndex < paths.length; pathIndex++) {
                Path path = paths[pathIndex];
                if (path == null) {
                    continue;
                }

                double y = path.y() + 0.5;
                double z = origin.getZ() + 0.5;

                Vec3 start = new Vec3(startX, y, z);
                Vec3 end = new Vec3(endX, y, z);

                double factor = FrequencyMaterialAttenuation.factor(level, start, end);

                for (int i = 0; i < WARMUP; i++) {
                    checksum = FrequencyMaterialAttenuation.factor(level, start, end);
                }

                double[] times = new double[SAMPLES];
                double[] allocations = new double[SAMPLES];

                for (int sample = 0; sample < SAMPLES; sample++) {
                    long before = memory == null ? 0L : memory.getThreadAllocatedBytes(threadId);
                    long started = System.nanoTime();
                    double sum = 0.0;

                    for (int q = 0; q < QUERIES; q++) {
                        sum += FrequencyMaterialAttenuation.factor(level, start, end);
                    }

                    long elapsed = System.nanoTime() - started;
                    long after = memory == null ? 0L : memory.getThreadAllocatedBytes(threadId);

                    checksum = sum;
                    times[sample] = elapsed / (QUERIES * 1000.0);
                    allocations[sample] = memory == null ? -1.0 : (after - before) / (double) QUERIES;
                }

                Arrays.sort(times);
                Arrays.sort(allocations);

                message(player, String.format(Locale.ROOT, "DISTANCE %s blocks=%d factor=%.6f median=%.3f us/path alloc=%.1f B/path", names[pathIndex], length, factor, times[SAMPLES / 2], allocations[SAMPLES / 2]));
            }
        }
    }

    private static void message(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[FreqMaterial] " + text));
    }
}
