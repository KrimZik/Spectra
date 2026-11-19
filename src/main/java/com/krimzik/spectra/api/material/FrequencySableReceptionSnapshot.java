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

public final class FrequencySableReceptionSnapshot {
    public static final class CaptureTimings {
        private long pathsNanos;
        private long worldNanos;

        public long pathsNanos() {
            return pathsNanos;
        }

        public long worldNanos() {
            return worldNanos;
        }
    }

    private static final class Coverage {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        void include(Vec3 start, Vec3 end) {
            minX = Math.min(minX, Math.min(start.x, end.x));
            minY = Math.min(minY, Math.min(start.y, end.y));
            minZ = Math.min(minZ, Math.min(start.z, end.z));

            maxX = Math.max(maxX, Math.max(start.x, end.x));
            maxY = Math.max(maxY, Math.max(start.y, end.y));
            maxZ = Math.max(maxZ, Math.max(start.z, end.z));
        }

        void include(Vec3 point) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }

        void capture(FrequencyMaterialWorldSnapshot.Builder builder) {
            if (minX != Double.POSITIVE_INFINITY) {
                builder.includePath(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
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

    private record CapturedPaths(Vec3 receiver, List<Ship> ships, List<Path> paths, List<Vec3> emitters) {}

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
    private record Path(Vec3 start, List<Span> ships) {}

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
    private final List<Path> paths;
    private final List<Vec3> emitters;

    private FrequencySableReceptionSnapshot(FrequencyMaterialWorldSnapshot world, Vec3 receiver, List<Ship> ships, List<Path> paths, List<Vec3> emitters) {
        this.world = world;
        this.receiver = receiver;
        this.ships = List.copyOf(ships);
        this.paths = List.copyOf(paths);
        this.emitters = emitters;
    }

    public static FrequencySableReceptionSnapshot capture(ServerLevel level, Vec3 receiver, List<Vec3> emitters, List<? extends SubLevelAccess> candidates) {
        return captureBatch(level, List.of(new BatchInput(receiver, emitters, candidates))).getFirst();
    }

    public static List<FrequencySableReceptionSnapshot> captureBatch(ServerLevel level, List<BatchInput> inputs) {
        return captureBatch(level, inputs, null);
    }

    public static List<FrequencySableReceptionSnapshot> captureBatch(ServerLevel level, List<BatchInput> inputs, CaptureTimings timings) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(inputs);

        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Material snapshots require the server thread");
        }

        if (inputs.isEmpty()) {
            return List.of();
        }

        long pathsStart = timings == null ? 0L : System.nanoTime();
        Coverage worldCoverage = new Coverage();
        IdentityHashMap<Ship, Coverage> shipCoverage = new IdentityHashMap<>();
        IdentityHashMap<SubLevelAccess, Ship> copiedShips = new IdentityHashMap<>();
        List<CapturedPaths> captured = new ArrayList<>(inputs.size());
        SegmentAabbClip.Interval interval = new SegmentAabbClip.Interval();

        for (BatchInput input : inputs) {
            Vec3 receiver = input.receiver();
            if (!input.emitters().isEmpty()) {
                worldCoverage.include(receiver);
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

            List<Path> paths = ships.isEmpty() ? List.of() : new ArrayList<>(input.emitters().size());

            for (Vec3 start : input.emitters()) {
                worldCoverage.include(start);
                if (ships.isEmpty()) {
                    continue;
                }

                double dx = receiver.x - start.x;
                double dy = receiver.y - start.y;
                double dz = receiver.z - start.z;
                double distance = start.distanceTo(receiver);

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

                    shipCoverage.computeIfAbsent(ship, ignored -> new Coverage()).include(localStart, localEnd);
                    var scale = pose.scale();
                    boolean scaled = scale.x() != 1.0 || scale.y() != 1.0 || scale.z() != 1.0;

                    if (spans == null) {
                        spans = new ArrayList<>();
                    }

                    spans.add(new Span(localStart, localEnd, enter, exit, distance * (exit - enter), localStart.distanceTo(localEnd), scaled));
                }

                paths.add(new Path(start, spans == null ? List.of() : List.copyOf(spans)));
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
                }
                for (Path path : entry.paths()) {
                    builder.includePath(path.start(), entry.receiver());

                    for (Span span : path.ships()) {
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

    public boolean hasShipIntersections(int index) {
        return !paths.isEmpty() && !paths.get(index).ships().isEmpty();
    }

    public double factor(int index, double frequencyHz, boolean reflection, MinecraftCombinedWavePath wave) {
        if (paths.isEmpty()) {
            Vec3 start = emitters.get(index);
            return reflection ? wave.factorSnapshot(world, start, receiver, frequencyHz) : FrequencyMaterialAttenuation.factorSnapshot(world, start, receiver);
        }

        Path path = paths.get(index);
        if (path.ships().isEmpty()) {
            return reflection ? wave.factorSnapshot(world, path.start(), receiver, frequencyHz) : FrequencyMaterialAttenuation.factorSnapshot(world, path.start(), receiver);
        }

        if (!reflection) {
            double result = FrequencyMaterialAttenuation.factorSnapshot(world, path.start(), receiver);

            for (Span span : path.ships()) {
                double ship = FrequencyMaterialAttenuation.factorSnapshotRelativeToAir(world, span.from(), span.to());

                if (span.scaled() && span.localMeters() > 0.0 && Double.isFinite(span.localMeters()) && Double.isFinite(span.worldMeters())) {
                    ship = MaterialAttenuationMath.scaledFactor(ship, span.worldMeters(), span.localMeters());
                }

                result *= ship;
            }

            return result;
        }

        OverlayWorkspace workspace = OVERLAYS.get();
        workspace.overlay.reset();

        double worldFactor = FrequencyMaterialAttenuation.factorSnapshotWithMaterials(world, path.start(), receiver, workspace.overlay);
        if (!Double.isFinite(worldFactor)) {
            return Double.NaN;
        }

        for (Span span : path.ships()) {
            workspace.enter = span.enter();
            workspace.span = span.exit() - span.enter();

            double shipFactor = FrequencyMaterialAttenuation.factorSnapshotRelativeToAirWithMaterials(world, span.from(), span.to(), workspace);
            if (!Double.isFinite(shipFactor)) {
                return Double.NaN;
            }
        }

        return wave.factorSnapshotCombined(workspace.overlay, path.start().distanceTo(receiver), frequencyHz, world.enabled());
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