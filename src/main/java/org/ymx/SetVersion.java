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
 * being skipped; {@code SpecConsistencyTest} reads the same sites back
 * against {@link YmxFormat#VERSION}.
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
                    + " — \\*\\*\\$[0-9A-F]{4}\\*\\*, version \\d+\\.\\d+",
                    "format version, the major byte then the minor"
                    + " — **$%04X**, version %2$s"),
            new Site("doc/SPEC.md",
                    "the version is \\$[0-9A-F]{4} — \\d+\\.\\d+;",
                    "the version is $%04X — %2$s;"));

    public static void main(String[] args) {
        if (args.length != 1 || !args[0].matches("\\d+\\.\\d+")) {
            throw Tools.fail("usage: setversion.sh MAJOR.MINOR");
        }
        String[] halves = args[0].split("\\.");
        int major = Integer.parseInt(halves[0]);
        int minor = Integer.parseInt(halves[1]);
        if (major > 255 || minor > 255) {
            throw Tools.fail("setversion: each half is a byte, 0 to 255");
        }
        int word = (major << 8) | minor;
        String name = major + "." + minor;

        Map<String, String> texts = new LinkedHashMap<>();
        for (Site site : SITES) {
            String text = texts.computeIfAbsent(site.file(), SetVersion::read);
            Matcher found = Pattern.compile(site.pattern()).matcher(text);
            if (!(found.find() && !found.find())) {
                throw Tools.fail("setversion: " + site.file()
                        + " does not carry exactly one match of \""
                        + site.pattern() + "\" - the site has moved");
            }
            texts.put(site.file(), found.reset().replaceAll(Matcher
                    .quoteReplacement(String.format(site.template(), word, name))));
        }
        for (Map.Entry<String, String> text : texts.entrySet()) {
            try {
                Files.writeString(Tools.repo().resolve(text.getKey()),
                        text.getValue());
            } catch (IOException e) {
                throw Tools.fail("setversion: " + text.getKey() + ": "
                        + e.getMessage());
            }
            System.out.println(text.getKey() + ": version " + name);
        }
    }

    private static String read(String file) {
        Path path = Tools.repo().resolve(file);
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw Tools.fail("setversion: " + path + ": " + e.getMessage());
        }
    }
}
