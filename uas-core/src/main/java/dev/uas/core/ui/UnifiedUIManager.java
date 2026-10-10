package dev.uas.core.ui;

import dev.uas.api.NamespacedKey;
import dev.uas.api.ui.UiElement;
import dev.uas.api.ui.UiPresentation;
import dev.uas.api.ui.UiSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the declared UI schemas and what each player currently has open.
 *
 * <p>Two concerns live here and they are deliberately separated. The schema table is
 * configuration -- registered once at startup, read constantly, effectively immutable.
 * The per-player state is runtime -- small, mutable, and bounded, because a server
 * that keeps a map entry forever for every player who ever joins is a memory leak
 * wearing a feature's clothes. Per-player state is held in an access-ordered map with
 * a hard ceiling and an eviction counter, so a ticket is tracked and a runaway is
 * observable rather than a slow leak.
 *
 * <p>The interesting logic is {@link #planFor(UiSchema, UiTarget)}. Rendering the same
 * schema on two client families is not a translation, it is a negotiation: Java can
 * draw an overlay, Bedrock can only natively show a form or a toast, so an overlay
 * either becomes a custom UI screen backed by the resource pack or degrades to a
 * dialog. That choice is made explicitly, tagged as lossy when it is, and explained.
 */
public final class UnifiedUIManager {

    /** Ceiling on tracked player states. Beyond this, the least recently used is dropped. */
    public static final int DEFAULT_MAX_TRACKED_PLAYERS = 4096;

    /** Ceiling on distinct registered schemas; a schema table this large is a mistake. */
    public static final int MAX_SCHEMAS = 1024;

    private final Map<NamespacedKey, UiSchema> schemas = new ConcurrentHashMap<>();
    private final Map<String, PlayerUiState> playerStates;
    private final Map<String, UiTarget> targetOverrides = new ConcurrentHashMap<>();
    private final AtomicLong revisionCounter = new AtomicLong();
    private final AtomicLong opened = new AtomicLong();
    private final AtomicLong closed = new AtomicLong();
    private final AtomicLong stateUpdates = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong rejectedUpdates = new AtomicLong();
    private final int maxTrackedPlayers;

    public UnifiedUIManager() {
        this(DEFAULT_MAX_TRACKED_PLAYERS);
    }

    public UnifiedUIManager(int maxTrackedPlayers) {
        if (maxTrackedPlayers <= 0) {
            throw new IllegalArgumentException("maxTrackedPlayers must be > 0");
        }
        this.maxTrackedPlayers = maxTrackedPlayers;
        this.playerStates = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PlayerUiState> eldest) {
                if (size() > maxTrackedPlayers) {
                    evictions.incrementAndGet();
                    return true;
                }
                return false;
            }
        };
    }

    // ---------------------------------------------------------------- schemas

    /** Registers a schema, replacing any existing schema with the same key. */
    public UiSchema register(UiSchema schema) {
        Objects.requireNonNull(schema, "schema");
        if (!schemas.containsKey(schema.key()) && schemas.size() >= MAX_SCHEMAS) {
            throw new IllegalStateException(
                "refusing to register more than " + MAX_SCHEMAS + " ui schemas");
        }
        schemas.put(schema.key(), schema);
        return schema;
    }

    public Optional<UiSchema> unregister(NamespacedKey key) {
        return Optional.ofNullable(schemas.remove(key));
    }

    public Optional<UiSchema> schema(NamespacedKey key) {
        return Optional.ofNullable(schemas.get(key));
    }

    public UiSchema requireSchema(NamespacedKey key) {
        return schema(key).orElseThrow(() ->
            new IllegalArgumentException("no ui schema registered for '" + key + "'"));
    }

    public Set<NamespacedKey> schemaKeys() {
        return Set.copyOf(schemas.keySet());
    }

    public int schemaCount() {
        return schemas.size();
    }

    // ------------------------------------------------------------- rendering

    /**
     * Chooses a render path for a schema on a client family.
     *
     * <p>The degradation rules are deliberately small and stated here rather than
     * scattered:
     * <ul>
     *   <li>Java renders everything, so it always gets a faithful path: a menu when the
     *       presentation can own inventory slots, a HUD overlay otherwise.</li>
     *   <li>A schema owning inventory slots is decided first: no Bedrock surface can
     *       own server-side slots, so it degrades to an action bar line rather than a
     *       form that would silently drop the slots.</li>
     *   <li>Otherwise, Bedrock gets a native Cumulus form when the presentation is
     *       already native (a toast or a modal) or when the whole tree happens to be
     *       form-expressible and the author allowed the fallback. The latter is lossy
     *       and says so.</li>
     *   <li>Anything else on Bedrock -- notably an overlay -- needs injected custom UI
     *       JSON, since forms cannot express it.</li>
     * </ul>
     */
    public UiRenderPlan planFor(UiSchema schema, UiTarget target) {
        Objects.requireNonNull(schema, "schema");
        UiTarget resolved = target == null ? UiTarget.JAVA : target;
        UiPresentation presentation = schema.presentation();

        if (resolved == UiTarget.JAVA) {
            return presentation.supportsSlotGrid()
                ? UiRenderPlan.faithful(schema, resolved, UiRenderPath.JAVA_INVENTORY_MENU, schema.initialValues())
                : UiRenderPlan.faithful(schema, resolved, UiRenderPath.JAVA_HUD_OVERLAY, schema.initialValues());
        }

        // Slot ownership is checked first, ahead of the native-presentation shortcut.
        // A Bedrock form cannot carry server-side inventory slots, so rendering a
        // slot-bearing schema as one would silently drop the slots -- exactly the
        // silent degradation this planner exists to prevent.
        if (hasSlotGrid(schema)) {
            return UiRenderPlan.degraded(
                schema, resolved, UiRenderPath.BEDROCK_ACTION_BAR, schema.initialValues(),
                "Bedrock has no inventory-slot surface outside custom UI JSON, which cannot own "
                    + "server-side slots; the player sees a summary line instead");
        }
        if (presentation.isBedrockNative()) {
            return UiRenderPlan.faithful(
                schema, resolved, UiRenderPath.BEDROCK_CUMULUS_FORM, schema.initialValues());
        }
        if (schema.isFullyFormExpressible() && schema.bedrockModalFallback()) {
            return UiRenderPlan.degraded(
                schema, resolved, UiRenderPath.BEDROCK_CUMULUS_FORM, schema.initialValues(),
                "the " + presentation.bedrockName() + " presentation has no Bedrock equivalent, "
                    + "so the tree is shown as a modal form the player must dismiss");
        }
        return UiRenderPlan.faithful(
            schema, resolved, UiRenderPath.BEDROCK_CUSTOM_JSON, schema.initialValues());
    }

    private static boolean hasSlotGrid(UiSchema schema) {
        List<UiElement> elements = schema.elementList();
        for (UiElement element : elements) {
            if (element instanceof UiElement.SlotGrid) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- targets

    /** Records which client family a player is on, e.g. when Geyser reports a login. */
    public void setTarget(String playerId, UiTarget target) {
        Objects.requireNonNull(playerId, "playerId");
        if (target == null) {
            targetOverrides.remove(playerId);
            return;
        }
        targetOverrides.put(playerId, target);
        // An open UI must be re-planned for the new client family, so its revision is
        // advanced and its path recomputed rather than left pointing at the old one.
        synchronized (playerStates) {
            PlayerUiState current = playerStates.get(playerId);
            if (current != null && current.target() != target) {
                UiSchema schema = schemas.get(current.schemaKey());
                if (schema != null) {
                    UiRenderPlan plan = planFor(schema, target);
                    playerStates.put(playerId, current.retarget(
                        target, plan.path(), nextRevision()));
                }
            }
        }
    }

    public UiTarget target(String playerId) {
        return targetOverrides.getOrDefault(playerId, UiTarget.JAVA);
    }

    // ----------------------------------------------------------- player state

    /**
     * Opens a schema for a player and returns the render plan to execute.
     *
     * @throws IllegalArgumentException if the schema was never registered
     */
    public UiRenderPlan open(String playerId, NamespacedKey schemaKey) {
        UiSchema schema = requireSchema(schemaKey);
        UiTarget target = target(playerId);
        UiRenderPlan plan = planFor(schema, target);

        synchronized (playerStates) {
            playerStates.put(playerId, new PlayerUiState(
                playerId, schemaKey, target, plan.path(), nextRevision(), plan.state()));
        }
        opened.incrementAndGet();
        return plan;
    }

    /** Closes whatever a player has open. Returns true if something was closed. */
    public boolean close(String playerId) {
        synchronized (playerStates) {
            if (playerStates.remove(playerId) != null) {
                closed.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    /** Forgets a player entirely; call this on disconnect so nothing is retained. */
    public void forget(String playerId) {
        close(playerId);
        targetOverrides.remove(playerId);
    }

    public Optional<PlayerUiState> state(String playerId) {
        synchronized (playerStates) {
            return Optional.ofNullable(playerStates.get(playerId));
        }
    }

    public boolean isOpen(String playerId) {
        return state(playerId).isPresent();
    }

    /** The render plan for a player's currently open UI, if any. */
    public Optional<UiRenderPlan> plan(String playerId) {
        synchronized (playerStates) {
            PlayerUiState current = playerStates.get(playerId);
            if (current == null) {
                return Optional.empty();
            }
            UiSchema schema = schemas.get(current.schemaKey());
            if (schema == null) {
                // The schema was unregistered under an open UI. Treat it as closed
                // rather than rendering something that no longer exists.
                playerStates.remove(playerId);
                closed.incrementAndGet();
                return Optional.empty();
            }
            return Optional.of(new UiRenderPlan(
                schema, current.target(), current.renderPath(), current.values(), false, "restored"));
        }
    }

    /**
     * Sets a bound state value, advancing the revision.
     *
     * <p>Rejects keys the schema does not bind. A client can ask to set anything; the
     * server must only accept what the tree actually reads, or a malicious or buggy
     * client turns a UI into an arbitrary state store.
     *
     * @return the updated state, or empty if nothing is open or the key is not bound
     */
    public Optional<PlayerUiState> setState(String playerId, String stateKey, String value) {
        Objects.requireNonNull(stateKey, "stateKey");
        synchronized (playerStates) {
            PlayerUiState current = playerStates.get(playerId);
            if (current == null) {
                rejectedUpdates.incrementAndGet();
                return Optional.empty();
            }
            UiSchema schema = schemas.get(current.schemaKey());
            if (schema == null || !schema.boundStateKeys().contains(stateKey)) {
                rejectedUpdates.incrementAndGet();
                return Optional.empty();
            }
            PlayerUiState updated = current.withValue(stateKey, value);
            playerStates.put(playerId, updated);
            stateUpdates.incrementAndGet();
            return Optional.of(updated);
        }
    }

    /** Applies several bound keys at once, rejecting the whole batch if any key is unbound. */
    public Optional<PlayerUiState> setState(String playerId, Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        synchronized (playerStates) {
            PlayerUiState current = playerStates.get(playerId);
            if (current == null) {
                rejectedUpdates.incrementAndGet();
                return Optional.empty();
            }
            UiSchema schema = schemas.get(current.schemaKey());
            if (schema == null) {
                rejectedUpdates.incrementAndGet();
                return Optional.empty();
            }
            Set<String> bound = schema.boundStateKeys();
            for (String key : values.keySet()) {
                if (!bound.contains(key)) {
                    rejectedUpdates.incrementAndGet();
                    return Optional.empty();
                }
            }
            PlayerUiState updated = current;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                updated = updated.withValue(entry.getKey(), entry.getValue());
            }
            playerStates.put(playerId, updated);
            stateUpdates.incrementAndGet();
            return Optional.of(updated);
        }
    }

    /** Resets a player's state to the schema's declared initial values. */
    public Optional<PlayerUiState> resetState(String playerId) {
        synchronized (playerStates) {
            PlayerUiState current = playerStates.get(playerId);
            if (current == null) {
                return Optional.empty();
            }
            UiSchema schema = schemas.get(current.schemaKey());
            if (schema == null) {
                return Optional.empty();
            }
            PlayerUiState reset = new PlayerUiState(
                playerId, current.schemaKey(), current.target(), current.renderPath(),
                nextRevision(), schema.initialValues());
            playerStates.put(playerId, reset);
            return Optional.of(reset);
        }
    }

    public int trackedPlayerCount() {
        synchronized (playerStates) {
            return playerStates.size();
        }
    }

    private long nextRevision() {
        return revisionCounter.incrementAndGet();
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("schemas", Integer.toString(schemas.size()));
        report.put("trackedPlayers", Integer.toString(trackedPlayerCount()));
        report.put("maxTrackedPlayers", Integer.toString(maxTrackedPlayers));
        report.put("opened", Long.toString(opened.get()));
        report.put("closed", Long.toString(closed.get()));
        report.put("stateUpdates", Long.toString(stateUpdates.get()));
        report.put("rejectedUpdates", Long.toString(rejectedUpdates.get()));
        report.put("evictions", Long.toString(evictions.get()));
        report.put("revisions", Long.toString(revisionCounter.get()));
        return report;
    }
}
