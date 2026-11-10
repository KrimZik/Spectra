package com.krimzik.spectra.api.motion;

public record FrequencyMotionSnapshot(double x, double y, double z, double vx, double vy, double vz) {
    public FrequencyMotionSnapshot {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)) {
            throw new IllegalArgumentException("Position and velocity must be finite");
        }
    }
}
