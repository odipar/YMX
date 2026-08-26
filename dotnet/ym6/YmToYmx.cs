using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using Ymx;

namespace Ym6
{
    /// <summary>
    /// One command from a YM dump to something that plays: a `.ymx`, an SNDH
    /// file, or a runnable TOS program. The output's extension picks which.
    ///
    /// <para>This is the tool a release ships as a standalone executable, so
    /// it carries the SNDH cores and the PRG stub as embedded resources
    /// rather than reading them out of a repository's dist/. Where a
    /// resource is absent - a build of this tree made before the binaries
    /// were assembled - it falls back to the repository, which is what the
    /// shell scripts have always used.</para>
    /// </summary>
    public static class YmToYmx
    {
        private const string UsageText =
            "usage: ym-to-ymx [options] output.{ymx|sndh|prg} tune.ym [more.ym ...]\n"
            + "\n"
            + "  The output's extension picks what is written:\n"
            + "    .ymx    the packed tune, one input only\n"
            + "    .sndh   an SNDH v2.2 file any SNDH host plays\n"
            + "    .prg    a TOS program that plays it\n"
            + "\n"
            + "packing\n"
            + "  -f              overwrite the output\n"
            + "  -o              play once: stop at the end instead of starting over\n"
            + "  -lF             start over from frame F; -l0 from the beginning\n"
            + "  -nN             ring size per stream, bytes (default 960)\n"
            + "  -cC             values decoded per call (default 24)\n"
            + "  -kK             ST4 unit size 1, 2 or 4 (default: the tune's"
            + " own shape)\n"
            + "  -minM -secS     trim: drop everything before M:S\n"
            + "  -startframeF -endframeF -framesN\n"
            + "                  the same window in frames\n"
            + "  -drumhzH        the drum rate ceiling (default 25600)\n"
            + "  -timersT        which MFP timer each channel runs on (default AD)\n"
            + "  -sidresume      the resume gap model, for maxYMiser tunes\n"
            + "\n"
            + "the SNDH file and the program\n"
            + "  -perf           build with the raster monitor\n"
            + "  -nomask         build with the frame write unmasked\n"
            + "  -tTitle         the SNDH TITL tag (default: the dump's own)\n"
            + "  -cComposer      the COMM tag - note -c is the chunk size when\n"
            + "                  it is followed by digits\n"
            + "  -Nnamesfile     subtune names, one per line\n"
            + "  -m              drop YMXDONE.MRK on exit, for scripted runs\n"
            + "\n"
            + "  -h, --help      this text";

        public static void Main(string[] args)
        {
            if (args.Length == 0 || args[0] == "-h" || args[0] == "--help")
            {
                Console.WriteLine(UsageText);
                return;
            }

            var packerFlags = new List<string>();
            string? title = null;
            string? composer = null;
            List<string>? names = null;
            bool perf = false;
            bool maskBurst = true;
            bool marker = false;

            int i = 0;
            for (; i < args.Length && args[i].StartsWith('-'); i++)
            {
                string flag = args[i];
                if (flag == "-perf")
                {
                    perf = true;
                }
                else if (flag == "-nomask")
                {
                    maskBurst = false;
                }
                else if (flag == "-m")
                {
                    marker = true;
                }
                else if (flag.StartsWith("-timers"))
                {
                    packerFlags.Add(flag);      // the packer's, not a title
                }
                else if (flag.StartsWith("-t") && flag.Length > 2)
                {
                    title = flag[2..];
                }
                else if (flag.StartsWith("-N") && flag.Length > 2)
                {
                    names = MkSndh.ReadNames(flag[2..]);
                }
                else if (flag.StartsWith("-c") && flag.Length > 2
                        && !char.IsDigit(flag[2]))
                {
                    composer = flag[2..];       // -c with digits is the packer's
                }
                else
                {
                    packerFlags.Add(flag);
                }
            }

            if (args.Length - i < 2)
            {
                throw Tools.Fail(UsageText);
            }
            string output = Path.GetFullPath(args[i++]);
            var yms = new List<string>();
            for (; i < args.Length; i++)
            {
                yms.Add(args[i]);
            }

            string kind = Path.GetExtension(output).ToLowerInvariant();
            if (kind != ".ymx" && kind != ".sndh" && kind != ".prg")
            {
                throw Tools.Fail("ym-to-ymx: the output's extension says what to"
                        + " write, and '" + kind + "' is not one of .ymx, .sndh"
                        + " or .prg");
            }
            if (kind == ".ymx" && yms.Count > 1)
            {
                throw Tools.Fail("ym-to-ymx: a .ymx holds one tune. Name a .sndh"
                        + " or a .prg output to combine " + yms.Count + " of them");
            }

            if (kind == ".ymx")
            {
                var argv = new List<string>(packerFlags) { yms[0], output };
                YmxCli.Main(argv.ToArray());
                return;
            }
            // The packer guards the .ymx it writes; the SNDH file and the
            // program are written here, so the guard is here too.
            if (!packerFlags.Contains("-f") && File.Exists(output))
            {
                throw Tools.Fail("ym-to-ymx: already existing output file "
                        + output);
            }

            string work = Path.Combine(Tools.DirectoryOf(output), ".ym_work");
            TuneSet set = TuneSet.Of("ym-to-ymx", yms);
            List<string> packed = Packing.Pack("ym-to-ymx", yms, work,
                    packerFlags, true);

            using var binaries = Embedded.Stage(packed, perf, maskBurst,
                    kind == ".prg");
            if (kind == ".sndh")
            {
                MkSndh.Build(MkSndh.Options.Of(output, packed,
                        title ?? set.Title, composer ?? set.Composer,
                        names ?? set.Names, perf, maskBurst));
            }
            else
            {
                MkPrg.Build(new MkPrg.Options(output, packed, title ?? set.Title,
                        composer ?? set.Composer, names ?? set.Names, perf,
                        maskBurst, marker));
            }
        }
    }

    /// <summary>
    /// The SNDH cores and the PRG stub this executable carries. A published
    /// build embeds them, so it combines without a repository; a build made
    /// from a tree whose dist/ was empty carries none, and the combiners then
    /// resolve them as they always have.
    ///
    /// <para>Staging writes the ones a run needs to a temporary directory and
    /// points YMX_CORE and YMX_STUB at them, which is the seam the combiners
    /// already read. Disposing removes the directory and restores whatever
    /// the caller had set.</para>
    /// </summary>
    internal sealed class Embedded : IDisposable
    {
        private readonly string? directory;
        private readonly string? core;
        private readonly string? stub;

        private Embedded(string? directory, string? core, string? stub)
        {
            this.directory = directory;
            this.core = core;
            this.stub = stub;
        }

        /// <summary>The core and stub for this run, staged where the
        /// combiners look. A resource this build does not carry is left to
        /// them to resolve.</summary>
        internal static Embedded Stage(List<string> packed, bool perf,
                bool maskBurst, bool wantStub)
        {
            string wasCore = Environment.GetEnvironmentVariable("YMX_CORE") ?? "";
            string wasStub = Environment.GetEnvironmentVariable("YMX_STUB") ?? "";
            int unit = UnitOf(packed);
            string suffix = (perf ? "-perf" : "") + (maskBurst ? "" : "-nomask");
            byte[]? coreBytes = Read("ymxsndh-k" + unit + suffix);
            byte[]? stubBytes = wantStub ? Read("ymxprg") : null;
            if (coreBytes == null && (stubBytes == null || !wantStub))
            {
                return new Embedded(null, null, null);      // nothing carried
            }

            string dir = Path.Combine(Path.GetTempPath(),
                    "ymx-" + Environment.ProcessId);
            Directory.CreateDirectory(dir);
            string? corePath = null;
            string? stubPath = null;
            if (coreBytes != null)
            {
                corePath = Path.Combine(dir, "core.bin");
                File.WriteAllBytes(corePath, coreBytes);
                Environment.SetEnvironmentVariable("YMX_CORE", corePath);
            }
            if (stubBytes != null)
            {
                stubPath = Path.Combine(dir, "stub.bin");
                File.WriteAllBytes(stubPath, stubBytes);
                Environment.SetEnvironmentVariable("YMX_STUB", stubPath);
            }
            return new Embedded(dir, corePath == null ? null : wasCore,
                    stubPath == null ? null : wasStub);
        }

        /// <summary>One binary's bytes, or null where this build carries
        /// none. The name is the stem and this release's own suffix, so a
        /// dist/ left holding an older release's binaries offers nothing
        /// here by accident.</summary>
        private static byte[]? Read(string stem)
        {
            string wanted = stem + Tools.BinarySuffix() + ".bin";
            Assembly assembly = typeof(Embedded).Assembly;
            foreach (string name in assembly.GetManifestResourceNames())
            {
                if (!name.EndsWith(wanted, StringComparison.Ordinal))
                {
                    continue;
                }
                using Stream? stream = assembly.GetManifestResourceStream(name);
                if (stream == null)
                {
                    return null;
                }
                using var bytes = new MemoryStream();
                stream.CopyTo(bytes);
                return bytes.ToArray();
            }
            return null;
        }

        /// <summary>The unit size the packed tunes share. YmxHeader reads
        /// it out of the first section that is a container, and a set whose
        /// sections are all stored reads at any unit size, so 2 - the
        /// packer's default - serves.</summary>
        private static int UnitOf(List<string> packed)
        {
            foreach (string tune in packed)
            {
                YmxHeader header = YmxHeader.Read(tune);
                if (!header.AnyUnit())
                {
                    return header.Unit;
                }
            }
            return 2;
        }

        public void Dispose()
        {
            if (core != null)
            {
                Environment.SetEnvironmentVariable("YMX_CORE",
                        core.Length == 0 ? null : core);
            }
            if (stub != null)
            {
                Environment.SetEnvironmentVariable("YMX_STUB",
                        stub.Length == 0 ? null : stub);
            }
            if (directory != null && Directory.Exists(directory))
            {
                Directory.Delete(directory, true);
            }
        }
    }
}
