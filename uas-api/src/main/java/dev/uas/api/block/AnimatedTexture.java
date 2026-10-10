package dev.uas.api.block;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import java.util.List;
import java.util.Objects;

/**
 * A flipbook (sprite-sheet) texture animation.
 *
 * <p>Minecraft and Bedrock both animate by slicing a tall sprite sheet into square
 * frames, but they disagree on how the frame list and timing are expressed.
 * This record is the neutral description; {@code .mcmeta} and
 * {@code flipbook_textures.json} are both generated from it.
 *
 * @param texture the animated texture key
 * @param frameWidth frame width in pixels
 * @param frameHeight frame height in pixels; {@code 0} means "same as {@link #frameWidth}"
 * @param frameCount number of frames in the sheet
 * @param frameTimeTicks how long each frame is shown
 * @param interpolate whether frames cross-fade (Java only; Bedrock falls back to hard cuts)
 * @param frameOrder explicit play order, or {@code null} for sequential
 */
public record AnimatedTexture(
    NamespacedKey texture,
    int frameWidth,
    int frameHeight,
    int frameCount,
    int frameTimeTicks,
    boolean interpolate,
    List<Integer> frameOrder) {

    public AnimatedTexture {
        Objects.requireNonNull(texture, "texture");
        if (frameWidth <= 0) {
            throw new UasValidationException("frame width must be positive: " + frameWidth);
        }
        if (frameHeight < 0) {
            throw new UasValidationException("frame height must not be negative: " + frameHeight);
        }
        if (frameCount <= 0) {
            throw new UasValidationException("frame count must be positive: " + frameCount);
        }
        if (frameTimeTicks <= 0) {
            throw new UasValidationException("frame time must be positive: " + frameTimeTicks);
        }
        if (frameOrder != null) {
            for (int index : frameOrder) {
                if (index < 0 || index >= frameCount) {
                    throw new UasValidationException(
                        "frame order index " + index + " outside 0.." + (frameCount - 1));
                }
            }
            frameOrder = List.copyOf(frameOrder);
        }
    }

    public static AnimatedTexture flipbook(NamespacedKey texture, int frameSize, int frameCount, int frameTimeTicks) {
        return new AnimatedTexture(texture, frameSize, frameSize, frameCount, frameTimeTicks, false, null);
    }

    public static Builder builder(NamespacedKey texture) {
        return new Builder(texture);
    }

    /** The effective frame height, resolving the "square frame" shorthand. */
    public int effectiveFrameHeight() {
        return frameHeight == 0 ? frameWidth : frameHeight;
    }

    public int effectiveFrameTime() {
        return frameTimeTicks;
    }

    public List<Integer> effectiveFrameOrder() {
        if (frameOrder != null) {
            return frameOrder;
        }
        return java.util.stream.IntStream.range(0, frameCount).boxed().toList();
    }

    /** Total animation length in ticks. */
    public int totalTicks() {
        return frameTimeTicks * frameCount;
    }

    /** The vertical offset of a frame, in pixels, inside the sheet. */
    public int frameTopPixel(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= frameCount) {
            throw new IllegalArgumentException("frame index out of range: " + frameIndex);
        }
        return frameIndex * effectiveFrameHeight();
    }

    public static final class Builder {
        private final NamespacedKey texture;
        private int frameWidth;
        private int frameHeight;
        private int frameCount;
        private int frameTimeTicks = 1;
        private boolean interpolate;
        private List<Integer> frameOrder;

        private Builder(NamespacedKey texture) {
            this.texture = Objects.requireNonNull(texture, "texture");
        }

        public Builder frameSize(int width, int height) {
            this.frameWidth = width;
            this.frameHeight = height;
            return this;
        }

        public Builder squareFrames(int size) {
            this.frameWidth = size;
            this.frameHeight = 0;
            return this;
        }

        public Builder frameCount(int count) {
            this.frameCount = count;
            return this;
        }

        public Builder frameTimeTicks(int ticks) {
            this.frameTimeTicks = ticks;
            return this;
        }

        public Builder interpolate(boolean value) {
            this.interpolate = value;
            return this;
        }

        public Builder frameOrder(List<Integer> order) {
            this.frameOrder = order == null ? null : List.copyOf(order);
            return this;
        }

        public AnimatedTexture build() {
            return new AnimatedTexture(
                texture, frameWidth, frameHeight, frameCount, frameTimeTicks, interpolate, frameOrder);
        }
    }
}
