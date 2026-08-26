package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Go commands against the C# ones they are ported from: the same flags,
 * and the same defaults behind them.
 *
 * <p>Both drifted before this existed. The Go packer left the drum rate
 * ceiling at zero where the C# takes it from {@code YmEffects.MaxTimerHz},
 * and the first tune with a digidrum divided by it. The trim window and the
 * loop frame were missing outright. Neither showed up in a byte-for-byte
 * sweep, because a flag the Go tree does not parse is a flag the sweep never
 * passes it.</p>
 *
 * <p>A flag is read out of each parser by the text it compares against, so a
 * flag added on one side and not the other fails here rather than in
 * somebody's hands.</p>
 */
final class GoToolsTest {

    /** Each Go command, and the C# source it is ported from. */
    private static final Map<String, String> TWINS = Map.of(
            "go/cmd/ymx/main.go", "dotnet/ym6/Ymx.cs",
            "go/cmd/ym-to-ymx/main.go", "dotnet/ym6/YmToYmx.cs");

    /**
     * Flags one of these commands reads. The C# writes them as switch cases
     * and StartsWith tests; the Go as equality and HasPrefix tests. Both
     * spell the flag itself in a string literal, which is what this reads.
     */
    private static Set<String> flags(String source) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "case \"(-[a-zA-Z]+)\""
                + "|StartsWith\\(\"(-[a-zA-Z]+)\""
                + "|HasPrefix\\(a, \"(-[a-zA-Z]+)\""
                + "|a == \"(-[a-zA-Z]+)\""
                + "|flag == \"(-[a-zA-Z]+)\"").matcher(source);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    found.add(matcher.group(group));
                }
            }
        }
        // -h and --help are a courtesy one tree offers and the other need
        // not; they change no output.
        found.remove("-h");
        found.remove("--help");
        return found;
    }

    @Test
    void theGoCommandsParseTheFlagsTheirTwinsDo() throws IOException {
        for (Map.Entry<String, String> twin : TWINS.entrySet()) {
            Set<String> go = flags(Files.readString(Path.of(twin.getKey())));
            Set<String> cs = flags(Files.readString(Path.of(twin.getValue())));
            Set<String> missing = new LinkedHashSet<>(cs);
            missing.removeAll(go);
            assertTrue(missing.isEmpty(), twin.getKey() + " does not parse "
                    + missing + ", which " + twin.getValue() + " does");
        }
    }

    /**
     * The defaults the Go pipeline starts from, against the constants the C#
     * takes its own from. A default that is only a literal in a command is a
     * default with no single place to be right.
     */
    @Test
    void theGoDefaultsAreTheOnesTheOtherTreesUse() throws IOException {
        String defaults = Files.readString(
                Path.of("go", "internal", "pack", "pack.go"));
        assertTrue(defaults.contains("DrumHz:   ymx.MaxTimerHz"),
                "the Go packer's drum ceiling is not MaxTimerHz, and zero"
                        + " there is a division by zero on the first digidrum");
        assertTrue(defaults.contains("Ring:     ymx.DefaultRingSize"),
                "the Go packer's ring is not DefaultRingSize");
        assertTrue(defaults.contains("Chunk:    ymx.DefaultChunk"),
                "the Go packer's chunk is not DefaultChunk");
        assertTrue(defaults.contains("TimerMap: ymx.DefaultTimers"),
                "the Go packer's timer map is not DefaultTimers");
        for (String unset : new String[] {"StartFrame: -1", "EndFrame:   -1",
                "FrameCount: -1", "LoopFrame:  -1"}) {
            assertTrue(defaults.contains(unset),
                    "the Go packer does not leave " + unset.split(":")[0]
                            + " at -1, which is how it says the caller named none");
        }
    }

    /**
     * The Go tree carries the release version too, so a bump has to reach it:
     * an executable looks its embedded cores up by the name this spells.
     */
    @Test
    void theGoTreeCarriesThisRelease() throws IOException {
        String format = Files.readString(
                Path.of("go", "internal", "ymx", "format.go"));
        assertEquals(YmxFormat.RELEASE_MAJOR, constant(format, "ReleaseMajor"),
                "the Go release major");
        assertEquals(YmxFormat.RELEASE_MINOR, constant(format, "ReleaseMinor"),
                "the Go release minor");
        assertEquals(YmxFormat.PATCH, constant(format, "Patch"),
                "the Go release patch");
        Matcher version = Pattern.compile("const Version = 0x([0-9A-Fa-f]{4})")
                .matcher(format);
        assertTrue(version.find(), "go/internal/ymx/format.go declares no Version");
        assertEquals(YmxFormat.VERSION, Integer.parseInt(version.group(1), 16),
                "the Go format version");
    }

    private static int constant(String source, String name) {
        Matcher matcher = Pattern.compile("const " + name + " = (\\d+)")
                .matcher(source);
        assertTrue(matcher.find(), "go/internal/ymx/format.go declares no " + name);
        return Integer.parseInt(matcher.group(1));
    }
}
