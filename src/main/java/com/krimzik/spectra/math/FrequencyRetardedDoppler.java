package com.krimzik.spectra.math;

import com.krimzik.spectra.api.motion.FrequencyMotionSnapshot;
import java.util.Objects;

/** World-space, constant-acceleration retarded-time Doppler model. */
public final class FrequencyRetardedDoppler {
    private FrequencyRetardedDoppler() {}
    public record Result(double receivedFrequencyHz, double travelTimeSeconds) {}

    public static Result calculate(double transmittedFrequencyHz, double propagationSpeed, FrequencyMotionSnapshot sourceNow, FrequencyMotionSnapshot receiverNow, double sourceAx, double sourceAy, double sourceAz) {
        Objects.requireNonNull(sourceNow, "sourceNow");
        Objects.requireNonNull(receiverNow, "receiverNow");

        double c = propagationSpeed;
        if (!(transmittedFrequencyHz > 0.0) || !Double.isFinite(transmittedFrequencyHz) || !(c > 0.0) || !Double.isFinite(c) || !Double.isFinite(sourceAx) || !Double.isFinite(sourceAy) || !Double.isFinite(sourceAz)) {
            throw new IllegalArgumentException("Invalid frequency, propagation speed, or acceleration");
        }

        double dx = receiverNow.x() - sourceNow.x();
        double dy = receiverNow.y() - sourceNow.y();
        double dz = receiverNow.z() - sourceNow.z();
        double vx = sourceNow.vx();
        double vy = sourceNow.vy();
        double vz = sourceNow.vz();
        double rvx = receiverNow.vx();
        double rvy = receiverNow.vy();
        double rvz = receiverNow.vz();

        double distance = Math.hypot(Math.hypot(dx, dy), dz);
        double sourceSpeed = Math.hypot(Math.hypot(vx, vy), vz);
        double receiverSpeed = Math.hypot(Math.hypot(rvx, rvy), rvz);

        if (!(distance > 0.0) || !Double.isFinite(distance) || !Double.isFinite(sourceSpeed) || sourceSpeed >= c || !Double.isFinite(receiverSpeed) || receiverSpeed >= c) {
            throw new IllegalArgumentException("Undefined LOS or speed at/above propagation speed");
        }

        double dot = Math.fma(dx, vx, Math.fma(dy, vy, dz * vz));
        double denominator = Math.fma(-sourceSpeed / c, sourceSpeed / c, 1.0);
        double scaledDot = dot / c;
        double discriminant = Math.fma(scaledDot, scaledDot, denominator * distance * distance);
        double tau = (scaledDot + Math.sqrt(discriminant)) / (c * denominator);

        if (!Double.isFinite(tau) || tau <= 0.0) {
            throw new IllegalArgumentException("Invalid retarded travel time");
        }

        if (sourceAx != 0.0 || sourceAy != 0.0 || sourceAz != 0.0) {
            boolean converged = false;
            for (int iteration = 0; iteration < 20; iteration++) {
                double halfTauSquared = 0.5 * tau * tau;
                double qx = Math.fma(-sourceAx, halfTauSquared, Math.fma(vx, tau, dx));
                double qy = Math.fma(-sourceAy, halfTauSquared, Math.fma(vy, tau, dy));
                double qz = Math.fma(-sourceAz, halfTauSquared, Math.fma(vz, tau, dz));
                double range = Math.hypot(Math.hypot(qx, qy), qz);
                double error = Math.fma(-c, tau, range);
                if (Math.abs(error) <= 1.0e-10 * Math.max(1.0, range)) {
                    converged = true;
                    break;
                }
                double ux = Math.fma(-sourceAx, tau, vx);
                double uy = Math.fma(-sourceAy, tau, vy);
                double uz = Math.fma(-sourceAz, tau, vz);
                double slope = Math.fma(qx, ux, Math.fma(qy, uy, qz * uz)) / range - c;
                if (!Double.isFinite(slope) || slope >= 0.0) {
                    throw new IllegalArgumentException("No stable subsonic retarded-time root");
                }
                double next = tau - error / slope;
                tau = next > 0.0 && Double.isFinite(next) ? next : tau * 0.5;
            }
            if (!converged) {
                throw new IllegalArgumentException("Retarded-time solver did not converge");
            }
        }

        double halfTauSquared = 0.5 * tau * tau;
        double qx = Math.fma(-sourceAx, halfTauSquared, Math.fma(vx, tau, dx));
        double qy = Math.fma(-sourceAy, halfTauSquared, Math.fma(vy, tau, dy));
        double qz = Math.fma(-sourceAz, halfTauSquared, Math.fma(vz, tau, dz));
        double range = Math.hypot(Math.hypot(qx, qy), qz);
        double emitVx = Math.fma(-sourceAx, tau, vx);
        double emitVy = Math.fma(-sourceAy, tau, vy);
        double emitVz = Math.fma(-sourceAz, tau, vz);
        double emitSpeed = Math.hypot(Math.hypot(emitVx, emitVy), emitVz);

        if (!Double.isFinite(range) || !(range > 0.0) || !(emitSpeed < c)) {
            throw new IllegalArgumentException("Invalid source state at emission time");
        }

        double sourceRadial = Math.fma(qx, emitVx, Math.fma(qy, emitVy, qz * emitVz)) / range;
        double receiverRadial = Math.fma(qx, rvx, Math.fma(qy, rvy, qz * rvz)) / range;
        double received = transmittedFrequencyHz * ((c - receiverRadial) / (c - sourceRadial));
        if (!(received > 0.0) || !Double.isFinite(received)) {
            throw new IllegalArgumentException("Invalid received frequency");
        }
        return new Result(received, tau);
    }
}
