package com.krimzik.spectra.api;

import com.krimzik.spectra.api.material.FrequencyMaterialConfig;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntToDoubleFunction;

final class FrequencyWorkerPool {
    private final MinecraftServer server;
    private final AtomicInteger nextThreadId = new AtomicInteger(1);
    private ThreadPoolExecutor executor;
    private int configuredThreads;

    FrequencyWorkerPool(MinecraftServer server) {
        this.server = Objects.requireNonNull(server);
    }

    boolean execute(Runnable task) {
        Objects.requireNonNull(task);

        if (!server.isSameThread()) {
            throw new IllegalStateException("Spectra work must be scheduled from the server thread");
        }

        int threads = FrequencyMaterialConfig.workerThreads();
        if (threads == 0) {
            shutdown();
            return false;
        }

        configure(threads);

        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }

    FrequencyParallelBatch.Job submitBatch(int count, IntToDoubleFunction calculation) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Spectra batches must be submitted from the server thread");
        }

        return FrequencyParallelBatch.submit(count, FrequencyMaterialConfig.workerThreads(), calculation, this::execute);
    }

    void shutdown() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
            configuredThreads = 0;
        }
    }

    private void configure(int threads) {
        if (executor == null) {
            int capacity = Math.max(16, Math.min(512, threads * 4));

            executor = new ThreadPoolExecutor(
                    threads,
                    threads,
                    30L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(capacity),
                    task -> {
                        Thread worker = new Thread(task, "Spectra-Worker-" + nextThreadId.getAndIncrement());
                        worker.setDaemon(true);
                        return worker;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );

            executor.allowCoreThreadTimeOut(true);
            configuredThreads = threads;
            return;
        }

        if (threads > configuredThreads) {
            executor.setMaximumPoolSize(threads);
            executor.setCorePoolSize(threads);
        } else if (threads < configuredThreads) {
            executor.setCorePoolSize(threads);
            executor.setMaximumPoolSize(threads);
        }

        configuredThreads = threads;
    }
}
