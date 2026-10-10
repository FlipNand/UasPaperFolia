package dev.uas.example;

import dev.uas.api.block.CustomBlock;
import dev.uas.api.sound.CustomSound;
import dev.uas.core.pack.PackCompileRequest;
import dev.uas.core.pack.PackCompileResult;
import dev.uas.paper.PaperUasPlatform;
import dev.uas.paper.UasPaperConfig;
import dev.uas.protocol.packet.UasBlockInteractionPacket;
import dev.uas.protocol.packet.UasCustomSoundPacket;
import dev.uas.protocol.packet.UasUiStatePacket;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The production-verification example: one plugin that exercises every part of the engine
 * on a real server.
 *
 * <p>What it does, in order, and why each step is here rather than being assumed:
 *
 * <ol>
 *   <li><strong>Declares the content</strong> (a shaped, animated, light-emitting block with
 *       five sounds and a bound HUD) and registers it. Registration is where a backend is
 *       chosen, so a server that cannot host the block fails here rather than in game.</li>
 *   <li><strong>Compiles the resource pack off the main thread.</strong> The compile reads
 *       and deflates a whole asset tree; on a 4 GiB instance doing that on the tick thread
 *       would stall the server for seconds. It goes to the async scheduler on both
 *       platforms -- the engine's own documentation is explicit that a compile must not run
 *       on a tick thread.</li>
 *   <li><strong>Attaches the multiplexer</strong> and lets the pack pusher hand the URL to
 *       players. The pack is served from the game port; no second listener exists.</li>
 *   <li><strong>Fires custom packets on interaction</strong>: a sound packet to everyone
 *       nearby and a block-interaction packet to the player who clicked. These are the same
 *       bytes a Bedrock client receives through the Geyser bridge, which is the point of
 *       putting them on one channel.</li>
 * </ol>
 */
public final class UasExamplePlugin extends JavaPlugin implements Listener {

    /** How far the interaction sound and packet are broadcast, in blocks. */
    private static final double CUE_RADIUS = 24d;

    private static final String PACK_ID = "uas-example";

    private PaperUasPlatform platform;
    private CustomBlock lamp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int port = getServer().getPort();
        UasPaperConfig config = UasPaperConfig.from(getConfig(), port);

        platform = PaperUasPlatform.create(this, config);

        lamp = ExampleContent.crystalLamp();
        List<CustomSound> sounds = ExampleContent.lampSoundEvents();

        // 1. Register the declarations. The returned backends are logged because the choice
        //    is a capability decision, not an implementation detail: on a note-block backend
        //    the emitted light is reported as unsupported instead of quietly ignored.
        platform.registerBlocks(List.of(lamp))
            .forEach((key, backend) -> getLogger().info(
                "UAS block " + key + " resolved to backend " + backend
                    + " (entities=" + backend.requiresEntities()
                    + ", light=" + backend.supportsLightEmission() + ")"));
        sounds.forEach(sound -> platform.engine().registerSound(sound));
        platform.engine().registerUi(ExampleContent.chargeHud());

        // 2. Compile the pack asynchronously. The engine is started first so the injected
        //    multiplexer is already routing by the time the pack is published; publishing a
        //    pack into a registry nobody is serving would just leave players on vanilla.
        platform.start();
        platform.scheduler().runAsync(this::compileAndPublishPack);

        getServer().getPluginManager().registerEvents(this, this);
        platform.bridge().addListener(this::onCustomPacket);

        // The command the operator reaches for first: /uas status. Registered here rather than
        // in plugin.yml alone so a missing command is a compile-time problem, not a silent
        // "Unknown command" on a live server.
        var uasCommand = new UasCommand(platform);
        var registered = getCommand("uas");
        if (registered != null) {
            registered.setExecutor(uasCommand);
            registered.setTabCompleter(uasCommand);
        } else {
            getLogger().warning("the 'uas' command is not declared in plugin.yml; /uas is unavailable");
        }

        reportWarnings();
    }

    @Override
    public void onDisable() {
        if (platform != null) {
            platform.close();
        }
    }

    private void compileAndPublishPack() {
        List<CustomSound> sounds = ExampleContent.lampSoundEvents();
        Path cache = getDataFolder().toPath().resolve("cache");
        try {
            Files.createDirectories(cache);
            PackCompileRequest request = PackCompileRequest.builder(
                    PACK_ID, cache.resolve(PACK_ID + ".zip"), cache.resolve("scratch"))
                // Widened explicitly: the builder takes the general AssetSource, and the
                // generated entries happen to be the in-memory kind.
                .entries(new java.util.ArrayList<dev.uas.api.pack.AssetSource>(
                    ExamplePackBuilder.entries(lamp, sounds)))
                .build();

            PackCompileResult result = platform.engine().compileAndRegister(request, "UAS Example Pack");
            getLogger().info(result.describe());
            // Logged with a stable marker: this line is the one an operator greps for, and it
            // names the exact URL a client will fetch, which is the single most common thing to
            // get wrong (a loopback URL left in place on a public server).
            getLogger().info(() -> "UAS PACK URL: "
                + platform.packPusher().urlFor(platform.engine().packs()
                    .resolve(PACK_ID).orElseThrow()));

            // Assets the pack must ship but cannot generate. Reported at startup because the
            // alternative is a player seeing a missing-texture block with no server-side clue.
            for (String asset : ExamplePackBuilder.requiredTextures(lamp, sounds)) {
                getLogger().info("UAS pack expects asset: " + asset);
            }

            // 3. Publish to everyone. The sweep compares a generation counter per player, so
            //    this is cheap on both platforms and re-pushes only players who are behind.
            platform.scheduler().runGlobal(() ->
                getLogger().info("UAS pushed the pack to "
                    + platform.packPusher().pushStaleToAll() + " player(s)"));
        } catch (IOException | RuntimeException failure) {
            getLogger().severe("UAS could not compile the example pack: " + failure);
        }
    }

    /** Surfaces the engine's own warnings, rather than letting them sit in a status dump. */
    private void reportWarnings() {
        var degraded = platform.engine().blocks().degradedGeometryBlocks();
        if (!degraded.isEmpty()) {
            getLogger().warning("UAS blocks whose shaping will not be rendered by their backend: "
                + degraded);
        }
        var unlit = platform.engine().blocks().unsupportedLightBlocks();
        if (!unlit.isEmpty()) {
            getLogger().warning("UAS blocks that ask for light their backend cannot emit: " + unlit);
        }
    }

    // ------------------------------------------------------------------- listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        platform.onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        platform.onPlayerQuit(event.getPlayer());
    }

    /**
     * Right-clicking one of our blocks fires the cross-platform interaction path.
     *
     * <p>The block is recognised by its backing note-block state, which is the only thing
     * that actually identifies it: there is no custom block type to test against. That check
     * is also what proves the state matrix is doing its job -- if the allocator and the pack
     * generator disagreed, this test would match blocks that are not ours and the client
     * would draw the wrong model for the ones that are.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
            || event.getHand() != EquipmentSlot.HAND
            || platform == null) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !isOurLamp(clicked)) {
            return;
        }
        Player player = event.getPlayer();

        // A sound packet to everyone in earshot. It carries the resolved SoundRef, so a
        // Bedrock client gets volume, pitch and attenuation without a second lookup.
        UasCustomSoundPacket cue = new UasCustomSoundPacket(
            ExampleContent.interactionCue(),
            clicked.getX() + ExampleContent.interactionOrigin().x(),
            clicked.getY() + ExampleContent.interactionOrigin().y(),
            clicked.getZ() + ExampleContent.interactionOrigin().z(),
            player.getUniqueId());
        for (Player nearby : clicked.getWorld().getNearbyPlayers(clicked.getLocation(), CUE_RADIUS)) {
            platform.bridge().send(nearby, cue);
        }

        // The interaction packet goes to the actor; on Bedrock it arrives through Geyser.
        platform.bridge().send(player, new UasBlockInteractionPacket(
            clicked.getX(), clicked.getY(), clicked.getZ(),
            UasBlockInteractionPacket.Action.INTERACT,
            dev.uas.api.block.Face.UP,
            player.getUniqueId(),
            lamp.key(),
            stateHashOf(clicked)));

        // A HUD update on the same round trip: the charge the lamp reports back to its owner.
        platform.engine().ui().setState(player.getUniqueId().toString(),
            ExampleContent.UI_STATE_KEY, "100").ifPresent(state -> platform.bridge().send(
                player, UasUiStatePacket.update(
                    ExampleContent.HUD_KEY, java.util.Map.of(ExampleContent.UI_STATE_KEY, "100"))));

        event.setCancelled(true);
    }

    /** True when a block carries exactly the note-block state this plugin assigned. */
    private boolean isOurLamp(Block block) {
        if (block.getType() != Material.NOTE_BLOCK) {
            return false;
        }
        Optional<dev.uas.core.block.NoteBlockStateMapper.State> assigned =
            platform.placer().stateOf(lamp.key());
        if (assigned.isEmpty() || !(block.getBlockData() instanceof NoteBlock data)) {
            return false;
        }
        var expected = assigned.get();
        return data.getInstrument().name().equalsIgnoreCase(bukkitName(expected.instrumentName()))
            && data.getNote().getId() == expected.note()
            && data.isPowered() == expected.powered();
    }

    /**
     * Maps a vanilla block-state instrument name onto Bukkit's own name for it.
     *
     * <p>Needed because the two vocabularies differ -- the block state calls the first
     * instrument {@code harp} where Bukkit calls it {@code PIANO} -- and a name-based
     * comparison that ignored that would never match the first instrument.
     */
    private static String bukkitName(String blockStateName) {
        return switch (blockStateName) {
            case "harp" -> "PIANO";
            case "basedrum" -> "BASS_DRUM";
            case "snare" -> "SNARE_DRUM";
            case "hat" -> "STICKS";
            case "bass" -> "BASS_GUITAR";
            default -> blockStateName.toUpperCase(java.util.Locale.ROOT);
        };
    }

    /** A cheap fingerprint of the block's state, for the interaction packet. */
    private static int stateHashOf(Block block) {
        return block.getBlockData().getAsString(true).hashCode();
    }

    /** Handles packets arriving from Java clients over the shared channel. */
    private void onCustomPacket(dev.uas.paper.net.UasChannelBridge.Inbound inbound) {
        var frame = inbound.frame();
        // ACTION_RESULT is how a client reports that a bound control moved; UPDATE is a
        // client-originated state change. Both are state writes from the client, and both
        // are validated the same way.
        if (frame.packet() instanceof UasUiStatePacket ui
            && (ui.action() == UasUiStatePacket.Action.ACTION_RESULT
                || ui.action() == UasUiStatePacket.Action.UPDATE)) {
            // A client asked to write state. The UI manager only accepts keys the schema
            // actually binds, so a malicious or buggy client cannot turn a HUD into an
            // arbitrary per-player store.
            platform.engine().ui().setState(inbound.playerId().toString(), ui.values());
            return;
        }
        getLogger().fine(() -> "UAS received " + frame.packet().getClass().getSimpleName()
            + " from " + inbound.playerId());
    }

    /** The platform, exposed so a server operator can inspect it from another plugin. */
    public PaperUasPlatform platform() {
        return platform;
    }
}
