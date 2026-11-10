package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyReceiver;

import java.util.Objects;
import java.util.function.Consumer;

final class ReceiverSubscriptionIndex {
    static final class Entry {
        final FrequencyReceiver receiver;
        final long sequence;
        final long priority;

        double lowerHz;
        double upperHz;
        double subtreeMaxUpperHz;

        Entry left;
        Entry right;

        Entry(FrequencyReceiver receiver, double lowerHz, double upperHz, long sequence) {
            this.receiver = receiver;
            this.lowerHz = lowerHz;
            this.upperHz = upperHz;
            this.subtreeMaxUpperHz = upperHz;
            this.sequence = sequence;
            this.priority = mixedPriority(sequence);
        }
    }

    private Entry root;
    private long nextSequence;

    Entry add(FrequencyReceiver receiver, double lowerHz, double upperHz) {
        Objects.requireNonNull(receiver);
        validate(lowerHz, upperHz);

        Entry entry = new Entry(receiver, lowerHz, upperHz, ++nextSequence);
        root = insert(root, entry);
        return entry;
    }

    void update(Entry entry, double lowerHz, double upperHz) {
        Objects.requireNonNull(entry);
        validate(lowerHz, upperHz);

        if (entry.lowerHz == lowerHz && entry.upperHz == upperHz) {
            return;
        }

        root = erase(root, entry);

        entry.left = null;
        entry.right = null;

        entry.lowerHz = lowerHz;
        entry.upperHz = upperHz;
        entry.subtreeMaxUpperHz = upperHz;

        root = insert(root, entry);
    }

    void remove(Entry entry) {
        Objects.requireNonNull(entry);

        root = erase(root, entry);
        entry.left = null;
        entry.right = null;
        entry.subtreeMaxUpperHz = entry.upperHz;
    }

    void forEachContaining(double frequencyHz, Consumer<FrequencyReceiver> consumer) {
        Objects.requireNonNull(consumer);
        find(root, frequencyHz, consumer);
    }

    void clear() {
        root = null;
    }

    private static void validate(double lowerHz, double upperHz) {
        if (!Double.isFinite(lowerHz) || !Double.isFinite(upperHz) || lowerHz < 0.0 || upperHz < lowerHz) {
            throw new IllegalArgumentException("Invalid receiver subscription band");
        }
    }

    private static Entry insert(Entry current, Entry entry) {
        if (current == null) {
            return entry;
        }

        if (compare(entry, current) < 0) {
            current.left = insert(current.left, entry);

            if (higherPriority(current.left, current)) {
                current = rotateRight(current);
            }
        } else {
            current.right = insert(current.right, entry);

            if (higherPriority(current.right, current)) {
                current = rotateLeft(current);
            }
        }

        refresh(current);
        return current;
    }

    private static Entry erase(Entry current, Entry target) {
        if (current == null) {
            throw new IllegalStateException("Subscription missing from frequency index");
        }

        int comparison = compare(target, current);
        if (comparison < 0) {
            current.left = erase(current.left, target);
        } else if (comparison > 0) {
            current.right = erase(current.right, target);
        } else if (current == target) {
            return merge(current.left, current.right);
        } else {
            throw new IllegalStateException("Subscription identity mismatch");
        }

        refresh(current);
        return current;
    }

    private static Entry merge(Entry left, Entry right) {
        if (left == null) return right;
        if (right == null) return left;

        if (higherPriority(left, right)) {
            left.right = merge(left.right, right);
            refresh(left);
            return left;
        }

        right.left = merge(left, right.left);
        refresh(right);
        return right;
    }

    private static void find(Entry current, double frequencyHz, Consumer<FrequencyReceiver> consumer) {
        if (current == null || current.subtreeMaxUpperHz < frequencyHz) {
            return;
        }

        if (current.left != null && current.left.subtreeMaxUpperHz >= frequencyHz) {
            find(current.left, frequencyHz, consumer);
        }

        if (current.lowerHz <= frequencyHz) {
            if (frequencyHz <= current.upperHz) {
                consumer.accept(current.receiver);
            }

            find(current.right, frequencyHz, consumer);
        }
    }

    private static Entry rotateLeft(Entry node) {
        Entry next = node.right;
        node.right = next.left;
        next.left = node;

        refresh(node);
        refresh(next);
        return next;
    }

    private static Entry rotateRight(Entry node) {
        Entry next = node.left;
        node.left = next.right;
        next.right = node;

        refresh(node);
        refresh(next);
        return next;
    }

    private static void refresh(Entry entry) {
        double maximum = entry.upperHz;
        if (entry.left != null) {
            maximum = Math.max(maximum, entry.left.subtreeMaxUpperHz);
        }

        if (entry.right != null) {
            maximum = Math.max(maximum, entry.right.subtreeMaxUpperHz);
        }

        entry.subtreeMaxUpperHz = maximum;
    }

    private static int compare(Entry a, Entry b) {
        int comparison = Double.compare(a.lowerHz, b.lowerHz);
        return comparison != 0 ? comparison : Long.compare(a.sequence, b.sequence);
    }

    private static boolean higherPriority(Entry a, Entry b) {
        return Long.compareUnsigned(a.priority, b.priority) > 0;
    }

    private static long mixedPriority(long value) {
        long z = value + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
