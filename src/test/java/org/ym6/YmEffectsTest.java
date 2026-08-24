package org.ym6;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The extraction rules, one by one, against hand-built frames: both YM6
 * slots, the YM5 dialect (including its fixed-R8 drum prescaler), every drop
 * rule, and the drum conversion in both sample widths. The reference corpus
 * facts these encode: inert TP/TC codes are no-ops (Ninja Remix carries 151
 * of them), the reference player runs an empty handler for Sinus-SID, and a
 * drum trigger without a sample must vanish at pack time.
 */
final class YmEffectsTest {

    private static final int FRAMES = 8;

    /** Sixteen zeroed register vectors to poke effect fields into. */
    private static byte[][] blank() {
        return new byte[Ym6Reader.Song.YM_REGISTERS][FRAMES];
    }

    private static Ym6Reader.Song song(String format, byte[][] registers, int drums,
                                       long attributes) {
        byte[][] samples = new byte[drums][];
        for (int i = 0; i < drums; i++) {
            samples[i] = new byte[] {(byte) 0x80, (byte) 0xF3, 0x21};
        }
        return new Ym6Reader.Song(format, FRAMES, 50, 2_000_000, 0, true,
                attributes, samples, "", "", "", registers);
    }

    @Test
    void ym6SlotsComeOutAsControlAndCountBytes() {
        byte[][] r = blank();
        r[1][2] = (byte) 0x10;          // SID voice A in slot 1...
        r[6][2] = (byte) (1 << 5);      // ...prescaler 1
        r[14][2] = 100;                 // ...count 100
        r[3][5] = (byte) 0xF0;          // Sync-Buzzer voice C in slot 2...
        r[8][5] = (byte) (7 << 5);      // ...prescaler 7
        r[15][5] = (byte) 200;          // ...count 200

        YmEffects.Extraction effects = YmEffects.extract(song("YM6!", r, 0, 1));
        assertEquals(0x11, effects.e1()[2] & 0xFF, "code nibble | prescaler");
        assertEquals(100, effects.t1()[2] & 0xFF);
        assertEquals(0xF7, effects.e2()[5] & 0xFF);
        assertEquals(200, effects.t2()[5] & 0xFF);
        assertEquals(0, effects.e1()[0], "idle frames stay zero");
        assertEquals(0, effects.dropped());
    }

    @Test
    void ym5IsNormalizedIntoTheSameShape() {
        byte[][] r = blank();
        r[1][1] = (byte) 0x20;          // YM5: SID voice B (bits 5-4 of R1)
        r[6][1] = (byte) (2 << 5);
        r[14][1] = 50;
        r[3][4] = (byte) 0x30;          // YM5: digidrum voice C (bits 5-4 of R3)
        r[8][4] = (byte) (1 << 5);      // the YM5 drum prescaler is ALWAYS R8
        r[10][4] = 1;                   // sample number in the voice's volume register
        r[15][4] = 122;

        YmEffects.Extraction effects = YmEffects.extract(song("YM5!", r, 2, 1));
        assertEquals(0x22, effects.e1()[1] & 0xFF, "SID voice B, prescaler 2");
        assertEquals(50, effects.t1()[1] & 0xFF);
        assertEquals(0x71, effects.e2()[4] & 0xFF, "drum code 0x40 | voice C bits");
        assertEquals(122, effects.t2()[4] & 0xFF);
        assertEquals(0, effects.dropped());
    }

    @Test
    void inertAndUnplayableCodesAreDroppedToIdle() {
        byte[][] r = blank();
        r[1][0] = (byte) 0x10;          // SID with prescaler 0: inert (Ninja Remix)
        r[14][0] = 100;
        r[1][1] = (byte) 0x10;          // SID with count 0: inert
        r[6][1] = (byte) (1 << 5);
        r[1][2] = (byte) 0x90;          // Sinus-SID: dropped at pack time
        r[6][2] = (byte) (1 << 5);
        r[14][2] = 100;
        r[1][3] = (byte) 0x10;          // 2457600/4/10 = 61440 Hz: too fast
        r[6][3] = (byte) (1 << 5);
        r[14][3] = 10;
        r[3][4] = (byte) 0x50;          // drum on voice A with no drums in the file
        r[8][4] = (byte) (1 << 5);
        r[15][4] = 122;

        YmEffects.Extraction effects = YmEffects.extract(song("YM6!", r, 0, 1));
        for (int frame = 0; frame < FRAMES; frame++) {
            assertEquals(0, effects.e1()[frame], "e1 frame " + frame);
            assertEquals(0, effects.t1()[frame], "t1 frame " + frame);
            assertEquals(0, effects.e2()[frame], "e2 frame " + frame);
            assertEquals(0, effects.t2()[frame], "t2 frame " + frame);
        }
        assertEquals(2, effects.inert());
        assertEquals(1, effects.sinus());
        assertEquals(1, effects.tooFast());
        assertEquals(1, effects.missingDrum());
    }

    @Test
    void aDrumTriggerWithASampleSurvives() {
        byte[][] r = blank();
        r[3][0] = (byte) 0x70;          // drum voice C in slot 2
        r[8][0] = (byte) (1 << 5);
        r[10][0] = 1;                   // sample 1 of the 2 in the file
        r[15][0] = 122;                 // 2457600/4/122 = 5036 Hz

        YmEffects.Extraction effects = YmEffects.extract(song("YM6!", r, 2, 1));
        assertEquals(0x71, effects.e2()[0] & 0xFF);
        assertEquals(122, effects.t2()[0] & 0xFF);
        assertEquals(0, effects.dropped());
    }

    @Test
    void aTooFastDrumIsDownsampledNotDropped() {
        byte[][] r = blank();
        r[3][2] = 0x50;                     // drum voice A on slot 2 at
        r[8][2] = 1 << 5;                   // TP=1 TC=16: 38400 Hz - twice
        r[15][2] = 16;                      // the ceiling
        r[8][4] = (byte) ((1 << 5) | 0);    // and a second trigger at an
        r[3][4] = 0x50;                     // already-playable rate
        r[15][4] = (byte) 200;              // (3072 Hz)
        YmEffects.Extraction effects = YmEffects.extract(song("YM6!", r, 1, 1));

        assertEquals(0, effects.tooFast());
        assertEquals(1, effects.notes().size());
        // Resampled to the ceiling itself, not halved: 38400 Hz lands at
        // 25600 (divisor 64 -> 96, ratio 3/2), so the converted 3-value
        // sample {8, 15, 2} becomes 2 values through the windowed sinc in
        // the chip curve's linear domain.
        assertEquals(2, effects.samples()[0].length);
        assertEquals(13, effects.samples()[0][0]);
        assertEquals(13, effects.samples()[0][1]);
        // Both triggers scale their divisor by 3/2, keeping pitch: 4*16
        // *3/2 = 96 fits as prescaler 1, count 24; 4*200*3/2 = 1200 as
        // prescaler 2, count 120.
        assertEquals(0x51, effects.e2()[2] & 0xFF);
        assertEquals(24, effects.t2()[2] & 0xFF);
        assertEquals(0x52, effects.e2()[4] & 0xFF);
        assertEquals(120, effects.t2()[4] & 0xFF);
    }

    @Test
    void drumsConvertByWidth() {
        // 8-bit samples: the high nibble, the reference player's own
        // real-hardware mapping. 4-bit files: the byte as it is.
        byte[][] r = blank();
        YmEffects.Extraction eightBit = YmEffects.extract(song("YM6!", r, 1, 1));
        assertArrayEquals(new byte[] {8, 15, 2}, eightBit.samples()[0]);
        YmEffects.Extraction fourBit = YmEffects.extract(
                song("YM6!", r, 1, 1 | Ym6Reader.Song.A_DRUM4BITS));
        assertArrayEquals(new byte[] {0, 3, 1}, fourBit.samples()[0]);
    }

    @Test
    void theLevelHeldEffectRepeatsItsBytes() {
        // SID held for three frames with a count change mid-way: the stream
        // carries the code every frame, the format's own contract -
        // and what lets the player treat "same code" as reprogram-count-only.
        byte[][] r = blank();
        for (int frame = 2; frame <= 4; frame++) {
            r[1][frame] = (byte) 0x10;
            r[6][frame] = (byte) (1 << 5);
            r[14][frame] = (byte) (frame == 4 ? 80 : 100);
        }
        YmEffects.Extraction effects = YmEffects.extract(song("YM6!", r, 0, 1));
        assertEquals(0x11, effects.e1()[2] & 0xFF);
        assertEquals(0x11, effects.e1()[3] & 0xFF);
        assertEquals(0x11, effects.e1()[4] & 0xFF);
        assertEquals(100, effects.t1()[3] & 0xFF);
        assertEquals(80, effects.t1()[4] & 0xFF);
        assertEquals(0, effects.e1()[5], "released: the level drops to idle");
    }
}
