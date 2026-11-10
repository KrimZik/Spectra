package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class Frequency50kStressTest {
    private static final int COUNT = 50_000;
    private static final double BASE = 915_000_000.0;

    private enum Distribution {
        SHARED, MIXED, DISTINCT
    }

    private static final class TestEmitter implements FrequencyEmitter {
        private final FrequencySignal signal;

        TestEmitter(double hz) {
            signal = new FrequencySignal(hz, 1.0, 0.0, 343.0);
        }

        @Override
        public FrequencySignal signal() {
            return signal;
        }

        @Override
        public Vec3 position() {
            return Vec3.ZERO;
        }

        @Override
        public boolean isEmitting() {
            return true;
        }
    }

    private record TestReceiver(double tunedFrequencyHz, double toleranceHz) implements FrequencyReceiver {
        @Override
        public Vec3 position() {
            return Vec3.ZERO;
        }
    }

    private static final class Counter implements BiConsumer<FrequencyEmitter, FrequencySignal> {
        int count;

        @Override
        public void accept(FrequencyEmitter emitter, FrequencySignal signal) {
            count++;
        }
    }

    @Test
    void benchmark50kRegisteredEmitters() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);

        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;

        if (memory != null && !memory.isThreadAllocatedMemoryEnabled()) {
            memory.setThreadAllocatedMemoryEnabled(true);
        }

        for (Distribution distribution : Distribution.values()) {
            FrequencyEngine engine = new FrequencyEngine(level);
            FrequencyRegistration[] registrations = new FrequencyRegistration[COUNT];

            long beforeBytes = allocatedBytes(memory);
            long start = System.nanoTime();

            for (int i = 0; i < COUNT; i++) {
                double hz = switch (distribution) {
                    case SHARED -> BASE;
                    case MIXED -> BASE + (i & 255);
                    case DISTINCT -> BASE + i;
                };

                registrations[i] = engine.register(new TestEmitter(hz));
            }

            double registrationMs = (System.nanoTime() - start) / 1_000_000.0;

            long afterBytes = allocatedBytes(memory);
            double allocationMiB = beforeBytes < 0 || afterBytes < 0 ? Double.NaN : (afterBytes - beforeBytes) / 1_048_576.0;

            assertEquals(COUNT, engine.emitterCount());
            System.out.printf(Locale.ROOT, "[FREQ-50K] scenario=%s " + "registered=%d registration=%.3f ms " + "buildAllocated=%.3f MiB%n", distribution, COUNT, registrationMs, allocationMiB);

            double narrowHz = switch (distribution) {
                case SHARED -> BASE;
                case MIXED -> BASE + 128;
                case DISTINCT -> BASE + COUNT / 2;
            };

            int narrowMatches = switch (distribution) {
                case SHARED -> COUNT;
                case MIXED -> 195;
                case DISTINCT -> 1;
            };

            TestReceiver narrow = new TestReceiver(narrowHz, 0.0);
            TestReceiver broad = new TestReceiver(BASE + COUNT / 2.0, COUNT / 2.0);

            for (int receivers : new int[]{1, 10, 50}) {
                benchmark(engine, distribution, "NARROW", narrow, narrowMatches, receivers);

                if (distribution != Distribution.SHARED) {
                    benchmark(engine, distribution, "BROAD", broad, COUNT, receivers);
                }
            }

            long removalStart = System.nanoTime();

            for (FrequencyRegistration registration : registrations) {
                registration.close();
            }

            double removalMs = (System.nanoTime() - removalStart) / 1_000_000.0;
            assertEquals(0, engine.emitterCount());

            System.out.printf(Locale.ROOT, "[FREQ-50K] scenario=%s " + "removed=%d removal=%.3f ms " + "remaining=%d%n", distribution, COUNT, removalMs, engine.emitterCount());
        }
    }

    private static long allocatedBytes(com.sun.management.ThreadMXBean memory) {
        return memory == null ? -1L : memory.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private static void benchmark(FrequencyEngine engine, Distribution distribution, String mode, FrequencyReceiver receiver, int expectedMatches, int receivers) {
        Counter counter = new Counter();

        for (int sample = 0; sample < 4; sample++) {
            counter.count = 0;

            for (int r = 0; r < receivers; r++) {
                engine.forEachMatching(receiver, counter);
            }

            assertEquals(expectedMatches * receivers, counter.count);
        }

        long[] durations = new long[7];

        for (int sample = 0; sample < durations.length; sample++) {
            counter.count = 0;
            long start = System.nanoTime();

            for (int r = 0; r < receivers; r++) {
                engine.forEachMatching(receiver, counter);
            }

            durations[sample] = System.nanoTime() - start;
            assertEquals(expectedMatches * receivers, counter.count);
        }

        Arrays.sort(durations);
        double medianTickMs = durations[durations.length / 2] / 1_000_000.0;

        System.out.printf(Locale.ROOT, "[FREQ-50K] scenario=%s mode=%s " + "receivers=%d matchesPerReceiver=%d " + "medianTick=%.3f ms " + "medianPerReceiver=%.3f us%n", distribution, mode, receivers, expectedMatches, medianTickMs, medianTickMs * 1000.0 / receivers);
    }
}
