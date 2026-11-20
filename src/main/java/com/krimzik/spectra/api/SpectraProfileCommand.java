package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.consumers.FrequencyMatchConsumer;
import com.krimzik.spectra.api.consumers.FrequencyReceptionConsumer;
import com.krimzik.spectra.api.material.*;
import com.krimzik.spectra.propagation.WaveMedium;
import com.krimzik.spectra.propagation.geometry.SegmentAabbClip;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import com.sun.management.ThreadMXBean;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
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
    private static ThreadedProfile activeThreadedProfile;

    private static final class ThreadedProfile {
        final ServerLevel level;
        final FrequencyEngine engine;
        final Thread serverThread;
        final int emitters;
        final int receivers;

        long baselineCallbacks;
        long callbacks;
        long immediateCallbacks;
        long syncNanos;
        long submitNanos;
        long completionNanos;
        long startedNanos;
        long startedTick;
        long syncAllocated;
        long submitAllocated;
        int queued;
        boolean offThread;

        ThreadedProfile(ServerLevel level, FrequencyEngine engine, int emitters, int receivers) {
            this.level = level;
            this.engine = engine;
            this.emitters = emitters;
            this.receivers = receivers;
            this.serverThread = Thread.currentThread();
        }
    }

    private SpectraProfileCommand() {}

    private record Emitter(Vec3 position, FrequencySignal signal) implements FrequencyEmitter {
        @Override
        public boolean hasFixedWorldPosition() {
            return true;
        }
    }

    private record Receiver(Vec3 position, double tunedFrequencyHz, double toleranceHz) implements FrequencyReceiver {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("spectra_profile")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> profile(context.getSource(), 200, 10))
                        .then(Commands.argument("emitters", IntegerArgumentType.integer(10, 1000000))
                                .executes(context -> profile(context.getSource(), IntegerArgumentType.getInteger(context, "emitters"), 10))
                                .then(Commands.argument("nearPercent", IntegerArgumentType.integer(0, 100))
                                        .executes(context -> profile(context.getSource(), IntegerArgumentType.getInteger(context, "emitters"), IntegerArgumentType.getInteger(context, "nearPercent")))))
        );
        event.getDispatcher().register(
                Commands.literal("spectra_profile_receivers")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> profileReceivers(context.getSource(), 1000, 16))
                        .then(Commands.argument("emitters", IntegerArgumentType.integer(10, 3000))
                                .executes(context -> profileReceivers(context.getSource(), IntegerArgumentType.getInteger(context, "emitters"), 16))
                                .then(Commands.argument("receivers", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> profileReceivers(context.getSource(), IntegerArgumentType.getInteger(context, "emitters"), IntegerArgumentType.getInteger(context, "receivers")))))
                        .then(Commands.literal("threaded")
                                .executes(context -> profileThreaded(context.getSource(), 1200, 2))
                                .then(Commands.argument("emitters", IntegerArgumentType.integer(1024, 3000))
                                        .executes(context -> profileThreaded(context.getSource(), IntegerArgumentType.getInteger(context, "emitters"), 2))
                                        .then(Commands.argument("receivers", IntegerArgumentType.integer(1, 3000))
                                                .executes(context -> profileThreaded(context.getSource(), IntegerArgumentType.getInteger(context, "emitters"), IntegerArgumentType.getInteger(context, "receivers"))))))
        );
        event.getDispatcher().register(
                Commands.literal("spectra_profile_material_lookup")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> profileMaterialLookups(context.getSource()))
        );
    }

    private static int profile(CommandSourceStack source, int count, int nearPercent) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        FrequencyEngine engine = new FrequencyEngine(level);
        FrequencyEmitter[] emitters = new FrequencyEmitter[count];
        boolean originalMaterials = FrequencyMaterialAttenuation.enabled();

        FrequencySignal signal = new FrequencySignal(FREQUENCY, 1.0, 0.0, 343.0);

        int nearby = 0;

        try {
            for (int i = 0; i < count; i++) {
                boolean close = i % 100 < nearPercent;
                Vec3 position;

                if (close) {
                    int index = (nearby++ * 1097) % 12167;
                    position = origin.add(index % 23 - 11, index / 529 - 11, (index / 23) % 23 - 11);
                } else {
                    position = origin.add(512 + i % 32, 0, 512 + (i / 32) % 32);
                }

                Emitter emitter = new Emitter(position, signal);
                emitters[i] = emitter;
                engine.registerChunkBound(net.minecraft.core.BlockPos.containing(position), emitter);
            }

            Receiver receiver = new Receiver(origin, FREQUENCY, 0.0);
            Vec3 receiverWorld = FrequencySpace.worldPosition(level, receiver.position());

            FrequencySpatialIndex<FrequencyEmitter> index = new FrequencySpatialIndex<>();

            ThreadMXBean memory = memoryBean();
            long threadId = Thread.currentThread().threadId();
            long bytesBefore = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
            long buildStart = System.nanoTime();

            for (FrequencyEmitter emitter : emitters) {
                Vec3 world = FrequencySpace.worldPosition(level, emitter.position());
                index.update(emitter, world.x, world.y, world.z);
            }

            long buildNanos = System.nanoTime() - buildStart;
            long buildBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId) - bytesBefore;

            LOGGER.info(String.format(Locale.ROOT, "[SPECTRA-PROFILE] INDEX_BUILD entries=%d time=%.3f ms allocated=%d B", index.size(), buildNanos / 1_000_000.0, buildBytes));

            Vec3[] materialTargets = new Vec3[count];
            int materialTargetCount = 0;

            for (FrequencyEmitter emitter : emitters) {
                Vec3 emitterWorld = FrequencySpace.worldPosition(level, emitter.position());

                if (emitterWorld.distanceToSqr(receiverWorld) <= RANGE * RANGE) {
                    materialTargets[materialTargetCount++] = emitterWorld;
                }
            }

            Vec3[] paths = Arrays.copyOf(materialTargets, materialTargetCount);
            int[] indexedMatches = {0};
            index.forEachWithin(receiverWorld.x, receiverWorld.y, receiverWorld.z, RANGE, emitter -> indexedMatches[0]++);
            if (indexedMatches[0] != materialTargetCount) {
                throw new IllegalStateException("Spatial index mismatch: expected " + materialTargetCount + ", found " + indexedMatches[0]);
            }

            LOGGER.info("[SPECTRA-PROFILE] matchedMaterialPaths={}", paths.length);

            double[] sink = {0.0};

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
            Runnable indexedSpatial = () -> index.forEachWithin(receiverWorld.x, receiverWorld.y, receiverWorld.z, RANGE, emitter -> {
                if (!emitter.isEmitting()) {
                    return;
                }

                FrequencySignal current = emitter.signal();
                if (current.frequencyHz() != FREQUENCY) {
                    return;
                }

                Vec3 world = FrequencySpace.worldPosition(level, emitter.position());
                double distanceSquared = world.distanceToSqr(receiverWorld);

                if (distanceSquared <= RANGE * RANGE) {
                    sink[0] += distanceSquared;
                }
            });

            Runnable reception = () -> engine.forEachReceivedInRange(receiver, RANGE, (emitter, current, distanceSquared, amplitude, travelTime) -> sink[0] += amplitude + travelTime);

            measure(count, "LOOKUP", lookup, memory);
            measure(count, "RAW_DISTANCE", rawDistance, memory);
            measure(count, "PROJECTION", projection, memory);
            compareSpatialStrategies(count, spatial, indexedSpatial, memory);
            measure(count, "RECEPTION", reception, memory);

            Runnable worldMaterials = () -> {
                for (Vec3 emitterWorld : paths) {
                    sink[0] += FrequencyMaterialAttenuation.factor(level, emitterWorld, receiverWorld);
                }
            };

            Runnable sableMaterials = () -> {
                for (Vec3 emitterWorld : paths) {
                    sink[0] += FrequencySableMaterialAttenuation.factor(level, emitterWorld, receiverWorld);
                }
            };

            MinecraftMaterialPropagation batchedPropagation = new MinecraftMaterialPropagation(level);
            Runnable sableBatchedMaterials = () -> {
                var candidates = batchedPropagation.receiverCandidates(receiverWorld, RANGE);
                if (candidates == null) {
                    return;
                }

                for (Vec3 emitterWorld : paths) {
                    sink[0] += FrequencySableMaterialAttenuation.factorForSublevels(level, emitterWorld, receiverWorld, candidates);
                }
            };

            var allCandidates = batchedPropagation.receiverCandidates(receiverWorld, RANGE);
            Runnable sableSingleCandidate;

            if (allCandidates == null || allCandidates.isEmpty()) {
                sableSingleCandidate = () -> {};
            } else {
                var selected = java.util.List.of(allCandidates.get(0));

                sableSingleCandidate = () -> {
                    for (Vec3 emitterWorld : paths) {
                        sink[0] += FrequencySableMaterialAttenuation.factorForSublevels(level, emitterWorld, receiverWorld, selected);
                    }
                };
            }

            Runnable sableEmptyCandidates = () -> {
                for (Vec3 emitterWorld : paths) {
                    sink[0] += FrequencySableMaterialAttenuation.factorForSublevels(level, emitterWorld, receiverWorld, java.util.List.of());
                }
            };

            Runnable cachedWorldMaterials = () -> {
                for (Vec3 emitterWorld : paths) {
                    sink[0] += batchedPropagation.factorForReception(emitterWorld, receiverWorld, java.util.List.of());
                }
            };

            Runnable sableContainerOnly = () -> {
                for (Vec3 ignored : paths) {
                    SubLevelContainer container = SubLevelContainer.getContainer(level);
                    if (container != null) {
                        sink[0] += container.getLoadedCount();
                    }
                }
            };

            Runnable sableBoundsOnly = () -> {
                SubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container == null || container.getLoadedCount() == 0) {
                    return;
                }

                var sublevels = container.getAllSubLevels();
                for (Vec3 emitterWorld : paths) {
                    double minX = Math.min(emitterWorld.x, receiverWorld.x);
                    double minY = Math.min(emitterWorld.y, receiverWorld.y);
                    double minZ = Math.min(emitterWorld.z, receiverWorld.z);
                    double maxX = Math.max(emitterWorld.x, receiverWorld.x);
                    double maxY = Math.max(emitterWorld.y, receiverWorld.y);
                    double maxZ = Math.max(emitterWorld.z, receiverWorld.z);

                    for (int i = 0; i < sublevels.size(); i++) {
                        var bounds = sublevels.get(i).boundingBox();

                        if (bounds.maxX() < minX || bounds.minX() > maxX || bounds.maxY() < minY || bounds.minY() > maxY || bounds.maxZ() < minZ || bounds.minZ() > maxZ) {
                            continue;
                        }

                        sink[0] += 1.0;
                    }
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
            measure(count, "SABLE_CONTAINER_ONLY", sableContainerOnly, memory);
            measure(count, "SABLE_BOUNDS_ONLY", sableBoundsOnly, memory);
            measure(count, "SABLE_MATERIAL_ONLY", sableMaterials, memory);
            measure(count, "SABLE_BATCHED_MATERIAL_ONLY", sableBatchedMaterials, memory);
            measure(count, "SABLE_SINGLE_CANDIDATE", sableSingleCandidate, memory);
            measure(count, "SABLE_EMPTY_CANDIDATES", sableEmptyCandidates, memory);
            measure(count, "WORLD_MATERIAL_CACHED_NO_SHIPS", cachedWorldMaterials, memory);
            MinecraftWaveMediumResolver waveResolver = new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), java.util.Map.of(FrequencyMaterialClassifier.STONE, new WaveMedium(3000.0, 3.0, 0.0), FrequencyMaterialClassifier.METAL, new WaveMedium(3000.0, 6.0, 0.0)), java.util.List.of());
            Runnable freshWaveCache = () -> {
                MinecraftWorldWavePath wave = new MinecraftWorldWavePath(new MinecraftMaterialPropagation(level), waveResolver);
                for (Vec3 emitterWorld : paths) {
                    sink[0] += wave.evaluate(emitterWorld, receiverWorld, FREQUENCY, true);
                }
            };

            MinecraftWorldWavePath cachedWave = new MinecraftWorldWavePath(batchedPropagation, waveResolver);
            for (Vec3 emitterWorld : paths) {
                sink[0] += cachedWave.evaluate(emitterWorld, receiverWorld, FREQUENCY, true);
            }

            Runnable cachedWavePaths = () -> {
                for (Vec3 emitterWorld : paths) {
                    sink[0] += cachedWave.evaluate(emitterWorld, receiverWorld, FREQUENCY, true);
                }
            };

            measure(count, "WORLD_WAVE_FRESH_CACHE", freshWaveCache, memory);
            measure(count, "WORLD_WAVE_CACHED", cachedWavePaths, memory);
            MinecraftMaterialPropagation combinedPropagation = new MinecraftMaterialPropagation(level);
            MinecraftCombinedWavePath combinedWave = new MinecraftCombinedWavePath(combinedPropagation, waveResolver);

            Runnable combinedWorldWave = () -> {
                for (Vec3 emitterWorld : paths) {
                    double result = combinedWave.evaluate(emitterWorld, receiverWorld, java.util.List.of(), FREQUENCY, true);

                    if (Double.isFinite(result)) {
                        sink[0] += result;
                    }
                }
            };

            measure(count, "WORLD_WAVE_COMBINED_NO_SHIPS", combinedWorldWave, memory);

            var waveCandidates = combinedPropagation.waveReceiverCandidates(receiverWorld, RANGE);
            int intersectingPaths = 0;

            if (!waveCandidates.isEmpty()) {
                SegmentAabbClip.Interval interval = new SegmentAabbClip.Interval();
                for (Vec3 emitterWorld : paths) {
                    for (var ship : waveCandidates) {
                        var bounds = ship.boundingBox();

                        if (SegmentAabbClip.clip(emitterWorld.x, emitterWorld.y, emitterWorld.z, receiverWorld.x, receiverWorld.y, receiverWorld.z, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), interval)) {
                            intersectingPaths++;
                            break;
                        }
                    }
                }
            }

            LOGGER.info("[SPECTRA-PROFILE] COMBINED_WAVE_SHIPS candidates={} intersectingPaths={} totalPaths={}", waveCandidates.size(), intersectingPaths, paths.length);

            if (intersectingPaths > 0) {
                int rejectedPaths = 0;

                for (Vec3 emitterWorld : paths) {
                    double result = combinedWave.evaluate(emitterWorld, receiverWorld, waveCandidates, FREQUENCY, true);

                    if (!Double.isFinite(result)) {
                        rejectedPaths++;
                    }
                }

                LOGGER.info("[SPECTRA-PROFILE] COMBINED_WAVE_SHIPS rejectedPaths={}", rejectedPaths);
                Runnable combinedShipWave = () -> {
                    for (Vec3 emitterWorld : paths) {
                        double result = combinedWave.evaluate(emitterWorld, receiverWorld, waveCandidates, FREQUENCY, true);
                        if (Double.isFinite(result)) {
                            sink[0] += result;
                        }
                    }
                };

                measure(count, "WORLD_SABLE_WAVE_COMBINED", combinedShipWave, memory);
            } else {
                LOGGER.info("[SPECTRA-PROFILE] WORLD_SABLE_WAVE_COMBINED skipped: no intersecting loaded ship");
            }

            LOGGER.info("[SPECTRA-PROFILE] COMBINED_WORLD_CACHE {}", combinedPropagation.worldCacheStats());
            LOGGER.info("[SPECTRA-PROFILE] WAVE_WORLD_CACHE {}", batchedPropagation.worldCacheStats());
            org.slf4j.LoggerFactory.getLogger(SpectraProfileCommand.class).info("[SPECTRA-PROFILE] WORLD_CACHE {}", batchedPropagation.worldCacheStats());
            measure(count, "SABLE_QUERY_ONLY", sableQueryOnly, memory);
            FrequencyMaterialAttenuation.setEnabled(false);
            measure(count, "RECEPTION_ATTENUATION_OFF", reception, memory);
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

    private static int profileThreaded(CommandSourceStack source, int emitterCount, int receiverCount) {
        if (activeThreadedProfile != null) {
            source.sendFailure(Component.literal("A threaded reception profile is already running"));
            return 0;
        }

        ServerLevel level = source.getLevel();
        FrequencyEngine engine = new FrequencyEngine(level);
        engine.enableReceptionPathSampling();
        ThreadedProfile profile = new ThreadedProfile(level, engine, emitterCount, receiverCount);

        try {
            Vec3 origin = source.getPosition();
            FrequencySignal signal = new FrequencySignal(FREQUENCY, 1.0, 0.0, 343.0);

            for (int i = 0; i < emitterCount; i++) {
                Vec3 position = origin.add(i % 21 - 10, (i / 441) % 21 - 10, (i / 21) % 21 - 10);
                engine.registerChunkBound(net.minecraft.core.BlockPos.containing(position), new Emitter(position, signal));
            }

            Receiver[] receivers = new Receiver[receiverCount];

            for (int i = 0; i < receiverCount; i++) {
                double x = receiverCount > 32 ? i % 32 - 15.5 : (i % 8 - 3.5) * 3.0;
                double z = receiverCount > 32 ? i / 32 - 15.5 : (i / 8 - 1.5) * 3.0;

                receivers[i] = new Receiver(origin.add(x, 0.0, z), FREQUENCY, 0.0);
                engine.registerReceiver(receivers[i]);
            }

            for (int warmup = 0; warmup < 2; warmup++) {
                for (Receiver receiver : receivers) {
                    engine.forEachReceivedInRange(receiver, RANGE, (emitter, current, distance, amplitude, travelTime) -> {});
                }
            }

            ThreadMXBean memory = memoryBean();
            long threadId = Thread.currentThread().threadId();

            long beforeSync = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
            long startSync = System.nanoTime();

            for (Receiver receiver : receivers) {
                engine.forEachReceivedInRange(receiver, RANGE, (emitter, current, distance, amplitude, travelTime) -> profile.baselineCallbacks++);
            }

            profile.syncNanos = System.nanoTime() - startSync;
            profile.syncAllocated = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId) - beforeSync;

            long beforeSubmit = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

            profile.startedNanos = System.nanoTime();
            profile.startedTick = level.getGameTime();

            for (Receiver receiver : receivers) {
                engine.scheduleReceptionInRange(receiver, RANGE, (emitter, current, distance, amplitude, travelTime) -> {
                    if (Thread.currentThread() != profile.serverThread) {
                        profile.offThread = true;
                    }

                    profile.callbacks++;
                });
            }

            profile.submitNanos = System.nanoTime() - profile.startedNanos;
            profile.submitAllocated = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId) - beforeSubmit;
            profile.immediateCallbacks = profile.callbacks;
            profile.queued = engine.pendingReceptionBatchCount();

            LOGGER.info("[SPECTRA-PROFILE] THREADED_DECISION reason={} materials={} reflection={} workers={}", engine.lastReceptionDecision(), FrequencyMaterialAttenuation.enabled(), FrequencyMaterialConfig.reflectionEnabled(), FrequencyMaterialConfig.workerThreads());

            if (profile.queued == 0) {
                finishThreadedProfile(profile, false);
            } else {
                activeThreadedProfile = profile;
            }

            source.sendSuccess(() -> Component.literal("Threaded reception profiling started. Results in latest.log."), false);

            return 1;
        } catch (RuntimeException | Error exception) {
            if (activeThreadedProfile == profile) {
                activeThreadedProfile = null;
            }

            engine.shutdown();
            throw exception;
        }
    }

    @SubscribeEvent
    public static void onThreadedProfileTick(LevelTickEvent.Post event) {
        ThreadedProfile profile = activeThreadedProfile;

        if (profile == null || event.getLevel() != profile.level) {
            return;
        }

        long start = System.nanoTime();

        try {
            profile.engine.processCompletedReceptionBatches();
            profile.completionNanos += System.nanoTime() - start;

            if (profile.engine.pendingReceptionBatchCount() == 0) {
                finishThreadedProfile(profile, false);
            } else if (profile.level.getGameTime() - profile.startedTick >= 600) {
                finishThreadedProfile(profile, true);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Threaded reception profiling failed", exception);
            activeThreadedProfile = null;
            profile.engine.shutdown();
        }
    }

    private static void finishThreadedProfile(ThreadedProfile profile, boolean timedOut) {
        if (activeThreadedProfile == profile) {
            activeThreadedProfile = null;
        }

        try {
            double syncMs = profile.syncNanos / 1_000_000.0;
            double submitMs = profile.submitNanos / 1_000_000.0;
            double completionMs = profile.completionNanos / 1_000_000.0;
            double mainMs = submitMs + completionMs;
            double wallMs = (System.nanoTime() - profile.startedNanos) / 1_000_000.0;

            LOGGER.info(String.format(Locale.ROOT, "[SPECTRA-PROFILE] THREADED_RECEPTION status=%s emitters=%d receivers=%d threads=%d queued=%d immediateCallbacks=%d expectedCallbacks=%d actualCallbacks=%d offThread=%s syncMs=%.3f submitMainMs=%.3f completionMainMs=%.3f totalMainMs=%.3f wallMs=%.3f syncAllocated=%d B submitAllocated=%d B", timedOut ? "TIMEOUT" : "COMPLETE", profile.emitters, profile.receivers, FrequencyMaterialConfig.workerThreads(), profile.queued, profile.immediateCallbacks, profile.baselineCallbacks, profile.callbacks, profile.offThread, syncMs, submitMs, completionMs, mainMs, wallMs, profile.syncAllocated, profile.submitAllocated));
            LOGGER.info("[SPECTRA-PROFILE] SHARED_RECEPTION submittedBatches={} inFlightBatches={} peakInFlightBatches={} outstandingRequests={}", profile.engine.submittedReceptionBatchCount(), profile.engine.inFlightReceptionBatchCount(), profile.engine.peakInFlightReceptionBatchCount(), profile.engine.pendingReceptionBatchCount());

            var stages = profile.engine.receptionTimings();
            LOGGER.info(String.format(Locale.ROOT, "[SPECTRA-PROFILE] RECEPTION_STAGES prepareMs=%.3f snapshotMs=%.3f validationMs=%.3f deliveryMs=%.3f synchronousMs=%.3f workerThreadRunMs=%.3f p95ActiveTickMs=%.3f maxActiveTickMs=%.3f activeTicks=%d adaptiveSyncRequests=%d materialValidationMs=%.3f emitterValidationMs=%.3f", stages.prepareMs(), stages.snapshotMs(), stages.validationMs(), stages.deliveryMs(), stages.synchronousMs(), stages.workerThreadRunMs(), stages.p95ActiveTickMs(), stages.maxActiveTickMs(), stages.activeTicks(), stages.adaptiveSyncRequests(), stages.materialValidationMs(), stages.emitterValidationMs()));
            LOGGER.info(String.format(Locale.ROOT, "[SPECTRA-PROFILE] SNAPSHOT_CAPTURE pathsMs=%.3f worldMs=%.3f otherMs=%.3f", stages.snapshotPathsMs(), stages.snapshotWorldMs(), Math.max(0.0, stages.snapshotMs() - stages.snapshotPathsMs() - stages.snapshotWorldMs())));

            var dense = profile.engine.denseReceptionDiagnostics();
            LOGGER.info("[SPECTRA-PROFILE] DENSE_SNAPSHOT eligibleBatches={} blockedLive={} blockedPartial={} blockedShips={} blockedRevision={} fastReceivers={} fastPaths={} boundsMs={} inputsMs={}", dense.eligibleBatches(), dense.blockedLive(), dense.blockedPartial(), dense.blockedShips(), dense.blockedRevision(), dense.fastReceivers(), dense.fastPaths(), String.format(Locale.ROOT, "%.3f", dense.boundsMs()), String.format(Locale.ROOT, "%.3f", dense.inputsMs()));
            LOGGER.info("[SPECTRA-PROFILE] SNAPSHOT_PATH_SAMPLES {}", profile.engine.receptionPathSamplesSummary());
            LOGGER.info("[SPECTRA-PROFILE] WORKER_SAMPLES {}", profile.engine.receptionWorkerSamplesSummary());
            LOGGER.info("[SPECTRA-PROFILE] WORKER_PIPELINE {}", profile.engine.receptionWorkerPipelineSummary());

            var scheduler = profile.engine.receptionSchedulerDiagnostics();
            LOGGER.info(String.format(Locale.ROOT, "[SPECTRA-PROFILE] RECEPTION_SCHEDULER headBlockedTicks=%d readyBehindHeadTicks=%d peakReadyBehindHead=%d overBudgetTicks=%d worstTickPrepareMs=%.3f worstTickSnapshotMs=%.3f worstTickValidationMs=%.3f worstTickDeliveryMs=%.3f worstTickSyncMs=%.3f worstTickOtherMs=%.3f", scheduler.headBlockedTicks(), scheduler.readyBehindHeadTicks(), scheduler.peakReadyBehindHead(), scheduler.overBudgetTicks(), scheduler.worstTickPrepareMs(), scheduler.worstTickSnapshotMs(), scheduler.worstTickValidationMs(), scheduler.worstTickDeliveryMs(), scheduler.worstTickSyncMs(), scheduler.worstTickOtherMs()));

            if (timedOut || profile.callbacks != profile.baselineCallbacks || profile.offThread) {
                LOGGER.warn("[SPECTRA-PROFILE] Threaded reception did not match the synchronous callback count or thread requirements");
            }
        } finally {
            profile.engine.shutdown();
        }
    }

    @SubscribeEvent
    public static void onThreadedProfileLevelUnload(LevelEvent.Unload event) {
        ThreadedProfile profile = activeThreadedProfile;

        if (profile != null && event.getLevel() == profile.level) {
            activeThreadedProfile = null;
            profile.engine.shutdown();
        }
    }

    @SubscribeEvent
    public static void onThreadedProfileServerStopped(ServerStoppedEvent event) {
        ThreadedProfile profile = activeThreadedProfile;

        if (profile != null && profile.level.getServer() == event.getServer()) {
            activeThreadedProfile = null;
            profile.engine.shutdown();
        }
    }

    private static int profileReceivers(CommandSourceStack source, int emitterCount, int receiverCount) {
        ServerLevel level = source.getLevel();
        FrequencyEngine engine = new FrequencyEngine(level);
        double[] sink = {0.0};

        try {
            Vec3 origin = source.getPosition();
            FrequencySignal signal = new FrequencySignal(FREQUENCY, 1.0, 0.0, 343.0);

            Vec3[] emitterPositions = new Vec3[emitterCount];
            for (int i = 0; i < emitterCount; i++) {
                Vec3 position = origin.add(i % 21 - 10, (i / 441) % 21 - 10, (i / 21) % 21 - 10);
                engine.registerChunkBound(net.minecraft.core.BlockPos.containing(position), new Emitter(position, signal));
                emitterPositions[i] = position;
            }

            int samples = receiverCount > 32 ? 3 : 5;
            Receiver[] stationary = new Receiver[receiverCount];
            Receiver[][] stationaryFrames = new Receiver[samples][];
            Receiver[][] movingFrames = new Receiver[samples][receiverCount];

            for (int i = 0; i < receiverCount; i++) {
                double x = receiverCount > 32 ? i % 32 - 15.5 : (i % 8 - 3.5) * 3.0;
                double z = receiverCount > 32 ? i / 32 - 15.5 : (i / 8 - 1.5) * 3.0;

                stationary[i] = new Receiver(origin.add(x, 0.0, z), FREQUENCY, 0.0);
                for (int frame = 0; frame < movingFrames.length; frame++) {
                    movingFrames[frame][i] = new Receiver(origin.add(x + (frame + 1) * 0.25, 0.0, z + (frame + 1) * 0.125), FREQUENCY, 0.0);
                }
            }

            Arrays.fill(stationaryFrames, stationary);
            FrequencyMatchConsumer matching = (emitter, current, distanceSquared) -> sink[0] += distanceSquared;
            FrequencyReceptionConsumer reception = (emitter, current, distanceSquared, amplitude, travelTime) -> sink[0] += amplitude + travelTime;
            ThreadMXBean memory = memoryBean();

            LOGGER.info("[SPECTRA-PROFILE] MULTI_RECEIVER_SETUP emitters={} receivers={} pairsPerSweep={} range={}", emitterCount, receiverCount, (long) emitterCount * receiverCount, RANGE);
            measureReceiverSweeps("MULTI_RECEIVER_SPATIAL", engine, stationaryFrames, matching, null, memory);

            for (int warmup = 0; warmup < 2; warmup++) {
                for (Receiver receiver : stationary) {
                    engine.forEachReceivedInRange(receiver, RANGE, reception);
                }
            }

            measureReceiverSweeps("MULTI_RECEIVER_STATIC", engine, stationaryFrames, null, reception, memory);
            measureReceiverSweeps("MULTI_RECEIVER_MOVING", engine, movingFrames, null, reception, memory);

            int diagnosticReceivers = Math.min(receiverCount, 128);
            FrequencyMaterialAttenuation.MaterialSegmentConsumer discard = (material, from, to, axes) -> {};
            MinecraftCombinedWavePath diagnosticWave = new MinecraftCombinedWavePath(new MinecraftMaterialPropagation(level), new MinecraftWaveMediumResolver(new WaveMedium(343.0, 1.0, 0.0), java.util.Map.of(FrequencyMaterialClassifier.STONE, new WaveMedium(3000.0, 3.0, 0.0), FrequencyMaterialClassifier.METAL, new WaveMedium(3000.0, 6.0, 0.0)), java.util.List.of()));

            Runnable rawWorld = () -> {
                for (int i = 0; i < diagnosticReceivers; i++) {
                    Vec3 end = stationary[i].position();

                    for (Vec3 start : emitterPositions) {
                        double result = FrequencyMaterialAttenuation.factorWithMaterials(level, start, end, discard);
                        if (Double.isFinite(result)) {
                            sink[0] += result;
                        }
                    }
                }
            };

            MinecraftMaterialPropagation diagnosticBatch = new MinecraftMaterialPropagation(level);
            Runnable rawWorldBatched = () -> {
                for (int i = 0; i < diagnosticReceivers; i++) {
                    Vec3 end = stationary[i].position();

                    diagnosticBatch.beginReceptionChunkBatch();
                    try {
                        for (Vec3 start : emitterPositions) {
                            double result = FrequencyMaterialAttenuation.factorWithMaterials(level, start, end, discard);
                            if (Double.isFinite(result)) {
                                sink[0] += result;
                            }
                        }
                    } finally {
                        diagnosticBatch.endReceptionChunkBatch();
                    }
                }
            };

            Runnable rawFactorOnly = () -> {
                for (int i = 0; i < diagnosticReceivers; i++) {
                    Vec3 end = stationary[i].position();

                    for (Vec3 start : emitterPositions) {
                        double result = FrequencyMaterialAttenuation.factor(level, start, end);
                        if (Double.isFinite(result)) {
                            sink[0] += result;
                        }
                    }
                }
            };

            var chunkSource = level.getChunkSource();

            Runnable chunkLookupOnly = () -> {
                for (int i = 0; i < diagnosticReceivers; i++) {
                    for (Vec3 start : emitterPositions) {
                        int chunkX = ((int) Math.floor(start.x)) >> 4;
                        int chunkZ = ((int) Math.floor(start.z)) >> 4;

                        var chunk = chunkSource.getChunkNow(chunkX, chunkZ);
                        if (chunk != null) {
                            sink[0] += chunk.getPos().x;
                        }
                    }
                }
            };

            Runnable cachedWorld = () -> {
                for (int i = 0; i < diagnosticReceivers; i++) {
                    Vec3 end = stationary[i].position();

                    for (Vec3 start : emitterPositions) {
                        double result = diagnosticWave.evaluate(start, end, java.util.List.of(), FREQUENCY, true);
                        if (Double.isFinite(result)) {
                            sink[0] += result;
                        }
                    }
                }
            };

            Runnable fullReception = () -> {
                for (int i = 0; i < diagnosticReceivers; i++) {
                    engine.forEachReceivedInRange(stationary[i], RANGE, reception);
                }
            };

            measurePathDiagnostic("RAW_WORLD_PATH", rawWorld, diagnosticReceivers, emitterCount, memory);
            measurePathDiagnostic("RAW_WORLD_BATCHED", rawWorldBatched, diagnosticReceivers, emitterCount, memory);
            measurePathDiagnostic("CHUNK_LOOKUP_ONLY", chunkLookupOnly, diagnosticReceivers, emitterCount, memory);
            measurePathDiagnostic("RAW_FACTOR_ONLY", rawFactorOnly, diagnosticReceivers, emitterCount, memory);
            measurePathDiagnostic("CACHED_WORLD_WAVE", cachedWorld, diagnosticReceivers, emitterCount, memory);
            measurePathDiagnostic("FULL_RECEPTION_SAMPLE", fullReception, diagnosticReceivers, emitterCount, memory);

            profileTraversal("RAW_FACTOR_ONLY", rawFactorOnly);
            profileTraversal("RAW_WORLD_BATCHED", rawWorldBatched);
            profileTraversal("FULL_RECEPTION_SAMPLE", fullReception);

            checksum = sink[0];
            source.sendSuccess(() -> Component.literal("Multi-receiver profiling complete. Results in latest.log."), false);

            return 1;
        } finally {
            engine.shutdown();
        }
    }

    private static void measureReceiverSweeps(String stage, FrequencyEngine engine, Receiver[][] frames, FrequencyMatchConsumer matching, FrequencyReceptionConsumer reception, ThreadMXBean memory) {
        double[] times = new double[frames.length];
        double[] allocations = new double[frames.length];
        long threadId = Thread.currentThread().threadId();
        var cacheBefore = engine.materialCacheStats();

        for (int sample = 0; sample < frames.length; sample++) {
            long beforeBytes = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();

            for (Receiver receiver : frames[sample]) {
                if (reception != null) {
                    engine.forEachReceivedInRange(receiver, RANGE, reception);
                } else {
                    engine.forEachMatchingInRange(receiver, RANGE, matching);
                }
            }

            times[sample] = (System.nanoTime() - start) / 1_000_000.0;
            allocations[sample] = memory == null ? -1.0 : memory.getThreadAllocatedBytes(threadId) - beforeBytes;
        }

        Arrays.sort(times);
        Arrays.sort(allocations);

        LOGGER.info(String.format(Locale.ROOT, "[SPECTRA-PROFILE] stage=%s receivers=%d median=%.3f ms/sweep allocated=%.1f B/sweep", stage, frames[0].length, times[times.length / 2], allocations[allocations.length / 2]));
        var cacheAfter = engine.materialCacheStats();
        LOGGER.info("[SPECTRA-PROFILE] stage={} cacheHits={} coldMisses={} traceBuilds={} evictions={} entries={}", stage, cacheAfter.cacheHits() - cacheBefore.cacheHits(), cacheAfter.coldMisses() - cacheBefore.coldMisses(), cacheAfter.traceBuilds() - cacheBefore.traceBuilds(), cacheAfter.evictions() - cacheBefore.evictions(), cacheAfter.entries());
    }

    private static void profileTraversal(String stage, Runnable task) {
        FrequencyMaterialAttenuation.TraversalStats stats = FrequencyMaterialAttenuation.beginTraversalStats();

        try {
            task.run();
        } finally {
            FrequencyMaterialAttenuation.endTraversalStats();
        }

        LOGGER.info("[SPECTRA-PROFILE] stage={} traversal={}", stage, stats);
    }

    private static void measurePathDiagnostic(String stage, Runnable task, int receivers, int emitters, ThreadMXBean memory) {
        double[] times = new double[3];
        double[] allocations = new double[3];
        long threadId = Thread.currentThread().threadId();

        task.run();

        for (int i = 0; i < times.length; i++) {
            long before = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();

            task.run();

            times[i] = (System.nanoTime() - start) / 1_000_000.0;
            allocations[i] = memory == null ? -1.0 : memory.getThreadAllocatedBytes(threadId) - before;
        }

        Arrays.sort(times);
        Arrays.sort(allocations);

        LOGGER.info(String.format(Locale.ROOT, "[SPECTRA-PROFILE] stage=%s paths=%d median=%.3f ms allocated=%.1f B", stage, (long) receivers * emitters, times[1], allocations[1]));
    }

    private static int profileMaterialLookups(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();

        BlockState[] states = new BlockState[256];
        int centerX = (int) Math.floor(origin.x);
        int centerY = (int) Math.floor(origin.y);
        int centerZ = (int) Math.floor(origin.z);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        int loaded = 0;

        for (int i = 0; i < states.length; i++) {
            int x = centerX + ((i & 15) << 1) - 15;
            int z = centerZ + ((i >> 4) << 1) - 15;
            int y = Math.max(minY, Math.min(maxY - 1, centerY + ((i * 7) & 15) - 8));

            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            BlockState state = Blocks.AIR.defaultBlockState();

            if (chunk != null) {
                LevelChunkSection[] sections = chunk.getSections();
                int index = (y >> 4) - (minY >> 4);

                if (index >= 0 && index < sections.length && sections[index] != null) {
                    state = sections[index].getBlockState(x & 15, y & 15, z & 15);
                    loaded++;
                }
            }

            states[i] = state;
        }

        int capacity = Block.BLOCK_STATE_REGISTRY.size();
        double[] losses = new double[capacity];
        ResourceLocation[] materials = new ResourceLocation[capacity];

        java.util.Set<BlockState> distinct = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlockState state : states) {
            int id = Block.BLOCK_STATE_REGISTRY.getId(state);

            if (id < 0 || id >= capacity) {
                throw new IllegalStateException("Unregistered block state");
            }

            losses[id] = FrequencyMaterialRules.decibelsPerMeter(state);
            materials[id] = FrequencyMaterialClassifier.classify(state);
            distinct.add(state);
        }

        final int iterations = 1_000_000;

        Runnable[] tasks = {
                () -> {
                    double sum = 0.0;
                    for (int i = 0; i < iterations; i++) {
                        sum += FrequencyMaterialRules.decibelsPerMeter(states[i & 255]);
                    }
                    checksum = sum;
                },
                () -> {
                    double sum = 0.0;
                    for (int i = 0; i < iterations; i++) {
                        int id = Block.BLOCK_STATE_REGISTRY.getId(states[i & 255]);
                        sum += losses[id];
                    }
                    checksum = sum;
                },
                () -> {
                    int count = 0;
                    for (int i = 0; i < iterations; i++) {
                        if (FrequencyMaterialClassifier.classify(states[i & 255]) == FrequencyMaterialClassifier.AIR) {
                            count++;
                        }
                    }
                    checksum = count;
                },
                () -> {
                    int count = 0;
                    for (int i = 0; i < iterations; i++) {
                        int id = Block.BLOCK_STATE_REGISTRY.getId(states[i & 255]);
                        if (materials[id] == FrequencyMaterialClassifier.AIR) {
                            count++;
                        }
                    }
                    checksum = count;
                }
        };

        String[] names = {"MATERIAL_HASH_LOSS", "MATERIAL_ARRAY_LOSS", "MATERIAL_HASH_CLASSIFY", "MATERIAL_ARRAY_CLASSIFY"};
        for (int i = 0; i < 32; i++) {
            tasks[i & 3].run();
        }

        double[][] times = new double[tasks.length][7];

        for (int sample = 0; sample < 7; sample++) {
            for (int turn = 0; turn < tasks.length; turn++) {
                int mode = (sample + turn) & 3;
                long start = System.nanoTime();
                tasks[mode].run();
                times[mode][sample] = (System.nanoTime() - start) / (double) iterations;
            }
        }

        LOGGER.info("[SPECTRA-PROFILE] MATERIAL_LOOKUP samples={} loaded={} distinctStates={}", states.length, loaded, distinct.size());

        for (int mode = 0; mode < tasks.length; mode++) {
            Arrays.sort(times[mode]);
            LOGGER.info("[SPECTRA-PROFILE] stage={} median={} ns/lookup", names[mode], String.format(Locale.ROOT, "%.3f", times[mode][3]));
        }

        source.sendSuccess(() -> Component.literal("Material lookup profiling complete. Results in latest.log."), false);

        return 1;
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

    private static void compareSpatialStrategies(int count, Runnable spatial, Runnable indexedSpatial, ThreadMXBean memory) {
        Runnable[] tasks = {spatial, indexedSpatial};
        String[] names = {"SPATIAL_PAIRED", "SPATIAL_INDEX_SNAPSHOT_PAIRED"};

        int samples = 9;
        int repetitions = 32;
        double[][] times = new double[2][samples];
        double[][] allocations = new double[2][samples];
        long threadId = Thread.currentThread().threadId();

        for (int i = 0; i < 160; i++) {
            tasks[i & 1].run();
        }

        for (int sample = 0; sample < samples; sample++) {
            for (int turn = 0; turn < 2; turn++) {
                int mode = (sample + turn) & 1;
                Runnable task = tasks[mode];

                long before = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);
                long start = System.nanoTime();

                for (int i = 0; i < repetitions; i++) {
                    task.run();
                }

                long elapsed = System.nanoTime() - start;
                long after = memory == null ? -1L : memory.getThreadAllocatedBytes(threadId);

                times[mode][sample] = elapsed / (repetitions * 1000.0);
                allocations[mode][sample] = memory == null ? -1.0 : (after - before) / (double) repetitions;
            }
        }

        for (int mode = 0; mode < tasks.length; mode++) {
            Arrays.sort(times[mode]);
            Arrays.sort(allocations[mode]);

            LOGGER.info(String.format(Locale.ROOT, "[SPECTRA-PROFILE] emitters=%d stage=%s median=%.3f us/query allocated=%.1f bytes/query", count, names[mode], times[mode][samples / 2], allocations[mode][samples / 2]));
        }
    }

    private static void measure(int count, String stage, Runnable task, ThreadMXBean memory) {
        for (int i = 0; i < 20; i++) {
            task.run();
        }

        double[] samples = new double[9];
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