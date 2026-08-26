package org.ymx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code ym-to-ymx} against the flags it says it takes.
 *
 * <p>The tool is the C# tree's, and the one a release ships as a standalone
 * executable, so it is the first thing a stranger runs and its help text is
 * the only documentation most of them will read. Every flag that text names
 * has to be one the tool passes on: the packer's go to the packer and the
 * combiners' are read here, and a flag named in help and dropped on the
 * floor is silent.
 */
final class YmToYmxDocTest {

    private static final Path TOOL = Path.of("dotnet", "ym6", "YmToYmx.cs");

    @Test
    void everyFlagTheHelpNamesIsOneTheToolReads() throws IOException {
        String source = Files.readString(TOOL);
        String help = source.substring(source.indexOf("UsageText ="),
                source.indexOf("public static void Main"));

        // the packer's own flags, which this tool collects and passes on
        List<String> packer = List.of("-f", "-o", "-l", "-n", "-c", "-k",
                "-min", "-sec", "-startframe", "-endframe", "-frames",
                "-drumhz", "-timers", "-sidresume");
        for (String flag : packer) {
            assertTrue(help.contains(flag), "ym-to-ymx's help does not name "
                    + flag + ", which the packer takes and this tool passes"
                    + " through");
        }

        // the ones this tool reads itself, each named in help and matched in
        // the parser below it
        String parser = source.substring(source.indexOf("public static void Main"));
        for (String flag : List.of("-perf", "-nomask", "-m", "-t", "-N")) {
            assertTrue(help.contains(flag), "ym-to-ymx's help does not name "
                    + flag);
            assertTrue(parser.contains("\"" + flag + "\""),
                    "ym-to-ymx names " + flag + " in its help and its parser"
                            + " does not read it");
        }

        for (String kind : List.of(".ymx", ".sndh", ".prg")) {
            assertTrue(help.contains(kind) && parser.contains("\"" + kind + "\""),
                    "ym-to-ymx's help and its parser disagree about " + kind);
        }
    }

    /** The launchers pass every argument on and take the emulator's own
     * through one variable, so a scener can reach both. */
    @Test
    void bothLaunchersReachTheEmulatorAndTheTool() throws IOException {
        for (String script : List.of("ymxplay.sh", "ymxplay.cmd")) {
            String text = Files.readString(Path.of("ymx", script));
            for (String name : List.of("HATARI", "TOS", "HATARI_OPTS",
                    "ym-to-ymx")) {
                assertTrue(text.contains(name), "ymx/" + script + " never"
                        + " mentions " + name);
            }
        }
    }
}
