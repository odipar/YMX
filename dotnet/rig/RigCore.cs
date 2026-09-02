using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace Rig
{
    /// <summary>
    /// What every rig shares, ported from the Java rig: the repository's
    /// paths, the assembled player builds with their symbol tables, the
    /// packers run as the tools a user runs.
    /// The scratch directory caches packs on the tune bytes, the options and
    /// the built packer, so a pack from an earlier build is never read back
    /// into a later one.
    /// </summary>
    public static class Rig
    {
        public static readonly string Repo = Ymx.Tools.Repo();
        public static readonly string Scratch =
                Path.Combine(Repo, "ymx", "test", ".work");

        public const ulong Code = 0x001000;
        public const ulong FileAt = 0x010000;
        public const ulong Work = 0x040000;
        public const ulong StackTop = 0x090000;
        public const ulong Magic = 0x0A0000;
        public const ulong Psg = 0xFFFF8800;
        public const ulong PsgPage = 0xFFFF8000;
        public const ulong MfpPage = 0xFFFFF000;    // $FFFFFAxx: the timers
        public const ulong Vectors = 0x000000;      // the timer vectors

        public const int Streams = 25;      // fourteen register, eleven script
        public const int YmxDefaultMap = 0x9C;  // the packer's: 0->A 1->D 2->B 3->C
        public const int YmxFixed = 58 + Streams * 64 + Streams * 32;  // before the rings

        public const int OffsetRingSize = 16;   // the header's ring word

        /// <summary>The workspace a packed tune needs, read out of the
        /// tune's own header. The packer raises the ring above the size it
        /// was asked for where a tune that starts over needs it, so the ring
        /// a caller passed to Pack is not the ring the file carries: sizing
        /// from anything but the header under-reserves, and the player
        /// writes past the end.</summary>
        public static int WorkspaceFor(byte[] packed)
        {
            int ring = ((packed[OffsetRingSize] & 0xFF) << 8)
                    | (packed[OffsetRingSize + 1] & 0xFF);
            return YmxFixed + Streams * ring;
        }

        /// <summary>One assembled player build and where its labels sit.</summary>
        public sealed record Build(byte[] Binary, Dictionary<string, int> Symbols);

        private static readonly Dictionary<string, Build> Assembled = new();

        /// <summary>YMX.S plus the decoder, built for one unit size, as one
        /// flat blob. perf builds the raster monitor in. YMX_NOMASK in the
        /// environment runs the rig - the size check aside - against the
        /// unmasked-frame-write build.</summary>
        public static Build Assemble(int unit, bool perf)
        {
            return Assemble(unit, perf,
                    Environment.GetEnvironmentVariable("YMX_NOMASK") == null, false);
        }

        /// <summary>The same with the copy code built in: a build that
        /// decodes copies from the literal stream, at the window it reads
        /// off the ring at init.</summary>
        public static Build Assemble(int unit, bool perf, bool copies)
        {
            return Assemble(unit, perf,
                    Environment.GetEnvironmentVariable("YMX_NOMASK") == null,
                    copies);
        }

        /// <summary>The masked build regardless of YMX_NOMASK: the README's
        /// byte counts quote it, so the size check measures it.</summary>
        public static Build AssembleMasked(int unit, bool perf)
        {
            return Assemble(unit, perf, true, false);
        }

        private static Build Assemble(int unit, bool perf, bool masked,
                bool copies)
        {
            string tag = unit + (perf ? "p" : "") + (masked ? "" : "n")
                    + (copies ? "c" : "");
            if (Assembled.TryGetValue(tag, out Build? held))
            {
                return held;
            }
            Directory.CreateDirectory(Scratch);
            string source = Path.Combine(Scratch, "link" + tag + ".S");
            File.WriteAllText(source, "ST4_UNIT    equ     " + unit + "\n"
                    + (copies ? "ST4_WINDOW  equ     1\n" : "")
                    + (perf ? "YMX_PERF    equ     1\n" : "")
                    + (masked ? "" : "YMX_MASK_BURST equ  0\n")
                    + "        include \"YMX.S\"\n"
                    + "        include \"ST4_wrap.S\"\n");
            string binary = Path.Combine(Scratch, "link" + tag + ".bin");
            string listing = Path.Combine(Scratch, "link" + tag + ".lst");
            Run(new List<string> {"rmac", "-m68000", "-fr", "+o3",
                    "-i" + Path.Combine(Repo, "68k"), "-l*" + listing,
                    "-o", binary, source});
            var built = new Build(File.ReadAllBytes(binary), SymbolTable(listing));
            Assembled[tag] = built;
            return built;
        }

        /// <summary>Every label in an rmac listing, from its symbol table -
        /// two symbols per line, which is why the pattern matches within a
        /// line.</summary>
        public static Dictionary<string, int> SymbolTable(string listing)
        {
            var pattern = new Regex("(\\S+)\\s+([0-9A-F]{16})\\s+[atdb]\\b");
            var symbols = new Dictionary<string, int>();
            foreach (string line in File.ReadAllLines(listing))
            {
                foreach (Match symbol in pattern.Matches(line))
                {
                    symbols[symbol.Groups[1].Value] = (int) Convert.ToInt64(
                            symbol.Groups[2].Value, 16);
                }
            }
            foreach (string wanted in new[] {"YMX_init", "YMX_play", "YMX_stop"})
            {
                if (!symbols.ContainsKey(wanted))
                {
                    throw new InvalidOperationException(wanted
                            + " missing from the listing");
                }
            }
            return symbols;
        }

        /// <summary>One label's value, checked present.</summary>
        public static int Symbol(Dictionary<string, int> symbols, string name)
        {
            if (!symbols.TryGetValue(name, out int value))
            {
                throw new InvalidOperationException(name
                        + " missing from the listing");
            }
            return value;
        }

        /// <summary>The C# tree's own launcher: dotnet against this
        /// assembly, so the rig runs the packers it was built with.</summary>
        public static List<string> OwnTool(string tool)
        {
            return new List<string> {"dotnet",
                    Path.Combine(AppContext.BaseDirectory, "ymx.dll"), tool};
        }

        /// <summary>Runs the real YM packer, cached on the tune and the
        /// packing options. loops says the tune starts over at the end;
        /// false packs one that stops.</summary>
        public static byte[] Pack(byte[] tune, int ring, int chunk, bool loops,
                int unit, params string[] extra)
        {
            string tag = string.Join("", extra);
            string cached = Path.Combine(Scratch, Sha1(tune) + "-n" + ring
                    + "-c" + chunk + "-k" + unit + (loops ? "loops" : "once")
                    + tag + "-" + PackerBuild() + ".ymx");
            if (!File.Exists(cached))
            {
                var options = new List<string> {"-f", "-n" + ring, "-c" + chunk,
                        "-k" + unit};
                if (!loops)
                {
                    options.Add("-o");
                }
                options.AddRange(extra);
                PackWith("ymx", tune, ".ym", cached, options);
            }
            return File.ReadAllBytes(cached);
        }

        /// <summary>A digest of the built packer, part of every pack's cache
        /// name. Without it a pack made before a packer change is read back
        /// after it, and the disagreement reads as a player fault.</summary>
        private static string PackerBuild()
        {
            packerBuild ??= Sha1(File.ReadAllBytes(
                    Path.Combine(AppContext.BaseDirectory, "ymx.dll")));
            return packerBuild;
        }

        private static string? packerBuild;


        private static void PackWith(string packer, byte[] source, string suffix,
                string cached, List<string> options)
        {
            Directory.CreateDirectory(Scratch);
            string tune = Path.Combine(Scratch,
                    "tune" + Environment.ProcessId + suffix);
            try
            {
                File.WriteAllBytes(tune, source);
                List<string> command = OwnTool(packer);
                command.AddRange(options);
                command.Add(tune);
                command.Add(cached);
                Run(command);
            }
            finally
            {
                File.Delete(tune);
            }
        }

        /// <summary>A command that must succeed, its output captured either
        /// way.</summary>
        public static string Run(List<string> command)
        {
            Finished finished = TryRun(command);
            if (finished.ExitCode != 0)
            {
                throw new InvalidOperationException(command[0] + " failed:\n"
                        + finished.Output);
            }
            return finished.Output;
        }

        /// <summary>The same command, allowed to fail: exit code and
        /// combined output.</summary>
        public sealed record Finished(int ExitCode, string Output);

        public static Finished TryRun(List<string> command)
        {
            var info = new ProcessStartInfo(command[0])
            {
                WorkingDirectory = Scratch,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            for (int i = 1; i < command.Count; i++)
            {
                info.ArgumentList.Add(command[i]);
            }
            // The launched tool resolves the repo the way the wrappers do.
            info.Environment["YMX_REPO"] = Repo;
            using Process process = Process.Start(info)
                    ?? throw new InvalidOperationException("cannot run " + command[0]);
            string output = process.StandardOutput.ReadToEnd()
                    + process.StandardError.ReadToEnd();
            process.WaitForExit();
            return new Finished(process.ExitCode, output);
        }



        private static void Word(MemoryStream stream, int value)
        {
            stream.WriteByte((byte) (value >>> 8));
            stream.WriteByte((byte) value);
        }

        private static void LongWord(MemoryStream stream, int value)
        {
            Word(stream, value >>> 16);
            Word(stream, value);
        }

        public static string Sha1(byte[] bytes)
        {
            byte[] digest = SHA1.HashData(bytes);
            var hex = new StringBuilder();
            for (int i = 0; i < 6; i++)
            {
                hex.Append(digest[i].ToString("x2"));
            }
            return hex.ToString();
        }
    }
}
