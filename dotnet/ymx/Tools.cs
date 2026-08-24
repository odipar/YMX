using System;
using System.Collections.Generic;
using System.Diagnostics;
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

        /// <summary>Prints the message and leaves, the way the shell scripts
        /// did.</summary>
        /// <summary>The prebuilt binaries' name suffix - the format
        /// version, so files from different releases tell apart on
        /// sight.</summary>
        public static string BinarySuffix()
        {
            return "-v" + YmxFormat.VersionName();
        }

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
            Directory.CreateDirectory(outDir);
            string suffix = (perf ? "-perf" : "") + (nomask ? "-nomask" : "");
            string work = Path.Combine(outDir, ".cores_work");
            Directory.CreateDirectory(work);
            foreach (int unit in new[] {1, 2, 4})
            {
                File.WriteAllText(Path.Combine(work, "core.S"),
                        "ST4_UNIT        equ     " + unit + "\n"
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
                "usage: mkcores.sh [-perf] [-nomask] [outdir]";

        public static void Main(string[] args)
        {
            bool perf = false;
            bool nomask = false;
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
                else if (args[i].StartsWith('-'))
                {
                    throw Tools.Fail(UsageText);
                }
                else
                {
                    break;
                }
            }
            if (args.Length - i > 1)
            {
                throw Tools.Fail(UsageText);
            }
            string outDir = i < args.Length ? args[i]
                    : Path.Combine(Tools.Repo(), "dist");
            Cores(outDir, perf, nomask);
            if (!perf && !nomask)
            {
                Stub(outDir);
            }
        }
    }
}
