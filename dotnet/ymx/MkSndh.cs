using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace Ymx
{
    /// <summary>
    /// Combines a prebuilt SNDH core with packed tunes into an SNDH v2.2
    /// file, ported from org.ymx.MkSndh - the canonical form of this player,
    /// which MkPrg then wraps in a runnable program around the same bytes.
    /// No assembler runs here: doc/BINARIES.md is the byte contract.
    /// </summary>
    public static class MkSndh
    {
        /// <summary>SNDH's '##' tag is two ASCII digits.</summary>
        public const int MaxSubtunes = 99;

        /// <summary>The core descriptor: 'YMXC' at this offset, then
        /// version, unit, flags, format version and the workspace's fixed
        /// size, words; then the two offsets this tool patches, longs.</summary>
        public const int CoreMagic = 12;
        public const int CoreVersion = 16;
        public const int CoreUnit = 18;
        public const int CoreFlags = 20;
        public const int CoreFormat = 22;
        public const int CoreWorkFixed = 24;
        public const int CoreTableOff = 26;
        public const int CoreWorkOff = 30;

        /// <summary>Core flag bits, matching YMX_sndh.S.</summary>
        public const int CoreFlagPerf = 1;
        public const int CoreFlagNomask = 2;

        /// <summary>What the caller requested; every field but the tunes has
        /// a default.</summary>
        public sealed record Options(string Output, List<string> Tunes,
                string Title, string? Composer, List<string>? Names,
                bool Perf, bool MaskBurst)
        {
            public static Options Of(string output, List<string> tunes,
                    string title, string? composer, List<string>? names,
                    bool perf, bool maskBurst)
            {
                if (tunes.Count == 0)
                {
                    throw Tools.Fail("mksndh: no tunes");
                }
                if (tunes.Count > MaxSubtunes)
                {
                    throw Tools.Fail("mksndh: SNDH's '##' tag caps a file at "
                            + MaxSubtunes + " subtunes");
                }
                return new Options(output, tunes, title, composer, names, perf,
                        maskBurst);
            }
        }

        /// <summary>What it built, for the caller that wraps it.</summary>
        public sealed record Result(string Output, int Subtunes, YmxHeader Shape);

        public static Result Build(Options options)
        {
            return Build(options, ResolveCore(options));
        }

        /// <summary>As above, with the core given rather than resolved from
        /// dist/.</summary>
        public static Result Build(Options options, string corePath)
        {
            byte[] core = ReadCore(corePath, options);

            var tunes = new List<byte[]>();
            var frms = new List<int>();
            var names = new List<string>();
            YmxHeader? first = null;
            int rate = 0;
            int maxRing = 0;
            int n = 0;
            foreach (string tune in options.Tunes)
            {
                YmxHeader header;
                try
                {
                    header = YmxHeader.Read(tune);
                }
                catch (IOException e)
                {
                    throw Tools.Fail("mksndh: " + e.Message);
                }
                if (!header.AnyUnit() && header.Unit != Word(core, CoreUnit))
                {
                    throw new ArgumentException(tune + " is packed at unit "
                            + header.Unit + ", the core serves unit "
                            + Word(core, CoreUnit));
                }
                string shape = YmxFormat.CheckShape(header.Ring, header.Chunk,
                        header.AnyUnit() ? 1 : header.Unit,
                        YmxFormat.LiveStreams(header.Flags));
                if (shape.Length != 0)
                {
                    throw new ArgumentException(tune + ": " + shape);
                }
                if (first == null)
                {
                    first = header;
                    rate = header.Hz;
                }
                else if (header.Hz != rate)
                {
                    throw new ArgumentException(tune + " plays at " + header.Hz
                            + " Hz, the set at " + rate
                            + " - one SNDH declares one rate");
                }
                n++;
                maxRing = Math.Max(maxRing, header.Ring);
                frms.Add(header.Frms());
                names.Add(SubtuneName(options, n, tune));
                try
                {
                    tunes.Add(File.ReadAllBytes(tune));
                }
                catch (IOException)
                {
                    throw Tools.Fail("mksndh: cannot read " + tune);
                }
            }
            if (first == null)
            {
                throw Tools.Fail("mksndh: no tunes");
            }

            byte[] file = Combine(core, tunes, Tags(options, rate, n, frms, names),
                    maxRing);
            string output = Path.GetFullPath(options.Output);
            try
            {
                File.WriteAllBytes(output, file);
            }
            catch (IOException)
            {
                throw Tools.Fail("mksndh: cannot write " + output);
            }
            Console.WriteLine(options.Output + ": " + file.Length + " bytes, "
                    + Tools.Plural(n, "subtune") + ", unit " + Word(core, CoreUnit)
                    + ", workspace for rings of " + maxRing);
            return new Result(output, n, first);
        }

        /// <summary>The whole file: the twelve-byte entry triple, the tag
        /// block, the core with its two offsets patched, the subtune table,
        /// the tunes and the workspace, every piece even-aligned. Each outer
        /// entry is bra.w to the same entry of the core's own triple, so all
        /// three displacements are the header's size minus 2.</summary>
        internal static byte[] Combine(byte[] core, List<byte[]> tunes,
                byte[] tags, int maxRing)
        {
            var file = new MemoryStream();
            int header = 12 + tags.Length;
            header += header & 1;               // the core starts even
            for (int entry = 0; entry < 3; entry++)
            {
                file.WriteByte(0x60);           // bra.w
                file.WriteByte(0x00);
                file.WriteByte((byte) ((header - 2) >> 8));
                file.WriteByte((byte) ((header - 2) & 0xFF));
            }
            file.Write(tags);
            if ((file.Length & 1) != 0)
            {
                file.WriteByte(0);
            }

            byte[] patched = (byte[]) core.Clone();
            int tableOff = core.Length + (core.Length & 1);
            int tableSize = 2 + 4 * tunes.Count;
            int at = tableOff + tableSize + (tableSize & 1);
            int[] offsets = new int[tunes.Count];
            for (int i = 0; i < tunes.Count; i++)
            {
                offsets[i] = at;
                at += tunes[i].Length;
                at += at & 1;
            }
            int workOff = at;
            PutLong(patched, CoreTableOff, tableOff);
            PutLong(patched, CoreWorkOff, workOff);
            file.Write(patched);

            Pad(file, header + tableOff);
            file.WriteByte((byte) (tunes.Count >> 8));
            file.WriteByte((byte) (tunes.Count & 0xFF));
            foreach (int offset in offsets)
            {
                file.WriteByte((byte) (offset >>> 24));
                file.WriteByte((byte) (offset >>> 16));
                file.WriteByte((byte) (offset >>> 8));
                file.WriteByte((byte) offset);
            }
            for (int i = 0; i < tunes.Count; i++)
            {
                Pad(file, header + offsets[i]);
                file.Write(tunes[i]);
            }
            Pad(file, header + workOff);
            int workspace = Word(core, CoreWorkFixed) + YmxFormat.Streams * maxRing;
            file.Write(new byte[workspace]);
            return file.ToArray();
        }

        /// <summary>Zero bytes up to a file position, one at most under
        /// these layouts.</summary>
        private static void Pad(MemoryStream file, int to)
        {
            while (file.Length < to)
            {
                file.WriteByte(0);
            }
        }

        /// <summary>The tag block, 'SNDH' through 'HDNS': the tags in the
        /// order the spec requires.</summary>
        internal static byte[] Tags(Options options, int rate, int n,
                List<int> frms, List<string> names)
        {
            var block = new MemoryStream();
            Text(block, "SNDH");
            Tag(block, "TITL", Clean(options.Title));
            if (!string.IsNullOrEmpty(options.Composer))
            {
                Tag(block, "COMM", Clean(options.Composer));
            }
            Tag(block, "CONV", "Converted from YM by YMX (ZX1 through ST4)");
            Tag(block, "##" + n.ToString("00"), "");
            Tag(block, "TC" + rate, "");
            Tag(block, "FLAG", "~ady");
            if ((block.Length & 1) != 0)
            {
                block.WriteByte(0);
            }
            Text(block, "FRMS");
            foreach (int frames in frms)
            {
                block.WriteByte((byte) (frames >>> 24));
                block.WriteByte((byte) (frames >>> 16));
                block.WriteByte((byte) (frames >>> 8));
                block.WriteByte((byte) frames);
            }
            // The subtune names: word offsets relative to the tag start, and
            // the reference parsers agree on the '!#SN' spelling.
            Text(block, "!#SN");
            int strings = 4 + 2 * n;
            int[] at = new int[n];
            for (int i = 0; i < n; i++)
            {
                at[i] = strings;
                strings += Clean(names[i]).Length + 1;
            }
            for (int i = 0; i < n; i++)
            {
                block.WriteByte((byte) (at[i] >> 8));
                block.WriteByte((byte) (at[i] & 0xFF));
            }
            for (int i = 0; i < n; i++)
            {
                Text(block, Clean(names[i]));
                block.WriteByte(0);
            }
            if ((block.Length & 1) != 0)
            {
                block.WriteByte(0);
            }
            Text(block, "HDNS");
            return block.ToArray();
        }

        /// <summary>One text tag: the four tag bytes, the value, a closing
        /// NUL.</summary>
        private static void Tag(MemoryStream block, string name, string value)
        {
            Text(block, name);
            Text(block, value);
            block.WriteByte(0);
        }

        private static void Text(MemoryStream block, string value)
        {
            block.Write(Encoding.Latin1.GetBytes(value));
        }

        /// <summary>The name file's nth line, or the tune's own stem.</summary>
        private static string SubtuneName(Options options, int n, string tune)
        {
            List<string>? given = options.Names;
            if (given != null && given.Count >= n)
            {
                return given[n - 1];
            }
            return Regex.Replace(Path.GetFileName(tune), "(?i)\\.ymx$", "");
        }

        /// <summary>Printable ASCII with the NUL-adjacent risks dropped:
        /// titles come out of YM headers, which carry anything at all.</summary>
        internal static string Clean(string text)
        {
            var kept = new StringBuilder();
            foreach (char c in text)
            {
                if (c >= 0x20 && c < 0x7F)
                {
                    kept.Append(c);
                }
            }
            return kept.ToString();
        }

        /// <summary>The core for these options, from dist/ beside the repo -
        /// assembled on the spot, once, when it is not there yet.</summary>
        internal static string ResolveCore(Options options)
        {
            string? named = Environment.GetEnvironmentVariable("YMX_CORE");
            if (named != null)
            {
                return named;
            }
            int unit = UnitOf(options.Tunes);
            string suffix = (options.Perf ? "-perf" : "")
                    + (options.MaskBurst ? "" : "-nomask");
            string core = Path.Combine(Tools.Repo(), "dist",
                    "ymxsndh-k" + unit + suffix + Tools.BinarySuffix()
                            + ".bin");
            if (Stale(core, "YMX_sndh.S", "YMX.S", "ST4_wrap.S"))
            {
                MkCores.Cores(Path.Combine(Tools.Repo(), "dist"), options.Perf,
                        !options.MaskBurst);
            }
            return core;
        }

        /// <summary>Whether a prebuilt binary is missing or older than a
        /// source it was assembled from, so the resolvers reassemble rather
        /// than combine against the repository's past.</summary>
        internal static bool Stale(string binary, params string[] sources)
        {
            try
            {
                if (!File.Exists(binary))
                {
                    return true;
                }
                DateTime built = File.GetLastWriteTimeUtc(binary);
                foreach (string source in sources)
                {
                    string path = Path.Combine(Tools.Repo(), "68k", source);
                    if (File.GetLastWriteTimeUtc(path) > built)
                    {
                        return true;
                    }
                }
                return false;
            }
            catch (IOException)
            {
                return true;
            }
        }

        private static int UnitOf(List<string> tunes)
        {
            foreach (string tune in tunes)
            {
                try
                {
                    YmxHeader header = YmxHeader.Read(tune);
                    if (!header.AnyUnit())
                    {
                        return header.Unit;
                    }
                }
                catch (IOException e)
                {
                    throw Tools.Fail("mksndh: " + e.Message);
                }
            }
            return 2;
        }

        /// <summary>The core, its descriptor checked against what the caller
        /// asked for.</summary>
        internal static byte[] ReadCore(string path, Options options)
        {
            byte[] core;
            try
            {
                core = File.ReadAllBytes(path);
            }
            catch (IOException)
            {
                throw Tools.Fail("mksndh: cannot read the core " + path);
            }
            if (core.Length < 34 || core[CoreMagic] != 'Y'
                    || core[CoreMagic + 1] != 'M' || core[CoreMagic + 2] != 'X'
                    || core[CoreMagic + 3] != 'C')
            {
                throw new ArgumentException(path + " is not an SNDH core");
            }
            if (Word(core, CoreVersion) != 1)
            {
                throw new ArgumentException(path + " is core descriptor version "
                        + Word(core, CoreVersion) + ", this tool writes 1");
            }
            if (Word(core, CoreFormat) != YmxFormat.Version)
            {
                throw new ArgumentException(path + " reads format version "
                        + YmxFormat.VersionName(Word(core, CoreFormat))
                        + " and the tunes carry " + YmxFormat.VersionName()
                        + " - reassemble it with ymx/mkcores.sh");
            }
            int flags = (options.Perf ? CoreFlagPerf : 0)
                    | (options.MaskBurst ? 0 : CoreFlagNomask);
            if (Word(core, CoreFlags) != flags)
            {
                throw new ArgumentException(path + " is built with flags "
                        + Word(core, CoreFlags) + ", the options ask for " + flags);
            }
            return core;
        }

        internal static int Word(byte[] bytes, int at)
        {
            return (bytes[at] << 8) | bytes[at + 1];
        }

        private static void PutLong(byte[] bytes, int at, int value)
        {
            bytes[at] = (byte) (value >>> 24);
            bytes[at + 1] = (byte) (value >>> 16);
            bytes[at + 2] = (byte) (value >>> 8);
            bytes[at + 3] = (byte) value;
        }

        private const string UsageText =
                "usage: mksndh.sh [-perf] [-nomask] [-tTitle] [-cComposer]"
                + " [-Nnamesfile] [-Pcorefile] output.sndh tune1.ymx"
                + " [tune2.ymx ...]";

        public static void Main(string[] args)
        {
            string? title = null;
            string? composer = null;
            List<string>? names = null;
            string? core = null;
            bool perf = false;
            bool maskBurst = true;
            int i = 0;
            for (; i < args.Length; i++)
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
                else if (a.StartsWith("-t"))
                {
                    title = a[2..];
                }
                else if (a.StartsWith("-c"))
                {
                    composer = a[2..];
                }
                else if (a.StartsWith("-N"))
                {
                    names = ReadNames(a[2..]);
                }
                else if (a.StartsWith("-P"))
                {
                    core = a[2..];
                }
                else
                {
                    break;
                }
            }
            if (args.Length - i < 2)
            {
                throw Tools.Fail(UsageText);
            }
            string output = args[i++];
            var tunes = new List<string>();
            for (; i < args.Length; i++)
            {
                tunes.Add(args[i]);
            }
            if (string.IsNullOrEmpty(title))
            {
                title = Regex.Replace(Path.GetFileName(output), "(?i)\\.sndh$", "");
            }
            Options options = Options.Of(output, tunes, title, composer, names,
                    perf, maskBurst);
            try
            {
                if (core != null)
                {
                    Build(options, core);
                }
                else
                {
                    Build(options);
                }
            }
            catch (ArgumentException e)
            {
                throw Tools.Fail("mksndh: " + e.Message);
            }
        }

        internal static List<string> ReadNames(string file)
        {
            try
            {
                return new List<string>(File.ReadAllLines(file, Encoding.Latin1));
            }
            catch (IOException)
            {
                throw Tools.Fail("mksndh: cannot read names from " + file);
            }
        }
    }
}
