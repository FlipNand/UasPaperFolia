package dev.uas.core.ui;

/**
 * The concrete mechanism chosen to render a schema for one player.
 *
 * <p>This is the output of {@link UnifiedUIManager#plan(String)} and exists because
 * "render this UI" is not one action. A HUD overlay on Java is a packet-driven
 * drawing routine; the same schema on Bedrock is either a Cumulus form (lossy: it
 * flattens an overlay into a modal) or injected custom UI JSON (faithful, but needs a
 * resource pack installed on the client). Naming the choice explicitly is what lets
 * the caller tell the plugin author why their overlay became a dialog on Bedrock.
 */
public enum UiRenderPath {

    /** Java: a server-side inventory menu; the only path that can own real slots. */
    JAVA_INVENTORY_MENU,
    /** Java: a packet-driven HUD overlay or action bar. */
    JAVA_HUD_OVERLAY,
    /** Bedrock: a native Cumulus form. The only presentation Bedrock renders without custom JSON. */
    BEDROCK_CUMULUS_FORM,
    /** Bedrock: UI JSON injected by the resource pack; faithful but requires the pack. */
    BEDROCK_CUSTOM_JSON,
    /** Bedrock: a degraded one-line notification, used when nothing better is available. */
    BEDROCK_ACTION_BAR;

    public boolean isBedrock() {
        return this == BEDROCK_CUMULUS_FORM || this == BEDROCK_CUSTOM_JSON || this == BEDROCK_ACTION_BAR;
    }
}
