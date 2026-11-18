package com.krimzik.spectra.propagation;

@FunctionalInterface
public interface WaveMediumResolver<M> {
    WaveMedium resolve(M material, double frequencyHz);
}