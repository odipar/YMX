package org.ym6;

import org.ymx.EffectScript;
import org.ymx.Tune;
import org.ymx.Ym2149;
import org.ymx.YmxFormat;

/**
 * The YM front end's far side: a {@link Ym6Reader.Song} in, a {@link Tune}
 * out, with every dialect and every unplayable code normalized away on the
 * journey - the player never sees an effect it cannot run.
 *
 * <p>This is where the vocabulary changes, and the change is the whole
 * reason the class exists. On the way in the names are the YM format's,
 * because the bytes are its: effect slots, codes, TP and TC. On the way out
 * they are the engine's, and what leaves here as a pair of bytes per frame
 * is a TIMER STREAM - a series of values written to one register between
 * frames, at a rate a timer sets. {@code doc/terminology.md} holds the
 * mapping and the model; a digidrum is a PCM stream there, a SID voice a
 * toggle stream, a sync-buzzer a retrigger stream.
 *
 * <p>{@link Extraction} is this front end's own report and stays behind
 * with it. Its four drop counters are counts of YM effects that a YM
 * dialect had to have normalized away, which is a sentence only a YM reader
 * can say and only a YM packer's report has any use for; the {@link Tune}
 * that {@link #tune} builds carries none of it. {@code org.ymr} is the
 * sibling that does the same job for a RhYMe dump, and reading the two side
 * by side is the quickest way to see which decisions belong to a format and
 * which belong to the engine.
 *
 * <p>A YM6 frame carries up to two effect slots, each three fields smeared
 * across spare register bits: a code nibble (type in bits 7-6, voice+1 in
 * bits 5-4, zero voice bits meaning idle) in R1 or R3, an MFP timer prescaler
 * in R6 or R8 bits 5-7, and a timer count in R14 or R15. YM5 encodes less in
 * different places: R1 bits 4-5 name a SID voice, R3 bits 4-5 a digidrum
 * voice, with the drum's prescaler always in R8 regardless of voice. Both
 * come out of here as the same two byte pairs per frame:
 *
 * <pre>
 *   E = code bits 7-4 | prescaler bits 2-0     (zero = the slot is idle)
 *   T = the timer count
 * </pre>
 *
 * <p>Codes are dropped to idle when the reference player would not start
 * them: prescaler or count of zero (wild files carry many such inert codes),
 * a SID or buzzer rate above what a real machine survives, a drum number
 * with no sample behind it, and Sinus-SID - which no player, the format
 * author's included, has ever implemented. The drop counters report what
 * happened.
 *
 * <p>A DRUM above the rate ceiling is rescued rather than dropped: the
 * sample is resampled to the highest MFP-representable rate under the
 * ceiling - through the chip's volume curve, with a windowed-sinc filter,
 * so no alias fold-back brightens the sound - and every trigger of that
 * drum has its timer divisor scaled by the same exact ratio, so pitch and
 * duration stay what the dump asked for and only the bandwidth falls, by
 * as little as the ceiling allows. A 29 kHz conversion-family drum lands
 * at 25.6 kHz, not at the old half-rate 14.6. When a drum's triggers
 * cannot all take the exact ratio, the old power-of-two factor is the
 * fallback. Each rescue is reported in {@code notes()}.
 *
 * <p>Drum samples are converted to PSG-ready volume values here: the high
 * nibble of an 8-bit sample, or the byte as-is for a 4-bit file - exactly the
 * real-hardware mapping in the reference player's source.
 */
public final class YmEffects {

    /** The four effect types under the engine's names, since the code byte
     *  they go into is the engine's: SID is a {@link Tune#KIND_TOGGLE},
     *  DRUM a {@link Tune#KIND_PCM}, BUZZER a {@link Tune#KIND_RETRIGGER},
     *  SINUS a {@link Tune#KIND_CURVE} (no corpus tune uses it). */
    private static final int KIND_TOGGLE = Tune.KIND_TOGGLE;
    private static final int KIND_PCM = Tune.KIND_PCM;
    private static final int KIND_CURVE = Tune.KIND_CURVE;
    private static final int KIND_RETRIGGER = Tune.KIND_RETRIGGER;

    /** The fastest tick rate a real player programs: SIDs and buzzers
     * above it are dropped, samples resampled under it. The CLI's -drumhz
     * option moves the drum ceiling. */
    public static final int MAX_TIMER_HZ = 25600;

    /** What the reader's frames become, and what this extraction has to say
     *  about the file it read: a code byte and a count byte per timer channel
     *  per frame naming the timer streams to run, the converted samples, what
     *  was dropped, and one note per resampled sample.
     *
     *  <p>The streams are indexed {@code [channel][frame]} rather than named
     *  after the two YM effect slots, because the slots are the YM format's
     *  count and the channels are the engine's: the file carries
     *  {@link YmxFormat#CHANNELS} of them and a source with more than two
     *  simultaneous streams has somewhere to put them. A YM tune fills the
     *  first two and leaves the rest idle. {@link #e1()} and its three
     *  companions stay behind under the slot names, for the packer and the
     *  reports that still think in them.
     *
     *  <p>The four drop counters never leave this package. They
     *  count frames whose effect the reference player would not have started,
     *  and each is named after the thing that was not started - a Sinus-SID,
     *  a drum with no sample - so they mean nothing to an engine that has
     *  never heard of either. A packer's report is where they belong, and a
     *  front end for a format with no dialects to normalize simply has no
     *  such report to make. */
    public record Extraction(byte[][] codes, byte[][] counts,
                             byte[][] samples, int inert, int tooFast, int sinus,
                             int missingDrum, java.util.List<String> notes) {

        /** Timer channel 0's codes: the YM format's first effect slot. */
        public byte[] e1() {
            return codes[0];
        }

        /** Timer channel 0's counts. */
        public byte[] t1() {
            return counts[0];
        }

        /** Timer channel 1's codes: the YM format's second effect slot. */
        public byte[] e2() {
            return codes[1];
        }

        /** Timer channel 1's counts. */
        public byte[] t2() {
            return counts[1];
        }

        /** The two YM slots' streams, in file order E1, T1, E2, T2. */
        public byte[][] streams() {
            return new byte[][] {e1(), t1(), e2(), t2()};
        }

        public int dropped() {
            return inert + tooFast + sinus + missingDrum;
        }
    }

    private final Ym6Reader.Song song;
    private final byte[][] samples;
    private final int[] num;            // per-sample divisor scale num/den >= 1;
    private final int[] den;            // 1/1 = the sample plays as dumped
    private final java.util.List<java.util.TreeSet<Integer>> divisors;
    private final int drumHz;
    private final java.util.List<String> notes = new java.util.ArrayList<>();
    private int inert;
    private int tooFast;
    private int sinus;
    private int missingDrum;

    private YmEffects(Ym6Reader.Song song, int drumHz) {
        this.song = song;
        this.drumHz = drumHz;
        this.samples = convertSamples(song);
        this.num = new int[samples.length];
        this.den = new int[samples.length];
        this.divisors = new java.util.ArrayList<>();
        for (int i = 0; i < samples.length; i++) {
            num[i] = 1;
            den[i] = 1;
            divisors.add(new java.util.TreeSet<>());
        }
    }

    /** A dump as the engine has it, at the standard rate ceiling. */
    public static Tune tune(Ym6Reader.Song song) {
        return tune(song, extract(song));
    }

    /**
     * A dump as the engine has it, over an extraction already made - which is
     * how a caller that needs both keeps the one it can report on.
     *
     * <p>Only the fourteen sound registers cross: R14 and R15 are the chip's
     * I/O ports, which this format borrowed as effect data and which the
     * extraction above has already read everything it needs out of. Nothing
     * downstream packs them, and carrying them would only invite something to
     * treat them as chip state. What does cross is the rest of the frame
     * UNMASKED, effect bits and all, because the script still reads a PCM
     * stream's sample number and a toggle stream's volume out of a voice's
     * volume register - see {@link EffectScript} - and the encoder masks the
     * frame streams itself on the way into the file.
     */
    public static Tune tune(Ym6Reader.Song song, Extraction fx) {
        return new Tune(song.frames(), song.playerHz(), song.masterClock(),
                (int) Math.min(song.loopFrame(), Integer.MAX_VALUE),
                java.util.Arrays.copyOf(song.registers(), YmxFormat.REGISTER_STREAMS),
                fx.codes(), fx.counts(), shapes(song, fx), fx.samples(),
                oneShot(fx.samples().length), EffectScript.Semantics.YM,
                song.name(), song.author(), song.comment(), fx.notes());
    }

    /**
     * The envelope shape a retrigger stream would restart, frame by frame,
     * as ST-Sound arrives at it.
     *
     * <p>Its {@code envShape} has two writers and the order is the whole of
     * the answer. {@code readYm6} writes R13 first, and only when the frame
     * does not carry the leave-it-alone marker; then {@code readYm6Effect}
     * runs for slot 1 and slot 2, and a sync-buzzer in either calls
     * {@code syncBuzzerStart(freq, pReg[voice+8] &amp; 15)} - unconditionally,
     * on every frame the code is present, not only where it arrives. So a
     * buzzer's own nibble overwrites R13's value, and where both slots carry
     * one the second wins by arriving last.
     *
     * <p>That YM6 files a shape on a voice at all is its own decision rather
     * than the chip's - the envelope generator is not a voice's - and it is
     * a reasonable one, since the parameter field sits at one place for all
     * three kinds and a buzzer's voice is following the envelope, which makes
     * that nibble the one byte going spare. It is measured: on
     * {@code jamblv1} R13 and the nibble disagree on hundreds of frames, and
     * the nibble is what the reference player restarts.
     */
    /** A digidrum is a hit: YM has no way to say a sample loops, and the
     * reference player's own drum tick stops at the end. */
    private static int[] oneShot(int samples) {
        int[] loops = new int[samples];
        java.util.Arrays.fill(loops, YmxFormat.SAMPLE_ONE_SHOT);
        return loops;
    }

    private static byte[] shapes(Ym6Reader.Song song, Extraction fx) {
        byte[] shapes = new byte[song.frames()];
        int shape = 0;                      // ST-Sound's reset leaves it here
        for (int frame = 0; frame < song.frames(); frame++) {
            int written = song.registers()[Ym2149.ENVELOPE_SHAPE][frame] & 0xFF;
            if (written != Ym2149.NO_ENVELOPE_CHANGE) {
                shape = written & 15;
            }
            for (int slot = 0; slot < fx.codes().length; slot++) {
                int code = fx.codes()[slot][frame] & 0xFF;
                if (code != 0 && (code & 0xC0) == KIND_RETRIGGER) {
                    int voice = ((code >> 4) & 3) - 1;
                    shape = song.registers()[8 + voice][frame] & 15;
                }
            }
            shapes[frame] = (byte) shape;
        }
        return shapes;
    }

    public static Extraction extract(Ym6Reader.Song song) {
        return extract(song, MAX_TIMER_HZ);
    }

    public static Extraction extract(Ym6Reader.Song song, int drumHz) {
        var effects = new YmEffects(song, drumHz);
        effects.downsample();
        int frames = song.frames();
        // A YM frame carries two effect slots and no more, so only the first
        // two channels are ever written here; the rest stay the all-zero
        // streams of an idle channel.
        byte[][] codes = new byte[YmxFormat.CHANNELS][frames];
        byte[][] counts = new byte[YmxFormat.CHANNELS][frames];
        boolean ym6 = song.format().startsWith("YM6");
        for (int frame = 0; frame < frames; frame++) {
            long slot1;
            long slot2;
            if (ym6) {
                slot1 = effects.validate(effects.register(1, frame) & 0xF0,
                        effects.register(6, frame) >> 5, effects.register(14, frame), frame);
                slot2 = effects.validate(effects.register(3, frame) & 0xF0,
                        effects.register(8, frame) >> 5, effects.register(15, frame), frame);
            } else {
                // YM5: R1 bits 4-5 are a SID voice, R3 bits 4-5 a drum voice
                // (the version byte is load-bearing: the same bits mean other
                // things in YM6), and a YM5 drum's prescaler always sits in R8.
                slot1 = effects.validate(KIND_TOGGLE | ((effects.register(1, frame) & 0x30)),
                        effects.register(6, frame) >> 5, effects.register(14, frame), frame);
                slot2 = effects.validate(KIND_PCM | ((effects.register(3, frame) & 0x30)),
                        effects.register(8, frame) >> 5, effects.register(15, frame), frame);
            }
            codes[0][frame] = (byte) (slot1 >> 8);
            counts[0][frame] = (byte) slot1;
            codes[1][frame] = (byte) (slot2 >> 8);
            counts[1][frame] = (byte) slot2;
        }
        return new Extraction(codes, counts, effects.samples, effects.inert,
                effects.tooFast, effects.sinus, effects.missingDrum,
                java.util.List.copyOf(effects.notes));
    }

    /**
     * Surveys every drum trigger and rescues the samples whose rate exceeds
     * the ceiling: each is resampled to the highest MFP-representable rate
     * under it, and every trigger's divisor scales by the same exact ratio.
     * When a drum's triggers cannot all take the ratio exactly, the
     * power-of-two factor is the fallback.
     */
    private void downsample() {
        boolean ym6 = song.format().startsWith("YM6");
        for (int frame = 0; frame < song.frames(); frame++) {
            surveyDrum(ym6 ? register(1, frame) & 0xF0 : 0,
                    register(6, frame) >> 5, register(14, frame), frame);
            surveyDrum(ym6 ? register(3, frame) & 0xF0
                            : (register(3, frame) & 0x30) != 0
                                    ? KIND_PCM | (register(3, frame) & 0x30) : 0,
                    register(8, frame) >> 5, register(15, frame), frame);
        }
        for (int i = 0; i < samples.length; i++) {
            if (divisors.get(i).isEmpty()) {
                continue;
            }
            int fastest = divisors.get(i).first();
            if ((long) drumHz * fastest >= Tune.MFP_CLOCK) {
                continue;               // the fastest trigger fits already
            }
            int target = ceilingDivisor();
            int g = gcd(target, fastest);
            int n = target / g;
            int d = fastest / g;
            boolean exact = true;
            for (int divisor : divisors.get(i)) {
                long scaled = (long) divisor * n;
                if (scaled % d != 0 || !representable((int) (scaled / d))) {
                    exact = false;
                    break;
                }
            }
            if (!exact) {               // the old rescue: a power of two
                n = 1;
                d = 1;
                while ((long) drumHz * fastest * n < Tune.MFP_CLOCK && n < 64) {
                    n *= 2;
                }
            }
            num[i] = n;
            den[i] = d;
            byte[] source = samples[i];
            int outLength = Math.max(1, (int) ((long) source.length * d / n));
            samples[i] = resample(source, outLength);
            notes.add("drum " + i + " resampled "
                    + Tune.MFP_CLOCK / fastest + " -> "
                    + (long) Tune.MFP_CLOCK * d / ((long) fastest * n)
                    + " Hz to fit " + drumHz + " Hz (-drumhz to change)");
        }
    }

    private void surveyDrum(int code, int prescaler, int count, int frame) {
        if ((code & 0xC0) != KIND_PCM || (code & 0x30) == 0) {
            return;
        }
        prescaler &= 7;
        count &= 0xFF;
        if (Tune.prescaler(prescaler) == 0 || count == 0) {
            return;
        }
        int number = register(8 + ((code & 0x30) >> 4) - 1, frame) & 31;
        if (number >= samples.length) {
            return;
        }
        divisors.get(number).add(Tune.prescaler(prescaler) * count);
    }

    /** The smallest MFP-representable divisor whose rate is at or under
     *  the ceiling - the fastest way to play a rescued drum. */
    private int ceilingDivisor() {
        int needed = (Tune.MFP_CLOCK + drumHz - 1) / drumHz;
        int best = Integer.MAX_VALUE;
        for (int p = 1; p < Tune.PRESCALERS; p++) {
            int count = (needed + Tune.prescaler(p) - 1) / Tune.prescaler(p);
            if (count <= 255 && Tune.prescaler(p) * count < best) {
                best = Tune.prescaler(p) * count;
            }
        }
        return best;
    }

    private static boolean representable(int divisor) {
        for (int p = 1; p < Tune.PRESCALERS; p++) {
            if (divisor % Tune.prescaler(p) == 0) {
                int count = divisor / Tune.prescaler(p);
                if (count >= 1 && count <= 255) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** The chip's volume curve, per the reference player: filtering happens
     *  in the linear domain the ear lives in, not on the register indices. */
    private static final int[] CURVE = {62, 161, 265, 377, 580, 774, 1155,
            1575, 2260, 3088, 4570, 6233, 9330, 13187, 21220, 32767};

    /**
     * Windowed-sinc resample of a volume-index sample: indices become
     * amplitudes through the chip curve, a Hann-windowed sinc low-passes at
     * the target rate's band, and the result quantizes back to the nearest
     * index. The old neighbour average folded the removed band back into
     * the audible one; a real filter removes it.
     */
    private static byte[] resample(byte[] source, int outLength) {
        final int taps = 12;
        double step = (double) source.length / outLength;
        double cutoff = Math.min(1.0, 1.0 / step);
        byte[] out = new byte[outLength];
        for (int j = 0; j < outLength; j++) {
            double center = (j + 0.5) * step - 0.5;
            int base = (int) Math.floor(center);
            double acc = 0;
            double weight = 0;
            for (int m = base - taps + 1; m <= base + taps; m++) {
                double t = (m - center) * cutoff;
                double x = (m - center) / taps;
                double w = (0.5 + 0.5 * Math.cos(Math.PI * x))
                        * (t == 0 ? 1.0 : Math.sin(Math.PI * t) / (Math.PI * t));
                int at = Math.min(source.length - 1, Math.max(0, m));
                acc += w * CURVE[source[at] & 15];
                weight += w;
            }
            double amplitude = acc / weight;
            int nearest = 0;
            for (int i = 1; i < 16; i++) {
                if (Math.abs(CURVE[i] - amplitude)
                        < Math.abs(CURVE[nearest] - amplitude)) {
                    nearest = i;
                }
            }
            out[j] = (byte) nearest;
        }
        return out;
    }

    /**
     * Fits a timer divisor into the MFP's prescaler table: the smallest
     * prescaler whose count divides exactly and fits a byte, or 0 when none
     * does.
     */
    private static long fit(int code, int divisor) {
        for (int p = 1; p < Tune.PRESCALERS; p++) {
            if (divisor % Tune.prescaler(p) == 0) {
                int count = divisor / Tune.prescaler(p);
                if (count >= 1 && count <= 255) {
                    return ((long) ((code & 0xF0) | p) << 8) | count;
                }
            }
        }
        return 0;
    }

    private int register(int register, int frame) {
        return song.registers()[register][frame] & 0xFF;
    }

    /**
     * One slot's E and T bytes packed as (E << 8) | T; zero when the slot is
     * idle or the code cannot be played.
     */
    private long validate(int code, int prescaler, int count, int frame) {
        int voiceBits = code & 0x30;
        if (voiceBits == 0) {
            return 0;                           // the slot is idle this frame
        }
        int type = code & 0xC0;
        if (type == KIND_CURVE) {
            sinus++;
            return 0;
        }
        prescaler &= 7;
        count &= 0xFF;
        if (Tune.prescaler(prescaler) == 0 || count == 0) {
            inert++;                            // the reference player's no-op
            return 0;
        }
        if (type == KIND_PCM) {
            int voice = (voiceBits >> 4) - 1;
            int number = register(8 + voice, frame) & 31;
            if (number >= samples.length) {
                missingDrum++;
                return 0;
            }
            // The drum's sample may have been resampled: every trigger
            // scales its divisor by the same exact ratio, keeping pitch
            // and duration exact. A divisor no prescaler/count pair
            // represents is dropped - the survey rules it out for the
            // ratio path, but the power-of-two fallback keeps the branch
            // correct.
            if (num[number] > den[number]) {
                long scaled = (long) Tune.prescaler(prescaler) * count * num[number]
                        / den[number];
                long fitted = fit(code, (int) scaled);
                if (fitted == 0) {
                    tooFast++;
                }
                return fitted;
            }
        }
        int hz = Tune.MFP_CLOCK / (Tune.prescaler(prescaler) * count);
        if (hz > (type == KIND_PCM ? drumHz : MAX_TIMER_HZ)) {
            tooFast++;                  // samples use their own ceiling, so
            return 0;                   // -drumhz above 25600 works too
        }
        return ((long) ((code & 0xF0) | prescaler) << 8) | count;
    }

    /**
     * The drum samples as PSG volume values 0..15, one per byte, without the
     * end markers - those belong to the file layout, not the sound.
     */
    private static byte[][] convertSamples(Ym6Reader.Song song) {
        int count = Math.min(song.digidrums(), YmxFormat.MAX_SAMPLES);
        byte[][] converted = new byte[count][];
        boolean fourBit = (song.attributes() & Ym6Reader.Song.A_DRUM4BITS) != 0;
        for (int i = 0; i < count; i++) {
            byte[] source = song.drums()[i];
            byte[] drum = new byte[source.length];
            for (int j = 0; j < source.length; j++) {
                drum[j] = (byte) (fourBit ? source[j] & 15 : (source[j] & 0xFF) >> 4);
            }
            converted[i] = drum;
        }
        return converted;
    }
}
