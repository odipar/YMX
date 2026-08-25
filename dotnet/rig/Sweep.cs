using System;
using System.Collections.Generic;
using System.IO;

namespace Rig
{
    /// <summary>
    /// Corpus sweep, ported from the Java rig's Sweep: pack each .ym tune
    /// and verify the player's chip writes against the YM truth, frame by
    /// frame, in the emulator rig. The effect-owned registers are checked
    /// against an INDEPENDENT model of the script semantics, recomputed from
    /// the YM data alone. One status line per tune: OK, ISSUE, PACKFAIL or
    /// SKIP.
    /// </summary>
    public static class Sweep
    {
        private static readonly int[] Mask = {0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F,
                0x1F, 0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0xFF};
        private static readonly int[] Prediv = {0, 4, 10, 16, 50, 64, 100, 200};
        private const int MfpClock = 2457600;
        private const int MaxHz = 25600;

        /// <summary>The YM registers and drum lengths, via the C# reader.</summary>
        public sealed record Dump(int Format, int Frames, int Drums, int Hz,
                byte[][] Registers, int[] Lengths);

        public static Dump ReadYm(string path)
        {
            byte[] source = File.ReadAllBytes(path);
            Ym6.Ym6Reader.Song song;
            try
            {
                song = Ym6.Ym6Reader.Read(source);
            }
            catch (Ym6.Ym6Reader.FormatException e)
            {
                throw new IOException(e.Message);
            }
            int[] lengths = new int[song.Drums.Length];
            for (int i = 0; i < lengths.Length; i++)
            {
                lengths[i] = song.Drums[i].Length;
            }
            return new Dump(song.Format.StartsWith("YM6") ? 6 : 5, song.Frames,
                    lengths.Length, song.PlayerHz, song.Registers, lengths);
        }

        /// <summary>The two slots' (code, prescaler, count) for source frame
        /// f, both dialects, exactly as the packer normalizes them.</summary>
        public static int[][] SlotCodes(Dump dump, int f)
        {
            byte[][] regs = dump.Registers;
            if (dump.Format == 6)
            {
                return new[] {
                        new[] {regs[1][f] & 0xF0, (regs[6][f] >> 5) & 7, regs[14][f]},
                        new[] {regs[3][f] & 0xF0, (regs[8][f] >> 5) & 7, regs[15][f]}};
            }
            int second = (regs[3][f] & 0x30) != 0 ? 0x40 | (regs[3][f] & 0x30) : 0;
            return new[] {
                    new[] {regs[1][f] & 0x30, (regs[6][f] >> 5) & 7, regs[14][f]},
                    new[] {second, (regs[8][f] >> 5) & 7, regs[15][f]}};
        }

        /// <summary>The packer's drop rules: the effective divisor, or 0
        /// when the slot is idle this frame.</summary>
        public static long Validate(int code, int tp, int tc, Dump dump, int f,
                long[][] scale)
        {
            int v = (code >> 4) & 3;
            if (v == 0 || tp == 0 || tc == 0)
            {
                return 0;
            }
            int kind = code & 0xC0;
            if (kind == 0x80)
            {
                return 0;               // sinus: never packs
            }
            if (kind == 0x40)
            {
                int n = dump.Registers[8 + v - 1][f] & 0x1F;
                if (n >= dump.Drums)
                {
                    return 0;           // missing drum
                }
                return Prediv[tp] * tc * scale[n][0] / scale[n][1];
            }
            if (MfpClock / (Prediv[tp] * tc) > MaxHz)
            {
                return 0;               // too-fast SID or buzzer
            }
            return (long) Prediv[tp] * tc;
        }

        private static bool Representable(long divisor)
        {
            for (int p = 1; p < 8; p++)
            {
                if (divisor % Prediv[p] == 0 && divisor / Prediv[p] >= 1
                        && divisor / Prediv[p] <= 255)
                {
                    return true;
                }
            }
            return false;
        }

        /// <summary>The smallest representable divisor at or under the rate
        /// ceiling.</summary>
        private static long CeilingDivisor()
        {
            long needed = (MfpClock + MaxHz - 1) / MaxHz;
            long best = long.MaxValue;
            for (int p = 1; p < 8; p++)
            {
                long count = (needed + Prediv[p] - 1) / Prediv[p];
                if (count <= 255)
                {
                    best = Math.Min(best, Prediv[p] * count);
                }
            }
            return best;
        }

        /// <summary>Each drum's divisor scale num/den, mirroring the packer:
        /// resample to the highest representable rate under the ceiling when
        /// every trigger takes the exact ratio, the power-of-two factor
        /// otherwise.</summary>
        public static long[][] DrumScales(Dump dump)
        {
            var seen = new List<HashSet<long>>();
            for (int n = 0; n < dump.Drums; n++)
            {
                seen.Add(new HashSet<long>());
            }
            for (int f = 0; f < dump.Frames; f++)
            {
                foreach (int[] slot in SlotCodes(dump, f))
                {
                    int code = slot[0];
                    int tp = slot[1];
                    int tc = slot[2];
                    if ((code & 0xC0) != 0x40 || (code & 0x30) == 0
                            || tp == 0 || tc == 0)
                    {
                        continue;
                    }
                    int n = dump.Registers[8 + ((code >> 4) & 3) - 1][f] & 0x1F;
                    if (n < dump.Drums)
                    {
                        seen[n].Add((long) Prediv[tp] * tc);
                    }
                }
            }
            long[][] scale = new long[dump.Drums][];
            for (int n = 0; n < dump.Drums; n++)
            {
                scale[n] = new long[] {1, 1};
                if (seen[n].Count == 0)
                {
                    continue;
                }
                long fastest = long.MaxValue;
                foreach (long divisor in seen[n])
                {
                    fastest = Math.Min(fastest, divisor);
                }
                if (MaxHz * fastest >= MfpClock)
                {
                    continue;
                }
                long target = CeilingDivisor();
                long g = Gcd(target, fastest);
                long num = target / g;
                long den = fastest / g;
                bool exact = true;
                foreach (long d in seen[n])
                {
                    if (d * num % den != 0 || !Representable(d * num / den))
                    {
                        exact = false;
                        break;
                    }
                }
                if (exact)
                {
                    scale[n] = new[] {num, den};
                }
                else
                {
                    long factor = 1;
                    while (MaxHz * fastest * factor < MfpClock && factor < 64)
                    {
                        factor *= 2;
                    }
                    scale[n] = new[] {factor, 1};
                }
            }
            return scale;
        }

        private static long Gcd(long a, long b)
        {
            return b == 0 ? a : Gcd(b, a % b);
        }

        /// <summary>An independent replay of the script semantics: which
        /// voices are skipped and which drums force the mixer, per played
        /// frame - the same decision rules as the packer's simulator,
        /// written a second time so the two implementations check each other
        /// through the player in between.</summary>
        public sealed class Model
        {
            private readonly Dump dump;
            private readonly long[][] scale;
            private readonly int[] lengths;
            private readonly int[] elast = {0, 0};
            private readonly int[] owner = {-1, -1, -1};
            private readonly int[] left = {0, 0, 0};
            private int skipped;
            private int silenced;

            public Model(Dump dump)
            {
                this.dump = dump;
                scale = DrumScales(dump);
                lengths = new int[dump.Drums];
                for (int n = 0; n < dump.Drums; n++)
                {
                    lengths[n] = n < dump.Lengths.Length
                            ? (int) Math.Max(1,
                                    dump.Lengths[n] * scale[n][1] / scale[n][0])
                            : 1;
                }
            }

            /// <summary>The tune starts over, so nothing is running from its
            /// end.</summary>
            public void Restart()
            {
                elast[0] = 0;
                elast[1] = 0;
                for (int v = 0; v < 3; v++)
                {
                    owner[v] = -1;
                    left[v] = 0;
                }
                skipped = 0;
                silenced = 0;
            }

            /// <summary>Advances one played frame showing source frame f;
            /// returns {skipped, forced, silenced} voice masks.</summary>
            public int[] Step(int f)
            {
                silenced = 0;
                for (int v = 0; v < 3; v++)
                {
                    if (owner[v] >= 0 && left[v] > 0)
                    {
                        left[v]--;
                        if (left[v] == 0)
                        {
                            owner[v] = -1;
                            skipped &= ~(1 << v);
                        }
                    }
                }
                for (int slot = 0; slot < 2; slot++)
                {
                    int[] codes = SlotCodes(dump, f)[slot];
                    long divisor = Validate(codes[0], codes[1], codes[2], dump,
                            f, scale);
                    int code = divisor == 0 ? 0 : codes[0];
                    if (code == elast[slot])
                    {
                        if (code != 0 && (code & 0xC0) == 0x40)
                        {
                            Drum(slot, code, divisor, f);   // retrigger
                        }
                        continue;
                    }
                    int old = elast[slot];
                    elast[slot] = code;
                    if (code == 0)
                    {
                        if ((old & 0xC0) == 0x00 && old != 0)
                        {
                            skipped &= ~(1 << (((old >> 4) & 3) - 1));
                        }
                        if ((old & 0xC0) != 0x40)
                        {
                            Cut(slot, -1);
                        }
                        continue;
                    }
                    int v2 = ((code >> 4) & 3) - 1;
                    int kind = code & 0xC0;
                    if (kind == 0x00)
                    {                                       // SID
                        if (owner[v2] >= 0)
                        {
                            elast[slot] = 0;                // suppressed
                            if ((old & 0xC0) == 0x00 && old != 0)
                            {
                                skipped &= ~(1 << (((old >> 4) & 3) - 1));
                            }
                            continue;
                        }
                        if ((old & 0xC0) == 0x00 && old != 0)
                        {
                            skipped &= ~(1 << (((old >> 4) & 3) - 1));
                        }
                        Cut(slot, -1);
                        skipped |= 1 << v2;
                        silenced |= 1 << v2;    // SID_START silences first
                    }
                    else if (kind == 0x40)
                    {                                       // drum
                        if ((old & 0xC0) == 0x00 && old != 0)
                        {
                            skipped &= ~(1 << (((old >> 4) & 3) - 1));
                        }
                        else if ((old & 0xC0) == 0x40 && old != 0
                                && ((old ^ code) & 0x30) != 0)
                        {
                            int o = ((old >> 4) & 3) - 1;
                            if (owner[o] == slot)
                            {
                                owner[o] = -1;              // orphan cleanup
                                left[o] = 0;
                                skipped &= ~(1 << o);
                            }
                        }
                        int other = elast[1 - slot];
                        if ((other & 0xC0) == 0x00 && other != 0
                                && ((other >> 4) & 3) - 1 == v2)
                        {
                            elast[1 - slot] = 0;            // arbitration
                        }
                        Cut(slot, v2);
                        Drum(slot, code, divisor, f);
                    }
                    else
                    {                                       // buzzer
                        Cut(slot, -1);
                    }
                }
                int forced = 0;
                for (int v = 0; v < 3; v++)
                {
                    if (owner[v] >= 0)
                    {
                        forced |= 1 << v;
                    }
                }
                return new[] {skipped, forced, silenced};
            }

            private void Drum(int slot, int code, long divisor, int f)
            {
                int v = ((code >> 4) & 3) - 1;
                int n = dump.Registers[8 + v][f] & 0x1F;
                long ticks = lengths[n] + 1;
                // the packer's duration(): a sixteenth of a frame covers the
                // arming phase
                long scaled = ticks * divisor * dump.Hz + MfpClock / 16;
                int frames = (int) ((scaled + MfpClock - 1) / MfpClock);
                owner[v] = slot;
                left[v] = frames;
                skipped |= 1 << v;
            }

            /// <summary>A program on this slot's timer: a drum it still owes
            /// ticks to is cut, its marker never runs, its voice stays
            /// skipped.</summary>
            private void Cut(int slot, int skip)
            {
                for (int v = 0; v < 3; v++)
                {
                    if (v != skip && owner[v] == slot && left[v] > 0)
                    {
                        left[v] = -1;                       // stuck
                    }
                }
            }
        }

        /// <summary>Extra packer options, for a shape the corpus never asks
        /// for: YMX_PACK_OPTIONS='-timersBC' ymx/test/sweep.sh -dotnet
        /// one.ym.</summary>
        private static string[] PackOptions()
        {
            string? options = Environment.GetEnvironmentVariable("YMX_PACK_OPTIONS");
            return string.IsNullOrWhiteSpace(options) ? new string[0]
                    : options.Trim().Split((char[]?) null,
                            StringSplitOptions.RemoveEmptyEntries);
        }

        public static string SweepOne(string path)
        {
            string name = Path.GetFileName(path);
            Dump dump;
            try
            {
                dump = ReadYm(path);
            }
            catch (IOException e)
            {
                return "SKIP " + name + ": " + e.Message;
            }
            string ymx = Path.Combine(Path.GetTempPath(),
                    "sweep" + Environment.ProcessId + ".ymx");
            try
            {
                List<string> command = Rig.OwnTool("ymx");
                command.AddRange(new[] {"-f", "-k1"});
                command.AddRange(PackOptions());
                command.Add(Path.GetFullPath(path));
                command.Add(ymx);
                Rig.Finished packed = Rig.TryRun(command);
                if (packed.ExitCode != 0)
                {
                    string[] lines = packed.Output.Trim().Split('\n');
                    return "PACKFAIL " + name + ": " + lines[^1];
                }
                var warns = new List<string>();
                foreach (string line in packed.Output.Split('\n'))
                {
                    if (line.Contains("Warning") || line.Contains("Padded"))
                    {
                        warns.Add(line.Trim());
                    }
                }
                return Play(name, dump, File.ReadAllBytes(ymx), warns);
            }
            catch (Exception e) when (e is IOException
                    || e is InvalidOperationException)
            {
                return "ISSUE " + name + ": " + e.Message;
            }
            finally
            {
                File.Delete(ymx);
            }
        }

        private static string Play(string name, Dump dump, byte[] packed,
                List<string> warns)
        {
            int ring = (packed[16] << 8) | packed[17];
            int headerFrames = (packed[8] << 24) | (packed[9] << 16)
                    | (packed[10] << 8) | packed[11];
            bool loops = (packed[7] & 1) != 0;
            int loopFrame = (int) ((long) (packed[30] & 0xFF) << 24
                    | (packed[31] & 0xFF) << 16 | (packed[32] & 0xFF) << 8
                    | (packed[33] & 0xFF));
            var player = new Player(packed);
            if (player.Init() != 0)
            {
                return "INITFAIL " + name;
            }
            var model = new Model(dump);
            int[] strict = {0, 1, 2, 3, 4, 5, 6, 11, 12};
            int budget = dump.Frames <= 3000 ? dump.Frames + 200 : 1200;
            bool wrapped = false;
            int played = 0;
            for (int f = 0; f < budget; f++)
            {
                // The first pass plays every frame; each pass after it
                // plays from the loop frame, which is 0 where the file
                // gives none.
                int body = headerFrames - loopFrame;
                int past = played - headerFrames;
                int src = played < headerFrames ? played
                        : loopFrame + past % body;
                if (played != 0 && src == loopFrame && past % body == 0)
                {
                    model.Restart();    // the player silenced everything
                }
                Player.Frame frame = player.PlayFrame();
                if (frame.Result == -1)
                {
                    if (f < dump.Frames)
                    {
                        return "ISSUE " + name + ": ended early at frame " + f
                                + "/" + dump.Frames;
                    }
                    break;
                }
                if (frame.Result == 1)
                {
                    wrapped = true;
                }
                int[] masks = model.Step(src);
                int skipped = masks[0];
                int forced = masks[1];
                int silenced = masks[2];
                var got = new Dictionary<int, int>();
                foreach (Player.Pair pair in frame.Writes)
                {
                    got[pair.Register] = pair.Value;
                }
                foreach (int r in strict)
                {
                    int want = dump.Registers[r][src] & Mask[r];
                    if (r == 7)
                    {
                        want |= forced | forced << 3;
                    }
                    if (!got.TryGetValue(r, out int value) || value != want)
                    {
                        return "ISSUE " + name + ": frame " + f + " R" + r
                                + " wrote "
                                + (got.ContainsKey(r) ? value.ToString() : "nothing")
                                + " want " + want;
                    }
                }
                for (int v = 0; v < 3; v++)
                {
                    int r = 8 + v;
                    bool wroteIt = got.TryGetValue(r, out int value);
                    if ((silenced & (1 << v)) != 0)
                    {
                        // the SID start's own silence write, then the loud
                        // half
                        if (!wroteIt || value != 0)
                        {
                            return "ISSUE " + name + ": frame " + f
                                    + " started a SID on voice " + "ABC"[v]
                                    + " without silencing R" + r + " (wrote "
                                    + (wroteIt ? value.ToString() : "nothing")
                                    + ")";
                        }
                    }
                    else if ((skipped & (1 << v)) != 0)
                    {
                        if (wroteIt)
                        {
                            return "ISSUE " + name + ": frame " + f + " wrote R"
                                    + r + " while it was skipped";
                        }
                    }
                    else
                    {
                        int want = dump.Registers[r][src] & Mask[r];
                        if (!wroteIt || value != want)
                        {
                            return "ISSUE " + name + ": frame " + f + " R" + r
                                    + " wrote "
                                    + (wroteIt ? value.ToString() : "nothing")
                                    + " want " + want;
                        }
                    }
                }
                int r13 = dump.Registers[13][src];
                bool wroteShape = got.TryGetValue(13, out int shape);
                if (r13 == 0xFF && wroteShape)
                {
                    return "ISSUE " + name + ": frame " + f + " wrote held R13";
                }
                if (r13 != 0xFF && (!wroteShape || shape != (r13 & 0x0F)))
                {
                    return "ISSUE " + name + ": frame " + f + " R13 "
                            + (wroteShape ? shape.ToString() : "nothing")
                            + " want " + (r13 & 0x0F);
                }
                played++;
                if (!loops && played == dump.Frames)
                {
                    break;
                }
            }
            string loop = wrapped ? "started over"
                    : dump.Frames > 3000 ? "partial" : "once";
            string extra = warns.Count == 0 ? ""
                    : " [" + string.Join("; ", warns) + "]";
            return "OK " + name + " (" + Math.Min(budget, dump.Frames + 200)
                    + "f " + loop + ")" + extra;
        }

        public static void Main(string[] args)
        {
            int failed = 0;
            foreach (string tune in args)
            {
                string line = SweepOne(tune);
                if (line.StartsWith("ISSUE") || line.StartsWith("PACKFAIL")
                        || line.StartsWith("INITFAIL"))
                {
                    failed = 1;
                }
                Console.WriteLine(line);
            }
            Environment.Exit(failed);
        }
    }
}
