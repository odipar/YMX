using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;
using Ymx;

namespace Ymr
{
    /// <summary>
    /// Test drive a RhYMe tune, ported from org.ymr.YmrPlay: Play with the
    /// .ym step replaced - the same flags mean the same things, the work
    /// directory is named the same way, and SPACE in the emulator window
    /// still ends the run. A .YMR carries no metadata, so a set is titled
    /// and its subtunes named from the file stems, and the composer is left
    /// absent rather than invented.
    /// </summary>
    public static class YmrPlay
    {
        private const string UsageText =
                "ymr.sh - test drive a .YMR tune: pack it, build a player, run"
                + " it under Hatari.\n"
                + "  ymr/ymr.sh [-perf] [-nomask] [-nRING] [-cCHUNK] [-kUNIT]"
                + " [-o] [trim flags] song.ymr...\n"
                + "Press SPACE in the Hatari window to stop. Everything it"
                + " builds lands in a work\ndirectory next to the first tune."
                + " HATARI= and TOS= point at your own install.";

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
                    Console.WriteLine(UsageText);
                    return;
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
                    unit = "-k" + a[2..];
                }
                else
                {
                    extra.Add(a);       // the packer's: the trim window, and
                }                       // whatever it reads next
            }
            var ymrs = new List<string>();
            for (; i < args.Length; i++)
            {
                ymrs.Add(args[i]);
            }
            if (ymrs.Count == 0)
            {
                throw Tools.Fail("usage: ymr.sh [-perf] [-nomask] [-nRING]"
                        + " [-cCHUNK] [-kUNIT] [-o] song.ymr...");
            }
            foreach (string ymr in ymrs)
            {
                if (!File.Exists(ymr))
                {
                    throw Tools.Fail("ymr.sh: no such file: " + ymr);
                }
            }
            string hatari = Ym6.Emulator.Env("HATARI", "hatari");
            string tos = Ym6.Emulator.Env("TOS",
                    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
                    + "/hatari-2.6.1_macos/tos-2.06.rom");
            if (!File.Exists(tos))
            {
                throw Tools.Fail("ymr.sh: no TOS image at " + tos
                        + " - set TOS=/path/to/tos.img");
            }
            CheckOneRate(ymrs);

            string first = Path.GetFullPath(ymrs[0]);
            string name = Stem(first)
                    + (ymrs.Count > 1 ? "+" + (ymrs.Count - 1) : "")
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

            var names = new List<string>();
            foreach (string ymr in ymrs)
            {
                names.Add(Stem(ymr));
            }
            Console.WriteLine("ymr.sh: packing " + string.Join(" ", ymrs));
            List<string> packed = Pack(ymrs, work, flags);
            // No composer: a .YMR has no author field, and MkPrg takes a
            // nullable one precisely so a converter with nothing to say can
            // say nothing rather than stamp the SNDH with a guess.
            MkPrg.Build(new MkPrg.Options(Path.Combine(work, "PLAY.PRG"), packed,
                    string.Join(" / ", names), null, names, perf, maskBurst, true));

            string marker = Path.Combine(work, "YMXDONE.MRK");
            File.Delete(marker);
            Console.WriteLine("ymr.sh: starting Hatari - press SPACE in its"
                    + " window to stop");
            Ym6.Emulator.Run("ymr.sh", hatari, tos,
                    Path.Combine(work, "PLAY.PRG"), marker);
            File.Delete(marker);
            Console.WriteLine("ymr.sh: stopped. The tune and the program are"
                    + " in " + work);
        }

        /// <summary>Every input through the .ymr packer with one
        /// configuration, into one directory. The packer's per-stream table
        /// is left where it lands: a test drive is the one moment you want
        /// it.</summary>
        private static List<string> Pack(List<string> ymrs, string work,
                List<string> flags)
        {
            Directory.CreateDirectory(work);
            var argv = new List<string> {"-f"};
            argv.AddRange(flags);
            var packed = new List<string>();
            foreach (string ymr in ymrs)
            {
                packed.Add(Path.Combine(work, Stem(ymr) + ".ymx"));
            }
            if (ymrs.Count == 1)
            {
                argv.Add(ymrs[0]);
                argv.Add(packed[0]);
            }
            else
            {
                argv.AddRange(ymrs);
                argv.Add(work);         // the trailing directory: a set
            }
            YmrCli.Main(argv.ToArray());
            return packed;
        }

        /// <summary>The rate every tune in a set must share; a lone tune is
        /// not asked, since answering costs a full read of the dump.</summary>
        private static void CheckOneRate(List<string> ymrs)
        {
            if (ymrs.Count < 2)
            {
                return;
            }
            int set = 0;
            foreach (string ymr in ymrs)
            {
                int rate = Read(ymr).FrameRate;
                if (set == 0)
                {
                    set = rate;
                }
                else if (rate != set)
                {
                    throw Tools.Fail("ymr.sh: " + ymr + " plays at " + rate
                            + " Hz, the set at " + set + " Hz - one player build"
                            + " is driven at one rate");
                }
            }
        }

        private static YmrReader.Song Read(string ymr)
        {
            try
            {
                return YmrReader.Read(File.ReadAllBytes(ymr));
            }
            catch (Exception e) when (e is IOException
                    || e is YmrReader.FormatException)
            {
                throw Tools.Fail(ymr + ": " + e.Message);
            }
        }

        /// <summary>The file's own name, the only name a .YMR has.</summary>
        private static string Stem(string ymr)
        {
            return Regex.Replace(Path.GetFileName(ymr), "(?i)\\.ymr$", "");
        }

        private static int Number(string text)
        {
            if (!int.TryParse(text, out int value))
            {
                throw Tools.Fail("ymr.sh: not a number: " + text);
            }
            return value;
        }
    }
}
