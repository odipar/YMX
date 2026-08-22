package org.ymr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class YmrReaderTest {

    // Stream indices, by the names the format gives them.
    private static final int TONE_A = 1;
    private static final int TONE_B = 2;
    private static final int NOISE = 4;
    private static final int MIXER = 5;
    private static final int VOLUME_A = 6;
    private static final int ENVELOPE_PERIOD = 9;
    private static final int ENVELOPE_SHAPE = 10;
    private static final int TIMER_A_EFFECT = 11;
    private static final int TIMER_A_RATE = 12;
    private static final int TIMER_A_SAMPLE = 13;
    private static final int TIMER_D_EFFECT = 17;

    @Test
    void readsWhatTheHeaderSaysAboutTheSong() {
        byte[] image = new Ymr()
                .frame(TONE_A, MIXER)
                .frame()
                .frame(MIXER)
                .stream(TONE_A, 0xE7, 0x03)
                .stream(MIXER, 0x38, 0x3F)
                .rate(60)
                .loop(1)
                .build();

        YmrReader.Song song = YmrReader.read(image);

        assertEquals(3, song.frameCount());
        assertEquals(1, song.loopFrame());
        assertTrue(song.loops());
        assertEquals(60, song.frameRate());
        assertEquals(2000000L, song.ymClock());
        assertEquals(0, song.samples().size());
    }

    @Test
    void aSongThatDoesNotLoopReportsMinusOne() {
        YmrReader.Song song = YmrReader.read(new Ymr().frame().build());

        assertEquals(-1, song.loopFrame());
        assertFalse(song.loops());
    }

    @Test
    void aRegisterKeepsItsLastPoppedValueUntilItIsPoppedAgain() {
        byte[] image = new Ymr()
                .frame(NOISE)
                .frame()
                .frame()
                .frame(NOISE)
                .stream(NOISE, 0x1F, 0x07)
                .build();

        YmrReader.Song song = YmrReader.read(image);

        assertArrayEquals(new byte[] {0x1F, 0x1F, 0x1F, 0x07}, song.registers()[6]);
    }

    @Test
    void aStreamNoFrameEverPoppedIsSimplyNotInTheFile() {
        byte[] image = new Ymr()
                .frame(TIMER_A_EFFECT, TIMER_A_RATE)
                .stream(TIMER_A_EFFECT, 1)
                .stream(TIMER_A_RATE, 3, 200)
                .build();

        YmrReader.Song song = YmrReader.read(image);

        // Nine timer streams exist in the map; this song wrote two of them and
        // left the other seven out, timer_a_sample among them.
        assertEquals(0, offsetOf(image, TIMER_A_SAMPLE));
        assertEquals(0, offsetOf(image, TIMER_D_EFFECT));

        YmrReader.TimerFrame timerA = song.timer(0).get(0);
        assertEquals(YmrReader.TimerFrame.PWM, timerA.effect());
        assertEquals(0, timerA.sample());
        assertFalse(timerA.samplePopped());
        assertEquals(YmrReader.TimerFrame.NONE, song.timer(2).get(0).effect());
        assertFalse(song.timer(2).get(0).popped());
    }

    @Test
    void aWidthTwoEntryIsTwoRegistersInRegisterOrder() {
        // Read as a big-endian word, $E703 would be a tone period of 59139 - far
        // outside the twelve bits R0/R1 hold. In register order it is R0 = $E7,
        // R1 = $03: period $3E7, a legal note.
        byte[] image = new Ymr()
                .frame(TONE_A, TONE_B, ENVELOPE_PERIOD, TIMER_A_EFFECT, TIMER_A_RATE)
                .stream(TONE_A, 0xE7, 0x03)
                .stream(TONE_B, 0x40, 0x00)
                .stream(ENVELOPE_PERIOD, 0x11, 0x22)
                .stream(TIMER_A_EFFECT, 1)
                .stream(TIMER_A_RATE, 3, 200)
                .build();

        YmrReader.Song song = YmrReader.read(image);

        assertEquals(0xE7, song.register(0, 0));
        assertEquals(0x03, song.register(1, 0));
        assertEquals(0x40, song.register(2, 0));
        assertEquals(0x00, song.register(3, 0));
        assertEquals(0x11, song.register(11, 0));
        assertEquals(0x22, song.register(12, 0));

        // A timer's rate is the MFP's control and data registers, in that order.
        YmrReader.TimerFrame timerA = song.timer(0).get(0);
        assertEquals(3, timerA.prescaler());
        assertEquals(200, timerA.counter());
    }

    @Test
    void registerThirteenIsWrittenOnlyOnTheFrameThatPopsTheShape() {
        byte[] image = new Ymr()
                .frame(ENVELOPE_SHAPE)
                .frame()
                .frame(ENVELOPE_SHAPE)
                .frame()
                .stream(ENVELOPE_SHAPE, 0x0A, 0x0A)
                .build();

        YmrReader.Song song = YmrReader.read(image);

        // The same shape twice is not a repeat but a second retrigger, and a frame
        // that does not pop the stream must not write R13 at all.
        assertEquals(0x0A, song.register(13, 0));
        assertEquals(YmrReader.NO_ENVELOPE_SHAPE, song.register(13, 1));
        assertEquals(0x0A, song.register(13, 2));
        assertEquals(YmrReader.NO_ENVELOPE_SHAPE, song.register(13, 3));
    }

    @Test
    void aPopOfATimerStreamIsAnEventEvenWhenTheValueDoesNotChange() {
        byte[] image = new Ymr()
                .frame(TIMER_A_EFFECT, TIMER_A_RATE, TIMER_A_SAMPLE)
                .frame(TIMER_A_SAMPLE)
                .frame(TIMER_A_RATE)
                .frame(TIMER_A_EFFECT)
                .stream(TIMER_A_EFFECT, 2, 0)
                .stream(TIMER_A_RATE, 3, 200, 3, 150)
                .stream(TIMER_A_SAMPLE, 4, 4)
                .build();

        List<YmrReader.TimerFrame> timerA = YmrReader.read(image).timer(0);

        assertEquals(YmrReader.TimerFrame.SAMPLE, timerA.get(0).effect());
        assertTrue(timerA.get(0).effectPopped());
        assertTrue(timerA.get(0).ratePopped());
        assertTrue(timerA.get(0).samplePopped());

        // Frame 1 pops the same sample index again: the value is unchanged and the
        // pop is the restart, so only the flag says anything happened.
        assertEquals(4, timerA.get(1).sample());
        assertTrue(timerA.get(1).samplePopped());
        assertFalse(timerA.get(1).effectPopped());
        assertFalse(timerA.get(1).ratePopped());

        // Frame 2 retunes without restarting anything.
        assertEquals(150, timerA.get(2).counter());
        assertTrue(timerA.get(2).ratePopped());
        assertFalse(timerA.get(2).samplePopped());

        // Frame 3 pops a 0 effect, which is what stops the timer.
        assertEquals(YmrReader.TimerFrame.NONE, timerA.get(3).effect());
        assertTrue(timerA.get(3).effectPopped());
        assertEquals(4, timerA.get(3).sample());
    }

    @Test
    void readsSampleBlocksIncludingALoopedOne() {
        byte[] oneShot = {0x08, 0x0F, 0x00, 0x08};
        byte[] looped = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06};

        byte[] image = new Ymr()
                .frame(VOLUME_A)
                .stream(VOLUME_A, 0x0F)
                .sample(oneShot, false, 0)
                .sample(looped, true, 0x1234)
                .build();

        List<YmrReader.Sample> samples = YmrReader.read(image).samples();

        assertEquals(2, samples.size());
        assertArrayEquals(oneShot, samples.get(0).data());
        assertFalse(samples.get(0).looped());
        assertEquals(0, samples.get(0).loopStart());
        assertArrayEquals(looped, samples.get(1).data());
        assertTrue(samples.get(1).looped());
        assertEquals(0x1234, samples.get(1).loopStart());
    }

    @Test
    void namesTheProblemWithImagesItWillNotRead() {
        byte[] good = new Ymr()
                .frame(TONE_A)
                .frame()
                .stream(TONE_A, 0xE7, 0x03)
                .build();

        byte[] wrongMagic = good.clone();
        wrongMagic[1] = 'X';
        assertTrue(message(wrongMagic).contains("not a .YMR image"), message(wrongMagic));

        byte[] wrongVersion = good.clone();
        wrongVersion[5] = 0x02;
        assertTrue(message(wrongVersion).contains("version 1.2"), message(wrongVersion));

        byte[] wrongStreamCount = good.clone();
        wrongStreamCount[23] = 19;
        assertTrue(message(wrongStreamCount).contains("19 entries"), message(wrongStreamCount));

        byte[] truncated = Arrays.copyOf(good, 100);
        assertTrue(message(truncated).contains("truncated file"), message(truncated));

        byte[] reservedCommand = new Ymr().rawCommands(0xC0, 0x00).frames(1).build();
        assertTrue(message(reservedCommand).contains("reserves"), message(reservedCommand));

        byte[] pastTheMap = new Ymr().rawCommands(20, 0x00).frames(1).build();
        assertTrue(message(pastTheMap).contains("past the 20"), message(pastTheMap));

        byte[] oddToneStream = new Ymr()
                .frame(TONE_A)
                .stream(TONE_A, 0xE7, 0x03, 0x40)
                .build();
        assertTrue(message(oddToneStream).contains("whole number of 2-byte entries"),
                message(oddToneStream));

        byte[] tooFewFrames = new Ymr().rawCommands(0x00, 0x00).frames(3).build();
        assertTrue(message(tooFewFrames).contains("holds 2 end-of-frame bytes"),
                message(tooFewFrames));

        byte[] tooManyFrames = new Ymr().rawCommands(0x00, 0x00, 0x00).frames(2).build();
        assertTrue(message(tooManyFrames).contains("more than the 2 end-of-frame bytes"),
                message(tooManyFrames));

        byte[] endsMidFrame = new Ymr()
                .rawCommands(TONE_A, 0x00, TONE_A)
                .frames(1)
                .stream(TONE_A, 0xE7, 0x03, 0x40, 0x00)
                .build();
        assertTrue(message(endsMidFrame).contains("ends in the middle of a frame"),
                message(endsMidFrame));

        byte[] popsAnAbsentStream = new Ymr().rawCommands(NOISE, 0x00).frames(1).build();
        assertTrue(message(popsAnAbsentStream).contains("not in the file"),
                message(popsAnAbsentStream));

        byte[] popsPastTheEnd = new Ymr()
                .frame(TONE_A)
                .frame(TONE_A)
                .stream(TONE_A, 0xE7, 0x03)
                .build();
        assertTrue(message(popsPastTheEnd).contains("nothing left to pop"),
                message(popsPastTheEnd));
    }

    // ------------------------------------------------------------------- ZX1

    /**
     * A ZX1 stream written out by hand from the format's own rules, so the test
     * does not rest on the same reading of them that {@link Zx1} does.
     *
     * <pre>
     *   $A7  1 0 1 0 0   gamma = 4, the opening literal run's length; the first
     *                    operation carries no flag bit of its own
     *        1           after literals, 1 means a match at a NEW offset
     *        1 1 ...     the start of that match's gamma
     *   $01 $02 $03 $04  the four literals themselves
     *   $F8              the new offset: even, so 128 - $F8/2 = 4
     *   $40  0           the gamma's last bit: 1 1 0 = 3, and a new-offset match
     *                    is one longer than it says, so four bytes
     *        1           after a match, 1 means another new offset
     *   $FF $FF          32512 - 254*128 - 254 - 1 = -255: the END MARKER, which
     *                    is what the reference packer writes
     * </pre>
     *
     * The match copies the four bytes just emitted, so the stream decodes to
     * {@code 01 02 03 04 01 02 03 04}.
     */
    private static final byte[] HAND_WRITTEN = {
        (byte) 0xA7, 0x01, 0x02, 0x03, 0x04, (byte) 0xF8, 0x40, (byte) 0xFF, (byte) 0xFF,
    };

    @Test
    void decodesAHandWrittenZx1Stream() {
        assertArrayEquals(new byte[] {1, 2, 3, 4, 1, 2, 3, 4}, Zx1.decode(HAND_WRITTEN, 64));
    }

    @Test
    void aRingSizeOfZeroMeansTheStreamIsStoredUncompressed() {
        assertArrayEquals(HAND_WRITTEN, Zx1.decode(HAND_WRITTEN, 0));
    }

    @Test
    void refusesAMatchThatReachesFurtherBackThanTheRing() {
        // The one match reaches back four bytes, which a three-byte ring has
        // already overwritten on the Atari - so this decoder will not pretend.
        // The distance itself is not in the message: offsets are the vendored
        // decoder's business, and the overreach is found by decoding twice and
        // comparing rather than by a second reading of the bitstream here.
        String message = String.valueOf(assertThrows(YmrReader.FormatException.class,
                () -> Zx1.decode(HAND_WRITTEN, 3)).getMessage());

        assertTrue(message.contains("a ZX1 stream"), message);
        assertTrue(message.contains("reaches back further"), message);
        assertTrue(message.contains("3-byte ring"), message);
    }

    @Test
    void refusesAStreamThatEndsMidOperation() {
        byte[] cut = Arrays.copyOf(HAND_WRITTEN, 4);
        String message = String.valueOf(assertThrows(YmrReader.FormatException.class,
                () -> Zx1.decode(cut, 64)).getMessage());

        assertTrue(message.contains("ends mid-operation"), message);
    }

    // --------------------------------------------------------------- fixtures

    private static String message(byte[] image) {
        return String.valueOf(assertThrows(YmrReader.FormatException.class,
                () -> YmrReader.read(image)).getMessage());
    }

    private static int offsetOf(byte[] image, int stream) {
        int at = 28 + stream * 12;
        return ((image[at] & 0xFF) << 24) | ((image[at + 1] & 0xFF) << 16)
                | ((image[at + 2] & 0xFF) << 8) | (image[at + 3] & 0xFF);
    }

    /**
     * Builds a .YMR image, storing every stream uncompressed - a ring size of 0,
     * which the format defines as "the bytes are the data". That is what lets a
     * fixture be written here at all: nothing in this repository packs ZX1, and
     * the reader has no way to tell an exporter's uncompressed stream from one
     * of these.
     *
     * <p>The header is fixed at twenty streams and the map is always written in
     * full, because a stream that is absent is absent by carrying an offset of
     * 0, not by being left out of the map.
     */
    private static final class Ymr {

        private static final int STREAMS = 20;
        private static final int HEADER_SIZE = 28 + STREAMS * 12;

        private final byte[][] streams = new byte[STREAMS][];
        private final ByteArrayOutputStream commands = new ByteArrayOutputStream();
        private final List<byte[]> sampleBlocks = new ArrayList<>();
        private int frameCount;
        private int version = 0x0103;
        private int declaredStreams = STREAMS;
        private long loopFrame = 0xFFFFFFFFL;
        private int frameRate = 50;
        private long ymClock = 2000000L;

        /** One frame, popping the given streams; they must be in ascending order. */
        Ymr frame(int... pops) {
            for (int pop : pops) {
                commands.write(pop);
            }
            commands.write(0x00);
            frameCount++;
            return this;
        }

        /** The command stream verbatim, for fixtures that have to be malformed. */
        Ymr rawCommands(int... bytes) {
            for (int b : bytes) {
                commands.write(b);
            }
            return this;
        }

        /** The frame count to write in the header, when it is not the truth. */
        Ymr frames(int count) {
            frameCount = count;
            return this;
        }

        Ymr stream(int index, int... bytes) {
            byte[] entries = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                entries[i] = (byte) bytes[i];
            }
            streams[index] = entries;
            return this;
        }

        Ymr sample(byte[] data, boolean looped, int loopStart) {
            ByteArrayOutputStream block = new ByteArrayOutputStream();
            writeU32(block, data.length);
            block.writeBytes(data);
            block.write(looped ? 1 : 0);
            writeU16(block, loopStart);
            block.write(0);
            sampleBlocks.add(block.toByteArray());
            return this;
        }

        Ymr rate(int hz) {
            frameRate = hz;
            return this;
        }

        Ymr loop(int frame) {
            loopFrame = frame;
            return this;
        }

        byte[] build() {
            streams[0] = commands.toByteArray();

            int sampleBytes = 0;
            for (byte[] block : sampleBlocks) {
                sampleBytes += block.length;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("YMR!".getBytes(StandardCharsets.US_ASCII));
            writeU16(out, version);
            writeU32(out, frameCount);
            writeU32(out, loopFrame);
            writeU16(out, frameRate);
            writeU16(out, sampleBlocks.size());
            writeU32(out, ymClock);
            writeU16(out, declaredStreams);
            writeU32(out, 0);                           // reserved

            int offset = HEADER_SIZE + sampleBytes;
            for (byte[] stream : streams) {
                if (stream == null) {
                    writeU32(out, 0);                   // the stream is absent
                    writeU32(out, 0);
                    writeU16(out, 0);
                    writeU16(out, 0);
                    continue;
                }
                writeU32(out, offset);
                writeU32(out, 0);                       // loop offset
                writeU16(out, 0);                       // ring size: uncompressed
                writeU16(out, 0);                       // reserved
                offset += stream.length;
            }

            for (byte[] block : sampleBlocks) {
                out.writeBytes(block);
            }
            for (byte[] stream : streams) {
                if (stream != null) {
                    out.writeBytes(stream);
                }
            }
            return out.toByteArray();
        }

        private static void writeU16(ByteArrayOutputStream out, int value) {
            out.write(value >> 8);
            out.write(value);
        }

        private static void writeU32(ByteArrayOutputStream out, long value) {
            out.write((int) (value >> 24));
            out.write((int) (value >> 16));
            out.write((int) (value >> 8));
            out.write((int) value);
        }
    }
}
