package dev.uas.api.block;

/**
 * The blend/alpha mode the client renders a block with.
 *
 * <p>The Java and Bedrock renderers name these differently, so this enum is the
 * canonical declaration and each backend maps it to its own vocabulary:
 * Bedrock uses material instance {@code render_method} values
 * ({@code opaque}, {@code alpha_test}, {@code blend}, {@code alpha_test_single_sided}).
 */
public enum RenderLayer {
    /** Fully opaque, no alpha, and the fastest path. */
    SOLID("solid", "opaque"),
    /** Alpha-tested cutout (leaves, ladders). Mipped variant shares the Bedrock mapping. */
    CUTOUT("cutout", "alpha_test"),
    CUTOUT_MIPPED("cutout_mipped", "alpha_test"),
    /** Order-independent transparency (glass, water). */
    TRANSLUCENT("translucent", "blend");

    private final String javaName;
    private final String bedrockRenderMethod;

    RenderLayer(String javaName, String bedrockRenderMethod) {
        this.javaName = javaName;
        this.bedrockRenderMethod = bedrockRenderMethod;
    }

    public String javaName() {
        return javaName;
    }

    public String bedrockRenderMethod() {
        return bedrockRenderMethod;
    }

    public boolean isTransparent() {
        return this != SOLID;
    }

    public static RenderLayer fromJavaName(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        for (RenderLayer layer : values()) {
            if (layer.javaName.equals(normalized)) {
                return layer;
            }
        }
        return SOLID;
    }
}
