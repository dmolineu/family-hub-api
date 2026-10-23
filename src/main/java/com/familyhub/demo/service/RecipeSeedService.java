package com.familyhub.demo.service;

import com.familyhub.demo.dto.CreateRecipeRequest;
import com.familyhub.demo.dto.StarterRecipesResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gives a family the bundled starter recipes: family-friendly, reasonably healthy,
 * quick, with a good share of grilling. Runs at registration and on demand from the
 * recipes screen; a title the family already has is skipped, so it is safe to repeat.
 */
@Service
@RequiredArgsConstructor
public class RecipeSeedService {
    private final RecipeRepository recipeRepository;
    private final RecipeService recipeService;

    @Transactional
    public StarterRecipesResponse seedStarterRecipes(Family family) {
        Set<String> existingTitles = recipeRepository.findByFamilyForSummary(family).stream()
                .map(recipe -> normalize(recipe.getTitle()))
                .collect(Collectors.toSet());

        int added = 0;
        int skipped = 0;
        for (StarterRecipe starter : StarterRecipeBundle.load()) {
            if (existingTitles.contains(normalize(starter.title()))) {
                skipped++;
                continue;
            }
            recipeService.createRecipe(new CreateRecipeRequest(
                    starter.title(),
                    null,
                    starter.ingredients(),
                    starter.instructions(),
                    starter.note(),
                    null,
                    starter.tags(),
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
