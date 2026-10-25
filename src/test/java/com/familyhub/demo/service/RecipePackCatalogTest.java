package com.familyhub.demo.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RecipePackCatalogTest {

    private static final Pattern TIMES = Pattern.compile("Prep (\\d+) min · Cook (\\d+) min · Serves (\\d+)");

    @Test
    void listsTheStarterPackThenQuickAndHealthy() {
        assertThat(RecipePackCatalog.all()).extracting(RecipePack::id)
                .containsExactly("starter", "quick-healthy");
        assertThat(RecipePackCatalog.all()).allSatisfy(pack -> {
            assertThat(pack.name()).isNotBlank();
            assertThat(pack.description()).isNotBlank();
            assertThat(RecipePackCatalog.recipes(pack)).isNotEmpty();
        });
        assertThat(RecipePackCatalog.find("nope")).isEmpty();
    }

    @Test
    void theStarterBundleIsTheStarterPack() {
        RecipePack starter = RecipePackCatalog.find("starter").orElseThrow();
        assertThat(RecipePackCatalog.recipes(starter)).isSameAs(StarterRecipeBundle.load());
    }

    @Test
    void quickAndHealthyIsAVarietyOfCompleteQuickRecipes() {
        RecipePack pack = RecipePackCatalog.find("quick-healthy").orElseThrow();
        List<StarterRecipe> recipes = RecipePackCatalog.recipes(pack);

        assertThat(recipes).hasSizeGreaterThanOrEqualTo(24);
        assertThat(recipes).extracting(StarterRecipe::title).doesNotHaveDuplicates();
        assertThat(recipes).allSatisfy(recipe -> {
            assertThat(recipe.title()).isNotBlank();
            assertThat(recipe.ingredients()).hasSizeGreaterThanOrEqualTo(4);
            assertThat(recipe.instructions()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(recipe.tags()).contains("quick & healthy", "healthy", "quick");
            Matcher times = TIMES.matcher(recipe.note());
            assertThat(times.find()).as("times in the note of %s", recipe.title()).isTrue();
            int total = Integer.parseInt(times.group(1)) + Integer.parseInt(times.group(2));
            assertThat(total).as("prep + cook minutes for %s", recipe.title()).isLessThanOrEqualTo(40);
        });
        assertThat(recipes).filteredOn(r -> r.tags().contains("vegetarian")).hasSizeGreaterThanOrEqualTo(5);
        assertThat(recipes).filteredOn(r -> r.tags().contains("seafood")).hasSizeGreaterThanOrEqualTo(4);
        assertThat(recipes).filteredOn(r -> r.tags().contains("chicken")).hasSizeGreaterThanOrEqualTo(4);
        assertThat(recipes).filteredOn(r -> r.tags().contains("breakfast")).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void noRecipeTitleIsInTwoPacks() {
        Set<String> starterTitles = RecipePackCatalog.recipes(RecipePackCatalog.find("starter").orElseThrow()).stream()
                .map(r -> r.title().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(RecipePackCatalog.recipes(RecipePackCatalog.find("quick-healthy").orElseThrow()))
                .extracting(r -> r.title().toLowerCase(Locale.ROOT))
                .doesNotContainAnyElementsOf(starterTitles);
    }
}
