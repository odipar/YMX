package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code doc/BINARIES.md}, the combiners and the assembled binaries against
 * each other.
 *
 * <p>The contract document, the {@code CORE_}/{@code STUB_} constants and
 * the two 68000 sources each carry the descriptor layouts; the first two are
 * compared here always, and where rmac is on the PATH, {@code mkcores.sh}
 * runs into a scratch directory and every binary it produces is read back:
 * magic, versions, unit, flags, the format version against
 * {@link YmxFormat#VERSION}, and the workspace's fixed size against the
 * equates in {@code 68k/YMX.S}. A core assembled from moved sources fails
 * here rather than combining against the repository's past.
 */
final class BinariesConsistencyTest {

    private static final Path DOC = Path.of("doc", "BINARIES.md");

    @Test
    void theContractTablesMatchTheCombinerConstants() throws IOException {
        String doc = Files.readString(DOC);

        Map<String, Integer> core = table(doc, "## 1. The SNDH core", "## 2.");
        assertEquals(MkSndh.CORE_MAGIC, field(core, "YMXC"));
        assertEquals(MkSndh.CORE_VERSION, field(core, "descriptor version"));
        assertEquals(MkSndh.CORE_UNIT, field(core, "unit size"));
        assertEquals(MkSndh.CORE_FLAGS, field(core, "flags"));
        assertEquals(MkSndh.CORE_FORMAT, field(core, "format version"));
        assertEquals(MkSndh.CORE_WORK_FIXED, field(core, "workspace bytes"));
        assertEquals(MkSndh.CORE_TABLE_OFF, field(core, "table offset"));
        assertEquals(MkSndh.CORE_WORK_OFF, field(core, "workspace offset"));

        Map<String, Integer> stub = table(doc, "## 3. The PRG stub", "## 4.");
        assertEquals(MkPrg.STUB_MAGIC, field(stub, "YMXP"));
        assertEquals(MkPrg.STUB_VERSION, field(stub, "descriptor version"));
        assertEquals(MkPrg.STUB_TUNES, field(stub, "subtunes"));
        assertEquals(MkPrg.STUB_FRAMES, field(stub, "frames"));
        assertEquals(MkPrg.STUB_FLAGS, field(stub, "flags"));
    }

    @Test
    void theAssembledBinariesCarryTheDescriptorsTheCombinersRead(@TempDir Path dir)
            throws IOException, InterruptedException {
        assumeTrue(new ProcessBuilder("rmac", "-?").start().waitFor() >= 0,
                "rmac is not on the PATH");
        Process build = new ProcessBuilder("sh",
                Path.of("ymx", "mkcores.sh").toAbsolutePath().toString(),
                dir.toString()).inheritIO().start();
        assertEquals(0, build.waitFor(), "mkcores.sh failed");

        int fixed = fixedFromEquates();
        for (int unit : new int[] {1, 2, 4}) {
            byte[] core = Files.readAllBytes(dir.resolve("ymxsndh-k" + unit + ".bin"));
            assertEquals('Y', core[MkSndh.CORE_MAGIC], "core k" + unit);
            assertEquals('C', core[MkSndh.CORE_MAGIC + 3], "core k" + unit);
            assertEquals(1, MkSndh.word(core, MkSndh.CORE_VERSION), "core k" + unit);
            assertEquals(unit, MkSndh.word(core, MkSndh.CORE_UNIT), "core k" + unit);
            assertEquals(0, MkSndh.word(core, MkSndh.CORE_FLAGS), "core k" + unit);
            assertEquals(YmxFormat.VERSION, MkSndh.word(core, MkSndh.CORE_FORMAT),
                    "core k" + unit + "'s format version");
            assertEquals(fixed, MkSndh.word(core, MkSndh.CORE_WORK_FIXED),
                    "core k" + unit + "'s workspace fixed size");
            assertEquals(0, core.length & 1, "core k" + unit + " is even-sized");
        }

        byte[] stub = Files.readAllBytes(dir.resolve("ymxprg.bin"));
        assertEquals('Y', stub[MkPrg.STUB_MAGIC]);
        assertEquals('P', stub[MkPrg.STUB_MAGIC + 3]);
        assertEquals(1, MkSndh.word(stub, MkPrg.STUB_VERSION));
        assertEquals(0, stub.length & 1, "the stub is even-sized: the SNDH"
                + " after it loads aligned");
    }

    /** {@code YMX_FIXED}, computed from the plain-number equates in the
     * player source the way the source computes it. */
    private static int fixedFromEquates() throws IOException {
        Map<String, Integer> equates = new LinkedHashMap<>();
        Matcher equ = Pattern.compile("^(\\w+)\\s+equ\\s+(\\d+)\\s*(?:;.*)?$",
                Pattern.MULTILINE).matcher(Files.readString(Path.of("68k", "YMX.S")));
        while (equ.find()) {
            equates.put(equ.group(1), Integer.parseInt(equ.group(2)));
        }
        return equate(equates, "YMX_STATE")
                + equate(equates, "YMX_STREAMS") * equate(equates, "YMX_STATE_SIZE");
    }

    private static int equate(Map<String, Integer> equates, String name) {
        Integer value = equates.get(name);
        assertTrue(value != null, "68k/YMX.S no longer defines " + name);
        return value == null ? 0 : value;
    }

    /** One layout table's rows, keyed by field text, valued by offset. */
    private static Map<String, Integer> table(String doc, String from, String to) {
        int start = doc.indexOf(from);
        assertTrue(start >= 0, DOC + " no longer carries " + from);
        int end = doc.indexOf(to, start);
        Map<String, Integer> rows = new LinkedHashMap<>();
        Matcher row = Pattern.compile("^\\| (\\d+) \\| \\d+ \\| (.+?) \\|$",
                Pattern.MULTILINE).matcher(doc.substring(start, end));
        while (row.find()) {
            rows.put(row.group(2), Integer.parseInt(row.group(1)));
        }
        return rows;
    }

    /** The offset of the row whose field text carries a keyword. */
    private static int field(Map<String, Integer> rows, String keyword) {
        for (Map.Entry<String, Integer> row : rows.entrySet()) {
            if (row.getKey().contains(keyword)) {
                return row.getValue();
            }
        }
        throw new AssertionError(DOC + " has no row for " + keyword + " in " + rows);
    }
}
