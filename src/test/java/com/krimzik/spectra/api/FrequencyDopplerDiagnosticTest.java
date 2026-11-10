package com.krimzik.spectra.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrequencyDopplerDiagnosticTest {
    @Test
    void deadSourceIsReported() {
        ServerLevel level = mock(ServerLevel.class);
        LivingEntity source = mock(LivingEntity.class);

        when(source.isDeadOrDying()).thenReturn(true);

        assertEquals("source died", FrequencyDopplerDiagnostic.sourceAbortReason(source, level));
    }

    @Test
    void removedSourceIsReported() {
        ServerLevel level = mock(ServerLevel.class);
        Entity source = mock(Entity.class);

        when(source.isRemoved()).thenReturn(true);

        assertEquals("source was removed", FrequencyDopplerDiagnostic.sourceAbortReason(source, level));
    }

    @Test
    void sourceChangingLevelsIsReported() {
        ServerLevel level = mock(ServerLevel.class);
        ServerLevel other = mock(ServerLevel.class);
        Entity source = mock(Entity.class);

        when(source.level()).thenReturn(other);

        assertEquals("source left the test level", FrequencyDopplerDiagnostic.sourceAbortReason(source, level));
    }

    @Test
    void healthySourceCanContinue() {
        ServerLevel level = mock(ServerLevel.class);
        Entity source = mock(Entity.class);

        when(source.level()).thenReturn(level);

        assertNull(FrequencyDopplerDiagnostic.sourceAbortReason(source, level));
    }
}
