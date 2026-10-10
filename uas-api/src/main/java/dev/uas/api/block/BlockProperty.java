package dev.uas.api.block;

import dev.uas.api.UasValidationException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A block state property, mirroring a Java blockstate property and mapping
 * one-to-one onto Bedrock's {@code booleanProperty} / {@code intProperty} /
 * {@code stringProperty} builders.
 */
public record BlockProperty(String name, Kind kind, List<Integer> intValues, List<String> stringValues) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");

    public enum Kind {
        BOOLEAN,
        INT,
        STRING
    }

    public BlockProperty {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        if (!NAME.matcher(name).matches()) {
            throw new UasValidationException("invalid block property name '" + name + "'");
        }
        intValues = intValues == null ? List.of() : List.copyOf(intValues);
        stringValues = stringValues == null ? List.of() : List.copyOf(stringValues);
        switch (kind) {
            case BOOLEAN -> {
                if (!intValues.isEmpty() || !stringValues.isEmpty()) {
                    throw new UasValidationException("boolean property '" + name + "' must not carry values");
                }
            }
            case INT -> {
                if (intValues.isEmpty()) {
                    throw new UasValidationException("int property '" + name + "' needs at least one value");
                }
                if (!stringValues.isEmpty()) {
                    throw new UasValidationException("int property '" + name + "' must not carry string values");
                }
            }
            case STRING -> {
                if (stringValues.isEmpty()) {
                    throw new UasValidationException("string property '" + name + "' needs at least one value");
                }
                if (!intValues.isEmpty()) {
                    throw new UasValidationException("string property '" + name + "' must not carry int values");
                }
            }
            default -> throw new UasValidationException("unsupported property kind " + kind);
        }
    }

    public static BlockProperty bool(String name) {
        return new BlockProperty(name, Kind.BOOLEAN, List.of(), List.of());
    }

    public static BlockProperty integer(String name, List<Integer> values) {
        return new BlockProperty(name, Kind.INT, values, List.of());
    }

    public static BlockProperty text(String name, List<String> values) {
        return new BlockProperty(name, Kind.STRING, List.of(), values);
    }

    public int valueCount() {
        return switch (kind) {
            case BOOLEAN -> 2;
            case INT -> intValues.size();
            case STRING -> stringValues.size();
        };
    }
}
