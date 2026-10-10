package dev.uas.api.block;

import dev.uas.api.UasValidationException;
import java.util.List;
import java.util.Objects;

/**
 * The mechanics half of a custom block: what you collide with, how hard it is,
 * how much light it emits and how it moves.
 *
 * <p>Every value here has a Bedrock counterpart in
 * {@code CustomBlockComponents}, so this record is also the translation contract
 * for the JIT block converter.
 */
public record BlockPhysics(
    List<BoundingBox> collisionBoxes,
    List<BoundingBox> selectionBoxes,
    float hardness,
    float blastResistance,
    int lightEmission,
    boolean solid,
    boolean waterloggable,
    float friction,
    float jumpFactor,
    float speedFactor,
    boolean redstoneConductive,
    boolean flammable,
    boolean requiresTool,
    boolean hasBlockEntity,
    String pistonBehaviour) {

    public BlockPhysics {
        collisionBoxes = collisionBoxes == null ? List.of() : List.copyOf(collisionBoxes);
        selectionBoxes = selectionBoxes == null ? List.of() : List.copyOf(selectionBoxes);
        if (hardness < -1f) {
            throw new UasValidationException("hardness must be >= -1 (bedrock), got " + hardness);
        }
        if (blastResistance < 0f) {
            throw new UasValidationException("blast resistance must be >= 0, got " + blastResistance);
        }
        if (lightEmission < 0 || lightEmission > 15) {
            throw new UasValidationException("light emission must be 0..15, got " + lightEmission);
        }
        if (friction < 0f || friction > 2f) {
            throw new UasValidationException("friction must be 0..2, got " + friction);
        }
        if (jumpFactor <= 0f) {
            throw new UasValidationException("jump factor must be > 0, got " + jumpFactor);
        }
        if (speedFactor <= 0f) {
            throw new UasValidationException("speed factor must be > 0, got " + speedFactor);
        }
        pistonBehaviour = pistonBehaviour == null ? "normal" : pistonBehaviour;
    }

    /** The default: a solid, full-cube, stone-like block. */
    public static BlockPhysics solidBlock() {
        return builder().build();
    }

    /** A non-solid decoration with no collision (flowers, torches). */
    public static BlockPhysics decoration() {
        return builder()
            .collisionBoxes(List.of())
            .selectionBoxes(List.of(BoundingBox.unit(0.2, 0, 0.2)))
            .solid(false)
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasCollision() {
        return solid && !collisionBoxes.isEmpty();
    }

    /** The union of the collision boxes, or the full cube when none are declared. */
    public BoundingBox collisionUnion() {
        if (collisionBoxes.isEmpty()) {
            return solid ? BoundingBox.full() : BoundingBox.empty();
        }
        BoundingBox union = collisionBoxes.get(0);
        for (int i = 1; i < collisionBoxes.size(); i++) {
            union = union.union(collisionBoxes.get(i));
        }
        return union;
    }

    /**
     * Bedrock's custom-block API models collision as a set of boxes, while its
     * {@code collision_box} component takes a single box. This reports whether a
     * faithful translation is possible; multiple boxes need the box list form.
     */
    public boolean hasSingleCollisionBox() {
        return collisionBoxes.size() <= 1;
    }

    public Builder toBuilder() {
        return new Builder()
            .collisionBoxes(collisionBoxes)
            .selectionBoxes(selectionBoxes)
            .hardness(hardness)
            .blastResistance(blastResistance)
            .lightEmission(lightEmission)
            .solid(solid)
            .waterloggable(waterloggable)
            .friction(friction)
            .jumpFactor(jumpFactor)
            .speedFactor(speedFactor)
            .redstoneConductive(redstoneConductive)
            .flammable(flammable)
            .requiresTool(requiresTool)
            .hasBlockEntity(hasBlockEntity)
            .pistonBehaviour(pistonBehaviour);
    }

    public static final class Builder {
        private List<BoundingBox> collisionBoxes = List.of(BoundingBox.full());
        private List<BoundingBox> selectionBoxes = List.of(BoundingBox.full());
        private float hardness = 2.0f;
        private float blastResistance = 6.0f;
        private int lightEmission;
        private boolean solid = true;
        private boolean waterloggable;
        private float friction = 0.6f;
        private float jumpFactor = 1.0f;
        private float speedFactor = 1.0f;
        private boolean redstoneConductive = true;
        private boolean flammable;
        private boolean requiresTool = true;
        private boolean hasBlockEntity;
        private String pistonBehaviour = "normal";

        public Builder collisionBoxes(List<BoundingBox> boxes) {
            this.collisionBoxes = boxes == null ? List.of() : List.copyOf(boxes);
            return this;
        }

        public Builder collisionBox(BoundingBox box) {
            this.collisionBoxes = List.of(Objects.requireNonNull(box, "box"));
            return this;
        }

        public Builder selectionBoxes(List<BoundingBox> boxes) {
            this.selectionBoxes = boxes == null ? List.of() : List.copyOf(boxes);
            return this;
        }

        public Builder selectionBox(BoundingBox box) {
            this.selectionBoxes = List.of(Objects.requireNonNull(box, "box"));
            return this;
        }

        public Builder hardness(float value) {
            this.hardness = value;
            return this;
        }

        public Builder blastResistance(float value) {
            this.blastResistance = value;
            return this;
        }

        public Builder lightEmission(int value) {
            this.lightEmission = value;
            return this;
        }

        public Builder solid(boolean value) {
            this.solid = value;
            return this;
        }

        public Builder waterloggable(boolean value) {
            this.waterloggable = value;
            return this;
        }

        public Builder friction(float value) {
            this.friction = value;
            return this;
        }

        public Builder jumpFactor(float value) {
            this.jumpFactor = value;
            return this;
        }

        public Builder speedFactor(float value) {
            this.speedFactor = value;
            return this;
        }

        public Builder redstoneConductive(boolean value) {
            this.redstoneConductive = value;
            return this;
        }

        public Builder flammable(boolean value) {
            this.flammable = value;
            return this;
        }

        public Builder requiresTool(boolean value) {
            this.requiresTool = value;
            return this;
        }

        public Builder hasBlockEntity(boolean value) {
            this.hasBlockEntity = value;
            return this;
        }

        public Builder pistonBehaviour(String value) {
            this.pistonBehaviour = value;
            return this;
        }

        public BlockPhysics build() {
            return new BlockPhysics(
                collisionBoxes, selectionBoxes, hardness, blastResistance, lightEmission,
                solid, waterloggable, friction, jumpFactor, speedFactor,
                redstoneConductive, flammable, requiresTool, hasBlockEntity, pistonBehaviour);
        }
    }
}
