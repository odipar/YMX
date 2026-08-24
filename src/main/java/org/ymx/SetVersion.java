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
 * {@code ymx/setversion.sh} - rewrite the format version at every site
 * that carries it: the Java, C# and 68k constants and SPEC.md's three
 * mentions. A site is found by the exact text around the version and must
 * match exactly once, so a reworded sentence fails loudly instead of
 * being skipped - and nothing is written unless every site matched.
 * {@code SpecConsistencyTest} reads the same sites back against
 * {@link YmxFormat#VERSION}.
 */
public final class SetVersion {

    private SetVersion() {}

    /** One pattern in one file; the whole match is replaced by the
     * template, formatted with the version word and its name. */
    private record Site(String file, String pattern, String template) {}

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
                    "the version is $%04X - %2$s;"),
            new Site("src/main/java/org/ymx/YmxFormat.java",
                    "public static final int PATCH = \\d+;",
                    "public static final int PATCH = %3$d;"),
            new Site("dotnet/ymx/YmxFormat.cs",
                    "public const int Patch = \\d+;",
                    "public const int Patch = %3$d;"));

    public static void main(String[] args) {
        if (args.length != 1) {
            throw Tools.fail("usage: setversion.sh MAJOR.MINOR[.PATCH]");
        }
        try {
            set(Tools.repo(), args[0]);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            throw Tools.fail(message == null ? e.toString() : message);
        }
    }

    /** Rewrites every site under {@code repo} to {@code version}, given
     * as MAJOR.MINOR[.PATCH] - the patch defaults to 0. Every site is
     * matched before the first write, so a
     * refusal leaves every file as it was. */
    static void set(Path repo, String version) {
        if (!version.matches("[0-9]{1,3}\\.[0-9]{1,3}(\\.[0-9]{1,4})?")) {
            throw new IllegalArgumentException(
                    "usage: setversion.sh MAJOR.MINOR[.PATCH]");
        }
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        if (major > 255 || minor > 255) {
            throw new IllegalArgumentException(
                    "setversion: each half of the format version is a byte,"
                    + " 0 to 255");
        }
        int word = (major << 8) | minor;
        String name = major + "." + minor;

        Map<String, String> texts = new LinkedHashMap<>();
        for (Site site : SITES) {
            String text = texts.computeIfAbsent(site.file(),
                    file -> read(repo.resolve(file)));
            Matcher found = Pattern.compile(site.pattern()).matcher(text);
            if (!(found.find() && !found.find())) {
                throw new IllegalArgumentException("setversion: " + site.file()
                        + " does not carry exactly one match of \""
                        + site.pattern() + "\" - the site has moved");
            }
            texts.put(site.file(), found.reset().replaceAll(Matcher.quoteReplacement(
                    String.format(site.template(), word, name, patch))));
        }
        for (Map.Entry<String, String> text : texts.entrySet()) {
            try {
                Files.writeString(repo.resolve(text.getKey()), text.getValue());
            } catch (IOException e) {
                throw new IllegalArgumentException("setversion: " + text.getKey()
                        + ": " + e.getMessage());
            }
            System.out.println(text.getKey() + ": version " + name + "."
                    + patch);
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
