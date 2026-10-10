package dev.uas.protocol.packet;

import dev.uas.api.NamespacedKey;
import dev.uas.api.ui.UiPresentation;
import dev.uas.protocol.UasCodec;
import dev.uas.protocol.UasPacket;
import dev.uas.protocol.UasProtocol;
import dev.uas.protocol.UasProtocolException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opens, updates or closes a declared UI schema, carrying the bound state values.
 *
 * <p>Only <em>changed</em> values are sent: the schema declares its initial values
 * and both sides start from those, so a HUD with twenty bound fields costs a few
 * bytes per tick rather than a full snapshot.
 */
public final class UasUiStatePacket implements UasPacket {

    public static final int ID = 3;

    public enum Action {
        /** Show the schema, seeding any values present. */
        OPEN,
        /** Hide the schema and drop its state. */
        CLOSE,
        /** Merge the carried values into the current state and re-render. */
        UPDATE,
        /** The client asks the server for a full state snapshot. */
        REQUEST_SYNC,
        /** A full snapshot answering {@link Action#REQUEST_SYNC}. */
        RESPONSE_SYNC,
        /** A form/overlay response travelling back with the action that fired. */
        ACTION_RESULT
    }

    private NamespacedKey schemaKey = NamespacedKey.uas("missing");
    private Action action = Action.OPEN;
    private UiPresentation presentation = UiPresentation.MODAL;
    private final Map<String, String> values = new LinkedHashMap<>();
    private String actionId = "";

    public UasUiStatePacket() {
    }

    public static UasUiStatePacket open(NamespacedKey schemaKey, UiPresentation presentation) {
        UasUiStatePacket packet = new UasUiStatePacket();
        packet.schemaKey = schemaKey;
        packet.action = Action.OPEN;
        packet.presentation = presentation;
        return packet;
    }

    public static UasUiStatePacket close(NamespacedKey schemaKey) {
        UasUiStatePacket packet = new UasUiStatePacket();
        packet.schemaKey = schemaKey;
        packet.action = Action.CLOSE;
        return packet;
    }

    public static UasUiStatePacket update(NamespacedKey schemaKey, Map<String, String> values) {
        UasUiStatePacket packet = new UasUiStatePacket();
        packet.schemaKey = schemaKey;
        packet.action = Action.UPDATE;
        packet.values.putAll(values);
        return packet;
    }

    public static UasUiStatePacket actionResult(NamespacedKey schemaKey, String actionId) {
        UasUiStatePacket packet = new UasUiStatePacket();
        packet.schemaKey = schemaKey;
        packet.action = Action.ACTION_RESULT;
        packet.actionId = actionId == null ? "" : actionId;
        return packet;
    }

    public static UasUiStatePacket requestSync(NamespacedKey schemaKey) {
        UasUiStatePacket packet = new UasUiStatePacket();
        packet.schemaKey = schemaKey;
        packet.action = Action.REQUEST_SYNC;
        return packet;
    }

    public NamespacedKey schemaKey() {
        return schemaKey;
    }

    public Action action() {
        return action;
    }

    public UiPresentation presentation() {
        return presentation;
    }

    public Map<String, String> values() {
        return Map.copyOf(values);
    }

    public String actionId() {
        return actionId;
    }

    public UasUiStatePacket put(String key, String value) {
        if (values.size() >= UasProtocol.MAX_STATE_ENTRIES) {
            throw new IllegalStateException(
                "UI state packet exceeds " + UasProtocol.MAX_STATE_ENTRIES + " entries");
        }
        values.put(key, value);
        return this;
    }

    @Override
    public int id() {
        return ID;
    }

    @Override
    public void write(UasCodec codec) {
        codec.writeNamespacedKey(schemaKey);
        codec.writeEnum(action);
        codec.writeEnum(presentation);
        codec.writeString(actionId);
        codec.writeVarInt(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            codec.writeString(entry.getKey());
            codec.writeString(entry.getValue());
        }
    }

    @Override
    public void read(UasCodec codec) {
        this.schemaKey = codec.readNamespacedKey();
        this.action = codec.readEnum(Action.class);
        this.presentation = codec.readEnum(UiPresentation.class);
        this.actionId = codec.readString();
        int count = codec.readVarInt();
        if (count < 0 || count > UasProtocol.MAX_STATE_ENTRIES) {
            throw UasProtocolException.oversized("ui state entries", count, UasProtocol.MAX_STATE_ENTRIES);
        }
        values.clear();
        for (int i = 0; i < count; i++) {
            String key = codec.readString();
            String value = codec.readString();
            values.put(key, value);
        }
        if (action == Action.ACTION_RESULT && actionId.isEmpty()) {
            throw new UasProtocolException("ACTION_RESULT for " + schemaKey + " must carry an action id");
        }
    }

    @Override
    public String toString() {
        return "UasUiStatePacket[" + action + " " + schemaKey + " " + presentation
            + " values=" + values + (actionId.isEmpty() ? "" : " actionId='" + actionId + "'") + "]";
    }
}
