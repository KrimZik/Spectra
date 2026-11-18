package com.krimzik.spectra.propagation.geometry;

import com.krimzik.spectra.math.FrequencyPropagation;

public final class PropagationPathGeometry {
    private double currentX;
    private double currentY;
    private double currentZ;
    private double distanceMeters;
    private int segmentCount;

    public PropagationPathGeometry(double startX, double startY, double startZ) {
        reset(startX, startY, startZ);
    }

    public void reset(double startX, double startY, double startZ) {
        validatePoint(startX, startY, startZ);

        currentX = startX;
        currentY = startY;
        currentZ = startZ;
        distanceMeters = 0.0;
        segmentCount = 0;
    }

    public double append(double endX, double endY, double endZ) {
        validatePoint(endX, endY, endZ);

        double dx = endX - currentX;
        double dy = endY - currentY;
        double dz = endZ - currentZ;
        double segmentMeters = Math.hypot(Math.hypot(dx, dy), dz);

        if (!Double.isFinite(segmentMeters)) {
            throw new IllegalArgumentException("Invalid segment length");
        }

        double total = FrequencyPropagation.appendPathDistance(distanceMeters, segmentMeters);
        if (!Double.isFinite(total)) {
            throw new IllegalArgumentException("Path distance overflow");
        }

        if (segmentMeters > 0.0) {
            currentX = endX;
            currentY = endY;
            currentZ = endZ;
            distanceMeters = total;
            segmentCount++;
        }

        return segmentMeters;
    }

    public double distanceMeters() {
        return distanceMeters;
    }

    public int segmentCount() {
        return segmentCount;
    }

    public double currentX() {
        return currentX;
    }

    public double currentY() {
        return currentY;
    }

    public double currentZ() {
        return currentZ;
    }

    private static void validatePoint(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Invalid path coordinates");
        }
    }
}