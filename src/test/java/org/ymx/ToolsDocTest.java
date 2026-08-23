package org.ymx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.ym6.YmEffects;

/**
 * {@code doc/tools.md} against the sources it documents: every flag a tool
 * parses appears in that tool's section, every C# dispatcher name is
 * listed, the environment variables are the ones the sources read, and the
 * defaults quoted in prose are the constants' values.
 */
final class ToolsDocTest {

    private static final Path DOC = Path.of("doc", "tools.md");

    /** Each tool's argument parser, and the section that documents it. */
    private static final Map<String, String> SECTIONS = sections();

    private static Map<String, String> sections() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("src/main/java/org/ym6/Ymx.java", "### org.ym6.Ymx");
        map.put("src/main/java/org/ymr/Ymr.java", "### org.ymr.Ymr");
        map.put("src/main/java/org/ymx/MkSndh.java", "### mksndh.sh");
        map.put("src/main/java/org/ymx/MkPrg.java", "### mkprg.sh");
        map.put("src/main/java/org/ymx/MkCores.java", "### mkcores.sh");
        map.put("src/main/java/org/ymx/MkRelease.java", "### mkrelease.sh");
        map.put("src/main/java/org/ym6/YmSndh.java", "### ym_sndh.sh");
        map.put("src/main/java/org/ym6/Play.java", "### play.sh");
        map.put("src/main/java/org/ymr/YmrPlay.java", "### ymr.sh");
        map.put("src/main/java/org/st4/St4.java", "### st4 and dst4");
        map.put("src/main/java/org/st4/Dst4.java", "### st4 and dst4");
        return map;
    }

    /** The environment variables and system properties the tools read, and
     * the source that reads each. */
    private static final Map<String, String> ENVIRONMENT = environment();

    private static Map<String, String> environment() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("HATARI", "src/main/java/org/ym6/Play.java");
        map.put("TOS", "src/main/java/org/ym6/Play.java");
        map.put("UNICORN_LIB", "src/test/java/org/ymx/rig/Unicorn.java");
        map.put("YMX_NOMASK", "src/test/java/org/ymx/rig/Rig.java");
        map.put("YMX_PACK_OPTIONS", "src/test/java/org/ymx/rig/Sweep.java");
        map.put("YMR_FRAME_CAP", "src/test/java/org/ymx/rig/YmrSweep.java");
        map.put("ymx.repo", "src/main/java/org/ymx/Tools.java");
        map.put("ymx.core", "src/main/java/org/ymx/MkSndh.java");
        map.put("ymx.stub", "src/main/java/org/ymx/MkPrg.java");
        map.put("YMX_REPO", "dotnet/ymx/Tools.cs");
        map.put("YMX_CORE", "dotnet/ymx/MkSndh.cs");
        map.put("YMX_STUB", "dotnet/ymx/MkPrg.cs");
        return map;
    }

    /** The flag literals a source's argument loop compares against. */
    private static Set<String> parsedFlags(String source) {
        Set<String> flags = new LinkedHashSet<>();
        Matcher compared = Pattern.compile(
                "(?:equals|startsWith)\\(\"(--?[a-zA-Z-]+)\"\\)"
                + "|case \"(-[a-zA-Z-]+)\"").matcher(source);
        while (compared.find()) {
            flags.add(compared.group(1) != null ? compared.group(1)
                    : compared.group(2));
        }
        return flags;
    }

    /** One section's text: from its heading to the next heading. */
    private static String section(String doc, String heading) {
        int start = doc.indexOf(heading + "\n");
        assertTrue(start >= 0, DOC + " no longer carries the section " + heading);
        Matcher next = Pattern.compile("(?m)^##").matcher(doc);
        int end = next.find(start + heading.length()) ? next.start() : doc.length();
        return doc.substring(start, end);
    }

    @Test
    void everyParsedFlagIsDocumentedInItsSection() throws IOException {
        String doc = Files.readString(DOC);
        for (Map.Entry<String, String> tool : SECTIONS.entrySet()) {
            String source = Files.readString(Path.of(tool.getKey()));
            String text = section(doc, tool.getValue());
            Set<String> flags = parsedFlags(source);
            for (String flag : flags) {
                assertTrue(mentions(text, flag, flags),
                        tool.getValue() + " does not document " + flag
                        + ", which " + tool.getKey() + " parses");
            }
        }
    }

    /** Whether the text carries the flag itself: an occurrence counts
     * unless it is the start of a longer flag of the same tool, so -n is
     * found in -nN and never in -nomask. */
    private static boolean mentions(String text, String flag, Set<String> flags) {
        for (int at = text.indexOf(flag); at >= 0;
                at = text.indexOf(flag, at + 1)) {
            boolean longer = false;
            for (String other : flags) {
                if (!other.equals(flag) && other.startsWith(flag)
                        && text.startsWith(other, at)) {
                    longer = true;
                    break;
                }
            }
            if (!longer) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyDispatcherNameIsListed() throws IOException {
        String doc = section(Files.readString(DOC), "## The C# tool names");
        String program = Files.readString(Path.of("dotnet", "Program.cs"));
        Matcher cases = Pattern.compile("case \"(\\w+)\"").matcher(program);
        int found = 0;
        while (cases.find()) {
            String name = cases.group(1);
            assertTrue(doc.contains("`" + name + "`"),
                    "the C# tool names section does not list " + name);
            found++;
        }
        assertTrue(found >= 15, "dotnet/Program.cs dispatches " + found
                + " tools; the dispatcher has moved");
    }

    @Test
    void theEnvironmentTableMatchesTheSources() throws IOException {
        String doc = section(Files.readString(DOC), "## Environment");
        for (Map.Entry<String, String> variable : ENVIRONMENT.entrySet()) {
            assertTrue(doc.contains(variable.getKey()),
                    "the environment table does not list " + variable.getKey());
            assertTrue(Files.readString(Path.of(variable.getValue()))
                            .contains("\"" + variable.getKey() + "\""),
                    variable.getValue() + " no longer reads " + variable.getKey());
        }
    }

    @Test
    void theQuotedDefaultsAreTheConstants() throws IOException {
        String doc = Files.readString(DOC);
        assertTrue(doc.contains("default " + YmxFormat.DEFAULT_RING_SIZE),
                "the ring default is " + YmxFormat.DEFAULT_RING_SIZE);
        assertTrue(doc.contains("default " + YmxFormat.DEFAULT_CHUNK),
                "the chunk default is " + YmxFormat.DEFAULT_CHUNK);
        assertTrue(doc.contains("default " + YmEffects.MAX_TIMER_HZ),
                "the drum ceiling default is " + YmEffects.MAX_TIMER_HZ);
        assertTrue(doc.contains("-dotnet"), "the -dotnet flag is undocumented");
    }
}
