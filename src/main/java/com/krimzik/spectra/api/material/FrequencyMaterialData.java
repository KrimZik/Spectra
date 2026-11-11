package com.krimzik.spectra.api.material;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class FrequencyMaterialData extends SimpleJsonResourceReloadListener {
    public FrequencyMaterialData() {
        super(new Gson(), "spectra/materials");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        List<FrequencyMaterialRules.Rule> rules = new ArrayList<>();

        resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .forEach(entry -> {
                    JsonObject object = entry.getValue().getAsJsonObject();
                    double loss = object.get("db_loss").getAsDouble();

                    boolean hasBlock = object.has("block");
                    boolean hasTag = object.has("tag");

                    if (hasBlock == hasTag) {
                        throw new IllegalArgumentException("Expected exactly one block or tag in " + entry.getKey());
                    }

                    ResourceLocation id = ResourceLocation.parse(object.get(hasBlock ? "block" : "tag").getAsString());

                    rules.add(hasBlock ? FrequencyMaterialRules.block(id, loss) : FrequencyMaterialRules.tag(id, loss));
                });

        FrequencyMaterialRules.replaceOverrides(rules);
    }
}
