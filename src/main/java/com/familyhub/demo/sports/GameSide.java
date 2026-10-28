package com.familyhub.demo.sports;

/** One side of a game; the score is null until the game starts. */
public record GameSide(String teamId, String name, String abbreviation, Integer score) {
}
