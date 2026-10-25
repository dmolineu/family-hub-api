package com.familyhub.demo.service;

/**
 * A bundled set of recipes a family can add in one tap.
 *
 * @param id          stable identifier used in the API path
 * @param name        what the recipes screen shows
 * @param description one line about what is in the pack
 * @param resource    classpath JSON holding the recipes
 */
public record RecipePack(String id, String name, String description, String resource) {}
