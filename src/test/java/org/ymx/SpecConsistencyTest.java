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
 * equ} and a table row in the document are three copies of every number in it
 * - the format version has a fourth, in the C# tree. The compiled copies
 * cannot go stale; the document can. Here it is read back: every offset,
 * size and code the document names is looked up in {@link YmxFormat},
 * {@link EffectScript}, {@link Tune}, {@code 68k/YMX.S} and
 * {@code dotnet/ymx/YmxFormat.cs}, and a row that no longer matches fails
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
        java.put("`L`, the frame", YmxFormat.OFFSET_LOOP_FRAME);
        java.put("loop table", YmxFormat.OFFSET_LOOP_TABLE);
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
                Map.entry("YH_LOOP", YmxFormat.OFFSET_LOOP_FRAME),
                Map.entry("YH_LOOPTAB", YmxFormat.OFFSET_LOOP_TABLE),
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
                "`S`, the stream count - always \\*\\*(\\d+)\\*\\*", "the stream count"));
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
                "\\*\\*decoded\\*\\* - ((?:\\d+, )*\\d+ or \\d+) \\|").matcher(said);
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

    /**
     * SPEC §9.3's rules on {@code L} and the loop table, against every packed
     * file in the tree.
     *
     * <p>The rules are what a player is built on: a wrap either moves the read
     * position in every ring back one pass, which a file whose pass is longer
     * than a ring cannot do, or opens a second section per stream, which needs
     * a loop table naming twenty-five of them. Nothing in a player checks
     * either (§9.1), which puts the whole of it on the packer - and the pinned
     * files are what the packer wrote.
     */
    @Test
    void everyPackedFileKeepsTheLoopRulesTheDocumentStates() throws IOException {
        String said = flat();
        assertTrue(said.contains("Where the loop table offset is 0, each of the"
                        + " twenty-five sections decodes to `O` values, and `L`"
                        + " is 0 or leaves `O` - `L` at most `N`"),
                "SPEC §9.3's rule on a file with no loop table has been"
                        + " reworded: this test reads the bounds it checks the"
                        + " packed files against out of it");
        assertTrue(said.contains("Where the loop table offset is not 0 it is a"
                        + " long boundary, `L` is not 0, and the loop table's"
                        + " twenty-five entries are nonzero, `O` - `L` is larger"
                        + " than `N`"),
                "SPEC §9.3's rule on a file with a loop table has been"
                        + " reworded: this test reads the bounds it checks the"
                        + " packed files against out of it");
        List<Path> packed = new ArrayList<>();
        for (String corpus : new String[] {"ym", "ymr"}) {
            try (var listing = Files.list(Path.of(corpus, "test"))) {
                listing.filter(file -> file.toString().endsWith(".ymx"))
                        .sorted().forEach(packed::add);
            }
        }
        assertTrue(!packed.isEmpty(), "no packed tunes in ym/test or ymr/test");
        int withLoopFrame = 0;
        int withLoopTable = 0;
        for (Path file : packed) {
            byte[] bytes = Files.readAllBytes(file);
            int frames = (int) field(bytes, YmxFormat.OFFSET_FRAMES, 4);
            int ring = (int) field(bytes, YmxFormat.OFFSET_RING_SIZE, 2);
            int loopFrame = (int) field(bytes, YmxFormat.OFFSET_LOOP_FRAME, 4);
            int loopTable = (int) field(bytes, YmxFormat.OFFSET_LOOP_TABLE, 4);
            if (loopFrame == 0) {
                assertEquals(0, loopTable, file + " carries a loop table"
                        + " without a loop frame");
                continue;
            }
            withLoopFrame++;
            assertTrue(loopFrame < frames, file + " starts over at frame "
                    + loopFrame + " of " + frames);
            if (loopTable == 0) {
                assertTrue(frames - loopFrame <= ring, file + " replays "
                        + (frames - loopFrame) + " frames through rings of "
                        + ring + " with no loop table");
                continue;
            }
            withLoopTable++;
            assertTrue(frames - loopFrame > ring, file + " replays "
                    + (frames - loopFrame) + " frames through rings of " + ring
                    + " and still carries a loop table");
            assertEquals(0, loopTable % 4, file + "'s loop table is at "
                    + loopTable + ", off a long boundary");
            for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
                assertTrue(field(bytes, loopTable + 4 * stream, 4) != 0,
                        file + "'s loop table has no section for stream " + stream);
            }
        }
        assertTrue(withLoopFrame > 0, "no packed tune carries a loop frame, so"
                + " this test is checking nothing");
        assertTrue(withLoopTable > 0, "no packed tune carries a loop table, so"
                + " this test is checking nothing");
    }

    /** One big-endian header field of a packed file. */
    private static long field(byte[] file, int at, int size) {
        long value = 0;
        for (int byteAt = 0; byteAt < size; byteAt++) {
            value = (value << 8) | (file[at + byteAt] & 0xFF);
        }
        return value;
    }

    @Test
    void theOpcodeAndStreamTablesAreTheCompilersOwn() throws IOException {
        String said = flat();
        int[] opcodes = {EffectScript.OPCODE_RESUME, EffectScript.OPCODE_HOLD,
                EffectScript.OPCODE_RELEASE, EffectScript.OPCODE_START_TOGGLE,
                EffectScript.OPCODE_RETUNE, EffectScript.OPCODE_START_RETRIGGER,
                EffectScript.OPCODE_START_PCM, EffectScript.OPCODE_START_PCM_PREEMPT};
        String[] names = {"RESUME", "HOLD", "RELEASE", "START_TOGGLE", "RETUNE",
                "START_RETRIGGER", "START_PCM", "START_PCM_PREEMPT"};
        for (int opcode = 0; opcode < names.length; opcode++) {
            assertTrue(said.contains("| " + opcode + " | `" + names[opcode] + "` |"),
                    "SPEC §3 row " + opcode + " is not `" + names[opcode] + "`");
            assertEquals(opcode << 5, opcodes[opcode], names[opcode] + "'s bits");
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
                "\\| divider \\| - \\| ((?:\\d+ \\| )*\\d+) \\|").matcher(said);
        assertTrue(dividers.find(), "SPEC §5's divider row no longer matches");
        String[] cells = dividers.group(1).split(" \\| ");
        assertEquals(Tune.PRESCALERS - 1, cells.length, "one divider per index but 0");
        assertEquals(0, Tune.prescaler(0), "index 0 is the stopped state");
        for (int index = 1; index < Tune.PRESCALERS; index++) {
            assertEquals(Tune.prescaler(index), Integer.parseInt(cells[index - 1]),
                    "prescaler " + index);
        }

        // The rate range the table and the count byte allow.
        int[] range = numbers(said, "The encodable range is ([\\d,]+) Hz - prescaler "
                + "([\\d,]+), count (\\d+), which is ([\\d,]+) - to ([\\d,]+) Hz, "
                + "prescaler (\\d+) and count (\\d+)", "the encodable range");
        assertEquals(range[0], Tune.MFP_CLOCK / (range[1] * range[3]), "the slowest rate");
        assertEquals(range[4], Tune.MFP_CLOCK / (range[5] * range[6]), "the fastest rate");
        assertEquals(range[1], Tune.prescaler(Tune.PRESCALERS - 1), "the slowest prescaler");
        assertEquals(range[5], Tune.prescaler(1), "the fastest prescaler");
        assertEquals(0, range[2], "a count byte of 0");
        assertEquals(256, range[3], "which the MFP reads as 256");
        assertTrue(said.contains("A count of 0 is read by the MFP as 256"),
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

    /** The register masks are the ones a writer must apply. */
    @Test
    void theMaskTableIsWhatTheEncoderApplies() throws IOException {
        String said = flat();
        int[][] rows = {
                {0, 2, 4}, {1, 3, 5}, {6}, {7}, {8, 9, 10}, {11, 12}, {13},
        };
        String[] kept = {"8", "4", "5", "6", "5", "8", "4"};
        String[] labels = {"R0, R2, R4", "R1, R3, R5", "R6", "R7",
                "R8, R9, R10", "R11, R12", "R13"};
        for (int row = 0; row < rows.length; row++) {
            assertTrue(said.contains("| " + labels[row] + " | " + kept[row] + " -"),
                    "SPEC §2's mask table no longer gives " + labels[row] + " "
                            + kept[row] + " bits");
            for (int register : rows[row]) {
                int mask = Ym2149.mask(register, register == 13 ? 0x7F : 0xFF);
                assertEquals(Integer.parseInt(kept[row]),
                        Integer.bitCount(mask), "R" + register + "'s kept bits");
            }
        }
        assertEquals(0xFF, Ym2149.mask(13, 0xFF),
                "R13's $FF must pass through unmasked - the do-not-write marker");
    }

    /** Every version mention against {@link YmxFormat#VERSION}: SPEC's
     * three and the C# constant, read back so a bump that misses one
     * fails by name. The 68k equate, the one {@code ymx/setversion.sh}
     * site not read back here, is bound with the header table above. */
    @Test
    void everyVersionMentionIsTheConstant() throws IOException {
        String said = flat();
        String hex = String.format("$%04X", YmxFormat.VERSION);
        String name = YmxFormat.versionName();
        assertTrue(said.contains("Version " + name + ". Big-endian throughout."),
                "SPEC's opening line no longer carries version " + name);
        assertTrue(said.contains("- **" + hex + "**, version " + name),
                "SPEC §1.1's version row no longer carries " + hex);
        assertTrue(said.contains("the version is " + hex + " - " + name),
                "SPEC §9.1 no longer names version " + hex);
        String cs = Files.readString(Path.of("dotnet", "ymx", "YmxFormat.cs"));
        Matcher constant = Pattern.compile(
                "public const int Version = 0x([0-9A-Fa-f]{4});").matcher(cs);
        assertTrue(constant.find(), "YmxFormat.cs no longer declares Version");
        assertEquals(YmxFormat.VERSION, Integer.parseInt(constant.group(1), 16),
                "the C# Version differs from the Java one");
        Matcher patch = Pattern.compile("public const int Patch = (\\d+);")
                .matcher(cs);
        assertTrue(patch.find(), "YmxFormat.cs no longer declares Patch");
        assertEquals(YmxFormat.PATCH, Integer.parseInt(patch.group(1)),
                "the C# Patch differs from the Java one");
        assertEquals(name + "." + YmxFormat.PATCH, YmxFormat.releaseName(),
                "the release name is the format version plus the patch");
    }

    /** The section-offset rule, which is one bit of one long. */
    @Test
    void theStoredBitIsBit31() throws IOException {
        String said = flat();
        assertTrue(said.contains("Bit 31 of an entry set marks a stored section,"
                        + " in either table; bits 30-0 are the offset"),
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
