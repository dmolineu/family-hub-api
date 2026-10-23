package com.familyhub.demo.service;

import com.familyhub.demo.dto.CreateRecipeRequest;
import com.familyhub.demo.dto.StarterRecipesResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.Recipe;
import com.familyhub.demo.repository.RecipeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.familyhub.demo.TestDataFactory.createFamily;
import static com.familyhub.demo.TestDataFactory.createRecipe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipeSeedServiceTest {

    @Mock
    private RecipeRepository recipeRepository;

    @Mock
    private RecipeService recipeService;

    @InjectMocks
    private RecipeSeedService recipeSeedService;

    @Test
    void seedsEveryStarterRecipeForANewFamily() {
        Family family = createFamily();
        when(recipeRepository.findByFamilyForSummary(family)).thenReturn(List.of());
        List<StarterRecipe> bundle = StarterRecipeBundle.load();

        StarterRecipesResponse result = recipeSeedService.seedStarterRecipes(family);

        assertThat(result.added()).isEqualTo(bundle.size());
        assertThat(result.skipped()).isZero();
        ArgumentCaptor<CreateRecipeRequest> requests = ArgumentCaptor.forClass(CreateRecipeRequest.class);
        verify(recipeService, times(bundle.size())).createRecipe(requests.capture(), eq(family));
        CreateRecipeRequest first = requests.getAllValues().get(0);
        assertThat(first.title()).isEqualTo(bundle.get(0).title());
        assertThat(first.ingredients()).isEqualTo(bundle.get(0).ingredients());
        assertThat(first.instructions()).isEqualTo(bundle.get(0).instructions());
        assertThat(first.tags()).isEqualTo(bundle.get(0).tags());
        assertThat(first.note()).isEqualTo(bundle.get(0).note());
    }

    @Test
    void skipsTitlesTheFamilyAlreadyHas_caseInsensitively() {
        Family family = createFamily();
        List<StarterRecipe> bundle = StarterRecipeBundle.load();
        Recipe existing = createRecipe(family, bundle.get(0).title().toUpperCase());
        when(recipeRepository.findByFamilyForSummary(family)).thenReturn(List.of(existing));

        StarterRecipesResponse result = recipeSeedService.seedStarterRecipes(family);

        assertThat(result.added()).isEqualTo(bundle.size() - 1);
        assertThat(result.skipped()).isEqualTo(1);
        verify(recipeService, times(bundle.size() - 1)).createRecipe(any(), eq(family));
    }
}
