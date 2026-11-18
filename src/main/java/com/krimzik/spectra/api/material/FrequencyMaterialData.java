package com.krimzik.spectra.api.material;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;

public final class FrequencyMaterialData extends SimpleJsonResourceReloadListener {
    public FrequencyMaterialData() {
        super(new Gson(), "spectra/materials");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        List<FrequencyMaterialRules.Rule> rules = new ArrayList<>();
        Map<ResourceLocation, FrequencyMaterialRules.Properties> definitions = new HashMap<>();

        for (var entry : resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {

            JsonObject object = entry.getValue().getAsJsonObject();

            boolean hasBlock = object.has("block");
            boolean hasTag = object.has("tag");

            if (hasBlock && hasTag) {
                throw new IllegalArgumentException("Expected one block or tag in " + entry.getKey());
            }

            ResourceLocation material = object.has("material") ? ResourceLocation.parse(object.get("material").getAsString()) : null;

            Double loss = optionalNumber(object, "db_loss");
            Double speed = optionalNumber(object, "speed_mps");
            Double impedance = optionalNumber(object, "impedance");

            if (material == null && (speed != null || impedance != null)) {
                throw new IllegalArgumentException("Wave properties require a material in " + entry.getKey());
            }

            if (!hasBlock && !hasTag && material == null) {
                throw new IllegalArgumentException("Missing material or block/tag in " + entry.getKey());
            }

            if (material != null && (loss != null || speed != null || impedance != null)) {
                FrequencyMaterialRules.Properties properties = new FrequencyMaterialRules.Properties(loss, speed, impedance);
                definitions.merge(material, properties, FrequencyMaterialRules.Properties::merge);
            }

            if (hasBlock || hasTag) {
                if (material == null && loss == null) {
                    throw new IllegalArgumentException("Material assignment has no properties in " + entry.getKey());
                }

                ResourceLocation id = ResourceLocation.parse(object.get(hasBlock ? "block" : "tag").getAsString());
                rules.add(hasBlock ? new FrequencyMaterialRules.Rule(id, null, material, material == null ? loss : null) : new FrequencyMaterialRules.Rule(null, TagKey.create(Registries.BLOCK, id), material, material == null ? loss : null));
            }
        }

        FrequencyMaterialRules.replaceMaterials(rules, definitions);
    }

    private static Double optionalNumber(JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsDouble() : null;
    }
}