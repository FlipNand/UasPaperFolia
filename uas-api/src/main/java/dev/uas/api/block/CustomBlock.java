package dev.uas.api.block;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A complete declarative custom block: visuals, physics, sounds, state and the
 * Java backend preference used to realise it.
 *
 * <p>This is the single description both sides consume. The Paper side compiles it
 * into real block states plus display entities; the Geyser side compiles it into a
 * Bedrock custom block definition, {@code geometry.json} and
 * {@code terrain_texture.json} entries.
 */
public record CustomBlock(
    NamespacedKey key,
    String displayName,
    BlockGeometry geometry,
    BlockPhysics physics,
    BlockSoundGroup sounds,
    Set<BlockBackend> backends,
    List<BlockProperty> properties,
    List<BlockVariant> variants,
    String creativeCategory,
    String creativeGroup,
    boolean includeInCreativeInventory,
    String itemModel) {

    public CustomBlock {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(physics, "physics");
        Objects.requireNonNull(sounds, "sounds");
        if (displayName == null || displayName.isBlank()) {
            throw new UasValidationException("block '" + key + "' must declare a display name");
        }
        if (backends == null || backends.isEmpty()) {
            throw new UasValidationException("block '" + key + "' must declare at least one backend");
        }
        // EnumSet keeps the declaration deterministic regardless of input order.
        backends = Set.copyOf(EnumSet.copyOf(backends));
        properties = properties == null ? List.of() : List.copyOf(properties);
        variants = variants == null ? List.of() : List.copyOf(variants);
        creativeCategory = creativeCategory == null ? "construction" : creativeCategory;
        creativeGroup = creativeGroup == null ? "itemGroup.name.stone" : creativeGroup;

        Set<String> propertyNames = new java.util.HashSet<>();
        for (BlockProperty property : properties) {
            if (!propertyNames.add(property.name())) {
                throw new UasValidationException(
                    "block '" + key + "' declares property '" + property.name() + "' twice");
            }
        }
        for (BlockVariant variant : variants) {
            String referenced = variant.propertyName();
            if (!referenced.isEmpty() && !propertyNames.contains(referenced)) {
                throw new UasValidationException(
                    "block '" + key + "' variant references unknown property '" + referenced + "'");
            }
        }
    }

    public static Builder builder(NamespacedKey key) {
        return new Builder(key);
    }

    /**
     * The backend that will actually be used, given the backends the running server
     * can provide. Returns empty when nothing is available, which the caller must
     * treat as a hard configuration error rather than silently degrading.
     */
    public java.util.Optional<BlockBackend> selectBackend(Set<BlockBackend> available) {
        return BlockBackend.preferenceOrder().stream()
            .filter(backends::contains)
            .filter(available::contains)
            .findFirst();
    }

    /** The highest-fidelity declared backend, ignoring server capability. */
    public BlockBackend preferredBackend() {
        return BlockBackend.preferenceOrder().stream()
            .filter(backends::contains)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no declared backend for " + key));
    }

    /** True when the declared backends cannot express this block's geometry. */
    public boolean isGeometryUnsupportedByAllBackends() {
        if (geometry.isUnitCube()) {
            return false;
        }
        return backends.stream().noneMatch(BlockBackend::supportsCustomGeometry);
    }

    /** The Bedrock identifier, {@code namespace_path}. */
    public String bedrockIdentifier() {
        return key.toBedrockIdentifier();
    }

    /** The Java-side namespaced key of the backing vanilla block, per backend. */
    public NamespacedKey vanillaBackingKey(BlockBackend backend) {
        return NamespacedKey.parse(backend.backingBlock());
    }

    public Builder toBuilder() {
        return new Builder(key)
            .displayName(displayName)
            .geometry(geometry)
            .physics(physics)
            .sounds(sounds)
            .backends(backends)
            .properties(properties)
            .variants(variants)
            .creativeCategory(creativeCategory)
            .creativeGroup(creativeGroup)
            .includeInCreativeInventory(includeInCreativeInventory)
            .itemModel(itemModel);
    }

    public static final class Builder {
        private final NamespacedKey key;
        private String displayName;
        private BlockGeometry geometry;
        private BlockPhysics physics = BlockPhysics.solidBlock();
        private BlockSoundGroup sounds;
        private Set<BlockBackend> backends = EnumSet.of(BlockBackend.DISPLAY_ENTITY, BlockBackend.NOTE_BLOCK_MATRIX);
        private final List<BlockProperty> properties = new ArrayList<>();
        private final List<BlockVariant> variants = new ArrayList<>();
        private String creativeCategory = "construction";
        private String creativeGroup = "itemGroup.name.stone";
        private boolean includeInCreativeInventory = true;
        private String itemModel;

        private Builder(NamespacedKey key) {
            this.key = Objects.requireNonNull(key, "key");
            this.displayName = key.path();
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder geometry(BlockGeometry value) {
            this.geometry = Objects.requireNonNull(value, "geometry");
            return this;
        }

        public Builder physics(BlockPhysics value) {
            this.physics = Objects.requireNonNull(value, "physics");
            return this;
        }

        public Builder sounds(BlockSoundGroup value) {
            this.sounds = Objects.requireNonNull(value, "sounds");
            return this;
        }

        public Builder backends(BlockBackend... values) {
            this.backends = values.length == 0 ? EnumSet.noneOf(BlockBackend.class) : EnumSet.of(values[0], values);
            return this;
        }

        public Builder backends(Set<BlockBackend> values) {
            this.backends = values == null || values.isEmpty()
                ? EnumSet.noneOf(BlockBackend.class)
                : EnumSet.copyOf(values);
            return this;
        }

        public Builder property(BlockProperty value) {
            this.properties.add(Objects.requireNonNull(value, "property"));
            return this;
        }

        public Builder properties(List<BlockProperty> values) {
            this.properties.clear();
            this.properties.addAll(values);
            return this;
        }

        public Builder variant(BlockVariant value) {
            this.variants.add(Objects.requireNonNull(value, "variant"));
            return this;
        }

        public Builder variants(List<BlockVariant> values) {
            this.variants.clear();
            this.variants.addAll(values);
            return this;
        }

        public Builder creativeCategory(String value) {
            this.creativeCategory = value;
            return this;
        }

        public Builder creativeGroup(String value) {
            this.creativeGroup = value;
            return this;
        }

        public Builder includeInCreativeInventory(boolean value) {
            this.includeInCreativeInventory = value;
            return this;
        }

        public Builder itemModel(String value) {
            this.itemModel = value;
            return this;
        }

        public CustomBlock build() {
            if (geometry == null) {
                throw new UasValidationException("block '" + key + "' must declare geometry");
            }
            if (sounds == null) {
                throw new UasValidationException("block '" + key + "' must declare a sound group");
            }
            return new CustomBlock(
                key, displayName, geometry, physics, sounds, backends, properties, variants,
                creativeCategory, creativeGroup, includeInCreativeInventory, itemModel);
        }
    }
}
