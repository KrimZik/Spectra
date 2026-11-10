package com.krimzik.spectra.api.consumers;

import com.krimzik.spectra.api.FrequencySignal;
import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import net.minecraft.server.level.ServerLevel;

@FunctionalInterface
public interface FrequencyBridgedMatchConsumer {
    void accept(ServerLevel sourceLevel, FrequencyEmitter emitter, FrequencySignal signal, double distanceSquaredMeters, double transmissionFactor);
}
