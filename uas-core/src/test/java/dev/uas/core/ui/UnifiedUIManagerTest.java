package dev.uas.core.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.uas.api.NamespacedKey;
import dev.uas.api.ui.Size;
import dev.uas.api.ui.UiElement;
import dev.uas.api.ui.UiPresentation;
import dev.uas.api.ui.UiSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Render-path selection and the per-player state lifecycle. */
class UnifiedUIManagerTest {

    private static final NamespacedKey SCHEMA = NamespacedKey.parse("uas:test_menu");

    /**
     * Text and a button only. This is exactly the tree a Cumulus form can express --
     * a progress bar or an image is <em>not</em> form-expressible, which is why the
     * state-bearing schema below is a separate fixture.
     */
    private static UiSchema formSchema(UiPresentation presentation, boolean modalFallback) {
        return UiSchema.builder(SCHEMA)
            .title("Test")
            .presentation(presentation)
            .bedrockModalFallback(modalFallback)
            .add(UiElement.Text.of("heading", "Hello"))
            .add(UiElement.Button.of("ok", "OK", dev.uas.api.ui.UiAction.close()))
            .build();
    }

    /** A modal with a bound progress bar, used for state lifecycle tests. */
    private static UiSchema bindableSchema() {
        return UiSchema.builder(SCHEMA)
            .title("Test")
            .presentation(UiPresentation.MODAL)
            .initialValue("progress", "0")
            .add(UiElement.Text.of("heading", "Hello"))
            .add(UiElement.ProgressBar.of("bar", "progress", 1.0))
            .build();
    }

    private static UiSchema slotSchema() {
        return UiSchema.builder(SCHEMA)
            .title("Container")
            .presentation(UiPresentation.MODAL)
            .initialValue("progress", "0")
            .add(UiElement.SlotGrid.of("grid", 3, 9))
            .add(UiElement.Text.of("heading", "Hello"))
            .build();
    }

    private static UiSchema overlaySchema() {
        // An overlay cannot be a form: forms are modal by construction, and the schema
        // declares no fallback, so Bedrock needs injected JSON.
        return UiSchema.builder(SCHEMA)
            .title("Overlay")
            .presentation(UiPresentation.OVERLAY)
            .bedrockModalFallback(false)
            .initialValue("progress", "0")
            .add(UiElement.Text.of("heading", "Hello"))
            .add(UiElement.ProgressBar.of("bar", "progress", 1.0))
            .build();
    }

    @Test
    void javaGetsAMenuForSlotPresentationsAndAnOverlayOtherwise() {
        UnifiedUIManager manager = new UnifiedUIManager();

        assertEquals(UiRenderPath.JAVA_INVENTORY_MENU,
            manager.planFor(slotSchema(), UiTarget.JAVA).path());
        assertEquals(UiRenderPath.JAVA_HUD_OVERLAY,
            manager.planFor(formSchema(UiPresentation.OVERLAY, true), UiTarget.JAVA).path());
        assertFalse(manager.planFor(slotSchema(), UiTarget.JAVA).lossy(),
            "Java renders every declared presentation faithfully");
    }

    @Test
    void bedrockUsesNativeFormsForNativePresentations() {
        UnifiedUIManager manager = new UnifiedUIManager();

        UiRenderPlan plan = manager.planFor(formSchema(UiPresentation.TOAST, true), UiTarget.BEDROCK);
        assertEquals(UiRenderPath.BEDROCK_CUMULUS_FORM, plan.path());
        assertFalse(plan.lossy(), "a toast already is what Bedrock natively shows");
    }

    @Test
    void bedrockDegradesANonNativePresentationToAFormAndSaysWhy() {
        UnifiedUIManager manager = new UnifiedUIManager();
        // ACTION_BAR has no Bedrock equivalent, but the tree happens to be a plain
        // text-and-button form, so it can be shown as a modal dialog instead. That is a
        // degradation the author must be able to see.
        UiRenderPlan plan = manager.planFor(
            formSchema(UiPresentation.ACTION_BAR, true), UiTarget.BEDROCK);

        assertEquals(UiRenderPath.BEDROCK_CUMULUS_FORM, plan.path());
        assertTrue(plan.lossy());
        assertTrue(plan.reason().contains("action_bar"), "the reason must name the presentation");
    }

    @Test
    void bedrockUsesCustomJsonWhenFormsCannotExpressTheSchema() {
        UnifiedUIManager manager = new UnifiedUIManager();

        // No fallback allowed, so a non-native presentation in a non-form tree has to go
        // through injected UI JSON rather than being silently turned into a dialog.
        UiRenderPlan plan = manager.planFor(overlaySchema(), UiTarget.BEDROCK);
        assertEquals(UiRenderPath.BEDROCK_CUSTOM_JSON, plan.path());
        assertFalse(plan.lossy(), "custom UI JSON renders the overlay as declared");
        assertTrue(plan.path().isBedrock());
    }

    @Test
    void aFormExpressibleTreeStillNeedsTheFallbackToBeEnabled() {
        UnifiedUIManager manager = new UnifiedUIManager();

        // Same tree as the degradation case, but the author refused the fallback: the
        // engine must honour that and use custom JSON instead of a dialog.
        UiRenderPlan plan = manager.planFor(
            formSchema(UiPresentation.ACTION_BAR, false), UiTarget.BEDROCK);
        assertEquals(UiRenderPath.BEDROCK_CUSTOM_JSON, plan.path());
    }

    @Test
    void bedrockCannotHostInventorySlotsAndSaysSo() {
        UnifiedUIManager manager = new UnifiedUIManager();

        // A slot grid is not form-expressible and custom UI JSON cannot own server-side
        // slots either, so the only honest outcome is to tell the player something.
        UiRenderPlan plan = manager.planFor(slotSchema(), UiTarget.BEDROCK);
        assertEquals(UiRenderPath.BEDROCK_ACTION_BAR, plan.path());
        assertTrue(plan.lossy(),
            "a slot grid has no Bedrock surface; the degradation must be visible, not silent");
        assertTrue(plan.reason().contains("inventory-slot"));
    }

    @Test
    void openTracksStateAndAdvancesRevisions() {
        UnifiedUIManager manager = new UnifiedUIManager();
        manager.register(bindableSchema());

        UiRenderPlan plan = manager.open("player-1", SCHEMA);
        assertEquals(UiRenderPath.JAVA_INVENTORY_MENU, plan.path());
        assertTrue(manager.isOpen("player-1"));

        long firstRevision = manager.state("player-1").orElseThrow().revision();
        PlayerUiState updated = manager.setState("player-1", "progress", "0.5").orElseThrow();
        assertTrue(updated.revision() > firstRevision, "state change must advance the revision");
        assertEquals("0.5", updated.value("progress").orElseThrow());

        // Only keys the tree actually binds may be written. A client can ask to set
        // anything at all, so this is the boundary that keeps a UI from becoming an
        // arbitrary per-player key-value store.
        assertTrue(manager.setState("player-1", "not_a_key", "x").isEmpty());
        assertEquals("1", manager.describe().get("rejectedUpdates"));
    }

    @Test
    void batchStateUpdateIsAllOrNothing() {
        UnifiedUIManager manager = new UnifiedUIManager();
        manager.register(bindableSchema());
        manager.open("p", SCHEMA);

        assertTrue(manager.setState("p", Map.of("progress", "1", "bogus", "1")).isEmpty());
        // The good key in the rejected batch must not have been applied.
        assertEquals("0", manager.state("p").orElseThrow().value("progress").orElseThrow());

        PlayerUiState applied = manager.setState("p", Map.of("progress", "0.25")).orElseThrow();
        assertEquals("0.25", applied.value("progress").orElseThrow());
    }

    @Test
    void stateUpdatesForAClosedUiAreRejected() {
        UnifiedUIManager manager = new UnifiedUIManager();
        manager.register(bindableSchema());

        assertTrue(manager.setState("ghost", "progress", "1").isEmpty());
        assertEquals(SCHEMA, manager.open("p", SCHEMA).schema().key());
        assertTrue(manager.close("p"));
        assertFalse(manager.close("p"));
        assertTrue(manager.setState("p", "progress", "1").isEmpty());
    }

    @Test
    void targetChangeReplansAnOpenUi() {
        UnifiedUIManager manager = new UnifiedUIManager();
        manager.register(slotSchema());
        manager.open("geyser-player", SCHEMA);
        assertEquals(UiRenderPath.JAVA_INVENTORY_MENU,
            manager.state("geyser-player").orElseThrow().renderPath());

        // Geyser reports a Bedrock login after the UI was opened, so the open UI must be
        // re-planned rather than left pointing at a path the client cannot render.
        manager.setTarget("geyser-player", UiTarget.BEDROCK);
        PlayerUiState state = manager.state("geyser-player").orElseThrow();
        assertEquals(UiTarget.BEDROCK, state.target());
        assertEquals(UiRenderPath.BEDROCK_ACTION_BAR, state.renderPath());
    }

    @Test
    void forgetDropsEverythingAboutAPlayer() {
        UnifiedUIManager manager = new UnifiedUIManager();
        manager.register(bindableSchema());
        manager.open("p", SCHEMA);
        manager.setTarget("p", UiTarget.BEDROCK);

        manager.forget("p");
        assertFalse(manager.isOpen("p"));
        assertEquals(UiTarget.JAVA, manager.target("p"), "the client family must not be retained");
    }

    @Test
    void resetRestoresDeclaredInitialValues() {
        UnifiedUIManager manager = new UnifiedUIManager();
        manager.register(bindableSchema());
        manager.open("p", SCHEMA);
        manager.setState("p", "progress", "0.9");

        PlayerUiState reset = manager.resetState("p").orElseThrow();
        assertEquals("0", reset.value("progress").orElseThrow());
    }

    @Test
    void unregisteringASchemaClosesAnyUiUsingIt() {
        UnifiedUIManager manager = new UnifiedUIManager();
        manager.register(bindableSchema());
        manager.open("p", SCHEMA);

        assertTrue(manager.unregister(SCHEMA).isPresent());
        // Rendering a schema that no longer exists must not silently continue.
        assertTrue(manager.plan("p").isEmpty());
        assertFalse(manager.isOpen("p"));
    }

    @Test
    void refusesToOpenAnUnregisteredSchema() {
        UnifiedUIManager manager = new UnifiedUIManager();
        assertThrows(IllegalArgumentException.class, () -> manager.open("p", SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> manager.requireSchema(SCHEMA));
    }

    @Test
    void evictsUnderPressureInsteadOfGrowingForever() {
        UnifiedUIManager manager = new UnifiedUIManager(8);
        manager.register(bindableSchema());

        for (int i = 0; i < 64; i++) {
            manager.open("player-" + i, SCHEMA);
        }

        // The bound is the point: an unbounded per-player map is a memory leak.
        assertTrue(manager.trackedPlayerCount() <= 8,
            "tracked players must stay within the ceiling, was " + manager.trackedPlayerCount());
        assertTrue(Long.parseLong(manager.describe().get("evictions")) > 0,
            "evictions must be counted so the pressure is observable rather than silent");
    }

    @Test
    void recentAccessSurvivesEviction() {
        UnifiedUIManager manager = new UnifiedUIManager(4);
        manager.register(bindableSchema());

        for (int i = 0; i < 4; i++) {
            manager.open("p" + i, SCHEMA);
        }
        // Touch p0 so it is the most recently used, then overflow.
        assertTrue(manager.isOpen("p0"));
        manager.open("fresh", SCHEMA);

        assertTrue(manager.isOpen("p0"), "the most recently used entry must be kept");
        assertTrue(manager.isOpen("fresh"));
    }

    @Test
    void describeReportsTheWholePicture() {
        UnifiedUIManager manager = new UnifiedUIManager();
        manager.register(bindableSchema());
        manager.open("p", SCHEMA);
        manager.setState("p", "progress", "1");
        manager.close("p");

        Map<String, String> report = manager.describe();
        assertEquals("1", report.get("schemas"));
        assertEquals("0", report.get("trackedPlayers"));
        assertEquals("1", report.get("opened"));
        assertEquals("1", report.get("closed"));
        assertEquals("1", report.get("stateUpdates"));
    }

    @Test
    void declaredLayoutUnitsSurviveTheRoundTrip() {
        // The declarative layer is what plugins actually write against, so it is worth
        // asserting that what it declares is what the engine later reads back.
        Size width = Size.px(8);
        UiSchema schema = UiSchema.builder(SCHEMA)
            .title("Layout")
            .presentation(UiPresentation.MODAL)
            .add(UiElement.Spacer.of("gap", width, Size.percent(50)))
            .build();

        assertEquals(1, schema.elementList().size());
        assertEquals("gap", schema.elementList().get(0).id());
        assertEquals("Layout", schema.title());
        assertEquals(Size.Unit.PIXELS, width.unit());
        assertEquals(8d, width.value());
        // Java can render this, and a slot-less modal is an inventory menu.
        assertEquals(UiRenderPath.JAVA_INVENTORY_MENU,
            new UnifiedUIManager().planFor(schema, UiTarget.JAVA).path());
    }
}
