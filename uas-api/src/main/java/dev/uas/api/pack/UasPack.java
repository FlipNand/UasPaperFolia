package dev.uas.api.pack;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import dev.uas.api.block.CustomBlock;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.ui.UiSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One complete, self-describing asset pack: the unit that gets compiled once and
 * then served to Java clients over the Minecraft port and to Bedrock clients
 * through Geyser.
 *
 * <p>Holds declarations plus raw assets; it never holds a compiled archive. The
 * compiler produces that, and the resulting {@link PackSource} replaces this at the
 * delivery layer.
 */
public record UasPack(
    String id,
    String displayName,
    String description,
    PackFormat packFormat,
    List<CustomBlock> blocks,
    List<CustomSound> sounds,
    List<UiSchema> ui,
    List<AssetSource> assets,
    String bedrockPackNamespace) {

    /** Vanilla truncates the pack description; we fail early instead of truncating. */
    public static final int MAX_DESCRIPTION_LENGTH = 512;

    public UasPack {
        if (id == null || id.isBlank()) {
            throw new UasValidationException("pack id must not be blank");
        }
        if (!id.matches("[a-z0-9_-]{1,64}")) {
            throw new UasValidationException(
                "pack id must match [a-z0-9_-]{1,64}, got '" + id + "'");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new UasValidationException("pack '" + id + "' must declare a display name");
        }
        description = description == null ? "" : description;
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new UasValidationException(
                "pack description is " + description.length() + " chars; the limit is " + MAX_DESCRIPTION_LENGTH);
        }
        packFormat = packFormat == null ? PackFormat.javaDefault() : packFormat;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        sounds = sounds == null ? List.of() : List.copyOf(sounds);
        ui = ui == null ? List.of() : List.copyOf(ui);
        assets = assets == null ? List.of() : List.copyOf(assets);
        bedrockPackNamespace = bedrockPackNamespace == null ? "uas" : bedrockPackNamespace;

        Set<NamespacedKey> blockKeys = new HashSet<>();
        for (CustomBlock block : blocks) {
            if (!blockKeys.add(block.key())) {
                throw new UasValidationException("pack '" + id + "' declares block " + block.key() + " twice");
            }
        }
        Set<NamespacedKey> soundKeys = new HashSet<>();
        for (CustomSound sound : sounds) {
            if (!soundKeys.add(sound.key())) {
                throw new UasValidationException("pack '" + id + "' declares sound " + sound.key() + " twice");
            }
        }
        Set<NamespacedKey> uiKeys = new HashSet<>();
        for (UiSchema schema : ui) {
            if (!uiKeys.add(schema.key())) {
                throw new UasValidationException("pack '" + id + "' declares ui " + schema.key() + " twice");
            }
        }
        Set<String> assetPaths = new HashSet<>();
        for (AssetSource asset : assets) {
            if (!assetPaths.add(asset.path())) {
                throw new UasValidationException(
                    "pack '" + id + "' declares asset '" + asset.path() + "' twice");
            }
        }
    }

    public static Builder builder(String id, String displayName) {
        return new Builder(id, displayName);
    }

    /** An empty pack with only a pack.mcmeta, useful as a smoke test. */
    public static UasPack empty(String id, String displayName) {
        return new UasPack(id, displayName, "", PackFormat.javaDefault(), List.of(), List.of(), List.of(), List.of(), "uas");
    }

    public Optional<CustomBlock> block(NamespacedKey key) {
        return blocks.stream().filter(block -> block.key().equals(key)).findFirst();
    }

    public Optional<CustomSound> sound(NamespacedKey key) {
        return sounds.stream().filter(sound -> sound.key().equals(key)).findFirst();
    }

    public Optional<UiSchema> uiSchema(NamespacedKey key) {
        return ui.stream().filter(schema -> schema.key().equals(key)).findFirst();
    }

    /** Sound event keys referenced by every block's sound group. */
    public Set<NamespacedKey> blockReferencedSoundKeys() {
        Set<NamespacedKey> keys = new java.util.TreeSet<>();
        for (CustomBlock block : blocks) {
            keys.addAll(block.sounds().eventKeys());
        }
        return keys;
    }

    /**
     * Sound keys referenced by a block but not declared in {@link #sounds()}.
     *
     * <p>Not fatal: the keys may intentionally refer to vanilla events. The engine
     * logs this at startup so a typo is visible rather than producing silence.
     */
    public Set<NamespacedKey> undeclaredSoundReferences() {
        Set<NamespacedKey> referenced = blockReferencedSoundKeys();
        referenced.removeIf(key -> "minecraft".equals(key.namespace()));
        Set<NamespacedKey> declared = new HashSet<>();
        for (CustomSound sound : sounds) {
            declared.add(sound.key());
        }
        referenced.removeAll(declared);
        return referenced;
    }

    /** Texture keys referenced by geometry, excluding animation descriptors. */
    public Set<NamespacedKey> referencedTextureKeys() {
        Set<NamespacedKey> keys = new java.util.TreeSet<>();
        for (CustomBlock block : blocks) {
            keys.addAll(block.geometry().referencedTextures());
            keys.addAll(block.geometry().animatedTextures().keySet());
        }
        return keys;
    }

    public int totalAssetCount() {
        return assets.size() + blocks.size() + sounds.size() + ui.size();
    }

    public Builder toBuilder() {
        return new Builder(id, displayName)
            .description(description)
            .packFormat(packFormat)
            .blocks(blocks)
            .sounds(sounds)
            .ui(ui)
            .assets(assets)
            .bedrockPackNamespace(bedrockPackNamespace);
    }

    public static final class Builder {
        private final String id;
        private final String displayName;
        private String description = "";
        private PackFormat packFormat = PackFormat.javaDefault();
        private final List<CustomBlock> blocks = new ArrayList<>();
        private final List<CustomSound> sounds = new ArrayList<>();
        private final List<UiSchema> ui = new ArrayList<>();
        private final List<AssetSource> assets = new ArrayList<>();
        private String bedrockPackNamespace = "uas";

        private Builder(String id, String displayName) {
            this.id = Objects.requireNonNull(id, "id");
            this.displayName = Objects.requireNonNull(displayName, "displayName");
        }

        public Builder description(String value) {
            this.description = value == null ? "" : value;
            return this;
        }

        public Builder packFormat(PackFormat value) {
            this.packFormat = value;
            return this;
        }

        public Builder block(CustomBlock value) {
            this.blocks.add(Objects.requireNonNull(value, "block"));
            return this;
        }

        public Builder blocks(List<CustomBlock> values) {
            this.blocks.clear();
            this.blocks.addAll(values);
            return this;
        }

        public Builder sound(CustomSound value) {
            this.sounds.add(Objects.requireNonNull(value, "sound"));
            return this;
        }

        public Builder sounds(List<CustomSound> values) {
            this.sounds.clear();
            this.sounds.addAll(values);
            return this;
        }

        public Builder uiSchema(UiSchema value) {
            this.ui.add(Objects.requireNonNull(value, "ui"));
            return this;
        }

        public Builder ui(List<UiSchema> values) {
            this.ui.clear();
            this.ui.addAll(values);
            return this;
        }

        public Builder asset(AssetSource value) {
            this.assets.add(Objects.requireNonNull(value, "asset"));
            return this;
        }

        public Builder assets(List<AssetSource> values) {
            this.assets.clear();
            this.assets.addAll(values);
            return this;
        }

        public Builder bedrockPackNamespace(String value) {
            this.bedrockPackNamespace = value;
            return this;
        }

        public UasPack build() {
            return new UasPack(
                id, displayName, description, packFormat, blocks, sounds, ui, assets, bedrockPackNamespace);
        }
    }
}
