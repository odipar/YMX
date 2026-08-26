using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text.RegularExpressions;
using Ymx;

namespace Ym6
{
    /// <summary>
    /// Command-line YM to YMX packer, ported from org.ym6.Ymx: reads a
    /// YM5!/YM6! register dump and writes a .ymx file the 68000 player
    /// streams through ST4.
    /// </summary>
    public static class YmxCli
    {
        public static void Main(string[] args)
        {
            // -meta: the YM header's strings and rate, one per line, for the
            // build scripts to carry into SNDH tags. Nothing else runs.
            if (args.Length == 2 && args[0] == "-meta")
            {
                Ym6Reader.Song metaSong = ReadSong(args[1]);
                Console.WriteLine(metaSong.Name.Trim());
                Console.WriteLine(metaSong.Author.Trim());
                Console.WriteLine(metaSong.PlayerHz);
                return;
            }
            // -script: the compiled effect script, one line per acting frame.
            if (args.Length == 2 && args[0] == "-script")
            {
                Ym6Reader.Song scriptSong = ReadSong(args[1]);
                EffectScript.Result script = EffectScript.Compile(
                        YmEffects.BuildTune(scriptSong), YmxFormat.DefaultTimers);
                Console.WriteLine(script.Frames + " frames");
                for (int f = 0; f < script.Frames; f++)
                {
                    if (script.M[f] == 0 && script.R7Force[f] == 0)
                    {
                        continue;
                    }
                    var line = new System.Text.StringBuilder(string.Format(
                            "{0,6}  M={1:X2} X={2:X2} T={3:X2}", f, script.M[f],
                            script.X[f], script.Timers[f]));
                    for (int c = 0; c < script.Actions.Length; c++)
                    {
                        line.Append(string.Format(" A{0}={1:X2} P{0}={2,3}", c,
                                script.Actions[c][f], script.Counts[c][f]));
                    }
                    Console.WriteLine(line + string.Format(" R7|={0:X2}",
                            script.R7Force[f]));
                }
                foreach (string note in script.Notes)
                {
                    Console.WriteLine("note: " + note);
                }
                return;
            }
            Console.WriteLine("YMX: YM chiptune packer v"
                    + YmxFormat.ReleaseName() + " by Robbert van Dalen,"
                    + " streaming ST4");

            int ringSize = YmxFormat.DefaultRingSize;
            int chunk = YmxFormat.DefaultChunk;
            int unit = 0;                   // 0 until chosen: -kK, or the
            bool playOnce = false;          // tune's shape
            bool forcedMode = false;
            bool sidResume = false;
            int startMin = 0;               // the trim window
            int startSec = 0;
            int startFrame = -1;
            int endFrame = -1;
            int frameCount = -1;
            int loopFrame = -1;             // -1 until -lF: the header's own
            int drumHz = YmEffects.MaxTimerHz;
            int timerMap = YmxFormat.DefaultTimers;
            int i = 0;
            for (; i < args.Length && args[i].StartsWith('-'); i++)
            {
                switch (args[i])
                {
                    case "-f":
                        forcedMode = true;
                        break;
                    case "-o":
                        playOnce = true;
                        break;
                    case "-sidresume":
                        sidResume = true;
                        break;
                    default:
                        if (args[i].StartsWith("-timers"))
                        {
                            timerMap = ParseTimers(args[i][7..]);
                        }
                        else if (args[i].StartsWith("-drumhz"))
                        {
                            drumHz = ParseNumber(args[i][7..]);
                        }
                        else if (args[i].StartsWith("-startframe"))
                        {
                            startFrame = ParseNumber(args[i][11..], true);
                        }
                        else if (args[i].StartsWith("-endframe"))
                        {
                            endFrame = ParseNumber(args[i][9..], true);
                        }
                        else if (args[i].StartsWith("-frames"))
                        {
                            frameCount = ParseNumber(args[i][7..], true);
                        }
                        else if (args[i].StartsWith("-min"))
                        {
                            startMin = ParseNumber(args[i][4..], true);
                        }
                        else if (args[i].StartsWith("-sec"))
                        {
                            startSec = ParseNumber(args[i][4..], true);
                        }
                        else if (args[i].StartsWith("-n"))
                        {
                            ringSize = ParseNumber(args[i][2..]);
                        }
                        else if (args[i].StartsWith("-c"))
                        {
                            chunk = ParseNumber(args[i][2..]);
                        }
                        else if (args[i].StartsWith("-k"))
                        {
                            unit = ParseNumber(args[i][2..]);
                        }
                        else if (args[i].StartsWith("-l"))
                        {
                            loopFrame = ParseNumber(args[i][2..], true);
                        }
                        else
                        {
                            throw Error("Invalid parameter " + args[i]);
                        }
                        break;
                }
            }

            // A trailing DIRECTORY collects a whole set: every argument
            // before it is an input, each packed with the identical
            // configuration into <dir>/<stem>.ymx.
            if (args.Length - i >= 2 && Directory.Exists(args[^1]))
            {
                if (startMin != 0 || startSec != 0 || startFrame >= 0
                        || endFrame >= 0 || frameCount >= 0)
                {
                    throw Error("the trim options take one tune, not a set");
                }
                if (unit == 0)
                {
                    unit = 2;           // uniform by construction: padding
                }                       // makes any shape fit, or fails loudly
                string dir = args[^1];
                for (int input = i; input < args.Length - 1; input++)
                {
                    string stem = Regex.Replace(
                            Path.GetFileName(args[input]), "(?i)\\.ym$", "");
                    PackOne(args[input], Path.Combine(dir, stem + ".ymx"),
                            ringSize, chunk, unit, playOnce, forcedMode,
                            drumHz, sidResume, timerMap, 0, 0, -1, -1, -1,
                            loopFrame);
                }
                return;
            }

            string outputName;
            if (args.Length == i + 1)
            {
                outputName = args[i] + ".ymx";
            }
            else if (args.Length == i + 2)
            {
                outputName = args[i + 1];
            }
            else
            {
                Usage();
                return;
            }
            PackOne(args[i], outputName, ringSize, chunk, unit, playOnce,
                    forcedMode, drumHz, sidResume, timerMap, startMin, startSec,
                    startFrame, endFrame, frameCount, loopFrame);
        }

        private static Ym6Reader.Song ReadSong(string path)
        {
            byte[] input;
            try
            {
                input = File.ReadAllBytes(path);
            }
            catch (Exception e) when (e is IOException
                    || e is UnauthorizedAccessException)
            {
                throw Error("Cannot access input file " + path);
            }
            try
            {
                return Ym6Reader.Read(input);
            }
            catch (Ym6Reader.FormatException e)
            {
                throw Error(path + ": " + e.Message);
            }
        }

        /// <summary>-timersABCD: which MFP timer each channel runs on, one
        /// letter per channel from channel 0 up; letters left off keep the
        /// default, and the map stays a permutation.</summary>
        internal static int ParseTimers(string spec)
        {
            if (spec.Length == 0 || spec.Length > YmxFormat.Channels)
            {
                throw Error("-timers takes one letter per channel, up to "
                        + YmxFormat.Channels + ": -timersBC, say");
            }
            bool[] taken = new bool[4];
            int[] timers = new int[YmxFormat.Channels];
            Array.Fill(timers, -1);
            for (int channel = 0; channel < spec.Length; channel++)
            {
                int timer = "ABCD".IndexOf(char.ToUpperInvariant(spec[channel]));
                if (timer < 0)
                {
                    throw Error("-timers: '" + spec[channel]
                            + "' is not one of the MFP's timers A, B, C or D");
                }
                if (taken[timer])
                {
                    throw Error("-timers: two channels cannot both run on Timer "
                            + "ABCD"[timer]);
                }
                taken[timer] = true;
                timers[channel] = timer;
            }
            // The channels the spec left out take the timers it did not, in
            // order.
            int spare = 0;
            int map = 0;
            for (int channel = 0; channel < YmxFormat.Channels; channel++)
            {
                if (timers[channel] < 0)
                {
                    while (taken[spare])
                    {
                        spare++;
                    }
                    taken[spare] = true;
                    timers[channel] = spare;
                }
                map |= timers[channel] << (2 * channel);
            }
            return map;
        }

        /// <summary>The whole pipeline for one tune: read, trim, pad, pack,
        /// write, report.</summary>
        private static void PackOne(string inputName, string outputName,
                int ringSize, int chunk, int unit, bool playOnce, bool forcedMode,
                int drumHz, bool sidResume, int timerMap, int startMin,
                int startSec, int startFrame, int endFrame, int frameCount,
                int loopFrame)
        {
            string problem = YmxFormat.CheckShape(ringSize, chunk,
                    Math.Max(unit, 1), YmxFormat.StreamA0);
            if (problem.Length != 0)
            {
                throw Error(problem);
            }

            byte[] input;
            try
            {
                input = File.ReadAllBytes(inputName);
            }
            catch (Exception e) when (e is IOException
                    || e is UnauthorizedAccessException)
            {
                throw Error("Cannot access input file " + inputName);
            }

            if (!forcedMode && File.Exists(outputName))
            {
                throw Error("Already existing output file " + outputName);
            }

            Ym6Reader.Song song;
            try
            {
                song = Ym6Reader.Read(input);
            }
            catch (Ym6Reader.FormatException e)
            {
                throw Error(inputName + ": " + e.Message);
            }

            // The trim window: everything before and after is dropped, so a
            // moment deep in a long tune plays immediately.
            int start = startFrame >= 0 ? startFrame
                    : (startMin * 60 + startSec) * song.PlayerHz;
            int end = song.Frames;
            if (endFrame >= 0)
            {
                end = Math.Min(end, endFrame);
            }
            if (frameCount >= 0)
            {
                end = Math.Min(end, start + frameCount);
            }
            if (start > 0 || end < song.Frames)
            {
                if (start < 0 || start >= end)
                {
                    throw Error("Empty trim window: frames " + start + ".." + end
                            + " of " + song.Frames);
                }
                byte[][] cut = new byte[song.Registers.Length][];
                for (int r = 0; r < cut.Length; r++)
                {
                    cut[r] = song.Registers[r][start..end];
                }
                // The loop frame is a frame number, so it rebases on the
                // first kept frame; one outside the window is no longer a
                // frame of this tune, and the excerpt starts over from its
                // own first frame.
                long kept = song.LoopFrame >= start && song.LoopFrame < end
                        ? song.LoopFrame - start : 0;
                if (song.LoopFrame != 0 && kept == 0)
                {
                    Console.WriteLine($"Frame {song.LoopFrame}, which the header"
                            + " loops from, is outside the kept window: the"
                            + " excerpt starts over from its own first frame");
                }
                song = new Ym6Reader.Song(song.Format, end - start, song.PlayerHz,
                        song.MasterClock, kept, song.Interleaved, song.Attributes,
                        song.Drums, song.Name, song.Author, song.Comment, cut);
                Console.WriteLine($"Trimmed to frames {start}-{end - 1}:"
                        + $" {end - start} frames");
            }

            // The boundary: from here on the tune is the engine's; the dump
            // is kept for what the report and the padding rule say about the
            // FILE.
            YmEffects.Extraction effects = YmEffects.Extract(song, drumHz);
            Tune tune = YmEffects.BuildTune(song, effects);
            // -sidresume is the one source semantic a listener picks rather
            // than a format.
            if (sidResume)
            {
                tune = tune.Under(tune.Semantics.Resuming());
            }
            // -lF says where the tune starts over, in the frames of the tune
            // being packed: a trim has already moved what F counts from. The
            // packer answers for the frame either way, whether the header gave
            // it or the command line did.
            if (loopFrame >= 0)
            {
                if (loopFrame >= tune.Frames)
                {
                    throw Error("-l" + loopFrame + " is past the tune's "
                            + tune.Frames + " frames");
                }
                tune = tune.StartingOverAt(loopFrame);
            }

            // A YM header always names a loop frame and its players always
            // went round, so a YM tune starts over unless -o says otherwise.
            bool startsOver = !playOnce;

            // The default unit is 2; a tune of odd length is PADDED with a
            // safe duplicate frame, or drops to -k1 when none exists.
            if (unit == 0 && chunk % 2 == 0)
            {
                Tune? padded = PadToUnit(song, tune, 2);
                if (padded != null)
                {
                    tune = padded;
                    unit = 2;
                }
                else
                {
                    unit = 1;
                    Console.WriteLine("Packing at -k1: this tune's length is not "
                            + "a whole number of 2-byte units, and no frame near "
                            + "the end is safe to duplicate");
                }
            }
            else if (unit == 0)
            {
                unit = 1;
            }
            else if (unit > 1)
            {
                Tune? padded = PadToUnit(song, tune, unit);
                if (padded != null)
                {
                    tune = padded;
                }
            }

            YmxEncoder.Result result;
            try
            {
                result = YmxEncoder.Encode(tune, ringSize, chunk, startsOver,
                        true, unit, timerMap);
            }
            catch (ArgumentException e)
            {
                throw Error(e.Message);
            }
            try
            {
                File.WriteAllBytes(outputName, result.File);
            }
            catch (Exception e) when (e is IOException
                    || e is UnauthorizedAccessException)
            {
                throw Error("Cannot write output file " + outputName);
            }

            Report(song, effects, result);
        }

        /// <summary>Pads the tune to whole units with the YM dump's own idea
        /// of which frame may be duplicated: R13 quiet, and no drum code in
        /// either slot's effect field. Null when no safe frame exists near
        /// the end - the caller's cue to drop to -k1.</summary>
        internal static Tune? PadToUnit(Ym6Reader.Song song, Tune tune, int unit)
        {
            Tune? padded = Tune.PadToUnit(tune, unit, SafeToDuplicate(song));
            if (padded != null && !ReferenceEquals(padded, tune))
            {
                int added = padded.Frames - tune.Frames;
                Console.WriteLine($"Padded {added} frame"
                        + (added == 1 ? "" : "s")
                        + " (duplicates of safe frames) so the length is whole "
                        + unit + "-byte units");
            }
            return padded;
        }

        private static Func<int, bool> SafeToDuplicate(Ym6Reader.Song song)
        {
            byte[][] r = song.Registers;
            bool ym6 = song.Format.StartsWith("YM6");
            return f =>
            {
                if (r[13][f] != 0xFF)
                {
                    return false;       // this frame restarts the envelope
                }
                int c1 = r[1][f] & 0xF0;
                int c3 = r[3][f] & 0xF0;
                bool drum = ym6 ? (c1 & 0xC0) == 0x40 && (c1 & 0x30) != 0
                        || (c3 & 0xC0) == 0x40 && (c3 & 0x30) != 0
                        : (c3 & 0x30) != 0;
                return !drum;
            };
        }

        /// <summary>Part of whole, in percent, rounded the way the tree this
        /// was ported from rounds it: a half goes up. .NET and Go take a half
        /// to the even digit, so 528 of 8448 reads 6.2 there and 6.3 in Java,
        /// and the three trees must not disagree about a figure a reader
        /// compares between them.</summary>
        private static double Percent(int part, int whole)
        {
            return Math.Round(100.0 * part / whole, 1,
                    MidpointRounding.AwayFromZero);
        }

        private static void Report(Ym6Reader.Song song,
                YmEffects.Extraction effects, YmxEncoder.Result result)
        {
            Tune tune = result.Tune;
            Console.WriteLine(string.Format("{0}: {1}{2}{3}", song.Format,
                    string.IsNullOrWhiteSpace(song.Name) ? "(untitled)" : song.Name,
                    string.IsNullOrWhiteSpace(song.Author) ? ""
                            : " by " + song.Author,
                    song.Interleaved ? "" : " (de-interleaved)"));
            if (effects.Samples.Length > 0)
            {
                int bytes = 0;
                foreach (byte[] sample in effects.Samples)
                {
                    bytes += sample.Length + 1;
                }
                Console.WriteLine(string.Format("{0} digidrum{1}, {2} bytes",
                        effects.Samples.Length,
                        effects.Samples.Length == 1 ? "" : "s", bytes));
            }
            if (effects.Sinus > 0)
            {
                Console.WriteLine(string.Format("Warning: {0} Sinus-SID frame{1}"
                        + " dropped (the reference player runs an empty"
                        + " handler)", effects.Sinus,
                        effects.Sinus == 1 ? "" : "s"));
            }
            if (effects.TooFast > 0)
            {
                Console.WriteLine(string.Format("Warning: {0} effect frame{1}"
                        + " dropped: timer above {2} Hz", effects.TooFast,
                        effects.TooFast == 1 ? "" : "s", YmEffects.MaxTimerHz));
            }
            if (effects.Inert > 0)
            {
                Console.WriteLine(string.Format("Warning: {0} effect frame{1}"
                        + " dropped: a prescaler of 0 is the MFP's stopped state,"
                        + " a counter of 0 is 256, and neither is armed here",
                        effects.Inert, effects.Inert == 1 ? "" : "s"));
            }
            if (effects.MissingDrum > 0)
            {
                Console.WriteLine(string.Format("Warning: {0} drum trigger{1}"
                        + " dropped: no such sample", effects.MissingDrum,
                        effects.MissingDrum == 1 ? "" : "s"));
            }
            foreach (string note in effects.Notes)
            {
                Console.WriteLine("Warning: " + note);
            }

            // What was packed: one byte per frame per stream, script included.
            int raw = result.Script.Frames * YmxFormat.Streams;
            Console.WriteLine(string.Format(
                    "{0} frames at {1} Hz ({2}:{3:00}), {4} rings of {5} bytes,"
                    + " {6} per call",
                    tune.Frames, tune.FrameRate,
                    tune.Frames / tune.FrameRate / 60,
                    tune.Frames / tune.FrameRate % 60,
                    YmxFormat.Streams, result.RingSize, result.Chunk));
            Console.WriteLine(result.StartingOver());
            foreach (string note in result.Notes)
            {
                Console.WriteLine(note);
            }
            string[] effectNames = {"M ", "X ", "T ", "A0", "P0", "A1", "P1",
                                    "A2", "P2", "A3", "P3"};
            foreach (YmxEncoder.Stream stream in result.Streams)
            {
                string name = stream.Register < YmxFormat.RegisterStreams
                        ? string.Format("R{0,-2}", stream.Register)
                        : effectNames[stream.Register - YmxFormat.RegisterStreams]
                                + " ";
                Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                        "  {0} {1,6} -> {2,6} bytes ({3,5:F1}%){4}", name,
                        stream.Frames, stream.PackedSize,
                        Percent(stream.PackedSize, stream.Frames),
                        stream.LoopSize == 0 ? "" : string.Format(
                                CultureInfo.InvariantCulture, "  {0,6} + {1}",
                                stream.FirstSize, stream.LoopSize)));
            }
            Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                    "Packed {0} register bytes into {1} ({2:F1}%), file {3} bytes",
                    raw, result.PackedSize(), Percent(result.PackedSize(), raw),
                    result.File.Length));
            int flags = (result.File[YmxFormat.OffsetFlags] << 8)
                    | result.File[YmxFormat.OffsetFlags + 1];
            // The chunk is a slot count: it has to cover the streams this
            // tune DECODES, not all the format defines.
            int live = YmxFormat.LiveStreams(flags);
            Console.WriteLine(string.Format("Player needs {0} bytes of ring plus"
                    + " its state, and decodes {1} of the {2} streams - one"
                    + " refill a call, so C={3} covers them with {4} slots idle",
                    YmxFormat.Streams * result.RingSize, live, YmxFormat.Streams,
                    result.Chunk, result.Chunk - live));
            foreach (string note in result.Script.Notes)
            {
                Console.WriteLine(note);
            }

            if (result.LongestOp() > 65535)
            {
                Console.WriteLine(string.Format("Warning: longest operation is"
                        + " {0} bytes, over the 65535 the 68000 decoder can"
                        + " represent: do not play this file", result.LongestOp()));
            }
        }

        /// <summary>Reads the packer's flags and keeps nothing. A command
        /// packing on its way to an SNDH file checks them before it makes a
        /// work directory, so a flag the packer does not have stops the run
        /// with nothing left behind. The Java tree runs its own parser for
        /// this; here the list is written a second time, and ymx/parity.sh is
        /// what holds the two copies to the same answer.</summary>
        public static void CheckFlags(List<string> flags)
        {
            foreach (string flag in flags)
            {
                if (flag == "-f" || flag == "-o" || flag == "-sidresume")
                {
                    continue;
                }
                if (flag.StartsWith("-timers")) { ParseTimers(flag[7..]); }
                else if (flag.StartsWith("-drumhz")) { ParseNumber(flag[7..]); }
                else if (flag.StartsWith("-startframe")) { ParseNumber(flag[11..], true); }
                else if (flag.StartsWith("-endframe")) { ParseNumber(flag[9..], true); }
                else if (flag.StartsWith("-frames")) { ParseNumber(flag[7..], true); }
                else if (flag.StartsWith("-min")) { ParseNumber(flag[4..], true); }
                else if (flag.StartsWith("-sec")) { ParseNumber(flag[4..], true); }
                else if (flag.StartsWith("-n")) { ParseNumber(flag[2..]); }
                else if (flag.StartsWith("-c")) { ParseNumber(flag[2..]); }
                else if (flag.StartsWith("-k")) { ParseNumber(flag[2..]); }
                else if (flag.StartsWith("-l")) { ParseNumber(flag[2..], true); }
                else { throw Error("Invalid parameter " + flag); }
            }
        }

        internal static Exception Error(string message)
        {
            Console.Error.WriteLine("Error: " + message);
            Environment.Exit(1);
            throw new AssertionException("unreachable");
        }

        private static void Usage()
        {
            Console.Error.WriteLine(string.Join("\n", new[] {
                "Usage: YMX [-f] [-o] [-lF] [-nN] [-cC] [-kK] input.ym [output.ymx]",
                "       ymx [options] one.ym two.ym more.ym output-dir/",
                "  -f      Force overwrite of output file",
                "  -o      Play once: stop at the end instead of starting over",
                "  -lF     Start over from frame F rather than from the frame",
                "          the header gives; -l0 starts over from the",
                "          beginning. Where the wrap cannot enter F the packer",
                "          takes the next frame it can and says so",
                "  -nN     Ring size per stream, in bytes (default 960)",
                "  -cC     Values decoded per call, and the round-robin group",
                "          size (default 24; N mod C = 0, and C at",
                "          least the streams the tune decodes: 17 with",
                "          no timer channel, 21 for a YM tune, 25 for",
                "          one that uses all four)",
                "  -kK     ST4 unit size: 1, 2 or 4 (default 2). An odd",
                "          tune length is padded with safe duplicate frames",
                "          - inaudible - to fit the unit. The player must be",
                "          built with the same ST4_UNIT",
                "  -minM -secS   Trim: drop everything before M:S, so a",
                "          moment deep in a long tune plays immediately",
                "  -drumhzH   The drum rate ceiling (default 25600): a drum",
                "          asking for a faster timer is downsampled to fit,",
                "          with a warning",
                "  -timersT   Which MFP timer each channel runs on, one",
                "          letter per channel from 0 up: -timersBC puts",
                "          channel 0 on Timer B and channel 1 on Timer C.",
                "          The default is AD, where a YM tune has always",
                "          played. Timer C is the system's 200 Hz clock,",
                "          so a tune that takes it stops that clock and",
                "          cannot be hosted from a Timer C interrupt",
                "  -sidresume   The maxYMiser SID gap model: a released",
                "          SID's timer keeps counting and a re-arrival",
                "          resumes its phase. Default: the ym2149-rs",
                "          model, phase-zero restarts",
                "  -startframeF -endframeF -framesN   The same window in",
                "          frames: start, end, or a length cap",
                "  -script Dump the compiled effect script instead of",
                "          packing: one line per frame anything acts on",
                "  -meta   Print the header's title, author and frame rate,",
                "          one per line, and pack nothing - what the build",
                "          scripts read for the SNDH tags",
                "",
                "The input is a YM5!/YM6! dump, LHA-archived or already",
                "unpacked - the reader tells them apart by itself. With a",
                "trailing DIRECTORY, every argument before it is an input,",
                "packed with the same configuration - the set one player",
                "build can hold as subtunes."}));
            Environment.Exit(1);
        }

        internal static int ParseNumber(string argument)
        {
            return ParseNumber(argument, false);
        }

        internal static int ParseNumber(string argument, bool zeroAllowed)
        {
            // NumberStyles.None, not Integer: Integer allows the whitespace
            // around a value that Java's Integer.parseInt and Go's
            // strconv.ParseInt turn down, so "-n 960" packed here alone. The
            // leading sign the other two accept is added back below.
            if (!int.TryParse(argument, NumberStyles.AllowLeadingSign,
                    CultureInfo.InvariantCulture, out int value) || value < 0
                    || (value == 0 && !zeroAllowed))
            {
                throw Error("Invalid parameter value " + argument);
            }
            return value;
        }
    }
}
