package dev.uas.api;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A {@code namespace:path} identifier, matching Minecraft's resource-location grammar.
 *
 * <p>This is the single join point between the Java and Bedrock sides of the engine:
 * a Java {@code NamespacedKey} always maps to Bedrock {@code namespace_path}.
 */
public record NamespacedKey(String namespace, String path) implements Comparable<NamespacedKey> {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]{1,128}");

    public NamespacedKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new UasValidationException("invalid namespace '" + namespace + "'");
        }
        if (!PATH.matcher(path).matches()) {
            throw new UasValidationException("invalid path '" + path + "'");
        }
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw new UasValidationException("path must not contain empty segments: '" + path + "'");
        }
    }

    /** Parses {@code namespace:path}, defaulting the namespace to {@code minecraft}. */
    public static NamespacedKey parse(String value) {
        Objects.requireNonNull(value, "value");
        int colon = value.indexOf(':');
        if (colon < 0) {
            return new NamespacedKey("minecraft", value);
        }
        return new NamespacedKey(value.substring(0, colon), value.substring(colon + 1));
    }

    public static NamespacedKey uas(String path) {
        return new NamespacedKey("uas", path);
    }

    /** The Bedrock identifier form: {@code namespace_path}. */
    public String toBedrockIdentifier() {
        return namespace + "_" + path.replace('/', '_').replace('.', '_').replace('-', '_');
    }

    public String toAssetPath(String suffix) {
        return namespace + "/" + path + suffix;
    }

    @Override
    public int compareTo(NamespacedKey other) {
        int byNamespace = namespace.compareTo(other.namespace);
        return byNamespace != 0 ? byNamespace : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    /** Lower-cases and sanitises a free-form string into a valid path segment set. */
    public static String sanitizePath(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (ok || c == '_' || c == '-' || c == '.' || c == '/') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
