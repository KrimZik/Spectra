package com.krimzik.spectra.api.material;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Objects;

public final class MaterialPathOverlay implements FrequencyMaterialAttenuation.MaterialSegmentConsumer, FrequencySableMaterialAttenuation.ShipMaterialSegmentConsumer, FrequencySableMaterialAttenuation.ShipLossSegmentConsumer {
    private static final int MAX_SEGMENTS = 2048;

    private ResourceLocation[] materials = new ResourceLocation[16];
    private double[] starts = new double[16];
    private double[] ends = new double[16];
    private int[] axes = new int[16];
    private double[] losses = new double[16];
    private boolean[] ships = new boolean[16];

    private int[] startNext = new int[16];
    private int[] endNext = new int[16];
    private int[] activePrevious = new int[16];
    private int[] activeNext = new int[16];

    private double[] cuts = new double[34];
    private int[] startHeads = new int[34];
    private int[] endHeads = new int[34];

    private ResourceLocation[] resultMaterials = new ResourceLocation[16];
    private double[] resultStarts = new double[16];
    private double[] resultEnds = new double[16];
    private int[] resultAxes = new int[16];
    private double[] resultLosses = new double[16];

    private int count;
    private int resultCount;
    private boolean overflow;

    public void reset() {
        count = 0;
        resultCount = 0;
        overflow = false;
    }

    @Override
    public void accept(ResourceLocation material, double startFraction, double endFraction, int enteredAxes) {
        add(material, startFraction, endFraction, enteredAxes, false, Double.NaN);
    }

    @Override
    public void acceptWithLoss(ResourceLocation material, double startFraction, double endFraction, int enteredAxes, double decibelsPerMeter) {
        add(material, startFraction, endFraction, enteredAxes, false, decibelsPerMeter);
    }

    @Override
    public void accept(SubLevelAccess sublevel, ResourceLocation material, double startFraction, double endFraction, int enteredAxes) {
        Objects.requireNonNull(sublevel);

        if (!FrequencyMaterialClassifier.AIR.equals(material)) {
            add(material, startFraction, endFraction, 0, true, Double.NaN);
        }
    }

    @Override
    public void accept(SubLevelAccess sublevel, ResourceLocation material, double startFraction, double endFraction, int enteredAxes, double decibelsPerMeter) {
        Objects.requireNonNull(sublevel);

        if (!FrequencyMaterialClassifier.AIR.equals(material)) {
            add(material, startFraction, endFraction, 0, true, decibelsPerMeter);
        }
    }

    void addSnapshotShip(ResourceLocation material, double start, double end, double decibelsPerMeter) {
        if (!FrequencyMaterialClassifier.AIR.equals(material)) {
            add(material, start, end, 0, true, decibelsPerMeter);
        }
    }

    private void add(ResourceLocation material, double start, double end, int enteredAxes, boolean ship, double decibelsPerMeter) {
        Objects.requireNonNull(material);

        if (!Double.isNaN(decibelsPerMeter) && (!Double.isFinite(decibelsPerMeter) || decibelsPerMeter < 0.0)) {
            throw new IllegalArgumentException("Invalid material attenuation");
        }

        if (!Double.isFinite(start) || !Double.isFinite(end) || start < -1.0e-12 || end > 1.0 + 1.0e-12 || end < start) {
            throw new IllegalArgumentException("Invalid material interval");
        }

        start = Math.max(0.0, start);
        end = Math.min(1.0, end);

        if (end <= start || overflow) {
            return;
        }

        if (count == MAX_SEGMENTS) {
            overflow = true;
            return;
        }

        if (count == materials.length) {
            int capacity = materials.length << 1;

            materials = Arrays.copyOf(materials, capacity);
            starts = Arrays.copyOf(starts, capacity);
            ends = Arrays.copyOf(ends, capacity);
            axes = Arrays.copyOf(axes, capacity);
            losses = Arrays.copyOf(losses, capacity);
            ships = Arrays.copyOf(ships, capacity);
            startNext = Arrays.copyOf(startNext, capacity);
            endNext = Arrays.copyOf(endNext, capacity);
            activePrevious = Arrays.copyOf(activePrevious, capacity);
            activeNext = Arrays.copyOf(activeNext, capacity);
        }

        materials[count] = material;
        starts[count] = start;
        ends[count] = end;
        axes[count] = enteredAxes;
        ships[count] = ship;
        losses[count] = decibelsPerMeter;
        count++;
    }

    private static final FrequencyMaterialAttenuation.MaterialSegmentConsumer DISCARD = (material, start, end, axes) -> {};

    public boolean replayWithLoss(FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
        Objects.requireNonNull(consumer);

        if (!replay(DISCARD)) {
            return false;
        }

        for (int i = 0; i < resultCount; i++) {
            if (!Double.isFinite(resultLosses[i])) {
                return false;
            }
        }

        for (int i = 0; i < resultCount; i++) {
            consumer.acceptWithLoss(resultMaterials[i], resultStarts[i], resultEnds[i], resultAxes[i], resultLosses[i]);
        }

        return true;
    }

    public boolean replay(FrequencyMaterialAttenuation.MaterialSegmentConsumer consumer) {
        Objects.requireNonNull(consumer);

        if (overflow) {
            return false;
        }

        int totalCuts = (count << 1) + 2;

        if (cuts.length < totalCuts) {
            cuts = new double[totalCuts];
            startHeads = new int[totalCuts];
            endHeads = new int[totalCuts];
        }

        cuts[0] = 0.0;
        cuts[1] = 1.0;

        for (int i = 0; i < count; i++) {
            cuts[2 + i * 2] = starts[i];
            cuts[3 + i * 2] = ends[i];
        }

        Arrays.sort(cuts, 0, totalCuts);

        int unique = 0;

        for (int i = 0; i < totalCuts; i++) {
            if (unique == 0 || cuts[i] > cuts[unique - 1]) {
                cuts[unique++] = cuts[i];
            }
        }

        Arrays.fill(startHeads, 0, unique, -1);
        Arrays.fill(endHeads, 0, unique, -1);

        for (int i = 0; i < count; i++) {
            int startIndex = Arrays.binarySearch(cuts, 0, unique, starts[i]);
            int endIndex = Arrays.binarySearch(cuts, 0, unique, ends[i]);

            startNext[i] = startHeads[startIndex];
            endNext[i] = endHeads[endIndex];

            startHeads[startIndex] = i;
            endHeads[endIndex] = i;
        }

        int worldHead = -1;
        int shipHead = -1;
        resultCount = 0;

        for (int i = 0; i < unique - 1; i++) {
            for (int j = endHeads[i]; j != -1; j = endNext[j]) {
                int previous = activePrevious[j];
                int next = activeNext[j];

                if (previous == -1) {
                    if (ships[j]) {
                        shipHead = next;
                    } else {
                        worldHead = next;
                    }
                } else {
                    activeNext[previous] = next;
                }

                if (next != -1) {
                    activePrevious[next] = previous;
                }
            }

            for (int j = startHeads[i]; j != -1; j = startNext[j]) {
                if (ships[j]) {
                    if (shipHead != -1 && (!materials[j].equals(materials[shipHead]) || (Double.isFinite(losses[j]) && Double.isFinite(losses[shipHead]) && Double.compare(losses[j], losses[shipHead]) != 0))) {
                        return false;
                    }

                    activePrevious[j] = -1;
                    activeNext[j] = shipHead;

                    if (shipHead != -1) {
                        activePrevious[shipHead] = j;
                    }

                    shipHead = j;
                } else {
                    if (worldHead != -1) {
                        return false;
                    }

                    activePrevious[j] = -1;
                    activeNext[j] = worldHead;
                    worldHead = j;
                }
            }

            ResourceLocation world = worldHead == -1 ? FrequencyMaterialClassifier.AIR : materials[worldHead];
            ResourceLocation ship = shipHead == -1 ? null : materials[shipHead];

            if (ship != null && !world.equals(FrequencyMaterialClassifier.AIR) && !world.equals(FrequencyMaterialClassifier.WATER) && !world.equals(ship)) {
                return false;
            }

            ResourceLocation material = ship != null ? ship : world;
            int entryAxes = ship != null ? 0 : worldHead == -1 ? 0 : axes[worldHead];
            double materialLoss = ship != null ? losses[shipHead] : worldHead == -1 ? Double.NaN : losses[worldHead];

            if (ship != null && worldHead != -1 && world.equals(ship) && Double.isFinite(losses[worldHead]) && Double.isFinite(losses[shipHead]) && Double.compare(losses[worldHead], losses[shipHead]) != 0) {
                return false;
            }

            double from = cuts[i];
            double to = cuts[i + 1];

            if (resultCount != 0 && resultMaterials[resultCount - 1].equals(material) && Double.compare(resultLosses[resultCount - 1], materialLoss) == 0) {
                resultEnds[resultCount - 1] = to;
            } else {
                if (resultCount == resultMaterials.length) {
                    int capacity = resultMaterials.length << 1;

                    resultMaterials = Arrays.copyOf(resultMaterials, capacity);
                    resultStarts = Arrays.copyOf(resultStarts, capacity);
                    resultEnds = Arrays.copyOf(resultEnds, capacity);
                    resultAxes = Arrays.copyOf(resultAxes, capacity);
                    resultLosses = Arrays.copyOf(resultLosses, capacity);
                }

                resultMaterials[resultCount] = material;
                resultStarts[resultCount] = from;
                resultEnds[resultCount] = to;
                resultAxes[resultCount] = entryAxes;
                resultLosses[resultCount] = materialLoss;
                resultCount++;
            }
        }

        for (int i = 0; i < resultCount; i++) {
            consumer.accept(resultMaterials[i], resultStarts[i], resultEnds[i], resultAxes[i]);
        }

        return true;
    }
}