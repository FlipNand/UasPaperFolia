package dev.uas.core.ui;

import java.util.Locale;

/** Which client family a UI must be rendered for. */
public enum UiTarget {

    /** A Java Edition client: real inventories, real HUDs, custom server-side rendering. */
    JAVA,

    /** A Bedrock client, reached through Geyser: Cumulus forms and injected UI JSON. */
    BEDROCK;

    public static UiTarget fromString(String value) {
        if (value == null) {
            return JAVA;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "bedrock", "be", "geyser", "pe" -> BEDROCK;
            case "java", "je", "pc" -> JAVA;
            default -> throw new IllegalArgumentException(
                "unknown ui target '" + value + "'; expected java or bedrock");
        };
    }
}
