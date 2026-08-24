package org.ymx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        map.put("src/main/java/org/ymx/SetVersion.java", "### setversion.sh");
        map.put("src/main/java/org/ym6/YmSndh.java", "### ym_sndh.sh");
        map.put("src/main/java/org/ym6/Play.java", "### play.sh");
        map.put("src/main/java/org/ymr/YmrPlay.java", "### ymr.sh");
        map.put("src/main/java/org/st4/St4.java", "### st4 and dst4");
        map.put("src/main/java/org/st4/Dst4.java", "### st4 and dst4");
        // The C# tree parses the same flags; PlayTools.cs holds the three
        // listening tools in one file, so it binds to their whole chapter.
        map.put("dotnet/ym6/Ymx.cs", "### org.ym6.Ymx");
        map.put("dotnet/ymr/Ymr.cs", "### org.ymr.Ymr");
        map.put("dotnet/ymx/MkSndh.cs", "### mksndh.sh");
        map.put("dotnet/ymx/MkPrg.cs", "### mkprg.sh");
        map.put("dotnet/ymx/Tools.cs", "### mkcores.sh");
        map.put("dotnet/ymx/MkRelease.cs", "### mkrelease.sh");
        map.put("dotnet/ymx/SetVersion.cs", "### setversion.sh");
        map.put("dotnet/ym6/PlayTools.cs", "## Listening");
        map.put("dotnet/ymr/YmrPlay.cs", "### ymr.sh");
        map.put("dotnet/st4/St4Cli.cs", "### st4 and dst4");
        return map;
    }

    /** The tools that exist in both trees, Java sources against the C#
     * file that carries the same parser. */
    private static final Map<String, String> TWINS = twins();

    private static Map<String, String> twins() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("src/main/java/org/ym6/Ymx.java", "dotnet/ym6/Ymx.cs");
        map.put("src/main/java/org/ymr/Ymr.java", "dotnet/ymr/Ymr.cs");
        map.put("src/main/java/org/ymx/MkSndh.java", "dotnet/ymx/MkSndh.cs");
        map.put("src/main/java/org/ymx/MkPrg.java", "dotnet/ymx/MkPrg.cs");
        map.put("src/main/java/org/ymx/MkCores.java", "dotnet/ymx/Tools.cs");
        map.put("src/main/java/org/ymx/MkRelease.java", "dotnet/ymx/MkRelease.cs");
        map.put("src/main/java/org/ymx/SetVersion.java", "dotnet/ymx/SetVersion.cs");
        map.put("src/main/java/org/ymr/YmrPlay.java", "dotnet/ymr/YmrPlay.cs");
        return map;
    }

    /** The environment variables and system properties the tools read, and
     * the source that reads each. */
    private static final Map<String, String> ENVIRONMENT = environment();

    private static Map<String, String> environment() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("HATARI", "src/main/java/org/ym6/Play.java,dotnet/ym6/PlayTools.cs");
        map.put("TOS", "src/main/java/org/ym6/Play.java,dotnet/ym6/PlayTools.cs");
        map.put("UNICORN_LIB",
                "src/test/java/org/ymx/rig/Unicorn.java,dotnet/rig/Unicorn.cs");
        map.put("YMX_NOMASK",
                "src/test/java/org/ymx/rig/Rig.java,dotnet/rig/RigCore.cs");
        map.put("YMX_PACK_OPTIONS",
                "src/test/java/org/ymx/rig/Sweep.java,dotnet/rig/Sweep.cs");
        map.put("YMR_FRAME_CAP",
                "src/test/java/org/ymx/rig/YmrSweep.java,dotnet/rig/YmrSweep.cs");
        map.put("ymx.repo", "src/main/java/org/ymx/Tools.java");
        map.put("ymx.core", "src/main/java/org/ymx/MkSndh.java");
        map.put("ymx.stub", "src/main/java/org/ymx/MkPrg.java");
        map.put("YMX_REPO", "dotnet/ymx/Tools.cs");
        map.put("YMX_CORE", "dotnet/ymx/MkSndh.cs");
        map.put("YMX_STUB", "dotnet/ymx/MkPrg.cs");
        return map;
    }

    /** The flag literals a source's argument loop compares against - the
     * Java forms and the C# ones. */
    private static Set<String> parsedFlags(String source) {
        Set<String> flags = new LinkedHashSet<>();
        Matcher compared = Pattern.compile(
                "(?:equals|startsWith|StartsWith)\\(\"(--?[a-zA-Z-]+)\"\\)"
                + "|case \"(-[a-zA-Z-]+)\"|== \"(--?[a-zA-Z-]+)\"")
                .matcher(source);
        while (compared.find()) {
            for (int group = 1; group <= 3; group++) {
                if (compared.group(group) != null) {
                    flags.add(compared.group(group));
                }
            }
        }
        return flags;
    }

    /** One section's text: from its heading to the next heading of the
     * same or a higher level, so a chapter covers its subsections. */
    private static String section(String doc, String heading) {
        int start = doc.indexOf(heading + "\n");
        assertTrue(start >= 0, DOC + " no longer carries the section " + heading);
        int level = heading.length() - heading.replace("#", "").length();
        Matcher next = Pattern.compile("(?m)^#{1," + level + "}\\s").matcher(doc);
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
        // And the other direction: every bare name the section backticks
        // is a case the dispatcher carries. Spans with a dot, dash or
        // space - wrapper names, flags, the dll path - are not names.
        Matcher listed = Pattern.compile("`(\\w+)`").matcher(doc);
        while (listed.find()) {
            assertTrue(program.contains("case \"" + listed.group(1) + "\""),
                    "the C# tool names section lists " + listed.group(1)
                    + ", which dotnet/Program.cs does not dispatch");
        }
    }

    /** The two SetVersion implementations, site list against site list:
     * each {@code new Site(file, pattern, template)} triple must agree
     * once the C# format placeholders are read as the Java ones. */
    @Test
    void theTwoTreesRewriteTheSameSites() throws IOException {
        List<String[]> java = sites(Files.readString(
                Path.of("src", "main", "java", "org", "ymx", "SetVersion.java")));
        List<String[]> cs = sites(Files.readString(
                Path.of("dotnet", "ymx", "SetVersion.cs")));
        assertTrue(java.size() == 6, "SetVersion.java carries " + java.size()
                + " sites; doc/tools.md and SetVersion's own doc say six");
        assertTrue(java.size() == cs.size(), "SetVersion.java carries "
                + java.size() + " sites and SetVersion.cs " + cs.size());
        for (int at = 0; at < java.size(); at++) {
            String[] ours = java.get(at);
            String[] theirs = cs.get(at);
            for (int part = 0; part < 3; part++) {
                String cSharp = theirs[part]
                        .replace("{0:X4}", "%04X").replace("{1}", "%2$s");
                assertTrue(ours[part].equals(cSharp), "site " + at
                        + " differs between the trees: " + ours[part]
                        + " against " + theirs[part]);
            }
        }
    }

    /** Each {@code new Site(...)} triple in a source, with concatenated
     * string literals joined. The arguments carry no quotes of their own,
     * so after the join each is one literal. */
    private static List<String[]> sites(String source) {
        String joined = source.replaceAll("\"\\s*\\+\\s*\"", "");
        Matcher site = Pattern.compile("new Site\\(\"([^\"]*)\",\\s*"
                + "\"([^\"]*)\",\\s*\"([^\"]*)\"\\)").matcher(joined);
        List<String[]> found = new ArrayList<>();
        while (site.find()) {
            found.add(new String[] {site.group(1), site.group(2), site.group(3)});
        }
        return found;
    }

    @Test
    void theEnvironmentTableMatchesTheSources() throws IOException {
        String doc = section(Files.readString(DOC), "## Environment");
        for (Map.Entry<String, String> variable : ENVIRONMENT.entrySet()) {
            assertTrue(doc.contains(variable.getKey()),
                    "the environment table does not list " + variable.getKey());
            for (String reader : variable.getValue().split(",")) {
                assertTrue(Files.readString(Path.of(reader))
                                .contains("\"" + variable.getKey() + "\""),
                        reader + " no longer reads " + variable.getKey());
            }
        }
    }

    @Test
    void theTwoTreesParseTheSameFlags() throws IOException {
        for (Map.Entry<String, String> twin : TWINS.entrySet()) {
            Set<String> java = parsedFlags(Files.readString(Path.of(twin.getKey())));
            Set<String> cs = parsedFlags(Files.readString(Path.of(twin.getValue())));
            assertTrue(java.equals(cs), twin.getKey() + " parses " + java
                    + " and " + twin.getValue() + " parses " + cs
                    + " - the trees have drifted");
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
