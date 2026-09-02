using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;

namespace Ymx
{
    /// <summary>
    /// What the build tools need from the world outside the runtime, ported
    /// from org.ymx.Tools: where the repo is, and how to run a command. The
    /// shell wrappers pass the repository root in; the fallback covers
    /// running the assembly straight from dotnet/bin without a wrapper.
    /// </summary>
    public static class Tools
    {
        public static string Repo()
        {
            string? named = Environment.GetEnvironmentVariable("YMX_REPO");
            if (named != null)
            {
                return named;
            }
            // dotnet/bin/Release/net10.0/ymx.dll -> net10.0 -> Release -> bin
            // -> dotnet -> repo
            string at = AppContext.BaseDirectory.TrimEnd('/');
            for (int up = 0; up < 4; up++)
            {
                at = DirectoryOf(at);
            }
            return at;
        }

        /// <summary>Runs a command, returning its trimmed stdout, failing
        /// loudly.</summary>
        public static string Output(string directory, List<string> command)
        {
            var info = Start(directory, command);
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            using Process process = Run(info, command[0]);
            string stdout = process.StandardOutput.ReadToEnd()
                    + process.StandardError.ReadToEnd();
            process.WaitForExit();
            if (process.ExitCode != 0)
            {
                throw Fail(command[0] + " failed: " + stdout.Trim());
            }
            return stdout.Trim();
        }

        /// <summary>Runs a command quietly, returning its exit status.</summary>
        public static int Status(string directory, List<string> command)
        {
            try
            {
                var info = Start(directory, command);
                info.RedirectStandardOutput = true;
                info.RedirectStandardError = true;
                using Process process = Run(info, command[0]);
                process.StandardOutput.ReadToEnd();
                process.StandardError.ReadToEnd();
                process.WaitForExit();
                return process.ExitCode;
            }
            catch (Exception)
            {
                return -1;
            }
        }

        /// <summary>Runs a command with its output on ours, failing loudly.</summary>
        public static void RunLoudly(string directory, List<string> command)
        {
            using Process process = Run(Start(directory, command), command[0]);
            process.WaitForExit();
            if (process.ExitCode != 0)
            {
                throw Fail(command[0] + " failed (" + process.ExitCode + ")");
            }
        }

        private static ProcessStartInfo Start(string directory, List<string> command)
        {
            var info = new ProcessStartInfo(command[0])
            {
                WorkingDirectory = directory,
                UseShellExecute = false,
            };
            for (int i = 1; i < command.Count; i++)
            {
                info.ArgumentList.Add(command[i]);
            }
            return info;
        }

        private static Process Run(ProcessStartInfo info, string name)
        {
            try
            {
                Process? process = Process.Start(info);
                if (process == null)
                {
                    throw Fail("cannot run " + name);
                }
                return process;
            }
            catch (System.ComponentModel.Win32Exception e)
            {
                throw Fail("cannot run " + name + ": " + e.Message);
            }
        }

        /// <summary>The directory a path sits in; a path with no parent is a
        /// caller's mistake, not a case to carry a null through.</summary>
        public static string DirectoryOf(string path)
        {
            string? parent = Path.GetDirectoryName(Path.GetFullPath(path));
            if (string.IsNullOrEmpty(parent))
            {
                throw Fail(path + " has no directory to build in");
            }
            return parent;
        }

        /// <summary>The size line every builder ends with.</summary>
        public static string Plural(long n, string noun)
        {
            return n + " " + noun + (n == 1 ? "" : "s");
        }

        public static long Size(string file)
        {
            try
            {
                return new FileInfo(file).Length;
            }
            catch (IOException)
            {
                return 0;
            }
        }

        /// <summary>The prebuilt binaries' name suffix - the release
        /// version, so files from different releases tell apart on
        /// sight.</summary>
        public static string BinarySuffix()
        {
            return "-v" + YmxFormat.ReleaseName();
        }

        /// <summary>Prints the message and leaves, the way the shell scripts
        /// did.</summary>
        public static Exception Fail(string message)
        {
            Console.Error.WriteLine(message);
            Environment.Exit(1);
            throw new AssertionException("unreachable");
        }
    }

    /// <summary>
    /// Assembles the prebuilt player binaries, ported from org.ymx.MkCores:
    /// the SNDH cores, one per ST4 unit size and flag combination, and the
    /// PRG stub. The combiners' assembler step: MkSndh and MkPrg combine
    /// without one, and call in here when a binary under dist/ is missing
    /// or stale.
    /// </summary>
    public static class MkCores
    {
        /// <summary>The three cores for one flag combination, named for it.</summary>
        public static void Cores(string outDir, bool perf, bool nomask)
        {
            Cores(outDir, perf, nomask, 0);
        }

        /// <summary>As above, copies building for the default ring as the
        /// window: a core that decodes copies from the literal stream, and
        /// takes no ring wider than that window.</summary>
        public static void Cores(string outDir, bool perf, bool nomask, bool copies)
        {
            Cores(outDir, perf, nomask, copies ? YmxFormat.DefaultRingSize : 0);
        }

        /// <summary>As above, built for ring bytes as the window, 0 for
        /// none. A tune with copies plays only on a core whose window is its
        /// ring, so a ring other than the default gets a core of its own,
        /// named for it; the default ring's is the release's -copies core.
        /// The window counts units, so a unit the ring is not a whole number
        /// of gets no core, and the run says so.</summary>
        public static void Cores(string outDir, bool perf, bool nomask, int ring)
        {
            Directory.CreateDirectory(outDir);
            string suffix = WindowSuffix(ring) + (perf ? "-perf" : "")
                    + (nomask ? "-nomask" : "");
            string work = Path.Combine(outDir, ".cores_work");
            Directory.CreateDirectory(work);
            foreach (int unit in new[] {1, 2, 4})
            {
                if (ring % unit != 0)
                {
                    Console.WriteLine("ymxsndh-k" + unit + suffix + ": a ring of " + ring
                            + " bytes is not a whole number of " + unit
                            + "-byte units, so there is no core");
                    continue;
                }
                File.WriteAllText(Path.Combine(work, "core.S"),
                        "ST4_UNIT        equ     " + unit + "\n"
                        + "ST4_WINDOW      equ     " + (ring / unit) + "\n"
                        + "YMX_PERF        equ     " + (perf ? 1 : 0) + "\n"
                        + "YMX_MASK_BURST  equ     " + (nomask ? 0 : 1) + "\n"
                        + "        include \"YMX_sndh.S\"\n");
                string core = Path.Combine(outDir, "ymxsndh-k" + unit
                        + suffix + Tools.BinarySuffix() + ".bin");
                Tools.RunLoudly(work, new List<string> {"rmac", "-m68000", "-fr",
                        "+o3", "-i" + Path.Combine(Tools.Repo(), "68k"),
                        "-o", core, "core.S"});
                Console.WriteLine(core + ": " + Tools.Size(core) + " bytes");
            }
            File.Delete(Path.Combine(work, "core.S"));
            Directory.Delete(work);
        }

        /// <summary>The part of a core's name that says its window: nothing
        /// for none, -copies for the default ring, -copies-n(ring) for
        /// another. The combiners resolve a core by this name.</summary>
        internal static string WindowSuffix(int ring)
        {
            if (ring == 0)
            {
                return "";
            }
            return "-copies" + (ring == YmxFormat.DefaultRingSize ? "" : "-n" + ring);
        }

        /// <summary>The PRG stub.</summary>
        public static void Stub(string outDir)
        {
            Directory.CreateDirectory(outDir);
            string stub = Path.Combine(outDir,
                    "ymxprg" + Tools.BinarySuffix() + ".bin");
            Tools.RunLoudly(Path.Combine(Tools.Repo(), "68k"),
                    new List<string> {"rmac", "-m68000", "-fr", "+o3",
                            "-o", stub, "YMX_player.S"});
            Console.WriteLine(stub + ": " + Tools.Size(stub) + " bytes");
        }

        private const string UsageText =
                "usage: mkcores.sh [-perf] [-nomask] [-copies [-nN]] [outdir]";

        public static void Main(string[] args)
        {
            bool perf = false;
            bool nomask = false;
            bool copies = false;
            int ring = YmxFormat.DefaultRingSize;
            int i = 0;
            for (; i < args.Length; i++)
            {
                if (args[i] == "-perf")
                {
                    perf = true;
                }
                else if (args[i] == "-nomask")
                {
                    nomask = true;
                }
                else if (args[i] == "-copies")
                {
                    copies = true;
                }
                else if (args[i].StartsWith("-n"))
                {
                    // The ring the copies core is built for as its window: a
                    // tune with copies at that ring plays on no other core.
                    if (!int.TryParse(args[i].Substring(2), NumberStyles.None,
                            CultureInfo.InvariantCulture, out ring))
                    {
                        throw Tools.Fail("mkcores: not a number: " + args[i].Substring(2));
                    }
                    if (ring <= 0)
                    {
                        throw Tools.Fail("mkcores: a ring is at least one byte: " + args[i]);
                    }
                }
                else if (args[i].StartsWith('-'))
                {
                    throw Tools.Fail(UsageText);
                }
                else
                {
                    break;
                }
            }
            if (args.Length - i > 1 || (!copies && ring != YmxFormat.DefaultRingSize))
            {
                throw Tools.Fail(UsageText);
            }
            string outDir = i < args.Length ? args[i]
                    : Path.Combine(Tools.Repo(), "dist");
            Cores(outDir, perf, nomask, copies ? ring : 0);
            if (!perf && !nomask && !copies)
            {
                Stub(outDir);
            }
        }
    }
}
