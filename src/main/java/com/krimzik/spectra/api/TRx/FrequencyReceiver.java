package com.krimzik.spectra.api.TRx;

import com.krimzik.spectra.api.motion.FrequencyMotion;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface FrequencyReceiver {
    Vec3 position();
    double tunedFrequencyHz();
    double toleranceHz();

    default Vec3 additionalWorldVelocityMetersPerSecond() {
        return Vec3.ZERO;
    }

    default FrequencyMotionSnapshot motionSnapshot(ServerLevel level) {
        return FrequencyMotion.capture(level, position(), additionalWorldVelocityMetersPerSecond());
    }
}