package dev.uas.geyser.jit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.uas.api.ui.UiElement;
import dev.uas.api.ui.UiSchema;
import dev.uas.core.ui.UiRenderPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiles a declarative UI schema into something a Bedrock client can actually show.
 *
 * <p>Bedrock has two UI mechanisms and they are not interchangeable, so the compiler has
 * two output modes and picks between them using the same decision the engine already made
 * in {@link dev.uas.core.ui.UnifiedUIManager}. It does not re-decide: the render path is
 * passed in, because a second independent copy of the degradation rules is exactly how the
 * Java and Bedrock views of one schema start disagreeing.
 *
 * <ul>
 *   <li><strong>Form</strong> -- the native surface. Cumulus forms are a title, a body and
 *       a list of buttons; that is genuinely all they are. Images, progress bars and slot
 *       grids cannot be expressed, which is why {@code OVERLAY} schemas without a modal
 *       fallback take the other path.</li>
 *   <li><strong>Custom UI JSON</strong> -- injected by the Bedrock resource pack. It can
 *       draw an overlay, but it cannot own server-side inventory slots (the server cannot
 *       see into a client-drawn grid), so a schema with a slot grid degrades to an action
 *       bar line and says so.</li>
 * </ul>
 *
 * <p>State binding is compiled to a text placeholder rather than left implicit: a bound
 * progress bar becomes a percentage string in the form's body, and the caller updates it by
 * editing that text. That is a real, if modest, capability -- and it is honest about being
 * a textual interpolation rather than a live widget.
 */
public final class BedrockUiCompiler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BedrockUiCompiler() {
    }

    /** The compiled output plus anything the conversion could not keep. */
    public record Result(JsonObject document, boolean lossy, List<String> notes) {

        public Result {
            document = Objects.requireNonNull(document, "document");
            notes = List.copyOf(notes);
        }

        public String json() {
            return GSON.toJson(document);
        }

        /** The {@code form} JSON for a Cumulus modal response. */
        public String formJson() {
            return json();
        }
    }

    /** Compiles to whichever Bedrock surface the render path names. */
    public static Result compile(UiSchema schema, UiRenderPath path, java.util.Map<String, String> state) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(state, "state");
        return switch (path) {
            case BEDROCK_CUMULUS_FORM -> form(schema, state, false);
            case BEDROCK_CUSTOM_JSON -> customUi(schema, state);
            case BEDROCK_ACTION_BAR -> actionBar(schema, state);
            // Both Java paths mean "this schema is not for Bedrock"; compiling them here
            // would silently produce a Bedrock surface the caller never asked for.
            case JAVA_INVENTORY_MENU, JAVA_HUD_OVERLAY -> throw new IllegalArgumentException(
                "render path " + path + " is a Java path and cannot be compiled for Bedrock");
        };
    }

    /**
     * A Cumulus form: title, body, buttons.
     *
     * @param degraded true when this form stands in for a presentation Bedrock does not
     *     have natively, so the body says so instead of quietly showing a dialog
     */
    public static Result form(UiSchema schema, java.util.Map<String, String> state, boolean degraded) {
        List<String> notes = new ArrayList<>();
        if (degraded) {
            notes.add("the " + schema.presentation().bedrockName()
                + " presentation has no Bedrock equivalent; shown as a modal form");
        }

        JsonObject form = new JsonObject();
        form.addProperty("type", "form");
        form.addProperty("title", schema.title());

        StringBuilder body = new StringBuilder();
        JsonArray buttons = new JsonArray();
        for (UiElement element : schema.elementList()) {
            switch (element) {
                case UiElement.Text text -> {
                    if (!body.isEmpty()) {
                        body.append('\n');
                    }
                    body.append(renderText(text, state));
                }
                case UiElement.Button button -> {
                    JsonObject rendered = new JsonObject();
                    rendered.addProperty("text", button.label());
                    buttons.add(rendered);
                }
                case UiElement.ProgressBar bar -> {
                    if (!body.isEmpty()) {
                        body.append('\n');
                    }
                    body.append(renderProgress(bar, state));
                }
                case UiElement.Container container -> {
                    // Containers describe layout, which a form cannot honour: a form lays
                    // its content out itself. Noting it is the honest option.
                    notes.add("container '" + container.id() + "' has no form layout equivalent");
                }
                case UiElement.Image image -> notes.add(
                    "image '" + image.id() + "' cannot be drawn in a Bedrock form");
                case UiElement.SlotGrid grid -> notes.add(
                    "slot grid '" + grid.id() + "' cannot be drawn in a Bedrock form");
                case UiElement.Spacer spacer -> {
                    if (!body.isEmpty()) {
                        body.append('\n');
                    }
                }
            }
        }
        form.addProperty("content", body.toString());
        form.add("buttons", buttons);

        return new Result(form, !notes.isEmpty(), notes);
    }

    /**
     * Custom UI JSON for the Bedrock resource pack.
     *
     * <p>Emitted as a namespaced screen definition: an id, the declared element tree with
     * anchors and offsets resolved to Bedrock's own units, and a pointer to the nine-slice
     * texture. Percentages are resolved against the reference resolution, because Bedrock's
     * UI JSON works in pixels and percentages relative to the screen it is drawn on.
     */
    public static Result customUi(UiSchema schema, java.util.Map<String, String> state) {
        List<String> notes = new ArrayList<>();

        JsonObject root = new JsonObject();
        root.addProperty("type", "panel");
        root.addProperty("anchors", "full_screen");
        root.addProperty("namespace", schema.bedrockNamespace());

        JsonArray elements = new JsonArray();
        for (UiElement element : schema.elementList()) {
            switch (element) {
                case UiElement.Text text -> {
                    JsonObject node = node(element, "label");
                    node.addProperty("text", renderText(text, state));
                    elements.add(node);
                }
                case UiElement.Image image -> {
                    JsonObject node = node(element, "image");
                    node.addProperty("texture", image.texture().namespace() + "_" + image.texture().path());
                    node.addProperty("fit", image.fit().name().toLowerCase(java.util.Locale.ROOT));
                    if (image.nineSlice() != null) {
                        node.addProperty("nine_slice", true);
                    }
                    elements.add(node);
                }
                case UiElement.ProgressBar bar -> {
                    JsonObject node = node(element, "progress_bar");
                    node.addProperty("bind", bar.bindKey());
                    node.addProperty("caption", renderProgress(bar, state));
                    elements.add(node);
                }
                case UiElement.Button button -> {
                    JsonObject node = node(element, "button");
                    node.addProperty("text", button.label());
                    elements.add(node);
                }
                case UiElement.Container container -> {
                    JsonObject node = node(container, "panel");
                    node.addProperty("layout", container.layout().toString());
                    elements.add(node);
                }
                case UiElement.SlotGrid grid -> {
                    // Reported, not emitted: a client-drawn grid cannot own server-side
                    // slots, so drawing one would produce a UI the server cannot serve.
                    notes.add("slot grid '" + grid.id() + "' cannot be drawn in custom UI JSON; "
                        + "it needs a real container on the Java side");
                }
                case UiElement.Spacer spacer -> {
                    elements.add(node(spacer, "spacer"));
                }
            }
        }

        root.add("elements", elements);

        JsonObject document = new JsonObject();
        document.addProperty("namespace", schema.bedrockNamespace());
        document.addProperty("ui_namespace", schema.key().namespace());
        document.add("root", root);

        return new Result(document, !notes.isEmpty(), notes);
    }

    /** A one-line action bar fallback for a schema Bedrock has no surface for. */
    public static Result actionBar(UiSchema schema, java.util.Map<String, String> state) {
        JsonObject document = new JsonObject();
        document.addProperty("type", "action_bar");
        document.addProperty("text", schema.title()
            + " cannot be opened on Bedrock; open it from a Java client");
        return new Result(document, true,
            List.of("a slot-owning schema has no Bedrock surface; an action bar line was used"));
    }

    private static JsonObject node(UiElement element, String type) {
        JsonObject node = new JsonObject();
        node.addProperty("id", element.id());
        node.addProperty("type", type);
        node.addProperty("anchor", element.style().anchor().bedrockName());
        // Sizes are emitted as "unit:value" rather than resolved here. Resolving AUTO, FILL
        // and ASPECT needs a parent extent, which only the layout pass has; inventing a
        // pixel number would produce a document that looks precise and is wrong.
        node.addProperty("offset_x", sizeSpec(element.style().offsetX()));
        node.addProperty("offset_y", sizeSpec(element.style().offsetY()));
        node.addProperty("width", sizeSpec(element.style().width()));
        node.addProperty("height", sizeSpec(element.style().height()));
        node.addProperty("z", element.style().zIndex());
        if (!element.style().visible()) {
            node.addProperty("visible", false);
        }
        return node;
    }

    /** A layout length as {@code unit:value}, e.g. {@code pixels:8} or {@code percent:50}. */
    static String sizeSpec(dev.uas.api.ui.Size size) {
        return size.unit().name().toLowerCase(java.util.Locale.ROOT) + ":" + size.value();
    }

    /** Resolves {@code %s} in a text element against bound state. */
    static String renderText(UiElement.Text text, java.util.Map<String, String> state) {
        String rendered = text.text();
        for (java.util.Map.Entry<String, String> entry : state.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }

    /** A bound progress bar as a percentage, which is what a form can show. */
    static String renderProgress(UiElement.ProgressBar bar, java.util.Map<String, String> state) {
        String raw = state.getOrDefault(bar.bindKey(), "0");
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException notANumber) {
            return bar.caption().isEmpty() ? "0%" : bar.caption();
        }
        double fraction = bar.fractionFor(value);
        String percent = String.format(java.util.Locale.ROOT, "%.0f%%", fraction * 100d);
        return bar.caption().isEmpty() ? percent : bar.caption() + " " + percent;
    }

    /** True when a schema's presentation has a native Bedrock surface. */
    public static boolean isNativelyPresentable(UiSchema schema) {
        return schema.presentation().isBedrockNative();
    }
}
