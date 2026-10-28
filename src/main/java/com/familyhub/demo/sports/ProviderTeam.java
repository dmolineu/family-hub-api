package com.familyhub.demo.sports;

/**
 * A team as a data provider lists it.
 *
 * @param id        the provider's own id, stable across seasons
 * @param shortName the name a family uses ("Padres")
 * @param logoUrl   shown from the provider's own image host at run time, never stored in a repository
 */
public record ProviderTeam(String id, String name, String shortName, String abbreviation, String logoUrl) {
}
