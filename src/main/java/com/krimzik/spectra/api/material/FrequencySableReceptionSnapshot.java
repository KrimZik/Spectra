package com.krimzik.spectra.api.material;

import com.krimzik.spectra.math.MaterialAttenuationMath;
import com.krimzik.spectra.propagation.geometry.SegmentAabbClip;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class FrequencySableReceptionSnapshot {
    public static final class CaptureTimings {
        private long pathsNanos;
        private long worldNanos;
        private long fastReceivers;
        private long fastPaths;
        private int shipBlockedReceivers;

        public long fastReceivers() {
            return fastReceivers;
        }

        public long fastPaths() {
            return fastPaths;
        }
        public long pathsNanos() {
            return pathsNanos;
        }

        public long worldNanos() {
            return worldNanos;
        }

        public int shipBlockedReceivers() {
            return shipBlockedReceivers;
        }
    }

    private static final class Coverage {
        private static final int MAX_SPARSE_RAYS = 32;
        private record Ray(Vec3 start, Vec3 end) {}
        private final List<Ray> rays = new ArrayList<>();
        private long estimatedCorridorChunks;
        private boolean dense;

        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        void include(Vec3 point) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);

            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }

        void include(Vec3 start, Vec3 end) {
            include(start);
            include(end);

            if (dense) {
                return;
            }

            if (rays.size() >= MAX_SPARSE_RAYS) {
                rays.clear();
                dense = true;
                return;
            }

            rays.add(new Ray(start, end));

            int startX = ((int) Math.floor(start.x)) >> 4;
            int startZ = ((int) Math.floor(start.z)) >> 4;
            int endX = ((int) Math.floor(end.x)) >> 4;
            int endZ = ((int) Math.floor(end.z)) >> 4;

            long stepsX = Math.abs((long) endX - startX);
            long stepsZ = Math.abs((long) endZ - startZ);

            estimatedCorridorChunks += 4L * (stepsX + stepsZ + 1L);
        }

        void includeDenseBounds(Vec3 min, Vec3 max) {
            minX = Math.min(minX, min.x);
            minY = Math.min(minY, min.y);
            minZ = Math.min(minZ, min.z);

            maxX = Math.max(maxX, max.x);
            maxY = Math.max(maxY, max.y);
            maxZ = Math.max(maxZ, max.z);

            dense = true;
            rays.clear();
        }

        void capture(FrequencyMaterialWorldSnapshot.Builder builder) {
            if (minX == Double.POSITIVE_INFINITY) {
                return;
            }

            int minChunkX = ((int) Math.floor(minX) - 1) >> 4;
            int maxChunkX = ((int) Math.floor(maxX) + 1) >> 4;
            int minChunkZ = ((int) Math.floor(minZ) - 1) >> 4;
            int maxChunkZ = ((int) Math.floor(maxZ) + 1) >> 4;

            long area = ((long) maxChunkX - minChunkX + 1L) * ((long) maxChunkZ - minChunkZ + 1L);

            if (!dense && !rays.isEmpty() && estimatedCorridorChunks < area) {
                for (Ray ray : rays) {
                    builder.includePath(ray.start(), ray.end());
                }
            } else {
                builder.includeBounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
            }
        }
    }

    public record BatchInput(Vec3 receiver, List<Vec3> emitters, List<? extends SubLevelAccess> candidates) {
        public BatchInput {
            Objects.requireNonNull(receiver);
            emitters = List.copyOf(emitters);
            candidates = List.copyOf(candidates);
        }
    }

    private record CapturedPaths(Vec3 receiver, List<Ship> ships, List<List<Span>> paths, List<Vec3> emitters) {}
    private record DeferredCapture(Vec3 receiver, List<Ship> ships, List<Vec3> emitters, boolean deferred) {}

    private record Ship(SubLevelAccess source, Pose3d pose, BoundingBox3d bounds) {
        boolean current() {
            Pose3dc now = source.logicalPose();
            BoundingBox3dc box = source.boundingBox();

            return now != null && box != null
                    && pose.position().equals(now.position())
                    && pose.orientation().equals(now.orientation())
                    && pose.rotationPoint().equals(now.rotationPoint())
                    && pose.scale().equals(now.scale())
                    && bounds.minX() == box.minX() && bounds.minY() == box.minY() && bounds.minZ() == box.minZ()
                    && bounds.maxX() == box.maxX() && bounds.maxY() == box.maxY() && bounds.maxZ() == box.maxZ();
        }
    }

    private record Span(Vec3 from, Vec3 to, double enter, double exit, double worldMeters, double localMeters, boolean scaled) {}
    private static List<Span> captureSpans(Vec3 start, Vec3 receiver, List<Ship> ships, SegmentAabbClip.Interval interval, IdentityHashMap<Ship, Coverage> shipCoverage) {
        double dx = receiver.x - start.x;
        double dy = receiver.y - start.y;
        double dz = receiver.z - start.z;

        double distance = 0.0;
        List<Span> spans = null;

        for (Ship ship : ships) {
            BoundingBox3d bounds = ship.bounds();

            if (!SegmentAabbClip.clip(start.x, start.y, start.z, receiver.x, receiver.y, receiver.z, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), interval)) {
                continue;
            }

            double enter = interval.enter();
            double exit = interval.exit();

            Vec3 clippedStart = new Vec3(Math.fma(dx, enter, start.x), Math.fma(dy, enter, start.y), Math.fma(dz, enter, start.z));
            Vec3 clippedEnd = new Vec3(Math.fma(dx, exit, start.x), Math.fma(dy, exit, start.y), Math.fma(dz, exit, start.z));

            Pose3d pose = ship.pose();
            Vec3 localStart = pose.transformPositionInverse(clippedStart);
            Vec3 localEnd = pose.transformPositionInverse(clippedEnd);

            if (shipCoverage != null) {
                shipCoverage.computeIfAbsent(ship, ignored -> new Coverage()).include(localStart, localEnd);
            }

            var scale = pose.scale();
            boolean scaled = scale.x() != 1.0 || scale.y() != 1.0 || scale.z() != 1.0;

            if (spans == null) {
                spans = new ArrayList<>();
                distance = start.distanceTo(receiver);
            }

            spans.add(new Span(localStart, localEnd, enter, exit, distance * (exit - enter), localStart.distanceTo(localEnd), scaled));
        }

        return spans == null ? List.of() : List.copyOf(spans);
    }

    public record WorkerSample(double factor, boolean shipHit, long totalNanos, long worldNanos, long shipNanos, long waveNanos) {}

    static final class SampleTimings {
        long worldNanos;
        long shipNanos;
        long waveNanos;
    }

    private static final class OverlayWorkspace implements FrequencyMaterialAttenuation.MaterialSegmentConsumer {
        final MaterialPathOverlay overlay = new MaterialPathOverlay();
        double enter;
        double span;

        @Override
        public void accept(ResourceLocation material, double from, double to, int axes) {
            throw new IllegalStateException("Missing exact material attenuation");
        }

        @Override
        public void acceptWithLoss(ResourceLocation material, double from, double to, int axes, double decibelsPerMeter) {
            overlay.addSnapshotShip(material, Math.fma(from, span, enter), Math.fma(to, span, enter), decibelsPerMeter);
        }
    }

    private static final ThreadLocal<OverlayWorkspace> OVERLAYS = ThreadLocal.withInitial(OverlayWorkspace::new);
    private final FrequencyMaterialWorldSnapshot world;
    private final Vec3 receiver;
    private final List<Ship> ships;
    private final List<List<Span>> paths;
    private final List<Vec3> emitters;
    private final AtomicReferenceArray<List<Span>> deferredPaths;

    private FrequencySableReceptionSnapshot(FrequencyMaterialWorldSnapshot world, Vec3 receiver, List<Ship> ships, List<List<Span>> paths, List<Vec3> emitters) {
        this(world, receiver, ships, paths, emitters, false);
    }

    private FrequencySableReceptionSnapshot(FrequencyMaterialWorldSnapshot world, Vec3 receiver, List<Ship> ships, List<List<Span>> paths, List<Vec3> emitters, boolean deferred) {
        this.world = world;
        this.receiver = receiver;
        this.ships = List.copyOf(ships);
        this.paths = List.copyOf(paths);
        this.emitters = List.copyOf(emitters);
        this.deferredPaths = deferred ? new AtomicReferenceArray<>(emitters.size()) : null;
    }

    public static FrequencySableReceptionSnapshot capture(ServerLevel level, Vec3 receiver, List<Vec3> emitters, List<? extends SubLevelAccess> candidates) {
        return captureBatch(level, List.of(new BatchInput(receiver, emitters, candidates))).getFirst();
    }

    public static List<FrequencySableReceptionSnapshot> captureBatch(ServerLevel level, List<BatchInput> inputs) {
        return captureBatch(level, inputs, null);
    }

    public static List<FrequencySableReceptionSnapshot> captureBatch(ServerLevel level, List<BatchInput> inputs, CaptureTimings timings) {
        return captureBatch(level, inputs, timings, null, null);
    }

    public static List<FrequencySableReceptionSnapshot> captureBatch(ServerLevel level, List<BatchInput> inputs, CaptureTimings timings, Vec3 sharedEmitterMin, Vec3 sharedEmitterMax) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(inputs);

        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Material snapshots require the server thread");
        }

        if (inputs.isEmpty()) {
            return List.of();
        }

        if (sharedEmitterMin != null && sharedEmitterMax != null) {
            List<FrequencySableReceptionSnapshot> deferred = captureDenseDeferred(level, inputs, timings, sharedEmitterMin, sharedEmitterMax);
            if (deferred != null) {
                return deferred;
            }
        }

        long pathsStart = timings == null ? 0L : System.nanoTime();
        Coverage worldCoverage = new Coverage();

        if ((sharedEmitterMin == null) != (sharedEmitterMax == null)) {
            throw new IllegalArgumentException("Incomplete dense emitter bounds");
        }

        boolean sharedDenseBounds = sharedEmitterMin != null;
        if (sharedDenseBounds) {
            worldCoverage.includeDenseBounds(sharedEmitterMin, sharedEmitterMax);
        }

        IdentityHashMap<Ship, Coverage> shipCoverage = new IdentityHashMap<>();
        IdentityHashMap<SubLevelAccess, Ship> copiedShips = new IdentityHashMap<>();
        List<CapturedPaths> captured = new ArrayList<>(inputs.size());
        SegmentAabbClip.Interval interval = new SegmentAabbClip.Interval();

        for (BatchInput input : inputs) {
            Vec3 receiver = input.receiver();

            if (sharedDenseBounds && input.candidates().isEmpty()) {
                if (timings != null) {
                    timings.fastReceivers++;
                    timings.fastPaths += input.emitters().size();
                }

                worldCoverage.includeDenseBounds(receiver, receiver);

                captured.add(new CapturedPaths(receiver, List.of(), List.of(), input.emitters()));
                continue;
            }

            List<Ship> ships = new ArrayList<>(input.candidates().size());

            for (SubLevelAccess source : input.candidates()) {
                Ship ship = copiedShips.get(source);

                if (ship == null) {
                    ship = new Ship(source, new Pose3d(source.logicalPose()), new BoundingBox3d(source.boundingBox()));
                    copiedShips.put(source, ship);
                }

                ships.add(ship);
            }

            if (sharedDenseBounds && !ships.isEmpty()) {
                double minX = Math.min(sharedEmitterMin.x, receiver.x);
                double minY = Math.min(sharedEmitterMin.y, receiver.y);
                double minZ = Math.min(sharedEmitterMin.z, receiver.z);

                double maxX = Math.max(sharedEmitterMax.x, receiver.x);
                double maxY = Math.max(sharedEmitterMax.y, receiver.y);
                double maxZ = Math.max(sharedEmitterMax.z, receiver.z);

                boolean intersectsCoverage = false;

                for (Ship ship : ships) {
                    BoundingBox3d bounds = ship.bounds();

                    if (bounds.maxX() >= minX && bounds.minX() <= maxX && bounds.maxY() >= minY && bounds.minY() <= maxY && bounds.maxZ() >= minZ && bounds.minZ() <= maxZ) {
                        intersectsCoverage = true;
                        break;
                    }
                }

                if (!intersectsCoverage) {
                    if (timings != null) {
                        timings.fastReceivers++;
                        timings.fastPaths += input.emitters().size();
                    }

                    worldCoverage.includeDenseBounds(receiver, receiver);
                    captured.add(new CapturedPaths(receiver, List.copyOf(ships), List.of(), input.emitters()));

                    continue;
                }

                if (timings != null) {
                    timings.shipBlockedReceivers++;
                }
            }

            if (sharedDenseBounds) {
                worldCoverage.include(receiver);
            }

            List<List<Span>> paths = ships.isEmpty() ? List.of() : new ArrayList<>(input.emitters().size());

            for (Vec3 start : input.emitters()) {
                if (!sharedDenseBounds) {
                    worldCoverage.include(start, receiver);
                }

                if (!ships.isEmpty()) {
                    paths.add(captureSpans(start, receiver, ships, interval, shipCoverage));
                }
            }

            captured.add(new CapturedPaths(receiver, List.copyOf(ships), List.copyOf(paths), input.emitters()));
        }

        if (timings != null) {
            timings.pathsNanos += System.nanoTime() - pathsStart;
        }

        long worldStart = timings == null ? 0L : System.nanoTime();

        FrequencyMaterialWorldSnapshot.Builder builder = FrequencyMaterialWorldSnapshot.builder(level);

        try {
            worldCoverage.capture(builder);
            for (Coverage coverage : shipCoverage.values()) {
                coverage.capture(builder);
            }
        } catch (IllegalArgumentException exception) {
            builder = FrequencyMaterialWorldSnapshot.builder(level);

            for (CapturedPaths entry : captured) {
                if (entry.paths().isEmpty()) {
                    for (Vec3 start : entry.emitters()) {
                        builder.includePath(start, entry.receiver());
                    }
                    continue;
                }

                for (int i = 0; i < entry.paths().size(); i++) {
                    builder.includePath(entry.emitters().get(i), entry.receiver());

                    for (Span span : entry.paths().get(i)) {
                        builder.includePath(span.from(), span.to());
                    }
                }
            }
        }

        FrequencyMaterialWorldSnapshot world = builder.build();

        if (timings != null) {
            timings.worldNanos += System.nanoTime() - worldStart;
        }

        List<FrequencySableReceptionSnapshot> results = new ArrayList<>(captured.size());
        for (CapturedPaths entry : captured) {
            results.add(new FrequencySableReceptionSnapshot(world, entry.receiver(), entry.ships(), entry.paths(), entry.emitters()));
        }

        return List.copyOf(results);
    }

    private static List<FrequencySableReceptionSnapshot> captureDenseDeferred(ServerLevel level, List<BatchInput> inputs, CaptureTimings timings, Vec3 emitterMin, Vec3 emitterMax) {
        long pathsStart = timings == null ? 0L : System.nanoTime();

        Coverage worldCoverage = new Coverage();
        worldCoverage.includeDenseBounds(emitterMin, emitterMax);

        IdentityHashMap<SubLevelAccess, Ship> copiedShips = new IdentityHashMap<>();
        IdentityHashMap<Ship, Coverage> shipCoverage = new IdentityHashMap<>();
        List<DeferredCapture> captured = new ArrayList<>(inputs.size());

        for (BatchInput input : inputs) {
            Vec3 receiver = input.receiver();
            worldCoverage.include(receiver);

            double minX = Math.min(emitterMin.x, receiver.x);
            double minY = Math.min(emitterMin.y, receiver.y);
            double minZ = Math.min(emitterMin.z, receiver.z);
            double maxX = Math.max(emitterMax.x, receiver.x);
            double maxY = Math.max(emitterMax.y, receiver.y);
            double maxZ = Math.max(emitterMax.z, receiver.z);

            List<Ship> ships = new ArrayList<>(input.candidates().size());
            boolean intersectsCoverage = false;

            for (SubLevelAccess source : input.candidates()) {
                Ship ship = copiedShips.get(source);

                if (ship == null) {
                    ship = new Ship(source, new Pose3d(source.logicalPose()), new BoundingBox3d(source.boundingBox()));
                    copiedShips.put(source, ship);
                }

                ships.add(ship);
                BoundingBox3d bounds = ship.bounds();

                if (bounds.maxX() < minX || bounds.minX() > maxX || bounds.maxY() < minY || bounds.minY() > maxY || bounds.maxZ() < minZ || bounds.minZ() > maxZ) {
                    continue;
                }

                intersectsCoverage = true;

                if (!shipCoverage.containsKey(ship)) {
                    Coverage localCoverage = new Coverage();

                    for (int corner = 0; corner < 8; corner++) {
                        double x = (corner & 1) == 0 ? bounds.minX() : bounds.maxX();
                        double y = (corner & 2) == 0 ? bounds.minY() : bounds.maxY();
                        double z = (corner & 4) == 0 ? bounds.minZ() : bounds.maxZ();

                        Vec3 local = ship.pose().transformPositionInverse(new Vec3(x, y, z));
                        if (!Double.isFinite(local.x) || !Double.isFinite(local.y) || !Double.isFinite(local.z)) {
                            return null;
                        }

                        localCoverage.include(local);
                    }

                    shipCoverage.put(ship, localCoverage);
                }
            }

            captured.add(new DeferredCapture(receiver, List.copyOf(ships), input.emitters(), intersectsCoverage));
        }

        if (timings != null) {
            timings.pathsNanos += System.nanoTime() - pathsStart;
        }

        long worldStart = timings == null ? 0L : System.nanoTime();

        try {
            FrequencyMaterialWorldSnapshot.Builder builder = FrequencyMaterialWorldSnapshot.builder(level);
            worldCoverage.capture(builder);

            for (Coverage coverage : shipCoverage.values()) {
                coverage.capture(builder);
            }

            FrequencyMaterialWorldSnapshot world = builder.build();
            List<FrequencySableReceptionSnapshot> results = new ArrayList<>(captured.size());

            for (DeferredCapture entry : captured) {
                if (timings != null) {
                    if (entry.deferred()) {
                        timings.shipBlockedReceivers++;
                    } else {
                        timings.fastReceivers++;
                        timings.fastPaths += entry.emitters().size();
                    }
                }

                results.add(new FrequencySableReceptionSnapshot(world, entry.receiver(), entry.ships(), List.of(), entry.emitters(), entry.deferred()));
            }

            return List.copyOf(results);
        } catch (IllegalArgumentException exception) {
            return null;
        } finally {
            if (timings != null) {
                timings.worldNanos += System.nanoTime() - worldStart;
            }
        }
    }

    private List<Span> pathSpans(int index) {
        if (deferredPaths == null) {
            return paths.isEmpty() ? List.of() : paths.get(index);
        }

        List<Span> cached = deferredPaths.get(index);

        if (cached != null) {
            return cached;
        }

        Vec3 start = emitters.get(index);
        double dx = receiver.x - start.x;
        double dy = receiver.y - start.y;
        double dz = receiver.z - start.z;

        SegmentAabbClip.Interval interval = new SegmentAabbClip.Interval();
        List<Span> calculated = null;
        double distance = 0.0;

        for (Ship ship : ships) {
            BoundingBox3d bounds = ship.bounds();
            if (!SegmentAabbClip.clip(start.x, start.y, start.z, receiver.x, receiver.y, receiver.z, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), interval)) {
                continue;
            }

            double enter = interval.enter();
            double exit = interval.exit();

            Vec3 clippedStart = new Vec3(Math.fma(dx, enter, start.x), Math.fma(dy, enter, start.y), Math.fma(dz, enter, start.z));
            Vec3 clippedEnd = new Vec3(Math.fma(dx, exit, start.x), Math.fma(dy, exit, start.y), Math.fma(dz, exit, start.z));

            Pose3d pose = ship.pose();

            Vec3 localStart = pose.transformPositionInverse(clippedStart);
            Vec3 localEnd = pose.transformPositionInverse(clippedEnd);

            if (calculated == null) {
                calculated = new ArrayList<>();
                distance = start.distanceTo(receiver);
            }

            var scale = pose.scale();
            boolean scaled = scale.x() != 1.0 || scale.y() != 1.0 || scale.z() != 1.0;

            calculated.add(new Span(localStart, localEnd, enter, exit, distance * (exit - enter), localStart.distanceTo(localEnd), scaled));
        }

        List<Span> result = calculated == null ? List.of() : List.copyOf(calculated);
        if (deferredPaths.compareAndSet(index, null, result)) {
            return result;
        }

        return deferredPaths.get(index);
    }

    public boolean hasShipIntersections(int index) {
        return !pathSpans(index).isEmpty();
    }

    public WorkerSample sampleFactor(int index, double frequencyHz, boolean reflection, MinecraftCombinedWavePath wave) {
        SampleTimings timings = new SampleTimings();
        boolean shipHit = hasShipIntersections(index);

        long started = System.nanoTime();
        double result = factor(index, frequencyHz, reflection, wave, timings);
        long totalNanos = System.nanoTime() - started;

        return new WorkerSample(result, shipHit, totalNanos, timings.worldNanos, timings.shipNanos, timings.waveNanos);
    }

    public double factor(int index, double frequencyHz, boolean reflection, MinecraftCombinedWavePath wave) {
        return factor(index, frequencyHz, reflection, wave, null);
    }

    private double factor(int index, double frequencyHz, boolean reflection, MinecraftCombinedWavePath wave, SampleTimings timings) {
        Vec3 start = emitters.get(index);
        List<Span> spans = pathSpans(index);

        if (spans.isEmpty()) {
            if (reflection) {
                return timings == null ? wave.factorSnapshot(world, start, receiver, frequencyHz) : wave.factorSnapshotProfiled(world, start, receiver, frequencyHz, timings);
            }

            long started = timings == null ? 0L : System.nanoTime();
            double result = FrequencyMaterialAttenuation.factorSnapshot(world, start, receiver);

            if (timings != null) {
                timings.worldNanos += System.nanoTime() - started;
            }

            return result;
        }

        if (!reflection) {
            long started = timings == null ? 0L : System.nanoTime();
            double result = FrequencyMaterialAttenuation.factorSnapshot(world, start, receiver);

            if (timings != null) {
                timings.worldNanos += System.nanoTime() - started;
            }

            for (Span span : spans) {
                long shipStarted = timings == null ? 0L : System.nanoTime();

                double ship = FrequencyMaterialAttenuation.factorSnapshotRelativeToAir(world, span.from(), span.to());
                if (span.scaled() && span.localMeters() > 0.0 && Double.isFinite(span.localMeters()) && Double.isFinite(span.worldMeters())) {
                    ship = MaterialAttenuationMath.scaledFactor(ship, span.worldMeters(), span.localMeters());
                }

                result *= ship;

                if (timings != null) {
                    timings.shipNanos += System.nanoTime() - shipStarted;
                }
            }

            return result;
        }

        OverlayWorkspace workspace = OVERLAYS.get();
        workspace.overlay.reset();

        long started = timings == null ? 0L : System.nanoTime();
        double worldFactor = FrequencyMaterialAttenuation.factorSnapshotWithMaterials(world, start, receiver, workspace.overlay);

        if (timings != null) {
            timings.worldNanos += System.nanoTime() - started;
        }

        if (!Double.isFinite(worldFactor)) {
            return Double.NaN;
        }

        for (Span span : spans) {
            workspace.enter = span.enter();
            workspace.span = span.exit() - span.enter();

            long shipStarted = timings == null ? 0L : System.nanoTime();
            double shipFactor = FrequencyMaterialAttenuation.factorSnapshotRelativeToAirWithMaterials(world, span.from(), span.to(), workspace);

            if (timings != null) {
                timings.shipNanos += System.nanoTime() - shipStarted;
            }

            if (!Double.isFinite(shipFactor)) {
                return Double.NaN;
            }
        }

        long waveStarted = timings == null ? 0L : System.nanoTime();
        double result = wave.factorSnapshotCombined(workspace.overlay, start.distanceTo(receiver), frequencyHz, world.enabled());

        if (timings != null) {
            timings.waveNanos += System.nanoTime() - waveStarted;
        }

        return result;
    }

    boolean sharesWorldSnapshotWith(FrequencySableReceptionSnapshot other) {
        return other != null && world == other.world;
    }

    public boolean currentOnServer(List<? extends SubLevelAccess> currentShips) {
        if (!world.currentOnServer() || currentShips.size() != ships.size()) {
            return false;
        }

        for (int i = 0; i < ships.size(); i++) {
            Ship ship = ships.get(i);
            if (ship.source() != currentShips.get(i) || !ship.current()) {
                return false;
            }
        }

        return true;
    }
}