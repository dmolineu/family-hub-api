package com.familyhub.demo.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

/**
 * Stores a named colour the way the enum did ({@code CORAL}), so rows saved before
 * custom colours read back unchanged and need no migration, and a custom colour as
 * its {@code #rrggbb}.
 */
@Converter
public class FamilyColorConverter implements AttributeConverter<FamilyColor, String> {
    @Override
    public String convertToDatabaseColumn(FamilyColor color) {
        if (color == null) {
            return null;
        }
        return color.isCustom() ? color.toValue() : color.toValue().toUpperCase(Locale.ROOT);
    }

    @Override
    public FamilyColor convertToEntityAttribute(String column) {
        return column == null ? null : FamilyColor.fromValue(column);
    }
}
