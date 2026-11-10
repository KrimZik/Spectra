package com.krimzik.spectra.api.registration;

@FunctionalInterface
public interface FrequencyRegistration extends AutoCloseable {
    @Override
    void close();
}
