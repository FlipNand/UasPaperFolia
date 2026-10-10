package dev.uas.api.ui;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A complete declarative UI: one presentation, one root tree, and the bound state
 * keys it reads.
 *
 * <p>Validated as a whole because the interesting failures are cross-element:
 * duplicate ids, state keys nobody declares, a slot grid inside a HUD overlay.
 */
public record UiSchema(
    NamespacedKey key,
    String title,
    UiPresentation presentation,
    List<UiElement> root,
    boolean cancellable,
    boolean bedrockModalFallback,
    Map<String, String> initialValues,
    String bedrockNamespace) {

    public static final int MAX_ELEMENTS = 512;

    public UiSchema {
        Objects.requireNonNull(key, "key");
        if (title == null || title.isBlank()) {
            throw new UasValidationException("ui schema '" + key + "' must declare a title");
        }
        presentation = presentation == null ? UiPresentation.MODAL : presentation;
        if (root == null || root.isEmpty()) {
            throw new UasValidationException("ui schema '" + key + "' must declare at least one root element");
        }
        root = List.copyOf(root);
        initialValues = initialValues == null ? Map.of() : Map.copyOf(initialValues);
        bedrockNamespace = bedrockNamespace == null ? key.namespace() : bedrockNamespace;

        // Flatten from the *parameter*, not from elementList(): inside a record's
        // compact constructor the fields are not assigned until the constructor
        // returns, so an instance method would read a null root.
        List<UiElement> all = new ArrayList<>();
        for (UiElement element : root) {
            element.collect(all);
        }
        if (all.size() > MAX_ELEMENTS) {
            throw new UasValidationException(
                "ui schema '" + key + "' declares " + all.size() + " elements; max is " + MAX_ELEMENTS);
        }
        Set<String> ids = new HashSet<>();
        for (UiElement element : all) {
            if (!ids.add(element.id())) {
                throw new UasValidationException(
                    "ui schema '" + key + "' declares duplicate element id '" + element.id() + "'");
            }
            if (element instanceof UiElement.SlotGrid && !presentation.supportsSlotGrid()) {
                throw new UasValidationException(
                    "ui schema '" + key + "' uses a slot grid in a " + presentation
                        + " presentation, which cannot own inventory slots");
            }
        }
    }

    public static Builder builder(NamespacedKey key) {
        return new Builder(key);
    }

    /** Every element in the tree, depth-first, including containers. */
    public List<UiElement> elementList() {
        List<UiElement> sink = new ArrayList<>();
        for (UiElement element : root) {
            element.collect(sink);
        }
        return sink;
    }

    public Optional<UiElement> find(String id) {
        for (UiElement element : elementList()) {
            if (element.id().equals(id)) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    /** Every state key the tree binds to, from progress bars and SET_STATE actions. */
    public Set<String> boundStateKeys() {
        Set<String> keys = new HashSet<>();
        for (UiElement element : elementList()) {
            if (element instanceof UiElement.ProgressBar bar) {
                keys.add(bar.bindKey());
            }
            for (UiAction action : element.style().actions()) {
                String key = action.payload().get("key");
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /** State keys referenced by the tree but absent from the declared initial values. */
    public Set<String> undeclaredStateKeys() {
        Set<String> missing = new java.util.TreeSet<>(boundStateKeys());
        missing.removeAll(initialValues.keySet());
        return missing;
    }

    /** True when every element can be expressed as a Cumulus form component. */
    public boolean isFullyFormExpressible() {
        for (UiElement element : elementList()) {
            switch (element.kind()) {
                case TEXT, BUTTON, CONTAINER -> {
                    // supported natively by Cumulus
                }
                case IMAGE, PROGRESS_BAR, SLOT_GRID, SPACER -> {
                    return false;
                }
            }
        }
        return true;
    }

    /** The highest z-index in the tree, used to size the overlay render pass. */
    public int maxZIndex() {
        int max = 0;
        for (UiElement element : elementList()) {
            max = Math.max(max, element.style().zIndex());
        }
        return max;
    }

    public Builder toBuilder() {
        return new Builder(key)
            .title(title)
            .presentation(presentation)
            .root(root)
            .cancellable(cancellable)
            .bedrockModalFallback(bedrockModalFallback)
            .initialValues(initialValues)
            .bedrockNamespace(bedrockNamespace);
    }

    public static final class Builder {
        private final NamespacedKey key;
        private String title;
        private UiPresentation presentation = UiPresentation.MODAL;
        private final List<UiElement> root = new ArrayList<>();
        private boolean cancellable = true;
        private boolean bedrockModalFallback = true;
        private final Map<String, String> initialValues = new LinkedHashMap<>();
        private String bedrockNamespace;

        private Builder(NamespacedKey key) {
            this.key = Objects.requireNonNull(key, "key");
            this.title = key.path();
            this.bedrockNamespace = key.namespace();
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder presentation(UiPresentation value) {
            this.presentation = value;
            return this;
        }

        public Builder root(List<UiElement> elements) {
            this.root.clear();
            this.root.addAll(elements);
            return this;
        }

        public Builder add(UiElement element) {
            this.root.add(Objects.requireNonNull(element, "element"));
            return this;
        }

        public Builder root(UiElement element) {
            this.root.clear();
            this.root.add(Objects.requireNonNull(element, "element"));
            return this;
        }

        public Builder cancellable(boolean value) {
            this.cancellable = value;
            return this;
        }

        public Builder bedrockModalFallback(boolean value) {
            this.bedrockModalFallback = value;
            return this;
        }

        public Builder initialValue(String stateKey, String value) {
            this.initialValues.put(
                Objects.requireNonNull(stateKey, "stateKey"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder initialValues(Map<String, String> values) {
            this.initialValues.clear();
            this.initialValues.putAll(values);
            return this;
        }

        public Builder bedrockNamespace(String value) {
            this.bedrockNamespace = value;
            return this;
        }

        public UiSchema build() {
            return new UiSchema(
                key, title, presentation, root, cancellable,
                bedrockModalFallback, initialValues, bedrockNamespace);
        }
    }
}
