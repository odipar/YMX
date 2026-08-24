using System;
using System.Globalization;
using System.IO;
using System.Text.RegularExpressions;
using Ymx;

namespace Ymr
{
    /// <summary>
    /// Command-line .YMR to YMX packer, ported from org.ymr.Ymr: org.ym6.Ymx
    /// with a different reader in front of it - every flag that means the
    /// same thing is spelled the same way, and the report answers the same
    /// questions in the same order. What a .YMR does not have, the CLI does
    /// not offer: no -drumhz, no -timers (the binding is normative), no
    /// -sidresume (a YM argument).
    /// </summary>
    public static class YmrCli
    {
        /// <summary>How many streams a three-channel tune makes the player
        /// decode, the floor the chunk size has to clear.</summary>
        private static readonly int LiveStreams = YmxFormat.LiveStreams(
                YmxFormat.FlagChannel(0) | YmxFormat.FlagChannel(1)
                | YmxFormat.FlagChannel(2));

        public static void Main(string[] args)
        {
            // -script: the compiled effect script, one line per acting frame.
            if (args.Length == 2 && args[0] == "-script")
            {
                YmrReader.Song dump = Read(args[1]);
                Tune converted = YmrEffects.Convert(dump, Stem(args[1]));
                EffectScript.Result script =
                        EffectScript.Compile(converted, YmrEffects.Timers);
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
                foreach (string note in converted.Notes)
                {
                    Console.WriteLine("note: " + note);
                }
                foreach (string note in script.Notes)
                {
                    Console.WriteLine("note: " + note);
                }
                return;
            }
            Console.WriteLine("YMX: .YMR chiptune packer v1.0 by Robbert van"
                    + " Dalen, streaming ST4");

            int ringSize = YmxFormat.DefaultRingSize;
            int chunk = YmxFormat.DefaultChunk;
            int unit = 0;                   // 0 until chosen
            bool playOnce = false;
            bool forcedMode = false;
            int startMin = 0;
            int startSec = 0;
            int startFrame = -1;
            int endFrame = -1;
            int frameCount = -1;
            int loopFrame = -1;             // -1 until -lF: the song's own
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
                    default:
                        if (args[i].StartsWith("-startframe"))
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

            // A trailing DIRECTORY collects a whole set.
            if (args.Length - i >= 2 && Directory.Exists(args[^1]))
            {
                if (startMin != 0 || startSec != 0 || startFrame >= 0
                        || endFrame >= 0 || frameCount >= 0)
                {
                    throw Error("the trim options take one tune, not a set");
                }
                if (unit == 0)
                {
                    unit = 2;
                }
                string dir = args[^1];
                for (int input = i; input < args.Length - 1; input++)
                {
                    PackOne(args[input],
                            Path.Combine(dir, Stem(args[input]) + ".ymx"),
                            ringSize, chunk, unit, playOnce, forcedMode,
                            0, 0, -1, -1, -1, loopFrame);
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
                    forcedMode, startMin, startSec, startFrame, endFrame,
                    frameCount, loopFrame);
        }

        /// <summary>The whole pipeline for one tune: read, convert, trim,
        /// pad, pack, write, report - the same order org.ym6.Ymx runs it in.</summary>
        private static void PackOne(string inputName, string outputName,
                int ringSize, int chunk, int unit, bool playOnce, bool forcedMode,
                int startMin, int startSec, int startFrame, int endFrame,
                int frameCount, int loopFrame)
        {
            string problem = YmxFormat.CheckShape(ringSize, chunk,
                    Math.Max(unit, 1), YmxFormat.StreamA0);
            if (problem.Length != 0)
            {
                throw Error(problem);
            }

            if (!forcedMode && File.Exists(outputName))
            {
                throw Error("Already existing output file " + outputName);
            }

            YmrReader.Song dump = Read(inputName);
            Tune tune = YmrEffects.Convert(dump, Stem(inputName));

            // The trim window: the registers and the timer streams are cut
            // together, since they are one timeline.
            int rate = dump.FrameRate;
            int start = startFrame >= 0 ? startFrame
                    : (startMin * 60 + startSec) * rate;
            int end = dump.FrameCount;
            if (endFrame >= 0)
            {
                end = Math.Min(end, endFrame);
            }
            if (frameCount >= 0)
            {
                end = Math.Min(end, start + frameCount);
            }
            if (start > 0 || end < dump.FrameCount)
            {
                if (start < 0 || start >= end)
                {
                    throw Error("Empty trim window: frames " + start + ".." + end
                            + " of " + dump.FrameCount);
                }
                tune = Trim(tune, start, end);
                Console.WriteLine($"Trimmed to frames {start}-{end - 1}:"
                        + $" {end - start} frames");
            }

            // What the song says the end does is the default; -o overrides.
            bool startsOver = tune.Loops && !playOnce;

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

            if (unit == 0 && chunk % 2 == 0)
            {
                Tune? padded = PadToUnit(tune, 2);
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
                Tune? padded = PadToUnit(tune, unit);
                if (padded != null)
                {
                    tune = padded;
                }
            }

            YmxEncoder.Result result;
            try
            {
                result = YmxEncoder.Encode(tune, ringSize, chunk, startsOver,
                        true, unit, YmrEffects.Timers);
            }
            catch (ArgumentException e)
            {
                string reason = e.Message;
                if (reason.Contains("chunk"))
                {
                    reason += " - a .ymr fills three timer channels, so the"
                            + " player decodes " + LiveStreams
                            + " streams and C must cover them";
                }
                throw Error(reason);
            }
            try
            {
                File.WriteAllBytes(outputName, result.File);
            }
            catch (IOException)
            {
                throw Error("Cannot write output file " + outputName);
            }

            Report(tune, result);
        }

        // ---------------------------------------------- shaping the tune

        /// <summary>The kept window of every timeline at once. The loop frame
        /// is a frame number, so it rebases on the first kept frame; one
        /// outside the window is no longer a frame of this tune, and the
        /// excerpt starts over from its own first frame.</summary>
        private static Tune Trim(Tune tune, int start, int end)
        {
            int loopFrame = tune.LoopFrame >= start && tune.LoopFrame < end
                    ? tune.LoopFrame - start : 0;
            if (tune.LoopFrame != 0 && loopFrame == 0)
            {
                Console.WriteLine($"Frame {tune.LoopFrame}, which the song starts"
                        + " over from, is outside the kept window: the excerpt"
                        + " starts over from its own first frame");
            }
            return Tune.Of(end - start, tune.FrameRate, tune.MasterClock,
                    tune.Loops, loopFrame, Slice(tune.Registers, start, end),
                    Slice(tune.Codes, start, end), Slice(tune.Counts, start, end),
                    tune.Shapes[start..end], tune.Samples, tune.SampleLoops,
                    tune.Semantics, tune.Name, tune.Author, tune.Comment,
                    tune.Notes);
        }

        /// <summary>Pads the tune to whole units, with the .YMR's own idea
        /// of which frame may be duplicated.</summary>
        private static Tune? PadToUnit(Tune tune, int unit)
        {
            Tune? padded = Tune.PadToUnit(tune, unit, SafeToDuplicate(tune));
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

        /// <summary>Which frames may be duplicated without being heard: R13
        /// quiet, and no channel carrying a PCM code - any frame inside a
        /// sample's run would slip the script's played-frame window against
        /// the code byte's dump-frame one.</summary>
        private static Func<int, bool> SafeToDuplicate(Tune tune)
        {
            byte[] shape = tune.Registers[13];
            byte[][] codes = tune.Codes;
            return frame =>
            {
                if (shape[frame] != YmrReader.NoEnvelopeShape)
                {
                    return false;       // this frame restarts the envelope
                }
                foreach (byte[] code in codes)
                {
                    if ((code[frame] & 0xC0) == Tune.KindPcm)
                    {
                        return false;
                    }
                }
                return true;
            };
        }

        private static byte[][] Slice(byte[][] streams, int start, int end)
        {
            byte[][] sliced = new byte[streams.Length][];
            for (int stream = 0; stream < streams.Length; stream++)
            {
                sliced[stream] = streams[stream][start..end];
            }
            return sliced;
        }

        // ------------------------------------------------------ the report

        private static void Report(Tune tune, YmxEncoder.Result result)
        {
            Console.WriteLine(string.Format(
                    "{0}: {1} (a .ymr carries no title, so this is the file's own)",
                    YmrReader.Magic,
                    string.IsNullOrWhiteSpace(tune.Name) ? "(untitled)" : tune.Name));
            Console.WriteLine("Timer A drives voice A on channel 0, Timer B"
                    + " voice B on channel 1, Timer D voice C on channel 2");
            if (tune.Samples.Length > 0)
            {
                int bytes = 0;
                foreach (byte[] sample in tune.Samples)
                {
                    bytes += sample.Length + 1;
                }
                Console.WriteLine(string.Format("{0} sample{1}, {2} bytes",
                        tune.Samples.Length, tune.Samples.Length == 1 ? "" : "s",
                        bytes));
            }
            foreach (string note in tune.Notes)
            {
                Console.WriteLine("Warning: " + note);
            }

            int raw = result.Script.Frames * YmxFormat.Streams;
            Console.WriteLine(string.Format(
                    "{0} frames at {1} Hz ({2}:{3:00}), {4} rings of {5} bytes,"
                    + " {6} per call", tune.Frames, tune.FrameRate,
                    tune.Frames / tune.FrameRate / 60,
                    tune.Frames / tune.FrameRate % 60,
                    YmxFormat.Streams, result.RingSize, result.Chunk));
            Console.WriteLine(result.StartingOver());
            foreach (string note in result.Notes)
            {
                Console.WriteLine(note);
            }
            string[] scriptNames = {"M ", "X ", "T ", "A0", "P0", "A1", "P1",
                                    "A2", "P2", "A3", "P3"};
            foreach (YmxEncoder.Stream stream in result.Streams)
            {
                string name = stream.Register < YmxFormat.RegisterStreams
                        ? string.Format("R{0,-2}", stream.Register)
                        : scriptNames[stream.Register - YmxFormat.RegisterStreams]
                                + " ";
                Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                        "  {0} {1,6} -> {2,6} bytes ({3,5:F1}%)", name,
                        stream.Frames, stream.PackedSize,
                        100.0 * stream.PackedSize / stream.Frames));
            }
            Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                    "Packed {0} register bytes into {1} ({2:F1}%), file {3} bytes",
                    raw, result.PackedSize(), 100.0 * result.PackedSize() / raw,
                    result.File.Length));
            int flags = (result.File[YmxFormat.OffsetFlags] << 8)
                    | result.File[YmxFormat.OffsetFlags + 1];
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

        // -------------------------------------------------------- the fuss

        private static YmrReader.Song Read(string inputName)
        {
            byte[] input;
            try
            {
                input = File.ReadAllBytes(inputName);
            }
            catch (IOException)
            {
                throw Error("Cannot access input file " + inputName);
            }
            try
            {
                return YmrReader.Read(input);
            }
            catch (YmrReader.FormatException e)
            {
                throw Error(inputName + ": " + e.Message);
            }
        }

        /// <summary>The file's own name, the only name a .YMR has.</summary>
        private static string Stem(string path)
        {
            return Regex.Replace(Path.GetFileName(path), "(?i)\\.ymr$", "");
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
                "Usage: ymr [-f] [-o] [-lF] [-nN] [-cC] [-kK] input.ymr [output.ymx]",
                "       ymr [options] one.ymr two.ymr more.ymr output-dir/",
                "  -f      Force overwrite of output file",
                "  -o      Play once: stop at the end instead of starting over",
                "  -lF     Start over from frame F rather than from the frame",
                "          the header gives; -l0 starts over from the",
                "          beginning. Where the wrap cannot enter F the packer",
                "          takes the next frame it can and says so",
                "  -nN     Ring size per stream, in bytes (default 960)",
                "  -cC     Values decoded per call, and the round-robin group",
                "          size (default 24; N mod C = 0, and C at least the",
                "          streams the tune decodes: a .ymr fills three timer",
                "          channels, so that is 23)",
                "  -kK     ST4 unit size: 1, 2 or 4 (default 2). An odd",
                "          tune length is padded with safe duplicate frames",
                "          - inaudible - to fit the unit. The player must be",
                "          built with the same ST4_UNIT",
                "  -minM -secS   Trim: drop everything before M:S, so a",
                "          moment deep in a long tune plays immediately",
                "  -startframeF -endframeF -framesN   The same window in",
                "          frames: start, end, or a length cap",
                "  -script Dump the compiled effect script instead of",
                "          packing: one line per frame anything acts on",
                "",
                "The input is a RhYMe .YMR version 1.3 register dump. Timer",
                "A, B and D drive voices A, B and C - the spec's normative",
                "binding - and become timer channels 0, 1 and 2, so no flag",
                "chooses them. A .YMR carries no title or author, so the",
                "file's own stem is the name the report prints.",
                "",
                "A conversion is not a copy, and the few things this one",
                "has to change it counts and reports as it goes. What they",
                "are and what each costs is \"What a .ymr gives up\" in",
                "doc/CONVERSION.md."}));
            Environment.Exit(1);
        }

        internal static int ParseNumber(string argument)
        {
            return ParseNumber(argument, false);
        }

        internal static int ParseNumber(string argument, bool zeroAllowed)
        {
            if (!int.TryParse(argument, NumberStyles.Integer,
                    CultureInfo.InvariantCulture, out int value) || value < 0
                    || (value == 0 && !zeroAllowed))
            {
                throw Error("Invalid parameter value " + argument);
            }
            return value;
        }
    }
}
