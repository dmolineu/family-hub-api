package com.familyhub.demo.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StarterRecipeBundleTest {
    @Test
    void bundleIsAVarietyOfCompleteFamilyRecipes() {
        List<StarterRecipe> recipes = StarterRecipeBundle.load();

        assertThat(recipes).hasSizeGreaterThanOrEqualTo(20);
        assertThat(recipes).extracting(StarterRecipe::title).doesNotHaveDuplicates();
        assertThat(recipes).allSatisfy(recipe -> {
            assertThat(recipe.title()).isNotBlank();
            assertThat(recipe.note()).contains("Serves");
            assertThat(recipe.ingredients()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(recipe.instructions()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(recipe.tags()).contains("starter");
        });
        assertThat(recipes).filteredOn(recipe -> recipe.tags().contains("grill")).hasSizeGreaterThanOrEqualTo(5);
        assertThat(recipes).filteredOn(recipe -> recipe.tags().contains("vegetarian")).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void loadReturnsTheSameParsedListEachTime() {
        assertThat(StarterRecipeBundle.load()).isSameAs(StarterRecipeBundle.load());
    }
}
