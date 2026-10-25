package com.familyhub.demo.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The recipe packs bundled with the app. Every recipe in them was written for this
 * app; packs never carry recipes copied from other sites. Parsed once per pack and
 * cached, since they never change at runtime.
 */
public final class RecipePackCatalog {
    private static final List<RecipePack> PACKS = List.of(
            new RecipePack(
                    "starter",
                    "Starter recipes",
                    "Family favourites, many on the grill, that most kids will eat.",
                    "/data/starter-recipes.json"),
            new RecipePack(
                    "quick-healthy",
                    "Quick & healthy",
                    "Weeknight meals in 40 minutes or less: lean proteins, vegetables and whole grains.",
                    "/data/quick-healthy-recipes.json")
    );

    private static final Map<String, List<StarterRecipe>> CACHE = new ConcurrentHashMap<>();

    private RecipePackCatalog() {}

    /** Every bundled pack, in display order. */
    public static List<RecipePack> all() {
        return PACKS;
    }

    public static Optional<RecipePack> find(String id) {
        return PACKS.stream().filter(pack -> pack.id().equals(id)).findFirst();
    }

    /** The pack's recipes; the same list instance on every call. */
    public static List<StarterRecipe> recipes(RecipePack pack) {
        return CACHE.computeIfAbsent(pack.resource(), RecipePackCatalog::read);
    }

    private static List<StarterRecipe> read(String resource) {
        ObjectMapper mapper = JsonMapper.builder().build();
        try (InputStream in = RecipePackCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Recipe pack resource missing: " + resource);
            }
            List<StarterRecipe> recipes = mapper.readValue(
                    in, mapper.getTypeFactory().constructCollectionType(List.class, StarterRecipe.class));
            return List.copyOf(recipes);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read recipe pack " + resource, ex);
        }
    }
}
