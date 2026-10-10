package dev.uas.core.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.uas.api.pack.AssetSource;
import dev.uas.core.memory.MemoryBudget;
import dev.uas.core.memory.MemoryProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command-line front end for {@link LowMemoryPackCompiler}.
 *
 * <p>This exists for two reasons. Operationally, it lets an operator precompile a
 * pack offline and ship the archive rather than pay the compression cost at startup.
 * And it is how the constrained-RAM guarantee is proved: a test can fork a JVM with
 * {@code -Xmx48m} and run this over a large asset tree. Running the compiler inside
 * the test JVM would not demonstrate anything, because the test JVM's heap is not the
 * one being constrained.
 *
 * <p>Exit codes: {@code 0} success, {@code 2} usage error, {@code 3} compile failure.
 */
public final class PackCompilerCli {

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_FAILURE = 3;

    private static final String USAGE = """
        UAS pack compiler

        Usage: PackCompilerCli --source <dir> --output <zip> [options]

        Required:
          --source <dir>        directory to pack (repeatable; later sources win ordering
                                is path-sorted, duplicates are rejected)
          --output <zip>        destination archive

        Options:
          --scratch <dir>       scratch directory for temp files (default: system temp)
          --pack-id <id>        pack identifier (default: derived from the output name)
          --profile <name>      auto | low_ram | balanced | performance (default: auto)
          --low-ram             shorthand for --profile low_ram
          --compression <mode>  auto | deflate | store (default: auto)
          --copy-buffer <n>     streaming copy buffer size in bytes (default: from profile)
          --pack-format <n>     pack_format written into a generated pack.mcmeta (default: 34)
          --description <text>  description written into a generated pack.mcmeta
          --no-index            skip writing the asset index
          --no-Mcmeta           do not synthesise pack.mcmeta when absent
          --report <file>       write a JSON report of the compile
          --print-budget        print the detected memory budget and exit
          --quiet               suppress the summary line
        """;

    private PackCompilerCli() {
    }

    public static void main(String[] args) {
        int exit = run(args);
        if (exit != EXIT_OK) {
            System.exit(exit);
        }
    }

    /** Runs the CLI without calling {@link System#exit}; returns the exit code. */
    public static int run(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            return EXIT_USAGE;
        }

        if (options.help) {
            System.out.println(USAGE);
            return EXIT_OK;
        }

        MemoryProfile profile = options.profile;
        if (profile == null) {
            profile = MemoryProfile.detect(Runtime.getRuntime().maxMemory(), options.lowRam);
        }
        MemoryBudget budget = MemoryBudget.forProfile(profile, Runtime.getRuntime().maxMemory());

        if (options.printBudget) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            System.out.println(gson.toJson(budget.describe()));
            return EXIT_OK;
        }

        try {
            PackCompileResult result = compile(options, budget);
            if (!options.quiet) {
                System.out.println(result.describe());
            }
            if (options.report != null) {
                writeReport(options.report, result, budget);
            }
            return EXIT_OK;
        } catch (IOException e) {
            System.err.println("compile failed: " + e);
            return EXIT_FAILURE;
        }
    }

    private static PackCompileResult compile(Options options, MemoryBudget budget) throws IOException {
        List<AssetSource> sources = new ArrayList<>();
        for (Path root : options.sources) {
            sources.addAll(DirectoryScanner.scan(root));
        }

        boolean hasMeta = sources.stream().anyMatch(s -> s.path().equals("pack.mcmeta"));
        if (!hasMeta && !options.noMcmeta) {
            sources.add(new dev.uas.api.pack.ByteArrayAssetSource(
                "pack.mcmeta", generatedMcmeta(options)));
        }

        if (sources.isEmpty()) {
            throw new IOException("no assets found under " + options.sources);
        }

        PackCompileRequest.Builder request = PackCompileRequest
            .builder(options.packId, options.output, options.scratch)
            .entries(sources)
            .writeIndex(!options.noIndex)
            .deterministicTimestamps(true);

        if (options.compression != null) {
            request.mode(options.compression);
        }

        LowMemoryPackCompiler compiler = options.copyBuffer > 0
            ? new LowMemoryPackCompiler(budget, budget.profile().compressionLevel(), options.copyBuffer)
            : new LowMemoryPackCompiler(budget);
        return compiler.compile(request.build());
    }

    private static byte[] generatedMcmeta(Options options) {
        JsonObject root = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", options.packFormat);
        pack.addProperty("description", options.description);
        root.add("pack", pack);
        return new GsonBuilder().setPrettyPrinting().create()
            .toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static void writeReport(Path report, PackCompileResult result, MemoryBudget budget)
        throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("packId", result.packId());
        document.put("output", result.output().toString());
        document.put("outputBytes", result.outputBytes());
        document.put("sha1", result.sha1());
        document.put("entryCount", result.entryCount());
        document.put("storedEntries", result.storedEntries());
        document.put("totalUncompressedBytes", result.totalUncompressedBytes());
        document.put("elapsedMillis", result.elapsedMillis());
        document.put("peakObservedHeapBytes", result.peakObservedHeapBytes());
        document.put("peakFractionOfMaxHeap", result.peakFractionOfMaxHeap());
        document.put("maxHeapBytes", result.maxHeapBytes());
        document.put("profile", result.profile().configName());
        document.put("indexFile", result.indexFile() == null ? null : result.indexFile().toString());
        document.put("budget", budget.describe());

        Path parent = report.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(document),
            StandardCharsets.UTF_8);
    }

    /** Parsed command line. Kept permissive about formatting, strict about meaning. */
    static final class Options {
        final List<Path> sources = new ArrayList<>();
        Path output;
        Path scratch;
        String packId;
        MemoryProfile profile;
        boolean lowRam;
        CompressionMode compression;
        int copyBuffer;
        int packFormat = 34;
        String description = "Unified Asset Streaming pack";
        boolean noIndex;
        boolean noMcmeta;
        Path report;
        boolean printBudget;
        boolean quiet;
        boolean help;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> options.help = true;
                    case "--source" -> options.sources.add(Path.of(require(args, ++i, arg)));
                    case "--output" -> options.output = Path.of(require(args, ++i, arg));
                    case "--scratch" -> options.scratch = Path.of(require(args, ++i, arg));
                    case "--pack-id" -> options.packId = require(args, ++i, arg);
                    case "--profile" -> options.profile =
                        MemoryProfile.parse(require(args, ++i, arg));
                    case "--low-ram" -> options.lowRam = true;
                    case "--compression" -> options.compression =
                        parseCompression(require(args, ++i, arg));
                    case "--copy-buffer" -> options.copyBuffer =
                        parseInt(require(args, ++i, arg), arg, 1, Integer.MAX_VALUE);
                    case "--pack-format" -> options.packFormat =
                        parseInt(require(args, ++i, arg), arg, 1, Integer.MAX_VALUE);
                    case "--description" -> options.description = require(args, ++i, arg);
                    case "--no-index" -> options.noIndex = true;
                    case "--no-Mcmeta" -> options.noMcmeta = true;
                    case "--report" -> options.report = Path.of(require(args, ++i, arg));
                    case "--print-budget" -> options.printBudget = true;
                    case "--quiet" -> options.quiet = true;
                    default -> throw new IllegalArgumentException("unknown argument '" + arg + "'");
                }
            }

            if (options.help || options.printBudget) {
                return options;
            }
            if (options.sources.isEmpty()) {
                throw new IllegalArgumentException("--source is required");
            }
            if (options.output == null) {
                throw new IllegalArgumentException("--output is required");
            }
            for (Path source : options.sources) {
                if (!Files.isDirectory(source)) {
                    throw new IllegalArgumentException("--source is not a directory: " + source);
                }
            }
            if (options.scratch == null) {
                options.scratch = Path.of(System.getProperty("java.io.tmpdir"), "uas-compile");
            }
            if (options.packId == null) {
                options.packId = derivePackId(options.output);
            }
            return options;
        }

        private static String derivePackId(Path output) {
            String name = output.getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            return name.isBlank() ? "uas" : name;
        }

        private static CompressionMode parseCompression(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> CompressionMode.AUTO;
                case "deflate", "deflated" -> CompressionMode.ALWAYS_DEFLATE;
                case "store", "stored" -> CompressionMode.ALWAYS_STORE;
                default -> throw new IllegalArgumentException(
                    "unknown compression mode '" + value + "'; expected auto, deflate or store");
            };
        }

        private static int parseInt(String value, String flag, int min, int max) {
            int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(flag + " expects an integer, got '" + value + "'");
            }
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(
                    flag + " must be between " + min + " and " + max + ", got " + parsed);
            }
            return parsed;
        }

        private static String require(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return args[index];
        }
    }
}
