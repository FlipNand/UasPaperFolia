package dev.uas.api.ui;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import java.util.Objects;

/**
 * A nine-slice (stretchable panel) texture.
 *
 * <p>The client renders the four corners unscaled, stretches or tiles the four
 * edges, and stretches or tiles the centre. Both Java and Bedrock support this
 * shape, which makes it the portable way to build resizable panels.
 */
public record NineSlice(
    NamespacedKey texture,
    int textureWidth,
    int textureHeight,
    int left,
    int top,
    int right,
    int bottom,
    boolean tile) {

    public NineSlice {
        Objects.requireNonNull(texture, "texture");
        if (textureWidth <= 0 || textureHeight <= 0) {
            throw new UasValidationException(
                "nine-slice texture size must be positive, got " + textureWidth + "x" + textureHeight);
        }
        if (left < 0 || top < 0 || right < 0 || bottom < 0) {
            throw new UasValidationException("nine-slice borders must not be negative");
        }
        if (left + right > textureWidth) {
            throw new UasValidationException(
                "nine-slice horizontal borders (" + left + " + " + right
                    + ") exceed texture width " + textureWidth);
        }
        if (top + bottom > textureHeight) {
            throw new UasValidationException(
                "nine-slice vertical borders (" + top + " + " + bottom
                    + ") exceed texture height " + textureHeight);
        }
    }

    public static NineSlice of(NamespacedKey texture, int width, int height, int border) {
        return new NineSlice(texture, width, height, border, border, border, border, false);
    }

    public int centreWidth() {
        return textureWidth - left - right;
    }

    public int centreHeight() {
        return textureHeight - top - bottom;
    }

    /** The smallest size the slice can render without distorting its corners. */
    public int minimumWidth() {
        return left + right;
    }

    public int minimumHeight() {
        return top + bottom;
    }

    public boolean tooSmallFor(double width, double height) {
        return width < minimumWidth() || height < minimumHeight();
    }

    public NineSlice withTile(boolean value) {
        return new NineSlice(texture, textureWidth, textureHeight, left, top, right, bottom, value);
    }

    public boolean isUniformBorder() {
        return left == top && top == right && right == bottom;
    }
}
