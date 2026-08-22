package org.ymr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntPredicate;
import org.jspecify.annotations.Nullable;
import org.ymx.EffectScript;
import org.ymx.Tune;
import org.ymx.YmxEncoder;
import org.ymx.YmxFormat;

/**
 * Command-line .YMR to YMX packer: reads a RhYMe register dump and writes a
 * {@code .ymx} file that the 68000 {@code YMX.S} player streams through ST4.
 *
 * <p>It is {@link org.ym6.Ymx} with a different reader in front of it, and
 * deliberately so: every flag that means the same thing is spelled the same
 * way, and the report answers the same questions in the same order, because
 * the two tools feed the same player and a build script should not have to
 * depend on which one made a file.
 *
 * <p>What is missing from that list is what a .YMR does not have. There is no
 * {@code -drumhz}: a YM digidrum carries the rate it was sampled at and can be
 * resampled to fit a ceiling, while a .YMR sample is a stream of levels whose
 * rate is whatever its timer is programmed to on the frame it plays - there is
 * nothing to resample it against. There is no {@code -timers} either: the
 * timer-to-voice binding is normative in the .YMR spec, and a flag that let a
 * caller break it would only produce a file that plays the wrong voices. And
 * there is no {@code -sidresume}, because the phase model it selects is a YM
 * argument: RhYMe's PWM handler restarts at its loud half whenever the effect
 * is configured, which is the default model already.
 *
 * <p>Three timer channels is one more than a YM tune uses, so the player
 * decodes 23 of the format's 25 streams rather than 21, and the chunk C has to
 * cover them. The default 960/24 does, with one slot to spare.
 */
public final class Ymr {

    /** How many of the format's streams a three-channel tune makes the player
     * decode, which is the floor the chunk size has to clear. */
    private static final int LIVE_STREAMS = YmxFormat.liveStreams(YmxFormat.flagChannel(0)
            | YmxFormat.flagChannel(1) | YmxFormat.flagChannel(2));

    private Ymr() {}

    public static void main(String[] args) {
        // -script: the compiled effect script, one line per acting frame -
        // the debugging window into what the streams will carry, and the
        // quickest way to see that a channel started the effect it should.
        if (args.length == 2 && args[0].equals("-script")) {
            YmrReader.Song dump = read(args[1]);
            Tune converted = YmrEffects.convert(dump, stem(args[1]));
            EffectScript.Result script = EffectScript.compile(converted,
                    dump.loops() ? dump.loopFrame() : -1, 1, YmrEffects.TIMERS);
            System.out.printf("%d frames, split %d%n", script.frames(), script.split());
            for (int f = 0; f < script.frames(); f++) {
                if (script.m()[f] == 0 && script.r7force()[f] == 0) {
                    continue;
                }
                StringBuilder line = new StringBuilder(String.format(
                        "%6d  M=%02X X=%02X T=%02X", f, script.m()[f] & 0xFF,
                        script.x()[f] & 0xFF, script.timers()[f] & 0xFF));
                for (int c = 0; c < script.actions().length; c++) {
                    line.append(String.format(" A%d=%02X P%d=%3d", c,
                            script.actions()[c][f] & 0xFF, c,
                            script.counts()[c][f] & 0xFF));
                }
                System.out.printf("%s R7|=%02X%n", line, script.r7force()[f] & 0xFF);
            }
            converted.notes().forEach(n -> System.out.println("note: " + n));
            script.notes().forEach(n -> System.out.println("note: " + n));
            return;
        }
        System.out.println("YMX: .YMR chiptune packer v1.0 by Robbert van Dalen, "
                + "streaming ST4");

        int ringSize = YmxFormat.DEFAULT_RING_SIZE;
        int chunk = YmxFormat.DEFAULT_CHUNK;
        int unit = 0;                           // 0 until chosen: -kK, or the
        int loopFrame = -1;                     // tune's shape; -1 likewise
        boolean playOnce = false;
        boolean forcedMode = false;
        int startMin = 0;                       // the trim window, for zooming
        int startSec = 0;                       // in on a moment of a tune
        int startFrame = -1;
        int endFrame = -1;
        int frameCount = -1;
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            switch (args[i]) {
                case "-f" -> forcedMode = true;
                case "-o" -> playOnce = true;
                default -> {
                    if (args[i].startsWith("-startframe")) {
                        startFrame = parseNumber(args[i].substring(11), true);
                    } else if (args[i].startsWith("-endframe")) {
                        endFrame = parseNumber(args[i].substring(9), true);
                    } else if (args[i].startsWith("-frames")) {
                        frameCount = parseNumber(args[i].substring(7), true);
                    } else if (args[i].startsWith("-min")) {
                        startMin = parseNumber(args[i].substring(4), true);
                    } else if (args[i].startsWith("-sec")) {
                        startSec = parseNumber(args[i].substring(4), true);
                    } else if (args[i].startsWith("-n")) {
                        ringSize = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-c")) {
                        chunk = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-k")) {
                        unit = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-l")) {
                        loopFrame = parseNumber(args[i].substring(2), true);
                    } else {
                        throw error("Invalid parameter " + args[i]);
                    }
                }
            }
        }

        // A trailing DIRECTORY collects a whole set: every argument before it
        // is an input, each packed with the identical configuration into
        // <dir>/<stem>.ymx - the shape a multi-tune player needs, since one
        // player build serves one unit size and one workspace.
        if (args.length - i >= 2 && Files.isDirectory(Path.of(args[args.length - 1]))) {
            if (startMin != 0 || startSec != 0 || startFrame >= 0
                    || endFrame >= 0 || frameCount >= 0) {
                throw error("the trim options take one tune, not a set");
            }
            if (unit == 0) {
                unit = 2;               // uniform by construction: padding
            }                           // makes any shape fit, or fails loudly
            Path dir = Path.of(args[args.length - 1]);
            for (int input = i; input < args.length - 1; input++) {
                packOne(args[input], dir.resolve(stem(args[input]) + ".ymx").toString(),
                        ringSize, chunk, unit, loopFrame, playOnce, forcedMode,
                        0, 0, -1, -1, -1);
            }
            return;
        }

        String outputName;
        if (args.length == i + 1) {
            outputName = args[i] + ".ymx";
        } else if (args.length == i + 2) {
            outputName = args[i + 1];
        } else {
            usage("""
                    Usage: ymr [-f] [-o] [-nN] [-cC] [-kK] [-lF] input.ymr [output.ymx]
                           ymr [options] one.ymr two.ymr more.ymr output-dir/
                      -f      Force overwrite of output file
                      -o      Play once: pack no loop section
                      -nN     Ring size per stream, in bytes (default 960)
                      -cC     Values decoded per call, and the round-robin group
                              size (default 24; N mod C = 0, and C at least the
                              streams the tune decodes: a .ymr fills three timer
                              channels, so that is 23)
                      -kK     ST4 unit size: 1, 2 or 4 (default 2). An odd
                              tune length or loop frame is padded with safe
                              duplicate frames - inaudible - to fit the unit.
                              The player must be built with the same ST4_UNIT
                      -lF     Loop from frame F, overriding the .ymr header
                      -minM -secS   Trim: drop everything before M:S, so a
                              moment deep in a long tune plays immediately
                      -startframeF -endframeF -framesN   The same window in
                              frames: start, end, or a length cap
                      -script Dump the compiled effect script instead of
                              packing: one line per frame anything acts on

                    The input is a RhYMe .YMR version 1.3 register dump. Timer
                    A, B and D drive voices A, B and C - the spec's normative
                    binding - and become timer channels 0, 1 and 2, so no flag
                    chooses them. A .YMR carries no title or author, so the
                    file's own stem is the name the report prints.

                    A conversion is not a copy, and the few things this one
                    has to change it counts and reports as it goes. What they
                    are and what each costs is "What a .ymr gives up" in
                    doc/CONVERSION.md.""");
            return;
        }
        packOne(args[i], outputName, ringSize, chunk, unit, loopFrame, playOnce,
                forcedMode, startMin, startSec, startFrame, endFrame, frameCount);
    }

    /** The whole pipeline for one tune: read, convert, trim, pad, pack, write,
     * report - the same order {@link org.ym6.Ymx} runs it in. */
    private static void packOne(String inputName, String outputName, int ringSize,
                                int chunk, int unit, int loopFrame, boolean playOnce,
                                boolean forcedMode, int startMin, int startSec,
                                int startFrame, int endFrame, int frameCount) {
        // The floor only: how many streams a tune decodes depends on the
        // channels it names, which the encoder derives when it compiles the
        // script and checks again there.
        String problem = YmxFormat.checkShape(ringSize, chunk, Math.max(unit, 1),
                YmxFormat.STREAM_A0);
        if (!problem.isEmpty()) {
            throw error(problem);
        }

        Path outputPath = Path.of(outputName);
        if (!forcedMode && Files.exists(outputPath)) {
            throw error("Already existing output file " + outputName);
        }

        YmrReader.Song dump = read(inputName);
        Tune tune = YmrEffects.convert(dump, stem(inputName));

        // The trim window: -minM -secS (or -startframeF) picks where to start,
        // -framesN (or -endframeF) how much to keep. The registers and the
        // timer streams are cut together, since they are one timeline; an
        // effect still running at the first kept frame simply starts there,
        // which is what its code byte arriving out of nowhere means.
        int rate = dump.frameRate();
        int start = startFrame >= 0 ? startFrame : (startMin * 60 + startSec) * rate;
        int end = dump.frameCount();
        if (endFrame >= 0) {
            end = Math.min(end, endFrame);
        }
        if (frameCount >= 0) {
            end = Math.min(end, start + frameCount);
        }
        boolean loops = dump.loops();
        if (start > 0 || end < dump.frameCount()) {
            if (start < 0 || start >= end) {
                throw error("Empty trim window: frames " + start + ".." + end
                        + " of " + dump.frameCount());
            }
            // A loop frame inside the kept window is kept, adjusted; one
            // outside it makes the excerpt loop from its own start.
            int kept = loops && dump.loopFrame() >= start && dump.loopFrame() < end
                    ? dump.loopFrame() - start : 0;
            tune = trim(tune, start, end, kept);
            System.out.printf("Trimmed to frames %d-%d: %d frames%n",
                    start, end - 1, end - start);
        }

        // The .ymr header's loop frame is the default; -lF overrides it and -o
        // drops the loop altogether. A song the header says plays once has no
        // loop to inherit, so it stays a play-once file.
        if (loopFrame < 0 && !playOnce && loops) {
            loopFrame = tune.loopFrame();
        }
        if (playOnce) {
            loopFrame = -1;
        }
        if (loopFrame >= tune.frames()) {
            System.out.printf("Warning: the loop frame is %d in a %d-frame tune;"
                    + " looping from the start instead%n", loopFrame, tune.frames());
            loopFrame = 0;
        }

        // The default unit is 2, measured a few percent cheaper per frame for
        // little ratio. A tune whose length or loop frame is odd is PADDED to
        // the shape with duplicated safe frames; an explicit -kK pads the same
        // way and drops to -k1 only when no safe frame exists.
        if (unit == 0 && chunk % 2 == 0) {
            Tune padded = padToUnit(tune, loopFrame, 2);
            if (padded != null) {
                if (padded != tune) {
                    tune = padded;
                    loopFrame = loopFrame > 0 ? tune.loopFrame() : loopFrame;
                }
                unit = 2;
            } else {
                unit = 1;
                System.out.println("Packing at -k1: this tune's shape is not "
                        + "a whole number of 2-byte units, and no frame near "
                        + "the boundary is safe to duplicate");
            }
        } else if (unit == 0) {
            unit = 1;
        } else if (unit > 1) {
            Tune padded = padToUnit(tune, loopFrame, unit);
            if (padded != null && padded != tune) {
                tune = padded;
                loopFrame = loopFrame > 0 ? tune.loopFrame() : loopFrame;
            }
        }

        YmxEncoder.Result result;
        try {
            result = YmxEncoder.encode(tune, ringSize, chunk, loopFrame, true, unit,
                    YmrEffects.TIMERS);
        } catch (IllegalArgumentException e) {
            // The encoder always says what it rejected, but getMessage() is
            // @Nullable, so give it something to fall back on - and where the
            // chunk is what it rejected, say why a .ymr needs a big one.
            String reason = e.getMessage();
            if (reason == null) {
                reason = "cannot pack this tune with these options";
            }
            if (reason.contains("chunk")) {
                reason += " - a .ymr fills three timer channels, so the player decodes "
                        + LIVE_STREAMS + " streams and C must cover them";
            }
            throw error(reason);
        }
        try {
            Files.write(outputPath, result.file());
        } catch (IOException e) {
            throw error("Cannot write output file " + outputName);
        }

        report(tune, result);
    }

    // ------------------------------------------------------- shaping the tune

    /**
     * The kept window of every timeline at once, and the loop frame inside it.
     *
     * <p>The registers and the timer streams are cut together because they are
     * one timeline: a frame is a column across all of them, and cutting one and
     * not the others would leave every effect from that frame on playing
     * against the wrong registers. Everything else about a tune - its name, its
     * rate, its samples, the notes the conversion left - is untouched by
     * shortening it.
     */
    private static Tune trim(Tune tune, int start, int end, int loopFrame) {
        return new Tune(end - start, tune.frameRate(), tune.masterClock(), loopFrame,
                slice(tune.registers(), start, end), slice(tune.codes(), start, end),
                slice(tune.counts(), start, end),
                java.util.Arrays.copyOfRange(tune.shapes(), start, end),
                tune.samples(), tune.sampleLoops(), tune.semantics(),
                tune.name(), tune.author(), tune.comment(), tune.notes());
    }

    /**
     * Pads the tune to whole {@code unit}s, with the .YMR's own idea of which
     * frame may be duplicated: {@link Tune#padToUnit} does the work and this
     * says what is safe.
     *
     * <p>Returns the tune itself when the shape already fits, the padded tune
     * otherwise - or null when no safe frame exists near a boundary that needs
     * one, which is the caller's cue to drop to {@code -k1}.
     */
    private static @Nullable Tune padToUnit(Tune tune, int loopFrame, int unit) {
        Tune padded = Tune.padToUnit(tune, loopFrame, unit, safeToDuplicate(tune));
        if (padded != null && padded != tune) {
            int added = padded.frames() - tune.frames();
            System.out.printf("Padded %d frame%s (duplicates of safe frames) so the "
                    + "shape is whole %d-byte units%n", added, added == 1 ? "" : "s",
                    unit);
        }
        return padded;
    }

    /**
     * Which frames may be duplicated without being heard: R13 quiet, and no
     * channel carrying a PCM code.
     *
     * <p>A trigger frame is the obvious thing to keep away from, but any frame
     * inside a sample's run is just as bad, and for a subtler reason: the
     * script computes where a sample ends in PLAYED frames from the length of
     * the sample and the rate it plays at, while the code byte's own window was
     * measured in dump frames. Slip an extra frame between the two and the
     * script returns the voice to the frame write one frame before the code
     * lets go, and
     * the sample number sitting in the volume register is written to the chip
     * as a volume. There is no shortage of quiet frames, so the cheap rule is
     * the right one.
     */
    private static IntPredicate safeToDuplicate(Tune tune) {
        byte[] shape = tune.registers()[13];
        byte[][] codes = tune.codes();
        return frame -> {
            if ((shape[frame] & 0xFF) != YmrReader.NO_ENVELOPE_SHAPE) {
                return false;                   // this frame restarts the
            }                                   // envelope: not twice
            for (byte[] code : codes) {
                if ((code[frame] & 0xC0) == Tune.KIND_PCM) {
                    return false;
                }
            }
            return true;
        };
    }

    private static byte[][] slice(byte[][] streams, int start, int end) {
        byte[][] out = new byte[streams.length][];
        for (int stream = 0; stream < streams.length; stream++) {
            out[stream] = java.util.Arrays.copyOfRange(streams[stream], start, end);
        }
        return out;
    }

    // ------------------------------------------------------------ the report

    private static void report(Tune tune, YmxEncoder.Result result) {
        System.out.printf("%s: %s (a .ymr carries no title, so this is the file's own)%n",
                YmrReader.MAGIC, tune.name().isBlank() ? "(untitled)" : tune.name());
        System.out.println("Timer A drives voice A on channel 0, Timer B voice B on "
                + "channel 1, Timer D voice C on channel 2");
        if (tune.samples().length > 0) {
            int bytes = 0;
            for (byte[] sample : tune.samples()) {
                bytes += sample.length + 1;
            }
            System.out.printf("%d sample%s, %d bytes%n", tune.samples().length,
                    tune.samples().length == 1 ? "" : "s", bytes);
        }
        for (String note : tune.notes()) {
            System.out.println("Warning: " + note);
        }

        // The PLAYED length, not the tune's: a rotated split hands the file
        // some frames twice, and those bytes are in it and were packed. The
        // line above still counts the tune, because that is the length a
        // musician has - but a ratio has to be against what was packed.
        int raw = result.script().frames() * YmxFormat.STREAMS;    // registers and script alike
        System.out.printf("%d frames at %d Hz (%d:%02d), %d rings of %d bytes,"
                        + " %d per call%n", tune.frames(), tune.frameRate(),
                tune.frames() / tune.frameRate() / 60,
                tune.frames() / tune.frameRate() % 60,
                YmxFormat.STREAMS, result.ringSize(), result.chunk());
        System.out.println(result.loops()
                ? result.loopFrame() == 0
                        ? "Loops from the start"
                        : "Plays frames 0-" + (result.loopFrame() - 1)
                                + ", then loops from frame " + result.loopFrame()
                : "Plays once, then stops");
        String[] scriptNames = {"M ", "X ", "T ", "A0", "P0", "A1", "P1",
                                "A2", "P2", "A3", "P3"};
        for (YmxEncoder.Stream stream : result.streams()) {
            String name = stream.register() < YmxFormat.REGISTER_STREAMS
                    ? String.format("R%-2d", stream.register())
                    : scriptNames[stream.register() - YmxFormat.REGISTER_STREAMS] + " ";
            System.out.printf("  %s %-5s %6d -> %6d bytes (%5.1f%%)%n", name,
                    stream.loop() ? "loop" : "intro", stream.frames(),
                    stream.packedSize(), 100.0 * stream.packedSize() / stream.frames());
        }
        System.out.printf("Packed %d register bytes into %d (%.1f%%), file %d bytes%n",
                raw, result.packedSize(), 100.0 * result.packedSize() / raw,
                result.file().length);
        int flags = ((result.file()[YmxFormat.OFFSET_FLAGS] & 0xFF) << 8)
                | (result.file()[YmxFormat.OFFSET_FLAGS + 1] & 0xFF);
        // The chunk is a slot count: one stream refilled per call, so it has
        // to cover the streams this tune DECODES, not all the format defines.
        int live = YmxFormat.liveStreams(flags);
        System.out.printf("Player needs %d bytes of ring plus its state, and"
                        + " decodes %d of the %d streams - one refill a call,"
                        + " so C=%d covers them with %d slots idle%n",
                YmxFormat.STREAMS * result.ringSize(), live, YmxFormat.STREAMS,
                result.chunk(), result.chunk() - live);
        for (String note : result.script().notes()) {
            System.out.println(note);
        }

        if (result.longestOp() > 65535) {
            // A literal run, the one operation ZX1 cannot split. Only a tune
            // longer than 65535 frames with a register that never repeats can
            // reach this, and the 68000 decoder would mis-decode it.
            System.out.printf("Warning: longest operation is %d bytes, over the 65535"
                    + " the 68000 decoder can represent: do not play this file%n",
                    result.longestOp());
        }
    }

    // -------------------------------------------------------------- the fuss

    private static YmrReader.Song read(String inputName) {
        byte[] input;
        try {
            input = Files.readAllBytes(Path.of(inputName));
        } catch (IOException e) {
            throw error("Cannot access input file " + inputName);
        }
        try {
            return YmrReader.read(input);
        } catch (YmrReader.FormatException e) {
            throw error(inputName + ": " + e.getMessage());
        }
    }

    /** The file's own name, which is the only name a .YMR has. */
    private static String stem(String path) {
        return Path.of(path).getFileName().toString().replaceAll("(?i)\\.ymr$", "");
    }

    private static RuntimeException error(String message) {
        System.err.println("Error: " + message);
        System.exit(1);
        throw new AssertionError("unreachable");
    }

    private static void usage(String text) {
        System.err.println(text);
        System.exit(1);
    }

    private static int parseNumber(String argument) {
        return parseNumber(argument, false);
    }

    /**
     * Parses a numeric argument, refusing a negative one and, unless
     * {@code zeroAllowed}, a zero.
     *
     * <p>Zero is a real answer for a loop frame and for every part of the trim
     * window - {@code -min0 -sec13} is how a caller says thirteen seconds in,
     * and {@code -startframe0} says the same thing again. It is nonsense for a
     * ring, a chunk, a unit or a rate ceiling, which is why the default stands
     * for those. A window that comes out empty is caught where the window is
     * worked out, and says so in those words rather than as a bad parameter.
     */
    private static int parseNumber(String argument, boolean zeroAllowed) {
        try {
            int value = Integer.parseInt(argument);
            if (value < 0 || (value == 0 && !zeroAllowed)) {
                throw error("Invalid parameter value " + argument);
            }
            return value;
        } catch (NumberFormatException e) {
            throw error("Invalid parameter value " + argument);
        }
    }
}
