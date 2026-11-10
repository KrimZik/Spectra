package com.krimzik.spectra.api.consumers;

import com.krimzik.spectra.api.FrequencySignal;
import com.krimzik.spectra.api.TRx.FrequencyEmitter;

@FunctionalInterface
public interface FrequencyDopplerMatchConsumer {
    void accept(FrequencyEmitter emitter, FrequencySignal signal, double distanceSquaredMeters, double receivedFrequencyHz);
}
