package com.krimzik.spectra.math;

import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;

import java.util.Objects;

public final class FrequencyDoppler {
    private FrequencyDoppler() {}

    public static double receivedFrequencyHz(double emittedFrequencyHz, double propagationSpeed, FrequencyMotionSnapshot emitter, FrequencyMotionSnapshot receiver) {
        Objects.requireNonNull(emitter);
        Objects.requireNonNull(receiver);

        if (!Double.isFinite(emittedFrequencyHz) || emittedFrequencyHz <= 0.0) {
            throw new IllegalArgumentException("Invalid frequency");
        }

        if (!Double.isFinite(propagationSpeed) || propagationSpeed <= 0.0) {
            throw new IllegalArgumentException("Invalid propagation speed");
        }

        double dx = receiver.x() - emitter.x();
        double dy = receiver.y() - emitter.y();
        double dz = receiver.z() - emitter.z();

        double distance = Math.hypot(Math.hypot(dx, dy), dz);

        if (!Double.isFinite(distance)) {
            throw new IllegalArgumentException("Invalid distance");
        }

        if (distance == 0.0) {
            return emittedFrequencyHz;
        }

        double nx = dx / distance;
        double ny = dy / distance;
        double nz = dz / distance;

        double sourceRadial = emitter.vx() * nx + emitter.vy() * ny + emitter.vz() * nz;
        double receiverRadial = receiver.vx() * nx + receiver.vy() * ny + receiver.vz() * nz;

        if (Math.abs(sourceRadial) >= propagationSpeed || Math.abs(receiverRadial) >= propagationSpeed) {
            throw new IllegalArgumentException("Radial velocity exceeds supported propagation speed");
        }

        double shifted = emittedFrequencyHz * ((propagationSpeed - receiverRadial) / (propagationSpeed - sourceRadial));
        if (!Double.isFinite(shifted) || shifted <= 0.0) {
            throw new IllegalArgumentException("Invalid Doppler result");
        }

        return shifted;
    }
}
