package com.krimzik.spectra.api.material;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MaterialPathOverlayTest {
    private static final ResourceLocation AIR = FrequencyMaterialClassifier.AIR;
    private static final ResourceLocation WATER = FrequencyMaterialClassifier.WATER;
    private static final ResourceLocation STONE = FrequencyMaterialClassifier.STONE;
    private static final ResourceLocation METAL = FrequencyMaterialClassifier.METAL;

    private record Segment(ResourceLocation material, double start, double end) {}

    private static List<Segment> merged(MaterialPathOverlay overlay) {
        List<Segment> result = new ArrayList<>();
        assertTrue(overlay.replay((material, start, end, axes) -> result.add(new Segment(material, start, end))));

        return result;
    }

    @Test
    void shipMaterialReplacesWorldAir() {
        MaterialPathOverlay overlay = new MaterialPathOverlay();
        SubLevelAccess ship = mock(SubLevelAccess.class);

        overlay.accept(AIR, 0.0, 1.0, 0);
        overlay.accept(ship, METAL, 0.25, 0.75, 1);

        assertEquals(List.of(new Segment(AIR, 0.0, 0.25), new Segment(METAL, 0.25, 0.75), new Segment(AIR, 0.75, 1.0)), merged(overlay));
    }

    @Test
    void shipMaterialCanDisplaceWater() {
        MaterialPathOverlay overlay = new MaterialPathOverlay();
        SubLevelAccess ship = mock(SubLevelAccess.class);

        overlay.accept(WATER, 0.0, 1.0, 0);
        overlay.accept(ship, METAL, 0.25, 0.75, 1);

        assertEquals(List.of(new Segment(WATER, 0.0, 0.25), new Segment(METAL, 0.25, 0.75), new Segment(WATER, 0.75, 1.0)), merged(overlay));
    }

    @Test
    void identicalOverlappingMaterialsAreMerged() {
        MaterialPathOverlay overlay = new MaterialPathOverlay();
        SubLevelAccess ship = mock(SubLevelAccess.class);

        overlay.accept(AIR, 0.0, 0.25, 0);
        overlay.accept(STONE, 0.25, 0.75, 1);
        overlay.accept(AIR, 0.75, 1.0, 1);
        overlay.accept(ship, STONE, 0.40, 0.60, 1);

        assertEquals(List.of(new Segment(AIR, 0.0, 0.25), new Segment(STONE, 0.25, 0.75), new Segment(AIR, 0.75, 1.0)), merged(overlay));
    }

    @Test
    void conflictingSolidOverlapFailsWithoutCallbacks() {
        MaterialPathOverlay overlay = new MaterialPathOverlay();
        SubLevelAccess ship = mock(SubLevelAccess.class);

        overlay.accept(AIR, 0.0, 0.25, 0);
        overlay.accept(STONE, 0.25, 0.75, 1);
        overlay.accept(AIR, 0.75, 1.0, 1);
        overlay.accept(ship, METAL, 0.40, 0.60, 1);

        List<Segment> result = new ArrayList<>();

        assertFalse(overlay.replay((material, start, end, axes) -> result.add(new Segment(material, start, end))));

        assertTrue(result.isEmpty());
    }

    @Test
    void overlappingDifferentShipsAreRejected() {
        MaterialPathOverlay overlay = new MaterialPathOverlay();

        SubLevelAccess first = mock(SubLevelAccess.class);
        SubLevelAccess second = mock(SubLevelAccess.class);

        overlay.accept(AIR, 0.0, 1.0, 0);
        overlay.accept(first, STONE, 0.2, 0.7, 1);
        overlay.accept(second, METAL, 0.5, 0.9, 1);

        assertFalse(overlay.replay((material, start, end, axes) -> fail("Unexpected partial result")));
    }

    @Test
    void workspaceResetDiscardsPreviousShipGeometry() {
        MaterialPathOverlay overlay = new MaterialPathOverlay();
        SubLevelAccess ship = mock(SubLevelAccess.class);

        overlay.accept(AIR, 0.0, 1.0, 0);
        overlay.accept(ship, METAL, 0.25, 0.75, 1);
        assertEquals(3, merged(overlay).size());

        overlay.reset();
        overlay.accept(AIR, 0.0, 1.0, 0);

        assertEquals(List.of(new Segment(AIR, 0.0, 1.0)), merged(overlay));
    }

    @Test
    void mergedShipUsesExactAttenuationInsteadOfWorldAir() {
        MaterialPathOverlay overlay = new MaterialPathOverlay();
        SubLevelAccess ship = mock(SubLevelAccess.class);

        overlay.acceptWithLoss(AIR, 0.0, 1.0, 0, 0.0);
        overlay.accept(ship, METAL, 0.25, 0.75, 1, 10.0);

        List<Double> losses = new ArrayList<>();

        FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer =
                new FrequencyMaterialAttenuation.MaterialSegmentConsumer() {
                    @Override
                    public void accept(ResourceLocation material, double start, double end, int axes) {
                        fail("Missing attenuation");
                    }

                    @Override
                    public void acceptWithLoss(ResourceLocation material, double start, double end, int axes, double dbPerMeter) {
                        losses.add(dbPerMeter);
                    }
                };

        assertTrue(overlay.replayWithLoss(consumer));
        assertEquals(List.of(0.0, 10.0, 0.0), losses);
    }

    @Test
    void differentAttenuationWithinSameMaterialRemainsSeparate() {
        MaterialPathOverlay overlay = new MaterialPathOverlay();

        overlay.acceptWithLoss(STONE, 0.0, 0.5, 0, 3.0);
        overlay.acceptWithLoss(STONE, 0.5, 1.0, 1, 10.0);

        List<Double> losses = new ArrayList<>();

        FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer =
                new FrequencyMaterialAttenuation.MaterialSegmentConsumer() {
                    @Override
                    public void accept(ResourceLocation material, double start, double end, int axes) {
                        fail("Missing attenuation");
                    }

                    @Override
                    public void acceptWithLoss(ResourceLocation material, double start, double end, int axes, double dbPerMeter) {
                        losses.add(dbPerMeter);
                    }
                };

        assertTrue(overlay.replayWithLoss(consumer));
        assertEquals(List.of(3.0, 10.0), losses);
    }
}