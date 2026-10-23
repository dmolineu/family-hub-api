package com.familyhub.demo.dto;

/** Rows restored per module, for the confirmation shown to the family. */
public record RestoreSummary(int members, int chores, int lists, int recipes, int meals, int events) {}
