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

            if (nonemptyAir != null && solid != null) {
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

    private static void message(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[FreqMaterial] " + text));
    }
}
