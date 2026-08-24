using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace Ymx
{
    /// <summary>
    /// A runnable TOS program around an SNDH file, ported from org.ymx.MkPrg:
    /// a prebuilt, position-independent stub in front of the same bytes
    /// MkSndh writes, with a 28-byte PRG header before both and an empty
    /// relocation table behind. doc/BINARIES.md is the byte contract.
    /// </summary>
    public static class MkPrg
    {
        /// <summary>The stub descriptor: 'YMXP' at this offset, then
        /// version, subtunes and flags, words, with the frame count a long
        /// between them.</summary>
        public const int StubMagic = 4;
        public const int StubVersion = 8;
        public const int StubTunes = 10;
        public const int StubFrames = 12;
        public const int StubFlags = 16;

        /// <summary>Stub flag bit 0: drop YMXDONE.MRK on exit.</summary>
        public const int StubFlagMarker = 1;

        public sealed record Options(string Output, List<string> Tunes,
                string? Title, string? Composer, List<string>? Names,
                bool Perf, bool MaskBurst, bool Marker);

        public static string Build(Options options)
        {
            string output = Path.GetFullPath(options.Output);
            string work = Path.Combine(Tools.DirectoryOf(output), ".prg_work");
            Directory.CreateDirectory(work);

            string sndh;
            if (options.Tunes.Count == 1 && options.Tunes[0].ToLowerInvariant()
                    .EndsWith(".sndh"))
            {
                sndh = options.Tunes[0];
            }
            else
            {
                string? title = options.Title;
                if (string.IsNullOrEmpty(title))
                {
                    title = Regex.Replace(Path.GetFileName(output),
                            "(?i)\\.prg$", "");
                }
                sndh = Path.Combine(work, "tune.sndh");
                MkSndh.Build(MkSndh.Options.Of(sndh, options.Tunes, title,
                        options.Composer, options.Names, options.Perf,
                        options.MaskBurst));
            }

            byte[] file;
            try
            {
                file = File.ReadAllBytes(sndh);
            }
            catch (IOException)
            {
                throw Tools.Fail("mkprg: cannot read " + sndh);
            }
            int subtunes = Subtunes(file);
            byte[] prg = Wrap(ReadStub(ResolveStub()), file, subtunes,
                    subtunes == 1 ? Frames(file) : 0, options.Marker);
            try
            {
                File.WriteAllBytes(output, prg);
            }
            catch (IOException)
            {
                throw Tools.Fail("mkprg: cannot write " + output);
            }
            Console.WriteLine(options.Output + ": " + prg.Length + " bytes, "
                    + Tools.Plural(subtunes, "subtune"));
            return output;
        }

        /// <summary>The program: the PRG header, the stub with its
        /// descriptor patched, the SNDH file, and the empty relocation
        /// table - a zero long.</summary>
        internal static byte[] Wrap(byte[] stub, byte[] sndh, int subtunes,
                int frames, bool marker)
        {
            var program = new MemoryStream();
            int text = stub.Length + sndh.Length;
            program.WriteByte(0x60);            // PRG magic $601A
            program.WriteByte(0x1A);
            PutLong(program, text);             // text
            PutLong(program, 0);                // data
            PutLong(program, 0);                // bss: every buffer is in
            PutLong(program, 0);                // the stub's own bytes
            PutLong(program, 0);                // reserved
            PutLong(program, 0);                // flags
            program.WriteByte(0);               // absflag 0: the relocation
            program.WriteByte(0);               // table follows the text

            byte[] patched = (byte[]) stub.Clone();
            PutWord(patched, StubTunes, subtunes);
            PutLongIn(patched, StubFrames, frames);
            PutWord(patched, StubFlags, marker ? StubFlagMarker : 0);
            program.Write(patched);
            program.Write(sndh);
            PutLong(program, 0);                // no fixups
            return program.ToArray();
        }

        /// <summary>The '##' tag's two ASCII digits.</summary>
        internal static int Subtunes(byte[] sndh)
        {
            int at = Find(sndh, "##");
            return (sndh[at + 2] - '0') * 10 + (sndh[at + 3] - '0');
        }

        /// <summary>FRMS's first long: subtune 1's frame count, 0 when it
        /// plays on.</summary>
        internal static int Frames(byte[] sndh)
        {
            int at = Find(sndh, "FRMS") + 4;
            return (sndh[at] << 24) | (sndh[at + 1] << 16) | (sndh[at + 2] << 8)
                    | sndh[at + 3];
        }

        /// <summary>A tag's position inside the SNDH header, before 'HDNS'.</summary>
        private static int Find(byte[] sndh, string tag)
        {
            byte[] wanted = Encoding.Latin1.GetBytes(tag);
            for (int at = 12; at + wanted.Length < sndh.Length; at++)
            {
                if (sndh[at] == 'H' && sndh[at + 1] == 'D' && sndh[at + 2] == 'N'
                        && sndh[at + 3] == 'S')
                {
                    break;
                }
                bool hit = true;
                for (int i = 0; i < wanted.Length; i++)
                {
                    if (sndh[at + i] != wanted[i])
                    {
                        hit = false;
                        break;
                    }
                }
                if (hit)
                {
                    return at;
                }
            }
            throw Tools.Fail("mkprg: the SNDH header carries no " + tag + " tag");
        }

        /// <summary>The stub from dist/ - assembled on the spot, once, when
        /// missing.</summary>
        internal static string ResolveStub()
        {
            string? named = Environment.GetEnvironmentVariable("YMX_STUB");
            if (named != null)
            {
                return named;
            }
            string stub = Path.Combine(Tools.Repo(), "dist",
                    "ymxprg" + Tools.BinarySuffix() + ".bin");
            if (MkSndh.Stale(stub, "YMX_player.S"))
            {
                MkCores.Stub(Path.Combine(Tools.Repo(), "dist"));
            }
            return stub;
        }

        /// <summary>The stub, its descriptor checked.</summary>
        internal static byte[] ReadStub(string path)
        {
            byte[] stub;
            try
            {
                stub = File.ReadAllBytes(path);
            }
            catch (IOException)
            {
                throw Tools.Fail("mkprg: cannot read the stub " + path);
            }
            if (stub.Length < 18 || stub[StubMagic] != 'Y'
                    || stub[StubMagic + 1] != 'M' || stub[StubMagic + 2] != 'X'
                    || stub[StubMagic + 3] != 'P')
            {
                throw new ArgumentException(path + " is not a PRG stub");
            }
            if (MkSndh.Word(stub, StubVersion) != 1)
            {
                throw new ArgumentException(path + " is stub descriptor version "
                        + MkSndh.Word(stub, StubVersion) + ", this tool writes 1");
            }
            if ((stub.Length & 1) != 0)
            {
                throw new ArgumentException(path + " is odd-sized: the SNDH"
                        + " after it would load misaligned");
            }
            return stub;
        }

        private static void PutWord(byte[] bytes, int at, int value)
        {
            bytes[at] = (byte) (value >> 8);
            bytes[at + 1] = (byte) value;
        }

        private static void PutLongIn(byte[] bytes, int at, int value)
        {
            bytes[at] = (byte) (value >>> 24);
            bytes[at + 1] = (byte) (value >>> 16);
            bytes[at + 2] = (byte) (value >>> 8);
            bytes[at + 3] = (byte) value;
        }

        private static void PutLong(MemoryStream program, int value)
        {
            program.WriteByte((byte) (value >>> 24));
            program.WriteByte((byte) (value >>> 16));
            program.WriteByte((byte) (value >>> 8));
            program.WriteByte((byte) value);
        }

        private const string UsageText =
                "usage: mkprg.sh [-m] [-perf] [-nomask] [-tTitle] [-cComposer]"
                + " [-Nnamesfile] output.prg tunes.ymx...|set.sndh";

        public static void Main(string[] args)
        {
            bool marker = false;
            bool perf = false;
            bool maskBurst = true;
            string? title = null;
            string? composer = null;
            List<string>? names = null;
            int i = 0;
            for (; i < args.Length; i++)
            {
                string a = args[i];
                if (a == "-m")
                {
                    marker = true;
                }
                else if (a == "-perf")
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
                    names = MkSndh.ReadNames(a[2..]);
                }
                else
                {
                    break;
                }
            }
            if (i >= args.Length)
            {
                throw Tools.Fail(UsageText);
            }

            // Both argument orders: the .prg names the output wherever it
            // stands, so `mkprg.sh song.ymx SONG.PRG` keeps working.
            string output;
            var tunes = new List<string>();
            if (args[i].ToLowerInvariant().EndsWith(".prg"))
            {
                output = args[i++];
                for (; i < args.Length; i++)
                {
                    tunes.Add(args[i]);
                }
            }
            else if (args.Length - i == 2)
            {
                tunes.Add(args[i]);
                output = args[i + 1];
            }
            else
            {
                throw Tools.Fail(UsageText);
            }
            if (tunes.Count == 0)
            {
                throw Tools.Fail(UsageText);
            }
            try
            {
                Build(new Options(output, tunes, title, composer, names, perf,
                        maskBurst, marker));
            }
            catch (ArgumentException e)
            {
                throw Tools.Fail("mkprg: " + e.Message);
            }
        }
    }
}
