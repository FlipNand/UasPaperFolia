package dev.uas.api.ui;

import dev.uas.api.UasValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Positioning, layering and interaction attributes shared by every UI element.
 *
 * <p>Split out from {@link UiElement} so that layout can be reasoned about (and
 * tested) without knowing what kind of element it is attached to.
 */
public record UiStyle(
    Anchor anchor,
    Size offsetX,
    Size offsetY,
    Size width,
    Size height,
    int zIndex,
    ResponsiveScale scale,
    boolean visible,
    boolean bedrockFormFallback,
    String tooltip,
    List<String> tags,
    List<UiAction> actions) {

    /** Elements beyond this depth are rejected: they are almost always a builder bug. */
    public static final int MAX_ACTIONS_PER_ELEMENT = 8;

    public UiStyle {
        anchor = anchor == null ? Anchor.TOP_LEFT : anchor;
        offsetX = offsetX == null ? Size.px(0) : offsetX;
        offsetY = offsetY == null ? Size.px(0) : offsetY;
        width = width == null ? Size.auto() : width;
        height = height == null ? Size.auto() : height;
        scale = scale == null ? ResponsiveScale.identity() : scale;
        tags = tags == null ? List.of() : List.copyOf(tags);
        actions = actions == null ? List.of() : List.copyOf(actions);
        tooltip = tooltip == null ? "" : tooltip;
        if (actions.size() > MAX_ACTIONS_PER_ELEMENT) {
            throw new UasValidationException(
                "element declares " + actions.size() + " actions; the maximum is " + MAX_ACTIONS_PER_ELEMENT);
        }
        if (!visible && !actions.isEmpty()) {
            throw new UasValidationException("an invisible element must not declare actions");
        }
        for (String tag : tags) {
            if (tag.isBlank()) {
                throw new UasValidationException("ui element tags must not be blank");
            }
        }
    }

    public static UiStyle of(Anchor anchor, Size width, Size height) {
        return builder().anchor(anchor).size(width, height).build();
    }

    public static UiStyle placeholder() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** True when this element needs a Bedrock form fallback because custom UI is off. */
    public boolean needsBedrockFallback() {
        return bedrockFormFallback;
    }

    public boolean hasActions() {
        return !actions.isEmpty();
    }

    public Builder toBuilder() {
        return new Builder()
            .anchor(anchor)
            .offset(offsetX, offsetY)
            .size(width, height)
            .zIndex(zIndex)
            .scale(scale)
            .visible(visible)
            .bedrockFormFallback(bedrockFormFallback)
            .tooltip(tooltip)
            .tags(tags)
            .actions(actions);
    }

    public static final class Builder {
        private Anchor anchor = Anchor.TOP_LEFT;
        private Size offsetX = Size.px(0);
        private Size offsetY = Size.px(0);
        private Size width = Size.auto();
        private Size height = Size.auto();
        private int zIndex;
        private ResponsiveScale scale = ResponsiveScale.identity();
        private boolean visible = true;
        private boolean bedrockFormFallback;
        private String tooltip = "";
        private final List<String> tags = new ArrayList<>();
        private final List<UiAction> actions = new ArrayList<>();

        public Builder anchor(Anchor value) {
            this.anchor = value;
            return this;
        }

        public Builder offset(Size x, Size y) {
            this.offsetX = x;
            this.offsetY = y;
            return this;
        }

        public Builder size(Size newWidth, Size newHeight) {
            this.width = newWidth;
            this.height = newHeight;
            return this;
        }

        public Builder width(Size value) {
            this.width = value;
            return this;
        }

        public Builder height(Size value) {
            this.height = value;
            return this;
        }

        public Builder zIndex(int value) {
            this.zIndex = value;
            return this;
        }

        public Builder scale(ResponsiveScale value) {
            this.scale = value;
            return this;
        }

        public Builder visible(boolean value) {
            this.visible = value;
            return this;
        }

        public Builder bedrockFormFallback(boolean value) {
            this.bedrockFormFallback = value;
            return this;
        }

        public Builder tooltip(String value) {
            this.tooltip = value == null ? "" : value;
            return this;
        }

        public Builder tag(String value) {
            this.tags.add(Objects.requireNonNull(value, "tag"));
            return this;
        }

        public Builder tags(List<String> values) {
            this.tags.clear();
            this.tags.addAll(values);
            return this;
        }

        public Builder action(UiAction value) {
            this.actions.add(Objects.requireNonNull(value, "action"));
            return this;
        }

        public Builder actions(List<UiAction> values) {
            this.actions.clear();
            this.actions.addAll(values);
            return this;
        }

        public UiStyle build() {
            return new UiStyle(
                anchor, offsetX, offsetY, width, height, zIndex, scale,
                visible, bedrockFormFallback, tooltip, tags, actions);
        }
    }
}
