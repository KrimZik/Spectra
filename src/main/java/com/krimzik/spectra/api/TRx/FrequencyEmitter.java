package com.krimzik.spectra.api.TRx;

import com.krimzik.spectra.api.FrequencySignal;
import com.krimzik.spectra.api.motion.FrequencyMotion;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface FrequencyEmitter extends FrequencyEndpoint {
    FrequencySignal signal();
    default boolean isEmitting() {
        return true;
    }
}