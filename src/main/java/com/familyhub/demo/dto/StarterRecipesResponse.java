package com.familyhub.demo.dto;

/** Outcome of adding the starter pack: recipes created, and titles the family already had. */
public record StarterRecipesResponse(int added, int skipped) {}
