package org.st4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class St4RoundTripTest {

    private static St4Compressor.Result pack(byte[] input, int unit) {
        return pack(input, unit, St4Format.maxOffsetUnits(unit), St4Format.MAX_OP);
    }

    private static St4Compressor.Result pack(byte[] input, int unit, int offsetLimit,
                                             int maxOpLength) {
        int[] units = Units.split(input, unit);
        // No progress bar: a test run is not a person waiting at a terminal.
        return St4Compressor.compress(
                St4Optimizer.optimize(units, unit, offsetLimit, false), units, unit,
                maxOpLength);
    }

    private static byte[] unpack(St4Compressor.Result packed) {
        return St4Decompressor.decompress(packed.control(), packed.literal(),
                packed.byteOffsets(), packed.wordOffsets(), packed.unit(),
                packed.paddedSize());
    }

    private static byte[] unpack(St4Compressor.Result packed, int offsetLimit) {
        return St4Decompressor.decompress(packed.control(), packed.literal(),
                packed.byteOffsets(), packed.wordOffsets(), packed.unit(),
                packed.paddedSize(), offsetLimit);
    }

    /**
     * Packs a stream that loops by rewind: the intro and the loop from unit
     * {@code index} parsed on their own, as the packer does when the loop is
     * longer than the window.
     */
    private static St4Compressor.Result packRewinding(byte[] input, int unit, int window,
                                                      int index) {
        int[] units = Units.split(input, unit);
        int[] intro = Arrays.copyOfRange(units, 0, index);
        int[] loop = Arrays.copyOfRange(units, index, units.length);
        return St4Compressor.compressRewinding(
                intro.length == 0 ? null : St4Optimizer.optimize(intro, unit, window, false),
                St4Optimizer.optimize(loop, unit, window, false), units, unit,
                St4Format.MAX_OP, index);
    }

    /** Packs a stream that loops: after its last unit it continues from unit {@code index}. */
    private static St4Compressor.Result packRepeating(byte[] input, int unit, int index) {
        int[] units = Units.split(input, unit);
        return St4Compressor.compress(
                St4Optimizer.optimize(units, unit, St4Format.maxOffsetUnits(unit), false),
                units, unit, St4Format.MAX_OP, index);
    }

    private static byte[] padded(byte[] input, int unit) {
        return Arrays.copyOf(input, Units.paddedLength(input.length, unit));
    }

    private static List<byte[]> inputs() {
        byte[] random = new byte[997];
        new Random(7).nextBytes(random);
        byte[] allSame = new byte[1000];
        Arrays.fill(allSame, (byte) 'A');
        byte[] period = new byte[1024];
        for (int i = 0; i < period.length; i++) {
            period[i] = (byte) (i % 12);
        }
        byte[] words = new byte[2048];
        for (int i = 0; i < words.length; i += 2) {
            words[i] = (byte) (i / 64);
            words[i + 1] = (byte) (i % 7);
        }
        return List.of(new byte[] {42}, new byte[] {1, 2, 3}, random, allSame, period, words,
                "abracadabra hocus pocus ".repeat(20).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void roundTripsAtEveryUnitSize() {
        for (int unit : new int[] {1, 2, 4}) {
            for (byte[] input : inputs()) {
                St4Compressor.Result packed = pack(input, unit);
                assertArrayEquals(padded(input, unit), unpack(packed),
                        "unit " + unit + ", input of " + input.length + " bytes");
            }
        }
    }

    @Test
    void everyStreamIsAWholeNumberOfUnits() {
        for (int unit : new int[] {1, 2, 4}) {
            for (byte[] input : inputs()) {
                St4Compressor.Result packed = pack(input, unit);
                assertEquals(0, packed.literal().length % unit,
                        "stream B holds whole units only");
                assertEquals(0, packed.control().length % 2,
                        "stream A is refilled a word at a time, so it ends on one");
                assertEquals(0, packed.wordOffsets().length % 2,
                        "stream D holds whole words only");
                assertEquals(0, packed.paddedSize() % unit);
            }
        }
    }

    @Test
    void limitedOffsetsStayInsideTheirWindow() {
        // -mN is what makes a stream safe for an N-unit ring; decoding through
        // exactly that much history has to reproduce the input.
        byte[] input = new byte[8000];
        var random = new Random(11);
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (random.nextInt(4) * 17 + i % 3);
        }
        for (int unit : new int[] {1, 2, 4}) {
            for (int window : new int[] {64, 256, 4096}) {
                St4Compressor.Result packed = pack(input, unit, window, St4Format.MAX_OP);
                assertArrayEquals(padded(input, unit), unpack(packed, window),
                        "unit " + unit + ", window " + window);
            }
        }
    }

    @Test
    void theLimitCheckingDecoderRefusesAWiderStream() {
        // Decoding at a window is how tests hold a -mN stream to its ring. A
        // stream that reaches further reads as copies from the literal stream
        // there, and random data repeated once makes one that cannot be: a
        // thousand units copied from 992 literals back.
        byte[] half = new byte[1000];
        new Random(5).nextBytes(half);
        byte[] input = new byte[2000];
        System.arraycopy(half, 0, input, 0, 1000);
        System.arraycopy(half, 0, input, 1000, 1000);
        St4Compressor.Result wide = pack(input, 1);
        assertThrows(IllegalStateException.class, () -> unpack(wide, 8),
                "a full-window stream through an 8-unit limit");
    }

    @Test
    void splittingKeepsOperationsInsideAWordCounter() {
        byte[] input = new byte[40000];
        Arrays.fill(input, (byte) 0x5A);
        for (int unit : new int[] {1, 2, 4}) {
            St4Compressor.Result packed = pack(input, unit,
                    St4Format.maxOffsetUnits(unit), 1000);
            assertTrue(packed.longestOp() <= 1000,
                    "unit " + unit + " emitted an operation of " + packed.longestOp());
            assertArrayEquals(padded(input, unit), unpack(packed));
        }
    }

    @Test
    void aRepeatingStreamFillsAnyOutputBeyondOnePass() {
        // A stream that loops from unit R decodes as the infinite input
        // units[0..R) units[R..O)*: past one whole pass, every unit is the one
        // O-R units back. Any output size from one pass up must decode, and
        // every byte past the pass must obey that recurrence.
        for (int unit : new int[] {1, 2, 4}) {
            for (byte[] input : inputs()) {
                int total = Units.paddedLength(input.length, unit) / unit;
                for (int index : new java.util.TreeSet<>(
                        List.of(0, total / 3, total - 1))) {
                    St4Compressor.Result packed = packRepeating(input, unit, index);
                    byte[] pass = padded(input, unit);
                    byte[] expected = Arrays.copyOf(pass, pass.length
                            + (2 * total + 3) * unit);
                    for (int at = pass.length; at < expected.length; at++) {
                        expected[at] = expected[at - (total - index) * unit];
                    }
                    String shape = "unit " + unit + ", " + input.length + " bytes, -r" + index;
                    assertArrayEquals(expected, St4Decompressor.decompress(
                            packed.control(), packed.literal(), packed.byteOffsets(),
                            packed.wordOffsets(), unit, expected.length), shape);
                    // One exact pass is still decodable: the repeat has no room
                    // and the streams must come out fully consumed anyway.
                    assertArrayEquals(pass, unpack(packed), shape);
                }
            }
        }
    }

    @Test
    void aRewindStreamDecodesToItsPassAndNeverReachesBeforeTheLoop() {
        // The loop is parsed on its own, so replaying it from the state saved
        // at the loop point sees the same history every pass. The reference
        // holds a stream to that: from the rewind point on, no match may reach
        // before it - and the pass must still be the input.
        for (int unit : new int[] {1, 2, 4}) {
            for (byte[] input : inputs()) {
                int total = Units.paddedLength(input.length, unit) / unit;
                for (int index : new java.util.TreeSet<>(
                        List.of(0, total / 3, Math.max(0, total - 65)))) {
                    if (index >= total) {
                        continue;
                    }
                    St4Compressor.Result packed = packRewinding(input, unit, 64, index);
                    assertEquals(index, packed.rewindIndex());
                    String shape = "unit " + unit + ", " + input.length + " bytes, -r" + index;
                    St4Decompressor.Decoded decoded = St4Decompressor.decode(
                            packed.control(), packed.literal(), packed.byteOffsets(),
                            packed.wordOffsets(), unit, packed.paddedSize(), 64, index * unit);
                    assertArrayEquals(padded(input, unit), decoded.output(), shape);
                    assertEquals(-1, decoded.repeatIndex(), "a rewind stream ends plainly");

                    // The container names the rewind point, in bytes like O.
                    St4Format.Container read = St4Format.read(St4.container(packed));
                    assertEquals(index * unit, read.rewind(), shape);
                }
            }
        }
    }

    @Test
    void theSeamOfARewindStreamIsAbsorbed() {
        // The loop's parse assumes the stream's initial offset at its start, so
        // its first match may be a one-unit rep of offset one - which, after an
        // intro that left another offset behind, the format cannot write. The
        // compressor turns that unit into a literal; the stream must still
        // decode, and still hold to its rewind point.
        byte[] input = "xyzxyzxyzxyzabb".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        St4Compressor.Result packed = packRewinding(input, 1, 512, 12);
        assertArrayEquals(input, St4Decompressor.decode(packed.control(), packed.literal(),
                packed.byteOffsets(), packed.wordOffsets(), 1, input.length, 512, 12).output());
        // And two literal runs meeting at the seam become one, which the
        // format demands: 256 distinct bytes hold no match at all, so an
        // intro and a loop cut from them are one literal run each - and one
        // together.
        byte[] distinct = new byte[256];
        for (int i = 0; i < distinct.length; i++) {
            distinct[i] = (byte) i;
        }
        St4Compressor.Result merged = packRewinding(distinct, 1, 512, 100);
        assertArrayEquals(distinct, St4Decompressor.decode(merged.control(), merged.literal(),
                merged.byteOffsets(), merged.wordOffsets(), 1, distinct.length, 512, 100)
                .output());
        assertEquals(1, merged.operations(), "one literal run, not two");
    }

    @Test
    void theRewindCheckRefusesALoopThatReachesBeforeItsPoint() {
        // A stream packed without the rewind constraint lets its second half
        // match its first; replayed from the halfway point it would read what
        // the ring held instead, so the reference must refuse it there.
        byte[] half = new byte[1000];
        new Random(5).nextBytes(half);
        byte[] input = new byte[2000];
        System.arraycopy(half, 0, input, 0, 1000);
        System.arraycopy(half, 0, input, 1000, 1000);
        St4Compressor.Result plain = pack(input, 1);
        assertThrows(IllegalStateException.class, () -> St4Decompressor.decode(
                plain.control(), plain.literal(), plain.byteOffsets(), plain.wordOffsets(),
                1, 2000, St4Format.maxOffsetUnits(1), 1000));
        // The same data packed for rewind at 1000 passes the same check.
        St4Compressor.Result rewound = packRewinding(input, 1, 512, 1000);
        assertArrayEquals(input, St4Decompressor.decode(rewound.control(), rewound.literal(),
                rewound.byteOffsets(), rewound.wordOffsets(), 1, 2000, 512, 1000).output());
    }

    @Test
    void theDecoderReportsHowAStreamEnds() {
        byte[] input = "the tail of this sentence loops. and loops. ".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);
        St4Compressor.Result plain = pack(input, 1);
        assertEquals(-1, St4Decompressor.decode(plain.control(), plain.literal(),
                plain.byteOffsets(), plain.wordOffsets(), 1, plain.paddedSize(),
                St4Format.maxOffsetUnits(1)).repeatIndex());
        St4Compressor.Result looped = packRepeating(input, 1, 12);
        assertEquals(12, St4Decompressor.decode(looped.control(), looped.literal(),
                looped.byteOffsets(), looped.wordOffsets(), 1, looped.paddedSize(),
                St4Format.maxOffsetUnits(1)).repeatIndex());
    }

    @Test
    void theLimitCheckingDecoderRefusesAWideRepeat() {
        // The loop distance is a match offset like any other: one that reaches
        // further back than the ring keeps must fail loudly, exactly as a wide
        // offset does. Looping 400 units from unit 100 is 300 units back.
        byte[] input = new byte[400];
        new Random(3).nextBytes(input);
        int[] units = Units.split(input, 1);
        St4Compressor.Result wide = St4Compressor.compress(
                St4Optimizer.optimize(units, 1, 64, false), units, 1,
                St4Format.MAX_OP, 100);
        assertThrows(IllegalStateException.class, () -> St4Decompressor.decompress(
                wide.control(), wide.literal(), wide.byteOffsets(), wide.wordOffsets(),
                1, 800, 64));
    }

    @Test
    void copiesFromTheLiteralStreamRoundTripAtSmallWindows() {
        // A parse whose matches keep within a small window and whose copies
        // reach the literal stream beyond it must decode at that window - and
        // the compressor's bits are the parse's, unless a copy had to give a
        // unit back to stay behind the read pointer.
        for (int unit : new int[] {1, 2, 4}) {
            for (byte[] input : inputs()) {
                int[] units = Units.split(input, unit);
                for (int window : new int[] {4, 16, 64}) {
                    St4Block parse = St4LiteralCopySearch.optimize(units, unit, window,
                            St4Format.MAX_OP, 0, 1);
                    St4Compressor.Result packed = St4Compressor.compress(parse, units, unit,
                            St4Format.MAX_OP, -1, window);
                    String shape = "unit " + unit + ", " + input.length + " bytes, window " + window;
                    assertArrayEquals(padded(input, unit), St4Decompressor.decompress(
                            packed.control(), packed.literal(), packed.byteOffsets(),
                            packed.wordOffsets(), unit, packed.paddedSize(), window), shape);
                    assertEquals(window, St4Format.read(St4.container(packed)).window(), shape);
                    // Never dearer than the same window without copies.
                    St4Compressor.Result plain = pack(input, unit, window, St4Format.MAX_OP);
                    assertTrue(packed.bits() <= plain.bits(), shape + ": "
                            + packed.bits() + " bits with copies, " + plain.bits() + " without");
                }
            }
        }
    }

    @Test
    void theOracleCostsExactlyWhatTheCompressorWrites() {
        // The oracle claims to know the format's every cost, reps of copies and
        // the offset a copy leaves behind included; the compressor is the
        // authority. On inputs small enough to exhaust, every oracle parse
        // must write to its own bit count and decode.
        var random = new Random(17);
        for (int trial = 0; trial < 60; trial++) {
            int count = 6 + random.nextInt(6);
            int[] units = new int[count];
            for (int i = 0; i < count; i++) {
                units[i] = random.nextInt(3);          // a small alphabet: matches abound
            }
            byte[] input = new byte[count];
            for (int i = 0; i < count; i++) {
                input[i] = (byte) units[i];
            }
            int window = 2 + random.nextInt(3);
            St4Block parse = St4LiteralCopyOracle.optimize(units, 1, window);
            St4Compressor.Result packed = St4Compressor.compress(parse, units, 1,
                    St4Format.MAX_OP, -1, window);
            String shape = "trial " + trial + ", window " + window + ", " + java.util.Arrays.toString(units);
            assertEquals(parse.bits(), packed.bits(), shape);
            assertArrayEquals(input, St4Decompressor.decompress(packed.control(),
                    packed.literal(), packed.byteOffsets(), packed.wordOffsets(), 1, count,
                    window), shape);
        }
    }

    @Test
    void theOpeningPassesAreMeasuredAgainstTheOracle() {
        // The search's opening passes - what st4 -c alone writes - choose a
        // dictionary first and are exact for it; the oracle tries everything.
        // On inputs small enough to exhaust, the passes can only be dearer,
        // and how much dearer is a number.
        var random = new Random(23);
        int oracleBits = 0;
        int heuristicBits = 0;
        int worst = 0;
        for (int trial = 0; trial < 60; trial++) {
            int count = 6 + random.nextInt(6);
            int[] units = new int[count];
            for (int i = 0; i < count; i++) {
                units[i] = random.nextInt(3);
            }
            int window = 2 + random.nextInt(3);
            int oracle = St4LiteralCopyOracle.optimize(units, 1, window).bits();
            St4Block parse = St4LiteralCopySearch.optimize(units, 1, window, St4Format.MAX_OP,
                    0, trial);
            int heuristic = St4Compressor.compress(parse, units, 1, St4Format.MAX_OP, -1, window)
                    .bits();
            assertTrue(oracle <= heuristic, "trial " + trial + ": the oracle is the optimum");
            oracleBits += oracle;
            heuristicBits += heuristic;
            worst = Math.max(worst, heuristic - oracle);
        }
        System.out.printf("literal copies: opening passes %d bits, oracle %d bits, +%.1f%%, worst +%d bits%n",
                heuristicBits, oracleBits, 100.0 * (heuristicBits - oracleBits) / oracleBits, worst);
        assertTrue(heuristicBits <= oracleBits * 1.5, "within reach of the optimum");
    }

    /**
     * ZX1's packed size for each of {@link #inputs()}, recorded from jx1 in
     * odipar/ST1 at commit 132aef0. The inputs are deterministic.
     */
    @Test
    void theSearchStartsWhereTheHeuristicEndsAndIsMeasuredAgainstTheOracle() {
        // The search anneals over dictionaries from its opening passes,
        // scoring each by what the compressor writes, so it can only improve
        // on them - and on inputs small enough to exhaust, how often it
        // reaches the optimum is a number, as is how far it lands otherwise.
        var random = new Random(29);
        int oracleBits = 0;
        int heuristicBits = 0;
        int searchBits = 0;
        int optimal = 0;
        for (int trial = 0; trial < 60; trial++) {
            int count = 6 + random.nextInt(6);
            int[] units = new int[count];
            byte[] input = new byte[count];
            for (int i = 0; i < count; i++) {
                units[i] = random.nextInt(3);
                input[i] = (byte) units[i];
            }
            int window = 2 + random.nextInt(3);
            int oracle = St4LiteralCopyOracle.optimize(units, 1, window).bits();
            int heuristic = St4Compressor.compress(
                    St4LiteralCopySearch.optimize(units, 1, window, St4Format.MAX_OP, 0, trial),
                    units, 1, St4Format.MAX_OP, -1, window).bits();
            St4Block parse = St4LiteralCopySearch.optimize(units, 1, window, St4Format.MAX_OP,
                    200, trial);
            St4Compressor.Result packed = St4Compressor.compress(parse, units, 1,
                    St4Format.MAX_OP, -1, window);
            String shape = "trial " + trial + ", window " + window + ", "
                    + java.util.Arrays.toString(units);
            assertTrue(oracle <= packed.bits(), shape + ": the oracle is the optimum");
            assertArrayEquals(input, St4Decompressor.decompress(packed.control(),
                    packed.literal(), packed.byteOffsets(), packed.wordOffsets(), 1, count,
                    window), shape);
            oracleBits += oracle;
            heuristicBits += heuristic;
            searchBits += packed.bits();
            optimal += packed.bits() == oracle ? 1 : 0;
        }
        System.out.printf("literal copies: search %d bits, heuristic %d bits, oracle %d bits; "
                + "the search is +%.1f%% over the optimum and optimal on %d of 60%n",
                searchBits, heuristicBits, oracleBits,
                100.0 * (searchBits - oracleBits) / oracleBits, optimal);
        assertTrue(searchBits <= heuristicBits, "the search starts where the heuristic ends");
        assertTrue(optimal >= 45, "the search reaches the optimum on most small inputs");
    }

    @Test
    void theSearchIsReproducibleAndItsParsesDecode() {
        // A seeded search is a function of its input: the same steps give the
        // same parse. Every parse it scores decodes, whatever the corpus or
        // window, and its best is never dearer than its opening passes'.
        for (int unit : new int[] {1, 2, 4}) {
            for (byte[] input : inputs()) {
                int[] units = Units.split(input, unit);
                for (int window : new int[] {4, 16, 64}) {
                    String shape = "unit " + unit + ", " + input.length + " bytes, window " + window;
                    St4Block parse = St4LiteralCopySearch.optimize(units, unit, window,
                            St4Format.MAX_OP, 40, 5);
                    St4Compressor.Result packed = St4Compressor.compress(parse, units, unit,
                            St4Format.MAX_OP, -1, window);
                    assertArrayEquals(padded(input, unit), St4Decompressor.decompress(
                            packed.control(), packed.literal(), packed.byteOffsets(),
                            packed.wordOffsets(), unit, packed.paddedSize(), window), shape);
                    St4Compressor.Result again = St4Compressor.compress(
                            St4LiteralCopySearch.optimize(units, unit, window, St4Format.MAX_OP,
                                    40, 5), units, unit, St4Format.MAX_OP, -1, window);
                    assertArrayEquals(packed.control(), again.control(), shape);
                    assertArrayEquals(packed.literal(), again.literal(), shape);
                    St4Compressor.Result passes = St4Compressor.compress(
                            St4LiteralCopySearch.optimize(units, unit, window, St4Format.MAX_OP,
                                    0, 5), units, unit, St4Format.MAX_OP, -1, window);
                    assertTrue(packed.bits() <= passes.bits(), shape + ": "
                            + packed.bits() + " bits searched, " + passes.bits() + " from the passes");
                }
            }
        }
    }

    @Test
    void theSearchParserRestartsFromItsCheckpointsExactly() {
        // A parse restarted from a checkpoint before the first changed unit
        // must be the parse from scratch, block for block - accepted or
        // not, and whatever the parses in between did to the arrays. At a
        // 512-unit window a single parse makes more nodes than a small
        // input's worth, which is where the pool's compaction has to know
        // what a full parse takes rather than go round again.
        var random = new Random(31);
        int count = 6000;
        int[] units = new int[count];
        for (int i = 0; i < count; i++) {
            units[i] = i > 40 && random.nextInt(3) > 0 ? units[i - 1 - random.nextInt(40)]
                    : random.nextInt(6);
        }
        for (int window : new int[] {16, 512}) {
            var parser = new St4LiteralCopySearch.Parser(units, 1, window);
            boolean[] dictionary = new boolean[count];
            for (int i = 0; i < count; i++) {
                dictionary[i] = random.nextInt(4) == 0;
            }
            for (int trial = 0; trial < 40; trial++) {
                int at = random.nextInt(count);
                int size = 1 + random.nextInt(24);
                boolean value = random.nextBoolean();
                Arrays.fill(dictionary, at, Math.min(count, at + size), value);
                St4Block restarted = parser.parse(dictionary);
                St4Block fresh = new St4LiteralCopySearch.Parser(units, 1, window).parse(dictionary);
                List<St4Block> a = St4LiteralCopySearch.blocks(restarted);
                List<St4Block> b = St4LiteralCopySearch.blocks(fresh);
                assertEquals(b.size(), a.size(), "trial " + trial + ": block count");
                for (int i = 0; i < a.size(); i++) {
                    assertEquals(b.get(i).index(), a.get(i).index(), "trial " + trial + " block " + i);
                    assertEquals(b.get(i).offset(), a.get(i).offset(), "trial " + trial + " block " + i);
                    assertEquals(b.get(i).bits(), a.get(i).bits(), "trial " + trial + " block " + i);
                }
                if (random.nextBoolean()) {
                    parser.accept();
                }
            }
        }
    }

    @Test
    void theUnpackerPlaysALoopAsManyTimesAsAsked() {
        // dst4 -rN plays a looping stream's loop N times: the pass, then N-1
        // repeats of its loop section - whether the stream loops by itself,
        // where the decoder fills the length, or by rewind, where the pass's
        // loop section is replayed. A stream that does not loop has one pass.
        for (int unit : new int[] {1, 2, 4}) {
            for (byte[] input : inputs()) {
                byte[] pass = padded(input, unit);
                int total = pass.length / unit;
                for (int index : new java.util.TreeSet<>(List.of(0, total / 3, total - 1))) {
                    for (boolean rewind : new boolean[] {false, true}) {
                        int window = rewind ? Math.max(1, (total - index) / 2) : total;
                        St4Compressor.Result packed = rewind
                                ? packRewinding(input, unit, window, index)
                                : packRepeating(input, unit, index);
                        St4Format.Container container = St4Format.read(St4.container(packed));
                        St4Decompressor.Decoded decoded = St4Decompressor.decode(
                                container.control(), container.literal(), container.byteOffsets(),
                                container.wordOffsets(), unit, container.size(),
                                container.window(), container.rewind());
                        String shape = "unit " + unit + ", " + input.length + " bytes, -r"
                                + index + (rewind ? " by rewind" : "");
                        assertArrayEquals(pass, decoded.output(), shape);
                        for (int times : new int[] {1, 2, 4}) {
                            int loop = pass.length - index * unit;
                            byte[] expected = Arrays.copyOf(pass, pass.length + (times - 1) * loop);
                            for (int at = pass.length; at < expected.length; at++) {
                                expected[at] = expected[at - loop];
                            }
                            assertArrayEquals(expected, Dst4.played(container, decoded, times),
                                    shape + " played " + times + " times");
                        }
                    }
                }
                St4Format.Container plain = St4Format.read(St4.container(pack(input, unit)));
                St4Decompressor.Decoded once = St4Decompressor.decode(plain.control(),
                        plain.literal(), plain.byteOffsets(), plain.wordOffsets(), unit,
                        plain.size(), plain.window(), plain.rewind());
                assertArrayEquals(pass, Dst4.played(plain, once, 1));
                assertThrows(IllegalArgumentException.class, () -> Dst4.played(plain, once, 2));
            }
        }
    }

    private static final int[] ZX1_SIZES = {4, 6, 1006, 6, 19, 383, 26};

    @Test
    void unitOneStaysWithinAFewPercentOfZx1() {
        // k=1 is ZX1's parse with everything moved into its own stream. Splitting
        // by offset width costs two control bits per new-offset match and gives
        // back a byte offset that reaches 512 units instead of 128, so the sizes
        // no longer match exactly - but they must stay close, and the padding
        // between four streams is itself a few bytes.
        List<byte[]> inputs = inputs();
        for (int i = 0; i < inputs.size(); i++) {
            St4Compressor.Result packed = pack(inputs.get(i), 1);
            int zx1 = ZX1_SIZES[i];
            assertTrue(packed.packedSize() <= zx1 + 8 + zx1 / 20,
                    "ST4 k=1 " + packed.packedSize() + " vs jx1's " + zx1);
        }
    }

    @Test
    void theHeaderIsTwentyEightBytesAndSaysOnlyWhatCannotBeDerived() {
        byte[] input = "a header should hold nothing that follows from the rest".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);
        for (int unit : new int[] {1, 2, 4}) {
            St4Compressor.Result packed = pack(input, unit);
            byte[] file = St4.container(packed);

            assertEquals(28, St4Format.HEADER_SIZE);
            // A stream that ends has no rewind point: nothing for a caller to do.
            assertEquals(St4Format.NO_REWIND, longAt(file, St4Format.OFFSET_REWIND));
            // The window a decoder needs to tell a match from a copy: here the
            // widest, since the pack had no limit.
            assertEquals(St4Format.maxOffsetUnits(unit), longAt(file, St4Format.OFFSET_WINDOW));
            // One long carries magic, version and k, so a decoder built for one
            // unit size checks an asset against itself with a single cmp.l.
            assertEquals(St4Format.signature(unit), longAt(file, St4Format.OFFSET_SIGNATURE));
            assertEquals(packed.paddedSize(), longAt(file, St4Format.OFFSET_SIZE));

            int literalAt = longAt(file, St4Format.OFFSET_LITERAL);
            int byteAt = longAt(file, St4Format.OFFSET_BYTE_OFFSETS);
            int wordAt = longAt(file, St4Format.OFFSET_WORD_OFFSETS);
            assertEquals(0, literalAt % 4, "stream B starts long-aligned");
            assertEquals(0, byteAt % 4, "stream C starts long-aligned");
            assertEquals(0, wordAt % 4, "stream D starts long-aligned");
            // The layout is A, B, C, D: the bits, the literal payload right
            // after them, the byte offsets, the word offsets to the end.
            assertTrue(literalAt <= byteAt && byteAt <= wordAt, "streams run A, B, C, D");
            assertEquals(St4Format.HEADER_SIZE + align(packed.control().length), literalAt,
                    "stream B follows stream A");
            assertEquals(file.length, wordAt + packed.wordOffsets().length,
                    "stream D runs to the end of the file");

            // Stream A needs no field: it begins where the header ends. Every
            // other start is one adda.l from the asset's own address.
            assertArrayEquals(packed.control(), Arrays.copyOfRange(file,
                    St4Format.HEADER_SIZE, St4Format.HEADER_SIZE + packed.control().length));
            assertArrayEquals(packed.literal(),
                    Arrays.copyOfRange(file, literalAt, literalAt + packed.literal().length));
            assertArrayEquals(packed.byteOffsets(),
                    Arrays.copyOfRange(file, byteAt, byteAt + packed.byteOffsets().length));
            assertArrayEquals(packed.wordOffsets(),
                    Arrays.copyOfRange(file, wordAt, wordAt + packed.wordOffsets().length));

            // A derived length is the real one, or up to three bytes of padding
            // longer - and nothing reads the padding.
            St4Format.Container read = St4Format.read(file);
            assertPadded(packed.control(), read.control());
            assertPadded(packed.literal(), read.literal());
            assertPadded(packed.byteOffsets(), read.byteOffsets());
            assertPadded(packed.wordOffsets(), read.wordOffsets());
        }
    }

    private static int align(int length) {
        return (length + 3) & ~3;
    }

    private static void assertPadded(byte[] written, byte[] derived) {
        assertTrue(derived.length >= written.length && derived.length - written.length < 4,
                "derived " + derived.length + " from a written " + written.length);
        assertArrayEquals(written, Arrays.copyOf(derived, written.length));
    }

    @Test
    void aContainerReadsBackAsTheStreamsThatWentIn() {
        // What dst4 does: header in, four streams out, decoded without being
        // told anything the file does not already say.
        for (int unit : new int[] {1, 2, 4}) {
            for (byte[] input : inputs()) {
                byte[] file = St4.container(pack(input, unit));
                St4Format.Container read = St4Format.read(file);
                assertEquals(unit, read.unit());
                assertArrayEquals(padded(input, unit), St4Decompressor.decompress(
                        read.control(), read.literal(), read.byteOffsets(),
                        read.wordOffsets(), read.unit(), read.size()));
            }
        }
    }

    @Test
    void aBrokenContainerSaysWhatIsWrongWithIt() {
        byte[] good = St4.container(pack("a container has to be checked".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII), 2));

        assertThrows(IllegalArgumentException.class,
                () -> St4Format.read(Arrays.copyOf(good, 8)), "a truncated header");

        byte[] wrongMagic = good.clone();
        wrongMagic[0] ^= 0xFF;
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(wrongMagic));

        byte[] wrongVersion = good.clone();
        wrongVersion[St4Format.OFFSET_SIGNATURE + 2] = (byte) (St4Format.VERSION + 1);
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(wrongVersion));

        byte[] wrongUnit = good.clone();
        wrongUnit[St4Format.OFFSET_SIGNATURE + 3] = 3;
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(wrongUnit));

        byte[] strayStream = good.clone();
        strayStream[St4Format.OFFSET_BYTE_OFFSETS + 1] = 0x7F;
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(strayStream));

        byte[] outOfOrder = good.clone();          // C before B ends
        outOfOrder[St4Format.OFFSET_BYTE_OFFSETS + 3] = 0;
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(outOfOrder));

        byte[] misaligned = good.clone();
        misaligned[St4Format.OFFSET_LITERAL + 3] += 1;
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(misaligned));

        byte[] strayRewind = good.clone();          // a rewind point past the output
        strayRewind[St4Format.OFFSET_REWIND + 2] = 0x7F;
        strayRewind[St4Format.OFFSET_REWIND + 3] = 0;
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(strayRewind));

        byte[] noWindow = good.clone();             // a window no offset could keep to
        noWindow[St4Format.OFFSET_WINDOW + 2] = 0;
        noWindow[St4Format.OFFSET_WINDOW + 3] = 0;
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(noWindow));
    }

    private static int longAt(byte[] file, int at) {
        return (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
                | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
    }
}
