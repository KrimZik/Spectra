package com.krimzik.spectra.api;

import com.krimzik.spectra.api.material.FrequencyMaterialSectionSnapshot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencyParallelBatchTest {
    private static void await(FrequencyParallelBatch.Job job) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (!job.complete() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }

        assertTrue(job.complete(), "Parallel batch did not complete");
    }

    @Test
    void workersCalculateEveryIndexExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);

        try {
            AtomicInteger[] visits = new AtomicInteger[4096];

            for (int i = 0; i < visits.length; i++) {
                visits[i] = new AtomicInteger();
            }

            var job = FrequencyParallelBatch.submit(visits.length, 4, i -> {
                visits[i].incrementAndGet();
                return i * 0.25;
                },
                task -> {
                executor.execute(task);
                return true;
            });

            assertNotNull(job);
            await(job);

            double[] results = job.results();

            for (int i = 0; i < results.length; i++) {
                assertEquals(i * 0.25, results[i], 0.0);
                assertEquals(1, visits[i].get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void partialQueueRejectionDoesNotLoseWork() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            AtomicInteger attempts = new AtomicInteger();

            var job = FrequencyParallelBatch.submit(
                    4096,
                    4,
                    i -> i + 1.0,
                    task -> {
                        if (attempts.getAndIncrement() != 0) {
                            return false;
                        }

                        executor.execute(task);
                        return true;
                    }
            );

            assertNotNull(job);
            await(job);

            double[] results = job.results();

            for (int i = 0; i < results.length; i++) {
                assertEquals(i + 1.0, results[i], 0.0);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void workerFailureIsReported() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var job = FrequencyParallelBatch.submit(2048, 2, i -> {
                if (i == 777) {
                    throw new IllegalStateException("Test failure");
                }

                return i;
            },
            task -> {
                executor.execute(task);
                return true;
            });

            assertNotNull(job);
            await(job);
            assertThrows(IllegalStateException.class, job::results);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void smallOrDisabledWorkloadsAreNotSubmitted() {
        assertNull(FrequencyParallelBatch.submit(100, 4, i -> i, task -> fail("Unexpected submission")));
        assertNull(FrequencyParallelBatch.submit(4096, 0, i -> i, task -> fail("Unexpected submission")));
    }

    @Test
    void workersCanReadAnUnchangedMaterialSnapshot() throws Exception {
        var states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);

        states.set(1, 2, 3, Blocks.STONE.defaultBlockState());

        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.getStates()).thenReturn(states);

        FrequencyMaterialSectionSnapshot snapshot = FrequencyMaterialSectionSnapshot.capture(section);

        states.set(1, 2, 3, Blocks.GLASS.defaultBlockState());

        ExecutorService executor = Executors.newFixedThreadPool(4);

        try {
            var job = FrequencyParallelBatch.submit(2048, 4, i -> snapshot.cell(1, 2, 3).lossDbPerMeter(), task -> {
                executor.execute(task);
                return true;
            });

            assertNotNull(job);
            await(job);

            for (double loss : job.results()) {
                assertEquals(3.0, loss, 0.0);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}