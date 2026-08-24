package org.ymx;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;
import org.jspecify.annotations.Nullable;

/**
 * A tune as the engine has one: the streams to play, the sources they play
 * from, and the few numbers that say how fast it all runs.
 *
 * <p>This is the handover point, and it is the first thing in the pipeline
 * that speaks no file format. A front end reads its own format in that
 * format's own words - {@code org.ym6} in YM's, {@code org.ymr} in RhYMe's -
 * and stops here, at a record whose every field is a term from
 * {@code doc/terminology.md}. Nothing downstream of this line can ask which
 * format the bytes came from, because no field here records it. That is the
 * point. A second front end is a PEER of the first, and two peers can only
 * meet on ground neither one owns.
 *
 * <p>The two kinds of stream sit side by side because they are one timeline.
 * {@code registers[r][frame]} is the FRAME STREAM targeting register
 * {@code r} - fourteen of them, R0 to R13, and not the two I/O ports, which
 * are not chip state and are never packed. {@code codes[c][frame]} and
 * {@code counts[c][frame]} are TIMER CHANNEL {@code c}'s TIMER STREAM: what
 * to run on that channel this frame and at what rate. Any operation that
 * cuts or lengthens a tune has to do the same to all of them at once, or
 * every stream from that frame on plays against the wrong registers - which
 * is why {@link #padToUnit} lives here rather than in each front end.
 *
 * <p>A code byte carries the kind in bits 7-6, the voice plus one in bits
 * 5-4 (so zero voice bits mean an idle channel and a zero byte means nothing),
 * and the MFP prescaler index in bits 2-0; the count byte is the
 * MFP timer count that finishes the rate. Bit 3 is free, and a front end
 * whose triggers are events rather than repeated codes uses it to make two
 * triggers of one thing differ. The streams are as wide as
 * {@link YmxFormat#CHANNELS} however few a source fills: the compact
 * constructor pads the rest with all-zero streams, which is exactly what a
 * channel that never acts looks like, and the script compiler then walks
 * every channel the format has without asking where the bytes came from.
 *
 * <p>{@code shapes} is the envelope shape a RETRIGGER STREAM would restart on
 * each frame - one value, not one per channel, because the chip has one
 * envelope generator and a shape is not per-voice data. It is a front end's
 * to fill, and the two fill it from different places: a YM6 file keeps a
 * buzzer's shape in the low nibble of the voice its code names, while RhYMe
 * keeps it where the chip does, in R13. Resolving that here rather than in
 * the compiler keeps the compiler and the player free of a mode -
 * the value is carried, the way every other operand is.
 *
 * <p>{@code samples} are the PCM streams' sources, PSG-ready volume values
 * 0..15 one per byte, and {@code sampleLoops} says for each of them where a
 * PCM stream goes back to when it runs out - an offset into that sample, or
 * {@link YmxFormat#SAMPLE_ONE_SHOT} for one that stops. The two are one
 * thing in two arrays, which the compact constructor keeps true.
 * {@code semantics} is what the source dialect implies about triggering and
 * stopping and cannot be read out of the codes - see
 * {@link EffectScript.Semantics}. {@code loops} is what the SOURCE says the
 * end of the tune does - start over, or stop - and is a default a CLI may
 * override; {@code loopFrame} is the frame it starts over from, 0 where the
 * source gives none, and a default the same way. Whether the frame survives
 * into the file is the packer's answer, not this record's - see
 * {@link LoopFrame}. {@code name}, {@code author} and
 * {@code comment} are what a report and the SNDH tags need, empty where a
 * format carries no such thing, and {@code notes} is what the front end had
 * to change on the way here, in the order it found it.
 */
public record Tune(int frames, int frameRate, long masterClock, boolean loops,
                   int loopFrame,
                   byte[][] registers, byte[][] codes, byte[][] counts,
                   byte[] shapes, byte[][] samples, int[] sampleLoops,
                   EffectScript.Semantics semantics,
                   String name, String author, String comment,
                   List<String> notes) {

    /**
     * The same tune under other semantics. A front end sets the ones its
     * format fixes; where a format fixes nothing - no YM file records
     * which gap model its own player used - a caller may say instead, and
     * this is how it says so without every layer between carrying a flag.
     */
    public Tune under(EffectScript.Semantics semantics) {
        return new Tune(frames, frameRate, masterClock, loops, loopFrame, registers,
                codes, counts, shapes, samples, sampleLoops, semantics, name,
                author, comment, notes);
    }

    /**
     * The same tune starting over from another frame. A source gives one and a
     * CLI may give another; this is how the second replaces the first without
     * every layer between carrying a flag.
     */
    public Tune startingOverAt(int frame) {
        return new Tune(frames, frameRate, masterClock, loops, frame, registers,
                codes, counts, shapes, samples, sampleLoops, semantics, name,
                author, comment, notes);
    }

    /** The four kinds of timer stream, as they sit in a code byte's bits
     * 7-6: a TOGGLE STREAM, a PCM STREAM, a WAVE STREAM under its earlier
     * name, and a RETRIGGER STREAM.
     *
     * <p>These live with the model rather than with either front end
     * because both front ends write them and the script compiler reads
     * them, which makes them the shared ABI of the code byte. A constant
     * one peer owned and the other borrowed would put a front end
     * downstream of its sibling for no better reason than which one was
     * written first. */
    public static final int KIND_TOGGLE = 0x00;
    public static final int KIND_PCM = 0x40;
    public static final int KIND_CURVE = 0x80;
    public static final int KIND_RETRIGGER = 0xC0;

    /** The MFP's own clock in Hz, which a code byte's prescaler and the
     * count byte divide down to a tick rate. Unrelated to
     * {@link #masterClock}, which is the YM2149's. */
    public static final int MFP_CLOCK = 2457600;

    /** How many prescaler indices a code byte's bits 2-0 can name. */
    public static final int PRESCALERS = 8;

    /** The MFP prescaler table. Index 0 is not a divider at all but the
     * MFP's stopped state, so a code that selects it starts nothing. */
    private static final int[] PRESCALER_TABLE = {0, 4, 10, 16, 50, 64, 100, 200};

    /** The divisor prescaler {@code index} stands for - a lookup rather
     * than an exposed table, because a shared array is a shared variable. */
    public static int prescaler(int index) {
        return PRESCALER_TABLE[index];
    }

    /** How far a safe frame is looked for either side of a boundary that
     * needs padding, before {@link #padToUnit} gives up. */
    private static final int PAD_SEARCH = 64;

    public Tune {
        if (registers.length != YmxFormat.REGISTER_STREAMS) {
            throw new IllegalArgumentException("a tune carries "
                    + YmxFormat.REGISTER_STREAMS + " frame streams, R0 to R13, not "
                    + registers.length + ": the I/O ports are not chip state");
        }
        codes = widen(codes, frames);
        counts = widen(counts, frames);
        if (shapes.length != frames) {
            throw new IllegalArgumentException("a tune carries one envelope shape a"
                    + " frame, not " + shapes.length + " for " + frames);
        }
        if (loopFrame < 0 || loopFrame >= frames) {
            throw new IllegalArgumentException("a tune of " + frames + " frames"
                    + " cannot start over at frame " + loopFrame + "; a source"
                    + " that gives no frame gives 0");
        }
        // A code names a kind in bits 7-6 and a voice PLUS ONE in bits 5-4, so
        // zero voice bits mean the channel is idle and the whole byte must be
        // 0. A code with a kind and no voice would compile to an action byte
        // whose voice field is -1, and a negative voice does not stay in its
        // three bits: it floods the opcode above it and the player would read
        // the result as another opcode entirely. Nothing this repository writes
        // can produce one - both front ends drop a voiceless code to idle -
        // which is exactly why it is worth rejecting here rather than leaving
        // it to the next front end.
        for (int channel = 0; channel < codes.length; channel++) {
            for (int frame = 0; frame < frames; frame++) {
                int code = codes[channel][frame] & 0xFF;
                if (code != 0 && (code & 0x30) == 0) {
                    throw new IllegalArgumentException("channel " + channel
                            + " carries the code " + String.format("$%02X", code)
                            + " on frame " + frame + ", which names a kind but no"
                            + " voice; an idle channel's code is 0");
                }
            }
        }
        if (sampleLoops.length != samples.length) {
            throw new IllegalArgumentException("a tune carries one loop point per"
                    + " sample, not " + sampleLoops.length + " for " + samples.length);
        }
        for (int sample = 0; sample < samples.length; sample++) {
            int loop = sampleLoops[sample];
            if (loop != YmxFormat.SAMPLE_ONE_SHOT && loop >= samples[sample].length) {
                throw new IllegalArgumentException("sample " + sample + " loops from "
                        + loop + ", which is past its " + samples[sample].length
                        + " bytes; a sample that does not loop says "
                        + YmxFormat.SAMPLE_ONE_SHOT);
            }
        }
        notes = List.copyOf(notes);
    }

    /**
     * Pads the tune so its length is a whole number of {@code unit}s, by
     * duplicating a frame the front end says is safe to duplicate: one that
     * holds the chip state a tick longer without being heard.
     *
     * <p>What counts as safe is the SOURCE FORMAT's question - a YM dump and a
     * .YMR disagree about where a sample's arrival is written, and both have
     * to keep away from a frame that restarts the envelope - so the predicate
     * comes in from the front end and only the mechanism is here. The
     * mechanism is the part that must not be written twice: every stream is
     * stretched at the same frame, because a frame is a column across all of
     * them and padding the registers alone would silently desynchronise the
     * timer streams from them.
     *
     * <p>The loop frame moves with the frame it points at: the duplicates go
     * in after {@code atEnd}, so a loop frame past that one sits {@code endPad}
     * frames further along and one at or before it stays where it is.
     *
     * <p>Returns the tune itself when the length already fits, a padded tune
     * otherwise, or {@code null} when no safe frame exists within
     * {@value #PAD_SEARCH} frames of the end - which leaves the caller to drop
     * to a unit size that needs no padding.
     */
    public static @Nullable Tune padToUnit(Tune tune, int unit,
                                           IntPredicate safeToDuplicate) {
        int endPad = (unit - tune.frames % unit) % unit;
        if (endPad == 0) {
            return tune;
        }
        int atEnd = safeFrame(safeToDuplicate, tune.frames - 1,
                tune.frames - PAD_SEARCH);
        if (atEnd < 0) {
            return null;
        }
        Padding padding = new Padding(tune.frames, atEnd, endPad,
                tune.frames + endPad);
        return new Tune(padding.total, tune.frameRate, tune.masterClock, tune.loops,
                padding.rebase(tune.loopFrame),
                padding.stretch(tune.registers), padding.stretch(tune.codes),
                padding.stretch(tune.counts), padding.stretch(tune.shapes),
                tune.samples, tune.sampleLoops, tune.semantics,
                tune.name, tune.author, tune.comment, tune.notes);
    }

    /** The nearest safe frame at or before {@code from}, not before
     * {@code floor} and not further back than the search window. */
    private static int safeFrame(IntPredicate safe, int from, int floor) {
        int stop = Math.max(floor, from - (PAD_SEARCH - 1));
        for (int frame = from; frame >= stop; frame--) {
            if (safe.test(frame)) {
                return frame;
            }
        }
        return -1;
    }

    /** Which two frames are duplicated and how often - one plan, applied to
     * every stream, the only way they stay one timeline. */
    private record Padding(int frames, int atEnd, int endPad, int total) {

        /** Where a frame of the unpadded tune sits in the padded one. */
        int rebase(int frame) {
            return frame <= atEnd ? frame : frame + endPad;
        }

        /** One stream, duplicated frame for frame with the rest of them:
         * a shape that did not follow its registers would arm a buzzer on
         * the wrong one for as long as the padding lasts. */
        byte[] stretch(byte[] values) {
            return stretch(new byte[][] {values})[0];
        }

        byte[][] stretch(byte[][] streams) {
            byte[][] out = new byte[streams.length][];
            for (int stream = 0; stream < streams.length; stream++) {
                byte[] values = streams[stream];
                byte[] padded = new byte[total];
                int at = 0;
                for (int frame = 0; frame < frames; frame++) {
                    padded[at++] = values[frame];
                    if (frame == atEnd) {
                        for (int copy = 0; copy < endPad; copy++) {
                            padded[at++] = values[frame];
                        }
                    }
                }
                out[stream] = padded;
            }
            return out;
        }
    }

    private static byte[][] widen(byte[][] streams, int frames) {
        if (streams.length > YmxFormat.CHANNELS) {
            throw new IllegalArgumentException("a tune offers " + streams.length
                    + " timer channels and the format carries "
                    + YmxFormat.CHANNELS + "; widening cannot drop the rest"
                    + " quietly, so a front end with more to say has to say it"
                    + " to a format that has room");
        }
        if (streams.length == YmxFormat.CHANNELS) {
            return streams;
        }
        byte[][] out = Arrays.copyOf(streams, YmxFormat.CHANNELS);
        for (int channel = streams.length; channel < YmxFormat.CHANNELS; channel++) {
            out[channel] = new byte[frames];
        }
        return out;
    }
}
