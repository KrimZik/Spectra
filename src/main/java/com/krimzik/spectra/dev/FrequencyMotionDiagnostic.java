package com.krimzik.spectra.dev;

import com.krimzik.spectra.api.motion.FrequencyMotion;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import com.krimzik.spectra.api.FrequencySpace;
import com.krimzik.spectra.api.FrequencyEngineQueryDiagnostic;
import com.krimzik.spectra.api.FrequencyDopplerDiagnostic;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import org.joml.Vector3d;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class FrequencyMotionDiagnostic {
    private static final Set<UUID> WATCHED_PLAYERS = new HashSet<>();
    private static final Map<UUID, LocalWatch> LOCAL_WATCH = new HashMap<>();

    private static final Map<UUID, VelocityHistory> VELOCITY_HISTORY = new HashMap<>();
    private record VelocityHistory(ServerLevel level, String mode, String sublevelId, int tick, Vec3 position, Vec3 velocity) {}

    private static final int RECORD_TICKS = 200;
    private static final Map<UUID, MotionRecording> RECORDINGS = new HashMap<>();

    private FrequencyMotionDiagnostic() {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(FrequencyMotionDiagnostic::onCommands);
        NeoForge.EVENT_BUS.addListener(FrequencyMotionDiagnostic::onServerTick);
        NeoForge.EVENT_BUS.addListener(FrequencyMotionDiagnostic::onServerStopped);
        NeoForge.EVENT_BUS.addListener(FrequencyMotionDiagnostic::onEntityTeleport);
    }

    private static void onEntityTeleport(EntityTeleportEvent event) {
        if (!event.isCanceled()) {
            FrequencyDopplerDiagnostic.noteTeleport(event.getEntity());
        }
    }

    private static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("freqmotion")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("once").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    report(player, player);
                    return Command.SINGLE_SUCCESS;
                }).then(Commands.argument("target", EntityArgument.entity()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    Entity target = EntityArgument.getEntity(context, "target");
                    report(player, target);
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.literal("space").executes(context -> profileSpace(context.getSource().getPlayerOrException(), null)).then(Commands.argument("target", EntityArgument.entity()).executes(context -> profileSpace(context.getSource().getPlayerOrException(), EntityArgument.getEntity(context, "target")))))
                .then(Commands.literal("query").executes(context -> FrequencyEngineQueryDiagnostic.run(context.getSource().getPlayerOrException(), null)).then(Commands.argument("target", EntityArgument.entity()).executes(context -> FrequencyEngineQueryDiagnostic.run(context.getSource().getPlayerOrException(), EntityArgument.getEntity(context, "target")))))
                .then(Commands.literal("doppler").then(Commands.argument("target", EntityArgument.entity()).executes(context -> FrequencyDopplerDiagnostic.start(context.getSource().getPlayerOrException(), EntityArgument.getEntity(context, "target")))))
                .then(Commands.literal("watch").executes(FrequencyMotionDiagnostic::watch))
                .then(Commands.literal("record").executes(FrequencyMotionDiagnostic::record))
                .then(Commands.literal("stop").executes(FrequencyMotionDiagnostic::stop))
        );
    }

    private static int watch(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        WATCHED_PLAYERS.add(player.getUUID());
        LOCAL_WATCH.remove(player.getUUID());
        VELOCITY_HISTORY.remove(player.getUUID());
        player.sendSystemMessage(Component.literal("[FreqMotion] Watching motion every 10 ticks"));

        return Command.SINGLE_SUCCESS;
    }

    private static int record(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        UUID id = player.getUUID();

        if (RECORDINGS.containsKey(id)) {
            player.sendSystemMessage(Component.literal("[FreqMotion] A recording is already running."));
            return Command.SINGLE_SUCCESS;
        }

        LOCAL_WATCH.remove(id);
        VELOCITY_HISTORY.remove(id);
        RECORDINGS.put(id, new MotionRecording());

        player.sendSystemMessage(Component.literal("[FreqMotion] Recording 200 server ticks (~10 seconds at 20 TPS)..."));

        return Command.SINGLE_SUCCESS;
    }

    private static int stop(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        UUID id = player.getUUID();
        FrequencyDopplerDiagnostic.stop(id);

        WATCHED_PLAYERS.remove(id);
        LOCAL_WATCH.remove(id);
        VELOCITY_HISTORY.remove(id);

        boolean discardedRecording = RECORDINGS.remove(id) != null;

        player.displayClientMessage(Component.empty(), true);
        player.sendSystemMessage(Component.literal(discardedRecording ? "[FreqMotion] Monitoring stopped; incomplete recording discarded." : "[FreqMotion] Motion monitoring stopped."));

        return Command.SINGLE_SUCCESS;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        FrequencyDopplerDiagnostic.tick(event.getServer());
        if (WATCHED_PLAYERS.isEmpty() && RECORDINGS.isEmpty()) return;

        int tick = event.getServer().getTickCount();
        boolean display = tick % 10 == 0;

        Set<UUID> active = new HashSet<>(WATCHED_PLAYERS);
        active.addAll(RECORDINGS.keySet());

        for (UUID id : active) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(id);

            if (player == null) {
                WATCHED_PLAYERS.remove(id);
                RECORDINGS.remove(id);
                LOCAL_WATCH.remove(id);
                VELOCITY_HISTORY.remove(id);
                continue;
            }

            LocalWatch local = updateLocalWatch(player, tick);

            boolean watched = WATCHED_PLAYERS.contains(id);
            MotionRecording recording = RECORDINGS.get(id);

            if (recording == null && !watched) {
                continue;
            }

            Sample sample = capture(player);
            Vec3 acceleration = sampleAcceleration(player, tick, sample);

            if (recording == null && !display) {
                continue;
            }

            if (recording != null) {
                appendRecordingRow(recording, tick, sample, local, player, acceleration);

                if (recording.samples() == RECORD_TICKS) {
                    RECORDINGS.remove(id);
                    saveRecording(player, recording);

                    if (!watched) {
                        LOCAL_WATCH.remove(id);
                        VELOCITY_HISTORY.remove(id);
                    }
                }
            }

            if (watched && display) {
                String localSpeed = local == null ? "N/A" : formatLocal(local.localSpeed());
                String anchorDrift = local == null ? "N/A" : formatLocal(local.anchorDrift());
                String footSpeed = local == null ? "N/A" : formatLocal(local.footSpeed());
                String footDrift = local == null ? "N/A" : formatLocal(local.footDrift());

                player.displayClientMessage(Component.literal(String.format(
                        Locale.ROOT,
                        "FREQ [%s] T%.1f S%.1f R%.2f | A%s L%s F%s D%s FD%s",
                        sample.mode(),
                        speed(sample.total()),
                        speed(sample.ship()),
                        speed(sample.relative()),
                        formatLocal(acceleration == null ? Double.NaN : acceleration.length()),
                        localSpeed,
                        footSpeed,
                        anchorDrift,
                        footDrift
                )), true);
            }
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        FrequencyDopplerDiagnostic.clear();
        WATCHED_PLAYERS.clear();
        LOCAL_WATCH.clear();
        VELOCITY_HISTORY.clear();
        RECORDINGS.clear();
    }

    private static void report(ServerPlayer recipient, Entity target) {
        Sample sample = capture(target);

        recipient.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[FreqMotion] Entity: %s | Mode: %s | Sublevel: %s", target.getName().getString(), sample.mode(), sample.sublevelId())));
        recipient.sendSystemMessage(Component.literal("[FreqMotion] Position: " + vector(sample.position())));
        recipient.sendSystemMessage(Component.literal("[FreqMotion] Total velocity: " + vector(sample.total()) + " m/s | Speed: " + number(speed(sample.total())) + " m/s"));
        recipient.sendSystemMessage(Component.literal("[FreqMotion] Ship velocity: " + vector(sample.ship()) + " m/s"));
        recipient.sendSystemMessage(Component.literal("[FreqMotion] Relative velocity: " + vector(sample.relative()) + " m/s"));
        recipient.sendSystemMessage(Component.literal("[FreqMotion] Raw world displacement: " + vector(sample.rawMovement()) + " m/s"));

        LocalWatch local = LOCAL_WATCH.get(target.getUUID());

        if (local != null && local.level() == target.level()) {
            recipient.sendSystemMessage(Component.literal("[FreqMotion] Origin local speed: " + formatLocal(local.localSpeed()) + " m/s | Origin drift: " + formatLocal(local.anchorDrift()) + " m"));
            recipient.sendSystemMessage(Component.literal("[FreqMotion] Feet local speed: " + formatLocal(local.footSpeed()) + " m/s | Feet drift: " + formatLocal(local.footDrift()) + " m"));
        }
    }

    private static volatile double spaceBenchmarkSink;

    private static int profileSpace(ServerPlayer player, Entity target) {
        ServerLevel level = player.serverLevel();

        if (target != null && target.level() != level) {
            player.sendSystemMessage(Component.literal(
                    "[FreqSpace] Both entities must be in the same dimension."
            ));
            return Command.SINGLE_SUCCESS;
        }

        Vec3 a = diagnosticSpatialPosition(player);

        Vec3 b = target == null
                ? FrequencySpace.worldPosition(level, a)
                .add(12.0, 1.0, -4.0)
                : diagnosticSpatialPosition(target);

        double original = FrequencySpace.distanceSquaredMeters(
                level, a, b
        );

        Vec3 worldA = FrequencySpace.worldPosition(level, a);
        Vec3 worldB = FrequencySpace.worldPosition(level, b);

        double optimized = worldA.distanceToSqr(worldB);

        double error = Math.abs(original - optimized);
        double tolerance = 1.0e-9 * Math.max(1.0, original);
        boolean equivalent = Double.isFinite(original)
                && Double.isFinite(optimized)
                && error <= tolerance;

        double directNs = benchmarkSpatial(level, a, b, 0);
        double projectedNs = benchmarkSpatial(level, a, b, 1);
        double cachedNs = benchmarkSpatial(level, a, b, 2);

        player.sendSystemMessage(Component.literal(String.format(
                Locale.ROOT,
                "[FreqSpace] A=%s B=%s",
                SableCompanion.INSTANCE.getContaining(level, a) != null
                        ? "PLOT" : "WORLD",
                SableCompanion.INSTANCE.getContaining(level, b) != null
                        ? "PLOT" : "WORLD"
        )));

        player.sendSystemMessage(Component.literal(String.format(
                Locale.ROOT,
                "[FreqSpace] Original=%.12f Optimized=%.12f",
                original, optimized
        )));

        player.sendSystemMessage(Component.literal(String.format(
                Locale.ROOT,
                "[FreqSpace] Error=%.3e Tolerance=%.3e Equivalent=%s",
                error, tolerance, equivalent
        )));

        player.sendSystemMessage(Component.literal(String.format(
                Locale.ROOT,
                "[FreqSpace] Direct=%.1f ns | Both projected=%.1f ns | Cached receiver=%.1f ns",
                directNs, projectedNs, cachedNs
        )));

        if (cachedNs > 0.0) {
            player.sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "[FreqSpace] Direct/Cached ratio=%.3fx",
                    directNs / cachedNs
            )));
        }

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Convert tracked world-space entities to their
     * sublevel-local positions when applicable.
     *
     * Mirrors the coordinate handling already used
     * by FrequencyMotionDiagnostic.
     */
    private static Vec3 diagnosticSpatialPosition(Entity entity) {
        SableCompanion companion = SableCompanion.INSTANCE;
        Vec3 position = entity.position();

        if (companion.getContaining(entity.level(), position) != null) {
            return position;
        }

        SubLevelAccess tracked =
                companion.getTrackingOrVehicleSubLevel(entity);

        if (tracked != null) {
            return tracked.logicalPose()
                    .transformPositionInverse(position);
        }

        return position;
    }

    /**
     * mode 0: Original Sable-aware distance
     * mode 1: Project both positions
     * mode 2: Cache receiver projection
     */
    private static double benchmarkSpatial(ServerLevel level, Vec3 a, Vec3 b, int mode) {
        Vec3 cachedB = FrequencySpace.worldPosition(level, b);

        long measuredNanos = 0L;

        for (int pass = 0; pass < 2; pass++) {
            int iterations = pass == 0 ? 512 : 2048;
            double checksum = 0.0;

            long start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                double distanceSquared;

                switch (mode) {
                    case 0 -> distanceSquared = FrequencySpace.distanceSquaredMeters(level, a, b);
                    case 1 -> {
                        Vec3 projectedA = FrequencySpace.worldPosition(level, a);
                        Vec3 projectedB = FrequencySpace.worldPosition(level, b);
                        distanceSquared = projectedA.distanceToSqr(projectedB);
                    }
                    case 2 -> {
                        Vec3 projectedA = FrequencySpace.worldPosition(level, a);
                        distanceSquared = projectedA.distanceToSqr(cachedB);
                    }

                    default -> throw new IllegalArgumentException("Invalid benchmark mode");
                }

                checksum += distanceSquared;
            }

            long elapsed = System.nanoTime() - start;
            spaceBenchmarkSink = checksum;

            if (pass == 1) {
                measuredNanos = elapsed;
            }
        }

        return measuredNanos / 2048.0;
    }


    private static Vec3 sampleAcceleration(ServerPlayer player, int tick, Sample sample) {
        UUID id = player.getUUID();

        Vec3 position = sample.position();
        Vec3 velocity = sample.total();

        if (!Double.isFinite(position.x)
                || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)
                || !Double.isFinite(velocity.x)
                || !Double.isFinite(velocity.y)
                || !Double.isFinite(velocity.z)) {
            VELOCITY_HISTORY.remove(id);
            return null;
        }

        VelocityHistory previous = VELOCITY_HISTORY.put(id, new VelocityHistory(player.serverLevel(), sample.mode(), sample.sublevelId(), tick, position, velocity));

        if (previous == null || previous.level() != player.serverLevel() || previous.tick() != tick - 1 || !previous.mode().equals(sample.mode()) || !previous.sublevelId().equals(sample.sublevelId())) {
            return null;
        }

        if (position.distanceToSqr(previous.position()) > 4096.0) {
            return null;
        }

        return velocity.subtract(previous.velocity()).scale(20.0);
    }

    private static Sample capture(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Motion diagnostics require a server level");
        }

        FrequencyMotion.EntityCapture captured = FrequencyMotion.captureEntityDetailed(level, entity);
        FrequencyMotionSnapshot motion = captured.motion();
        String mode = captured.frame().name().toLowerCase(Locale.ROOT);
        Vec3 shipVelocity = captured.shipVelocity();
        Vec3 position = entity.position();

        Vec3 worldPosition = new Vec3(motion.x(), motion.y(), motion.z());
        Vec3 total = new Vec3(motion.vx(), motion.vy(), motion.vz());
        Vec3 relative = total.subtract(shipVelocity);
        Vec3 previous = new Vec3(entity.xOld, entity.yOld, entity.zOld);

        Vec3 rawMovement = position.subtract(previous).scale(20.0);
        String sublevelId = captured.sublevelId() == null ? "none" : captured.sublevelId().toString();

        return new Sample(mode, sublevelId, worldPosition, total, shipVelocity, relative, rawMovement);
    }

    private static double speed(Vec3 velocity) {
        return velocity.length();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String vector(Vec3 value) {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", value.x, value.y, value.z);
    }

    private record Sample(String mode, String sublevelId, Vec3 position, Vec3 total, Vec3 ship, Vec3 relative, Vec3 rawMovement) {}

    private record LocalWatch(
        ServerLevel level,
        UUID shipId,
        Vec3 anchorLocal,
        Vec3 previousLocal,
        Vec3 previousWorld,
        boolean tracked,
        Vec3 anchorFeetLocal,
        Vec3 previousFeetLocal,
        int previousTick,
        double localSpeed,
        double anchorDrift,
        double footSpeed,
        double footDrift,
        Vec3 sampledRelativeWorld,
        double oldWorldOffset,
        double lastPoseOffset
    ) {}

    private static LocalWatch updateLocalWatch(ServerPlayer player, int tick) {
        UUID id = player.getUUID();
        ServerLevel level = player.serverLevel();
        SableCompanion companion = SableCompanion.INSTANCE;

        Vec3 position = player.position();

        SubLevelAccess containing = companion.getContaining(level, position);
        SubLevelAccess ship = containing != null ? containing : companion.getTrackingOrVehicleSubLevel(player);

        if (ship == null) {
            LOCAL_WATCH.remove(id);
            return null;
        }

        boolean tracked = containing == null;
        Pose3dc pose = ship.logicalPose();

        Vec3 local = tracked ? pose.transformPositionInverse(position) : position;
        Vector3d feet = companion.getFeetPos(player, 0.0f);
        Vec3 footWorld = new Vec3(feet.x, feet.y, feet.z);
        Vec3 footLocal = tracked ? pose.transformPositionInverse(footWorld) : footWorld;

        LocalWatch previous = LOCAL_WATCH.get(id);
        boolean continuous = previous != null && previous.level() == level && previous.shipId().equals(ship.getUniqueId()) && previous.tracked() == tracked && previous.previousTick() == tick - 1;

        Vec3 anchor = continuous ? previous.anchorLocal() : local;
        Vec3 feetAnchor = continuous ? previous.anchorFeetLocal() : footLocal;

        double localSpeed = Double.NaN;
        double footSpeed = Double.NaN;
        double oldWorldOffset = Double.NaN;
        double lastPoseOffset = Double.NaN;
        Vec3 sampledRelativeWorld = null;

        if (continuous) {
            Vec3 localDelta = local.subtract(previous.previousLocal());

            sampledRelativeWorld = pose.transformNormal(localDelta).scale(20.0);
            localSpeed = sampledRelativeWorld.length();

            footSpeed = pose.transformNormal(footLocal.subtract(previous.previousFeetLocal())).length() * 20.0;

            if (tracked) {
                Vec3 entityOld = new Vec3(player.xOld, player.yOld, player.zOld);
                oldWorldOffset = entityOld.subtract(previous.previousWorld()).length() * 20.0;
                Vec3 previousWorldFromLastPose = ship.lastPose().transformPosition(previous.previousLocal());
                lastPoseOffset = previousWorldFromLastPose.subtract(previous.previousWorld()).length() * 20.0;
            }
        }

        double drift = pose.transformNormal(local.subtract(anchor)).length();
        double footDrift = pose.transformNormal(footLocal.subtract(feetAnchor)).length();

        LocalWatch result = new LocalWatch(
            level,
            ship.getUniqueId(),
            anchor,
            local,
            position,
            tracked,
            feetAnchor,
            footLocal,
            tick,
            localSpeed,
            drift,
            footSpeed,
            footDrift,
            sampledRelativeWorld,
            oldWorldOffset,
            lastPoseOffset
        );

        LOCAL_WATCH.put(id, result);
        return result;
    }


    private static final class MotionRecording {
        private final List<String> lines = new ArrayList<>();

        private MotionRecording() {
            lines.add("tick,mode,sublevel,position_x,position_y,position_z," + "total_vx,total_vy,total_vz," + "ship_vx,ship_vy,ship_vz," + "relative_vx,relative_vy,relative_vz," + "raw_vx,raw_vy,raw_vz," + "relative_speed,local_speed,foot_speed," + "origin_drift,feet_drift," + "sampled_rel_vx,sampled_rel_vy,sampled_rel_vz," + "relative_vector_gap,old_world_offset,last_pose_offset," + "entity_delta_vx,entity_delta_vy,entity_delta_vz," + "on_ground,flying,accel_x,accel_y,accel_z");
        }

        private int samples() {
            return lines.size() - 1;
        }
    }

    private static void appendRecordingRow(MotionRecording recording, int tick, Sample sample, LocalWatch local, ServerPlayer player, Vec3 acceleration) {
        StringBuilder row = new StringBuilder();

        row.append(tick).append(',').append(sample.mode()).append(',').append(sample.sublevelId());

        appendCsvVector(row, sample.position());
        appendCsvVector(row, sample.total());
        appendCsvVector(row, sample.ship());
        appendCsvVector(row, sample.relative());
        appendCsvVector(row, sample.rawMovement());

        appendCsvNumber(row, speed(sample.relative()));

        appendCsvNumber(row, local == null ? Double.NaN : local.localSpeed());
        appendCsvNumber(row, local == null ? Double.NaN : local.footSpeed());
        appendCsvNumber(row, local == null ? Double.NaN : local.anchorDrift());
        appendCsvNumber(row, local == null ? Double.NaN : local.footDrift());

        if (local != null && sample.mode().equals("tracked") && local.sampledRelativeWorld() != null) {
            Vec3 independent = local.sampledRelativeWorld();
            appendCsvVector(row, independent);
            appendCsvNumber(row, sample.relative().subtract(independent).length());
            appendCsvNumber(row, local.oldWorldOffset());
            appendCsvNumber(row, local.lastPoseOffset());
        } else {
            for (int i = 0; i < 6; i++) {
                appendCsvNumber(row, Double.NaN);
            }
        }

        appendCsvVector(row, player.getDeltaMovement().scale(20.0));

        row.append(',').append(player.onGround() ? 1 : 0);
        row.append(',').append(player.getAbilities().flying ? 1 : 0);

        if (acceleration == null) {
            appendCsvNumber(row, Double.NaN);
            appendCsvNumber(row, Double.NaN);
            appendCsvNumber(row, Double.NaN);
        } else {
            appendCsvVector(row, acceleration);
        }

        recording.lines.add(row.toString());
    }

    private static void appendCsvVector(StringBuilder row, Vec3 vector) {
        appendCsvNumber(row, vector.x);
        appendCsvNumber(row, vector.y);
        appendCsvNumber(row, vector.z);
    }

    private static void appendCsvNumber(StringBuilder row, double value) {
        row.append(',');

        if (Double.isFinite(value)) {
            row.append(value);
        }
    }

    private static void saveRecording(ServerPlayer player, MotionRecording recording) {
        Path folder = player.getServer().getWorldPath(LevelResource.ROOT).resolve("spectra").resolve("motion_recordings");
        Path file = folder.resolve("freqmotion_" + player.getUUID() + "_" + System.currentTimeMillis() + ".csv");

        try {
            Files.createDirectories(folder);
            Files.write(file, recording.lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            player.sendSystemMessage(Component.literal("[FreqMotion] Saved " + recording.samples() + " samples: " + file.toAbsolutePath()));
        } catch (IOException exception) {
            player.sendSystemMessage(Component.literal("[FreqMotion] Failed to save CSV: " + exception.getMessage()));
        }
    }

    private static String formatLocal(double value) {
        if (!Double.isFinite(value)) {
            return "N/A";
        }

        return String.format(Locale.ROOT, "%.3f", value);
    }
}
