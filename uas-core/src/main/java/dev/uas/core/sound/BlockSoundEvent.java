package dev.uas.core.sound;

import dev.uas.api.block.BlockSoundGroup;
import dev.uas.api.block.SoundRef;
import java.util.Locale;

/** The interactions a block can emit a sound for. */
public enum BlockSoundEvent {
    BREAK,
    STEP,
    PLACE,
    HIT,
    FALL;

    /** The reference this block declares for the event. */
    public SoundRef refFrom(BlockSoundGroup group) {
        return switch (this) {
            case BREAK -> group.breakRef();
            case STEP -> group.stepRef();
            case PLACE -> group.placeRef();
            case HIT -> group.hitRef();
            case FALL -> group.fallRef();
        };
    }

    /** The Bedrock sound event suffix, e.g. {@code .step}. */
    public String bedrockSuffix() {
        return "." + name().toLowerCase(Locale.ROOT);
    }
}
