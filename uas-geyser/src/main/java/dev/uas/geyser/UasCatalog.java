package dev.uas.geyser;

import dev.uas.api.NamespacedKey;
import dev.uas.api.block.CustomBlock;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.ui.UiSchema;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The declarations the Geyser side compiles from.
 *
 * <p>Where does Geyser get the blocks, sounds and screens? There are only two honest
 * answers, and this class supports the first while making the second explicit.
 *
 * <ol>
 *   <li><strong>Co-located</strong> (Geyser-Spigot in the same JVM as the Paper plugin).
 *       The Paper side populates this catalog directly, and the extension compiles exactly
 *       the same objects the Java side did. One description, two renderers -- which is the
 *       entire premise of the project. This is the supported path.</li>
 *   <li><strong>Standalone Geyser.</strong> Then there is no shared memory, and a
 *       declaration has to cross a process boundary as a file. That serialisation is a
 *       deliberate follow-up rather than something this class pretends to do: a half-built
 *       manifest format that silently mismatched the Java side would be worse than an
 *       absent one, because the Bedrock blocks would be wrong in ways nobody sees until a
 *       player walks into them.</li>
 * </ol>
 *
 * <p>The catalog is concurrent and snapshot-based for the same reason the pack registry is:
 * it is written on a server thread and read from Geyser's event threads, and a reader must
 * never see a half-updated set.
 */
public final class UasCatalog {

    private final Map<NamespacedKey, CustomBlock> blocks = new ConcurrentHashMap<>();
    private final Map<NamespacedKey, CustomSound> sounds = new ConcurrentHashMap<>();
    private final Map<NamespacedKey, UiSchema> schemas = new ConcurrentHashMap<>();
    private final Map<NamespacedKey, String> geometryIdentifiers = new ConcurrentHashMap<>();

    /** Adds or replaces a block, returning the Bedrock geometry identifier it will use. */
    public String put(CustomBlock block) {
        Objects.requireNonNull(block, "block");
        String identifier = geometryIdentifier(block);
        blocks.put(block.key(), block);
        geometryIdentifiers.put(block.key(), identifier);
        return identifier;
    }

    public CustomSound put(CustomSound sound) {
        Objects.requireNonNull(sound, "sound");
        sounds.put(sound.key(), sound);
        return sound;
    }

    public UiSchema put(UiSchema schema) {
        Objects.requireNonNull(schema, "schema");
        schemas.put(schema.key(), schema);
        return schema;
    }

    public Collection<CustomBlock> blocks() {
        return Map.copyOf(blocks).values();
    }

    public Collection<CustomSound> sounds() {
        return Map.copyOf(sounds).values();
    }

    public Collection<UiSchema> schemas() {
        return Map.copyOf(schemas).values();
    }

    public Optional<CustomBlock> block(NamespacedKey key) {
        return Optional.ofNullable(blocks.get(key));
    }

    public Optional<String> geometryIdentifier(NamespacedKey key) {
        return Optional.ofNullable(geometryIdentifiers.get(key));
    }

    public void clear() {
        blocks.clear();
        sounds.clear();
        schemas.clear();
        geometryIdentifiers.clear();
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && sounds.isEmpty() && schemas.isEmpty();
    }

    /** The deterministic Bedrock geometry identifier for a block. */
    public static String geometryIdentifier(CustomBlock block) {
        return "geometry.uas." + block.key().namespace() + "." + block.key().path();
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("catalog.blocks", Integer.toString(blocks.size()));
        report.put("catalog.sounds", Integer.toString(sounds.size()));
        report.put("catalog.schemas", Integer.toString(schemas.size()));
        return report;
    }
}
