package dev.uas.api.ui;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import java.util.List;
import java.util.Objects;

/**
 * A node in a declarative UI tree.
 *
 * <p>Sealed so that every consumer -- the Java renderer, the Bedrock UI compiler
 * and the Cumulus form fallback -- must handle each element kind explicitly and
 * exhaustively. Adding a kind therefore breaks the build until every backend
 * supports it, instead of silently rendering nothing on one platform.
 */
public sealed interface UiElement {

    /** Stable, unique-within-a-schema identifier, also used as the state key. */
    String id();

    UiStyle style();

    /** The element kind, used by backends to test support without instanceof chains. */
    default ElementKind kind() {
        return switch (this) {
            case Text ignored -> ElementKind.TEXT;
            case Image ignored -> ElementKind.IMAGE;
            case Button ignored -> ElementKind.BUTTON;
            case SlotGrid ignored -> ElementKind.SLOT_GRID;
            case ProgressBar ignored -> ElementKind.PROGRESS_BAR;
            case Container ignored -> ElementKind.CONTAINER;
            case Spacer ignored -> ElementKind.SPACER;
        };
    }

    /** Appends this element and, for containers, all descendants in depth-first order. */
    default void collect(List<UiElement> sink) {
        sink.add(this);
    }

    enum ElementKind {
        TEXT,
        IMAGE,
        BUTTON,
        SLOT_GRID,
        PROGRESS_BAR,
        CONTAINER,
        SPACER
    }

    /** A text run. */
    record Text(
        String id,
        UiStyle style,
        String text,
        Align align,
        boolean shadow,
        boolean boundToState) implements UiElement {

        public enum Align {
            LEFT,
            CENTER,
            RIGHT
        }

        public Text {
            requireId(id);
            Objects.requireNonNull(style, "style");
            text = text == null ? "" : text;
            align = align == null ? Align.LEFT : align;
        }

        public static Text of(String id, String text) {
            return new Text(id, UiStyle.placeholder(), text, Align.LEFT, true, false);
        }
    }

    /** A textured image, optionally nine-sliced. */
    record Image(
        String id,
        UiStyle style,
        NamespacedKey texture,
        NineSlice nineSlice,
        Fit fit,
        Integer tintColor) implements UiElement {

        public enum Fit {
            STRETCH,
            CONTAIN,
            COVER,
            TILE
        }

        public Image {
            requireId(id);
            Objects.requireNonNull(style, "style");
            Objects.requireNonNull(texture, "texture");
            fit = fit == null ? Fit.STRETCH : fit;
            if (nineSlice != null && fit != Fit.STRETCH) {
                throw new UasValidationException(
                    "nine-sliced image '" + id + "' must use Fit.STRETCH, got " + fit);
            }
            if (tintColor != null && (tintColor < 0 || tintColor > 0xFFFFFF)) {
                throw new UasValidationException("tint colour must be 0x000000..0xFFFFFF, got " + tintColor);
            }
        }

        public static Image of(String id, NamespacedKey texture) {
            return new Image(id, UiStyle.placeholder(), texture, null, Fit.STRETCH, null);
        }
    }

    /** A clickable button. */
    record Button(
        String id,
        UiStyle style,
        String label,
        NamespacedKey icon,
        NineSlice skin,
        String value) implements UiElement {

        public Button {
            requireId(id);
            Objects.requireNonNull(style, "style");
            label = label == null ? "" : label;
            value = value == null ? label : value;
            if (label.isBlank() && icon == null) {
                throw new UasValidationException("button '" + id + "' needs a label or an icon");
            }
        }

        public static Button of(String id, String label, UiAction action) {
            return new Button(id, UiStyle.placeholder().toBuilder().action(action).build(), label, null, null, label);
        }
    }

    /** A grid of inventory slots: the Java side backs it with a real inventory. */
    record SlotGrid(
        String id,
        UiStyle style,
        int rows,
        int columns,
        List<Integer> lockedSlots,
        boolean interactive) implements UiElement {

        public static final int MAX_SLOTS = 54;

        public SlotGrid {
            requireId(id);
            Objects.requireNonNull(style, "style");
            if (rows <= 0 || columns <= 0) {
                throw new UasValidationException("slot grid '" + id + "' must have positive dimensions");
            }
            if (rows * columns > MAX_SLOTS) {
                throw new UasValidationException(
                    "slot grid '" + id + "' has " + (rows * columns)
                        + " slots, which exceeds the " + MAX_SLOTS + "-slot inventory limit");
            }
            lockedSlots = lockedSlots == null ? List.of() : List.copyOf(lockedSlots);
            for (int slot : lockedSlots) {
                if (slot < 0 || slot >= rows * columns) {
                    throw new UasValidationException(
                        "locked slot " + slot + " is outside the " + (rows * columns) + " slot grid");
                }
            }
        }

        public int slotCount() {
            return rows * columns;
        }

        public static SlotGrid of(String id, int rows, int columns) {
            return new SlotGrid(id, UiStyle.placeholder(), rows, columns, List.of(), true);
        }
    }

    /** A bound progress bar. */
    record ProgressBar(
        String id,
        UiStyle style,
        String bindKey,
        double min,
        double max,
        NamespacedKey trackTexture,
        NamespacedKey fillTexture,
        boolean vertical,
        String caption) implements UiElement {

        public ProgressBar {
            requireId(id);
            Objects.requireNonNull(style, "style");
            if (bindKey == null || bindKey.isBlank()) {
                throw new UasValidationException("progress bar '" + id + "' must declare a bind key");
            }
            if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
                throw new UasValidationException(
                    "progress bar '" + id + "' range must be finite with max > min, got "
                        + min + ".." + max);
            }
            caption = caption == null ? "" : caption;
        }

        /** Clamps and normalises a raw state value into 0..1 for rendering. */
        public double fractionFor(double rawValue) {
            double clamped = Math.max(min, Math.min(max, rawValue));
            return (clamped - min) / (max - min);
        }

        public static ProgressBar of(String id, String bindKey, double max) {
            return new ProgressBar(id, UiStyle.placeholder(), bindKey, 0, max, null, null, false, "");
        }
    }

    /** A recursive container. */
    record Container(
        String id,
        UiStyle style,
        Layout layout,
        List<UiElement> children,
        boolean clipChildren) implements UiElement {

        public static final int MAX_CHILDREN = 128;

        public Container {
            requireId(id);
            Objects.requireNonNull(style, "style");
            layout = layout == null ? Layout.absolute() : layout;
            children = children == null ? List.of() : List.copyOf(children);
            if (children.size() > MAX_CHILDREN) {
                throw new UasValidationException(
                    "container '" + id + "' has " + children.size() + " children; max is " + MAX_CHILDREN);
            }
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (UiElement child : children) {
                if (!ids.add(child.id())) {
                    throw new UasValidationException(
                        "container '" + id + "' declares duplicate child id '" + child.id() + "'");
                }
            }
        }

        @Override
        public void collect(List<UiElement> sink) {
            sink.add(this);
            for (UiElement child : children) {
                child.collect(sink);
            }
        }

        public static Container of(String id, Layout layout, List<UiElement> children) {
            return new Container(id, UiStyle.placeholder(), layout, children, false);
        }
    }

    /** An empty element that consumes layout space. */
    record Spacer(String id, UiStyle style) implements UiElement {
        public Spacer {
            requireId(id);
            Objects.requireNonNull(style, "style");
        }

        public static Spacer of(String id, Size width, Size height) {
            return new Spacer(id, UiStyle.of(Anchor.TOP_LEFT, width, height));
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new UasValidationException("ui element id must not be blank");
        }
        if (id.length() > 128) {
            throw new UasValidationException("ui element id too long (" + id.length() + " chars)");
        }
    }
}
