using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace Ymx
{
    /// <summary>
    /// Stages a release of the prebuilt player binaries, ported from
    /// org.ymx.MkRelease: every core variant - three unit sizes by the perf
    /// and mask flags - plus the PRG stub, each assembled by MkCores,
    /// verified against the descriptors MkSndh and MkPrg read, and listed in
    /// a manifest with its SHA-256. -publish creates or updates the GitHub
    /// release tagged binaries-v&lt;format version&gt;, replacing its assets.
    /// </summary>
    public static class MkRelease
    {
        /// <summary>One core build: a unit size and the two assembly flags.</summary>
        internal sealed record Variant(int Unit, bool Perf, bool Nomask)
        {
            internal string FileName()
            {
                return "ymxsndh-k" + Unit + (Perf ? "-perf" : "")
                        + (Nomask ? "-nomask" : "") + ".bin";
            }

            internal int Flags()
            {
                return (Perf ? MkSndh.CoreFlagPerf : 0)
                        | (Nomask ? MkSndh.CoreFlagNomask : 0);
            }
        }

        /// <summary>Every core the release carries.</summary>
        internal static List<Variant> Matrix()
        {
            var variants = new List<Variant>();
            foreach (int unit in new[] {1, 2, 4})
            {
                for (int flags = 0; flags < 4; flags++)
                {
                    variants.Add(new Variant(unit, (flags & 1) != 0,
                            (flags & 2) != 0));
                }
            }
            return variants;
        }

        private const string UsageText = "usage: mkrelease.sh [-publish] [stagedir]";

        public static void Main(string[] args)
        {
            bool publish = false;
            int i = 0;
            for (; i < args.Length; i++)
            {
                if (args[i] == "-publish")
                {
                    publish = true;
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
            string dir = Path.GetFullPath(i < args.Length ? args[i]
                    : Path.Combine(Tools.Repo(), "dist", "release"));
            string commit = Tools.Output(Tools.Repo(),
                    new List<string> {"git", "rev-parse", "--short", "HEAD"});
            if (Directory.Exists(dir))
            {
                foreach (string old in Directory.GetFiles(dir))
                {
                    File.Delete(old);
                }
            }
            Directory.CreateDirectory(dir);

            for (int flags = 0; flags < 4; flags++)
            {
                MkCores.Cores(dir, (flags & 1) != 0, (flags & 2) != 0);
            }
            MkCores.Stub(dir);

            var manifest = new StringBuilder();
            manifest.Append("YMX player binaries - format version ")
                    .Append(YmxFormat.VersionName()).Append(", descriptor version 1\n");
            manifest.Append("source commit ").Append(commit).Append('\n');
            manifest.Append("doc/BINARIES.md is the combine contract\n\n");
            manifest.Append("name  bytes  sha256  unit  flags\n");
            try
            {
                foreach (Variant variant in Matrix())
                {
                    byte[] core = File.ReadAllBytes(
                            Path.Combine(dir, variant.FileName()));
                    VerifyCore(core, variant);
                    manifest.Append(Entry(variant.FileName(), core,
                            variant.Unit + "  " + variant.Flags()));
                }
                byte[] stub = MkPrg.ReadStub(Path.Combine(dir, "ymxprg.bin"));
                manifest.Append(Entry("ymxprg.bin", stub, "-  -"));
                File.WriteAllText(Path.Combine(dir, "MANIFEST.txt"),
                        manifest.ToString(), Encoding.Latin1);
            }
            catch (Exception e) when (e is IOException || e is ArgumentException)
            {
                throw Tools.Fail("mkrelease: " + e.Message);
            }
            Console.WriteLine(dir + ": " + (Matrix().Count + 1)
                    + " binaries and MANIFEST.txt, format version "
                    + YmxFormat.VersionName());

            if (publish)
            {
                Publish(dir, commit);
            }
        }

        /// <summary>The GitHub release tagged by format version, its assets
        /// replaced and its notes rewritten, so the commit they name is the
        /// one the assets were assembled at.</summary>
        private static void Publish(string dir, string commit)
        {
            string tag = "binaries-v" + YmxFormat.VersionName();
            string notes = "Prebuilt SNDH cores and the PRG stub, assembled"
                    + " at " + commit + ". doc/BINARIES.md is the combine"
                    + " contract; MANIFEST.txt lists sizes and SHA-256 digests.";
            if (Tools.Status(Tools.Repo(),
                    new List<string> {"gh", "release", "view", tag}) != 0)
            {
                Tools.RunLoudly(Tools.Repo(), new List<string> {"gh", "release",
                        "create", tag,
                        "--title", "YMX player binaries, format " + YmxFormat.VersionName(),
                        "--notes", notes});
            }
            else
            {
                Tools.RunLoudly(Tools.Repo(), new List<string> {"gh", "release",
                        "edit", tag, "--notes", notes});
            }
            var upload = new List<string> {"gh", "release", "upload", tag,
                    "--clobber"};
            foreach (Variant variant in Matrix())
            {
                upload.Add(Path.Combine(dir, variant.FileName()));
            }
            upload.Add(Path.Combine(dir, "ymxprg.bin"));
            upload.Add(Path.Combine(dir, "MANIFEST.txt"));
            Tools.RunLoudly(Tools.Repo(), upload);
            Console.WriteLine("published " + tag);
        }

        /// <summary>One manifest line: name, size, digest, and the given
        /// tail columns.</summary>
        private static string Entry(string name, byte[] bytes, string tail)
        {
            return name + "  " + bytes.Length + "  " + Sha256(bytes) + "  "
                    + tail + '\n';
        }

        /// <summary>The descriptor checks MkSndh performs at combine time,
        /// run once at release time against the variant each file is named
        /// for.</summary>
        internal static void VerifyCore(byte[] core, Variant variant)
        {
            if (core.Length < 34 || core[MkSndh.CoreMagic] != 'Y'
                    || core[MkSndh.CoreMagic + 3] != 'C')
            {
                throw new ArgumentException(variant.FileName()
                        + " is not an SNDH core");
            }
            if (MkSndh.Word(core, MkSndh.CoreVersion) != 1)
            {
                throw new ArgumentException(variant.FileName()
                        + " carries descriptor version "
                        + MkSndh.Word(core, MkSndh.CoreVersion) + ", not 1");
            }
            if (MkSndh.Word(core, MkSndh.CoreFormat) != YmxFormat.Version)
            {
                throw new ArgumentException(variant.FileName()
                        + " reads format version "
                        + YmxFormat.VersionName(MkSndh.Word(core, MkSndh.CoreFormat))
                        + ", the release is " + YmxFormat.VersionName());
            }
            if (MkSndh.Word(core, MkSndh.CoreUnit) != variant.Unit)
            {
                throw new ArgumentException(variant.FileName() + " serves unit "
                        + MkSndh.Word(core, MkSndh.CoreUnit) + ", its name says "
                        + variant.Unit);
            }
            if (MkSndh.Word(core, MkSndh.CoreFlags) != variant.Flags())
            {
                throw new ArgumentException(variant.FileName() + " carries flags "
                        + MkSndh.Word(core, MkSndh.CoreFlags) + ", its name says "
                        + variant.Flags());
            }
            if ((core.Length & 1) != 0)
            {
                throw new ArgumentException(variant.FileName() + " is odd-sized");
            }
        }

        internal static string Sha256(byte[] bytes)
        {
            byte[] digest = SHA256.HashData(bytes);
            var hex = new StringBuilder();
            foreach (byte b in digest)
            {
                hex.Append(b.ToString("x2"));
            }
            return hex.ToString();
        }
    }
}
