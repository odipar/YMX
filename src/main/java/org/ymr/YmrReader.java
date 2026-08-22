package org.ymr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads a RhYMe .YMR v1.3 register dump and replays its command stream onto the
 * played timeline.
 *
 * <p>This class is the boundary. Everything here speaks .YMR's own language -
 * stream, pop, command stream, timer, prescaler, counter, Sample - because
 * those are the names of the bytes it is reading, and calling them anything
 * else would misdescribe the file. The engine's vocabulary starts downstream,
 * at the {@link org.ymx.Tune} {@link YmrEffects} builds out of this;
 * {@code doc/terminology.md} maps the two.
 *
 * <p>A .YMR stores no frames. Everything a frame can change lives in a STREAM,
 * and a stream holds one entry per change rather than one per frame; a separate
 * COMMAND STREAM lists, for every frame, the streams to POP, and popping a
 * stream is what applies its next entry. A held note, a static mixer and an
 * idle timer therefore cost nothing at all after the frame they arrive on. What
 * that costs is random access: no frame can be reached by index, because where
 * each stream stands depends on every pop before it, and because a compressed
 * stream can only be read forwards. Replaying the command stream from the start
 * is the only way in. A player is unaffected - it runs forward - but a
 * converter needs the whole picture, so this reader replays it once and hands
 * back the flat per-frame view the rest of the pipeline reads.
 *
 * <p>Position carries the addressing throughout. A stream's identity is its
 * index in the header's stream map, a frame's identity is its position in the
 * command stream, and nothing in the file stores a register number, a timer
 * number or a frame number. Every stream is separately ZX1-compressed and
 * decoded through {@link Zx1}, which is why the map gives each one an offset
 * and a ring of its own: no stream's position depends on decoding any other.
 *
 * <h2>A width-2 entry is two registers, not a 16-bit number</h2>
 *
 * <p>The spec heads its layout section with "all multi-byte values
 * big-endian", and that line is true of the header - and only of the header.
 * A stream entry two bytes wide is the two register values in REGISTER ORDER:
 * a tone is (R0, R1), fine byte first; an envelope period is (R11, R12); a
 * timer's rate is (prescaler, counter), which are the MFP's control and data
 * registers and not one number at all. The player settles it -
 * {@code ymr_pop_register} in {@code lib_data.s} walks a row of (first
 * register, count) and writes the bytes to consecutive registers in the order
 * they arrive - and so does any real file. Take the 16463 tone entries of the
 * {@code signals-grouped.ymr} export: read as big-endian words, 15895 of them
 * land above the YM's 12-bit period range, which is most of the song; read in
 * register order, not one of their coarse bytes exceeds the four bits R1, R3
 * and R5 have. The spec is wrong to imply otherwise, and this reader follows
 * the bytes.
 */
public final class YmrReader {

    /** The four bytes every .YMR image opens with, and the name a report
     * gives the format. */
    public static final String MAGIC = "YMR!";

    /** The only version this reader accepts. */
    private static final int VERSION_13 = 0x0103;

    /** Entries in the stream map. Stream 0 is the command stream itself. */
    public static final int STREAM_COUNT = 20;

    /** Registers a frame carries: R0..R13. R14/R15 are the chip's I/O ports. */
    public static final int REGISTER_COUNT = 14;

    /** Timers available for effects: A, B and D, in that order. */
    public static final int TIMER_COUNT = 3;

    /**
     * The R13 value that means "leave the envelope alone this frame".
     *
     * <p>R13 is the one register a .YMR frame may decline to write. Writing R13
     * restarts the hardware envelope, so the pop IS the retrigger, and a frame
     * that does not pop {@code envelope_shape} must not write the register at
     * all - not even the value it last carried, which would retrigger the
     * envelope on every frame of a held note. No shape value can mean "nothing",
     * so the timeline carries a marker instead, and the marker is $FF because
     * that is already what the YMX pipeline downstream reads as exactly this
     * (see {@code org.ymx.Ym2149.NO_ENVELOPE_CHANGE}): a converter hands the
     * register vector straight on rather than translating a convention.
     */
    public static final int NO_ENVELOPE_SHAPE = 0xFF;

    private static final int HEADER_FIELDS = 28;
    private static final int MAP_ENTRY = 12;
    private static final int HEADER_SIZE = HEADER_FIELDS + STREAM_COUNT * MAP_ENTRY;

    private static final int COMMAND = 0;
    private static final int END_OF_FRAME = 0x00;
    private static final int FIRST_RESERVED_COMMAND = 0xC0;

    private static final int ENVELOPE_SHAPE = 10;
    private static final int LAST_REGISTER_STREAM = ENVELOPE_SHAPE;
    private static final int R_ENVELOPE_SHAPE = 13;
    private static final int FIRST_TIMER_STREAM = 11;
    private static final int STREAMS_PER_TIMER = 3;

    /** Bytes in one entry of each stream. The command stream is never popped. */
    private static final int[] WIDTH = {
        0, 2, 2, 2, 1, 1, 1, 1, 1, 2, 1,
        1, 2, 1,
        1, 2, 1,
        1, 2, 1,
    };

    /** The first YM register a register stream's entry writes; streams 1..10. */
    private static final int[] FIRST_REGISTER = {-1, 0, 2, 4, 6, 7, 8, 9, 10, 11, 13};

    /** The stream names the format uses, for messages that name what went wrong. */
    private static final String[] NAME = {
        "command", "tone_a", "tone_b", "tone_c", "noise", "mixer",
        "volume_a", "volume_b", "volume_c", "envelope_period", "envelope_shape",
        "timer_a_effect", "timer_a_rate", "timer_a_sample",
        "timer_b_effect", "timer_b_rate", "timer_b_sample",
        "timer_d_effect", "timer_d_rate", "timer_d_sample",
    };

    /**
     * One parsed .YMR image, on the played timeline: every stream's entries put
     * back where the command stream said they belong.
     *
     * <p>The three timers are named rather than indexed because the file names
     * them - Timer A, Timer B and Timer D, in that order, bound to voices A, B
     * and C. There is no Timer C among them: Timer C is reserved for the frame
     * tick, and calling the third one by its voice would hide that.
     *
     * @param frameCount how many frames the song plays; the command stream holds
     *                   exactly this many end-of-frame bytes
     * @param loopFrame  the frame the song comes back to, or -1 when the header's
     *                   $FFFFFFFF says it plays once
     * @param frameRate  the rate the song was written at, in Hz
     * @param ymClock    the YM2149 master clock the periods are relative to
     * @param registers  {@code registers[r][frame]} is R{@code r} as the chip
     *                   would hold it, each register keeping its last popped
     *                   value; R13 carries {@link #NO_ENVELOPE_SHAPE} on every
     *                   frame that did not pop {@code envelope_shape}. Laid out
     *                   one vector per register rather than one record per
     *                   frame, which is the shape a frame stream has downstream,
     *                   so the conversion hands them on rather than transposing
     *                   them.
     * @param samples    the sample blocks, in the order the file stores them,
     *                   which is the order a {@code timer_*_sample} entry indexes
     * @param timerA     what every frame asked of Timer A, one entry per frame
     * @param timerB     the same for Timer B
     * @param timerD     the same for Timer D
     */
    public record Song(int frameCount, int loopFrame, int frameRate, long ymClock,
                       byte[][] registers, List<Sample> samples,
                       List<TimerFrame> timerA, List<TimerFrame> timerB,
                       List<TimerFrame> timerD) {

        public Song {
            samples = List.copyOf(samples);
            timerA = List.copyOf(timerA);
            timerB = List.copyOf(timerB);
            timerD = List.copyOf(timerD);
        }

        /** One register on one frame, as an unsigned byte. */
        public int register(int register, int frame) {
            return registers[register][frame] & 0xFF;
        }

        /** Timer 0, 1 and 2 are Timers A, B and D, bound to voices A, B and C. */
        public List<TimerFrame> timer(int timer) {
            return switch (timer) {
                case 0 -> timerA;
                case 1 -> timerB;
                case 2 -> timerD;
                default -> throw new IndexOutOfBoundsException("timer " + timer);
            };
        }

        /** Whether the header names a loop frame at all. */
        public boolean loops() {
            return loopFrame >= 0;
        }
    }

    /**
     * What one frame asked of one timer, and which of its three streams said so.
     *
     * <p>The values are the timer's requested configuration: whatever its
     * {@code timer_*_effect}, {@code timer_*_rate} and {@code timer_*_sample}
     * streams last popped, held from frame to frame like any other stream. The
     * three flags are the events, and they are not the same information: popping
     * {@code timer_*_sample} restarts the sample even when the index has not
     * changed - that is how the same drum sounds twice on adjacent rows - and
     * popping {@code timer_*_effect} carrying 0 is what stops the timer, though
     * the value it stops from is the same 0 an idle timer already held.
     *
     * <p>{@code _ymr_process_tmr} in {@code lib_ymr.s} reconciles the three once
     * the whole frame has been read, and a caller with these fields and the
     * previous frame's can reproduce it: nothing popped means nothing changed; an
     * effect pop reconfigures the timer, or stops it when the effect is 0; a
     * sample pop restarts the sample on a running effect; and a rate pop alone
     * reprograms the prescaler and counter without restarting anything, so a
     * pitch slide does not disturb a running PWM's phase or a sample's position.
     */
    public record TimerFrame(int effect, int prescaler, int counter, int sample,
                             boolean effectPopped, boolean ratePopped,
                             boolean samplePopped) {

        /** Effect types, as the effect stream carries them. */
        public static final int NONE = 0;

        /** Pulse-width modulation on the timer's voice. */
        public static final int PWM = 1;

        /** The timer streams a sample block into the voice's volume register. */
        public static final int SAMPLE = 2;

        /** Sync-buzzer: the timer rewrites R13 to restart the envelope. */
        public static final int RTE = 3;

        /** Whether any of this timer's three streams popped this frame. */
        public boolean popped() {
            return effectPopped || ratePopped || samplePopped;
        }
    }

    /**
     * One sample block: pre-converted 4-bit YM volume levels, one per byte.
     *
     * <p>The exporter does the 8-bit to 4-bit conversion, so the player's timer
     * ISR writes a byte straight to a volume register with no table in between.
     * {@code loopStart} is a position in samples, which is also a position in
     * bytes; a sample that plays once carries 0 there and a resting level
     * written after its last value, so the voice does not stop on whatever the
     * waveform happened to be doing.
     */
    public record Sample(byte[] data, boolean looped, int loopStart) {}

    /** Thrown for anything this reader will not accept, with a usable message. */
    public static final class FormatException extends RuntimeException {
        public FormatException(String message) {
            super(message);
        }
    }

    private final byte[] data;
    private int at;

    private YmrReader(byte[] data) {
        this.data = data;
    }

    public static Song read(byte[] data) {
        return new YmrReader(data).run();
    }

    private Song run() {
        String magic = ascii(4);
        if (!magic.equals(MAGIC)) {
            throw new FormatException("not a .YMR image (starts with \"" + magic + "\")");
        }
        int version = u16();
        if (version != VERSION_13) {
            throw new FormatException("this is .YMR version " + (version >> 8) + "."
                    + (version & 0xFF) + "; only 1.3 has the stream map this reader reads");
        }

        long frames = u32();
        long loop = u32();
        int frameRate = u16();
        int sampleCount = u16();
        long ymClock = u32();
        int streamCount = u16();
        if (streamCount != STREAM_COUNT) {
            throw new FormatException("the stream map has " + streamCount + " entries, not the "
                    + STREAM_COUNT + " version 1.3 defines");
        }
        u32();                                          // reserved; written 0, ignored here

        if (frames <= 0 || frames > Integer.MAX_VALUE) {
            throw new FormatException("unusable frame count " + frames);
        }
        int frameCount = (int) frames;
        if (loop != 0xFFFFFFFFL && loop >= frames) {
            throw new FormatException("loop frame " + loop + " is past the " + frameCount
                    + " frames the song has");
        }
        int loopFrame = loop == 0xFFFFFFFFL ? -1 : (int) loop;

        int[] offsets = new int[STREAM_COUNT];
        int[] rings = new int[STREAM_COUNT];
        readMap(offsets, rings);

        List<Sample> samples = readSamples(sampleCount);

        byte[][] entries = new byte[STREAM_COUNT][];
        boolean[] present = new boolean[STREAM_COUNT];
        for (int stream = 0; stream < STREAM_COUNT; stream++) {
            present[stream] = offsets[stream] != 0;
            entries[stream] = present[stream]
                    ? decodeStream(stream, offsets, rings)
                    : new byte[0];
        }
        if (!present[COMMAND]) {
            throw new FormatException("the map has no command stream, so nothing says which "
                    + "streams any frame pops");
        }

        return replay(entries, present, frameCount, loopFrame, frameRate, ymClock, samples);
    }

    /**
     * Reads the map, whose entries are (offset, loop offset, ring size,
     * reserved). An offset of 0 means the stream is not in the file at all -
     * nothing ever popped it - and a song that runs no timer effect leaves out
     * all nine timer streams that way.
     *
     * <p>The loop offset is where each stream's second, independently decodable
     * piece begins: a compressed stream cannot be rewound, so the frames from
     * the loop frame onwards are packed on their own and the player re-enters
     * them there, on the first pass as well as on every one after. This reader
     * replays the song once through, from the start, and never needs it.
     */
    private void readMap(int[] offsets, int[] rings) {
        for (int stream = 0; stream < STREAM_COUNT; stream++) {
            long offset = u32();
            u32();                                      // loop offset; see above
            rings[stream] = u16();
            u16();                                      // reserved

            if (offset > data.length) {
                throw new FormatException("truncated file: " + NAME[stream] + " starts at offset "
                        + offset + ", past the " + data.length + " bytes in the file");
            }
            offsets[stream] = (int) offset;
        }
    }

    /**
     * A stream's stored length is the distance to the next present stream's
     * offset, and the last present stream runs to the end of the file - which
     * works because the exporter writes the present streams in ascending map
     * order, and is the reason nothing in the file stores a stream's size.
     */
    private byte[] decodeStream(int stream, int[] offsets, int[] rings) {
        int end = data.length;
        for (int next = stream + 1; next < STREAM_COUNT; next++) {
            if (offsets[next] != 0) {
                end = offsets[next];
                break;
            }
        }
        if (end < offsets[stream]) {
            throw new FormatException(NAME[stream] + " starts at offset " + offsets[stream]
                    + ", after the stream that follows it in the map; the streams are stored in "
                    + "map order and their lengths are the distances between them");
        }

        byte[] decoded = Zx1.decode(data, offsets[stream], end - offsets[stream],
                rings[stream], NAME[stream]);
        int width = WIDTH[stream];
        if (width != 0 && decoded.length % width != 0) {
            throw new FormatException(NAME[stream] + " decodes to " + decoded.length
                    + " bytes, which is not a whole number of " + width + "-byte entries");
        }
        return decoded;
    }

    /**
     * Reads the sample blocks, which sit between the map and the streams: a size,
     * that many pre-converted volume levels, then a four-byte trailer. The size
     * is the padded one - the data is padded to an even byte count - so a reader
     * advances exactly that far to reach the trailer and every block after the
     * first starts word-aligned, which is what the 68000 player requires.
     */
    private List<Sample> readSamples(int count) {
        at = HEADER_SIZE;                               // the blocks follow the map
        List<Sample> samples = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long size = u32();
            if (size > data.length - at) {
                throw new FormatException("truncated file: sample " + index + " claims " + size
                        + " bytes but only " + (data.length - at) + " are left");
            }
            byte[] levels = Arrays.copyOfRange(data, at, at + (int) size);
            at += (int) size;

            need(4, "sample " + index + "'s trailer");
            boolean looped = (data[at++] & 1) != 0;
            int loopStart = u16();
            at++;                                       // reserved
            samples.add(new Sample(levels, looped, loopStart));
        }
        return samples;
    }

    // The replay's running state: where each stream has been read to, and what
    // the chip and the three timers hold at this point in the walk. It lives
    // here rather than in the loop because a pop touches one of them and a frame
    // boundary reads all of them, and because a reader is used once.
    private final int[] cursor = new int[STREAM_COUNT];
    private final int[] held = new int[REGISTER_COUNT];
    private final int[] effect = new int[TIMER_COUNT];
    private final int[] prescaler = new int[TIMER_COUNT];
    private final int[] counter = new int[TIMER_COUNT];
    private final int[] sample = new int[TIMER_COUNT];
    private final boolean[] effectPopped = new boolean[TIMER_COUNT];
    private final boolean[] ratePopped = new boolean[TIMER_COUNT];
    private final boolean[] samplePopped = new boolean[TIMER_COUNT];
    private boolean shapePopped;

    /**
     * Walks the command stream and writes down what the chip held on every
     * frame. One byte per command: $00 ends the frame, $01-$BF pops the stream
     * with that index, and $C0 upwards is reserved. A future command would
     * define for itself how many bytes follow it, so a reader that meets one it
     * does not recognise cannot skip it and must stop - which is why the reserved
     * range is a rejection here rather than something to step over.
     */
    private Song replay(byte[][] entries, boolean[] present, int frameCount, int loopFrame,
                        int frameRate, long ymClock, List<Sample> samples) {
        byte[][] registers = new byte[REGISTER_COUNT][frameCount];
        List<List<TimerFrame>> timers = new ArrayList<>(TIMER_COUNT);
        for (int timer = 0; timer < TIMER_COUNT; timer++) {
            timers.add(new ArrayList<>(frameCount));
        }

        byte[] commands = entries[COMMAND];
        int frame = 0;
        for (byte b : commands) {
            int command = b & 0xFF;
            if (command == END_OF_FRAME) {
                if (frame == frameCount) {
                    throw new FormatException("the command stream holds more than the "
                            + frameCount + " end-of-frame bytes the header declares");
                }
                settle(registers, timers, frame);
                frame++;
                continue;
            }
            if (command >= FIRST_RESERVED_COMMAND) {
                throw new FormatException("frame " + frame + " carries command $"
                        + Integer.toHexString(command).toUpperCase()
                        + ", which version 1.3 reserves; a reader has no way to tell its length");
            }
            if (command >= STREAM_COUNT) {
                throw new FormatException("frame " + frame + " pops stream " + command
                        + ", past the " + STREAM_COUNT + " in the map");
            }
            pop(command, frame, entries, present);
        }

        if (frame != frameCount) {
            throw new FormatException("the command stream holds " + frame
                    + " end-of-frame bytes, not the " + frameCount + " the header declares");
        }
        if (commands.length != 0 && commands[commands.length - 1] != END_OF_FRAME) {
            throw new FormatException("the command stream ends in the middle of a frame: there "
                    + "is no end-of-song marker, so its last byte has to end a frame");
        }

        return new Song(frameCount, loopFrame, frameRate, ymClock, registers, samples,
                timers.get(0), timers.get(1), timers.get(2));
    }

    /** Applies one stream's next entry, which is the whole of what a pop does. */
    private void pop(int stream, int frame, byte[][] entries, boolean[] present) {
        if (!present[stream]) {
            throw new FormatException("frame " + frame + " pops " + NAME[stream]
                    + ", which the map says is not in the file");
        }
        byte[] entry = entries[stream];
        int width = WIDTH[stream];
        int from = cursor[stream];
        if (width > entry.length - from) {
            throw new FormatException("frame " + frame + " pops " + NAME[stream]
                    + ", which has nothing left to pop: all " + entry.length
                    + " of its decoded bytes have been read");
        }
        cursor[stream] = from + width;

        if (stream <= LAST_REGISTER_STREAM) {
            // A width-2 entry is two register values in register order - a tone is
            // (fine, coarse) = (R0, R1), an envelope period is (R11, R12) - and not
            // a big-endian 16-bit number; see this class's javadoc for how the
            // player and the exported files settle that against the spec's wording.
            int register = FIRST_REGISTER[stream];
            for (int i = 0; i < width; i++) {
                held[register + i] = entry[from + i] & 0xFF;
            }
            if (stream == ENVELOPE_SHAPE) {
                shapePopped = true;
            }
            return;
        }

        // A timer's rate entry is (prescaler, counter): the MFP's control and data
        // registers, two halves of one decision rather than one wide number.
        int timer = (stream - FIRST_TIMER_STREAM) / STREAMS_PER_TIMER;
        switch ((stream - FIRST_TIMER_STREAM) % STREAMS_PER_TIMER) {
            case 0 -> {
                effect[timer] = entry[from] & 0xFF;
                effectPopped[timer] = true;
            }
            case 1 -> {
                prescaler[timer] = entry[from] & 0xFF;
                counter[timer] = entry[from + 1] & 0xFF;
                ratePopped[timer] = true;
            }
            default -> {
                sample[timer] = entry[from] & 0xFF;
                samplePopped[timer] = true;
            }
        }
    }

    /** Writes down the frame the commands just finished, and starts the next. */
    private void settle(byte[][] registers, List<List<TimerFrame>> timers, int frame) {
        for (int register = 0; register < REGISTER_COUNT; register++) {
            registers[register][frame] = (byte) held[register];
        }
        registers[R_ENVELOPE_SHAPE][frame] =
                (byte) (shapePopped ? held[R_ENVELOPE_SHAPE] : NO_ENVELOPE_SHAPE);
        shapePopped = false;

        for (int timer = 0; timer < TIMER_COUNT; timer++) {
            timers.get(timer).add(new TimerFrame(effect[timer], prescaler[timer], counter[timer],
                    sample[timer], effectPopped[timer], ratePopped[timer], samplePopped[timer]));
            effectPopped[timer] = false;
            ratePopped[timer] = false;
            samplePopped[timer] = false;
        }
    }

    // ------------------------------------------------------- reading the bytes

    private void need(int bytes, String what) {
        if (bytes > data.length - at) {
            throw new FormatException("truncated file: " + what + " needs " + bytes
                    + " bytes but only " + (data.length - at) + " are left");
        }
    }

    private String ascii(int bytes) {
        need(bytes, "the magic");
        String text = new String(data, at, bytes, StandardCharsets.US_ASCII);
        at += bytes;
        return text;
    }

    private int u16() {
        need(2, "a header field");
        return ((data[at++] & 0xFF) << 8) | (data[at++] & 0xFF);
    }

    private long u32() {
        return ((long) u16() << 16) | u16();
    }
}
