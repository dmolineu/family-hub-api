package com.familyhub.demo.dto;

/** A bundled recipe pack as the recipes screen lists it. */
public record RecipePackResponse(String id, String name, String description, int recipeCount) {}
