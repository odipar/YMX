package org.ymx.rig;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic YM6 tunes for the rig, so the tests need no distributable
 * chiptune. The generated registers behave like real chip data - tones that
 * hold and step, volumes that decay, an envelope that is usually
 * "unchanged" - and every YM6 effect bit is set in the registers that carry
 * them, so the packer's masking has something to remove and the player has
 * something to get wrong.
 */
final class GenYm {

    static final int YM_REGISTERS = 16;         // R0..R15 in the file
    static final int PLAY_REGISTERS = 14;       // R0..R13 reach the chip
    static final int NO_ENVELOPE_CHANGE = 0xFF; // R13: leave the envelope running
    static final int PORT_BITS = 0xC0;          // R7 bits the ST forces back on

    // Bits the YM2149 uses; everything else in a YM6 frame is effect data.
    static final int[] MASK = {0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F, 0x1F,
            0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F};

    private GenYm() {}

    /** A tiny LCG, the rig's own: one sequence on every host. */
    private static final class Random {
        private int state = 12345;

        int next(int bound) {
            state = (state * 1103515245 + 12345) & 0x7FFFFFFF;
            return (state >>> 8) % bound;
        }
    }

    /** Raw YM6 register vectors, effect bits and all: registers[r][frame]. */
    static byte[][] registers(int frames) {
        Random random = new Random();
        byte[][] values = new byte[YM_REGISTERS][frames];
        int[] period = {0, 0, 0};
        int[] volume = {15, 12, 9};
        for (int frame = 0; frame < frames; frame++) {
            for (int voice = 0; voice < 3; voice++) {
                if (frame % (7 + voice * 3) == 0) {
                    period[voice] = 40 + random.next(3000);
                    volume[voice] = 15;
                } else if (volume[voice] > 0 && frame % 4 == 0) {
                    volume[voice]--;
                }
                values[voice * 2][frame] = (byte) period[voice];
                values[voice * 2 + 1][frame] = (byte) (period[voice] >> 8);
                values[8 + voice][frame] = (byte) volume[voice];
            }
            values[6][frame] = (byte) (frame % 32);
            values[7][frame] = (byte) (0x38 | (frame % 8));
            values[11][frame] = (byte) (frame * 3);
            values[12][frame] = (byte) (frame / 64);
            values[13][frame] = (byte) (frame % 50 == 0 ? 0x0A : NO_ENVELOPE_CHANGE);

            values[1][frame] |= 0x30;           // effect 1: voice set, TP=0 -
            values[3][frame] |= (byte) 0xC0;    // inert, dropped at pack time,
            values[7][frame] |= (byte) 0xC0;    // so the checksum stays exact
            values[8][frame] |= 0x20;           // per-voice effect flags
            values[9][frame] |= 0x40;
            values[10][frame] |= (byte) 0x80;
            values[14][frame] = (byte) random.next(256);
            values[15][frame] = (byte) random.next(256);
        }
        return values;
    }

    /** What a plain YM2149 receives: the fourteen streams the packer writes. */
    static int[][] masked(int frames, byte[][] source) {
        int[][] out = new int[PLAY_REGISTERS][frames];
        for (int register = 0; register < PLAY_REGISTERS; register++) {
            for (int frame = 0; frame < frames; frame++) {
                int value = source[register][frame] & 0xFF;
                out[register][frame] = register == 13 && value == NO_ENVELOPE_CHANGE
                        ? NO_ENVELOPE_CHANGE : value & MASK[register];
            }
        }
        return out;
    }

    /** Which frame of the tune each played frame shows: a tune that starts
     * over runs 0..O-1 once and then L..O-1 again and again, one that plays
     * once stops at O-1. */
    static int[] frameOrder(int frames, int loopFrame, boolean loops, int count) {
        List<Integer> order = new ArrayList<>();
        int frame = 0;
        for (int i = 0; i < count; i++) {
            order.add(frame);
            frame++;
            if (frame >= frames) {
                if (!loops) {
                    break;
                }
                frame = loopFrame;
            }
        }
        return order.stream().mapToInt(Integer::intValue).toArray();
    }

    /** One played frame's outcome: the chip's fourteen registers after it,
     * and whether R13 was written - the write restarts the envelope, so it
     * is observable on its own. */
    record ChipState(int[] registers, boolean envelopeWritten) {}

    /** What the sound chip must hold after each played frame. A player may
     * skip a register whose value has not changed - the chip cannot tell -
     * so state, not the write sequence, has to match. */
    static List<ChipState> chipStates(int frames, byte[][] source, boolean loops,
            int loopFrame, int count) {
        int[][] vectors = masked(frames, source);
        int[] state = new int[PLAY_REGISTERS];
        List<ChipState> history = new ArrayList<>();
        for (int frame : frameOrder(frames, loopFrame, loops, count)) {
            boolean envelopeWritten = false;
            for (int register = 0; register < PLAY_REGISTERS; register++) {
                int value = vectors[register][frame];
                if (register == 7) {
                    value |= PORT_BITS;
                }
                if (register == 13) {
                    if (value == NO_ENVELOPE_CHANGE) {
                        continue;
                    }
                    envelopeWritten = true;
                }
                state[register] = value;
            }
            history.add(new ChipState(state.clone(), envelopeWritten));
        }
        return history;
    }

    /** A complete, unpacked YM6! file - what the YMX packer takes as input.
     * The drums are 8-bit digidrum samples, stored the way a YM6 file
     * stores them. */
    static byte[] ym6File(int frames, byte[][] source, byte[]... drums) {
        return ym6File(frames, 0, source, drums);
    }

    /** The same, with the frame the header sends its own player back to -
     * what the packer answers for when it decides the file's L. */
    static byte[] ym6File(int frames, int loopFrame, byte[][] source,
            byte[]... drums) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("YM6!LeOnArD!".getBytes(StandardCharsets.US_ASCII));
        writeLong(out, frames);
        writeLong(out, 1);                          // interleaved
        writeWord(out, drums.length);               // digidrums
        writeLong(out, 2000000);                    // master clock
        writeWord(out, 50);                         // player rate
        writeLong(out, loopFrame);                  // loop frame
        writeWord(out, 0);                          // additional data size
        for (byte[] drum : drums) {
            writeLong(out, drum.length);
            out.writeBytes(drum);
        }
        out.writeBytes("Synthetic\0Test\0Generated by the rig\0"
                .getBytes(StandardCharsets.US_ASCII));
        for (byte[] vector : source) {
            out.write(vector, 0, frames);
        }
        out.writeBytes("End!".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static void writeWord(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void writeLong(ByteArrayOutputStream out, int value) {
        writeWord(out, value >>> 16);
        writeWord(out, value);
    }
}
