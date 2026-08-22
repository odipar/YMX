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
        // Decoding through an offset limit is how tests hold a -mN stream to
        // its ring, so a stream that reaches further must fail loudly rather
        // than pretend - random data repeated once guarantees one far match.
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

    /**
     * ZX1's packed size for each of {@link #inputs()}, recorded from jx1 in
     * odipar/ST1 at commit 132aef0. The inputs are deterministic, so these can
     * only drift if the ZX1 reference itself would - which it does not.
     */
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
    void theHeaderIsTwentyBytesAndSaysOnlyWhatCannotBeDerived() {
        byte[] input = "a header should hold nothing that follows from the rest".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);
        for (int unit : new int[] {1, 2, 4}) {
            St4Compressor.Result packed = pack(input, unit);
            byte[] file = St4.container(packed);

            assertEquals(20, St4Format.HEADER_SIZE);
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

        byte[] outOfOrder = good.clone();          // C before B
        outOfOrder[St4Format.OFFSET_BYTE_OFFSETS + 3] = 0;
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(outOfOrder));

        byte[] misaligned = good.clone();
        misaligned[St4Format.OFFSET_LITERAL + 3] += 1;
        assertThrows(IllegalArgumentException.class, () -> St4Format.read(misaligned));
    }

    private static int longAt(byte[] file, int at) {
        return (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
                | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
    }
}
