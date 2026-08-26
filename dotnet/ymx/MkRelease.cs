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
    /// release tagged binaries-v&lt;release version&gt;, replacing its assets
    /// and posting this release's section of doc/RELEASES.md as the notes:
    /// a new format version is a new release, so is a patch of one, and an
    /// unchanged release updates in place.
    /// </summary>
    public static class MkRelease
    {
        /// <summary>One core build: a unit size and the two assembly flags.</summary>
        internal sealed record Variant(int Unit, bool Perf, bool Nomask)
        {
            internal string FileName()
            {
                return "ymxsndh-k" + Unit + (Perf ? "-perf" : "")
                        + (Nomask ? "-nomask" : "") + Tools.BinarySuffix()
                        + ".bin";
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

        private const string UsageText =
                "usage: mkrelease.sh [-publish] [stagedir]\n"
                + "       mkrelease.sh -notes";

        public static void Main(string[] args)
        {
            bool publish = false;
            bool notesOnly = false;
            int i = 0;
            for (; i < args.Length; i++)
            {
                if (args[i] == "-publish")
                {
                    publish = true;
                }
                else if (args[i] == "-notes")
                {
                    notesOnly = true;
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
            if (notesOnly)
            {
                if (publish || i < args.Length)
                {
                    throw Tools.Fail(UsageText);
                }
                RepostNotes();
                return;
            }
            string dir = Path.GetFullPath(i < args.Length ? args[i]
                    : Path.Combine(Tools.Repo(), "dist", "release"));
            string commit = Tools.Output(Tools.Repo(),
                    new List<string> {"git", "rev-parse", "--short", "HEAD"});
            try
            {
                if (Directory.Exists(dir))
                {
                    foreach (string old in Directory.GetFileSystemEntries(dir))
                    {
                        File.Delete(old);      // a directory here throws, as
                    }                          // it does in the Java tree
                }
                Directory.CreateDirectory(dir);
            }
            catch (IOException)
            {
                throw Tools.Fail("mkrelease: cannot clear " + dir);
            }
            catch (UnauthorizedAccessException)
            {
                throw Tools.Fail("mkrelease: cannot clear " + dir);
            }

            for (int flags = 0; flags < 4; flags++)
            {
                MkCores.Cores(dir, (flags & 1) != 0, (flags & 2) != 0);
            }
            MkCores.Stub(dir);
            CarryStandalone(dir);

            var manifest = new StringBuilder();
            manifest.Append("YMX player binaries - release ")
                    .Append(YmxFormat.ReleaseName()).Append(", format version ")
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
                byte[] stub = MkPrg.ReadStub(Path.Combine(dir,
                        "ymxprg" + Tools.BinarySuffix() + ".bin"));
                VerifyStub(stub);
                manifest.Append(Entry("ymxprg" + Tools.BinarySuffix() + ".bin",
                        stub, "-  -"));
                foreach (string zip in Standalone(dir))
                {
                    manifest.Append(Entry(Path.GetFileName(zip),
                            File.ReadAllBytes(zip), "-  -"));
                }
                File.WriteAllText(Path.Combine(dir, "MANIFEST.txt"),
                        manifest.ToString(), Encoding.Latin1);
            }
            catch (Exception e) when (e is IOException || e is ArgumentException)
            {
                throw Tools.Fail("mkrelease: " + e.Message);
            }
            int tools = Standalone(dir).Count;
            Console.WriteLine(dir + ": " + (Matrix().Count + 1) + " binaries, "
                    + tools + (tools == 1 ? " standalone tool"
                            : " standalone tools")
                    + " and MANIFEST.txt, release " + YmxFormat.ReleaseName());

            if (publish)
            {
                try
                {
                    Publish(dir, commit);
                }
                catch (ArgumentException e)
                {
                    throw Tools.Fail(e.Message);
                }
            }
        }

        /// <summary>The GitHub release tagged by the release version, its
        /// assets replaced and its notes rewritten, so the commit they name
        /// is the one the assets were assembled at and the account of what
        /// changed is this release's section of doc/RELEASES.md. A release
        /// created here is tagged at the staged commit; a tag that exists
        /// stays where it is, so a run whose HEAD has moved past it stops
        /// instead of posting notes naming a commit the tag does not
        /// reach.</summary>
        /// <summary>
        /// The page's text: this release's section of doc/RELEASES.md, then
        /// the line naming the commit the binaries were assembled at. Both
        /// the publish and the notes-only path read it here, so the page
        /// reads the same however it was written.
        /// </summary>
        internal static string Notes(string commit)
        {
            return Reflow(ReleaseNotes())
                    + "\n\nPrebuilt SNDH cores and the PRG stub, assembled at "
                    + commit
                    + ". doc/BINARIES.md is the combine contract; MANIFEST.txt"
                    + " lists sizes and SHA-256 digests.";
        }

        /// <summary>
        /// The section as a release page wants it: one line to a paragraph,
        /// one to a list item. doc/RELEASES.md wraps at the width the
        /// repository holds to, and the page renders every one of those
        /// breaks, so a wrap that reads straight in the file reads ragged
        /// there. Table rows and fenced blocks keep the lines they were
        /// written with.
        /// </summary>
        internal static string Reflow(string section)
        {
            var outText = new StringBuilder();
            var line = new StringBuilder();
            bool fenced = false;
            foreach (string raw in section.Split("\n"))
            {
                string text = raw.Trim();
                bool fence = text.StartsWith("```");
                if (fenced || fence || text.StartsWith("|"))
                {
                    if (line.Length > 0)
                    {
                        outText.Append(line).Append('\n');
                        line.Clear();
                    }
                    outText.Append(raw).Append('\n');
                    if (fence)
                    {
                        fenced = !fenced;
                    }
                    continue;
                }
                if (text.Length == 0)
                {
                    if (line.Length > 0)
                    {
                        outText.Append(line).Append('\n');
                        line.Clear();
                    }
                    outText.Append('\n');
                    continue;
                }
                if (text.StartsWith("- ") || text.StartsWith("#"))
                {
                    if (line.Length > 0)
                    {
                        outText.Append(line).Append('\n');
                        line.Clear();
                    }
                }
                else if (line.Length > 0)
                {
                    line.Append(' ');
                }
                line.Append(text);
            }
            if (line.Length > 0)
            {
                outText.Append(line);
            }
            return outText.ToString().Trim();
        }

        /// <summary>
        /// Rewrite a published release's notes, and nothing else: no asset
        /// is replaced and the tag stays where it is. The commit the notes
        /// name is the tag's own rather than HEAD, so the page keeps saying
        /// where its binaries came from however far main has moved since -
        /// a reworded section is the page changing, not the release.
        /// </summary>
        private static void RepostNotes()
        {
            string tag = Tag();
            if (Tools.Status(Tools.Repo(),
                    new List<string> {"gh", "release", "view", tag}) != 0)
            {
                throw Tools.Fail("mkrelease: there is no release " + tag
                        + " to write notes for - publish it first");
            }
            string tagged = Tools.Output(Tools.Repo(), new List<string>
                    {"gh", "api", "repos/{owner}/{repo}/commits/" + tag,
                    "--jq", ".sha"});
            Tools.RunLoudly(Tools.Repo(),
                    EditCommand(tag, Notes(ShortSha(tagged))));
            Console.WriteLine("notes rewritten for " + tag + " at "
                    + ShortSha(tagged));
        }

        private static void Publish(string dir, string commit)
        {
            string tag = Tag();
            string head = Tools.Output(Tools.Repo(),
                    new List<string> {"git", "rev-parse", "HEAD"});
            string notes = Notes(commit);
            if (Tools.Status(Tools.Repo(),
                    new List<string> {"gh", "release", "view", tag}) != 0)
            {
                Tools.RunLoudly(Tools.Repo(), CreateCommand(tag, head, notes));
            }
            else
            {
                string tagged = Tools.Output(Tools.Repo(), new List<string>
                        {"gh", "api", "repos/{owner}/{repo}/commits/" + tag,
                        "--jq", ".sha"});
                if (tagged != head)
                {
                    throw new ArgumentException("mkrelease: " + tag
                            + " is tagged at " + ShortSha(tagged) + " and this"
                            + " run staged " + commit + " - stage from "
                            + ShortSha(tagged) + ", or delete the release and"
                            + " its tag and publish again");
                }
                Tools.RunLoudly(Tools.Repo(), EditCommand(tag, notes));
            }
            Tools.RunLoudly(Tools.Repo(), UploadCommand(dir, tag));
            Console.WriteLine("published " + tag + " at " + commit);
        }

        /// <summary>The command that creates the release: --target tags the
        /// commit whose bytes are staged, rather than the default branch's
        /// head. The whole SHA goes in, not the short form the notes
        /// carry.</summary>
        internal static List<string> CreateCommand(string tag, string head,
                string notes)
        {
            return new List<string> {"gh", "release", "create", tag,
                    "--target", head,
                    "--title", "YMX player binaries " + YmxFormat.ReleaseName()
                            + ", format " + YmxFormat.VersionName(),
                    "--notes", notes};
        }

        /// <summary>The command that rewrites an existing release's notes.
        /// It moves no tag, which is why the caller reads the tag's commit
        /// first.</summary>
        internal static List<string> EditCommand(string tag, string notes)
        {
            return new List<string> {"gh", "release", "edit", tag,
                    "--notes", notes};
        }

        /// <summary>The command that replaces every asset: each core, the
        /// stub, each standalone zip and the manifest, out of the staging
        /// directory.</summary>
        internal static List<string> UploadCommand(string dir, string tag)
        {
            var upload = new List<string> {"gh", "release", "upload", tag,
                    "--clobber"};
            foreach (Variant variant in Matrix())
            {
                upload.Add(Path.Combine(dir, variant.FileName()));
            }
            upload.Add(Path.Combine(dir,
                    "ymxprg" + Tools.BinarySuffix() + ".bin"));
            foreach (string zip in Standalone(dir))
            {
                upload.Add(zip);
            }
            upload.Add(Path.Combine(dir, "MANIFEST.txt"));
            return upload;
        }

        /// <summary>
        /// The standalone <c>ym-to-ymx</c> zips this release carries, one
        /// per platform: the executable and its launcher, for a machine with
        /// no SDK. <c>ymx/publish.sh</c> builds them into
        /// <c>dist/standalone</c>, and staging copies in each one whose name
        /// carries this release's suffix. A release staged without them
        /// carries none and publishes anyway - a combiner reads the cores,
        /// not the tool.
        /// </summary>
        private static void CarryStandalone(string dir)
        {
            string built = Path.Combine(Tools.Repo(), "dist", "standalone");
            if (!Directory.Exists(built))
            {
                return;
            }
            try
            {
                foreach (string zip in Directory.GetFiles(built,
                        "ym-to-ymx-*" + Tools.BinarySuffix() + ".zip"))
                {
                    File.Copy(zip, Path.Combine(dir, Path.GetFileName(zip)));
                }
            }
            catch (IOException e)
            {
                throw Tools.Fail("mkrelease: cannot carry " + built + ": "
                        + e.Message);
            }
        }

        /// <summary>The zips staging left in the directory, in name
        /// order.</summary>
        internal static List<string> Standalone(string dir)
        {
            try
            {
                var zips = new List<string>(Directory.GetFiles(dir,
                        "ym-to-ymx-*" + Tools.BinarySuffix() + ".zip"));
                zips.Sort(StringComparer.Ordinal);
                return zips;
            }
            catch (IOException)
            {
                return new List<string>();
            }
        }

        /// <summary>A commit as the notes spell it: the first seven
        /// characters of the SHA, or all of a shorter string.</summary>
        private static string ShortSha(string sha)
        {
            return sha.Length > 7 ? sha.Substring(0, 7) : sha;
        }

        /// <summary>The tag this release publishes under: the release
        /// version, the format version and the patch together.</summary>
        public static string Tag()
        {
            return "binaries-v" + YmxFormat.ReleaseName();
        }

        /// <summary>This release's section of doc/RELEASES.md, from its
        /// heading to the next: the account the release page carries. A
        /// release with no section of its own is not published - this
        /// throws rather than leaving, so a test can call it.</summary>
        public static string ReleaseNotes()
        {
            string heading = "## " + YmxFormat.ReleaseName();
            string path = Path.Combine(Tools.Repo(), "doc", "RELEASES.md");
            string document;
            try
            {
                document = File.ReadAllText(path);
            }
            catch (IOException e)
            {
                throw new ArgumentException("mkrelease: doc/RELEASES.md: "
                        + e.Message);
            }
            int start = document.IndexOf(heading + "\n");
            if (start < 0)
            {
                throw new ArgumentException("mkrelease: doc/RELEASES.md"
                        + " carries no \"" + heading + "\" section - write"
                        + " what this release changes before publishing it");
            }
            int next = document.IndexOf("\n## ", start + heading.Length);
            return (next < 0 ? document.Substring(start + heading.Length + 1)
                    : document.Substring(start + heading.Length + 1,
                            next - start - heading.Length - 1)).Trim();
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
                    || core[MkSndh.CoreMagic + 1] != 'M'
                    || core[MkSndh.CoreMagic + 2] != 'X'
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
                        + ", this release reads " + YmxFormat.VersionName());
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
            int fixedBytes = MkSndh.Word(core, MkSndh.CoreWorkFixed);
            if (fixedBytes == 0 || (fixedBytes & 1) != 0)
            {
                throw new ArgumentException(variant.FileName() + " gives F = "
                        + fixedBytes + ", the workspace bytes before the rings:"
                        + " nonzero and even");
            }
            if (!ZeroLong(core, MkSndh.CoreTableOff))
            {
                throw new ArgumentException(variant.FileName() + " carries a"
                        + " table offset; a combiner patches that long, so a"
                        + " released core carries 0");
            }
            if (!ZeroLong(core, MkSndh.CoreWorkOff))
            {
                throw new ArgumentException(variant.FileName() + " carries a"
                        + " workspace offset; a combiner patches that long, so a"
                        + " released core carries 0");
            }
            if ((core.Length & 1) != 0)
            {
                throw new ArgumentException(variant.FileName() + " is odd-sized");
            }
        }

        /// <summary>The descriptor checks MkPrg performs at wrap time, run
        /// once at release time, with two of the fields a combiner patches
        /// read back: a released stub carries the frame count and the flags
        /// as the assembler left them.</summary>
        internal static void VerifyStub(byte[] stub)
        {
            string name = "ymxprg" + Tools.BinarySuffix() + ".bin";
            if (stub.Length < 18 || stub[MkPrg.StubMagic] != 'Y'
                    || stub[MkPrg.StubMagic + 1] != 'M'
                    || stub[MkPrg.StubMagic + 2] != 'X'
                    || stub[MkPrg.StubMagic + 3] != 'P')
            {
                throw new ArgumentException(name + " is not a PRG stub");
            }
            if (MkSndh.Word(stub, MkPrg.StubVersion) != 2)
            {
                throw new ArgumentException(name
                        + " carries stub descriptor version "
                        + MkSndh.Word(stub, MkPrg.StubVersion) + ", not 2");
            }
            if (!ZeroLong(stub, MkPrg.StubFrames))
            {
                throw new ArgumentException(name + " carries a frame count;"
                        + " a combiner patches that long, so a released stub"
                        + " carries 0");
            }
            if (MkSndh.Word(stub, MkPrg.StubFlags) != 0)
            {
                throw new ArgumentException(name + " carries flags "
                        + MkSndh.Word(stub, MkPrg.StubFlags) + "; a combiner"
                        + " patches that word, so a released stub carries 0");
            }
            if ((stub.Length & 1) != 0)
            {
                throw new ArgumentException(name + " is odd-sized");
            }
        }

        /// <summary>Whether the long at an offset is zero - the value the
        /// assembler writes where a combiner patches.</summary>
        private static bool ZeroLong(byte[] bytes, int at)
        {
            return MkSndh.Word(bytes, at) == 0 && MkSndh.Word(bytes, at + 2) == 0;
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
