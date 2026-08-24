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
 * <p>The registers are packed separately: a register's value usually
 * repeats from frame to frame, and a vector holds one register's values
 * back to back, so the matches are short-range and dense. It also
 * gives the player fourteen independent decoders it can advance one at a
 * time, which keeps the per-VBL cost flat.
 *
 * <p>A stream can only be restarted from its beginning, so a tune that repeats
 * reaches its loop frame again in one of two ways: the player moves the read
 * position in every ring back one pass and plays on from the bytes already
 * there, or every stream is packed as two sections - the frames before the loop
 * frame, then the frames from it - and the player opens the second one at the
 * wrap. Which frame the file carries, and which of the two reaches it, is
 * {@link LoopFrame}'s answer; the ring size the plan comes back with is the one
 * the file carries.
 *
 * <p>Each section is packed as {@code st4} would with
 * {@code -k<unit> -m<N/unit> -l65535}: offsets never reach further back than
 * the ring the player decodes through, and no single operation is longer
 * than the word counters in the 68000 decoder. Register vectors are exactly the event
 * engine's kind of data - long runs of an unchanging value - so packing is
 * effectively instant.
 */
public final class YmxEncoder {

    /** What packing one stream's vector produced; the first fourteen stream
     * indices are registers, then the script streams in file order - M, X,
     * T, and each channel's A and P. */
    public record Stream(int register, int frames, int packedSize, int longestOp) {}

    /** The finished file plus the per-stream numbers the CLI reports; the
     * tune is the one that was actually packed, the padded one where the
     * length needed padding, {@code ringSize} the one the file carries, and
     * {@code loopFrame} the {@code L} it holds. {@code notes} is what the loop
     * frame moved or cost, for the CLI to report. */
    public record Result(byte[] file, List<Stream> streams, int ringSize, int chunk,
                         boolean loops, int unit, int loopFrame, Tune tune,
                         EffectScript.Result script, List<String> notes) {

        public Result {
            notes = List.copyOf(notes);
        }

        public int packedSize() {
            return streams.stream().mapToInt(Stream::packedSize).sum();
        }

        /** What the end of the tune does, and the frame it goes back to -
         * one sentence, so both CLIs report it in the same words. */
        public String startingOver() {
            if (!loops) {
                return "Plays once, then stops";
            }
            if (loopFrame == 0) {
                return "Plays through, then starts over";
            }
            return String.format("Plays through, then starts over from frame %d,"
                    + " replaying %d of its %d frames", loopFrame,
                    tune.frames() - loopFrame, tune.frames());
        }

        /** The longest operation in any stream; over 65535 the file is
         * unsafe for the 68000 decoders' word counters. */
        public int longestOp() {
            return streams.stream().mapToInt(Stream::longestOp).max().orElse(0);
        }
    }

    private YmxEncoder() {}

    /** Packs a tune that plays once and stops. */
    public static Result encode(Tune tune, int ringSize, int chunk) {
        return encode(tune, ringSize, chunk, false, true);
    }

    /**
     * Packs a tune that plays its frames once and then, when {@code loops},
     * plays them again from the top.
     */
    public static Result encode(Tune tune, int ringSize, int chunk,
                                boolean loops) {
        return encode(tune, ringSize, chunk, loops, true);
    }

    /**
     * As above, with the parser's progress report turned off. The fourteen
     * register streams are packed one after another, so what it reports is
     * progress through a stream rather than through the tune - worth watching
     * at a terminal, noise anywhere else.
     */
    public static Result encode(Tune tune, int ringSize, int chunk,
                                boolean loops, boolean progress) {
        return encode(tune, ringSize, chunk, loops, progress, 1);
    }

    /**
     * As above, packing the sections at {@code unit} bytes per ST4 unit - the
     * player must then be built with the same {@code ST4_UNIT}, which it
     * verifies against each container's signature. Lengths and offsets are
     * whole units, so the tune length must be a multiple of {@code unit}: a
     * padded section would decode one extra value into the ring, and it would
     * be played.
     */
    public static Result encode(Tune tune, int ringSize, int chunk,
                                boolean loops, boolean progress, int unit) {
        // The default map is a YM tune's, and only a YM tune's: it puts
        // channel 2 on Timer B, where a .ymr needs Timer D. A front end whose
        // format binds its timers passes its own map to the overload below,
        // and both CLIs do; this shorthand is for callers with no such map -
        // which in practice means tests.
        return encode(tune, ringSize, chunk, loops, progress, unit,
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
    public static Result encode(Tune tune, int ringSize, int chunk, boolean loops,
                                boolean progress, int unit, int timerMap) {
        // The floor first, on what every tune decodes; the exact check waits
        // for the script, since a tune that leaves channels idle decodes
        // fewer streams and may use a smaller chunk.
        String problem = YmxFormat.checkShape(ringSize, chunk, unit,
                YmxFormat.STREAM_A0);
        if (!problem.isEmpty()) {
            throw new IllegalArgumentException(problem);
        }
        if (tune.frames() % unit != 0) {
            throw new IllegalArgumentException("a tune of " + tune.frames()
                    + " frames cannot be packed in " + unit + "-byte units:"
                    + " its length must be a multiple of " + unit);
        }

        // Every stream's vector: the registers with R7 carrying the baked
        // mixer force, then the script's own five streams. Bytes a stream does
        // not consume repeat their predecessor - the event optimizer packs a
        // repeat to nothing, and the player never reads them.
        EffectScript.Result script = EffectScript.compile(tune, timerMap);
        int channels = channelsUsed(script);
        problem = YmxFormat.checkShape(ringSize, chunk, unit,
                YmxFormat.liveStreams(channels));
        if (!problem.isEmpty()) {
            throw new IllegalArgumentException(problem);
        }

        // The loop frame comes before the packing rather than after it: a body
        // that needs a bigger ring gets one, and a bigger ring lets a
        // back-reference reach further, so the sections are packed against the
        // ring the file ends up carrying. A plan that cuts the streams decides
        // how many sections there are to pack at all.
        LoopFrame.Plan plan = LoopFrame.resolve(tune, script, loops, ringSize, chunk,
                unit);
        ringSize = plan.ringSize();

        // A back-reference may never reach out of the ring the player decodes
        // through - N bytes is N/unit units - and the format's own ceiling
        // still applies above that.
        int offsetLimit = Math.min(ringSize / unit, St4Format.maxOffsetUnits(unit));
        int frames = script.frames();
        byte[][] vectors = new byte[YmxFormat.STREAMS][];
        for (int register = 0; register < YmxFormat.REGISTER_STREAMS; register++) {
            byte[] values = Ym2149.mask(register, tune.registers()[register]);
            if (register == 7) {
                values = values.clone();
                for (int p = 0; p < frames; p++) {
                    values[p] |= script.r7force()[p];
                }
            }
            vectors[register] = values;
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

        // One section per stream, or two where the plan cuts them at the loop
        // frame: the first covers the frames before it, the second the frames
        // from it, and the pair is what the stream reports as its cost.
        var streams = new ArrayList<Stream>(YmxFormat.STREAMS);
        var sections = new Section[YmxFormat.STREAMS];
        Section[] loopSections = plan.cut() ? new Section[YmxFormat.STREAMS] : null;
        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            byte[] values = vectors[stream];
            if (loopSections == null) {
                sections[stream] = pack(progress, values, offsetLimit, unit);
            } else {
                sections[stream] = pack(progress,
                        Arrays.copyOfRange(values, 0, plan.frame()), offsetLimit, unit);
                loopSections[stream] = pack(progress,
                        Arrays.copyOfRange(values, plan.frame(), values.length),
                        offsetLimit, unit);
            }
            streams.add(measure(stream, values.length, sections[stream],
                    loopSections == null ? null : loopSections[stream]));
        }

        byte[] file = build(tune, ringSize, chunk, frames, loops, plan.frame(),
                sections, loopSections, tune.samples(), channels);
        return new Result(file, List.copyOf(streams), ringSize, chunk, loops, unit,
                plan.frame(), tune, script, plan.notes());
    }

    /**
     * A stream byte is meaningful only on frames its master bit marks - and
     * for a count stream, only when the action actually reads the count (a
     * program opcode, or a HELD carrying the reload flag). Everywhere else the
     * previous byte repeats, which costs nothing packed.
     */
    static byte[] carry(byte[] values, byte[] master, int bit,
                                byte @org.jspecify.annotations.Nullable [] actions) {
        byte[] out = values.clone();
        byte last = 0;
        for (int p = 0; p < out.length; p++) {
            boolean read = (master[p] & bit) != 0;
            if (read && actions != null) {
                int opcode = actions[p] & 0xE0;
                read = opcode >= EffectScript.OPCODE_START_TOGGLE
                        || opcode == EffectScript.OPCODE_HOLD
                                && (actions[p] & EffectScript.HOLD_RELOAD) != 0
                        || opcode == EffectScript.OPCODE_RESUME
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
    private record Section(byte[] bytes, boolean stored, int longestOp) {

        static final Section ABSENT = new Section(new byte[0], false, 0);
    }

    /**
     * Packs one section of one stream; an empty section produces nothing.
     *
     * <p>A short section costs more as a container than as itself: twenty of
     * the bytes are header before a value is written down, and a one-frame
     * tune carries one value. Where the values are the smaller of the two,
     * they are what the file gets, and the section's offset says so.
     */
    private static Section pack(boolean progress, byte[] values, int offsetLimit,
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
        return new Section(stored ? values : container, stored, result.longestOp());
    }

    /** What one stream costs the file: its frames, and the bytes of the one
     * section covering them or of the two that share them. */
    private static Stream measure(int stream, int frames, Section first,
                                  @org.jspecify.annotations.Nullable Section second) {
        if (second == null) {
            return new Stream(stream, frames, first.bytes().length, first.longestOp());
        }
        return new Stream(stream, frames,
                first.bytes().length + second.bytes().length,
                Math.max(first.longestOp(), second.longestOp()));
    }

    private static byte[] build(Tune tune, int ringSize, int chunk, int frames,
                                boolean loops, int loopFrame, Section[] sections,
                                Section @org.jspecify.annotations.Nullable [] loopSections,
                                byte[][] samples, int channels) {
        // Containers carry alignment rules of their own - stream A and D
        // are read a word at a time - so each is placed on a long boundary. A
        // stored section is read a byte at a time and needs none, but it takes
        // the same boundary: one placement rule, and the four bytes it can
        // cost are what a section of its size is trying to save.
        //
        // The loop table, where there is one, sits between the header and the
        // sections: one more table of the same shape, on a long boundary like
        // everything else in the body.
        int total = YmxFormat.HEADER_SIZE;
        int loopTable = 0;
        if (loopSections != null) {
            loopTable = align(total);
            total = loopTable + 4 * YmxFormat.STREAMS;
        }
        for (Section section : sections) {
            total = align(total) + section.bytes().length;
        }
        if (loopSections != null) {
            for (Section section : loopSections) {
                total = align(total) + section.bytes().length;
            }
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
        putLong(file, YmxFormat.OFFSET_MASTER_CLOCK, tune.masterClock());
        putLong(file, YmxFormat.OFFSET_SAMPLE_TABLE, sampleTable);
        putWord(file, YmxFormat.OFFSET_SAMPLE_COUNT, samples.length);
        // L, the frame the tune starts over from: 0 where it plays once
        // through, and 0 where the packer could not keep the source's own. The
        // loop table offset is 0 where the sections cover the whole tune, and
        // otherwise where the second set of them is located from.
        putLong(file, YmxFormat.OFFSET_LOOP_FRAME, loopFrame);
        putLong(file, YmxFormat.OFFSET_LOOP_TABLE, loopTable);

        int at = place(file, YmxFormat.OFFSET_SECTION_TABLE, sections,
                loopTable == 0 ? YmxFormat.HEADER_SIZE
                        : loopTable + 4 * YmxFormat.STREAMS);
        if (loopSections != null) {
            place(file, loopTable, loopSections, at);
        }

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

    /** Copies one table's sections into the file and fills in its offsets,
     * and reports where the next part may begin. */
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
