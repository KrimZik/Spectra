package com.krimzik.spectra.api.material;

import com.krimzik.spectra.propagation.geometry.MaterialBoundaryScanner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

final class FrequencyMaterialPathCache {
    private static final int MAX_ENTRIES = 16_384;
    private static final int ADMISSION_SLOTS = 1 << 14;
    private static final int MAX_CACHED_BOUNDARIES = 128;
    private static final int MAX_CACHED_LOSS_SEGMENTS = 256;

    private final ServerLevel level;
    private final HashMap<Vec3, HashMap<Vec3, Entry>> entries = new HashMap<>();
    private long[] admission;

    private Entry oldest;
    private Entry newest;
    private int size;
    private long cacheHits;
    private long coldMisses;
    private long traceBuilds;
    private long rulesInvalidations;
    private long sectionInvalidations;
    private long failedTraces;
    private long evictions;

    FrequencyMaterialPathCache(ServerLevel level) {
        this.level = level;
    }

    double factor(Vec3 start, Vec3 end) {
        if (!FrequencyMaterialAttenuation.enabled()) {
            return 1.0;
        }

        HashMap<Vec3, Entry> row = entries.get(end);
        Entry entry = row == null ? null : row.get(start);

        if (entry == null) {
            coldMisses++;
            admit(start, end, row);
            return FrequencyMaterialAttenuation.factor(level, start, end);
        }

        touch(entry);

        long revision = FrequencyMaterialRules.revision();

        if (entry.trace != null) {
            if (entry.rulesRevision != revision) {
                rulesInvalidations++;
            } else if (entry.trace.current()) {
                if (Double.isFinite(entry.factor)) {
                    cacheHits++;
                    return entry.factor;
                }
            } else {
                sectionInvalidations++;
            }
        }

        traceBuilds++;
        entry.boundaryGeometry = null;
        entry.lossGeometry = null;
        Trace trace = new Trace();
        double result = FrequencyMaterialAttenuation.factorWithTrace(level, start, end, trace);

        if (trace.cacheable && revision == FrequencyMaterialRules.revision() && trace.current()) {
            entry.factor = result;
            entry.rulesRevision = revision;
            entry.trace = trace;
        } else {
            failedTraces++;
            entry.trace = null;
        }

        return result;
    }

    private Entry admit(Vec3 start, Vec3 end, HashMap<Vec3, Entry> row) {
        if (size >= MAX_ENTRIES && !repeatCandidate(start, end)) {
            return null;
        }

        if (row == null) {
            row = new HashMap<>();
            entries.put(end, row);
        }

        Entry entry = new Entry(start, end);
        row.put(start, entry);
        link(entry);

        size++;
        if (size > MAX_ENTRIES) {
            evict();
        }

        return entry;
    }

    double factorWithBoundaries(Vec3 start, Vec3 end, MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> consumer) {
        Objects.requireNonNull(consumer);
        return factorWithMaterialData(start, end, consumer, null, null);
    }

    double factorWithSegments(Vec3 start, Vec3 end, FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
        Objects.requireNonNull(consumer);
        return factorWithMaterialData(start, end, null, consumer, null);
    }

    double factorWithLossSegments(Vec3 start, Vec3 end, FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
        Objects.requireNonNull(consumer);
        return factorWithMaterialData(start, end, null, null, consumer);
    }

    private double factorWithMaterialData(Vec3 start, Vec3 end, MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> boundaryConsumer, FrequencyMaterialAttenuation.MaterialSegmentConsumer segmentConsumer, FrequencyMaterialAttenuation.MaterialSegmentConsumer lossConsumer) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        HashMap<Vec3, Entry> row = entries.get(end);
        Entry entry = row == null ? null : row.get(start);

        if (entry == null) {
            coldMisses++;
            entry = admit(start, end, row);
        } else {
            touch(entry);
        }

        long revision = FrequencyMaterialRules.revision();
        boolean enabled = FrequencyMaterialAttenuation.enabled();

        if (entry != null && entry.trace != null) {
            if (entry.rulesRevision != revision) {
                rulesInvalidations++;
            } else if (!entry.trace.current()) {
                sectionInvalidations++;
            } else if (!enabled || Double.isFinite(entry.factor)) {
                if (lossConsumer != null && entry.lossGeometry != null) {
                    cacheHits++;
                    entry.lossGeometry.replay(lossConsumer);
                    return enabled ? entry.factor : 1.0;
                }

                if (lossConsumer == null && entry.boundaryGeometry != null) {
                    cacheHits++;

                    if (boundaryConsumer != null) {
                        entry.boundaryGeometry.replay(boundaryConsumer);
                    } else {
                        entry.boundaryGeometry.replaySegments(segmentConsumer);
                    }

                    return enabled ? entry.factor : 1.0;
                }
            }
        }

        BoundaryRecorder recorder = new BoundaryRecorder();
        MaterialBoundaryCollector collector = new MaterialBoundaryCollector(recorder);
        LossRecorder detailed = lossConsumer == null ? null : new LossRecorder(collector);
        Trace trace = entry == null ? null : new Trace();

        if (trace != null) {
            traceBuilds++;
        }

        double result = FrequencyMaterialAttenuation.factorWithTraceAndMaterials(level, start, end, trace, detailed == null ? collector : detailed);

        if (entry != null) {
            entry.trace = null;
            entry.boundaryGeometry = null;
            entry.lossGeometry = null;
            entry.factor = Double.NaN;

            if (Double.isFinite(result) && trace.cacheable && revision == FrequencyMaterialRules.revision() && trace.current()) {
                entry.factor = enabled ? result : Double.NaN;
                entry.rulesRevision = revision;
                entry.trace = trace;

                if (recorder.count() <= MAX_CACHED_BOUNDARIES) {
                    entry.boundaryGeometry = recorder.snapshot(collector.firstMaterial());
                }

                if (detailed != null && detailed.count <= MAX_CACHED_LOSS_SEGMENTS) {
                    entry.lossGeometry = detailed.snapshot();
                }
            } else {
                failedTraces++;
            }
        }

        if (Double.isFinite(result)) {
            if (detailed != null) {
                detailed.replay(lossConsumer);
            } else if (boundaryConsumer != null) {
                recorder.replay(boundaryConsumer);
            } else {
                recorder.replaySegments(collector.firstMaterial(), segmentConsumer);
            }
        }

        return result;
    }

    void clear() {
        entries.clear();
        admission = null;
        oldest = null;
        newest = null;
        size = 0;
    }

    record Stats(long cacheHits, long coldMisses, long traceBuilds, long rulesInvalidations, long sectionInvalidations, long failedTraces, long evictions, int entries) {}
    Stats stats() {
        return new Stats(cacheHits, coldMisses, traceBuilds, rulesInvalidations, sectionInvalidations, failedTraces, evictions, size);
    }

    private void link(Entry entry) {
        entry.previous = newest;
        entry.next = null;

        if (newest == null) {
            oldest = entry;
        } else {
            newest.next = entry;
        }

        newest = entry;
    }

    private void unlink(Entry entry) {
        if (entry.previous == null) {
            oldest = entry.next;
        } else {
            entry.previous.next = entry.next;
        }

        if (entry.next == null) {
            newest = entry.previous;
        } else {
            entry.next.previous = entry.previous;
        }
    }

    private void touch(Entry entry) {
        if (entry == newest) {
            return;
        }

        unlink(entry);
        link(entry);
    }

    private void evict() {
        Entry entry = oldest;
        unlink(entry);

        HashMap<Vec3, Entry> row = entries.get(entry.end);
        row.remove(entry.start);

        if (row.isEmpty()) {
            entries.remove(entry.end);
        }

        size--;
        evictions++;
    }

    private boolean repeatCandidate(Vec3 start, Vec3 end) {
        if (admission == null) {
            admission = new long[ADMISSION_SLOTS];
        }

        int startHash = start.hashCode();
        int endHash = end.hashCode();

        long signature = ((long) startHash << 32) | (endHash & 0xffffffffL);

        if (signature == 0L) {
            signature = Long.MIN_VALUE;
        }

        int hash = startHash * 0x9e3779b9 + Integer.rotateLeft(endHash, 13);
        int slot = (hash ^ (hash >>> 16)) & (ADMISSION_SLOTS - 1);

        boolean repeated = admission[slot] == signature;
        admission[slot] = signature;

        return repeated;
    }

    private static void replaySegments(ResourceLocation initialMaterial, ResourceLocation[] materials, double[] fractions, byte[] axes, int count, FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
        if (initialMaterial == null) {
            return;
        }

        ResourceLocation current = initialMaterial;
        double start = 0.0;
        int enteredAxes = 0;

        for (int i = 0; i < count; i++) {
            double end = fractions[i];

            if (end > start) {
                consumer.accept(current, start, end, enteredAxes);
            }

            start = end;
            current = materials[i];
            enteredAxes = Byte.toUnsignedInt(axes[i]);
        }

        if (start < 1.0) {
            consumer.accept(current, start, 1.0, enteredAxes);
        }
    }

    private static final class LossRecorder implements FrequencyMaterialAttenuation.MaterialSegmentConsumer {
        private final MaterialBoundaryCollector boundaries;

        private ResourceLocation[] materials = new ResourceLocation[8];
        private double[] starts = new double[8];
        private double[] ends = new double[8];
        private double[] losses = new double[8];
        private int[] axes = new int[8];

        private int count;

        private LossRecorder(MaterialBoundaryCollector boundaries) {
            this.boundaries = boundaries;
        }

        @Override
        public void accept(ResourceLocation material, double start, double end, int enteredAxes) {
            throw new IllegalStateException("Missing material attenuation");
        }

        @Override
        public void acceptWithLoss(ResourceLocation material, double start, double end, int enteredAxes, double decibelsPerMeter) {
            boundaries.accept(material, start, end, enteredAxes);

            if (count > 0 && materials[count - 1].equals(material) && losses[count - 1] == decibelsPerMeter && ends[count - 1] == start) {
                ends[count - 1] = end;
                return;
            }

            if (count == materials.length) {
                int capacity = materials.length << 1;
                materials = Arrays.copyOf(materials, capacity);
                starts = Arrays.copyOf(starts, capacity);
                ends = Arrays.copyOf(ends, capacity);
                losses = Arrays.copyOf(losses, capacity);
                axes = Arrays.copyOf(axes, capacity);
            }

            materials[count] = material;
            starts[count] = start;
            ends[count] = end;
            losses[count] = decibelsPerMeter;
            axes[count] = enteredAxes;
            count++;
        }

        LossGeometry snapshot() {
            return new LossGeometry(Arrays.copyOf(materials, count), Arrays.copyOf(starts, count), Arrays.copyOf(ends, count), Arrays.copyOf(losses, count), Arrays.copyOf(axes, count));
        }

        void replay(FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
            for (int i = 0; i < count; i++) {
                consumer.acceptWithLoss(materials[i], starts[i], ends[i], axes[i], losses[i]);
            }
        }
    }

    private static final class LossGeometry {
        private final ResourceLocation[] materials;
        private final double[] starts;
        private final double[] ends;
        private final double[] losses;
        private final int[] axes;

        private LossGeometry(ResourceLocation[] materials, double[] starts, double[] ends, double[] losses, int[] axes) {
            this.materials = materials;
            this.starts = starts;
            this.ends = ends;
            this.losses = losses;
            this.axes = axes;
        }

        void replay(FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
            for (int i = 0; i < materials.length; i++) {
                consumer.acceptWithLoss(materials[i], starts[i], ends[i], axes[i], losses[i]);
            }
        }
    }

    private static final class BoundaryRecorder implements MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> {
        private ResourceLocation first;
        private ResourceLocation[] materials = new ResourceLocation[8];
        private double[] fractions = new double[8];
        private byte[] axes = new byte[8];
        private int count;

        @Override
        public void onBoundary(ResourceLocation from, ResourceLocation to, double fraction, int crossedAxes) {
            if (count == 0) {
                first = from;
            }

            if (count == materials.length) {
                int capacity = materials.length << 1;
                materials = Arrays.copyOf(materials, capacity);
                fractions = Arrays.copyOf(fractions, capacity);
                axes = Arrays.copyOf(axes, capacity);
            }

            materials[count] = to;
            fractions[count] = fraction;
            axes[count] = (byte) crossedAxes;
            count++;
        }

        int count() {
            return count;
        }

        BoundaryGeometry snapshot(ResourceLocation initialMaterial) {
            if (initialMaterial == null) {
                return null;
            }

            return new BoundaryGeometry(initialMaterial, Arrays.copyOf(materials, count), Arrays.copyOf(fractions, count), Arrays.copyOf(axes, count));
        }

        void replay(MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> consumer) {
            ResourceLocation from = first;

            for (int i = 0; i < count; i++) {
                ResourceLocation to = materials[i];
                consumer.onBoundary(from, to, fractions[i], axes[i]);
                from = to;
            }
        }

        void replaySegments(ResourceLocation initialMaterial, FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
            FrequencyMaterialPathCache.replaySegments(initialMaterial, materials, fractions, axes, count, consumer);
        }
    }

    private static final class BoundaryGeometry {
        private static final BoundaryGeometry EMPTY = new BoundaryGeometry(null, new ResourceLocation[0], new double[0], new byte[0]);

        private final ResourceLocation first;
        private final ResourceLocation[] materials;
        private final double[] fractions;
        private final byte[] axes;

        private BoundaryGeometry(ResourceLocation first, ResourceLocation[] materials, double[] fractions, byte[] axes) {
            this.first = first;
            this.materials = materials;
            this.fractions = fractions;
            this.axes = axes;
        }

        void replay(MaterialBoundaryScanner.BoundaryConsumer<ResourceLocation> consumer) {
            ResourceLocation from = first;

            for (int i = 0; i < materials.length; i++) {
                ResourceLocation to = materials[i];
                consumer.onBoundary(from, to, fractions[i], axes[i]);
                from = to;
            }
        }

        void replaySegments(FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
            FrequencyMaterialPathCache.replaySegments(first, materials, fractions, axes, materials.length, consumer);
        }
    }

    private static final class Entry {
        final Vec3 start;
        final Vec3 end;

        double factor;
        long rulesRevision;
        Trace trace;
        BoundaryGeometry boundaryGeometry;
        LossGeometry lossGeometry;

        Entry previous;
        Entry next;

        Entry(Vec3 start, Vec3 end) {
            this.start = start;
            this.end = end;
        }
    }

    static final class Trace {
        private LevelChunkSection[] sections = new LevelChunkSection[4];
        private FrequencyMaterialSectionCache[] summaries = new FrequencyMaterialSectionCache[4];

        private int size;
        boolean cacheable = true;

        void observe(LevelChunkSection section, FrequencyMaterialSectionCache summary) {
            if (!cacheable) {
                return;
            }

            if (!(section instanceof FrequencyMaterialSectionAccess access)) {
                cacheable = false;
                return;
            }

            FrequencyMaterialSectionCache current = summary == null ? access.spectra$materialSummary() : summary;
            if (current == null) {
                cacheable = false;
                return;
            }

            for (int i = 0; i < size; i++) {
                if (sections[i] == section) {
                    if (summaries[i] != current) {
                        cacheable = false;
                    }
                    return;
                }
            }

            if (size == sections.length) {
                sections = Arrays.copyOf(sections, size * 2);
                summaries = Arrays.copyOf(summaries, size * 2);
            }

            sections[size] = section;
            summaries[size] = current;
            size++;
        }

        void missing() {
            cacheable = false;
        }

        boolean current() {
            if (!cacheable) {
                return false;
            }

            for (int i = 0; i < size; i++) {
                if (!(sections[i] instanceof FrequencyMaterialSectionAccess access) || access.spectra$materialSummary() != summaries[i]) {
                    return false;
                }
            }

            return true;
        }
    }
}