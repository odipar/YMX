package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code doc/SPEC.md} against the two implementations of it in this tree.
 *
 * <p>The specification is the contract, and a Java constant, a 68000 {@code
 * equ} and a table row in the document are three copies of every number in it.
 * Two of them are compiled and the third is not, so the document is the one
 * that goes stale. Here it is read back: every offset, size and code the
 * document names is looked up in {@link YmxFormat}, {@link EffectScript},
 * {@link Tune} and {@code 68k/YMX.S}, and a row that no longer matches fails
 * with the row and both values in the message.
 *
 * <p>A reworded sentence fails too, on purpose: these checks locate their
 * numbers by the shape of the table they sit in, so rewriting one without
 * re-measuring it is caught rather than passed over.
 */
final class SpecConsistencyTest {

    private static final Path SPEC = Path.of("doc", "SPEC.md");
    private static final Path PLAYER = Path.of("68k", "YMX.S");

    @Test
    void theHeaderTableIsTheHeaderBothImplementationsRead() throws IOException {
        Map<Integer, String> said = headerTable();
        Map<String, Integer> java = new LinkedHashMap<>();
        java.put("'YMX!'", YmxFormat.OFFSET_MAGIC);
        java.put("format version", YmxFormat.OFFSET_VERSION);
        java.put("flags", YmxFormat.OFFSET_FLAGS);
        java.put("frame count", YmxFormat.OFFSET_FRAMES);
        java.put("frame rate", YmxFormat.OFFSET_PLAYER_HZ);
        java.put("stream count", YmxFormat.OFFSET_STREAM_COUNT);
        java.put("ring size", YmxFormat.OFFSET_RING_SIZE);
        java.put("values decoded per call", YmxFormat.OFFSET_CHUNK);
        java.put("master clock", YmxFormat.OFFSET_MASTER_CLOCK);
        java.put("sample table", YmxFormat.OFFSET_SAMPLE_TABLE);
        java.put("sample count", YmxFormat.OFFSET_SAMPLE_COUNT);
        java.put("section", YmxFormat.OFFSET_SECTION_TABLE);

        for (Map.Entry<String, Integer> field : java.entrySet()) {
            String row = said.get(field.getValue());
            assertTrue(row != null && row.contains(field.getKey()),
                    "SPEC §1.1 has no row at offset " + field.getValue()
                            + " for " + field.getKey() + "; it has " + row);
        }
        assertEquals(java.size(), said.size(), "SPEC §1.1 rows: " + said);

        // The same table, as the 68000 player reads it.
        Map<String, Integer> asm = equates();
        Map<String, Integer> offsets = Map.ofEntries(
                Map.entry("YH_MAGIC", YmxFormat.OFFSET_MAGIC),
                Map.entry("YH_VERSION", YmxFormat.OFFSET_VERSION),
                Map.entry("YH_FLAGS", YmxFormat.OFFSET_FLAGS),
                Map.entry("YH_FRAMES", YmxFormat.OFFSET_FRAMES),
                Map.entry("YH_PLAYER_HZ", YmxFormat.OFFSET_PLAYER_HZ),
                Map.entry("YH_STREAM_COUNT", YmxFormat.OFFSET_STREAM_COUNT),
                Map.entry("YH_RING", YmxFormat.OFFSET_RING_SIZE),
                Map.entry("YH_CHUNK", YmxFormat.OFFSET_CHUNK),
                Map.entry("YH_CLOCK", YmxFormat.OFFSET_MASTER_CLOCK),
                Map.entry("YH_SAMPLES", YmxFormat.OFFSET_SAMPLE_TABLE),
                Map.entry("YH_SAMPLECOUNT", YmxFormat.OFFSET_SAMPLE_COUNT),
                Map.entry("YH_SECTIONS", YmxFormat.OFFSET_SECTION_TABLE));
        offsets.forEach((name, at) -> assertEquals(at.intValue(), equate(asm, name), name));
        assertEquals((int) YmxFormat.MAGIC, equate(asm, "YMX_MAGIC"), "YMX_MAGIC");
        assertEquals(YmxFormat.VERSION, equate(asm, "YMX_VERSION"), "YMX_VERSION");
        assertEquals(YmxFormat.STREAMS, equate(asm, "YMX_STREAMS"), "YMX_STREAMS");
        assertEquals(YmxFormat.CHANNELS, equate(asm, "YMX_CHANNELS"), "YMX_CHANNELS");
        assertEquals(YmxFormat.FLAG_LOOPS, equate(asm, "YMX_FLAG_LOOPS"), "YMX_FLAG_LOOPS");
        assertEquals(YmxFormat.flagChannel(0), equate(asm, "YMX_FLAG_CHAN"), "YMX_FLAG_CHAN");
    }

    @Test
    void theSizesTheDocumentQuotesAreTheSizesThatAreBuilt() throws IOException {
        String said = flat();
        assertEquals(YmxFormat.HEADER_SIZE, number(said,
                "the header is \\*\\*(\\d+) bytes\\*\\*", "the header size"));
        assertEquals(YmxFormat.STREAMS, number(said,
                "`S`, the stream count — always \\*\\*(\\d+)\\*\\*", "the stream count"));
        assertEquals(2520, number(said,
                "`N` is capped at \\*\\*(\\d+)\\*\\*", "the ring cap"));
        // 28 divides 2520 ninety times and covers all twenty-five streams; the
        // next ring up that it divides is the first one past the cap.
        assertTrue(YmxFormat.checkShape(2520, 28).isEmpty(), "2520 must be usable");
        assertTrue(!YmxFormat.checkShape(2548, 28).isEmpty(), "past the cap must not be");
        assertEquals(YmxFormat.SAMPLE_ENTRY_SIZE, number(said,
                "`count` entries of \\*\\*(\\d+) bytes\\*\\*", "a sample entry"));
        assertEquals(YmxFormat.MAX_SAMPLES, number(said,
                "a file may carry at most \\*\\*(\\d+)\\*\\* samples", "the sample ceiling"));
        assertEquals(YmxFormat.SAMPLE_ONE_SHOT, Integer.parseInt(group(said,
                "loop point, or `\\$([0-9A-F]{4})` for one-shot", "the one-shot value"), 16));
        assertEquals(Tune.MFP_CLOCK, number(said,
                "rate = (\\d+) / prescaler", "the MFP clock"));

        // What a player decodes, by the highest channel a tune uses.
        List<Integer> steps = new ArrayList<>();
        Matcher decoded = Pattern.compile(
                "\\*\\*decoded\\*\\* — ((?:\\d+, )*\\d+ or \\d+) \\|").matcher(said);
        assertTrue(decoded.find(), "SPEC §1.5's decoded row no longer matches");
        for (String n : decoded.group(1).split(", | or ")) {
            steps.add(Integer.parseInt(n));
        }
        assertEquals(YmxFormat.CHANNELS + 1, steps.size(), "one step per channel, plus none");
        assertEquals(YmxFormat.liveStreams(0), steps.get(0).intValue(), "no channel in use");
        for (int c = 0; c < YmxFormat.CHANNELS; c++) {
            assertEquals(YmxFormat.liveStreams(YmxFormat.flagChannel(c)),
                    steps.get(c + 1).intValue(), "channel " + c + " the highest in use");
        }
    }

    @Test
    void theVerbAndStreamTablesAreTheCompilersOwn() throws IOException {
        String said = flat();
        int[] verbs = {EffectScript.VERB_RESUME, EffectScript.VERB_HOLD,
                EffectScript.VERB_RELEASE, EffectScript.VERB_START_TOGGLE,
                EffectScript.VERB_RETUNE, EffectScript.VERB_START_RETRIGGER,
                EffectScript.VERB_START_PCM, EffectScript.VERB_START_PCM_PREEMPT};
        String[] names = {"RESUME", "HOLD", "RELEASE", "START_TOGGLE", "RETUNE",
                "START_RETRIGGER", "START_PCM", "START_PCM_PREEMPT"};
        for (int verb = 0; verb < names.length; verb++) {
            assertTrue(said.contains("| " + verb + " | `" + names[verb] + "` |"),
                    "SPEC §3 row " + verb + " is not `" + names[verb] + "`");
            assertEquals(verb << 5, verbs[verb], names[verb] + "'s bits");
        }
        assertEquals(EffectScript.VOICELESS, number(said,
                "so \\*\\*(\\d) is no voice\\*\\*", "the voiceless code"));

        assertTrue(said.contains("| 14 | \\*\\*M\\*\\*".replace("\\", ""))
                        || said.contains("| 14 | **M**"),
                "SPEC §2's stream table no longer names M at 14");
        assertEquals(YmxFormat.STREAM_M, 14);
        assertEquals(YmxFormat.STREAM_X, 15);
        assertEquals(YmxFormat.STREAM_T, 16);
        assertTrue(said.contains("| 15 | **X**") && said.contains("| 16 | **T**"),
                "SPEC §2's stream table no longer names X at 15 and T at 16");
        for (int c = 0; c < YmxFormat.CHANNELS; c++) {
            int action = YmxFormat.streamAction(c);
            assertTrue(said.contains("| " + action + "," + (action + 1)
                            + " | **A" + c + ",P" + c + "**"),
                    "SPEC §2 puts channel " + c + " somewhere other than "
                            + action + "," + (action + 1));
        }
        assertEquals(YmxFormat.REGISTER_STREAMS, 14);
        assertTrue(said.contains("| 0-13 | R0-R13 |"),
                "SPEC §2's register rows no longer cover 0-13");

        // The prescaler table, index by index.
        Matcher dividers = Pattern.compile(
                "\\| divider \\| — \\| ((?:\\d+ \\| )*\\d+) \\|").matcher(said);
        assertTrue(dividers.find(), "SPEC §5's divider row no longer matches");
        String[] cells = dividers.group(1).split(" \\| ");
        assertEquals(Tune.PRESCALERS - 1, cells.length, "one divider per index but 0");
        assertEquals(0, Tune.prescaler(0), "index 0 is the stopped state");
        for (int index = 1; index < Tune.PRESCALERS; index++) {
            assertEquals(Tune.prescaler(index), Integer.parseInt(cells[index - 1]),
                    "prescaler " + index);
        }

        // The rate range the table and the count byte allow.
        int[] range = numbers(said, "The encodable range is ([\\d,]+) Hz — prescaler "
                + "([\\d,]+), count (\\d+), which is ([\\d,]+) — to ([\\d,]+) Hz, "
                + "prescaler (\\d+) and count (\\d+)", "the encodable range");
        assertEquals(range[0], Tune.MFP_CLOCK / (range[1] * range[3]), "the slowest rate");
        assertEquals(range[4], Tune.MFP_CLOCK / (range[5] * range[6]), "the fastest rate");
        assertEquals(range[1], Tune.prescaler(Tune.PRESCALERS - 1), "the slowest prescaler");
        assertEquals(range[5], Tune.prescaler(1), "the fastest prescaler");
        assertEquals(0, range[2], "a count byte of 0");
        assertEquals(256, range[3], "which the MFP reads as 256");
        assertTrue(said.contains("A count of 0 is not: the MFP reads it as 256"),
                "SPEC §5's count-of-0 sentence has been reworded");

        // The code byte's kinds, which both front ends write.
        assertTrue(said.contains("| `00` | **toggle stream**")
                        && said.contains("| `01` | **PCM stream**")
                        && said.contains("| `10` | **wave stream**")
                        && said.contains("| `11` | **retrigger stream**"),
                "SPEC §4's kind table no longer reads 00/01/10/11");
        assertEquals(0x00, Tune.KIND_TOGGLE);
        assertEquals(0x40, Tune.KIND_PCM);
        assertEquals(0x80, Tune.KIND_CURVE);
        assertEquals(0xC0, Tune.KIND_RETRIGGER);

        // T's two bits a channel.
        assertTrue(said.contains("| " + YmxFormat.TIMER_A + " | Timer A |")
                        && said.contains("| " + YmxFormat.TIMER_B + " | Timer B |")
                        && said.contains("| " + YmxFormat.TIMER_C + " | Timer C |")
                        && said.contains("| " + YmxFormat.TIMER_D + " | Timer D |"),
                "SPEC §2.3's timer table no longer matches YmxFormat");
    }

    @Test
    void theMasterByteIsTheCompilersMasterByte() throws IOException {
        String said = flat();
        int[] channelBits = {EffectScript.M_CHANNEL_0, EffectScript.M_CHANNEL_1,
                EffectScript.M_CHANNEL_2, EffectScript.M_CHANNEL_3};
        for (int c = 0; c < channelBits.length; c++) {
            assertEquals(1 << c, channelBits[c], "M's channel " + c + " bit");
            assertTrue(said.contains("| " + c + " | timer channel " + c + " acts"),
                    "SPEC §2.1 no longer puts channel " + c + " in bit " + c);
        }
        assertEquals(1 << 4, EffectScript.M_SKIPS, "M's skip-meaningful bit");
        assertTrue(said.contains("| 4 | bits 7-5 are meaningful this frame"),
                "SPEC §2.1 no longer puts the skip flag in bit 4");
    }

    /** The section-offset rule, which is one bit of one long. */
    @Test
    void theStoredBitIsBit31() throws IOException {
        String said = flat();
        assertTrue(said.contains("Bit 31 of a section's offset says which of the two"
                        + " it is, and the offset is the rest"),
                "SPEC §1.4's stored-section sentence has been reworded");
        assertEquals(1L << 31, YmxFormat.SECTION_STORED);
        assertEquals(0x1234, YmxFormat.sectionOffset(0x1234L | YmxFormat.SECTION_STORED));
        assertTrue(YmxFormat.isStored(YmxFormat.SECTION_STORED));
        assertTrue(!YmxFormat.isStored(0x1234L));
    }

    // ------------------------------------------------------------- the sources

    /** {@code doc/SPEC.md} with its whitespace collapsed, so a rewrapped
     * paragraph reads the same as the one it replaced. */
    private static String flat() throws IOException {
        return String.join(" ", Files.readString(SPEC).split("\\s+"));
    }

    /** SPEC §1.1's rows, by the offset each one gives. */
    private static Map<Integer, String> headerTable() throws IOException {
        String spec = Files.readString(SPEC);
        int from = spec.indexOf("### 1.1 Header");
        assertTrue(from >= 0, "SPEC has no §1.1 Header");
        int to = spec.indexOf("### 1.2", from);
        Map<Integer, String> rows = new LinkedHashMap<>();
        Matcher row = Pattern.compile("^\\| *(\\d+) *\\| *[\\d·S]+ *\\| *(.*?) *\\|?$",
                Pattern.MULTILINE).matcher(spec.substring(from, to));
        while (row.find()) {
            rows.put(Integer.parseInt(row.group(1)), row.group(2));
        }
        return rows;
    }

    /** Every {@code NAME equ VALUE} in the 68000 player, decimal and hex. */
    private static Map<String, Integer> equates() throws IOException {
        Map<String, Integer> equates = new LinkedHashMap<>();
        Matcher equ = Pattern.compile("^(\\w+)\\s+equ\\s+(\\$?[0-9A-Fa-f]+)\\s*(?:;.*)?$",
                Pattern.MULTILINE).matcher(Files.readString(PLAYER));
        while (equ.find()) {
            String value = equ.group(2);
            equates.put(equ.group(1), value.startsWith("$")
                    ? (int) Long.parseLong(value.substring(1), 16)
                    : Integer.parseInt(value));
        }
        return equates;
    }

    /** One equate, or a failure naming the one the player no longer defines. */
    private static int equate(Map<String, Integer> equates, String name) {
        Integer value = equates.get(name);
        assertTrue(value != null, PLAYER + " no longer defines " + name);
        return value == null ? -1 : value;
    }

    private static int number(String document, String pattern, String what) {
        return Integer.parseInt(group(document, pattern, what).replace(",", ""));
    }

    private static String group(String document, String pattern, String what) {
        Matcher found = Pattern.compile(pattern).matcher(document);
        assertTrue(found.find(), "the sentence giving " + what + " no longer matches "
                + pattern + " - this test reads the number out of it");
        return found.group(1);
    }

    private static int[] numbers(String document, String pattern, String what) {
        Matcher found = Pattern.compile(pattern).matcher(document);
        assertTrue(found.find(), "the sentence giving " + what + " no longer matches "
                + pattern + " - this test reads the numbers out of it");
        int[] said = new int[found.groupCount()];
        for (int i = 0; i < said.length; i++) {
            said[i] = Integer.parseInt(found.group(i + 1).replace(",", ""));
        }
        return said;
    }
}
