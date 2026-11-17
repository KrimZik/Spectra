package com.krimzik.spectra.propagation;

@FunctionalInterface
public interface PathAttenuation<P> {
    double factor(P start, P end);
}
