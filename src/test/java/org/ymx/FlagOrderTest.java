package org.ymx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The front ends read their flags by prefix, so a flag tested by prefix
 * takes every longer flag that starts with it: {@code -c} took
 * {@code -copies} in every tree's play command, and the packer was handed
 * {@code opies} as a chunk size. Nothing but the order of the tests keeps
 * the two apart, and this holds that order, for every such pair in every
 * parser, from the source text the way {@link GoToolsTest} reads the flags.
 */
final class FlagOrderTest {

    /** Every command-line parser in the three trees that reads a packer flag. */
    private static final List<Path> PARSERS = List.of(
            Path.of("src", "main", "java", "org", "ym6", "Ymx.java"),
            Path.of("src", "main", "java", "org", "ym6", "YmToYmx.java"),
            Path.of("src", "main", "java", "org", "ym6", "Play.java"),
            Path.of("dotnet", "ym6", "Ymx.cs"),
            Path.of("dotnet", "ym6", "YmToYmx.cs"),
            Path.of("dotnet", "ym6", "PlayTools.cs"),
            Path.of("go", "cmd", "ymx", "main.go"),
            Path.of("go", "cmd", "ym-to-ymx", "main.go"),
            Path.of("go", "cmd", "play", "main.go"));

    /** A flag tested by prefix, in any of the three languages. */
    private static final Pattern PREFIX = Pattern.compile(
            "[sS]tartsWith\\(\"(-[a-zA-Z]+)\""
            + "|HasPrefix\\([a-zA-Z0-9\\[\\]]+, \"(-[a-zA-Z]+)\"");

    /** A flag tested for equality: a switch case, {@code ==} or equals. */
    private static final Pattern EQUAL = Pattern.compile(
            "case \"(-[a-zA-Z]+)\""
            + "|== \"(-[a-zA-Z]+)\""
            + "|[eE]quals\\(\"(-[a-zA-Z]+)\"");

    /** One test in a parser: the flag, whether it is a prefix test, and
     * where in the source it stands. */
    private record Check(String flag, boolean prefix, int at) {}

    /** The tests of a parser, in source order. */
    private static List<Check> checks(String source) {
        List<Check> found = new ArrayList<>();
        for (Pattern pattern : new Pattern[] {PREFIX, EQUAL}) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                for (int group = 1; group <= matcher.groupCount(); group++) {
                    if (matcher.group(group) != null) {
                        found.add(new Check(matcher.group(group), pattern == PREFIX,
                                matcher.start()));
                    }
                }
            }
        }
        found.sort((a, b) -> Integer.compare(a.at(), b.at()));
        return found;
    }

    @Test
    void aLongerFlagIsTestedBeforeTheFlagItStartsWith() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path parser : PARSERS) {
            List<Check> checks = checks(Files.readString(parser));
            assertTrue(checks.stream().anyMatch(Check::prefix),
                    parser + ": no prefix test read out of it, so this test reads nothing");
            Map<String, Integer> first = new LinkedHashMap<>();
            for (Check check : checks) {
                first.putIfAbsent(check.flag(), check.at());
            }
            for (Check check : checks) {
                if (!check.prefix()) {
                    continue;
                }
                for (Map.Entry<String, Integer> other : first.entrySet()) {
                    String flag = other.getKey();
                    if (!flag.equals(check.flag()) && flag.startsWith(check.flag())
                            && other.getValue() > check.at()) {
                        problems.add(parser + ": " + flag + " is tested after the prefix"
                                + " test of " + check.flag() + ", which takes it");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyFrontEndTakesCopies() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path parser : PARSERS) {
            if (checks(Files.readString(parser)).stream()
                    .noneMatch(check -> check.flag().equals("-copies"))) {
                missing.add(parser.toString());
            }
        }
        assertTrue(missing.isEmpty(), "-copies is not read by " + missing);
    }
}
