package dev.uas.api.ui;

import java.util.Locale;

/**
 * How a UI schema is presented to the player.
 *
 * <p>Java can render all of these. Bedrock can only render {@link #MODAL} natively
 * through Cumulus forms, so the others either use custom UI JSON injected by the
 * resource pack or degrade to a form plus a toast.
 */
public enum UiPresentation {
    /** A non-blocking HUD overlay drawn over the game. */
    OVERLAY(true, false, false),
    /** A blocking menu the player must dismiss. */
    MODAL(false, true, true),
    /** A full custom screen, replacing the whole interface. */
    SCREEN(false, true, false),
    /** A single line shown above the hotbar. */
    ACTION_BAR(true, false, false),
    /** A transient notification; the only presentation Bedrock natively matches. */
    TOAST(true, false, true);

    private final boolean nonBlocking;
    private final boolean supportsSlotGrid;
    private final boolean bedrockNative;

    UiPresentation(boolean nonBlocking, boolean supportsSlotGrid, boolean bedrockNative) {
        this.nonBlocking = nonBlocking;
        this.supportsSlotGrid = supportsSlotGrid;
        this.bedrockNative = bedrockNative;
    }

    public boolean isNonBlocking() {
        return nonBlocking;
    }

    /** Only blocking presentations can own real inventory slots. */
    public boolean supportsSlotGrid() {
        return supportsSlotGrid;
    }

    /** True when Bedrock can render this natively without custom UI JSON. */
    public boolean isBedrockNative() {
        return bedrockNative;
    }

    public String bedrockName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
