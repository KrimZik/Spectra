package com.krimzik.spectra.api.TRx;

import com.krimzik.spectra.api.motion.FrequencyMotion;
import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface FrequencyEndpoint {
    Vec3 position();

    default Vec3 additionalWorldVelocityMetersPerSecond() {
        return Vec3.ZERO;
    }

    default FrequencyMotionSnapshot motionSnapshot(ServerLevel level) {
        return FrequencyMotion.capture(level, position(), additionalWorldVelocityMetersPerSecond());
    }
}
