package com.familyhub.demo.service;

import java.util.List;

/** The starter pack, the one seeded at registration. See {@link RecipePackCatalog}. */
public final class StarterRecipeBundle {
    private StarterRecipeBundle() {}

    /** The parsed starter pack; the same instance on every call. */
    public static List<StarterRecipe> load() {
        return RecipePackCatalog.recipes(RecipePackCatalog.find("starter").orElseThrow());
    }
}
