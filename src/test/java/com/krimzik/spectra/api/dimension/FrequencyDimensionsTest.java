package com.krimzik.spectra.api.dimension;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FrequencyDimensionsTest {
    private static final double EPSILON = 1.0e-9;

    private static ServerLevel dimension(double coordinateScale) {
        ServerLevel level = mock(ServerLevel.class);
        DimensionType type = mock(DimensionType.class);

        when(type.coordinateScale()).thenReturn(coordinateScale);
        when(level.dimensionType()).thenReturn(type);

        return level;
    }

    @Test
    void sameDimensionDoesNotTransform() {
        ServerLevel overworld = dimension(1.0);
        Vec3 position = new Vec3(800, 64, 400);

        Vec3 result = FrequencyDimensions.scalePosition(overworld, overworld, position);

        assertSame(position, result);
        assertTrue(FrequencyDimensions.sameDimension(overworld, overworld));
    }

    @Test
    void overworldToNether() {
        ServerLevel overworld = dimension(1.0);
        ServerLevel nether = dimension(8.0);

        Vec3 result = FrequencyDimensions.scalePosition(overworld, nether, new Vec3(800, 64, 400));

        assertEquals(100.0, result.x, EPSILON);
        assertEquals(64.0, result.y, EPSILON);
        assertEquals(50.0, result.z, EPSILON);
    }

    @Test
    void netherToOverworld() {
        ServerLevel overworld = dimension(1.0);
        ServerLevel nether = dimension(8.0);

        Vec3 result = FrequencyDimensions.scalePosition(nether, overworld, new Vec3(100, 64, 50));

        assertEquals(800.0, result.x, EPSILON);
        assertEquals(64.0, result.y, EPSILON);
        assertEquals(400.0, result.z, EPSILON);
    }

    @Test
    void moddedDimensionScale() {
        ServerLevel source = dimension(3.0);
        ServerLevel destination = dimension(2.0);

        Vec3 result = FrequencyDimensions.scalePosition(source, destination, new Vec3(100, 70, 200));

        assertEquals(150.0, result.x, EPSILON);
        assertEquals(70.0, result.y, EPSILON);
        assertEquals(300.0, result.z, EPSILON);
    }

    @Test
    void differentDimensionsRemainDistinct() {
        ServerLevel overworld = dimension(1.0);
        ServerLevel other = dimension(1.0);
        assertFalse(FrequencyDimensions.sameDimension(overworld, other));
    }
}
