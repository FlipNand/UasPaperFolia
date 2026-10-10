package dev.uas.api.ui;

import dev.uas.api.UasValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What happens when the player activates a UI element.
 *
 * <p>Actions are declared once and dispatched per platform: on Java the server
 * executes them directly, on Bedrock the Cumulus form response carries the action
 * id back and the same dispatch runs. That is why the action carries an id and a
 * payload rather than a callback.
 */
public record UiAction(String id, Kind kind, Map<String, String> payload, boolean requiresServerConfirmation) {

    public enum Kind {
        /** Runs a server command as the player. */
        COMMAND,
        /** Emits a UAS custom packet. */
        PACKET,
        /** Plays a sound locally. */
        SOUND,
        /** Opens another declared UI schema. */
        OPEN_MENU,
        /** Closes the current UI. */
        CLOSE,
        /** Writes a value into the player's UI state, which re-renders bound elements. */
        SET_STATE,
        /** A platform-specific action the host plugin handles. */
        CUSTOM
    }

    public UiAction {
        if (id == null || id.isBlank()) {
            throw new UasValidationException("ui action id must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        if (kind == Kind.SET_STATE && !payload.containsKey("key")) {
            throw new UasValidationException("SET_STATE action '" + id + "' must declare a 'key' payload entry");
        }
        if (kind == Kind.PACKET && !payload.containsKey("packet")) {
            throw new UasValidationException("PACKET action '" + id + "' must declare a 'packet' payload entry");
        }
    }

    public static UiAction close() {
        return new UiAction("uas:close", Kind.CLOSE, Map.of(), false);
    }

    public static UiAction setState(String key, String value) {
        return new UiAction("uas:set_" + key, Kind.SET_STATE, Map.of("key", key, "value", value), false);
    }

    public static UiAction packet(String packetName, Map<String, String> fields) {
        Map<String, String> merged = new LinkedHashMap<>(fields);
        merged.put("packet", packetName);
        return new UiAction("uas:packet_" + packetName, Kind.PACKET, merged, false);
    }

    public static UiAction command(String command) {
        return new UiAction("uas:cmd_" + command.hashCode(), Kind.COMMAND, Map.of("command", command), true);
    }

    public static Builder builder(String id, Kind kind) {
        return new Builder(id, kind);
    }

    /** True when the action can be executed without a round trip to the server. */
    public boolean isClientSideOnly() {
        return !requiresServerConfirmation
            && (kind == Kind.CLOSE || kind == Kind.OPEN_MENU || kind == Kind.SOUND || kind == Kind.SET_STATE);
    }

    public static final class Builder {
        private final String id;
        private final Kind kind;
        private final Map<String, String> payload = new LinkedHashMap<>();
        private boolean requiresServerConfirmation;

        private Builder(String id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }

        public Builder put(String key, String value) {
            this.payload.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder requiresServerConfirmation(boolean value) {
            this.requiresServerConfirmation = value;
            return this;
        }

        public UiAction build() {
            return new UiAction(id, kind, payload, requiresServerConfirmation);
        }
    }
}
