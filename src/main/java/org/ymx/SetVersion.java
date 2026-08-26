package org.ymx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ymx/setversion.sh} - rewrite one of the two versions at every
 * site it reaches. The format version reaches six: the Java, C# and 68k
 * constants and SPEC.md's three mentions. The release version reaches six
 * more, three numbers in each tree. The two are set apart because moving
 * the format one stops every tune already packed from playing and moving
 * the release one breaks nothing.
 *
 * <p>A site is found by the exact text around the version and must match
 * exactly once, so a reworded sentence fails loudly instead of being
 * skipped, and nothing is written unless every site matched.
 * {@code SpecConsistencyTest} reads the same sites back against
 * {@link YmxFormat#VERSION} and {@link YmxFormat#PATCH}, and holds the
 * prose around the constants to naming no version, so these twelve are
 * the whole list.</p>
 */
public final class SetVersion {

    private SetVersion() {}

    /** One pattern in one file; the whole match is replaced by the
     * template, formatted with the version word and its name. */
    private record Site(String file, String pattern, String template) {}

    /** The release's own version: three numbers, in both trees. */
    private static final List<Site> RELEASE_SITES = List.of(
            new Site("src/main/java/org/ymx/YmxFormat.java",
                    "public static final int RELEASE_MAJOR = \\d+;",
                    "public static final int RELEASE_MAJOR = %4$d;"),
            new Site("src/main/java/org/ymx/YmxFormat.java",
                    "public static final int RELEASE_MINOR = \\d+;",
                    "public static final int RELEASE_MINOR = %5$d;"),
            new Site("src/main/java/org/ymx/YmxFormat.java",
                    "public static final int PATCH = \\d+;",
                    "public static final int PATCH = %3$d;"),
            new Site("dotnet/ymx/YmxFormat.cs",
                    "public const int ReleaseMajor = \\d+;",
                    "public const int ReleaseMajor = %4$d;"),
            new Site("dotnet/ymx/YmxFormat.cs",
                    "public const int ReleaseMinor = \\d+;",
                    "public const int ReleaseMinor = %5$d;"),
            new Site("dotnet/ymx/YmxFormat.cs",
                    "public const int Patch = \\d+;",
                    "public const int Patch = %3$d;"));

    /** The format version: the word a header carries and a player checks. */
    private static final List<Site> SITES = List.of(
            new Site("src/main/java/org/ymx/YmxFormat.java",
                    "public static final int VERSION = 0x[0-9A-Fa-f]{4};",
                    "public static final int VERSION = 0x%04X;"),
            new Site("dotnet/ymx/YmxFormat.cs",
                    "public const int Version = 0x[0-9A-Fa-f]{4};",
                    "public const int Version = 0x%04X;"),
            new Site("68k/YMX.S",
                    "YMX_VERSION     equ     \\$[0-9A-F]{4}",
                    "YMX_VERSION     equ     $%04X"),
            new Site("doc/SPEC.md",
                    "Version \\d+\\.\\d+\\. Big-endian throughout\\.",
                    "Version %2$s. Big-endian throughout."),
            new Site("doc/SPEC.md",
                    "format version, the major byte then the minor"
                    + " - \\*\\*\\$[0-9A-F]{4}\\*\\*, version \\d+\\.\\d+",
                    "format version, the major byte then the minor"
                    + " - **$%04X**, version %2$s"),
            new Site("doc/SPEC.md",
                    "the version is \\$[0-9A-F]{4} - \\d+\\.\\d+;",
                    "the version is $%04X - %2$s;"));

    static final String USAGE = "usage: setversion.sh -format MAJOR.MINOR\n"
            + "       setversion.sh -release MAJOR.MINOR[.PATCH]";

    public static void main(String[] args) {
        if (args.length != 2) {
            throw Tools.fail(USAGE);
        }
        boolean format = args[0].equals("-format");
        if (!format && !args[0].equals("-release")) {
            throw Tools.fail(USAGE);
        }
        try {
            set(Tools.repo(), args[1], format);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            throw Tools.fail(message == null ? e.toString() : message);
        }
    }

    /**
     * Rewrites one version under {@code repo}, given as MAJOR.MINOR[.PATCH]
     * - the patch defaults to 0. {@code format} picks which: the format
     * version a header carries and a player checks, or the binaries' own.
     * The two are set apart because moving the format one is a break for
     * every tune already packed, and moving the release one is not. Every
     * site is matched before the first write, so a refusal leaves every
     * file as it was.
     */
    static void set(Path repo, String version, boolean format) {
        if (!version.matches("[0-9]{1,3}\\.[0-9]{1,3}(\\.[0-9]{1,4})?")) {
            throw new IllegalArgumentException(USAGE);
        }
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        if (format && parts.length > 2) {
            throw new IllegalArgumentException(
                    "setversion: the format version is MAJOR.MINOR - a patch"
                    + " is the release's, and -release sets it");
        }
        if (major > 255 || minor > 255) {
            throw new IllegalArgumentException(
                    "setversion: each half of a version is a byte, 0 to 255");
        }
        int word = (major << 8) | minor;
        String name = major + "." + minor;

        Map<String, String> texts = new LinkedHashMap<>();
        for (Site site : (format ? SITES : RELEASE_SITES)) {
            String text = texts.computeIfAbsent(site.file(),
                    file -> read(repo.resolve(file)));
            Matcher found = Pattern.compile(site.pattern()).matcher(text);
            if (!(found.find() && !found.find())) {
                throw new IllegalArgumentException("setversion: " + site.file()
                        + " does not carry exactly one match of \""
                        + site.pattern() + "\" - the site has moved");
            }
            texts.put(site.file(), found.reset().replaceAll(Matcher.quoteReplacement(
                    String.format(site.template(), word, name, patch, major,
                            minor))));
        }
        for (Map.Entry<String, String> text : texts.entrySet()) {
            try {
                Files.writeString(repo.resolve(text.getKey()), text.getValue());
            } catch (IOException e) {
                throw new IllegalArgumentException("setversion: " + text.getKey()
                        + ": " + e.getMessage());
            }
            System.out.println(text.getKey() + ": "
                    + (format ? "format version " + name
                    : "release " + name + "." + patch));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("setversion: " + path + ": "
                    + e.getMessage());
        }
    }
}
