package com.familyhub.demo.service;

import com.familyhub.demo.dto.CreateRecipeRequest;
import com.familyhub.demo.dto.RecipePackResponse;
import com.familyhub.demo.dto.StarterRecipesResponse;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gives a family the bundled recipe packs (see {@link RecipePackCatalog}). The starter
 * pack runs at registration; any pack can be added from the recipes screen. A title the
 * family already has is skipped, so adding a pack again is always safe.
 */
@Service
@RequiredArgsConstructor
public class RecipeSeedService {
    private final RecipeRepository recipeRepository;
    private final RecipeService recipeService;

    /** The bundled packs with their sizes, for the recipes screen. */
    public List<RecipePackResponse> listPacks() {
        return RecipePackCatalog.all().stream()
                .map(pack -> new RecipePackResponse(
                        pack.id(), pack.name(), pack.description(), RecipePackCatalog.recipes(pack).size()))
                .toList();
    }

    @Transactional
    public StarterRecipesResponse seedStarterRecipes(Family family) {
        return seedPack("starter", family);
    }

    @Transactional
    public StarterRecipesResponse seedPack(String packId, Family family) {
        RecipePack pack = RecipePackCatalog.find(packId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe pack not found: " + packId));
        Set<String> existingTitles = recipeRepository.findByFamilyForSummary(family).stream()
                .map(recipe -> normalize(recipe.getTitle()))
                .collect(Collectors.toSet());

        int added = 0;
        int skipped = 0;
        for (StarterRecipe recipe : RecipePackCatalog.recipes(pack)) {
            if (!existingTitles.add(normalize(recipe.title()))) {
                skipped++;
                continue;
            }
            recipeService.createRecipe(new CreateRecipeRequest(
                    recipe.title(),
                    null,
                    recipe.ingredients(),
                    recipe.instructions(),
                    recipe.note(),
                    null,
                    recipe.tags(),
                    false
            ), family);
            added++;
        }
        return new StarterRecipesResponse(added, skipped);
    }

    private static String normalize(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    }
}
