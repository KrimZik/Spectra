package com.krimzik.spectra.propagation;

import com.krimzik.spectra.math.FrequencyPropagation;
import com.krimzik.spectra.math.MaterialAttenuationMath;
import com.krimzik.spectra.math.WaveBoundaryMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaveMediumTest {
    private static final double EPSILON = 1.0e-12;

    @Test
    void mediumPropertiesComposeWithExistingPropagationMath() {
        WaveMedium first = new WaveMedium(200.0, 1.0, 0.5);
        WaveMedium second = new WaveMedium(400.0, 3.0, 2.0);

        double travelTime = FrequencyPropagation.travelTimeSeconds(3.0, first.speedMetersPerSecond());
        travelTime += FrequencyPropagation.travelTimeSeconds(2.0, second.speedMetersPerSecond());

        double loss = MaterialAttenuationMath.accumulateLoss(0.0, first.lossDbPerMeter(), 3.0);
        loss = MaterialAttenuationMath.accumulateLoss(loss, second.lossDbPerMeter(), 2.0);

        assertEquals(3.0 / 200.0 + 2.0 / 400.0, travelTime, EPSILON);
        assertEquals(5.5, loss, EPSILON);
        assertEquals(0.25, WaveBoundaryMath.reflectance(first.impedance(), second.impedance()), EPSILON);
        assertEquals(0.75, WaveBoundaryMath.transmittance(first.impedance(), second.impedance()), EPSILON);
    }

    @Test
    void independentModelsCanResolveTheSameMaterialDifferently() {
        WaveMedium first = new WaveMedium(200.0, 1.0, 0.5);
        WaveMedium second = new WaveMedium(400.0, 3.0, 2.0);

        WaveMediumResolver<String> firstModel = (material, frequencyHz) -> first;
        WaveMediumResolver<String> secondModel = (material, frequencyHz) -> second;

        assertSame(first, firstModel.resolve("stone", 100.0));
        assertSame(second, secondModel.resolve("stone", 100.0));
    }

    @Test
    void invalidMediumPropertiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WaveMedium(0.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new WaveMedium(200.0, -1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new WaveMedium(200.0, 1.0, -0.5));
        assertThrows(IllegalArgumentException.class, () -> new WaveMedium(Double.NaN, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new WaveMedium(200.0, Double.POSITIVE_INFINITY, 0.0));
    }
}