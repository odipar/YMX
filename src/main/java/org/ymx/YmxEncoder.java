package org.ymx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.st4.St4;
import org.st4.St4Compressor;
import org.st4.St4EventOptimizer;
import org.st4.St4Format;
import org.st4.Units;

/**
 * Turns a {@link Tune} into a {@code .ymx} file: fourteen register vectors,
 * masked down to what a plain YM2149 receives, plus the compiled effect script
 * streams and the sample table, each vector packed as its own embedded
 * ST4 container.
 *
 * <p>Nothing here depends on what file the tune was read out of, and nothing
 * can find out: a front end has already turned its own format into the
 * engine's model and stopped. That lets a second front end be a peer
 * of the first rather than a client of it.
 *
 * <p>Packing the registers separately is the whole point. A register's value
 * usually repeats from frame to frame, and a vector holds one register's
 * values back to back, so the matches are short-range and dense. It also
 * gives the player fourteen independent decoders it can advance one at a
 * time, which keeps the per-VBL cost flat.
 *
 * <p>A looping tune is packed as two sets of sections, split at the loop
 * frame. Looping means restarting a decoder, and a stream can only be
 * restarted from its beginning - so the frames from the loop point on become
 * sections of their own, which the player re-inits every time round. The split
 * costs a little ratio, since the loop half cannot reference the intro half,
 * and costs nothing for the common case of a tune that loops from
 * frame 0.
 *
 * <p>Each section is packed the way {@code st4} would with
 * {@code -k1 -mN -l65535}: offsets never reach further back than the ring the
 * player decodes through, and no single operation is longer than the word
 * counters in the 68000 decoder. Register vectors are exactly the event
 * engine's kind of data - long runs of an unchanging value - so packing is
 * effectively instant.
 */
public final class YmxEncoder {

    /** What packing one stream's vector produced; the first fourteen stream
     * indices are registers, then M, A1, P1, A2, P2. */
    public record Stream(int register, boolean loop, int frames, int packedSize, int longestOp) {}

    /** The finished file plus the per-stream numbers the CLI reports; the
     * tune is the one that was actually packed, the padded one
     * where the shape needed padding. */
    public record Result(byte[] file, List<Stream> streams, int ringSize, int chunk,
                         int loopFrame, boolean loops, int unit, Tune tune,
                         EffectScript.Result script) {

        public int packedSize() {
            return streams.stream().mapToInt(Stream::packedSize).sum();
        }

        /** The longest operation in any stream; over 65535 the file is unsafe for ST1. */
        public int longestOp() {
            return streams.stream().mapToInt(Stream::longestOp).max().orElse(0);
        }
    }

    private YmxEncoder() {}

    /** Packs a tune that plays once and stops. */
    public static Result encode(Tune tune, int ringSize, int chunk) {
        return encode(tune, ringSize, chunk, -1, true);
    }

    /**
     * Packs a tune, looping at {@code loopFrame} - or playing once and stopping
     * when {@code loopFrame} is negative. A loop frame of 0 means the whole
     * tune is the loop.
     */
    public static Result encode(Tune tune, int ringSize, int chunk,
                                int loopFrame) {
        return encode(tune, ringSize, chunk, loopFrame, true);
    }

    /** A tune that plays once and stops, with the progress report turned off. */
    public static Result encode(Tune tune, int ringSize, int chunk,
                                boolean progress) {
        return encode(tune, ringSize, chunk, -1, progress);
    }

    /**
     * As above, with the parser's progress report turned off. The fourteen
     * register streams are packed one after another, so what it reports is
     * progress through a stream rather than through the tune - worth watching
     * at a terminal, noise anywhere else.
     */
    public static Result encode(Tune tune, int ringSize, int chunk,
                                int loopFrame, boolean progress) {
        return encode(tune, ringSize, chunk, loopFrame, progress, 1);
    }

    /**
     * As above, packing the sections at {@code unit} bytes per ST4 unit - the
     * player must then be built with the same {@code ST4_UNIT}, which it
     * verifies against each container's signature. Lengths and offsets are
     * whole units, so the tune length and the loop frame must be multiples of
     * {@code unit}: a padded section would decode one extra value into the
     * ring, and it would be played.
     */
    public static Result encode(Tune tune, int ringSize, int chunk,
                                int loopFrame, boolean progress, int unit) {
        // The default map is a YM tune's, and only a YM tune's: it puts
        // channel 2 on Timer B, where a .ymr needs Timer D. A front end whose
        // format binds its timers passes its own map to the overload below,
        // and both CLIs do; this shorthand is for callers with no such map -
        // which in practice means tests.
        return encode(tune, ringSize, chunk, loopFrame, progress, unit,
                YmxFormat.DEFAULT_TIMERS);
    }

    /**
     * The whole encoder, with the channel-to-timer map the T stream carries.
     *
     * <p>From this line down nothing depends on which format the tune was read
     * of. The frame streams are the chip state whatever wrote it, the timer
     * streams are already normalized, and how the source triggers and stops
     * arrives with the tune as its {@link EffectScript.Semantics}.
     */
    public static Result encode(Tune tune, int ringSize, int chunk, int loopFrame,
                                boolean progress, int unit, int timerMap) {
        // The floor first, on what every tune decodes; the exact check waits
        // for the script, since a tune that leaves channels idle decodes
        // fewer streams and may use a smaller chunk.
        String problem = YmxFormat.checkShape(ringSize, chunk, unit,
                YmxFormat.STREAM_A0);
        if (!problem.isEmpty()) {
            throw new IllegalArgumentException(problem);
        }
        boolean loops = loopFrame >= 0;
        if (loops && loopFrame >= tune.frames()) {
            throw new IllegalArgumentException("loop frame " + loopFrame
                    + " is not inside a tune of " + tune.frames() + " frames");
        }
        // Without a loop the intro covers everything, the same thing
        // as looping at the end - so the player needs only one rule.
        if (tune.frames() % unit != 0 || (loops ? loopFrame : 0) % unit != 0) {
            throw new IllegalArgumentException("a tune of " + tune.frames()
                    + " frames splitting at " + (loops ? loopFrame : tune.frames())
                    + " cannot be packed in " + unit + "-byte units: both must be"
                    + " multiples of " + unit);
        }

        // A back-reference may never reach out of the ring the player decodes
        // through - N bytes is N/unit units - and the format's own ceiling
        // still applies above that.
        int offsetLimit = Math.min(ringSize / unit, St4Format.maxOffsetUnits(unit));

        // Every stream's vector, on the PLAYED timeline the script compiled:
        // registers source-mapped through the split rotation with R7 carrying
        // the baked mixer force, then the script's own five streams. Bytes a
        // stream does not consume repeat their predecessor - the event
        // optimizer packs a repeat to nothing, and the player never reads
        // them.
        EffectScript.Result script = EffectScript.compile(tune,
                loops ? loopFrame : -1, unit, timerMap);
        int channels = channelsUsed(script);
        problem = YmxFormat.checkShape(ringSize, chunk, unit,
                YmxFormat.liveStreams(channels));
        if (!problem.isEmpty()) {
            throw new IllegalArgumentException(problem);
        }
        int frames = script.frames();
        int split = script.split();
        byte[][] vectors = new byte[YmxFormat.STREAMS][];
        for (int register = 0; register < YmxFormat.REGISTER_STREAMS; register++) {
            byte[] source = Ym2149.mask(register, tune.registers()[register]);
            byte[] played = new byte[frames];
            for (int p = 0; p < frames; p++) {
                played[p] = source[script.source()[p]];
            }
            if (register == 7) {
                for (int p = 0; p < frames; p++) {
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
            byte[] action = script.actions()[c];
            vectors[YmxFormat.streamAction(c)] =
                    carry(action, script.m(), acts, null);
            vectors[YmxFormat.streamAction(c) + 1] =
                    carry(script.counts()[c], script.m(), acts, action);
        }

        var streams = new ArrayList<Stream>(2 * YmxFormat.STREAMS);
        var intro = new Section[YmxFormat.STREAMS];
        var loop = new Section[YmxFormat.STREAMS];
        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            byte[] values = vectors[stream];
            intro[stream] = pack(streams, stream, false, progress,
                    Arrays.copyOfRange(values, 0, split), offsetLimit, unit);
            loop[stream] = pack(streams, stream, true, progress,
                    loops ? Arrays.copyOfRange(values, split, values.length) : new byte[0],
                    offsetLimit, unit);
        }

        byte[] file = build(tune, ringSize, chunk, frames, split, loops, intro,
                loop, tune.samples(), channels);
        return new Result(file, List.copyOf(streams), ringSize, chunk, split, loops, unit,
                tune, script);
    }

    /**
     * A stream byte is meaningful only on frames its master bit marks - and
     * for a count stream, only when the action actually reads the count (a
     * program verb, or a HELD carrying the reload flag). Everywhere else the
     * previous byte repeats, which costs nothing packed.
     */
    static byte[] carry(byte[] values, byte[] master, int bit,
                                byte @org.jspecify.annotations.Nullable [] actions) {
        byte[] out = values.clone();
        byte last = 0;
        for (int p = 0; p < out.length; p++) {
            boolean read = (master[p] & bit) != 0;
            if (read && actions != null) {
                int verb = actions[p] & 0xE0;
                read = verb >= EffectScript.VERB_START_TOGGLE
                        || verb == EffectScript.VERB_HOLD
                                && (actions[p] & EffectScript.HOLD_RELOAD) != 0
                        || verb == EffectScript.VERB_RESUME
                                && (actions[p] & EffectScript.RESUME_RELOAD) != 0;
            }
            if (read) {
                last = out[p];
            } else {
                out[p] = last;
            }
        }
        return out;
    }

    /** One section as it goes into the file: packed, or the values themselves. */
    private record Section(byte[] bytes, boolean stored) {

        static final Section ABSENT = new Section(new byte[0], false);
    }

    /**
     * Packs one section of one register; an empty section produces nothing.
     *
     * <p>A short section costs more as a container than as itself: twenty of
     * the bytes are header before a value is written down, and a one-frame
     * intro carries one value. Where the values are the smaller of the two,
     * they are what the file gets, and the section's offset says so.
     */
    private static Section pack(List<Stream> streams, int register, boolean loop,
                                boolean progress, byte[] values, int offsetLimit,
                                int unit) {
        if (values.length == 0) {
            return Section.ABSENT;
        }
        int[] units = Units.split(values, unit);
        St4Compressor.Result result = St4Compressor.compress(
                St4EventOptimizer.optimize(units, unit, offsetLimit, progress),
                units, unit, St4Format.MAX_OP);
        byte[] container = St4.container(result);
        boolean stored = values.length < container.length;
        byte[] bytes = stored ? values : container;
        streams.add(new Stream(register, loop, values.length, bytes.length,
                result.longestOp()));
        return new Section(bytes, stored);
    }

    private static byte[] build(Tune tune, int ringSize, int chunk, int frames,
                                int split, boolean loops, Section[] intro, Section[] loop,
                                byte[][] samples, int channels) {
        // Containers carry alignment guarantees of their own - stream A and D
        // are read a word at a time - so each is placed on a long boundary. A
        // stored section is read a byte at a time and needs none, but it takes
        // the same boundary: one placement rule, and the four bytes it can
        // cost are what a section of its size is trying to save.
        int total = YmxFormat.HEADER_SIZE;
        for (Section section : intro) {
            total = align(total) + section.bytes().length;
        }
        for (Section section : loop) {
            total = align(total) + section.bytes().length;
        }
        int sampleTable = samples.length == 0 ? 0 : align(total);
        if (samples.length > 0) {
            total = sampleTable + YmxFormat.SAMPLE_ENTRY_SIZE * samples.length;
            for (byte[] sample : samples) {
                total += sample.length + 1;               // the end marker byte
            }
        }

        byte[] file = new byte[align(total)];
        putLong(file, YmxFormat.OFFSET_MAGIC, YmxFormat.MAGIC);
        putWord(file, YmxFormat.OFFSET_VERSION, YmxFormat.VERSION);
        // One flag per timer channel: the player claims a timer for each
        // channel named here and leaves the rest to the host. A YM tune
        // names two, so Timer B stays the host's.
        putWord(file, YmxFormat.OFFSET_FLAGS,
                (loops ? YmxFormat.FLAG_LOOPS : 0) | channels);
        putLong(file, YmxFormat.OFFSET_FRAMES, frames);
        putWord(file, YmxFormat.OFFSET_PLAYER_HZ, tune.frameRate());
        putWord(file, YmxFormat.OFFSET_STREAM_COUNT, YmxFormat.STREAMS);
        putWord(file, YmxFormat.OFFSET_RING_SIZE, ringSize);
        putWord(file, YmxFormat.OFFSET_CHUNK, chunk);
        putLong(file, YmxFormat.OFFSET_LOOP_FRAME, split);
        putLong(file, YmxFormat.OFFSET_MASTER_CLOCK, tune.masterClock());
        putLong(file, YmxFormat.OFFSET_SAMPLE_TABLE, sampleTable);
        putWord(file, YmxFormat.OFFSET_SAMPLE_COUNT, samples.length);

        int at = YmxFormat.HEADER_SIZE;
        at = place(file, YmxFormat.OFFSET_INTRO_TABLE, intro, at);
        at = place(file, YmxFormat.OFFSET_LOOP_TABLE, loop, at);

        // The sample table: entries first, then the samples, each closed by the
        // end marker the PCM tick handler stops on.
        if (samples.length > 0) {
            int sample = sampleTable + YmxFormat.SAMPLE_ENTRY_SIZE * samples.length;
            for (int i = 0; i < samples.length; i++) {
                putLong(file, sampleTable + YmxFormat.SAMPLE_ENTRY_SIZE * i, sample);
                putWord(file, sampleTable + YmxFormat.SAMPLE_ENTRY_SIZE * i + 4,
                        samples[i].length);
                // Where a PCM stream goes back to when it runs out, or the
                // one-shot marker. An offset rather than an address: the
                // player resolves both against the same base at init.
                putWord(file, sampleTable + YmxFormat.SAMPLE_ENTRY_SIZE * i + 6,
                        tune.sampleLoops()[i]);
                System.arraycopy(samples[i], 0, file, sample, samples[i].length);
                sample += samples[i].length;
                file[sample++] = (byte) YmxFormat.SAMPLE_END_MARK;
            }
        }
        return file;
    }

    /** Copies one table's sections into the file and fills in its offsets. */
    private static int place(byte[] file, int table, Section[] sections, int at) {
        for (int register = 0; register < YmxFormat.STREAMS; register++) {
            byte[] bytes = sections[register].bytes();
            if (bytes.length == 0) {
                continue;                       // no such section: the offset stays 0
            }
            at = align(at);
            putLong(file, table + 4 * register,
                    sections[register].stored() ? at | YmxFormat.SECTION_STORED : at);
            System.arraycopy(bytes, 0, file, at, bytes.length);
            at += bytes.length;
        }
        return at;
    }

    /** The header's channel flags: a bit per timer channel the script ever
     * gives something to do. */
    private static int channelsUsed(EffectScript.Result script) {
        int acting = 0;
        for (byte b : script.m()) {
            acting |= b;
        }
        int flags = 0;
        for (int c = 0; c < YmxFormat.CHANNELS; c++) {
            if ((acting & (EffectScript.M_CHANNEL_0 << c)) != 0) {
                flags |= YmxFormat.flagChannel(c);
            }
        }
        return flags;
    }

    private static int align(int at) {
        return at + ((-at) & 3);
    }

    private static void putWord(byte[] file, int at, int value) {
        file[at] = (byte) (value >>> 8);
        file[at + 1] = (byte) value;
    }

    private static void putLong(byte[] file, int at, long value) {
        putWord(file, at, (int) (value >>> 16));
        putWord(file, at + 2, (int) value);
    }
}
