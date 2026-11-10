package com.krimzik.spectra.api.TRx;

import com.krimzik.spectra.api.motion.FrequencyMotion;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface FrequencyReceiver extends FrequencyEndpoint {
    double tunedFrequencyHz();
    double toleranceHz();
}