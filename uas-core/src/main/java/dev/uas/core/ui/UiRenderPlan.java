package dev.uas.core.ui;

import dev.uas.api.ui.UiSchema;
import java.util.Map;
import java.util.Objects;

/**
 * The decision of <em>how</em> to render a schema for a particular client, and why.
 *
 * <p>Cross-platform UI is where silent degradation usually happens: the plugin author
 * writes an overlay, Bedrock shows a modal dialog, and nobody finds out until a
 * player complains. Carrying {@link #lossy()} and {@link #reason()} in the plan makes
 * the degradation a value the caller can log, surface to the author, or refuse.
 */
public record UiRenderPlan(
    UiSchema schema,
    UiTarget target,
    UiRenderPath path,
    Map<String, String> state,
    boolean lossy,
    String reason) {

    public UiRenderPlan {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(path, "path");
        state = state == null ? Map.of() : Map.copyOf(state);
        reason = reason == null ? "" : reason;
    }

    public static UiRenderPlan faithful(UiSchema schema, UiTarget target, UiRenderPath path,
        Map<String, String> state) {
        return new UiRenderPlan(schema, target, path, state, false, "native rendering path");
    }

    public static UiRenderPlan degraded(UiSchema schema, UiTarget target, UiRenderPath path,
        Map<String, String> state, String reason) {
        return new UiRenderPlan(schema, target, path, state, true, reason);
    }

    @Override
    public String toString() {
        return schema.key() + " -> " + path + (lossy ? " (lossy: " + reason + ")" : "");
    }
}
