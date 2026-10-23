package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks an imported theme pack before it is stored or applied.
 *
 * <p>The accepted document is
 * <pre>
 * {"version": 1, "name": "Padres", "dark": true,
 *  "tokens": {"--primary": "#ffc425", ...},
 *  "images": {"logo": "data:image/svg+xml;base64,...", ...},
 *  "slots": {...}}
 * </pre>
 * Tokens become inline CSS custom properties on every device that picks the look,
 * and images become {@code url()} values, so both are held to a shape that cannot
 * escape a declaration or load anything from the network. Unknown fields are kept
 * (the document is stored verbatim) but ignored.
 */
@Component
public class ThemePackDocumentValidator {

    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_TOKENS = 200;
    public static final int MAX_IMAGES = 12;
    public static final int MAX_TOKEN_VALUE_LENGTH = 400;

    private static final Pattern TOKEN_NAME = Pattern.compile("^--[a-z0-9-]{1,60}$");
    private static final Pattern UNSAFE_TOKEN_VALUE =
            Pattern.compile("[;{}<>]|url\\(|expression\\(|javascript:", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_KEY = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final Pattern IMAGE_DATA_URL =
            Pattern.compile("^data:image/(png|jpe?g|webp|gif|svg\\+xml);base64,[A-Za-z0-9+/=]+$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ValidatedThemePack validate(String document) {
        if (document == null) {
            throw new BadRequestException("Theme pack is empty");
        }
        int sizeBytes = document.getBytes(StandardCharsets.UTF_8).length;
        if (sizeBytes > MAX_BYTES) {
            throw new BadRequestException("Theme pack must be 4 MB or smaller");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(document);
        } catch (JacksonException e) {
            throw new BadRequestException("Theme pack is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new BadRequestException("Theme pack must be a JSON object");
        }

        JsonNode version = root.get("version");
        if (version == null || !version.isIntegralNumber() || version.asInt() != 1) {
            throw new BadRequestException("Theme pack version must be 1");
        }

        JsonNode nameNode = root.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            throw new BadRequestException("Theme pack needs a name");
        }
        String name = nameNode.asText().trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BadRequestException("Theme pack name must be " + MAX_NAME_LENGTH + " characters or less");
        }

        JsonNode darkNode = root.get("dark");
        if (darkNode == null || !darkNode.isBoolean()) {
            throw new BadRequestException("Theme pack dark must be true or false");
        }

        JsonNode tokens = root.get("tokens");
        if (tokens == null || !tokens.isObject()) {
            throw new BadRequestException("Theme pack tokens must be an object");
        }
        if (tokens.size() > MAX_TOKENS) {
            throw new BadRequestException("Theme pack can hold at most " + MAX_TOKENS + " tokens");
        }
        for (Map.Entry<String, JsonNode> token : tokens.properties()) {
            if (!TOKEN_NAME.matcher(token.getKey()).matches()) {
                throw new BadRequestException(
                        "Theme pack token " + token.getKey() + " is not a custom property name (--name)");
            }
            JsonNode value = token.getValue();
            if (!value.isTextual() || value.asText().length() > MAX_TOKEN_VALUE_LENGTH
                    || UNSAFE_TOKEN_VALUE.matcher(value.asText()).find()) {
                throw new BadRequestException("Theme pack token " + token.getKey() + " has an unsafe value");
            }
        }

        JsonNode images = root.get("images");
        if (images != null && !images.isNull()) {
            if (!images.isObject()) {
                throw new BadRequestException("Theme pack images must be an object");
            }
            if (images.size() > MAX_IMAGES) {
                throw new BadRequestException("Theme pack can hold at most " + MAX_IMAGES + " images");
            }
            for (Map.Entry<String, JsonNode> image : images.properties()) {
                if (!IMAGE_KEY.matcher(image.getKey()).matches()) {
                    throw new BadRequestException(
                            "Theme pack image key " + image.getKey() + " may only use a-z, 0-9 and -");
                }
                JsonNode value = image.getValue();
                if (!value.isTextual() || !IMAGE_DATA_URL.matcher(value.asText()).matches()) {
                    throw new BadRequestException(
                            "Theme pack image " + image.getKey() + " must be an image data URL");
                }
            }
        }

        JsonNode slots = root.get("slots");
        if (slots != null && !slots.isNull() && !slots.isObject()) {
            throw new BadRequestException("Theme pack slots must be an object");
        }

        return new ValidatedThemePack(name, darkNode.asBoolean(), document, sizeBytes);
    }
}
