package org.ymx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.st4.St4Decompressor;
import org.st4.St4Format;
import org.junit.jupiter.api.Test;
import org.ym6.Ym6Reader;
import org.ym6.Ym6TestData;
import org.ym6.YmEffects;

/** The loop split: two sets of streams, and what the player is promised. */
final class YmxLoopTest {

    private static final int FRAMES = 900;

    private static Tune song(int loopFrame) {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        return YmEffects.tune(Ym6Reader.read(
                Ym6TestData.file(registers, FRAMES, true, "YM6!", 50, 0, loopFrame)));
    }

    /** The same tune with its effect codes silenced: the register-section
     * properties below are about the split itself, and a held effect
     * crossing the wrap would rotate it. */
    private static Tune quiet(int loopFrame) {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        for (int frame = 0; frame < FRAMES; frame++) {
            registers[1][frame] &= ~0x30;       // no slot-1 code
            registers[3][frame] &= ~0x30;       // no slot-2 voice bits
        }
        return YmEffects.tune(Ym6Reader.read(
                Ym6TestData.file(registers, FRAMES, true, "YM6!", 50, 0, loopFrame)));
    }

    private static int word(byte[] file, int at) {
        return ((file[at] & 0xFF) << 8) | (file[at + 1] & 0xFF);
    }

    private static int longAt(byte[] file, int at) {
        return (word(file, at) << 16) | word(file, at + 2);
    }

    private static byte[] stream(byte[] file, int table, int register, int packedSize) {
        int from = longAt(file, table + 4 * register);
        return unpack(file, from, packedSize, Integer.MAX_VALUE);
    }

    /** Unpacks one embedded ST4 container, holding it to an offset limit. */
    private static byte[] unpack(byte[] file, int from, int packedSize, int offsetLimit) {
        St4Format.Container section = St4Format.read(
                Arrays.copyOfRange(file, from, from + packedSize));
        return St4Decompressor.decompress(section.control(), section.literal(),
                section.byteOffsets(), section.wordOffsets(), section.unit(),
                section.size(), offsetLimit);
    }


    private static int packedSize(YmxEncoder.Result result, int register, boolean loop) {
        return result.streams().stream()
                .filter(s -> s.register() == register && s.loop() == loop)
                .findFirst().orElseThrow().packedSize();
    }

    @Test
    void eachSectionUnpacksToItsOwnSliceOfTheRegister() {
        int loop = 397;                             // not a multiple of the chunk
        Tune source = song(loop);
        YmxEncoder.Result result = YmxEncoder.encode(source, 960, 24, loop, false);
        byte[] file = result.file();

        // This tune holds an effect across the wrap, so the split rotates
        // until both arrivals agree; the header carries the played shape.
        int split = result.script().split();
        assertEquals(YmxFormat.FLAG_LOOPS,
                word(file, YmxFormat.OFFSET_FLAGS) & YmxFormat.FLAG_LOOPS);
        assertEquals(split, longAt(file, YmxFormat.OFFSET_LOOP_FRAME));
        assertEquals(result.script().frames(), longAt(file, YmxFormat.OFFSET_FRAMES));

        byte[][] expected = YmxEncoderTest.expectedVectors(source, loop, 1);
        for (int register = 0; register < YmxFormat.STREAMS; register++) {
            byte[] whole = expected[register];
            assertArrayEquals(Arrays.copyOfRange(whole, 0, split),
                    stream(file, YmxFormat.OFFSET_INTRO_TABLE, register,
                            packedSize(result, register, false)),
                    "intro stream " + register);
            assertArrayEquals(Arrays.copyOfRange(whole, split, whole.length),
                    stream(file, YmxFormat.OFFSET_LOOP_TABLE, register,
                            packedSize(result, register, true)),
                    "loop stream " + register);
        }
    }

    @Test
    void loopingFromTheStartPacksNoIntro() {
        YmxEncoder.Result result = YmxEncoder.encode(quiet(0), 960, 24, 0, false);
        byte[] file = result.file();

        assertEquals(0, longAt(file, YmxFormat.OFFSET_LOOP_FRAME));
        for (int register = 0; register < YmxFormat.STREAMS; register++) {
            assertEquals(0, longAt(file, YmxFormat.OFFSET_INTRO_TABLE + 4 * register),
                    "intro offset " + register + " should be unused");
            assertTrue(longAt(file, YmxFormat.OFFSET_LOOP_TABLE + 4 * register) > 0);
        }
        assertEquals(YmxFormat.STREAMS, result.streams().size());
    }

    @Test
    void aHeldEffectAcrossTheWrapRotatesTheSplit() {
        // The effectful tune holds a SID from frame 0: the first arrival at
        // the loop head (pristine) and the wrap arrival (running) disagree,
        // so the split rotates past the start and a short intro appears -
        // the frames it absorbs exist twice, compiled differently.
        YmxEncoder.Result result = YmxEncoder.encode(song(0), 960, 24, 0, false);
        int split = result.script().split();
        assertTrue(split > 0, "the wrap state cannot match the pristine start");
        assertEquals(FRAMES + split, result.script().frames());
        byte[] file = result.file();
        assertEquals(split, longAt(file, YmxFormat.OFFSET_LOOP_FRAME));
        assertTrue(longAt(file, YmxFormat.OFFSET_INTRO_TABLE) > 0,
                "the rotation gives the loop-from-zero tune an intro");
    }

    @Test
    void playingOncePacksNoLoop() {
        YmxEncoder.Result result = YmxEncoder.encode(song(0), 960, 24, -1, false);
        byte[] file = result.file();

        assertEquals(0, word(file, YmxFormat.OFFSET_FLAGS) & YmxFormat.FLAG_LOOPS);
        assertEquals(FRAMES, longAt(file, YmxFormat.OFFSET_LOOP_FRAME),
                "the intro covers everything, so the split sits at the end");
        for (int register = 0; register < YmxFormat.STREAMS; register++) {
            assertTrue(longAt(file, YmxFormat.OFFSET_INTRO_TABLE + 4 * register) > 0);
            assertEquals(0, longAt(file, YmxFormat.OFFSET_LOOP_TABLE + 4 * register),
                    "loop offset " + register + " should be unused");
        }
    }

    @Test
    void theSplitCostsRatioButLoopingFromZeroDoesNot() {
        int whole = YmxEncoder.encode(quiet(0), 960, 24, -1, false).packedSize();
        int fromStart = YmxEncoder.encode(quiet(0), 960, 24, 0, false).packedSize();
        int fromMiddle = YmxEncoder.encode(quiet(450), 960, 24, 450, false).packedSize();

        assertEquals(whole, fromStart, "one section either way, so the same bytes");
        assertTrue(fromMiddle > whole, "splitting a register costs some ratio");
    }

    @Test
    void rejectsALoopFrameOutsideTheTune() {
        Tune source = song(0);
        assertThrows(IllegalArgumentException.class,
                () -> YmxEncoder.encode(source, 960, 24, FRAMES, false));
        assertThrows(IllegalArgumentException.class,
                () -> YmxEncoder.encode(source, 960, 24, FRAMES + 10, false));
    }

    @Test
    void bothSectionsStayInsideTheRingAndTheWordCounters() {
        int ring = 240;
        YmxEncoder.Result result = YmxEncoder.encode(song(397), ring, 24, 397, false);
        byte[] file = result.file();
        assertTrue(result.longestOp() <= 65535);

        for (YmxEncoder.Stream stream : result.streams()) {
            int table = stream.loop() ? YmxFormat.OFFSET_LOOP_TABLE
                    : YmxFormat.OFFSET_INTRO_TABLE;
            int from = longAt(file, table + 4 * stream.register());
            // An offset within the ring is exactly what ring-safety means;
            // the limit-checking reference throws on anything further.
            assertEquals(stream.frames(),
                    unpack(file, from, stream.packedSize(), ring).length,
                    "stream " + stream.register() + (stream.loop() ? " loop" : " intro")
                            + " needs more than a " + ring + "-byte ring");
        }
    }

    @Test
    void theLoopFrameComesFromTheYmHeaderUnlessOverridden() {
        // What the CLI reads: a YM6 file carries its own loop frame.
        assertEquals(397, song(397).loopFrame());
        assertNotEquals(397, YmxEncoder.encode(song(397), 960, 24, 0, false).loopFrame());
    }
}
