package com.krimzik.spectra.propagation;

@FunctionalInterface
public interface MaterialPathSampler<C> {
    double factor(C context, double startX, double startY, double startZ, double endX, double endY, double endZ, boolean relativeToAir);
}