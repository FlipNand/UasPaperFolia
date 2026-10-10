package dev.uas.api.ui;

import dev.uas.api.UasValidationException;

/** How a container arranges its children. */
public record Layout(Direction direction, double gap, int columns, Alignment alignment) {

    public enum Direction {
        /** Children position themselves absolutely via their own anchors. */
        ABSOLUTE,
        ROW,
        COLUMN,
        /** {@link #columns()} children per row, wrapping downwards. */
        GRID,
        /** Children stack on top of each other; used for layered overlays. */
        STACK
    }

    public enum Alignment {
        START,
        CENTER,
        END,
        SPACE_BETWEEN
    }

    public Layout {
        if (direction == null) {
            throw new UasValidationException("layout direction must not be null");
        }
        alignment = alignment == null ? Alignment.START : alignment;
        if (gap < 0 || !Double.isFinite(gap)) {
            throw new UasValidationException("layout gap must be finite and >= 0, got " + gap);
        }
        if (direction == Direction.GRID && columns <= 0) {
            throw new UasValidationException("grid layout needs a positive column count, got " + columns);
        }
    }

    public static Layout absolute() {
        return new Layout(Direction.ABSOLUTE, 0, 0, Alignment.START);
    }

    public static Layout row(double gap) {
        return new Layout(Direction.ROW, gap, 0, Alignment.START);
    }

    public static Layout column(double gap) {
        return new Layout(Direction.COLUMN, gap, 0, Alignment.START);
    }

    public static Layout grid(int columns, double gap) {
        return new Layout(Direction.GRID, gap, columns, Alignment.START);
    }

    public static Layout stack() {
        return new Layout(Direction.STACK, 0, 0, Alignment.CENTER);
    }

    public boolean flowsChildren() {
        return direction != Direction.ABSOLUTE && direction != Direction.STACK;
    }

    public Layout withAlignment(Alignment newAlignment) {
        return new Layout(direction, gap, columns, newAlignment);
    }
}
