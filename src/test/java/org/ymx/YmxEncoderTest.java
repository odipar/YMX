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

final class YmxEncoderTest {

    private static final int FRAMES = 1500;

    private static Ym6Reader.Song song(boolean interleaved) {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        return Ym6Reader.read(Ym6TestData.file(registers, FRAMES, interleaved));
    }

    /** The same dump past the YM front end: what the encoder takes. */
    private static Tune tune(boolean interleaved) {
        return YmEffects.tune(song(interleaved));
    }

    private static int word(byte[] file, int at) {
        return ((file[at] & 0xFF) << 8) | (file[at + 1] & 0xFF);
    }

    private static int longAt(byte[] file, int at) {
        return (word(file, at) << 16) | word(file, at + 2);
    }

    @Test
    void headerDescribesTheStreams() {
        YmxEncoder.Result result = YmxEncoder.encode(tune(true), 960, 24, false);
        byte[] file = result.file();

        assertEquals(YmxFormat.MAGIC, longAt(file, YmxFormat.OFFSET_MAGIC));
        assertEquals(YmxFormat.VERSION, word(file, YmxFormat.OFFSET_VERSION));
        assertEquals(0, word(file, YmxFormat.OFFSET_FLAGS) & YmxFormat.FLAG_LOOPS,
                "a play-once tune does not loop");
        assertEquals(0, word(file, YmxFormat.OFFSET_FLAGS) & YmxFormat.flagChannel(2),
                "a YM frame starts at most two effects, so no YM tune ever asks"
                        + " for the third timer channel - and the host keeps its timer");
        assertEquals(FRAMES, longAt(file, YmxFormat.OFFSET_LOOP_FRAME),
                "a play-once tune loops at its end");
        assertEquals(FRAMES, longAt(file, YmxFormat.OFFSET_FRAMES));
        assertEquals(50, word(file, YmxFormat.OFFSET_PLAYER_HZ));
        assertEquals(YmxFormat.STREAMS, word(file, YmxFormat.OFFSET_STREAM_COUNT));
        assertEquals(960, word(file, YmxFormat.OFFSET_RING_SIZE));
        assertEquals(24, word(file, YmxFormat.OFFSET_CHUNK));

        // The table is in register order, long-aligned per section (each is a
        // complete ST4 container with alignment promises of its own), and
        // covers the whole file up to the final alignment pad.
        int expected = YmxFormat.HEADER_SIZE;
        for (int register = 0; register < YmxFormat.STREAMS; register++) {
            expected += (-expected) & 3;
            assertEquals(expected, longAt(file, YmxFormat.OFFSET_INTRO_TABLE + 4 * register),
                    "offset of section " + register);
            expected += result.streams().get(register).packedSize();
        }
        assertTrue(file.length - expected < 4, "nothing after the last section but padding");
    }

    /** Every vector as the encoder should build them: registers
     * source-mapped through the split rotation with R7 carrying the baked
     * mixer force, then the compiled script streams with their unread
     * bytes repeating. The same assembly the encoder performs - which is
     * the point: the file must decode back to exactly this. */
    static byte[][] expectedVectors(Tune source, int loopFrame, int unit) {
        EffectScript.Result script = EffectScript.compile(source, loopFrame, unit);
        byte[][] vectors = new byte[YmxFormat.STREAMS][];
        for (int register = 0; register < YmxFormat.REGISTER_STREAMS; register++) {
            byte[] masked = Ym2149.mask(register, source.registers()[register]);
            byte[] played = new byte[script.frames()];
            for (int p = 0; p < played.length; p++) {
                played[p] = masked[script.source()[p]];
            }
            if (register == 7) {
                for (int p = 0; p < played.length; p++) {
                    played[p] |= script.r7force()[p];
                }
            }
            vectors[register] = played;
        }
        vectors[YmxFormat.STREAM_M] = script.m();
        vectors[YmxFormat.STREAM_X] = script.x();
        vectors[YmxFormat.STREAM_T] = script.timers();
        for (int c = 0; c < YmxFormat.CHANNELS; c++) {
            int acts = EffectScript.M_CHANNEL_0 << c;
            vectors[YmxFormat.streamAction(c)] =
                    YmxEncoder.carry(script.actions()[c], script.m(), acts, null);
            vectors[YmxFormat.streamAction(c) + 1] = YmxEncoder.carry(
                    script.counts()[c], script.m(), acts, script.actions()[c]);
        }
        return vectors;
    }

    static byte[] expectedVector(Tune source, int index) {
        return expectedVectors(source, -1, 1)[index];
    }

    @Test
    void everyStreamUnpacksToItsVector() {
        Tune source = tune(true);
        YmxEncoder.Result result = YmxEncoder.encode(source, 960, 24, false);
        byte[] file = result.file();

        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            int from = longAt(file, YmxFormat.OFFSET_INTRO_TABLE + 4 * stream);
            byte[] unpacked = unpack(file, from,
                    result.streams().get(stream).packedSize(), Integer.MAX_VALUE);
            assertArrayEquals(expectedVector(source, stream), unpacked,
                    "stream " + stream + " does not decode to its vector");
        }
    }

    @Test
    void interleavedAndPerFrameFilesPackIdentically() {
        assertArrayEquals(YmxEncoder.encode(tune(true), 960, 24, false).file(),
                YmxEncoder.encode(tune(false), 960, 24, false).file());
    }

    @Test
    void everyStreamSurvivesItsOwnRing() {
        // -mN is what makes a stream safe for an N-byte ring: decoding it
        // through exactly that ring must never need a byte that has left it.
        // A too-far offset does not fail loudly - it reads whatever the ring
        // has wrapped onto - so the output comparison is the check.
        Tune source = tune(true);
        for (int ring : new int[] {48, 240, 960}) {
            YmxEncoder.Result result = YmxEncoder.encode(source, ring, 24, false);
            byte[] file = result.file();
            for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
                int from = longAt(file, YmxFormat.OFFSET_INTRO_TABLE + 4 * stream);
                assertArrayEquals(expectedVector(source, stream),
                        unpack(file, from, result.streams().get(stream).packedSize(), ring),
                        "stream " + stream + " needs more than a " + ring + "-byte ring");
            }
        }
    }

    @Test
    void everySectionIsAStandardUnitOneContainer() {
        // The player opens each section with ST4's own eight-instruction
        // sequence, so each must be a complete k=1 container whose recorded
        // size is the section's frame count exactly (k=1 pads nothing). The
        // player's C-sized-call shape itself is exercised by the emulation
        // rig, through the real 68000 decoder.
        Tune source = tune(true);
        YmxEncoder.Result result = YmxEncoder.encode(source, 240, 24, false);
        byte[] file = result.file();

        for (int register = 0; register < YmxFormat.STREAMS; register++) {
            int from = longAt(file, YmxFormat.OFFSET_INTRO_TABLE + 4 * register);
            assertEquals(0, from % 4, "section " + register + " starts long-aligned");
            St4Format.Container section = St4Format.read(Arrays.copyOfRange(
                    file, from, from + result.streams().get(register).packedSize()));
            assertEquals(1, section.unit(), "section " + register + " is not k=1");
            assertEquals(FRAMES, section.size(), "section " + register + " frame count");
        }
    }

    @Test
    void everyOperationFitsAWordCounter() {
        assertTrue(YmxEncoder.encode(tune(true), 960, 24, false).longestOp() <= 65535);
    }

    @Test
    void rejectsShapesThePlayerCannotRun() {
        Tune source = tune(true);
        // Fewer values per call than registers: the round-robin cannot fit.
        assertThrows(IllegalArgumentException.class, () -> YmxEncoder.encode(source, 960, 13, false));
        // Ring smaller than two chunks: the group being written would land on
        // the group being read.
        assertThrows(IllegalArgumentException.class, () -> YmxEncoder.encode(source, 24, 24, false));
        // ST1_wrap needs the chunk to divide the ring.
        assertThrows(IllegalArgumentException.class, () -> YmxEncoder.encode(source, 1000, 24, false));
        // The burst reads register k's ring through an assembled-in k*N
        // displacement: 13*N must fit a signed word, so N stops at 2520.
        assertThrows(IllegalArgumentException.class, () -> YmxEncoder.encode(source, 2544, 24, false));
    }

    @Test
    void widerUnitsRoundTripAndAreRejectedWhenTheyCannot() {
        // FRAMES is even, so k=2 works end to end; each section must carry
        // k=2 in its signature, which is what the player checks its build
        // against. A loop frame that is not a whole number of units cannot be
        // packed at all - a padded section would decode one extra value into
        // the ring, and it would be played.
        Tune source = tune(true);
        YmxEncoder.Result result = YmxEncoder.encode(source, 960, 24, -1, false, 2);
        byte[] file = result.file();
        byte[][] expected = expectedVectors(source, -1, 2);
        for (int register = 0; register < YmxFormat.STREAMS; register++) {
            int from = longAt(file, YmxFormat.OFFSET_INTRO_TABLE + 4 * register);
            St4Format.Container section = St4Format.read(Arrays.copyOfRange(
                    file, from, from + result.streams().get(register).packedSize()));
            assertEquals(2, section.unit(), "section " + register);
            assertArrayEquals(expected[register],
                    unpack(file, from, result.streams().get(register).packedSize(), 480),
                    "section " + register + " at unit 2");
        }
        assertThrows(IllegalArgumentException.class,
                () -> YmxEncoder.encode(source, 960, 24, 397, false, 2),
                "an odd loop frame cannot be a whole number of 2-byte units");
        assertThrows(IllegalArgumentException.class,
                () -> YmxEncoder.encode(source, 960, 30, -1, false, 4),
                "a chunk of 30 is not a whole number of 4-byte units");
    }

    @Test
    void drumsTravelWithEndMarkers() {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        Tune source = YmEffects.tune(Ym6Reader.read(
                Ym6TestData.file(registers, FRAMES, true, "YM6!", 50, 2, 0)));
        byte[] file = YmxEncoder.encode(source, 960, 24, false).file();

        assertEquals(2, word(file, YmxFormat.OFFSET_SAMPLE_COUNT));
        int table = longAt(file, YmxFormat.OFFSET_SAMPLE_TABLE);
        assertTrue(table > 0, "the sample table exists");
        for (int i = 0; i < 2; i++) {
            int at = longAt(file, table + YmxFormat.SAMPLE_ENTRY_SIZE * i);
            int length = word(file, table + YmxFormat.SAMPLE_ENTRY_SIZE * i + 4);
            assertEquals(3, length, "the test drums are three samples long");
            for (int j = 0; j < length; j++) {
                assertTrue((file[at + j] & 0xFF) <= 15,
                        "sample bytes are PSG-ready volumes");
            }
            assertEquals(YmxFormat.SAMPLE_END_MARK, file[at + length] & 0xFF,
                    "drum " + i + " ends with the marker the ISR stops on");
        }
    }

    @Test
    void aDrumlessFileHasNoDrumTable() {
        byte[] file = YmxEncoder.encode(tune(true), 960, 24, false).file();
        assertEquals(0, longAt(file, YmxFormat.OFFSET_SAMPLE_TABLE));
        assertEquals(0, word(file, YmxFormat.OFFSET_SAMPLE_COUNT));
    }

    /** Unpacks one embedded ST4 container, holding it to an offset limit. */
    private static byte[] unpack(byte[] file, int from, int packedSize, int offsetLimit) {
        St4Format.Container section = St4Format.read(
                Arrays.copyOfRange(file, from, from + packedSize));
        return St4Decompressor.decompress(section.control(), section.literal(),
                section.byteOffsets(), section.wordOffsets(), section.unit(),
                section.size(), offsetLimit);
    }
}
