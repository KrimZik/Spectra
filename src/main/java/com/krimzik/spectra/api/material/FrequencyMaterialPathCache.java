package com.krimzik.spectra.api.material;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.IdentityHashMap;

final class FrequencyMaterialPathCache {
    private static final int MAX_ENTRIES = 4096;

    private final ServerLevel level;
    private final IdentityHashMap<Object, IdentityHashMap<Object, Entry>> entries = new IdentityHashMap<>();

    private Entry oldest;
    private Entry newest;
    private int size;
    private long cacheHits;
    private long coldMisses;
    private long movedPaths;
    private long traceBuilds;
    private long rulesInvalidations;
    private long sectionInvalidations;
    private long failedTraces;
    private long evictions;

    FrequencyMaterialPathCache(ServerLevel level) {
        this.level = level;
    }

    double factor(Object receiver, Object emitter, Vec3 start, Vec3 end) {
        if (!FrequencyMaterialAttenuation.enabled()) {
            return 1.0;
        }

        IdentityHashMap<Object, Entry> row = entries.get(receiver);
        Entry entry = row == null ? null : row.get(emitter);

        if (entry == null) {
            coldMisses++;
            if (row == null) {
                row = new IdentityHashMap<>();
                entries.put(receiver, row);
            }

            entry = new Entry(receiver, emitter, start, end);
            row.put(emitter, entry);
            link(entry);
            size++;

            if (size > MAX_ENTRIES) {
                evict();
            }

            return FrequencyMaterialAttenuation.factor(level, start, end);
        }

        touch(entry);

        if (!entry.start.equals(start) || !entry.end.equals(end)) {
            movedPaths++;
            entry.start = start;
            entry.end = end;
            entry.trace = null;
            return FrequencyMaterialAttenuation.factor(level, start, end);
        }

        long revision = FrequencyMaterialRules.revision();

        if (entry.trace != null) {
            if (entry.rulesRevision != revision) {
                rulesInvalidations++;
            } else if (entry.trace.current()) {
                cacheHits++;
                return entry.factor;
            } else {
                sectionInvalidations++;
            }
        }

        traceBuilds++;

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

    void clear() {
        entries.clear();
        oldest = null;
        newest = null;
        size = 0;
    }

    record Stats(long cacheHits, long coldMisses, long movedPaths, long traceBuilds, long rulesInvalidations, long sectionInvalidations, long failedTraces, long evictions, int entries) {}
    Stats stats() {
        return new Stats(cacheHits, coldMisses, movedPaths, traceBuilds, rulesInvalidations, sectionInvalidations, failedTraces, evictions, size);
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

        IdentityHashMap<Object, Entry> row = entries.get(entry.receiver);
        row.remove(entry.emitter);

        if (row.isEmpty()) {
            entries.remove(entry.receiver);
        }

        size--;
        evictions++;
    }

    private static final class Entry {
        final Object receiver;
        final Object emitter;

        Vec3 start;
        Vec3 end;

        double factor;
        long rulesRevision;
        Trace trace;

        Entry previous;
        Entry next;

        Entry(Object receiver, Object emitter, Vec3 start, Vec3 end) {
            this.receiver = receiver;
            this.emitter = emitter;
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