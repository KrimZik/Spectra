package com.krimzik.spectra.math;

public final class FrequencyMath {
    public static final double TWO_PI = 2.0 * Math.PI;
    public static final double TICKS_PER_SECOND = 20.0;

    private FrequencyMath() {}

    public static double angularFrequency(double hz) {
        return TWO_PI * hz;
    }

    public static double period(double hz) {
        if (hz <= 0.0 || !Double.isFinite(hz)) {
            throw new IllegalArgumentException("Frequency must be finite and positive");
        }
        return 1.0 / hz;
    }

    public static double cyclesPerTick(double hz) {
        return hz / TICKS_PER_SECOND;
    }

    public static double phase(double hz, double timeSeconds, double initialPhase) {
        double cycles = Math.fma(hz, timeSeconds, initialPhase / TWO_PI);
        double fractional = cycles - Math.floor(cycles);
        return fractional * TWO_PI;
    }

    public static double sampleSine(double hz, double amplitude, double initialPhase, double timeSeconds) {
        return amplitude * Math.sin(phase(hz, timeSeconds, initialPhase));
    }

    public static double phaseAtTick(double hz, long tick, double initialPhase) {
        return phaseAtTick(hz, tick, initialPhase, 0.0);
    }

    public static double phaseAtTick(double hz, long tick, double initialPhase, double delaySeconds) {
        if (!Double.isFinite(hz) || hz <= 0.0) {
            throw new IllegalArgumentException("Invalid frequency");
        }

        if (tick < 0) {
            throw new IllegalArgumentException("Invalid tick");
        }

        if (!Double.isFinite(initialPhase)) {
            throw new IllegalArgumentException("Invalid initial phase");
        }

        if (!Double.isFinite(delaySeconds) || delaySeconds < 0.0) {
            throw new IllegalArgumentException("Invalid propagation delay");
        }

        double tickCycles = tickCycleFraction(hz, tick);
        double delayCycles = delaySeconds == 0.0 ? 0.0 : fractionalProduct(hz, delaySeconds);
        double initialCycles = fractionalCycles((initialPhase % TWO_PI) / TWO_PI);
        double cycles = fractionalCycles(tickCycles - delayCycles + initialCycles);

        return cycles * TWO_PI;
    }

    public static double phaseAdvanceRadians(double hz, double durationSeconds) {
        if (!Double.isFinite(hz) || hz < 0.0) {
            throw new IllegalArgumentException("Invalid frequency");
        }

        if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0) {
            throw new IllegalArgumentException("Invalid duration");
        }

        if (hz == 0.0 || durationSeconds == 0.0) {
            return 0.0;
        }

        return fractionalProduct(hz, durationSeconds) * TWO_PI;
    }

    public static double sampleSineAtTick(double hz, double amplitude, double initialPhase, long tick, double delaySeconds) {
        return amplitude * Math.sin(phaseAtTick(hz, tick, initialPhase, delaySeconds));
    }

    private static double tickCycleFraction(double hz, long tick) {
        double remainderHz = hz % TICKS_PER_SECOND;

        if (tick <= (1L << 53)) {
            double product = remainderHz * (double) tick;
            double error = Math.fma(remainderHz, (double) tick, -product);

            return fractionalCycles(((product % TICKS_PER_SECOND) + error) / TICKS_PER_SECOND);
        }

        double accumulated = 0.0;
        double step = remainderHz;
        long remaining = tick;

        while (remaining != 0L) {
            if ((remaining & 1L) != 0L) {
                accumulated = (accumulated + step) % TICKS_PER_SECOND;
            }

            step = (step + step) % TICKS_PER_SECOND;
            remaining >>>= 1;
        }

        return accumulated / TICKS_PER_SECOND;
    }

    private static double fractionalProduct(double a, double b) {
        double product = a * b;

        if (!Double.isFinite(product)) {
            throw new IllegalArgumentException("Phase multiplication overflow");
        }

        double error = Math.fma(a, b, -product);

        return fractionalCycles((product % 1.0) + (error % 1.0));
    }

    private static double fractionalCycles(double cycles) {
        return cycles - Math.floor(cycles);
    }

    public static double wavelength(double hz, double propagationSpeed) {
        if (!Double.isFinite(hz) || hz <= 0.0) {
            throw new IllegalArgumentException("Frequency must be finite and positive");
        }

        if (!Double.isFinite(propagationSpeed) || propagationSpeed <= 0.0) {
            throw new IllegalArgumentException("Propagation speed must be finite and positive");
        }

        return propagationSpeed / hz;
    }
}
