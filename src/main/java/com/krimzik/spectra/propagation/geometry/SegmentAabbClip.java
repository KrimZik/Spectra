package com.krimzik.spectra.propagation.geometry;

public final class SegmentAabbClip {
    private SegmentAabbClip() {}

    public static final class Interval {
        private double enter;
        private double exit;

        public double enter() {
            return enter;
        }

        public double exit() {
            return exit;
        }

        public double length() {
            return exit - enter;
        }
    }

    public static boolean clip(double startX, double startY, double startZ, double endX, double endY, double endZ, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Interval result) {
        result.enter = 0.0;
        result.exit = 1.0;

        if (!clipAxis(startX, endX - startX, minX, maxX, result)) {
            return false;
        }

        if (!clipAxis(startY, endY - startY, minY, maxY, result)) {
            return false;
        }

        if (!clipAxis(startZ, endZ - startZ, minZ, maxZ, result)) {
            return false;
        }

        return result.exit > result.enter;
    }

    private static boolean clipAxis(double origin, double direction, double min, double max, Interval result) {
        if (direction == 0.0) {
            return origin >= min && origin <= max;
        }

        double enter = (min - origin) / direction;
        double exit = (max - origin) / direction;

        if (enter > exit) {
            double swap = enter;
            enter = exit;
            exit = swap;
        }

        result.enter = Math.max(result.enter, enter);
        result.exit = Math.min(result.exit, exit);

        return result.enter <= result.exit;
    }
}