package dev.uas.core.pack;

import dev.uas.api.pack.AssetSource;
import dev.uas.api.pack.FileAssetSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns an on-disk pack tree into {@link FileAssetSource}s without reading anything.
 *
 * <p>Only paths and sizes are touched; content stays on disk until the compiler
 * streams it. That is what lets a plugin point this at a multi-hundred-megabyte tree
 * on a 2 GiB heap and have it work.
 */
public final class DirectoryScanner {

    /** Editor and VCS noise that must never reach a client pack. */
    public static final Set<String> IGNORED_FILE_NAMES = Set.of(
        ".DS_Store", "Thumbs.db", ".gitkeep");

    public static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(".git", ".svn", ".idea");

    public static final Set<String> IGNORED_EXTENSIONS = Set.of("ds_store", "tmp", "swp", "bak");

    private DirectoryScanner() {
    }

    /** Scans a tree, returning entries sorted by pack path. */
    public static List<AssetSource> scan(Path root) throws IOException {
        return scan(root, path -> true);
    }

    /**
     * Scans a tree.
     *
     * @param root    directory whose contents become the pack root
     * @param include filter on the pack-relative path; use it to restrict the scan
     */
    public static List<AssetSource> scan(Path root, java.util.function.Predicate<String> include)
        throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("not a directory: " + root);
        }
        List<AssetSource> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(path -> !isIgnored(root.relativize(path)))
                .toList();
            for (Path file : files) {
                String packPath = toPackPath(root.relativize(file));
                if (packPath.isEmpty() || !include.test(packPath)) {
                    continue;
                }
                sources.add(new FileAssetSource(packPath, file));
            }
        }
        sources.sort(Comparator.comparing(AssetSource::path));
        return sources;
    }

    /** True when any path segment is editor/VCS noise or the name matches an ignore rule. */
    private static boolean isIgnored(Path relative) {
        for (Path segment : relative) {
            String name = segment.toString();
            if (IGNORED_DIRECTORY_NAMES.contains(name)) {
                return true;
            }
            if (IGNORED_FILE_NAMES.contains(name)) {
                return true;
            }
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && IGNORED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Normalises a platform path to the forward-slash form zip and Minecraft expect. */
    public static String toPackPath(Path relative) {
        StringBuilder builder = new StringBuilder(relative.getNameCount() * 12);
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(relative.getName(i));
        }
        return builder.toString();
    }
}
