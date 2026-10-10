package dev.uas.api.sound;

import java.util.Locale;

/**
 * A Minecraft sound mixer channel.
 *
 * <p>Java clients honour these channel volumes; Bedrock has no equivalent, so the
 * Bedrock bridge applies the volume attenuation itself and ignores the category.
 */
public enum SoundCategory {
    MASTER,
    MUSIC,
    RECORDS,
    WEATHER,
    BLOCKS,
    HOSTILE,
    NEUTRAL,
    PLAYERS,
    AMBIENT,
    VOICE,
    UI;

    public String javaName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SoundCategory fromJavaName(String name) {
        if (name == null) {
            return MASTER;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (SoundCategory category : values()) {
            if (category.name().equals(normalized)) {
                return category;
            }
        }
        return MASTER;
    }
}
