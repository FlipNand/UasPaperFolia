package dev.uas.example;

import dev.uas.paper.PaperUasPlatform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * {@code /uas} — the operator's view of what the engine actually did.
 *
 * <p>Written because "is asset streaming working?" has no answer from the outside. A pack that
 * was never injected looks identical, from a player's seat, to a pack that was injected and is
 * being served: both show vanilla textures until the client has fetched it. The difference is
 * entirely in the counters, so the counters have to be reachable.
 *
 * <p>Each subcommand answers one question and reports the <em>raw</em> numbers rather than a
 * verdict, because the failure modes are only distinguishable by combination:
 *
 * <ul>
 *   <li>{@code hookedChannels=0} with {@code sniff.connections=0} — the multiplexer never
 *       attached. The injection trace names the reflection hop that broke.</li>
 *   <li>{@code hookedChannels=1} with {@code sniff.httpConnections=0} — it attached, but no
 *       client has ever asked for a pack. That is a URL or a push problem, not injection.</li>
 *   <li>{@code queue.rejectedForPlayer} climbing — players are disconnecting mid-push, or the
 *       scheduler is refusing work. Either way they are seeing stale assets.</li>
 * </ul>
 *
 * <p>Subcommands: {@code status} (everything), {@code packs}, {@code blocks}, {@code inject}
 * (retry attachment — safe, an already-hooked channel is left alone).
 */
public final class UasCommand implements CommandExecutor, TabCompleter {

    /** Permission required to run any subcommand other than from the console. */
    public static final String PERMISSION = "uas.admin";

    private static final List<String> SUBCOMMANDS = List.of("status", "packs", "blocks", "inject");

    private final PaperUasPlatform platform;

    public UasCommand(PaperUasPlatform platform) {
        this.platform = platform;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof org.bukkit.entity.Player && !sender.hasPermission(PERMISSION)) {
            send(sender, "You do not have permission to use UAS commands.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> send(sender, platform.statusReport());
            case "packs" -> send(sender, render("UAS packs", packs()));
            case "blocks" -> send(sender, render("UAS blocks", blocks()));
            case "inject" -> send(sender, reinject());
            default -> send(sender, "UAS subcommands: " + String.join(", ", SUBCOMMANDS));
        }
        return true;
    }

    private Map<String, String> packs() {
        Map<String, String> report = new LinkedHashMap<>();
        platform.engine().packs().describe().forEach((key, value) -> report.put("packs." + key, value));
        platform.packPusher().describe().forEach((key, value) -> report.put("push." + key, value));
        return report;
    }

    private Map<String, String> blocks() {
        Map<String, String> report = new LinkedHashMap<>();
        platform.engine().blocks().describe().forEach((key, value) -> report.put(key, value));
        platform.placer().describe().forEach((key, value) -> report.put("placer." + key, value));
        // The variant list is exactly what the resource pack must contain, so printing it makes
        // a generator/placer mismatch visible without opening the archive.
        platform.placer().blockStateVariants()
            .forEach((state, model) -> report.put("variant[" + state + "]", model));
        return report;
    }

    private String reinject() {
        var report = platform.reinject();
        StringBuilder builder = new StringBuilder("UAS injection: ").append(report.summarise()).append('\n');
        for (String line : report.trace()) {
            builder.append("  ").append(line).append('\n');
        }
        return builder.toString();
    }

    private static void send(CommandSender sender, String message) {
        // Row by row, so a multi-line report stays readable both in chat and in a log file.
        for (String line : message.split("\n")) {
            if (!line.isEmpty()) {
                sender.sendMessage("[UAS] " + line);
            }
        }
    }

    private static String render(String title, Map<String, String> values) {
        StringBuilder builder = new StringBuilder(title).append('\n');
        values.forEach((key, value) ->
            builder.append("  ").append(key).append(": ").append(value).append('\n'));
        return builder.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        List<String> matches = new ArrayList<>();
        for (String sub : SUBCOMMANDS) {
            if (sub.startsWith(prefix)) {
                matches.add(sub);
            }
        }
        return matches;
    }
}
