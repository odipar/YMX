using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Ymx;

namespace Ym6
{
    /// <summary>
    /// What a set of YM dumps says about itself, for the SNDH tags, ported
    /// from org.ym6.TuneSet: each tune's name, a composer they all share,
    /// and a title made of the lot. A name that says nothing gives way to
    /// the file stem; a composer is only claimed when every tune agrees.
    /// </summary>
    public sealed record TuneSet(List<string> Names, string? Composer, string Title)
    {
        public static TuneSet Of(string command, List<string> tunes)
        {
            var names = new List<string>();
            string? composer = null;
            bool agree = true;
            foreach (string tune in tunes)
            {
                Ym6Reader.Song song = ReadSong(command, tune);
                string name = song.Name.Trim();
                if (SaysNothing(name))
                {
                    name = Stem(tune);
                }
                names.Add(name);
                string author = song.Author.Trim();
                if (composer == null && author.Length != 0)
                {
                    composer = author;
                }
                else if (author != (composer ?? ""))
                {
                    agree = false;
                }
            }
            return new TuneSet(names, agree ? composer : null,
                    string.Join(" / ", names));
        }

        /// <summary>The rate every tune must share: one SNDH declares one.</summary>
        public static int PlayerHz(string command, string tune)
        {
            return ReadSong(command, tune).PlayerHz;
        }

        /// <summary>One dump. Where the file does not open, the message
        /// names the command; where it opens and the reader rejects it, the
        /// message names the file.</summary>
        private static Ym6Reader.Song ReadSong(string command, string tune)
        {
            byte[] file;
            try
            {
                file = File.ReadAllBytes(tune);
            }
            catch (Exception e) when (e is IOException
                    || e is UnauthorizedAccessException)
            {
                throw Tools.Fail(command + ": cannot read " + tune);
            }
            try
            {
                return Ym6Reader.Read(file);
            }
            catch (Ym6Reader.FormatException e)
            {
                throw Tools.Fail(tune + ": " + e.Message);
            }
        }

        internal static string Stem(string tune)
        {
            return Regex.Replace(Path.GetFileName(tune), "(?i)\\.ym$", "");
        }

        private static bool SaysNothing(string name)
        {
            string lower = name.ToLowerInvariant();
            return lower.Length == 0 || lower == "unknown" || lower == "untitled"
                    || lower == "<unknown>";
        }
    }

    /// <summary>
    /// The packing step both the SNDH and the Hatari front ends need, ported
    /// from org.ym6.Packing: every .ym through the packer with one
    /// configuration, into one directory. A caller who asked for this pack
    /// reads the per-stream table; one on its way somewhere else drops it.
    /// </summary>
    public static class Packing
    {
        /// <summary>Packs for a caller who asked for this pack: the
        /// per-stream table is what they are reading.</summary>
        public static List<string> Pack(string command, List<string> yms,
                string work, List<string> flags)
        {
            return Pack(command, yms, work, flags, false, false);
        }

        /// <summary>Packs on the way to something else, with the table
        /// dropped.</summary>
        public static List<string> Pack(string command, List<string> yms,
                string work, List<string> flags, bool fresh)
        {
            return Pack(command, yms, work, flags, fresh, true);
        }

        public static List<string> Pack(string command, List<string> yms,
                string work, List<string> flags, bool fresh, bool quiet)
        {
            if (fresh && Directory.Exists(work))
            {
                try
                {
                    Directory.Delete(work, true);
                }
                catch (Exception e) when (e is IOException
                        || e is UnauthorizedAccessException)
                {
                    throw Tools.Fail(command + ": cannot clear " + work);
                }
            }
            try
            {
                Directory.CreateDirectory(work);
            }
            catch (Exception e) when (e is IOException
                    || e is UnauthorizedAccessException)
            {
                throw Tools.Fail(command + ": cannot make " + work);
            }

            var argv = new List<string> {"-f"};
            argv.AddRange(flags);
            var packed = new List<string>();
            foreach (string ym in yms)
            {
                packed.Add(Path.Combine(work, TuneSet.Stem(ym) + ".ymx"));
            }
            if (yms.Count == 1)
            {
                argv.Add(yms[0]);
                argv.Add(packed[0]);
            }
            else
            {
                argv.AddRange(yms);
                argv.Add(work);         // the trailing directory: a set
            }
            if (quiet)
            {
                Quietly(() => YmxCli.Main(argv.ToArray()));
            }
            else
            {
                YmxCli.Main(argv.ToArray());
            }
            return packed;
        }

        /// <summary>Runs the packer with its per-stream table dropped. The
        /// table is one line of ratios per stream per tune, which a build
        /// script on its way to an SNDH file does not need in its log.</summary>
        private static void Quietly(Action packer)
        {
            TextWriter original = Console.Out;
            var filter = new LineFilter(original);
            Console.SetOut(filter);
            try
            {
                packer();
            }
            finally
            {
                Console.Out.Flush();
                filter.Drain();
                Console.SetOut(original);
            }
        }

        /// <summary>Drops the packer's per-stream lines - a register's
        /// "  R0", the script's "  A3" and "  P3", "  M" and "  X" - and
        /// passes everything else straight through.</summary>
        private sealed class LineFilter : TextWriter
        {
            private readonly TextWriter output;
            private readonly StringBuilder line = new();
            private static readonly Regex PerStream =
                    new Regex("^ {2}(?:[REAP]\\d+|[MXT]) +.*$");

            internal LineFilter(TextWriter output)
            {
                this.output = output;
            }

            public override Encoding Encoding => output.Encoding;

            public override void Write(char value)
            {
                line.Append(value);
                if (value == '\n')
                {
                    string text = line.ToString();
                    line.Clear();
                    if (!PerStream.IsMatch(text.TrimEnd('\r', '\n')))
                    {
                        output.Write(text);
                    }
                }
            }

            /// <summary>A flush can land mid-line. Text that cannot grow
            /// into a table line goes straight out; the rest is held until
            /// its newline, so no flush carries half a table line past the
            /// match. The Java tree had this the other way round, and every
            /// line reached the screen in fragments.</summary>
            public override void Flush()
            {
                string held = line.ToString();
                if (held.Length > 0 && !OpensTableLine(held))
                {
                    output.Write(held);
                    line.Clear();
                }
                output.Flush();
            }

            /// <summary>Whether text could still grow into a line this
            /// drops.</summary>
            private static bool OpensTableLine(string text)
            {
                return text.Length < 2 ? "  ".StartsWith(text,
                        StringComparison.Ordinal)
                        : text.StartsWith("  ", StringComparison.Ordinal);
            }

            /// <summary>Writes what is left, newline or not: the last of the
            /// output.</summary>
            internal void Drain()
            {
                if (line.Length > 0)
                {
                    output.Write(line.ToString());
                    line.Clear();
                }
                output.Flush();
            }
        }
    }

    /// <summary>
    /// From .ym dumps to one SNDH file, in one command, ported from
    /// org.ym6.YmSndh: the packer over every input with one configuration,
    /// then MkSndh around the results.
    /// </summary>
    public static class YmSndh
    {
        private const string UsageText = "usage: ym_sndh.sh [-perf] [-tTitle]"
                + " [packer flags] output.sndh tunes.ym...";

        public static void Main(string[] args)
        {
            string? title = null;
            bool perf = false;
            var packerFlags = new List<string>();
            int i = 0;
            for (; i < args.Length && args[i].StartsWith('-'); i++)
            {
                if (args[i] == "-perf")
                {
                    perf = true;
                }
                else if (args[i].StartsWith("-t"))
                {
                    title = args[i][2..];
                }
                else
                {
                    packerFlags.Add(args[i]);
                }
            }
            if (args.Length - i < 2)
            {
                throw Tools.Fail(UsageText);
            }
            // The packer's flags are read before anything is made, so a flag
            // it does not have stops the run with no work directory left.
            YmxCli.CheckFlags(packerFlags);
            string output = args[i++];
            var yms = new List<string>();
            for (; i < args.Length; i++)
            {
                yms.Add(args[i]);
            }

            string work = Path.Combine(Tools.DirectoryOf(output), ".ym_work");
            TuneSet set = TuneSet.Of("ymsndh", yms);
            // a fresh work directory each run: yesterday's leftovers are not
            // this set's subtunes
            List<string> packed = Packing.Pack("ymsndh", yms, work, packerFlags,
                    true);
            MkSndh.Build(MkSndh.Options.Of(output, packed,
                    !string.IsNullOrEmpty(title) ? title : set.Title,
                    set.Composer, set.Names, perf, true));
        }
    }

    /// <summary>The Hatari session Play and YmrPlay share: sound on, real
    /// speed, a window, ended by the exit marker or the window closing.</summary>
    internal static class Emulator
    {
        internal static void Run(string tool, string hatari, string tos,
                string program, string marker)
        {
            var info = new ProcessStartInfo(hatari) {UseShellExecute = false};
            foreach (string argument in new[] {"--tos", tos, "--machine", "st",
                    "--cpuclock", "8", "--memsize", "4", "--sound", "44100",
                    "--ym-mixing", "model", "--window", "--zoom", "2",
                    "--confirm-quit", "off", "--log-level", "fatal", program})
            {
                info.ArgumentList.Add(argument);
            }
            Process emulator;
            try
            {
                emulator = Process.Start(info)
                        ?? throw Tools.Fail(tool + ": cannot start " + hatari);
            }
            catch (System.ComponentModel.Win32Exception e)
            {
                throw Tools.Fail(tool + ": cannot start " + hatari + ": "
                        + e.Message);
            }
            while (!emulator.HasExited)
            {
                if (File.Exists(marker))
                {
                    emulator.Kill();
                    emulator.WaitForExit(3000);
                    break;
                }
                Thread.Sleep(200);
            }
            emulator.WaitForExit();
        }

        internal static string Env(string name, string fallback)
        {
            string? value = Environment.GetEnvironmentVariable(name);
            return string.IsNullOrEmpty(value) ? fallback : value;
        }
    }

    /// <summary>
    /// Test drive, ported from org.ym6.Play: pack a tune, build a player
    /// around it, run it under Hatari, with the exit marker detecting the
    /// stop. `ym/play.sh -h` prints the usage.
    /// </summary>
    public static class Play
    {
        // The help text and the one-line failure the other two trees print.
        // A caller reading one tree's examples runs them against another.
        private const string HelpText =
                "play.sh - test drive a YM tune: pack it, build a player, run it under Hatari.\n"
                + "\n"
                + "  ym/play.sh song.ym                  # 960-byte rings, 24 values per call\n"
                + "  ym/play.sh -n256 song.ym            # smaller rings: less RAM, worse ratio\n"
                + "  ym/play.sh -n2048 -c32 song.ym      # longer calls: cheaper on average\n"
                + "  ym/play.sh -copies song.ym          # copies from the literal stream: the\n"
                + "                                      # player is built for the ring as its\n"
                + "                                      # window; -copies5 searches five\n"
                + "                                      # seconds a stream for a better parse\n"
                + "  ym/play.sh -o song.ym               # play once and stop, instead of\n"
                + "                                      # starting over at the end\n"
                + "  ym/play.sh -min13 -sec52 song.ym    # trim: start deep in a long tune\n"
                + "  ym/play.sh -startframe41403 -frames1729 song.ym\n"
                + "  ym/play.sh one.ym two.ym            # a set: subtunes, number keys pick\n"
                + "  ym/play.sh -perf song.ym            # the raster monitor: the frame step\n"
                + "                                      # works in red, timer ticks in green\n"
                + "                                      # (A) and blue (D), and a yellow bar\n"
                + "                                      # estimates the ticks' scanlines\n"
                + "  ym/play.sh -nomask song.ym          # drop the interrupt mask around the\n"
                + "                                      # frame write, which the writes do\n"
                + "                                      # not need: ticks then interleave\n"
                + "                                      # with it instead of waiting ~500\n"
                + "                                      # cycles behind it\n"
                + "\n"
                + "Press SPACE in the Hatari window to stop. Everything it builds lands in a\n"
                + "work directory next to the first tune. The trim flags take one tune.\n"
                + "\n"
                + "  HATARI=/path/to/hatari TOS=/path/to/tos.img ym/play.sh song.ym";

        private const string UsageText =
                "usage: play.sh [-perf] [-nomask] [-nRING] [-cCHUNK] [-kUNIT] [-copies[S]] [-o] song.ym...";

        public static void Main(string[] args)
        {
            int ring = YmxFormat.DefaultRingSize;
            int chunk = YmxFormat.DefaultChunk;
            string unit = "";
            string once = "";
            bool perf = false;
            bool maskBurst = true;
            var extra = new List<string>();
            int i = 0;
            for (; i < args.Length && args[i].StartsWith('-'); i++)
            {
                string a = args[i];
                if (a == "-perf")
                {
                    perf = true;
                }
                else if (a == "-nomask")
                {
                    maskBurst = false;
                }
                else if (a == "-o")
                {
                    once = "-o";
                }
                else if (a == "-h" || a == "--help")
                {
                    Console.WriteLine(HelpText);
                    return;
                }
                else if (a == "-copies")
                {
                    extra.Add(a);       // the packer's: copies, the opening passes
                }
                else if (a.StartsWith("-copies"))
                {
                    Number(a.Substring(7)); // a search of that many seconds, checked
                    extra.Add(a);           // here as -k is
                }
                else if (a.StartsWith("-n"))
                {
                    ring = Number(a[2..]);
                }
                else if (a.StartsWith("-c"))
                {
                    chunk = Number(a[2..]);
                }
                else if (a.StartsWith("-k"))
                {
                    // Parsed, so -k01 and -k1 name one work directory and an
                    // empty value is reported as the number it is not.
                    unit = "-k" + Number(a[2..]);
                }
                else
                {
                    extra.Add(a);       // the packer's: trim, -drumhz,
                }                       // whatever it reads next
            }
            var yms = new List<string>();
            for (; i < args.Length; i++)
            {
                yms.Add(args[i]);
            }
            if (yms.Count == 0)
            {
                throw Tools.Fail("usage: play.sh [-perf] [-nomask] [-nRING]"
                        + " [-cCHUNK] [-kUNIT] [-copies[S]] [-o] song.ym...");
            }
            foreach (string ym in yms)
            {
                if (!File.Exists(ym))
                {
                    throw Tools.Fail("play.sh: no such file: " + ym);
                }
            }
            string hatari = Emulator.Env("HATARI", "hatari");
            string tos = Emulator.Env("TOS",
                    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
                    + "/hatari-2.6.1_macos/tos-2.06.rom");
            if (!File.Exists(tos))
            {
                throw Tools.Fail("play.sh: no TOS image at " + tos
                        + " - set TOS=/path/to/tos.img");
            }

            // One directory per run, named after the first tune and the
            // shape, so a second run with a different ring size does not
            // overwrite the first.
            string first = Path.GetFullPath(yms[0]);
            string name = TuneSet.Stem(first)
                    + (yms.Count > 1 ? "+" + (yms.Count - 1) : "")
                    + "-n" + ring + "-c" + chunk
                    + (unit.Length == 0 ? "" : "-" + unit[1..]);
            string work = Path.Combine(Tools.DirectoryOf(first), name);

            var flags = new List<string> {"-n" + ring, "-c" + chunk};
            if (unit.Length != 0)
            {
                flags.Add(unit);
            }
            if (once.Length != 0)
            {
                flags.Add(once);
            }
            flags.AddRange(extra);

            TuneSet set = TuneSet.Of("play.sh", yms);
            Console.WriteLine("play.sh: packing " + string.Join(" ", yms));
            List<string> packed = Packing.Pack("play.sh", yms, work, flags);
            try
            {
                MkPrg.Build(new MkPrg.Options(Path.Combine(work, "PLAY.PRG"), packed,
                        set.Title, set.Composer, set.Names, perf, maskBurst, true));
            }
            catch (ArgumentException e)
            {
                // A set the core cannot serve: the combiner's own words, as
                // the Go tree prints them.
                throw Tools.Fail(e.Message);
            }

            string marker = Path.Combine(work, "YMXDONE.MRK");
            File.Delete(marker);
            Console.WriteLine("play.sh: starting Hatari - press SPACE in its"
                    + " window to stop");
            Emulator.Run("play.sh", hatari, tos, Path.Combine(work, "PLAY.PRG"),
                    marker);
            File.Delete(marker);
            Console.WriteLine("play.sh: stopped. The tune and the program are"
                    + " in " + work);
        }

        private static int Number(string text)
        {
            if (text.Length == 0 || !int.TryParse(text, NumberStyles.AllowLeadingSign,
                    CultureInfo.InvariantCulture, out int value))
            {
                throw Tools.Fail("play.sh: not a number: " + text);
            }
            return value;
        }
    }
}
