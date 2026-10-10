package dev.uas.api.block;

import dev.uas.api.UasValidationException;

/**
 * A state-dependent override: geometry swapped in when {@code condition} holds.
 *
 * <p>{@code condition} is a Bedrock permutation query such as
 * {@code q.block_state('uas:lit') == true}. The Java side evaluates the same
 * condition through the state-matrix backends, so the expression is carried as
 * text and validated for shape rather than being parsed here.
 */
public record BlockVariant(String condition, BlockGeometry geometry, RenderLayer renderLayerOverride) {

    public BlockVariant {
        if (condition == null || condition.isBlank()) {
            throw new UasValidationException("block variant condition must not be blank");
        }
        if (!condition.contains("block_state(")) {
            throw new UasValidationException(
                "block variant condition must reference a block state, got '" + condition + "'");
        }
    }

    public static BlockVariant of(String condition, BlockGeometry geometry) {
        return new BlockVariant(condition, geometry, null);
    }

    /** Extracts the property name from the first {@code block_state('ns:name')} call. */
    public String propertyName() {
        int start = condition.indexOf("block_state(");
        if (start < 0) {
            return "";
        }
        int quoteStart = condition.indexOf('\'', start);
        int quoteEnd = condition.indexOf('\'', quoteStart + 1);
        if (quoteStart < 0 || quoteEnd < 0) {
            return "";
        }
        String qualified = condition.substring(quoteStart + 1, quoteEnd);
        int colon = qualified.indexOf(':');
        return colon < 0 ? qualified : qualified.substring(colon + 1);
    }
}
