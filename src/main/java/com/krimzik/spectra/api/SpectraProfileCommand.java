package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.material.FrequencyMaterialAttenuation;
import com.krimzik.spectra.api.material.FrequencySableMaterialAttenuation;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import com.sun.management.ThreadMXBean;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

@EventBusSubscriber(modid = "spectra")
public final class SpectraProfileCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double FREQUENCY = 915_000_000.0;
    private static final double RANGE = 64.0;
    private static volatile double checksum;

    private SpectraProfileCommand() {}

    private record Emitter(Vec3 position, FrequencySignal signal)
            implements FrequencyEmitter {
    }

    private record Receiver(Vec3 position, double tunedFrequencyHz,
                            double toleranceHz) implements FrequencyReceiver {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("spectra_profile")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> profile(context.getSource(), 200))
                        .then(Commands.argument("emitters", IntegerArgumentType.integer(10, 100000))
                                .executes(context -> profile(context.getSource(), IntegerArgumentType.getInteger(context, "emitters"))))
        );
    }

    private static int profile(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyEmitter[] emitters = new FrequencyEmitter[count];
        boolean originalMaterials = FrequencyMaterialAttenuation.enabled();

        FrequencySignal signal = new FrequencySignal(FREQUENCY, 1.0, 0.0, 343.0);

        int nearby = 0;

        try {
            for (int i = 0; i < count; i++) {
                boolean close = i % 10 == 0;
                Vec3 position;

                if (close) {
                    int index = i / 10;
                    position = origin.add(8 + index % 12, 0, 8 + (index / 12) % 12);
                    nearby++;
                } else {
                    position = origin.add(512 + i % 32, 0, 512 + (i / 32) % 32);
                }

                Emitter emitter = new Emitter(position, signal);
                emitters[i] = emitter;
                engine.register(emitter);
            }

            Receiver receiver = new Receiver(origin, FREQUENCY, 0.0);
            Vec3 receiverWorld = FrequencySpace.worldPosition(level, receiver.position());
            Vec3[] materialTargets = new Vec3[count];
            int materialTargetCount = 0;

            for (FrequencyEmitter emitter : emitters) {
                Vec3 emitterWorld = FrequencySpace.worldPosition(level, emitter.position());

                if (emitterWorld.distanceToSqr(receiverWorld) <= RANGE * RANGE) {
                    materialTargets[materialTargetCount++] = emitterWorld;
                }
            }

            Vec3[] paths = Arrays.copyOf(materialTargets, materialTargetCount);

            LOGGER.info("[SPECTRA-PROFILE] matchedMaterialPaths={}", paths.length);

            double[] sink = {0.0};
            ThreadMXBean memory = memoryBean();

            LOGGER.info("[SPECTRA-PROFILE] emitters={} nearby={} materials={}", count, nearby, FrequencyMaterialAttenuation.enabled());

            Runnable lookup = () -> engine.forEachMatching(receiver, (emitter, current) -> sink[0] += 1.0);
            Runnable rawDistance = () -> engine.forEachMatching(receiver, (emitter, current) -> sink[0] += emitter.position().distanceToSqr(receiver.position()));
            Runnable projection = () -> {
                Vec3 center = FrequencySpace.worldPosition(level, receiver.position());

                for (FrequencyEmitter emitter : emitters) {
                    Vec3 world = FrequencySpace.worldPosition(level, emitter.position());
                    sink[0] += world.distanceToSqr(center);
                }
            };

            Runnable spatial = () -> engine.forEachMatchingInRange(receiver, RANGE, (emitter, current, distanceSquared) -> sink[0] += distanceSquared);
            Runnable reception = () -> engine.forEachReceivedInRange(receiver, RANGE, (emitter, current, distanceSquared, amplitude, travelTime) -> sink[0] += amplitude + travelTime);

            measure(count, "LOOKUP", lookup, memory);
            measure(count, "RAW_DISTANCE", rawDistance, memory);
            measure(count, "PROJECTION", projection, memory);
            measure(count, "SPATIAL", spatial, memory);
            measure(count, "RECEPTION", reception, memory);

            Runnable worldMaterials = () -> {
                for (Vec3 emitterWorld : paths) {
                    sink[0] += FrequencyMaterialAttenuation.factor(
                            level, emitterWorld, receiverWorld
                    );
                }
            };

            Runnable sableMaterials = () -> {
                for (Vec3 emitterWorld : paths) {
                    sink[0] += FrequencySableMaterialAttenuation.factor(
                            level, emitterWorld, receiverWorld
                    );
                }
            };

            Runnable sableQueryOnly = () -> {
                for (Vec3 emitterWorld : paths) {
                    BoundingBox3d bounds = new BoundingBox3d(
                            Math.min(emitterWorld.x, receiverWorld.x),
                            Math.min(emitterWorld.y, receiverWorld.y),
                            Math.min(emitterWorld.z, receiverWorld.z),
                            Math.max(emitterWorld.x, receiverWorld.x),
                            Math.max(emitterWorld.y, receiverWorld.y),
                            Math.max(emitterWorld.z, receiverWorld.z)
                    );

                    int matches = 0;

                    for (var sublevel : SableCompanion.INSTANCE.getAllIntersecting(level, bounds)) {
                        matches++;
                    }

                    sink[0] += matches;
                }
            };

            measure(count, "WORLD_MATERIAL_ONLY", worldMaterials, memory);
            measure(count, "SABLE_MATERIAL_ONLY", sableMaterials, memory);
            measure(count, "WORLD_MATERIAL_ONLY", worldMaterials, memory);
            measure(count, "SABLE_MATERIAL_ONLY", sableMaterials, memory);
            measure(count, "SABLE_QUERY_ONLY", sableQueryOnly, memory);
            FrequencyMaterialAttenuation.setEnabled(false);
            measure(count, "RECEPTION_NO_MATERIALS", reception, memory);
            FrequencyMaterialAttenuation.setEnabled(originalMaterials);
            measure(count, "RECEPTION_RESTORED", reception, memory);

            checksum = sink[0];
            source.sendSuccess(() -> Component.literal("Spectra profiling complete. Results in latest.log."), false);

            return 1;
        } finally {
            FrequencyMaterialAttenuation.setEnabled(originalMaterials);
            engine.shutdown();
        }
    }

    private static ThreadMXBean memoryBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
            return null;
        }

        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }

        return bean;
    }

    private static void measure(int count, String stage, Runnable task,
                                ThreadMXBean memory) {
        for (int i = 0; i < 3; i++) {
            task.run();
        }

        double[] samples = new double[5];
        double allocated = 0.0;
        long threadId = Thread.currentThread().threadId();

        for (int i = 0; i < samples.length; i++) {
            long beforeBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

            long start = System.nanoTime();
            task.run();
            long elapsed = System.nanoTime() - start;

            long afterBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

            samples[i] = elapsed / 1000.0;

            if (memory != null) {
                allocated += afterBytes - beforeBytes;
            }
        }

        Arrays.sort(samples);

        double bytesPerQuery = memory == null ? -1.0 : allocated / samples.length;
        LOGGER.info(String.format(Locale.ROOT, "[SPECTRA-PROFILE] emitters=%d stage=%s median=%.3f us/query allocated=%.1f bytes/query", count, stage, samples[samples.length / 2], bytesPerQuery));
    }
}