package com.familyhub.demo.service;

import java.util.List;

/** One entry of the bundled starter-recipe pack (src/main/resources/data/starter-recipes.json). */
public record StarterRecipe(
        String title,
        String note,
        List<String> ingredients,
        List<String> instructions,
        List<String> tags
) {}
