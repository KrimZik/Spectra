package com.krimzik.spectra.api;

import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.dimension.FrequencyDimensionBridge;
import com.krimzik.spectra.api.registration.FrequencyRegistration;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FrequencyBridgeRegistryTest {
    private MinecraftServer server;
    private FrequencyBridgeRegistry registry;

    @BeforeEach
    void setup() {
        server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        registry = new FrequencyBridgeRegistry(server);
    }

    @Test
    void bridgeRegistrationAndRemoval() {
        FrequencyRegistration registration = registry.register(netherToOverworld());
        assertEquals(1, registry.bridgeCount());
        registration.close();
        assertEquals(0, registry.bridgeCount());
        registration.close();
        assertEquals(0, registry.bridgeCount());
    }

    @Test
    void bridgeDoesNotAutomaticallyWorkInReverse() {
        FrequencyRegistration registration = registry.register(netherToOverworld());

        ServerLevel nether = mock(ServerLevel.class);

        when(nether.getServer()).thenReturn(server);
        when(nether.dimension()).thenReturn(Level.NETHER);

        FrequencyReceiver receiver = mock(FrequencyReceiver.class);

        registry.forEachMatching(nether, receiver, 64.0, (source, emitter, signal, distance, factor) -> fail("Reverse dimension bridge was used"));

        assertEquals(1, registry.bridgeCount());
        registration.close();
    }

    @Test
    void bridgeCannotConnectDimensionToItself() {
        assertThrows(IllegalArgumentException.class, () -> new FrequencyDimensionBridge(Level.OVERWORLD, Level.OVERWORLD, (source, destination, position) -> position, 1.0));
    }

    @Test
    void invalidTransmissionFactorRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FrequencyDimensionBridge(Level.NETHER, Level.OVERWORLD, (source, destination, position) -> position, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new FrequencyDimensionBridge(Level.NETHER, Level.OVERWORLD, (source, destination, position) -> position, 1.1));
    }

    @Test
    void bridgeShutdownClearsRegistrations() {
        FrequencyRegistration registration = registry.register(netherToOverworld());
        registry.shutdown();
        registration.close();
        assertThrows(IllegalStateException.class, registry::bridgeCount);
    }

    private static FrequencyDimensionBridge netherToOverworld() {
        return new FrequencyDimensionBridge(Level.NETHER, Level.OVERWORLD, (source, destination, position) -> position, 1.0);
    }
}