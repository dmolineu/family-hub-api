package com.familyhub.demo.service;

/**
 * A theme pack document that passed validation: the fields the server needs to
 * store and list it, plus the document text exactly as received.
 */
public record ValidatedThemePack(String name, boolean dark, String document, int sizeBytes) {}
