package org.ym6;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.IntPredicate;
import org.jspecify.annotations.Nullable;
import org.ymx.EffectScript;
import org.ymx.Tune;
import org.ymx.YmxEncoder;
import org.ymx.YmxFormat;

/**
 * Command-line YM to YMX packer: reads a YM5!/YM6! register dump and writes a
 * {@code .ymx} file that the 68000 {@code YMX.S} player streams through ST4.
 *
 * <p>The player covers the fourteen standard YM2149 registers and the YM
 * special effects - digidrums, SID voices, the sync-buzzer - extracted into
 * their own streams; only sinus-SID is dropped, for which the reference
 * player runs an empty handler.
 *
 * <p>The class is named after the format it writes rather than the one it
 * reads, which reads oddly beside the engine's own names in the package next
 * door. It stays that way because the name is published: the pom's
 * {@code exec:exec@ymx} profile, the READMEs and the test rigs all spell it
 * out on a command line, and a rename would break every one of them to
 * settle a matter of taste.
 */
public final class Ymx {

    private Ymx() {}

    public static void main(String[] args) {
        // -meta: the YM header's strings and rate, one per line, for the
        // build scripts to carry into SNDH tags. Nothing else runs.
        if (args.length == 2 && args[0].equals("-meta")) {
            Ym6Reader.Song song;
            try {
                song = Ym6Reader.read(Files.readAllBytes(Path.of(args[1])));
            } catch (IOException | Ym6Reader.FormatException e) {
                throw error(args[1] + ": " + e.getMessage());
            }
            System.out.println(song.name().strip());
            System.out.println(song.author().strip());
            System.out.println(song.playerHz());
            return;
        }
        // -script: the compiled effect script, one line per acting frame -
        // the debugging window into what the script streams will carry.
        if (args.length == 2 && args[0].equals("-script")) {
            Ym6Reader.Song song;
            try {
                song = Ym6Reader.read(Files.readAllBytes(Path.of(args[1])));
            } catch (IOException | Ym6Reader.FormatException e) {
                throw error(args[1] + ": " + e.getMessage());
            }
            EffectScript.Result script = EffectScript.compile(YmEffects.tune(song));
            System.out.printf(Locale.ROOT, "%d frames%n", script.frames());
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
                System.out.printf(Locale.ROOT, "%s R7|=%02X%n", line,
                        script.r7force()[f] & 0xFF);
            }
            script.notes().forEach(n -> System.out.println("note: " + n));
            return;
        }
        System.out.println("YMX: YM chiptune packer v"
                + YmxFormat.releaseName() + " by Robbert van Dalen, "
                + "streaming ST4");

        Flags flags = parseFlags(args);
        int i = flags.inputs;

        // A trailing DIRECTORY collects a whole set: every argument before it
        // is an input, each packed with the identical configuration into
        // <dir>/<stem>.ymx - the shape a multi-tune player needs, since one
        // player build serves one unit size and one workspace.
        if (args.length - i >= 2 && Files.isDirectory(Path.of(args[args.length - 1]))) {
            if (flags.startMin != 0 || flags.startSec != 0 || flags.startFrame >= 0
                    || flags.endFrame >= 0 || flags.frameCount >= 0) {
                throw error("the trim options take one tune, not a set");
            }
            if (flags.unit == 0) {
                flags.unit = 2;         // uniform by construction: padding
            }                           // makes any shape fit, or fails loudly
            Path dir = Path.of(args[args.length - 1]);
            for (int input = i; input < args.length - 1; input++) {
                String stem = Path.of(args[input]).getFileName().toString()
                        .replaceAll("(?i)\\.ym$", "");
                packOne(args[input], dir.resolve(stem + ".ymx").toString(),
                        flags.ringSize, flags.chunk, flags.unit, flags.playOnce,
                        flags.forcedMode, flags.drumHz, flags.sidResume,
                        flags.timerMap, 0, 0, -1, -1, -1, flags.loopFrame, flags.copies);
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
                    Usage: YMX [-f] [-o] [-lF] [-nN] [-cC] [-kK] input.ym [output.ymx]
                           ymx [options] one.ym two.ym more.ym output-dir/
                      -f      Force overwrite of output file
                      -o      Play once: stop at the end instead of starting over
                      -lF     Start over from frame F rather than from the frame
                              the header gives; -l0 starts over from the
                              beginning. Where the wrap cannot enter F the packer
                              takes the next frame it can and says so
                      -nN     Ring size per stream, in bytes (default 960)
                      -cC     Values decoded per call, and the round-robin group
                              size (default 24; N mod C = 0, and C at
                              least the streams the tune decodes: 17 with
                              no timer channel, 21 for a YM tune, 25 for
                              one that uses all four)
                      -kK     ST4 unit size: 1, 2 or 4 (default 2). An odd
                              tune length is padded with safe duplicate frames
                              - inaudible - to fit the unit. The player must be
                              built with the same ST4_UNIT
                      -copies Let a match beyond the ring copy from the literal
                              stream; the player must then be built with
                              ST4_WINDOW = N/K, and mksndh takes the -copies core
                      -copiesS   The same, searching S seconds a stream for a
                              better parse
                      -minM -secS   Trim: drop everything before M:S, so a
                              moment deep in a long tune plays immediately
                      -drumhzH   The drum rate ceiling (default 25600): a drum
                              asking for a faster timer is downsampled to fit,
                              with a warning
                      -timersT   Which MFP timer each channel runs on, one
                              letter per channel from 0 up: -timersBC puts
                              channel 0 on Timer B and channel 1 on Timer C.
                              The default is AD, where a YM tune has always
                              played. Timer C is the system's 200 Hz clock,
                              so a tune that takes it stops that clock and
                              cannot be hosted from a Timer C interrupt
                      -sidresume   The maxYMiser SID gap model: a released
                              SID's timer keeps counting and a re-arrival
                              resumes its phase. Default: the ym2149-rs
                              model, phase-zero restarts
                      -startframeF -endframeF -framesN   The same window in
                              frames: start, end, or a length cap
                      -script Dump the compiled effect script instead of
                              packing: one line per frame anything acts on
                      -meta   Print the header's title, author and frame rate,
                              one per line, and pack nothing - what the build
                              scripts read for the SNDH tags

                    The input is a YM5!/YM6! dump, LHA-archived or already
                    unpacked - the reader tells them apart by itself. With a
                    trailing DIRECTORY, every argument before it is an input,
                    packed with the same configuration - the set one player
                    build can hold as subtunes.""");
            return;
        }
        packOne(args[i], outputName, flags.ringSize, flags.chunk, flags.unit,
                flags.playOnce, flags.forcedMode, flags.drumHz, flags.sidResume,
                flags.timerMap, flags.startMin, flags.startSec, flags.startFrame,
                flags.endFrame, flags.frameCount, flags.loopFrame, flags.copies);
    }

    /** The command line's flags, and where the inputs start. */
    private static final class Flags {
        int ringSize = YmxFormat.DEFAULT_RING_SIZE;
        int chunk = YmxFormat.DEFAULT_CHUNK;
        int unit = 0;                           // 0 until chosen: -kK, or the
        boolean playOnce = false;               // tune's shape
        boolean forcedMode = false;
        boolean sidResume = false;
        int startMin = 0;                       // the trim window, for zooming
        int startSec = 0;                       // in on a moment of a tune
        int startFrame = -1;
        int endFrame = -1;
        int frameCount = -1;
        int loopFrame = -1;                     // -1 until -lF: the header's own
        int drumHz = YmEffects.MAX_TIMER_HZ;
        int timerMap = YmxFormat.DEFAULT_TIMERS;
        double copies = -1;                     // -copies: 0 the opening passes,
                                                // S a search of S seconds
        int inputs = 0;                         // the first argument that is
    }                                           // not a flag

    /**
     * Reads the leading flags and stops the tool on one the packer does not
     * have. Nothing is opened and nothing is written, so a front end reads its
     * flags through this before it makes a work directory and a bad flag
     * leaves none behind.
     */
    public static void checkFlags(java.util.List<String> flags) {
        parseFlags(flags.toArray(new String[0]));
    }

    private static Flags parseFlags(String[] args) {
        Flags flags = new Flags();
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            switch (args[i]) {
                case "-f" -> flags.forcedMode = true;
                case "-o" -> flags.playOnce = true;
                case "-sidresume" -> flags.sidResume = true;
                default -> {
                    if (args[i].startsWith("-timers")) {
                        flags.timerMap = parseTimers(args[i].substring(7));
                    } else if (args[i].startsWith("-drumhz")) {
                        flags.drumHz = parseNumber(args[i].substring(7));
                    } else if (args[i].startsWith("-startframe")) {
                        flags.startFrame = parseNumber(args[i].substring(11), true);
                    } else if (args[i].startsWith("-endframe")) {
                        flags.endFrame = parseNumber(args[i].substring(9), true);
                    } else if (args[i].startsWith("-frames")) {
                        flags.frameCount = parseNumber(args[i].substring(7), true);
                    } else if (args[i].startsWith("-min")) {
                        flags.startMin = parseNumber(args[i].substring(4), true);
                    } else if (args[i].startsWith("-sec")) {
                        flags.startSec = parseNumber(args[i].substring(4), true);
                    } else if (args[i].startsWith("-n")) {
                        flags.ringSize = parseNumber(args[i].substring(2));
                    } else if (args[i].equals("-copies")) {
                        flags.copies = 0;
                    } else if (args[i].startsWith("-copies")) {
                        flags.copies = parseNumber(args[i].substring(7));
                    } else if (args[i].startsWith("-c")) {
                        flags.chunk = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-k")) {
                        flags.unit = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-l")) {
                        flags.loopFrame = parseNumber(args[i].substring(2), true);
                    } else {
                        throw error("Invalid parameter " + args[i]);
                    }
                }
            }
        }
        flags.inputs = i;
        return flags;
    }

    /** The whole pipeline for one tune: read, trim, pad, pack, write, report. */
    /**
     * {@code -timersABCD}: which MFP timer each channel runs on, one letter
     * per channel from channel 0 up. Letters left off keep the default, so
     * {@code -timersBC} moves the two channels a YM tune uses onto Timers B
     * and C and leaves the rest alone. Two channels may not name the same
     * timer.
     */
    private static int parseTimers(String spec) {
        int map = YmxFormat.DEFAULT_TIMERS;
        if (spec.isEmpty() || spec.length() > YmxFormat.CHANNELS) {
            throw error("-timers takes one letter per channel, up to "
                    + YmxFormat.CHANNELS + ": -timersBC, say");
        }
        boolean[] taken = new boolean[4];
        int[] timers = new int[YmxFormat.CHANNELS];
        java.util.Arrays.fill(timers, -1);
        for (int channel = 0; channel < spec.length(); channel++) {
            int timer = "ABCD".indexOf(Character.toUpperCase(spec.charAt(channel)));
            if (timer < 0) {
                throw error("-timers: '" + spec.charAt(channel)
                        + "' is not one of the MFP's timers A, B, C or D");
            }
            if (taken[timer]) {
                throw error("-timers: two channels cannot both run on Timer "
                        + "ABCD".charAt(timer));
            }
            taken[timer] = true;
            timers[channel] = timer;
        }
        // The channels the spec left out take the timers it did not, in
        // order, so the map stays a permutation however short the spec is.
        int spare = 0;
        map = 0;
        for (int channel = 0; channel < YmxFormat.CHANNELS; channel++) {
            if (timers[channel] < 0) {
                while (taken[spare]) {
                    spare++;
                }
                taken[spare] = true;
                timers[channel] = spare;
            }
            map |= timers[channel] << (2 * channel);
        }
        return map;
    }

    private static void packOne(String inputName, String outputName, int ringSize,
                                int chunk, int unit, boolean playOnce,
                                boolean forcedMode, int drumHz, boolean sidResume,
                                int timerMap, int startMin, int startSec, int startFrame,
                                int endFrame, int frameCount, int loopFrame,
                                double copies) {
        // The floor only: how many streams a tune decodes depends on the
        // channels it names, which the encoder derives when it compiles the
        // script and checks again there.
        String problem = YmxFormat.checkShape(ringSize, chunk, Math.max(unit, 1),
                YmxFormat.STREAM_A0);
        if (!problem.isEmpty()) {
            throw error(problem);
        }

        byte[] input;
        try {
            input = Files.readAllBytes(Path.of(inputName));
        } catch (IOException e) {
            throw error("Cannot access input file " + inputName);
        }

        Path outputPath = Path.of(outputName);
        if (!forcedMode && Files.exists(outputPath)) {
            throw error("Already existing output file " + outputName);
        }

        Ym6Reader.Song song;
        try {
            song = Ym6Reader.read(input);
        } catch (Ym6Reader.FormatException e) {
            throw error(inputName + ": " + e.getMessage());
        }

        // The trim window: -minM -secS (or -startframeF) picks where to start,
        // -framesN (or -endframeF) how much to keep - everything before and
        // after is dropped, so a moment deep in a long tune plays immediately.
        int start = startFrame >= 0 ? startFrame
                : (startMin * 60 + startSec) * song.playerHz();
        int end = song.frames();
        if (endFrame >= 0) {
            end = Math.min(end, endFrame);
        }
        if (frameCount >= 0) {
            end = Math.min(end, start + frameCount);
        }
        if (start > 0 || end < song.frames()) {
            if (start < 0 || start >= end) {
                throw error("Empty trim window: frames " + start + ".." + end
                        + " of " + song.frames());
            }
            byte[][] cut = new byte[song.registers().length][];
            for (int r = 0; r < cut.length; r++) {
                cut[r] = java.util.Arrays.copyOfRange(song.registers()[r], start, end);
            }
            // The loop frame is a frame number, so it rebases on the first kept
            // frame; one outside the window is no longer a frame of this tune,
            // and the excerpt starts over from its own first frame.
            long kept = song.loopFrame() >= start && song.loopFrame() < end
                    ? song.loopFrame() - start : 0;
            if (song.loopFrame() != 0 && kept == 0) {
                System.out.printf(Locale.ROOT, "Frame %d, which the header loops from, is outside"
                        + " the kept window: the excerpt starts over from its own"
                        + " first frame%n", song.loopFrame());
            }
            song = new Ym6Reader.Song(song.format(), end - start, song.playerHz(),
                    song.masterClock(), kept, song.interleaved(), song.attributes(),
                    song.drums(), song.name(), song.author(), song.comment(), cut);
            System.out.printf(Locale.ROOT, "Trimmed to frames %d-%d: %d frames%n",
                    start, end - 1, end - start);
        }

        // The boundary: from here on the tune is the engine's, and the dump is
        // kept only for what the report and the padding rule have to say about
        // the FILE - its dialect, its strings, where its effect codes sit. The
        // extraction happens here rather than inside the encoder because its
        // drop counters are a statement about the file, and padding, which
        // comes next, invents frames the file never had.
        YmEffects.Extraction effects = YmEffects.extract(song, drumHz);
        Tune tune = YmEffects.tune(song, effects);
        // -sidresume is the one source semantic a listener picks rather than a
        // format: no YM file records which gap model its own player used, so
        // the front end's answer is a default and this overrides it, the way
        // -o overrides what the header says the end of the tune does.
        if (sidResume) {
            tune = tune.under(tune.semantics().resuming());
        }
        // -lF says where the tune starts over, in the frames of the tune being
        // packed: a trim has already moved what F counts from. The packer
        // answers for the frame either way, whether the header gave it or the
        // command line did.
        if (loopFrame >= 0) {
            if (loopFrame >= tune.frames()) {
                throw error("-l" + loopFrame + " is past the tune's "
                        + tune.frames() + " frames");
            }
            tune = tune.startingOverAt(loopFrame);
        }

        // A YM header always names a loop frame and its players always went
        // round, so a YM tune starts over unless -o says otherwise.
        boolean startsOver = !playOnce;

        // The default unit is 2, measured a few percent cheaper per frame for
        // little ratio. A tune of odd length is PADDED to the shape: a
        // duplicated frame holds the chip state one tick longer, which is
        // inaudible as long as the duplicate neither writes R13 (an envelope
        // restart) nor triggers a drum - the packer scans for a safe frame and
        // says what it did. An explicit -kK pads the same way and fails loudly
        // only when no safe frame exists.
        boolean unitAsked = unit != 0;
        Tune unpadded = tune;
        if (unit == 0 && chunk % 2 == 0) {
            Tune padded = padToUnit(song, tune, 2);
            if (padded != null) {
                tune = padded;
                unit = 2;
            } else {
                unit = 1;
                System.out.println("Packing at -k1: this tune's length is not "
                        + "a whole number of 2-byte units, and no frame near "
                        + "the end is safe to duplicate");
            }
        } else if (unit == 0) {
            unit = 1;
        } else if (unit > 1) {
            Tune padded = padToUnit(song, tune, unit);
            if (padded != null) {
                tune = padded;
            }
        }

        YmxEncoder.Result result;
        try {
            result = YmxEncoder.encode(tune, ringSize, chunk, startsOver, true, unit,
                    timerMap, copies);
            // A section is a whole number of units, so a cut falls on a unit
            // boundary and a loop point that is not one leaves the tune
            // starting over from frame 0. Every frame is a boundary at unit 1:
            // where the unit was not asked for, the packer packs at 1 and
            // keeps the loop point.
            if (!unitAsked && unit > 1 && startsOver && unpadded.loopFrame() > 0
                    && result.loopFrame() != unpadded.loopFrame()) {
                // The pack at the shape asked for has already succeeded, so
                // a shape the encoder rejects here leaves that one standing
                // rather than failing the tune.
                YmxEncoder.Result atOne;
                try {
                    atOne = YmxEncoder.encode(unpadded, ringSize, chunk,
                            startsOver, true, 1, timerMap, copies);
                } catch (IllegalArgumentException rejected) {
                    atOne = result;
                }
                // Unit 1 stands only where it starts the tune over where the
                // source says. Where the frame moved for another reason it
                // moves at unit 1 too, and the shape asked for is the cheaper
                // of the two.
                if (atOne != result && atOne.loopFrame() == unpadded.loopFrame()) {
                    unit = 1;
                    tune = unpadded;
                    result = atOne;
                    System.out.printf(Locale.ROOT, "Packing at -k1: frame %d, where"
                            + " this tune starts over, is not a whole number of"
                            + " 2-byte units, and a section is%n",
                            result.loopFrame());
                }
            }
        } catch (IllegalArgumentException e) {
            // The encoder always says what it rejected, but getMessage() is
            // @Nullable, so give it something to fall back on.
            String reason = e.getMessage();
            throw error(reason != null ? reason : "cannot pack this tune with these options");
        }
        try {
            Files.write(outputPath, result.file());
        } catch (IOException e) {
            throw error("Cannot write output file " + outputName);
        }

        report(song, effects, result);
    }

    /**
     * Pads the tune to whole {@code unit}s, with the YM dump's own idea of
     * which frame may be duplicated: {@link Tune#padToUnit} does the work and
     * this says what is safe. The mechanism is the engine's because a frame is
     * a column across every stream and stretching one and not the others would
     * put the effects a frame out; the rule is the dump's because only a YM
     * reader can see a drum trigger in R1 or R3.
     *
     * <p>Returns the tune itself when the length already fits, the padded tune
     * otherwise - or null when no safe frame exists near the end, the caller's
     * cue to drop to {@code -k1}.
     */
    static @Nullable Tune padToUnit(Ym6Reader.Song song, Tune tune, int unit) {
        Tune padded = Tune.padToUnit(tune, unit, safeToDuplicate(song));
        if (padded != null && padded != tune) {
            int added = padded.frames() - tune.frames();
            System.out.printf(Locale.ROOT, "Padded %d frame%s (duplicates of safe frames) so the "
                    + "length is whole %d-byte units%n", added, added == 1 ? "" : "s",
                    unit);
        }
        return padded;
    }

    /**
     * Which frames of this dump may be duplicated without being heard: R13
     * quiet, and no drum code in either slot's effect field - a duplicated
     * drum code would trigger the drum again. The test is on the RAW dump
     * rather than on the extracted codes, because a code the extraction
     * dropped is still a code the frame's registers carry, and the tune plays
     * the same either way while the frames near the boundary are cheap.
     */
    private static IntPredicate safeToDuplicate(Ym6Reader.Song song) {
        byte[][] r = song.registers();
        boolean ym6 = song.format().startsWith("YM6");
        return f -> {
            if ((r[13][f] & 0xFF) != 0xFF) {
                return false;                   // this frame restarts the
            }                                   // envelope: not twice
            int c1 = r[1][f] & 0xF0;
            int c3 = r[3][f] & 0xF0;
            boolean drum = ym6 ? (c1 & 0xC0) == 0x40 && (c1 & 0x30) != 0
                    || (c3 & 0xC0) == 0x40 && (c3 & 0x30) != 0
                    : (c3 & 0x30) != 0;
            return !drum;
        };
    }

    private static void report(Ym6Reader.Song song, YmEffects.Extraction effects,
                               YmxEncoder.Result result) {
        Tune tune = result.tune();
        System.out.printf(Locale.ROOT, "%s: %s%s%s%n", song.format(),
                song.name().isBlank() ? "(untitled)" : song.name(),
                song.author().isBlank() ? "" : " by " + song.author(),
                song.interleaved() ? "" : " (de-interleaved)");
        if (effects.samples().length > 0) {
            int bytes = 0;
            for (byte[] sample : effects.samples()) {
                bytes += sample.length + 1;
            }
            System.out.printf(Locale.ROOT, "%d digidrum%s, %d bytes%n", effects.samples().length,
                    effects.samples().length == 1 ? "" : "s", bytes);
        }
        if (effects.sinus() > 0) {
            System.out.printf(Locale.ROOT, "Warning: %d Sinus-SID frame%s dropped "
                    + "(the reference player runs an empty handler)%n",
                    effects.sinus(), effects.sinus() == 1 ? "" : "s");
        }
        if (effects.tooFast() > 0) {
            System.out.printf(Locale.ROOT, "Warning: %d effect frame%s dropped: timer above %d Hz%n",
                    effects.tooFast(), effects.tooFast() == 1 ? "" : "s",
                    YmEffects.MAX_TIMER_HZ);
        }
        if (effects.inert() > 0) {
            System.out.printf(Locale.ROOT, "Warning: %d effect frame%s dropped: a prescaler"
                    + " of 0 is the MFP's stopped state, a counter of 0 is 256, and neither"
                    + " is armed here%n", effects.inert(), effects.inert() == 1 ? "" : "s");
        }
        if (effects.missingDrum() > 0) {
            System.out.printf(Locale.ROOT, "Warning: %d drum trigger%s dropped: no such sample%n",
                    effects.missingDrum(), effects.missingDrum() == 1 ? "" : "s");
        }
        for (String note : effects.notes()) {
            System.out.println("Warning: " + note);
        }

        // What was packed: one byte per frame per stream, script included.
        int raw = result.script().frames() * YmxFormat.STREAMS;
        System.out.printf(Locale.ROOT, "%d frames at %d Hz (%d:%02d), %d rings of %d bytes, %d per call%n",
                tune.frames(), tune.frameRate(),
                tune.frames() / tune.frameRate() / 60,
                tune.frames() / tune.frameRate() % 60,
                YmxFormat.STREAMS, result.ringSize(), result.chunk());
        System.out.println(result.startingOver());
        for (String note : result.notes()) {
            System.out.println(note);
        }
        String[] effectNames = {"M ", "X ", "T ", "A0", "P0", "A1", "P1",
                                "A2", "P2", "A3", "P3"};
        for (YmxEncoder.Stream stream : result.streams()) {
            String name = stream.register() < YmxFormat.REGISTER_STREAMS
                    ? String.format("R%-2d", stream.register())
                    : effectNames[stream.register() - YmxFormat.REGISTER_STREAMS] + " ";
            System.out.printf(Locale.ROOT, "  %s %6d -> %6d bytes (%5.1f%%)%n", name,
                    stream.frames(), stream.packedSize(),
                    100.0 * stream.packedSize() / stream.frames());
        }
        System.out.printf(Locale.ROOT, "Packed %d register bytes into %d (%.1f%%), file %d bytes%n",
                raw, result.packedSize(), 100.0 * result.packedSize() / raw, result.file().length);
        int flags = ((result.file()[YmxFormat.OFFSET_FLAGS] & 0xFF) << 8)
                | (result.file()[YmxFormat.OFFSET_FLAGS + 1] & 0xFF);
        // The chunk is a slot count: one stream refilled per call, so it has
        // to cover the streams this tune DECODES, not all the format defines.
        int live = YmxFormat.liveStreams(flags);
        System.out.printf(Locale.ROOT, "Player needs %d bytes of ring plus its state, and"
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
            System.out.printf(Locale.ROOT, "Warning: longest operation is %d bytes, over the 65535 the "
                    + "68000 decoder can represent: do not play this file%n",
                    result.longestOp());
        }
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
     * <p>Zero is a real answer for every part of the trim window -
     * {@code -min0 -sec13} is how a caller says thirteen seconds in,
     * and {@code -startframe0} says the same thing again. It is nonsense for a
     * ring, a chunk, a unit or a rate ceiling, which is why the default stands
     * for those. A window that comes out empty is caught where the window is
     * worked out, and says so in those words rather than as a bad parameter.
     */
    private static int parseNumber(String argument, boolean zeroAllowed) {
        if (!decimal(argument)) {
            throw error("Invalid parameter value " + argument);
        }
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

    /**
     * Whether the text is an optional sign and then ASCII digits.
     * {@code Integer.parseInt} reads the digits of every script, so
     * {@code -n} with the Arabic-Indic 960 packed a file here where the other
     * two trees, which convert 32-bit decimal, refused the value.
     */
    private static boolean decimal(String argument) {
        int at = argument.startsWith("+") || argument.startsWith("-") ? 1 : 0;
        if (at == argument.length()) {
            return false;
        }
        for (; at < argument.length(); at++) {
            if (argument.charAt(at) < '0' || argument.charAt(at) > '9') {
                return false;
            }
        }
        return true;
    }
}
