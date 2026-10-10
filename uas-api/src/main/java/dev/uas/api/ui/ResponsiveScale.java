package dev.uas.api.ui;

import dev.uas.api.UasValidationException;

/**
 * Scale mapping between a design viewport and the client's actual resolution.
 *
 * <p>Authoring at one resolution and rendering at another is the usual source of
 * broken HUDs. Declaring the design viewport here lets the layout pass compute a
 * single clamped scale factor, and lets the Bedrock bridge emit pixel values that
 * already account for the phone/console split.
 */
public record ResponsiveScale(
    double designWidth,
    double designHeight,
    double minScale,
    double maxScale,
    boolean preserveAspect) {

    public static final double DEFAULT_DESIGN_WIDTH = 1920;
    public static final double DEFAULT_DESIGN_HEIGHT = 1080;

    public ResponsiveScale {
        if (designWidth <= 0 || designHeight <= 0) {
            throw new UasValidationException(
                "design viewport must be positive, got " + designWidth + "x" + designHeight);
        }
        if (!Double.isFinite(minScale) || !Double.isFinite(maxScale)) {
            throw new UasValidationException("scale bounds must be finite");
        }
        if (minScale <= 0 || maxScale < minScale) {
            throw new UasValidationException(
                "invalid scale bounds " + minScale + ".." + maxScale);
        }
    }

    /** An identity scale: 1:1 on the design viewport, clamped to the same value everywhere. */
    public static ResponsiveScale identity() {
        return new ResponsiveScale(DEFAULT_DESIGN_WIDTH, DEFAULT_DESIGN_HEIGHT, 1, 1, true);
    }

    public static ResponsiveScale of(double designWidth, double designHeight) {
        return new ResponsiveScale(designWidth, designHeight, 0.5, 3.0, true);
    }

    /**
     * The scale factor for a concrete viewport.
     *
     * <p>With {@link #preserveAspect} the factor is the smaller of the two axis
     * ratios, so nothing overflows; otherwise the axes are averaged, which keeps
     * total area roughly constant on very wide or very tall screens.
     */
    public double scaleFor(double viewportWidth, double viewportHeight) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException(
                "viewport must be positive, got " + viewportWidth + "x" + viewportHeight);
        }
        double byWidth = viewportWidth / designWidth;
        double byHeight = viewportHeight / designHeight;
        double raw = preserveAspect ? Math.min(byWidth, byHeight) : Math.sqrt(byWidth * byHeight);
        return Math.max(minScale, Math.min(maxScale, raw));
    }

    public boolean isIdentityAt(double viewportWidth, double viewportHeight) {
        return Math.abs(scaleFor(viewportWidth, viewportHeight) - 1.0) < 1e-9;
    }
}
