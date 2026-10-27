package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks an imported avatar pack before it is stored. The accepted document is
 * <pre>
 * {"version": 1, "name": "Star Wars",
 *  "characters": [{"key": "yoda", "name": "Yoda", "group": "Star Wars",
 *                  "image": "data:image/png;base64,..."}, ...]}
 * </pre>
 * Pictures are inline PNG, JPEG or WebP of at most 64 KB each, so a pack never loads
 * anything from the network and a chosen character can be copied onto a member as is.
 * A character without a group belongs to a group named after the pack. Unknown fields
 * are kept (the document is stored verbatim) but ignored.
 */
@Component
public class AvatarPackDocumentValidator {

    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_CHARACTERS = 120;
    public static final int MAX_IMAGE_BYTES = MemberAvatars.MAX_IMAGE_BYTES;
    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_CHARACTER_NAME_LENGTH = 60;
    public static final int MAX_GROUP_LENGTH = 40;

    private static final Pattern KEY = Pattern.compile("^[a-z0-9-]{1,40}$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Validates a pack document.
     *
     * @throws BadRequestException naming the first problem found, and the character it is in
     */
    public ValidatedAvatarPack validate(String document) {
        if (document == null) {
            throw new BadRequestException("Avatar pack is empty");
        }
        int sizeBytes = document.getBytes(StandardCharsets.UTF_8).length;
        if (sizeBytes > MAX_BYTES) {
            throw new BadRequestException("Avatar pack must be 4 MB or smaller");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(document);
        } catch (JacksonException e) {
            throw new BadRequestException("Avatar pack is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new BadRequestException("Avatar pack must be a JSON object");
        }

        JsonNode version = root.get("version");
        if (version == null || !version.isIntegralNumber() || version.asInt() != 1) {
            throw new BadRequestException("Avatar pack version must be 1");
        }

        String name = text(root.get("name"));
        if (name == null) {
            throw new BadRequestException("Avatar pack needs a name");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BadRequestException("Avatar pack name must be " + MAX_NAME_LENGTH + " characters or less");
        }

        JsonNode characters = root.get("characters");
        if (characters == null || !characters.isArray() || characters.isEmpty()) {
            throw new BadRequestException("Avatar pack needs at least one character");
        }
        if (characters.size() > MAX_CHARACTERS) {
            throw new BadRequestException("Avatar pack can hold at most " + MAX_CHARACTERS + " characters");
        }

        Set<String> keys = new HashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        for (JsonNode character : characters) {
            if (!character.isObject()) {
                throw new BadRequestException("Every avatar pack character must be an object");
            }
            String key = character.path("key").isTextual() ? character.get("key").asText() : "";
            if (!KEY.matcher(key).matches()) {
                throw new BadRequestException("Character key \"" + key + "\" may only use a-z, 0-9 and -");
            }
            if (!keys.add(key)) {
                throw new BadRequestException("Character key " + key + " appears twice");
            }
            String characterName = text(character.get("name"));
            if (characterName == null || characterName.length() > MAX_CHARACTER_NAME_LENGTH) {
                throw new BadRequestException("Character " + key + " needs a name of "
                        + MAX_CHARACTER_NAME_LENGTH + " characters or less");
            }
            String group = text(character.get("group"));
            if (group != null && group.length() > MAX_GROUP_LENGTH) {
                throw new BadRequestException("Character " + key + " has a group name over "
                        + MAX_GROUP_LENGTH + " characters");
            }
            groups.add(group == null ? name : group);
            String image = character.path("image").isTextual() ? character.get("image").asText() : null;
            boolean imageOk = InlineImages.parse(image).filter(p -> p.bytes() <= MAX_IMAGE_BYTES).isPresent();
            if (!imageOk) {
                throw new BadRequestException("Character " + key + " needs a PNG, JPEG or WebP image of 64 KB or less");
            }
        }

        return new ValidatedAvatarPack(name, characters.size(), new ArrayList<>(groups), document, sizeBytes);
    }

    /** The trimmed text of a string node; null when absent, not a string, or blank. */
    private static String text(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }
}
