package com.familyhub.demo.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Loads the bundled starter recipes once; they never change at runtime. */
public final class StarterRecipeBundle {
    static final String RESOURCE = "/data/starter-recipes.json";

    private StarterRecipeBundle() {}

    /** The parsed bundle; the same instance on every call. */
    public static List<StarterRecipe> load() {
        return Holder.RECIPES;
    }

    private static final class Holder {
        private static final List<StarterRecipe> RECIPES = read();

        private static List<StarterRecipe> read() {
            ObjectMapper mapper = JsonMapper.builder().build();
            try (InputStream in = StarterRecipeBundle.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Starter recipes resource missing: " + RESOURCE);
                }
                List<StarterRecipe> recipes = mapper.readValue(
                        in, mapper.getTypeFactory().constructCollectionType(List.class, StarterRecipe.class));
                return List.copyOf(recipes);
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read starter recipes from " + RESOURCE, ex);
            }
        }
    }
}
