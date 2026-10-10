package dev.uas.api.ui;

import java.util.Locale;

/**
 * Where an element is pinned inside its parent.
 *
 * <p>Nine-point anchoring plus two spanning modes. Offsets are always applied
 * <em>away from</em> the anchor, so a {@code BOTTOM_RIGHT} element with a positive
 * offset moves up and left, which is what designers expect when anchoring to a
 * corner.
 */
public enum Anchor {
    TOP_LEFT(Horizontal.LEFT, Vertical.TOP),
    TOP_CENTER(Horizontal.CENTER, Vertical.TOP),
    TOP_RIGHT(Horizontal.RIGHT, Vertical.TOP),
    CENTER_LEFT(Horizontal.LEFT, Vertical.CENTER),
    CENTER(Horizontal.CENTER, Vertical.CENTER),
    CENTER_RIGHT(Horizontal.RIGHT, Vertical.CENTER),
    BOTTOM_LEFT(Horizontal.LEFT, Vertical.BOTTOM),
    BOTTOM_CENTER(Horizontal.CENTER, Vertical.BOTTOM),
    BOTTOM_RIGHT(Horizontal.RIGHT, Vertical.BOTTOM),
    /** Stretches full width, pinned vertically. */
    STRETCH_H(Horizontal.STRETCH, Vertical.TOP),
    /** Stretches full height, pinned horizontally. */
    STRETCH_V(Horizontal.LEFT, Vertical.STRETCH),
    /** Fills the parent entirely. */
    FULL(Horizontal.STRETCH, Vertical.STRETCH);

    public enum Horizontal {
        LEFT,
        CENTER,
        RIGHT,
        STRETCH
    }

    public enum Vertical {
        TOP,
        CENTER,
        BOTTOM,
        STRETCH
    }

    private final Horizontal horizontal;
    private final Vertical vertical;

    Anchor(Horizontal horizontal, Vertical vertical) {
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    public Horizontal horizontal() {
        return horizontal;
    }

    public Vertical vertical() {
        return vertical;
    }

    public boolean stretchesHorizontally() {
        return horizontal == Horizontal.STRETCH;
    }

    public boolean stretchesVertically() {
        return vertical == Vertical.STRETCH;
    }

    /** The Bedrock custom-UI anchor token, used when emitting UI JSON. */
    public String bedrockName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Anchor of(Horizontal horizontal, Vertical vertical) {
        for (Anchor anchor : values()) {
            if (anchor.horizontal == horizontal && anchor.vertical == vertical) {
                return anchor;
            }
        }
        throw new IllegalArgumentException("no anchor for " + horizontal + "/" + vertical);
    }

    /** Mirrors the anchor horizontally; used for right-to-left layouts. */
    public Anchor mirrored() {
        Horizontal mirrored = switch (horizontal) {
            case LEFT -> Horizontal.RIGHT;
            case RIGHT -> Horizontal.LEFT;
            case CENTER, STRETCH -> horizontal;
        };
        return of(mirrored, vertical);
    }
}
