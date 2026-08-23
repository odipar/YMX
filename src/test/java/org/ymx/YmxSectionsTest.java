package org.ymx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.st4.St4Decompressor;
import org.st4.St4Format;
import org.junit.jupiter.api.Test;
import org.ym6.Ym6Reader;
import org.ym6.Ym6TestData;
import org.ym6.YmEffects;

/** One section per stream, covering the whole tune, and what the player is
 * promised: a tune that starts over reaches the end of that section and the
 * player opens it again from the top. */
final class YmxSectionsTest {

    private static final int FRAMES = 900;

    private static Tune song() {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        return YmEffects.tune(Ym6Reader.read(
                Ym6TestData.file(registers, FRAMES, true, "YM6!", 50, 0, 0)));
    }

    private static int word(byte[] file, int at) {
        return ((file[at] & 0xFF) << 8) | (file[at + 1] & 0xFF);
    }

    /** One section's table entry, unsigned: bit 31 marks a stored section. */
    private static long entry(byte[] file, int register) {
        return longAt(file, YmxFormat.OFFSET_SECTION_TABLE + 4 * register)
                & 0xFFFF_FFFFL;
    }

    /** A section's values: a stored section is already them. */
    private static byte[] values(byte[] file, int register, int size, int offsetLimit) {
        long e = entry(file, register);
        int from = (int) YmxFormat.sectionOffset(e);
        return YmxFormat.isStored(e)
                ? Arrays.copyOfRange(file, from, from + size)
                : unpack(file, from, size, offsetLimit);
    }

    private static int longAt(byte[] file, int at) {
        return (word(file, at) << 16) | word(file, at + 2);
    }

    /** Unpacks one embedded ST4 container, holding it to an offset limit. */
    private static byte[] unpack(byte[] file, int from, int packedSize, int offsetLimit) {
        St4Format.Container section = St4Format.read(
                Arrays.copyOfRange(file, from, from + packedSize));
        return St4Decompressor.decompress(section.control(), section.literal(),
                section.byteOffsets(), section.wordOffsets(), section.unit(),
                section.size(), offsetLimit);
    }

    private static int packedSize(YmxEncoder.Result result, int register) {
        return result.streams().stream().filter(s -> s.register() == register)
                .findFirst().orElseThrow().packedSize();
    }

    @Test
    void eachSectionUnpacksToItsWholeStream() {
        Tune source = song();
        YmxEncoder.Result result = YmxEncoder.encode(source, 960, 24, true, false);
        byte[] file = result.file();

        assertEquals(YmxFormat.FLAG_LOOPS,
                word(file, YmxFormat.OFFSET_FLAGS) & YmxFormat.FLAG_LOOPS);
        assertEquals(FRAMES, longAt(file, YmxFormat.OFFSET_FRAMES),
                "the tune is compiled once, so the file is as long as its source");

        byte[][] expected = YmxEncoderTest.expectedVectors(source);
        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            assertArrayEquals(expected[stream],
                    values(file, stream, packedSize(result, stream), Integer.MAX_VALUE),
                    "stream " + stream);
        }
    }

    @Test
    void startingOverIsAFlagAndNothingElse() {
        Tune source = song();
        YmxEncoder.Result over = YmxEncoder.encode(source, 960, 24, true, false);
        YmxEncoder.Result once = YmxEncoder.encode(source, 960, 24, false, false);

        assertEquals(YmxFormat.FLAG_LOOPS,
                word(over.file(), YmxFormat.OFFSET_FLAGS) & YmxFormat.FLAG_LOOPS);
        assertEquals(0,
                word(once.file(), YmxFormat.OFFSET_FLAGS) & YmxFormat.FLAG_LOOPS);
        assertEquals(once.packedSize(), over.packedSize(),
                "the same frames either way, so the same bytes");
        assertEquals(YmxFormat.STREAMS, over.streams().size(),
                "one section per stream, however the tune ends");
    }

    @Test
    void everySectionStaysInsideTheRingAndTheWordCounters() {
        int ring = 240;
        YmxEncoder.Result result = YmxEncoder.encode(song(), ring, 24, true, false);
        byte[] file = result.file();
        assertTrue(result.longestOp() <= 65535);

        for (YmxEncoder.Stream stream : result.streams()) {
            // An offset within the ring is exactly what ring-safety means;
            // the limit-checking reference throws on anything further. A
            // stored section makes no reference at all.
            assertEquals(stream.frames(),
                    values(file, stream.register(), stream.packedSize(), ring).length,
                    "stream " + stream.register() + " needs more than a "
                            + ring + "-byte ring");
        }
    }

    @Test
    void rejectsAShapeThePlayerCannotDecode() {
        Tune source = song();
        assertThrows(IllegalArgumentException.class,
                () -> YmxEncoder.encode(source, 960, 13, true, false),
                "a chunk of 13 is below the streams this tune decodes");
    }
}
