package com.krimzik.spectra.api;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntToDoubleFunction;
import java.util.function.Predicate;

final class FrequencyParallelBatch {
    private static final int MIN_PARALLEL_ITEMS = 1024;
    private static final int CLAIM_SIZE = 64;
    private static final int ITEMS_PER_WORKER = 512;

    private FrequencyParallelBatch() {}

    static Job submit(int count, int maxWorkers, IntToDoubleFunction calculation, Predicate<Runnable> submitter) {
        Objects.requireNonNull(calculation);
        Objects.requireNonNull(submitter);

        if (count < MIN_PARALLEL_ITEMS || maxWorkers <= 0) {
            return null;
        }

        Job job = new Job(count, calculation);
        int workers = Math.min(maxWorkers, 1 + (count - 1) / ITEMS_PER_WORKER);
        int accepted = 0;

        try {
            for (int i = 0; i < workers; i++) {
                job.remaining.incrementAndGet();

                boolean scheduled;
                try {
                    scheduled = submitter.test(job::run);
                } catch (RejectedExecutionException exception) {
                    scheduled = false;
                } catch (RuntimeException exception) {
                    job.remaining.decrementAndGet();
                    throw exception;
                }

                if (scheduled) {
                    accepted++;
                } else {
                    job.remaining.decrementAndGet();
                }
            }
        } catch (RuntimeException exception) {
            job.cancel();
            job.finishScheduling();
            throw exception;
        }

        job.finishScheduling();
        return accepted == 0 ? null : job;
    }

    static final class Job {
        private final double[] results;
        private final IntToDoubleFunction calculation;
        private final AtomicInteger next = new AtomicInteger();
        private final AtomicInteger remaining = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicLong workerRunNanos = new AtomicLong();

        private volatile boolean schedulingFinished;
        private volatile boolean complete;
        private volatile boolean cancelled;

        private Job(int count, IntToDoubleFunction calculation) {
            this.results = new double[count];
            this.calculation = calculation;
        }

        private void run() {
            long started = System.nanoTime();

            try {
                while (!cancelled && failure.get() == null && !Thread.currentThread().isInterrupted()) {
                    int start = next.getAndAdd(CLAIM_SIZE);
                    if (start >= results.length) {
                        break;
                    }

                    int end = Math.min(results.length, start + CLAIM_SIZE);

                    for (int i = start; i < end; i++) {
                        if (cancelled || Thread.currentThread().isInterrupted()) {
                            break;
                        }

                        results[i] = calculation.applyAsDouble(i);
                    }
                }
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            } finally {
                workerRunNanos.addAndGet(System.nanoTime() - started);

                if (remaining.decrementAndGet() == 0 && schedulingFinished) {
                    complete = true;
                }
            }
        }

        private void finishScheduling() {
            schedulingFinished = true;

            if (remaining.get() == 0) {
                complete = true;
            }
        }

        boolean complete() {
            return complete;
        }

        long workerRunNanos() {
            return workerRunNanos.get();
        }

        double[] results() {
            if (!complete) {
                throw new IllegalStateException("Parallel batch is still running");
            }

            Throwable exception = failure.get();
            if (exception != null) {
                throw new IllegalStateException("Parallel batch failed", exception);
            }

            if (cancelled) {
                throw new IllegalStateException("Parallel batch was cancelled");
            }

            return results;
        }

        void cancel() {
            cancelled = true;
        }
    }
}