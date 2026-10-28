package com.familyhub.demo.inbox;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON schema of a record, for structured output: every component required and
 * nothing else allowed, enums as their names, descriptions from
 * {@link JsonPropertyDescription}. The record stays the one place the answer's shape
 * is written down. Covers what readings use: strings, booleans, whole numbers, enums,
 * lists and nested records.
 */
final class RecordJsonSchema {
    private RecordJsonSchema() {
    }

    /** The schema of a record type. */
    static Map<String, Object> of(Class<? extends Record> type) {
        return schemaFor(type);
    }

    private static Map<String, Object> schemaFor(Type type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == List.class) {
            schema.put("type", "array");
            schema.put("items", schemaFor(parameterized.getActualTypeArguments()[0]));
            return schema;
        }
        if (!(type instanceof Class<?> kind)) {
            throw new IllegalArgumentException("No schema for " + type);
        }
        if (kind == String.class) {
            schema.put("type", "string");
        } else if (kind == boolean.class || kind == Boolean.class) {
            schema.put("type", "boolean");
        } else if (kind == int.class || kind == Integer.class || kind == long.class || kind == Long.class) {
            schema.put("type", "integer");
        } else if (kind.isEnum()) {
            schema.put("type", "string");
            schema.put("enum", Arrays.stream(kind.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList());
        } else if (kind.isRecord()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent component : kind.getRecordComponents()) {
                Map<String, Object> property = schemaFor(component.getGenericType());
                // The annotation reaches the accessor, not the component itself.
                JsonPropertyDescription description = component.getAccessor()
                        .getAnnotation(JsonPropertyDescription.class);
                if (description != null) {
                    property.put("description", description.value());
                }
                properties.put(component.getName(), property);
                required.add(component.getName());
            }
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", required);
            schema.put("additionalProperties", false);
        } else {
            throw new IllegalArgumentException("No schema for " + kind.getName());
        }
        return schema;
    }
}
