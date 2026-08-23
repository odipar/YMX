package org.ymx.rig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Corpus sweep for the .ymr front end: pack each RhYMe register dump and
 * verify the real player's chip writes against the .YMR truth, frame by
 * frame, in the emulator rig.
 *
 * <p>{@code ymx/test/ymr_sweep.sh song.ymr [more.ymr ...]}
 *
 * <p>Each tune is packed at k=1 - no padding frames, so the .YMR's own
 * frames are the exact expectation - then played through the real 68000
 * player under emulation, exactly as the .ym sweep plays a .ym. The truth
 * side is an INDEPENDENT model of the .YMR image, written in this file from
 * the format spec: its own ZX1 decoder, its own stream map walk, its own
 * replay of the command stream. Nothing here calls org.ymr except the
 * packer under test, so the Java reader and the 68000 player cannot cancel
 * each other's bugs out.
 *
 * <p>Checked frame by frame: R0-R6 and R11-R12 exactly, masked as a YM2149
 * masks them; R7 as the .YMR's mixer with the ST's two port bits and
 * nothing else; R13 as an event - written exactly once on a frame that
 * pops the shape, never on a held one; R8-R10 against the skips, a PWM's
 * first frame writing one zero; and the MFP claims - exactly the timers
 * the .YMR uses, never Timer C. The tick handlers' own audio is outside
 * this window: it is the directed effect test's. A long tune plays its
 * first {@code YMR_FRAME_CAP} frames (1200 unless the environment raises
 * it), and the cap is printed on the status line with the boundaries the
 * walk crossed, so a tune whose only interesting frame is past it says so
 * rather than reading OK on nothing.
 *
 * <p>One status line per tune: OK, ISSUE, PACKFAIL or SKIP; a non-zero
 * exit on any ISSUE.
 */
final class YmrSweep {

    // The bits a YM2149 keeps, what the packer masks a register down to.
    // R13 is the exception: $FF passes through as "do not write it at all".
    private static final int[] MASK = {0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F,
            0x1F, 0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F};
    private static final int NO_SHAPE = 0xFF;   // this frame popped no shape
    private static final int SHAPE_BEFORE_ANY_POP = 0x08;
    private static final int PORTS = 0xC0;      // R7 bits the ST needs as outputs

    private static final int[] PRESCALE = {0, 4, 10, 16, 50, 64, 100, 200};
    private static final int MFP_CLOCK = 2457600;

    // Effect types, as the timer_*_effect stream carries them.
    private static final int FX_NONE = 0;
    private static final int FX_PWM = 1;
    private static final int FX_SAMPLE = 2;
    private static final int FX_RTE = 3;

    // The engine's code-byte kinds, the vocabulary the skip is decided in:
    // a PWM becomes a toggle stream, a Sample a PCM stream, an RTE a
    // retrigger stream. Only the first two own a volume register.
    private static final int KIND_TOGGLE = 0x00;
    private static final int KIND_PCM = 0x40;
    private static final int KIND_RETRIGGER = 0xC0;
    private static final int TRIGGER = 0x08;    // code bit 3, flipped on
                                                // every sample trigger
    private static final int MAX_SAMPLES = 32;  // five bits of a volume register
    private static final int MAX_SAMPLE_BYTES = 65535;  // a word-sized length

    // One entry's width per stream, and the first YM register a register
    // stream's entry writes. A width-2 entry is two registers in REGISTER
    // ORDER, not a big-endian word: the spec's "all multi-byte values
    // big-endian" line is true of the header and only of the header.
    private static final int[] WIDTH = {0, 2, 2, 2, 1, 1, 1, 1, 1, 2, 1,
            1, 2, 1, 1, 2, 1, 1, 2, 1};
    private static final int[] FIRST_REGISTER = {-1, 0, 2, 4, 6, 7, 8, 9,
            10, 11, 13};
    private static final int STREAM_COUNT = 20;
    private static final int LAST_REGISTER_STREAM = 10;
    private static final int FIRST_TIMER_STREAM = 11;
    private static final int HEADER_SIZE = 28 + STREAM_COUNT * 12;

    private YmrSweep() {}

    /** Anything about the .YMR image this file will not read. */
    static final class Malformed extends RuntimeException {
        Malformed(String reason) {
            super(reason);
        }
    }

    // ------------------------------------------------------------------ ZX1

    /**
     * One ZX1 stream out of a .YMR image, decoded through its own ring.
     * Written here rather than borrowed, so the Java reader has something
     * to be checked against. ZX1 alternates literal runs and matches: a run
     * length is an interlaced Elias gamma code, a match either repeats the
     * last offset or carries a new one, and a non-positive offset is the
     * end marker. The ring is both the window and the output queue, so a
     * back-reference resolves modulo its size. A ring of 0 is a different
     * thing entirely: the stream is stored uncompressed and its bytes are
     * the data.
     */
    static byte[] zx1(byte[] image, int at, int length, int ring) {
        if (ring == 0) {
            byte[] stored = new byte[length];
            System.arraycopy(image, at, stored, 0, length);
            return stored;
        }
        byte[] source = new byte[length];
        System.arraycopy(image, at, source, 0, length);
        var out = new java.io.ByteArrayOutputStream();
        byte[] window = new byte[ring];
        int[] state = {0, 0, 0, 0};     // atIn, mask, bits, atWin

        final class Codes {
            int nextByte() {
                if (state[0] >= source.length) {
                    throw new Malformed("the stream ends mid-operation");
                }
                return source[state[0]++] & 0xFF;
            }

            int bit() {
                state[1] >>= 1;
                if (state[1] == 0) {
                    state[1] = 128;
                    state[2] = nextByte();
                }
                return (state[2] & state[1]) != 0 ? 1 : 0;
            }

            int gamma() {
                int value = 1;
                while (bit() != 0) {
                    value = value * 2 + bit();
                }
                return value;
            }

            void emit(int value) {
                out.write(value);
                window[state[3]] = (byte) value;
                state[3] = (state[3] + 1) % ring;
            }
        }
        Codes codes = new Codes();

        int offset = 1;                 // the distance a stream starts at
        boolean literals = true;
        int lengthLeft = codes.gamma();
        while (true) {
            for (int i = 0; i < lengthLeft; i++) {
                codes.emit(literals ? codes.nextByte()
                        : window[Math.floorMod(state[3] - offset, ring)] & 0xFF);
            }
            if (codes.bit() != 0) {     // a match from a new offset, or the end
                int first = codes.nextByte();
                if ((first & 1) != 0) {
                    int second = codes.nextByte();
                    offset = 32512 - (second & 254) * 128 - (first & 254)
                            - (second & 1);
                } else {
                    offset = 128 - first / 2;
                }
                if (offset <= 0) {
                    if (state[0] != source.length) {
                        throw new Malformed(
                                "the end marker lands before the stream does");
                    }
                    return out.toByteArray();
                }
                if (offset > ring) {
                    throw new Malformed("a match reaches back further than the "
                            + ring + "-byte ring");
                }
                if (offset > out.size()) {
                    // Past the stream's own first byte, into whatever the
                    // ring happened to hold. Nothing distinguishes those
                    // bytes once copied out, so the reach is refused here.
                    throw new Malformed(
                            "a match reaches back for bytes the stream never wrote");
                }
                literals = false;
                lengthLeft = codes.gamma() + 1;
            } else if (literals) {      // a match from the offset in hand
                literals = false;
                lengthLeft = codes.gamma();
            } else {
                literals = true;
                lengthLeft = codes.gamma();
            }
        }
    }

    // ------------------------------------------------------ the .YMR image

    /** One frame of one timer: what it should be doing, and which of its
     * three streams said so this frame. The flags are the events and are
     * not the same information as the values - popping timer_*_sample
     * restarts the sample even when the index has not moved, and popping
     * timer_*_effect with 0 stops the timer even though an idle timer
     * already held 0. */
    record TimerFrame(int effect, int prescaler, int counter, int sample,
            boolean effectPop, boolean ratePop, boolean samplePop) {}

    /**
     * A .YMR v1.3 register dump, replayed onto the flat per-frame view. A
     * .YMR stores no frames: everything a frame can change lives in a
     * stream, a stream holds one entry per change, and a separate command
     * stream lists for every frame the streams to pop. No frame can be
     * reached by index, so the only way in is to replay the command stream
     * from the start, once, and write down what the chip held on every
     * frame as it goes.
     */
    static final class Ymr {
        final int frames;
        final int rate;
        final int loop;
        final int[][] registers = new int[14][];
        final TimerFrame[][] timers = new TimerFrame[3][];
        final int[][] codes = new int[3][];
        final int[][] window = new int[3][];
        int used;                       // the channels that ever act
        int triggers;
        private final byte[] image;
        private final int[][] map = new int[STREAM_COUNT][];
        private final byte[][] entries = new byte[STREAM_COUNT][];
        private final int[] samples;    // playing lengths; -1 = never stops

        Ymr(byte[] image) {
            this.image = image;
            if (image.length < 4 || image[0] != 'Y' || image[1] != 'M'
                    || image[2] != 'R' || image[3] != '!') {
                throw new Malformed("not a .YMR image");
            }
            int version = word(4);
            if (version != 0x0103) {
                throw new Malformed(".YMR version " + (version >> 8) + "."
                        + (version & 0xFF) + ", not the 1.3 this reads");
            }
            frames = longWord(6);
            int loopField = longWord(10);
            rate = word(14);
            int sampleCount = word(16);
            int streams = word(22);
            if (streams != STREAM_COUNT) {
                throw new Malformed("the stream map has " + streams
                        + " entries, not " + STREAM_COUNT);
            }
            if (frames <= 0) {
                throw new Malformed("unusable frame count " + frames);
            }
            loop = loopField == -1 ? -1 : loopField;
            if (loop >= frames) {
                throw new Malformed("loop frame " + loop + " is past the "
                        + frames + " frames");
            }
            for (int s = 0; s < STREAM_COUNT; s++) {
                map[s] = new int[] {longWord(28 + 12 * s), longWord(32 + 12 * s),
                        word(36 + 12 * s), word(38 + 12 * s)};
            }
            samples = readSamples(sampleCount);
            for (int s = 0; s < STREAM_COUNT; s++) {
                entries[s] = decode(s);
            }
            if (map[0][0] == 0) {
                throw new Malformed(
                        "no command stream: nothing says what any frame pops");
            }
            replay();
            walk();
        }

        private int word(int at) {
            return ((image[at] & 0xFF) << 8) | (image[at + 1] & 0xFF);
        }

        private int longWord(int at) {
            return (word(at) << 16) | word(at + 2);
        }

        /** The sample blocks, between the map and the streams: a size, that
         * many pre-converted 4-bit levels, then a four-byte trailer. The
         * value kept is how many bytes a PCM stream plays before it stops,
         * or -1 for a looped block, which plays until something else takes
         * the timer. */
        private int[] readSamples(int count) {
            int at = HEADER_SIZE;
            List<Integer> playing = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int size = longWord(at);
                at += 4;
                if (size > image.length - at - 4) {
                    throw new Malformed("sample " + index + " claims " + size
                            + " bytes past the file");
                }
                int dataLength = Math.min(size, MAX_SAMPLE_BYTES);
                boolean looped = (image[at + size] & 1) != 0;
                int start = word(at + size + 1);
                at += size + 4;
                if (playing.size() < MAX_SAMPLES) {
                    playing.add(looped && start < dataLength ? -1 : dataLength);
                }
            }
            int[] lengths = new int[playing.size()];
            for (int i = 0; i < lengths.length; i++) {
                lengths[i] = playing.get(i);
            }
            return lengths;
        }

        /** A stream's stored length is the distance to the next present
         * stream's offset, and the last present one runs to the end of the
         * file: the streams are written in map order, so nothing in the
         * file stores a size. */
        private byte[] decode(int stream) {
            int offset = map[stream][0];
            int ring = map[stream][2];
            if (offset == 0) {
                return new byte[0];     // not in the file: never popped
            }
            int end = image.length;
            for (int later = stream + 1; later < STREAM_COUNT; later++) {
                if (map[later][0] != 0) {
                    end = map[later][0];
                    break;
                }
            }
            if (end < offset) {
                throw new Malformed("stream " + stream
                        + " starts after the stream that follows it");
            }
            try {
                return zx1(image, offset, end - offset, ring);
            } catch (Malformed problem) {
                throw new Malformed("stream " + stream + ": "
                        + problem.getMessage());
            }
        }

        /** The command stream, one byte per command: $00 ends the frame,
         * $01-$BF pops the stream with that index, and $C0 upwards is
         * reserved - a future command would define for itself how many
         * bytes follow it, so meeting one is a stop rather than something
         * to step over. */
        private void replay() {
            for (int register = 0; register < 14; register++) {
                registers[register] = new int[frames];
            }
            for (int timer = 0; timer < 3; timer++) {
                timers[timer] = new TimerFrame[frames];
            }
            int[] cursor = new int[STREAM_COUNT];
            int[] held = new int[14];
            int[] effect = new int[3];
            int[] prescaler = new int[3];
            int[] counter = new int[3];
            int[] sample = new int[3];
            boolean[][] popped = new boolean[3][3];
            boolean shapePopped = false;
            int frame = 0;
            for (byte raw : entries[0]) {
                int command = raw & 0xFF;
                if (command == 0) {
                    if (frame == frames) {
                        throw new Malformed("more end-of-frame bytes than the"
                                + " header asks for");
                    }
                    for (int register = 0; register < 14; register++) {
                        registers[register][frame] = held[register];
                    }
                    registers[13][frame] = shapePopped ? held[13] : NO_SHAPE;
                    shapePopped = false;
                    for (int timer = 0; timer < 3; timer++) {
                        timers[timer][frame] = new TimerFrame(effect[timer],
                                prescaler[timer], counter[timer], sample[timer],
                                popped[timer][0], popped[timer][1],
                                popped[timer][2]);
                        popped[timer] = new boolean[3];
                    }
                    frame++;
                    continue;
                }
                if (command >= 0xC0) {
                    throw new Malformed(String.format(
                            "frame %d carries reserved command $%02X",
                            frame, command));
                }
                if (command >= STREAM_COUNT) {
                    throw new Malformed("frame " + frame + " pops stream "
                            + command + ", past the map");
                }
                int width = WIDTH[command];
                int at = cursor[command];
                byte[] entry = entries[command];
                if (width > entry.length - at) {
                    throw new Malformed("frame " + frame + " pops stream "
                            + command + ", which has nothing left");
                }
                cursor[command] = at + width;
                if (command <= LAST_REGISTER_STREAM) {
                    int first = FIRST_REGISTER[command];
                    for (int i = 0; i < width; i++) {
                        held[first + i] = entry[at + i] & 0xFF;
                    }
                    shapePopped |= command == LAST_REGISTER_STREAM;
                    continue;
                }
                int timer = (command - FIRST_TIMER_STREAM) / 3;
                int which = (command - FIRST_TIMER_STREAM) % 3;
                popped[timer][which] = true;
                if (which == 0) {
                    effect[timer] = entry[at] & 0xFF;
                } else if (which == 1) {
                    prescaler[timer] = entry[at] & 0xFF;
                    counter[timer] = entry[at + 1] & 0xFF;
                } else {
                    sample[timer] = entry[at] & 0xFF;
                }
            }
            if (frame != frames) {
                throw new Malformed("the command stream holds " + frame
                        + " end-of-frame bytes, not " + frames);
            }
        }

        /**
         * What every dump frame asks of each timer, as one code byte. This
         * is the dump timeline: a code byte is packed against the frame it
         * belongs to, and comes round again wherever the played timeline
         * shows that frame again. What the effect stage MAKES of a run of
         * code bytes is the played timeline's business, in {@link Stage}.
         *
         * <p>A timer that never ticks owns nothing, and three
         * configurations never tick: a reserved effect type, a prescaler
         * index of 0 or a counter of 0, and a Sample pointing at a block
         * the file does not carry. A sample that has played out gives its
         * register back as well, so the window is recomputed at every
         * trigger.
         */
        private void walk() {
            for (int channel = 0; channel < 3; channel++) {
                codes[channel] = new int[frames];
                window[channel] = new int[frames];
            }
            for (int channel = 0; channel < 3; channel++) {
                channel(channel);
            }
        }

        private void channel(int channel) {
            int running = FX_NONE;
            int trigger = 0;
            int armedTo = 0;
            int last = 0;
            for (int frame = 0; frame < frames; frame++) {
                TimerFrame want = timers[channel][frame];
                boolean configure = false;
                if (want.effectPop()) {
                    if (want.effect() == FX_NONE) {
                        running = FX_NONE;
                    } else {
                        configure = true;
                    }
                } else if (running != FX_NONE && want.samplePop()) {
                    configure = true;
                }
                boolean started = false;
                if (configure) {
                    running = want.effect();
                    started = running == FX_SAMPLE;
                    if (started) {
                        trigger ^= TRIGGER;
                        triggers++;
                    }
                }
                int code = code(channel, running, want, trigger, started,
                        frame, armedTo);
                if (code != 0 && (code & 0xC0) == KIND_PCM) {
                    // Every armed frame carries the window its rate would
                    // give a sample starting there, not only the frame a
                    // code changes on: the effect stage arms on the frame a
                    // code CHANGES, and the arming frame is the one whose
                    // rate the window is measured at.
                    window[channel][frame] = armed(want);
                    if (code != last) {
                        armedTo = frame + window[channel][frame];
                    }
                }
                last = code;
                codes[channel][frame] = code;
                if (code != 0) {
                    used |= 1 << channel;
                }
            }
        }

        /** The code byte a frame hands the effect stage, or 0 for a channel
         * with nothing to run. The trigger bit makes two pops of one sample
         * at one rate two different codes, which is how an explicit
         * re-trigger reaches a stage that acts on a code that CHANGED. */
        private int code(int channel, int running, TimerFrame want, int trigger,
                boolean started, int frame, int armedTo) {
            int kind = switch (running) {
                case FX_PWM -> KIND_TOGGLE;
                case FX_SAMPLE -> KIND_PCM;
                case FX_RTE -> KIND_RETRIGGER;
                default -> -1;          // idle, or a type the format reserves
            };
            if (kind < 0) {
                return 0;
            }
            if (PRESCALE[want.prescaler() & 7] == 0 || want.counter() == 0) {
                return 0;               // prescaler 0 stops it; counter 0 is
            }                           // dropped
            int head = kind | ((channel + 1) << 4) | (want.prescaler() & 7);
            if (kind != KIND_PCM) {
                return head;
            }
            if (want.sample() >= samples.length) {
                return 0;               // no block behind it: nothing plays
            }
            return started || frame < armedTo ? head | trigger : 0;
        }

        /** How many frames a sample armed with this rate stays armed for:
         * the sample plus its end marker at the timer's rate, plus a
         * sixteenth of a frame for the arming phase, rounded up so the skip
         * never lifts early. Getting the frame wrong here would read as the
         * player writing a skipped register. */
        private int armed(TimerFrame want) {
            if (samples[want.sample()] < 0) {
                return 1 << 30;         // a looped sample: the skip never
            }                           // reopens on its own
            long ticks = samples[want.sample()] + 1;
            long divisor = (long) PRESCALE[want.prescaler() & 7] * want.counter();
            long scaled = ticks * divisor * rate + MFP_CLOCK / 16;
            return (int) ((scaled + MFP_CLOCK - 1) / MFP_CLOCK);
        }

        /** The dump's own shape on each frame: the last popped, or the
         * assumed 8 before the first pop. */
        int shape(int frame) {
            int shape = SHAPE_BEFORE_ANY_POP;
            for (int f = 0; f <= frame; f++) {
                if (registers[13][f] != NO_SHAPE) {
                    shape = registers[13][f];
                }
            }
            return shape & 15;
        }
    }

    // -------------------------------------------------------- the stage

    /**
     * The effect stage, replayed frame by frame. The skip is state, not a
     * property of a frame: what a code byte does depends on the code before
     * it, so this steps once per played frame, given the dump frame that
     * frame shows, the way the compiler does. A .YMR binds each timer to
     * one voice - A to A, B to B, D to C, and the binding is normative -
     * so no two channels ever contend for one voice and every branch below
     * reads only its own voice.
     */
    static final class Stage {
        private final Ymr dump;
        private int played;
        private final int[] last = new int[3];  // the code each channel ran
        private final int[] owner = {-1, -1, -1};   // the channel a voice's
        private final int[] end = {-1, -1, -1};     // sample belongs to, and
        private int skips;                          // the played frame its
                                                    // window closes on

        Stage(Ymr dump) {
            this.dump = dump;
        }

        /** The tune starts over, so nothing is running from its end: the
         * state the compiler began from, which the player puts the machine
         * back into on the frame that ends the tune. */
        void restart() {
            for (int i = 0; i < 3; i++) {
                last[i] = 0;
                owner[i] = -1;
                end[i] = -1;
            }
            skips = 0;
        }

        /** Advances one played frame showing dump frame {@code frame};
         * returns {skipped, buzzing, started}, each a voice mask. */
        int[] step(int frame) {
            int now = played++;
            for (int voice = 0; voice < 3; voice++) {
                if (owner[voice] >= 0 && end[voice] == now) {
                    owner[voice] = -1;  // the marker tick has run by now
                    end[voice] = -1;
                    skips &= ~(1 << voice);
                }
            }
            int started = 0;
            int buzzing = 0;
            for (int channel = 0; channel < 3; channel++) {
                int code = dump.codes[channel][frame];
                if (code != 0 && (code & 0xC0) == KIND_RETRIGGER) {
                    buzzing |= 1 << channel;
                }
                int old = last[channel];
                last[channel] = code;
                if (code == old) {
                    continue;           // held: a .YMR's trigger is a pop, so
                }                       // nothing re-fires on a repeated code
                int voice = channel;
                if (code == 0) {
                    // A .YMR can say stop, and every command that does
                    // programs the one timer the sample was ticking on, so a
                    // sample ends on the frame it is stopped rather than at
                    // a marker it will now never reach - and the voice's
                    // volume comes back out of this same frame's burst.
                    drop(channel, voice);
                    if ((old & 0xC0) == KIND_TOGGLE && old != 0) {
                        skips &= ~(1 << voice);
                    }
                    continue;
                }
                int kind = code & 0xC0;
                if (kind == KIND_RETRIGGER) {
                    drop(channel, voice);
                    continue;           // a buzzer writes R13, never a volume
                }
                if (kind == KIND_TOGGLE) {
                    // The sample this channel was playing ends here too, and
                    // the skip stands: the square requires it as well.
                    if (owner[voice] == channel) {
                        owner[voice] = -1;
                        end[voice] = -1;
                    }
                    // A start and a retune differ in the top nibble of the
                    // code - the kind and the voice - and only the start
                    // touches the chip.
                    if (old == 0 || ((code ^ old) & 0xF0) != 0) {
                        started |= 1 << voice;
                    }
                } else {
                    owner[voice] = channel;
                    end[voice] = now + dump.window[channel][frame];
                }
                skips |= 1 << voice;
            }
            return new int[] {skips, buzzing, started};
        }

        /** The sample this channel still owns, ended because the channel
         * was told to do something else: its skip lifts on this frame. */
        private void drop(int channel, int voice) {
            if (owner[voice] == channel) {
                owner[voice] = -1;
                end[voice] = -1;
                skips &= ~(1 << voice);
            }
        }
    }

    // ---------------------------------------------------------------- MFP

    // One row per MFP timer, in A B C D order: control, data, the interrupt
    // enable and mask registers its bit lives in, and that bit. Timers C
    // and D share TCDCR - C in the high nibble, D in the low - which is why
    // a claim is checked as a nibble rather than as a whole byte.
    private static final long[][] TIMERS = {
            {0xFFFFFA19L, 0xFFFFFA1FL, 0xFFFFFA07L, 0xFFFFFA13L, 5},
            {0xFFFFFA1BL, 0xFFFFFA21L, 0xFFFFFA07L, 0xFFFFFA13L, 0},
            {0xFFFFFA1DL, 0xFFFFFA23L, 0xFFFFFA09L, 0xFFFFFA15L, 5},
            {0xFFFFFA1DL, 0xFFFFFA25L, 0xFFFFFA09L, 0xFFFFFA15L, 4}};

    // The spec's normative binding, as the T stream carries it: channel 0
    // runs on Timer A, 1 on B, 2 on D, and the fourth channel no .YMR fills
    // takes the leftover Timer C.
    private static final int[] CHANNEL_TIMER = {0, 1, 3, 2};
    private static final char[] TIMER_NAMES = {'A', 'B', 'C', 'D'};

    private static final long TCDCR = 0xFFFFFA1DL;
    private static final long TCDR = 0xFFFFFA23L;

    // The interrupt registers each timer's bit lives in, by group, in the
    // same A B C D order: A and B share the A group, C and D the B group. A
    // player may touch the enable, the pending, the in-service and the mask
    // register of a group it has a timer in, and nothing else in the MFP's
    // page.
    private static final long[][] INTERRUPT = {
            {0xFFFFFA07L, 0xFFFFFA0BL, 0xFFFFFA0FL, 0xFFFFFA13L},
            {0xFFFFFA07L, 0xFFFFFA0BL, 0xFFFFFA0FL, 0xFFFFFA13L},
            {0xFFFFFA09L, 0xFFFFFA0DL, 0xFFFFFA11L, 0xFFFFFA15L},
            {0xFFFFFA09L, 0xFFFFFA0DL, 0xFFFFFA11L, 0xFFFFFA15L}};

    /** What the MFP writes say about who claimed which timer. Timer C is
     * the one that must stay untouched whatever the tune does: the
     * operating system's own 200 Hz clock, reserved by the format, mapped
     * to the fourth channel no .YMR fills. Its control bits share a byte
     * with Timer D's, so the check is that every write to that byte leaves
     * the high nibble alone, and that its data register is never written. */
    static String mfpProblem(List<Player.Write> writes, int used) {
        java.util.Set<Long> allowed = new java.util.HashSet<>();
        for (int channel = 0; channel < 3; channel++) {
            if ((used & (1 << channel)) != 0) {
                long[] row = TIMERS[CHANNEL_TIMER[channel]];
                allowed.add(row[0]);
                allowed.add(row[1]);
                for (long register : INTERRUPT[CHANNEL_TIMER[channel]]) {
                    allowed.add(register);
                }
            }
        }
        Map<Long, Integer> seen = new HashMap<>();  // address -> bits ever set
        for (Player.Write write : writes) {
            seen.merge(write.address(), write.value(), (a, b) -> a | b);
            if (write.address() == TCDR) {
                return "wrote Timer C's data register";
            }
            if (write.address() == TCDCR && (write.value() & 0xF0) != 0) {
                return String.format("programmed Timer C in TCDCR (%#04x)",
                        write.value());
            }
            if (!allowed.contains(write.address())) {
                return String.format("wrote %#010x, which no timer this tune"
                        + " uses owns", write.address());
            }
        }
        for (int channel = 0; channel < 4; channel++) {
            long[] row = TIMERS[CHANNEL_TIMER[channel]];
            char timerName = TIMER_NAMES[CHANNEL_TIMER[channel]];
            long data = row[1];
            long enable = row[2];
            long unmask = row[3];
            int bit = (int) row[4];
            boolean claimed = channel < 3 && (used & (1 << channel)) != 0;
            long[][] checks = {{enable, 0}, {unmask, 1}};
            for (long[] check : checks) {
                Integer bits = seen.get(check[0]);
                boolean live = bits != null && (bits & (1 << bit)) != 0;
                String what = check[1] == 0 ? "enabled" : "unmasked";
                if (claimed && !live) {
                    return "never " + what + " Timer " + timerName
                            + ", which channel " + channel + " uses";
                }
                if (!claimed && live) {
                    return what + " Timer " + timerName + " for channel "
                            + channel + ", which the tune never uses";
                }
            }
            if (!claimed && seen.containsKey(data)) {
                return "wrote Timer " + timerName
                        + "'s data register for an idle channel";
            }
        }
        return "";
    }

    // ------------------------------------------------------------ the sweep

    /** How far the walk goes into a long tune: the same 1200 frames the .ym
     * sweep plays. Raising it is the only way to reach the wrap -
     * {@code YMR_FRAME_CAP=11000 ymx/test/ymr_sweep.sh song.ymr} is a whole
     * pass of a four-minute tune. */
    private static int frameCap() {
        String cap = System.getenv("YMR_FRAME_CAP");
        return cap == null ? 1200 : Integer.parseInt(cap);
    }

    static String sweep(Path path) {
        String name = String.valueOf(path.getFileName());
        Ymr dump;
        try {
            dump = new Ymr(Files.readAllBytes(path));
        } catch (Malformed problem) {
            return "SKIP " + name + ": " + problem.getMessage();
        } catch (IOException problem) {
            return "SKIP " + name + ": " + problem;
        } catch (IndexOutOfBoundsException problem) {
            // A field or a block that runs off the end of the image; the
            // parser checks what it can name, and this is the rest.
            return "SKIP " + name + ": truncated .YMR image";
        }

        Path ymx;
        try {
            ymx = Files.createTempFile("ymr_sweep", ".ymx");
        } catch (IOException e) {
            return "SKIP " + name + ": " + e;
        }
        try {
            // -k1 so the packer inserts no padding frames: every played
            // frame is a frame the .YMR carries, and the expectation is
            // exact.
            Rig.Finished packed = Rig.tryRun(List.of("java", "-ea", "-cp",
                    Rig.CLASSES.toString(), "org.ymr.Ymr", "-f", "-k1",
                    path.toAbsolutePath().toString(), ymx.toString()));
            if (packed.code() != 0) {
                String[] lines = packed.output().strip().split("\n");
                return "PACKFAIL " + name + ": " + lines[lines.length - 1];
            }
            List<String> warns = new ArrayList<>();
            for (String line : packed.output().replace("\r", "\n").split("\n")) {
                if (line.startsWith("Warning")) {
                    warns.add(line);
                }
            }
            return play(name, dump, Files.readAllBytes(ymx), warns);
        } catch (IOException | IllegalStateException problem) {
            return "ISSUE " + name + ": " + problem.getMessage();
        } finally {
            try {
                Files.deleteIfExists(ymx);
            } catch (IOException e) {
                // the temp directory's own business
            }
        }
    }

    /** Runs the packed tune through the rig and compares every frame. Its
     * length and whether it starts over come from the PACKED FILE's header
     * - O at offset 8, the flags at 6 - because the header is the contract
     * the player itself reads. */
    private static String play(String name, Ymr dump, byte[] packed,
            List<String> warns) {
        int flags = ((packed[6] & 0xFF) << 8) | (packed[7] & 0xFF);
        int played = ((packed[8] & 0xFF) << 24) | ((packed[9] & 0xFF) << 16)
                | ((packed[10] & 0xFF) << 8) | (packed[11] & 0xFF);
        int ring = ((packed[16] & 0xFF) << 8) | (packed[17] & 0xFF);
        boolean loops = (flags & 1) != 0;
        if (((flags >> 1) & 15) != dump.used) {
            return String.format("ISSUE %s: the header marks timer channels"
                    + " %#x, the dump uses %#x", name, (flags >> 1) & 15,
                    dump.used);
        }

        Player player = new Player(packed, Rig.workspaceSize(ring));
        if (player.init() != 0) {
            return "INITFAIL " + name;
        }
        String problem = mfpProblem(player.mfp, dump.used);
        if (!problem.isEmpty()) {
            return "ISSUE " + name + ": YMX_init " + problem;
        }
        List<Player.Write> claim = new ArrayList<>(player.mfp);

        // The same budget the .ym sweep plays: a short tune goes right
        // round and out the other side, a long one plays its first
        // FRAME_CAP frames.
        int cap = frameCap();
        int budget = played <= 3000 ? played + 200 : cap;
        Stage stage = new Stage(dump);
        boolean wrapped = false;
        int walked = 0;
        // What the walk actually got to see, so a cap that crossed nothing
        // interesting says so on its own status line rather than reading OK.
        int edges = 0;
        int pops = 0;
        int buzzers = 0;
        int starts = 0;
        int wasSkipped = 0;
        for (int frame = 0; frame < budget; frame++) {
            int source = frame % played;    // the same frames, over and over
            if (frame != 0 && source == 0) {
                stage.restart();        // the player silenced everything
            }
            Player.Frame result = player.frame();
            if (result.result() == -1) {
                return "ISSUE " + name + ": ended early at frame " + frame
                        + "/" + played;
            }
            if (result.result() == 1) {
                wrapped = true;
            }
            int[] masks = stage.step(source);
            problem = compare(dump, frame, source, result.writes(), masks[0],
                    masks[2]);
            if (!problem.isEmpty()) {
                return "ISSUE " + name + ": " + problem;
            }
            List<Player.Write> all = new ArrayList<>(claim);
            all.addAll(player.mfp);
            problem = mfpProblem(all, dump.used);
            if (!problem.isEmpty()) {
                return "ISSUE " + name + ": frame " + frame + " " + problem;
            }
            edges += Integer.bitCount(masks[0] ^ wasSkipped);
            wasSkipped = masks[0];
            pops += dump.registers[13][source] != NO_SHAPE ? 1 : 0;
            buzzers += masks[1] != 0 ? 1 : 0;
            starts += Integer.bitCount(masks[2]);
            walked = frame + 1;
            if (!loops && walked == played) {
                break;
            }
        }

        StringBuilder timers = new StringBuilder();
        for (int channel = 0; channel < 3; channel++) {
            if ((dump.used & (1 << channel)) != 0) {
                timers.append(TIMER_NAMES[CHANNEL_TIMER[channel]]);
            }
        }
        String where = wrapped ? "started over"
                : walked < played ? "partial" : "once";
        String crossings = String.format(
                "%d skip edge%s, %d PWM start%s, %d buzzer frame%s, %d shape pop%s",
                edges, edges == 1 ? "" : "s", starts, starts == 1 ? "" : "s",
                buzzers, buzzers == 1 ? "" : "s", pops, pops == 1 ? "" : "s");
        String extra = warns.isEmpty() ? ""
                : " [" + String.join("; ", warns) + "]";
        return String.format("OK %s (%df of %d played, cap %d, %s; timers %s;"
                + " %s; %d sample trigger%s in the whole dump)%s",
                name, walked, played, cap, where,
                timers.isEmpty() ? "none" : timers, crossings, dump.triggers,
                dump.triggers == 1 ? "" : "s", extra);
    }

    /** One frame's chip writes against the .YMR's own frame, with the
     * effect stage's verdict on the three volume registers. */
    private static String compare(Ymr dump, int frame, int source,
            List<Player.Pair> writes, int skipped, int started) {
        Map<Integer, Integer> counted = new HashMap<>();
        Map<Integer, Integer> got = new HashMap<>();
        for (Player.Pair pair : writes) {
            if (pair.register() > 13) {
                return "frame " + frame + " wrote R" + pair.register()
                        + ", which is an I/O port";
            }
            counted.merge(pair.register(), 1, Integer::sum);
            got.put(pair.register(), pair.value());
        }

        // The periods, the noise and the envelope period: the burst writes
        // every one of them every frame, so a missing or repeated write is
        // as wrong as a wrong value.
        for (int register : new int[] {0, 1, 2, 3, 4, 5, 6, 11, 12}) {
            int want = dump.registers[register][source] & MASK[register];
            if (counted.getOrDefault(register, 0) != 1) {
                return "frame " + frame + " wrote R" + register + " "
                        + counted.getOrDefault(register, 0) + " times";
            }
            Integer value = got.get(register);
            if (value == null || value != want) {
                return "frame " + frame + " R" + register + " wrote "
                        + value + ", want " + want;
            }
        }

        // R7 is the mixer plus the ST's port directions and nothing else:
        // the .ymr front end runs with the forced mixer off - RhYMe's
        // engine bakes the mixer a sample needs into the exported mixer
        // stream - so a bit here that the .YMR did not ask for is a bit
        // nobody can account for.
        int want7 = (dump.registers[7][source] & MASK[7]) | PORTS;
        if (counted.getOrDefault(7, 0) != 1) {
            return "frame " + frame + " wrote R7 "
                    + counted.getOrDefault(7, 0) + " times";
        }
        int got7 = got.getOrDefault(7, -1);
        if (got7 != want7) {
            int unexplained = got7 & ~want7 & 0xFF;
            return String.format("frame %d R7 wrote %#04x, want %#04x%s",
                    frame, got7, want7, unexplained != 0
                            ? String.format(" (unexplained bits %#04x)",
                                    unexplained) : "");
        }

        // The volumes, against the skips. A skipped voice's register must
        // be absent from the frame's writes - the player mutes the burst
        // write - and an open one must be exact.
        for (int voice = 0; voice < 3; voice++) {
            int register = 8 + voice;
            if ((started & (1 << voice)) != 0) {
                // A fresh square starts silent, and that write comes from
                // the start action rather than from the burst: through the
                // closed skipped write, exactly once, carrying zero.
                Integer wrote = got.get(register);
                if (counted.getOrDefault(register, 0) != 1
                        || wrote == null || wrote != 0) {
                    return "frame " + frame + " started a PWM on voice "
                            + "ABC".charAt(voice) + " and wrote R" + register
                            + " " + wrote + ", want one write of 0";
                }
                continue;
            }
            if ((skipped & (1 << voice)) != 0) {
                if (counted.containsKey(register)) {
                    return "frame " + frame + " wrote R" + register
                            + " that a skip covers (voice "
                            + "ABC".charAt(voice)
                            + " is running a PWM or a sample)";
                }
                continue;
            }
            if (counted.getOrDefault(register, 0) != 1) {
                return "frame " + frame + " wrote R" + register + " "
                        + counted.getOrDefault(register, 0) + " times";
            }
            int open = dump.registers[register][source] & MASK[register];
            // A buzzing voice is no special case: an RTE drives R13 and
            // never the volume register, and the shape travels in the
            // script, so the byte is the dump's own and is compared whole.
            Integer wrote = got.get(register);
            if (wrote == null || wrote != open) {
                return "frame " + frame + " R" + register + " wrote "
                        + wrote + ", want " + open;
            }
        }

        // R13 is the one register a frame may decline to write, and the
        // write is an event in its own right: it restarts the hardware
        // envelope, so a frame that did not pop the shape must not write it
        // even with the value it already holds, and a frame that did must
        // write it exactly once.
        int shape = dump.registers[13][source];
        if (shape == NO_SHAPE) {
            if (counted.containsKey(13)) {
                return "frame " + frame + " wrote R13 (" + got.get(13)
                        + ") on a frame that popped no shape";
            }
        } else if (counted.getOrDefault(13, 0) != 1) {
            return "frame " + frame + " wrote R13 "
                    + counted.getOrDefault(13, 0) + " times, want once";
        } else if (got.getOrDefault(13, -1) != (shape & MASK[13])) {
            return "frame " + frame + " R13 wrote " + got.get(13) + ", want "
                    + (shape & MASK[13]);
        }
        return "";
    }

    public static void main(String[] args) {
        List<Path> tunes = new ArrayList<>();
        for (String tune : args) {
            tunes.add(Path.of(tune));
        }
        if (tunes.isEmpty()) {
            tunes.add(Rig.REPO.resolve("ymr").resolve("test")
                    .resolve("deeper.ymr"));
        }
        int failed = 0;
        for (Path tune : tunes) {
            String line = sweep(tune);
            if (line.startsWith("ISSUE") || line.startsWith("PACKFAIL")
                    || line.startsWith("INITFAIL")) {
                failed = 1;
            }
            System.out.println(line);
        }
        System.exit(failed);
    }
}
