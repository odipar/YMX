package org.ymx.rig;

/**
 * The two YM dumps under {@code ym/test} that are built rather than
 * recorded, and the effects they carry that no recorded file does.
 *
 * <p>A YM6 frame files an effect in two slots, each three fields spread
 * across spare register bits: slot 1's code is R1 bits 7-4, its prescaler
 * R6 bits 7-5 and its count R14; slot 2's are R3, R8 and R15. A code's
 * bits 7-6 select the kind - 00 SID, 01 DigiDrum, 11 Sync-Buzzer - and its
 * bits 5-4 the voice plus one, so 00 leaves the slot idle. The parameter
 * sits in the voice's own volume register: a SID's maximum volume, a
 * drum's sample number, a buzzer's envelope shape.
 *
 * <p>{@code BuiltTunesTest} rebuilds both files and compares them to the
 * bytes in the tree, so each stays reproducible from this source.
 */
public final class BuiltTunes {

    private BuiltTunes() {
    }

    /** The frames each built tune runs for. */
    public static final int BUZZER_FRAMES = 3000;
    /** The frames the preempt tune runs for. */
    public static final int PREEMPT_FRAMES = 400;

    /**
     * A sync-buzzer, which no file in the collection carries: a retrigger
     * stream on voice C in bursts, and a toggle stream on voice B whose
     * rate moves while its volume holds, so a rate change reprograms a
     * running stream rather than starting a fresh one.
     */
    public static byte[] buzzer() {
        int frames = BUZZER_FRAMES;
        byte[][] v = bed(frames);
        for (int f = 0; f < frames; f++) {
            v[8][f] = 12;

            // Bursts of 40 frames every 60, so the retrigger stream starts
            // and is released more than once. Its shape holds for a whole
            // burst, and the rate moves three times inside one.
            boolean buzz = f >= 8 && (f / 60) % 2 == 0;
            if (buzz) {
                v[1][f] |= (byte) 0xF0;             // sync-buzzer, voice C
                v[6][f] |= (byte) ((5 + (f / 20) % 3) << 5);
                v[14][f] = (byte) (60 + (f / 5) % 40);
                v[10][f] = (byte) ((f / 120) % 2 == 0 ? 0x0A : 0x0C);
            } else {
                v[10][f] = 11;
            }

            if (f >= 4) {                           // SID, voice B
                v[3][f] |= (byte) 0x20;
                v[8][f] |= (byte) ((4 + (f / 37) % 4) << 5);
                v[15][f] = (byte) (40 + (f / 11) % 80);
                v[9][f] = 13;
            } else {
                v[9][f] = 12;
            }
        }
        return GenYm.ym6File(frames, v);
    }

    /**
     * A drum arriving on the voice a toggle stream is running on, which no
     * file in the collection does: the drum's stream stops the toggle's
     * timer before it starts. Both slots address voice A, and the toggle
     * stays armed on the frame the drum lands, so it is running when the
     * drum takes the voice.
     */
    public static byte[] preempt() {
        int frames = PREEMPT_FRAMES;
        byte[][] v = bed(frames);
        byte[] drum = new byte[150];
        for (int i = 0; i < drum.length; i++) {
            drum[i] = (byte) (128 + 100 * Math.sin(i * 0.2));
        }
        for (int f = 0; f < frames; f++) {
            v[8][f] = 13;
            v[1][f] |= (byte) 0x10;                 // SID, voice A
            v[6][f] |= (byte) ((4 + (f / 20) % 4) << 5);
            v[14][f] = (byte) (60 + f % 40);
            if (f % 20 == 0 && f > 0) {             // DigiDrum, voice A
                v[3][f] |= (byte) 0x50;
                v[8][f] = (byte) (5 << 5);          // prescaler, sample 0
                v[15][f] = (byte) 80;
            }
        }
        return GenYm.ym6File(frames, v, drum);
    }

    /** Three tones and no noise, the same under both tunes' effects. */
    private static byte[][] bed(int frames) {
        byte[][] v = new byte[16][frames];
        for (int f = 0; f < frames; f++) {
            v[0][f] = (byte) (0x40 + f % 90);
            v[1][f] = 1;
            v[2][f] = (byte) (0x30 + f % 40);
            v[3][f] = 2;
            v[4][f] = (byte) (0x20 + f % 30);
            v[5][f] = 3;
            v[6][f] = (byte) (f % 32);
            v[7][f] = (byte) 0x38;
            v[9][f] = 12;
            v[10][f] = 11;
            v[11][f] = (byte) (f * 3);
            v[12][f] = (byte) (f / 64);
            v[13][f] = (byte) GenYm.NO_ENVELOPE_CHANGE;
        }
        return v;
    }
}
