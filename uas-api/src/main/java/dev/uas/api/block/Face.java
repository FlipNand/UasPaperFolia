package dev.uas.api.block;

import dev.uas.api.math.Vec3;

/** The six axis-aligned faces of a cuboid. */
public enum Face {
    DOWN(0, -1, 0),
    UP(1, 0, 1),
    NORTH(2, 0, 0),
    SOUTH(3, 0, -1),
    WEST(4, -1, 0),
    EAST(5, 1, 0);

    private final int index;
    private final int normalX;
    private final int normalZ;

    Face(int index, int normalX, int normalZ) {
        this.index = index;
        this.normalX = normalX;
        this.normalZ = normalZ;
    }

    public int index() {
        return index;
    }

    /** Outward normal in block space. */
    public Vec3 normal() {
        return switch (this) {
            case DOWN -> new Vec3(0, -1, 0);
            case UP -> new Vec3(0, 1, 0);
            case NORTH -> new Vec3(0, 0, -1);
            case SOUTH -> new Vec3(0, 0, 1);
            case WEST -> new Vec3(-1, 0, 0);
            case EAST -> new Vec3(1, 0, 0);
        };
    }

    public Face opposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    /** Bedrock geometry keys are lowercase with no underscores. */
    public String bedrockName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Java model and Bedrock geometry agree on the face order, which matters for UV index derivation. */
    public static Face byIndex(int index) {
        for (Face face : values()) {
            if (face.index == index) {
                return face;
            }
        }
        throw new IllegalArgumentException("no face with index " + index);
    }

    int normalComponentX() {
        return normalX;
    }

    int normalComponentZ() {
        return normalZ;
    }
}
