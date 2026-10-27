package com.familyhub.demo.service;

import java.util.List;

/**
 * An avatar pack document that passed validation: what the server needs to store and
 * list it, plus the document text exactly as received.
 */
public record ValidatedAvatarPack(String name, int characterCount, List<String> groups, String document, int sizeBytes) {}
