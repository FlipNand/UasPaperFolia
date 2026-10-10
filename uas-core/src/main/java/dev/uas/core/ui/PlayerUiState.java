package dev.uas.core.ui;

import dev.uas.api.NamespacedKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one player currently sees.
 *
 * <p>Immutable and revision-stamped. The revision is what makes state sync cheap over
 * the network: the server sends a value only when it differs, and the client echoes
 * the revision it last saw so a stale update can be dropped rather than applied on
 * top of newer state.
 */
public record PlayerUiState(
    String playerId,
    NamespacedKey schemaKey,
    UiTarget target,
    UiRenderPath renderPath,
    long revision,
    Map<String, String> values) {

    public PlayerUiState {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(schemaKey, "schemaKey");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(renderPath, "renderPath");
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public Optional<String> value(String stateKey) {
        return Optional.ofNullable(values.get(stateKey));
    }

    /** The same state with one value replaced and the revision advanced. */
    public PlayerUiState withValue(String stateKey, String value) {
        Map<String, String> updated = new LinkedHashMap<>(values);
        updated.put(stateKey, value);
        return new PlayerUiState(playerId, schemaKey, target, renderPath, revision + 1, updated);
    }

    /** The same state re-targeted, used when a player reconnects from the other client. */
    public PlayerUiState retarget(UiTarget newTarget, UiRenderPath newPath, long newRevision) {
        return new PlayerUiState(playerId, schemaKey, newTarget, newPath, newRevision, values);
    }

    @Override
    public String toString() {
        return playerId + "@" + schemaKey + "[" + renderPath + " rev=" + revision
            + " values=" + values + "]";
    }
}
