package org.ymx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.st4.St4;
import org.st4.St4Compressor;
import org.st4.St4EventOptimizer;
import org.st4.St4LiteralCopySearch;
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
     * tune is the one that was packed, the padded one where the
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
                return String.format("Plays through, then starts over from frame 0,"
                        + " replaying all of its %d frames", tune.frames());
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
        // channel 2 on Timer B, where another source may need Timer D. A front end whose
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
        return encode(tune, ringSize, chunk, loops, progress, unit, timerMap, -1);
    }

    /**
     * As above, with copies from the literal stream (SPEC.md Appendix A.5):
     * {@code copies} below 0 packs none, 0 packs the search's opening passes,
     * and above 0 searches for that many seconds a stream. A file with a
     * copy in it sets flag bit 5 and plays only on a player built for its
     * ring as a window.
     */
    public static Result encode(Tune tune, int ringSize, int chunk, boolean loops,
                                boolean progress, int unit, int timerMap,
                                double copies) {
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
        // The source's own loop frame is compiled as an entry, so a stream
        // running into it starts there and LoopFrame can keep it. The file's
        // L is resolved from this script below, so it is not known here.
        EffectScript.Result script = EffectScript.compile(tune, timerMap,
                loops ? tune.loopFrame() : 0);
        int channels = channelsUsed(script);
        problem = YmxFormat.checkShape(ringSize, chunk, unit,
                YmxFormat.liveStreams(channels));
        if (!problem.isEmpty()) {
            throw new IllegalArgumentException(problem);
        }

        // The loop frame comes before the packing: a plan that rewinds packs
        // the frames from it on their own, so every pass reads one history.
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

        // One section per stream. Where the plan rewinds, the container's
        // rewind point is the loop frame and the frames from it are parsed on
        // their own, so a pass after the first reads the history the first
        // one did (SPEC.md §8).
        var streams = new ArrayList<Stream>(YmxFormat.STREAMS);
        var sections = new Section[YmxFormat.STREAMS];
        int rewindAt = plan.rewinds() ? plan.frame() : -1;
        boolean copied = false;
        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            byte[] values = vectors[stream];
            sections[stream] = pack(progress, values, offsetLimit, unit, rewindAt, copies);
            copied |= sections[stream].copies() > 0;
            streams.add(new Stream(stream, values.length, sections[stream].bytes().length,
                    sections[stream].longestOp()));
        }
        // The twenty-five containers of one file take one loop form. A
        // stream's section holds one byte a frame, so every container's rewind
        // point is the loop frame in bytes, or none.
        loopsAlign(sections, rewindAt, offsetLimit);

        byte[] file = build(tune, ringSize, chunk, frames, loops, plan.frame(),
                sections, tune.samples(), channels | (copied ? YmxFormat.FLAG_COPIES : 0));
        return new Result(file, List.copyOf(streams), ringSize, chunk, loops, unit,
                plan.frame(), tune, script, plan.notes());
    }

    /**
     * A stream byte is meaningful only on frames its master bit marks - and
     * for a count stream, only when the action reads the count (a
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
                // RESUME at voice 3 programs the timer, so its low bits are
                // a prescaler and not the flags RESUME_RELOAD sits among:
                // it always reads a count (SPEC.md §3.5).
                boolean resumeRetuned =
                        opcode == EffectScript.OPCODE_RESUME
                        && ((actions[p] >> 3) & 3) == EffectScript.VOICELESS;
                read = opcode >= EffectScript.OPCODE_START_TOGGLE
                        || resumeRetuned
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

    /** One section as it goes into the file: packed, or the values
     * themselves; {@code copies} counts the blocks copied from the literal
     * stream. */
    private record Section(byte[] bytes, boolean stored, int longestOp, int copies) {

        static final Section ABSENT = new Section(new byte[0], false, 0, 0);
    }

    /**
     * Packs one section of one stream; an empty section produces nothing.
     * {@code rewindAt} is the frame the container carries as its rewind
     * point, or -1: the frames from it are parsed apart from the frames
     * before it, so no match in the loop reaches before it.
     *
     * <p>A short section costs more as a container than as itself:
     * twenty-eight of the bytes are header before a value is written down,
     * and a one-frame tune carries one value. Where the values are the
     * smaller of the two, they are what the file gets, and the section's
     * offset says so.
     */
    private static Section pack(boolean progress, byte[] values, int offsetLimit,
                                int unit, int rewindAt, double copies) {
        if (values.length == 0) {
            return Section.ABSENT;
        }
        int[] units = Units.split(values, unit);
        St4Compressor.Result result;
        if (rewindAt < 0) {
            result = St4Compressor.compress(
                    parse(units, unit, offsetLimit, copies, progress),
                    units, unit, St4Format.MAX_OP, -1, offsetLimit);
        } else {
            // A value is one byte, so the rewind point in units is the frame
            // over the unit size; the plan holds the frame to a unit boundary.
            int rewindIndex = rewindAt / unit;
            int[] intro = Arrays.copyOfRange(units, 0, rewindIndex);
            int[] loop = Arrays.copyOfRange(units, rewindIndex, units.length);
            result = St4Compressor.compressRewinding(
                    parse(intro, unit, offsetLimit, copies, progress),
                    parse(loop, unit, offsetLimit, copies, progress),
                    units, unit, St4Format.MAX_OP, rewindIndex, offsetLimit);
        }
        byte[] container = St4.container(result);
        boolean stored = values.length < container.length;
        return new Section(stored ? values : container, stored, result.longestOp(),
                stored ? 0 : result.copies());
    }

    /** The parse: the event-driven optimizer, or with copies the search
     * that copies from the literal stream, for {@code copies} seconds. */
    private static org.st4.St4Block parse(int[] units, int unit, int window,
                                          double copies, boolean progress) {
        if (copies < 0) {
            return St4EventOptimizer.optimize(units, unit, window, progress);
        }
        return St4LiteralCopySearch.optimize(units, unit, window, St4Format.MAX_OP,
                copies, progress && copies > 0);
    }

    /**
     * Every container of the file carries the one loop form the plan chose:
     * a rewind point of {@code rewindAt} bytes, or none, and the window the
     * ring gives. A stored section carries no header and takes its form from
     * the file's. A writer that let one stream differ would hand the player
     * a file it cannot detect and cannot play, so the check is here.
     */
    private static void loopsAlign(Section[] sections, int rewindAt, int window) {
        int want = rewindAt < 0 ? St4Format.NO_REWIND : rewindAt;
        for (int stream = 0; stream < sections.length; stream++) {
            if (sections[stream].stored() || sections[stream].bytes().length == 0) {
                continue;
            }
            St4Format.Container container = St4Format.read(sections[stream].bytes());
            if (container.rewind() != want) {
                throw new IllegalStateException("stream " + stream + " carries rewind "
                        + container.rewind() + " where the file's loop form is " + want);
            }
            if (container.window() != window) {
                throw new IllegalStateException("stream " + stream + " carries window "
                        + container.window() + " where the ring gives " + window);
            }
        }
    }

    private static byte[] build(Tune tune, int ringSize, int chunk, int frames,
                                boolean loops, int loopFrame, Section[] sections,
                                byte[][] samples, int channels) {
        // Containers carry alignment rules of their own - stream A and D
        // are read a word at a time - so each is placed on a long boundary. A
        // stored section is read a byte at a time and needs none, but it takes
        // the same boundary: one placement rule, and the four bytes it can
        // cost are what a section of its size is trying to save.
        int total = YmxFormat.HEADER_SIZE;
        for (Section section : sections) {
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
        putLong(file, YmxFormat.OFFSET_MASTER_CLOCK, tune.masterClock());
        putLong(file, YmxFormat.OFFSET_SAMPLE_TABLE, sampleTable);
        putWord(file, YmxFormat.OFFSET_SAMPLE_COUNT, samples.length);
        // L, the frame the tune starts over from: 0 where it plays once
        // through, and 0 where the packer could not keep the source's own.
        putLong(file, YmxFormat.OFFSET_LOOP_FRAME, loopFrame);
        // Q: this version carries no extension stream, so the mask names the
        // twenty-five §2 defines and nothing above them (SPEC.md §1.6).
        putLong(file, YmxFormat.OFFSET_REQUIRED, YmxFormat.REQUIRED_BASE);

        place(file, YmxFormat.OFFSET_SECTION_TABLE, sections, YmxFormat.HEADER_SIZE);

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
