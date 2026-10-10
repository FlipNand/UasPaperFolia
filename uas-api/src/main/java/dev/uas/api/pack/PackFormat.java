package dev.uas.api.pack;

import dev.uas.api.UasValidationException;
import java.util.List;

/**
 * The {@code pack_format} / {@code supported_formats} pair written into
 * {@code pack.mcmeta}.
 *
 * <p>Minecraft refuses packs whose format falls outside the supported range, and
 * the numbering changed with almost every release, so the engine treats it as
 * configuration rather than a constant. The presets below cover the documented
 * 1.20.4 line; newer servers should pass their own value from plugin config, and
 * {@link #javaDefault()} is the fallback when nothing is configured.
 */
public record PackFormat(int format, List<Integer> supportedFormats) {

    public static final int MIN_VALID_FORMAT = 4;
    public static final int MAX_VALID_FORMAT = 999;

    /** Resource pack format for the 1.20.4 protocol line. */
    public static final int JAVA_1_20_4 = 22;

    public PackFormat {
        if (format < MIN_VALID_FORMAT || format > MAX_VALID_FORMAT) {
            throw new UasValidationException(
                "pack format must be in " + MIN_VALID_FORMAT + ".." + MAX_VALID_FORMAT + ", got " + format);
        }
        supportedFormats = supportedFormats == null || supportedFormats.isEmpty()
            ? List.of(format)
            : List.copyOf(supportedFormats);
        for (int supported : supportedFormats) {
            if (supported < MIN_VALID_FORMAT || supported > MAX_VALID_FORMAT) {
                throw new UasValidationException("supported format out of range: " + supported);
            }
        }
    }

    public static PackFormat of(int format) {
        return new PackFormat(format, List.of(format));
    }

    /** Accepts every format from {@code min} to {@code max} inclusive. */
    public static PackFormat range(int format, int min, int max) {
        if (max < min) {
            throw new UasValidationException("format range max (" + max + ") is below min (" + min + ")");
        }
        return new PackFormat(
            format, java.util.stream.IntStream.rangeClosed(min, max).boxed().toList());
    }

    /**
     * The default used when the operator has not configured an explicit format.
     *
     * <p>Defaults to the 1.20.4 value with a wide supported range, which makes the
     * pack load on the 1.20.4 protocol line and on later releases that still accept
     * that range.
     */
    public static PackFormat javaDefault() {
        return new PackFormat(JAVA_1_20_4, List.of(JAVA_1_20_4, JAVA_1_20_4 + 1));
    }

    public boolean supports(int other) {
        return supportedFormats.contains(other);
    }

    /**
     * The {@code supported_formats} field: a single number when the range collapses,
     * otherwise a {@code [min, max]} pair, which is the form vanilla parses fastest.
     */
    public Object asJsonValue() {
        if (supportedFormats.size() == 1) {
            return supportedFormats.get(0);
        }
        int min = supportedFormats.stream().mapToInt(Integer::intValue).min().orElse(format);
        int max = supportedFormats.stream().mapToInt(Integer::intValue).max().orElse(format);
        return List.of(min, max);
    }
}
