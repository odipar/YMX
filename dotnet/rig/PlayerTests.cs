using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;
using Ymx;

namespace Rig
{
    /// <summary>
    /// Differential tests for the YMX player, ported from the Java rig's
    /// PlayerTests: most checks pack a tune with the real packer - one
    /// builds its file by hand - assemble YMX.S with the decoder, run the
    /// player under emulation as a plain 68000, and compare every chip
    /// write against what GenYm computes independently.
    /// `ymx/test/rig.sh -dotnet [--quick]` runs the battery; each check
    /// returns a problem line, or the empty string.
    /// </summary>
    public static class PlayerTests
    {
        public const ulong Tacr = 0xFFFFFA19;
        public const ulong Tadr = 0xFFFFFA1F;
        public const ulong Tcdcr = 0xFFFFFA1D;
        public const ulong Tddr = 0xFFFFFA25;

        /// <summary>Feeds captured writes to a model of the chip; reports
        /// R13 writes - the write restarts the envelope, so it is an event
        /// in its own right.</summary>
        public static bool ApplyWrites(int[] state, List<Player.Pair> writes)
        {
            bool envelopeWritten = false;
            foreach (Player.Pair write in writes)
            {
                if (write.Register >= GenYm.PlayRegisters)
                {
                    throw new InvalidOperationException("wrote R" + write.Register
                            + ", which is an I/O port");
                }
                if (write.Register == 13)
                {
                    envelopeWritten = true;
                }
                state[write.Register] = write.Value;
            }
            return envelopeWritten;
        }

        /// <summary>Plays a whole tune (and passes passes more) and checks
        /// it.</summary>
        public static string RunShape(int frames, int ring, int chunk,
                string label, bool loops, int passes, int unit)
        {
            return RunShape(frames, ring, chunk, label, loops, passes, unit, 0);
        }

        /// <summary>The same, for a tune whose header sends its own player back
        /// to loopFrame. A pass after the first is the frames from there on, so
        /// the played length counts the whole tune once and a body each time
        /// after that. The ring the workspace is sized for is the packed file's
        /// own: a body that does not fit the ring asked for is packed with a
        /// bigger one.</summary>
        public static string RunShape(int frames, int ring, int chunk,
                string label, bool loops, int passes, int unit, int loopFrame)
        {
            byte[][] source = GenYm.Registers(frames);
            byte[] packed = Rig.Pack(GenYm.Ym6File(frames, loopFrame, source),
                    ring, chunk, loops, unit);
            int carried = Header(packed, YmxLoopFrame, 4);
            if (carried != (loops ? loopFrame : 0))
            {
                return label + ": the file carries L=" + carried + ", not "
                        + loopFrame;
            }
            int played = loops ? frames + passes * (frames - carried) : frames;
            List<GenYm.ChipState> expected = GenYm.ChipStates(frames, source,
                    loops, carried, played);

            var player = new Player(packed, unit);
            if (player.Init() != 0)
            {
                return label + ": YMX_init rejected the file";
            }

            int[] state = new int[GenYm.PlayRegisters];
            int position = 0;                   // where in the tune we are
            for (int index = 0; index < played; index++)
            {
                Player.Frame frame = player.PlayFrame();
                bool envelope = ApplyWrites(state, frame.Writes);
                GenYm.ChipState wanted = expected[index];
                if (!SameState(state, wanted.Registers))
                {
                    var differs = new System.Text.StringBuilder();
                    for (int r = 0; r < GenYm.PlayRegisters; r++)
                    {
                        if (state[r] != wanted.Registers[r])
                        {
                            differs.Append(differs.Length == 0 ? "" : ", ")
                                    .Append(string.Format(
                                            "R{0}=0x{1:x2} want 0x{2:x2}",
                                            r, state[r], wanted.Registers[r]));
                        }
                    }
                    return label + ": after frame " + index + " the chip has "
                            + differs;
                }
                if (envelope != wanted.EnvelopeWritten)
                {
                    return label + ": frame " + index + " "
                            + (envelope ? "wrote" : "skipped")
                            + " R13, expected the other";
                }
                position++;
                // d0 = 1 means "that frame ended the tune, the next one is
                // frame L again". A tune that plays once never reports it:
                // it reports -1 on the call after its last frame instead.
                bool wrapped = position >= frames && loops;
                if (wrapped)
                {
                    position = carried;
                }
                if (frame.Result != (wrapped ? 1 : 0))
                {
                    return label + ": frame " + index + " returned "
                            + frame.Result + ", expected " + (wrapped ? 1 : 0);
                }
            }

            if (!loops)
            {
                Player.Frame past = player.PlayFrame();
                if (past.Result != -1 || past.Writes.Count != 0)
                {
                    return label + ": past the end it wrote " + past.Writes.Count
                            + " pairs and returned " + past.Result;
                }
            }

            // Re-initialising is the whole reset: the second pass must be
            // identical.
            if (player.Init() != 0)
            {
                return label + ": re-init rejected the file";
            }
            state = new int[GenYm.PlayRegisters];
            for (int index = 0; index < Math.Min(played, 3 * chunk); index++)
            {
                ApplyWrites(state, player.PlayFrame().Writes);
                if (!SameState(state, expected[index].Registers))
                {
                    return label + ": frame " + index + " differs after re-init";
                }
            }
            return "";
        }

        private static bool SameState(int[] left, int[] right)
        {
            for (int i = 0; i < left.Length; i++)
            {
                if (left[i] != right[i])
                {
                    return false;
                }
            }
            return true;
        }

        /// <summary>Header offsets the battery reads back out of a packed
        /// file.</summary>
        public const int YmxLoopFrame = 30;

        /// <summary>One big-endian header field of a packed file.</summary>
        public static int Header(byte[] packed, int at, int size)
        {
            int value = 0;
            for (int byteAt = 0; byteAt < size; byteAt++)
            {
                value = (value << 8) | packed[at + byteAt];
            }
            return value;
        }

        /// <summary>The sample table's offset, straight from the packed
        /// file's header.</summary>
        public static long DrumTable(Player player)
        {
            return player.Uc.Value(player.File + 24, 4);
        }

        /// <summary>Runs one tick handler to its rte, which this emulator
        /// build cannot execute - reaching it is the completed tick. Returns
        /// the chip writes.</summary>
        public static List<Player.Pair> InvokeIsr(Player player, ulong address)
        {
            ulong stack = Rig.StackTop - 512;
            player.Writes.Clear();
            player.Uc.Set(Unicorn.SR, 0x2600);
            player.Uc.Set(Unicorn.A7, (long) stack);
            player.Uc.Start(address, Rig.Magic, 1_000);
            ulong pc = (ulong) player.Uc.Register(Unicorn.PC);
            if (player.Uc.Value(pc, 2) != 0x4E73)
            {
                throw new InvalidOperationException("the tick handler faulted at "
                        + pc.ToString("x"));
            }
            return player.DecodeWrites();
        }

        /// <summary>The byte the retrigger tick will write to R13, out of
        /// the running player's own code: a self-modified immediate inside
        /// the tick block.</summary>
        public static int PatchedShape(Player player, string timer)
        {
            return (int) player.Uc.Value(
                    Rig.Code + (ulong) player.Symbol("ymx_retrigger_" + timer) + 4,
                    1);
        }



        /// <summary>The loop word is unsigned - a point of $8000 through
        /// $FFFE is legal in any sample long enough to hold it - and init
        /// resolves it to an absolute address. A sign-extended resolve lands
        /// 65536 bytes low, so the proof is the resolved long against the
        /// arithmetic done by hand. The rig's packed tunes carry short
        /// samples, so the file is built here, stored sections alone.</summary>
        public static string RunLoopPointResolve()
        {
            int loop = 0x8084;
            byte[] packed = StoredYmx(4, loop + 96, loop);
            var player = new Player(packed);
            if (player.Init() != 0)
            {
                return "loop resolve: YMX_init rejected the tune";
            }
            long table = DrumTable(player);
            long offset = player.Uc.Value(player.File + (ulong) table, 4);
            long resolved = player.Uc.Value(
                    Rig.Code + (ulong) player.Symbol("ymx_samples") + 4, 4);
            ulong want = player.File + (ulong) offset + (ulong) loop;
            if ((ulong) resolved != want)
            {
                return "loop resolve: loop point $" + loop.ToString("x")
                        + " resolved to $" + resolved.ToString("x")
                        + ", want $" + want.ToString("x");
            }
            return "";
        }

        /// <summary>A .ymx of stored sections, built to SPEC.md's header and
        /// table layout, with one looped sample of the given length.</summary>
        private static byte[] StoredYmx(int frames, int length, int loop)
        {
            var body = new MemoryStream();
            int[] where = new int[Rig.Streams];
            int at = Ymx.YmxFormat.HeaderSize;
            for (int stream = 0; stream < Rig.Streams; stream++)
            {
                while (at % 4 != 0)
                {
                    body.WriteByte(0);
                    at++;
                }
                where[stream] = at;
                for (int frame = 0; frame < frames; frame++)
                {
                    body.WriteByte(stream == 16 ? (byte) 0xE4 : (byte) 0);
                }                           // T: 0->A 1->B 2->C 3->D
                at += frames;
            }
            while (at % 4 != 0)
            {
                body.WriteByte(0);
                at++;
            }
            int table = at;
            var outStream = new MemoryStream();
            outStream.Write(System.Text.Encoding.ASCII.GetBytes("YMX!"));
            Word(outStream, Ymx.YmxFormat.Version);
            Word(outStream, 1);             // flags: starts over
            LongWord(outStream, frames);
            Word(outStream, 50);
            Word(outStream, Rig.Streams);
            Word(outStream, 960);           // ring
            Word(outStream, 24);            // chunk
            LongWord(outStream, 2000000);
            LongWord(outStream, table);
            Word(outStream, 1);             // one sample
            LongWord(outStream, 0);         // L: back to the beginning
            LongWord(outStream, 0);         // no loop table
            LongWord(outStream, Ymx.YmxFormat.RequiredBase);  // Q: no extension
            for (int stream = 0; stream < Rig.Streams; stream++)
            {
                LongWord(outStream, unchecked((int) 0x80000000) | where[stream]);
            }                               // bit 31: stored
            body.WriteTo(outStream);
            LongWord(outStream, table + 8); // the sample's offset
            Word(outStream, length);
            Word(outStream, loop);
            outStream.Write(new byte[length]);  // the levels, then the
            outStream.WriteByte(0x80);          // end marker
            return outStream.ToArray();
        }

        /// <summary>
        /// A file cut in two at its loop frame, every section stored: the
        /// section table locates frames [0, L) and the loop table [L, O). A
        /// short replay packs smaller stored than as a container, so this is a
        /// shape a writer reaches; here it also puts the loop table's own
        /// entries on the stored path. L is under one group, so the first
        /// refill of every stream already runs into the second section.
        /// </summary>
        public static string RunStoredCut()
        {
            int frames = 96;
            int loop = 12;
            int ring = 48;
            byte[] file = StoredCutYmx(frames, loop, ring, 24);
            var player = new Player(file, 1);
            if (player.Init() != 0)
            {
                return "stored cut: YMX_init rejected the file";
            }
            // Two passes past the first: R0 carries the frame number, so the
            // values written to it are the frames the player played.
            for (int index = 0; index < frames + 2 * (frames - loop); index++)
            {
                Player.Frame played = player.PlayFrame();
                int wanted = index < frames ? index
                        : loop + (index - frames) % (frames - loop);
                int got = -1;
                foreach (Player.Pair write in played.Writes)
                {
                    if (write.Register == 0)
                    {
                        got = write.Value;
                    }
                }
                if (got != wanted)
                {
                    return "stored cut: call " + index + " wrote R0=" + got
                            + ", want frame " + wanted;
                }
            }
            return "";
        }

        /// <summary>The file RunStoredCut plays: twenty-five stored sections of
        /// loop values, twenty-five more of the rest, and the two tables that
        /// locate them. R0 carries the frame number and every other stream
        /// holds one value, so what reaches the chip says which frame is
        /// playing.</summary>
        private static byte[] StoredCutYmx(int frames, int loop, int ring, int chunk)
        {
            int table = Align(Ymx.YmxFormat.HeaderSize);
            int at = table + 4 * Rig.Streams;
            int[] first = new int[Rig.Streams];
            int[] second = new int[Rig.Streams];
            var body = new MemoryStream();
            for (int half = 0; half < 2; half++)
            {
                for (int stream = 0; stream < Rig.Streams; stream++)
                {
                    while (at % 4 != 0)
                    {
                        body.WriteByte(0);
                        at++;
                    }
                    int from = half == 0 ? 0 : loop;
                    int to = half == 0 ? loop : frames;
                    (half == 0 ? first : second)[stream] = at;
                    for (int frame = from; frame < to; frame++)
                    {
                        body.WriteByte(StreamByte(stream, frame));
                    }
                    at += to - from;
                }
            }

            var outStream = new MemoryStream();
            outStream.Write(System.Text.Encoding.ASCII.GetBytes("YMX!"));
            Word(outStream, Ymx.YmxFormat.Version);
            Word(outStream, 1);             // flags: starts over
            LongWord(outStream, frames);
            Word(outStream, 50);
            Word(outStream, Rig.Streams);
            Word(outStream, ring);
            Word(outStream, chunk);
            LongWord(outStream, 2000000);
            LongWord(outStream, 0);         // no samples
            Word(outStream, 0);
            LongWord(outStream, loop);      // L, where it starts over
            LongWord(outStream, table);     // and the table for [L, O)
            LongWord(outStream, Ymx.YmxFormat.RequiredBase);  // Q: no extension
            for (int stream = 0; stream < Rig.Streams; stream++)
            {
                LongWord(outStream, unchecked((int) 0x80000000) | first[stream]);
            }                               // bit 31: stored
            while (outStream.Length < table)
            {
                outStream.WriteByte(0);
            }
            for (int stream = 0; stream < Rig.Streams; stream++)
            {
                LongWord(outStream, unchecked((int) 0x80000000) | second[stream]);
            }
            body.WriteTo(outStream);
            return outStream.ToArray();
        }

        /// <summary>One stream's byte on one frame of that file.</summary>
        private static byte StreamByte(int stream, int frame)
        {
            return stream switch
            {
                0 => (byte) (frame & 0xFF),     // R0: the frame number
                13 => 0xFF,                     // R13: no envelope restart
                16 => 0xE4,                     // T: 0->A 1->B 2->C 3->D
                _ => 0,
            };
        }

        private static int Align(int at)
        {
            return at + ((-at) & 3);
        }

        private static void Word(MemoryStream outStream, int value)
        {
            outStream.WriteByte((byte) (value >> 8));
            outStream.WriteByte((byte) value);
        }

        private static void LongWord(MemoryStream outStream, int value)
        {
            Word(outStream, value >> 16);
            Word(outStream, value);
        }



        /// <summary>Opcode counts from one tune's compiled script: a RETUNE
        /// addressed to voice 3 is the live retune, one to a real voice
        /// stops the timer, and a HOLD with bit 0 reloads the count under a
        /// running one.</summary>
        public static Dictionary<string, int> ScriptOpcodes(string tune)
        {
            List<string> command = Rig.OwnTool("ymr");
            command.AddRange(new[] {"-script", tune});
            string script = Rig.Run(command);
            var counts = new Dictionary<string, int>
            {
                ["live retune"] = 0, ["stopping retune"] = 0, ["live reload"] = 0,
            };
            foreach (Match action in Regex.Matches(script, "A[0-3]=([0-9A-F]{2})"))
            {
                int value = Convert.ToInt32(action.Groups[1].Value, 16);
                int opcode = value >> 5;
                int voice = (value >> 3) & 3;
                if (opcode == 4)
                {
                    counts[voice == 3 ? "live retune" : "stopping retune"]++;
                }
                else if (opcode == 1 && (value & 1) != 0)
                {
                    counts["live reload"]++;
                }
            }
            return counts;
        }

        /// <summary>One number under a name: what the packer measured, what
        /// the document says.</summary>
        private sealed record Measured(string What, long Is, long Said);

        // Said() reports through this: a sentence was reworded away from
        // the pattern that reads it back out.
        private static string reworded = "";


        private static List<string>? Said(string flat, string pattern, string what)
        {
            Match found = Regex.Match(flat, pattern);
            if (!found.Success)
            {
                reworded = "conversion numbers: the sentence giving " + what
                        + " no longer matches its pattern - this check reads"
                        + " them out of it";
                return null;
            }
            var groups = new List<string>();
            for (int i = 1; i < found.Groups.Count; i++)
            {
                groups.Add(found.Groups[i].Value);
            }
            return groups;
        }

        private static long Number(string text)
        {
            return long.Parse(text.Replace(",", ""));
        }

        /// <summary>The README's two byte counts, against what the assembler
        /// just produced: YMX.S runs from the start of the binary to
        /// ST4_wrap.S's first symbol, and ST4_wrap.S is the rest of it.</summary>
        public static string RunReadmeSizes()
        {
            string text;
            try
            {
                text = File.ReadAllText(Path.Combine(Rig.Repo, "README.md"));
            }
            catch (IOException e)
            {
                return "README sizes: " + e.Message;
            }
            Match playerSaid = Regex.Match(text, "is the player, ([\\d,]+) bytes"
                    + " at the `ST4_UNIT` (\\d)");
            Match wrapSaid = Regex.Match(text, "plus the ([\\d,]+) of"
                    + " \\[68k/ST4_wrap\\.S\\]");
            if (!playerSaid.Success || !wrapSaid.Success)
            {
                return "README sizes: the sentence carrying them has been"
                        + " reworded. It must still read \"is the player, N"
                        + " bytes at the `ST4_UNIT` k\" and \"plus the M of"
                        + " [68k/ST4_wrap.S]\", which is what this check reads"
                        + " them out of";
            }
            int unit = int.Parse(playerSaid.Groups[2].Value);
            Rig.Build build = Rig.AssembleMasked(unit, false);
            int player = Rig.Symbol(build.Symbols, "ST4_init");
            int wrap = build.Binary.Length - player;
            long saidPlayer = Number(playerSaid.Groups[1].Value);
            long saidWrap = Number(wrapSaid.Groups[1].Value);
            if (saidPlayer != player || saidWrap != wrap)
            {
                return "README sizes: it says " + saidPlayer + " + " + saidWrap
                        + " bytes at ST4_UNIT " + unit + "; this build is "
                        + player + " + " + wrap;
            }
            return "";
        }

        /// <summary>The SNDH container, end to end: three subtunes built by
        /// the SNDH combiner, every entry preserving d0-a6, each subtune
        /// playing its own data, the machine state handed back at exit -
        /// subtunes 1 and 2 run a SID on the default Timer A, subtune 3 the
        /// same SID on Timer B - and init-without-exit recovering by itself.</summary>
        public static string RunSndh()
        {
            // Subtune 2 is shorter than the window played below, so it
            // reaches its own wrap while the others do not: nothing a
            // wrapped subtune leaves in the workspace survives the switch
            // to the next.
            int[][] signatures = SndhSignatures();
            Directory.CreateDirectory(Rig.Scratch);
            List<string> command = Rig.OwnTool("mksndh");
            command.Add("-tRig");
            command.Add(Path.Combine(Rig.Scratch, "sndh_test.sndh"));
            for (int i = 0; i < 3; i++)
            {
                string[] extra = i == 2 ? new[] {"-timersB"} : new string[0];
                string tune = Path.Combine(Rig.Scratch,
                        "sndh_tune" + (i + 1) + ".ymx");
                File.WriteAllBytes(tune, Rig.Pack(SndhSource(signatures[i]),
                        960, 24, true, 2, extra));
                command.Add(tune);
            }
            Rig.Finished build = Rig.TryRun(command);
            if (build.ExitCode != 0)
            {
                return "sndh: build failed: " + build.Output.Trim();
            }
            byte[] blob = File.ReadAllBytes(
                    Path.Combine(Rig.Scratch, "sndh_test.sndh"));
            if (IndexOf(blob, "SNDH", 0) != 12 || IndexOf(blob, "HDNS", 0) > 256
                    || IndexOf(blob, "HDNS", 0) < 0)
            {
                return "sndh: the header is not an SNDH header";
            }
            // the subtune-name tag: word offsets from the tag start to NULs
            int sn = IndexOf(blob, "!#SN", 0);
            for (int i = 0; i < 3; i++)
            {
                int at = sn + ((blob[sn + 4 + 2 * i] << 8) | blob[sn + 5 + 2 * i]);
                var name = new System.Text.StringBuilder();
                while (blob[at] != 0)
                {
                    name.Append((char) blob[at++]);
                }
                if (name.ToString() != "sndh_tune" + (i + 1))
                {
                    return "sndh: subtune " + (i + 1) + " is named " + name;
                }
            }

            return CheckSndh(blob, signatures);
        }

        /// <summary>Every core a release publishes, each playing a tune.
        /// RunSndh covers the one core dist/ holds for the default flags;
        /// the twelve a release carries are three unit sizes by the raster
        /// monitor and the frame mask. The monitor paints a colour register
        /// and the mask changes what the frame write sits behind, so neither
        /// reaches the sound chip: every core plays the same values.</summary>
        public static string RunSndhEveryCore()
        {
            string cores = Path.Combine(Rig.Scratch, "cores");
            Directory.CreateDirectory(cores);
            foreach (bool perf in new[] {false, true})
            {
                foreach (bool nomask in new[] {false, true})
                {
                    MkCores.Cores(cores, perf, nomask);
                    foreach (int unit in new[] {1, 2, 4})
                    {
                        string name = "ymxsndh-k" + unit
                                + (perf ? "-perf" : "")
                                + (nomask ? "-nomask" : "")
                                + Tools.BinarySuffix() + ".bin";
                        string problem = SndhOnCore(Path.Combine(cores, name),
                                unit, perf, !nomask);
                        if (problem.Length != 0)
                        {
                            return name + ": " + problem;
                        }
                    }
                }
            }
            return "";
        }

        /// <summary>Every tune the tree pins, played twice: straight
        /// through the player, and through the combine path a release gives
        /// a host - the tune inside an SNDH file built around a real core.
        /// The two must write the same values to the sound chip on every
        /// frame. The sweeps hold the straight path to a model of the
        /// source, so the two paths agreeing is what this adds.</summary>
        public static string RunSndhCorpus()
        {
            string cores = Path.Combine(Rig.Scratch, "cores");
            MkCores.Cores(cores, false, false);
            string core = Path.Combine(cores,
                    "ymxsndh-k2" + Tools.BinarySuffix() + ".bin");
            var pinned = new List<string>();
            foreach (string where in new[] {"ym", "ymr"})
            {
                string dir = Path.Combine(Rig.Repo, where, "test");
                var found = new List<string>(Directory.GetFiles(dir, "*.ymx"));
                found.Sort(StringComparer.Ordinal);
                pinned.AddRange(found);
            }
            if (pinned.Count == 0)
            {
                return "sndh corpus: no pinned .ymx under ym/test";
            }
            foreach (string tune in pinned)
            {
                string problem = SndhAgainstPlayer(tune, core);
                if (problem.Length != 0)
                {
                    return problem;
                }
            }
            return "";
        }

        /// <summary>One pinned tune down both paths, frame by frame.</summary>
        private static string SndhAgainstPlayer(string tune, string core)
        {
            byte[] packed = File.ReadAllBytes(tune);
            string name = Path.GetFileName(tune);
            int frames = Header(packed, 8, 4);
            int budget = Math.Min(frames + 40, 400);

            var straight = new Player(packed, 2);
            if (straight.Init() != 0)
            {
                return "sndh corpus: " + name + ": YMX_init rejected the tune";
            }
            string outFile = Path.Combine(Rig.Scratch, "corpus.sndh");
            MkSndh.Build(new MkSndh.Options(outFile, new List<string> {tune},
                    "Rig", null, null, false, true), core);
            var combined = new Sndh(File.ReadAllBytes(outFile));
            string problem = combined.Call(0, 1);
            if (problem.Length != 0)
            {
                return "sndh corpus: " + name + ": " + problem;
            }

            for (int f = 0; f < budget; f++)
            {
                Player.Frame one = straight.PlayFrame();
                Sndh.Frame two = combined.PlayFrame();
                if (two.Problem.Length != 0)
                {
                    return "sndh corpus: " + name + " frame " + f + ": "
                            + two.Problem;
                }
                if (one.Result == -1)
                {
                    break;
                }
                var want = new Dictionary<int, int>();
                foreach (Player.Pair pair in one.Writes)
                {
                    want[pair.Register] = pair.Value;
                }
                if (want.Count != two.Writes.Count)
                {
                    return Divergence(name, f, want, two.Writes);
                }
                foreach (KeyValuePair<int, int> wrote in want)
                {
                    if (!two.Writes.TryGetValue(wrote.Key, out int got)
                            || got != wrote.Value)
                    {
                        return Divergence(name, f, want, two.Writes);
                    }
                }
            }
            combined.Call(4, 0xD0D0D0D0);
            return "";
        }

        private static string Divergence(string name, int frame,
                Dictionary<int, int> want, Dictionary<int, int> got)
        {
            return "sndh corpus: " + name + " frame " + frame
                    + ": the player wrote " + Registers(want)
                    + " and the SNDH core wrote " + Registers(got);
        }

        private static string Registers(Dictionary<int, int> writes)
        {
            var keys = new List<int>(writes.Keys);
            keys.Sort();
            var text = new List<string>();
            foreach (int key in keys)
            {
                text.Add(key + "=" + writes[key]);
            }
            return "{" + string.Join(", ", text) + "}";
        }

        /// <summary>One core, combined with tunes packed at its own unit
        /// size and played. The core is given rather than resolved from
        /// dist/, which is the only way to reach a release's variants.
        /// </summary>
        private static string SndhOnCore(string core, int unit, bool perf,
                bool maskBurst)
        {
            int[][] signatures = SndhSignatures();
            var tunes = new List<string>();
            for (int i = 0; i < 3; i++)
            {
                string tune = Path.Combine(Rig.Scratch,
                        "core_tune" + (i + 1) + ".ymx");
                File.WriteAllBytes(tune, Rig.Pack(SndhSource(signatures[i]),
                        960, 24, true, unit,
                        i == 2 ? new[] {"-timersB"} : new string[0]));
                tunes.Add(tune);
            }
            string outFile = Path.Combine(Rig.Scratch, "core_test.sndh");
            MkSndh.Build(new MkSndh.Options(outFile, tunes, "Rig", null, null,
                    perf, maskBurst), core);
            return CheckSndh(File.ReadAllBytes(outFile), signatures);
        }

        /// <summary>A looped sample owns its voice until something stops the
        /// timer. A one-shot's voice comes back to the frame write at the
        /// sample's computed end; a looped sample has no such end, so the skip
        /// that covers it has to hold. Both front ends compute that window and
        /// both once read the sample's length alone, so a looped sample's voice
        /// rejoined the frame write one pass in while the loop kept writing the
        /// same register.</summary>
        /// <summary>A file that requires an extension stream, against a
        /// player that implements none. Section 1.6's mask is the whole of
        /// 0.6's new behaviour and the only thing in the format a build
        /// rejects a file for. The control is the same file with the bit
        /// clear, so a rejection is the mask and not the shape of a
        /// hand-built file.</summary>
        public static string RunRequiredExtension()
        {
            byte[] plain = StoredYmx(4, 96, 0);
            var accepted = new Player(plain);
            if (accepted.Init() != 0)
            {
                return "required extension: the control file was rejected, so"
                        + " this check would pass whatever the mask did";
            }

            byte[] required = StoredYmx(4, 96, 0);
            required[Ymx.YmxFormat.OffsetRequired] |= 0x02;  // bit 25 of a
                                            // big-endian long is bit 1 of byte 0
            var refused = new Player(required);
            if (refused.Init() == 0)
            {
                return "required extension: a file requiring stream 25 was"
                        + " accepted by a build that implements no extension"
                        + " stream, so section 1.6's mask decides nothing";
            }

            byte[] wider = StoredYmx(4, 96, 0);
            wider[Ymx.YmxFormat.OffsetStreamCount + 1] = 33;
            var tooMany = new Player(wider);
            if (tooMany.Init() == 0)
            {
                return "required extension: a file claiming 33 streams was"
                        + " accepted, and thirty-two is the ceiling at every"
                        + " version";
            }
            return "";
        }



        /// <summary>The three subtunes' register-2 values, one per frame.
        /// Subtune 2 is shorter than the window played below, so it reaches
        /// its own wrap while the others do not.</summary>
        private static int[][] SndhSignatures()
        {
            int[] lengths = {200, 20, 200};
            int[][] signatures = new int[3][];
            for (int s = 0; s < 3; s++)
            {
                signatures[s] = new int[lengths[s]];
            }
            for (int f = 0; f < lengths[0]; f++)
            {
                signatures[0][f] = (3 * f + 1) & 0xFF;
            }
            for (int f = 0; f < lengths[1]; f++)
            {
                signatures[1][f] = (0x55 + 7 * f) & 0xFF;
            }
            for (int f = 0; f < lengths[2]; f++)
            {
                signatures[2][f] = (0xA0 + f) & 0xFF;
            }
            return signatures;
        }

        /// <summary>One subtune as a YM6 file: its own value in register 2
        /// each frame, and a held SID on voice A from frame 5, so the tune
        /// claims a timer.</summary>
        private static byte[] SndhSource(int[] signature)
        {
            int frames = signature.Length;
            byte[][] values = NewValues(frames);
            for (int f = 0; f < frames; f++)
            {
                values[2][f] = (byte) signature[f];
                values[13][f] = (byte) GenYm.NoEnvelopeChange;
            }
            for (int f = 5; f < frames; f++)
            {
                values[1][f] |= 0x10;
                values[6][f] |= 1 << 5;
                values[14][f] = 100;
                values[8][f] = 10;
            }
            return GenYm.Ym6File(frames, values);
        }

        /// <summary>One built SNDH blob, driven through its three entries.
        /// </summary>
        private static string CheckSndh(byte[] blob, int[][] signatures)
        {
            var player = new Sndh(blob);
            // sentinels for every timer's state: what a claim must hand
            // back, and what an unclaimed timer must never touch
            long[][] sentinels = {new long[] {0x134, 4, 0xCAFE0134},
                    new long[] {0x120, 4, 0xCAFE0120},
                    new long[] {0x114, 4, 0xCAFE0114},
                    new long[] {0x110, 4, 0xCAFE0110},
                    new long[] {0xFFFFFA19, 1, 3}, new long[] {0xFFFFFA1B, 1, 7},
                    new long[] {0xFFFFFA1D, 1, 0x17},
                    new long[] {0xFFFFFA1F, 1, 99}, new long[] {0xFFFFFA21, 1, 77},
                    new long[] {0xFFFFFA23, 1, 66}, new long[] {0xFFFFFA25, 1, 88},
                    new long[] {0xFFFFFA07, 1, 0x21},
                    new long[] {0xFFFFFA13, 1, 0x20},
                    new long[] {0xFFFFFA09, 1, 0x10},
                    new long[] {0xFFFFFA15, 1, 0x11}};
            foreach (long[] sentinel in sentinels)
            {
                byte[] bytes = new byte[sentinel[1]];
                long v = sentinel[2];
                for (int i = bytes.Length - 1; i >= 0; i--)
                {
                    bytes[i] = (byte) v;
                    v >>>= 8;
                }
                player.Uc.Write((ulong) sentinel[0], bytes);
            }

            string problem = player.Call(0, 1);     // init subtune 1
            if (problem.Length != 0)
            {
                return "sndh: " + problem;
            }
            problem = PlayAndCheck(player, signatures, 1);
            if (problem.Length != 0)
            {
                return problem;
            }
            problem = player.Call(4, 0xD0D0D0D0);   // exit
            if (problem.Length != 0)
            {
                return "sndh: " + problem;
            }
            problem = Handback(player, sentinels);  // Timer A restored, the
            if (problem.Length != 0)                // other three untouched
            {
                return problem;
            }

            problem = player.Call(0, 3);            // subtune 3: the SID on
            if (problem.Length != 0)                // Timer B
            {
                return "sndh: " + problem;
            }
            problem = PlayAndCheck(player, signatures, 3);
            if (problem.Length != 0)
            {
                return problem;
            }
            problem = player.Call(4, 0xD0D0D0D0);   // exit
            if (problem.Length != 0)
            {
                return "sndh: " + problem;
            }
            problem = Handback(player, sentinels);  // Timer B restored, the
            if (problem.Length != 0)                // other three untouched
            {
                return problem;
            }

            problem = player.Call(0, 2);            // subtune 2
            if (problem.Length != 0)
            {
                return "sndh: " + problem;
            }
            problem = PlayAndCheck(player, signatures, 2);
            if (problem.Length != 0)
            {
                return problem;
            }
            problem = player.Call(0, 1);            // init WITHOUT exit
            if (problem.Length != 0)
            {
                return "sndh: " + problem;
            }
            problem = PlayAndCheck(player, signatures, 1);
            if (problem.Length != 0)
            {
                return problem;
            }
            problem = player.Call(0, 11);           // out of range: subtune 1
            if (problem.Length != 0)
            {
                return "sndh: " + problem;
            }
            problem = PlayAndCheck(player, signatures, 1);
            if (problem.Length != 0)
            {
                return problem;
            }
            player.Call(4, 0xD0D0D0D0);
            return "";
        }


        private static string PlayAndCheck(Sndh player, int[][] signatures,
                int which)
        {
            int[] want = signatures[which - 1];
            for (int f = 0; f < 30; f++)
            {
                Sndh.Frame frame = player.PlayFrame();
                if (frame.Problem.Length != 0)
                {
                    return "sndh: " + frame.Problem;
                }
                if (!frame.Writes.TryGetValue(2, out int got)
                        || got != want[f % want.Length])
                {
                    return "sndh: subtune " + which + " frame " + f + " played "
                            + (frame.Writes.ContainsKey(2) ? got.ToString() : "nothing")
                            + " want " + want[f % want.Length]
                            + (f >= want.Length ? " - past its wrap" : "");
                }
            }
            return "";
        }

        private static string Handback(Sndh player, long[][] sentinels)
        {
            foreach (long[] sentinel in sentinels)
            {
                long got = player.Uc.Value((ulong) sentinel[0], (int) sentinel[1]);
                if (sentinel[0] == 0xFFFFFA1D || sentinel[0] == 0xFFFFFA1B)
                {
                    if ((got & 0x0F) != (sentinel[2] & 0x0F))
                    {
                        return "sndh: exit lost the control nibble at "
                                + sentinel[0].ToString("x");
                    }
                }
                else if (got != sentinel[2])
                {
                    return "sndh: exit lost the state at "
                            + sentinel[0].ToString("x");
                }
            }
            return "";
        }

        public static int IndexOf(byte[] haystack, string needle, int from)
        {
            byte[] wanted = System.Text.Encoding.ASCII.GetBytes(needle);
            for (int at = from; at <= haystack.Length - wanted.Length; at++)
            {
                bool hit = true;
                for (int i = 0; i < wanted.Length; i++)
                {
                    if (haystack[at + i] != wanted[i])
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
            return -1;
        }

        internal static byte[][] NewValues(int frames)
        {
            byte[][] values = new byte[16][];
            for (int r = 0; r < 16; r++)
            {
                values[r] = new byte[frames];
            }
            return values;
        }

        internal static int[][] EmptyPops(int frames)
        {
            int[][] pops = new int[frames][];
            for (int f = 0; f < frames; f++)
            {
                pops[f] = new int[0];
            }
            return pops;
        }

        internal static bool OnePair(List<Player.Pair> pairs, int register,
                int value)
        {
            return pairs.Count == 1 && pairs[0].Register == register
                    && pairs[0].Value == value;
        }

        internal static string Show(List<Player.Pair> pairs)
        {
            var text = new System.Text.StringBuilder("[");
            foreach (Player.Pair pair in pairs)
            {
                text.Append(text.Length == 1 ? "" : ", ").Append('(')
                        .Append(pair.Register).Append(", ").Append(pair.Value)
                        .Append(')');
            }
            return text.Append(']').ToString();
        }

        private static string Stem(string path)
        {
            string name = Path.GetFileName(path);
            int dot = name.LastIndexOf('.');
            return dot < 0 ? name : name[..dot];
        }

        /// <summary>The whole battery, one status line per check.</summary>
        public static int Battery(bool quick)
        {
            // frames, ring, chunk, label, starts over, extra passes, unit, and
            // where the header sends its player back to - left off where that
            // is frame 0, which is every shape but the four that name one
            var shapes = new List<object[]>
            {
                new object[] {600, 960, 24, "default 960/24", true, 1, 1},
                new object[] {600, 960, 24, "plays once", false, 0, 1},
                new object[] {600, 960, 24, "two passes more", true, 2, 1},
                new object[] {600, 240, 24, "small ring 240/24", true, 1, 1},
                new object[] {600, 48, 24, "two-group ring 48/24", true, 1, 1},
                new object[] {600, 960, 64, "long calls 960/64", true, 1, 1},
                new object[] {608, 34, 17, "tightest legal 34/17", true, 1, 1},
                new object[] {37, 960, 24, "shorter than a ring", true, 3, 1},
                new object[] {48, 48, 24, "exactly a ring", true, 2, 1},
                new object[] {40, 960, 24, "shorter than two groups", true, 4, 1},
                new object[] {24, 960, 24, "exactly one group", true, 2, 1},
                new object[] {9, 960, 24, "shorter than one group", true, 3, 1},
                new object[] {1, 960, 24, "a single frame", true, 5, 1},
                new object[] {1, 960, 24, "a single frame, once", false, 0, 1},
                // Wider units: cheaper refills, and the packer's whole-unit
                // rules must hold. The decoder is a different build for each.
                new object[] {600, 960, 24, "unit 2", true, 2, 2},
                new object[] {600, 960, 24, "unit 2, plays once", false, 0, 2},
                new object[] {600, 960, 24, "unit 4", true, 1, 4},
                // A header that loops from a frame other than 0: the file
                // carries L, and the player rewinds by O - L at the wrap
                // rather than starting the tune again.
                new object[] {600, 960, 24, "loops from frame 200", true, 2, 1, 200},
                new object[] {600, 960, 24, "loops from frame 599", true, 3, 1, 599},
                new object[] {600, 240, 24, "a body past the ring", true, 2, 1, 100},
                // A body past the largest ring the format allows: the packer
                // cuts every stream at the loop frame and the file carries a
                // loop table, so the wrap opens the second section rather than
                // moving the cursor back.
                new object[] {2688, 960, 24, "cut at frame 100", true, 2, 1, 100},
                // A first section shorter than a group: every stream runs out
                // of it inside its own first fill, at init, and opens the loop
                // table's before frame 0 is played.
                new object[] {2688, 960, 24, "cut at frame 12, unit 2",
                    true, 2, 2, 12},
                new object[] {600, 960, 24, "loops from frame 200, unit 2",
                    true, 2, 2, 200},
            };
            if (!quick)
            {
                shapes.Add(new object[] {4000, 960, 24, "four thousand frames",
                        true, 1, 1});
                shapes.Add(new object[] {4000, 2048, 32, "four thousand, 2048/32",
                        true, 1, 1});
                shapes.Add(new object[] {4000, 960, 24, "four thousand, unit 2",
                        true, 1, 2});
                shapes.Add(new object[] {4000, 2048, 32, "four thousand, unit 4",
                        true, 1, 4});
            }

            int failures = 0;
            foreach (object[] shape in shapes)
            {
                string label = (string) shape[3];
                bool loops = (bool) shape[4];
                int loopFrame = shape.Length > 7 ? (int) shape[7] : 0;
                string problem = RunShape((int) shape[0], (int) shape[1],
                        (int) shape[2], label, loops, (int) shape[5],
                        (int) shape[6], loopFrame);
                if (problem.Length != 0)
                {
                    Console.WriteLine("FAIL " + problem);
                    failures++;
                }
                else
                {
                    string where = loops ? "starts over" : "plays once";
                    Console.WriteLine(string.Format(
                            "OK   {0,-26} ({1} frames, {2}-byte rings, {3})",
                            label, shape[0], shape[1], where));
                }
            }

            failures += Report(RunSndh(),
                    "the SNDH container       (subtunes, handback, re-init)");
            failures += Report(RunSndhEveryCore(),
                    "every published core     (3 units x monitor x mask)");
            failures += Report(RunSndhCorpus(),
                    "the pinned tunes combined (both paths, same chip writes)");
            failures += Report(RunRequiredExtension(),
                    "a required extension     (the mask rejects, the ceiling holds)");
            failures += Report(RunStoredCut(),
                    "the stored cut           (both tables, values not containers)");
            failures += Report(RunLoopPointResolve(),
                    "the loop-point resolve   (an unsigned word, $8000 and up)");
            failures += Report(RunReadmeSizes(),
                    "the README sizes         (the two byte counts, measured)");

            foreach (bool perf in new[] {false, true})
            {
                string problem = Effects.RunEffects(perf);
                string build = perf ? "PERF build" : "";
                if (problem.Length != 0)
                {
                    Console.WriteLine("FAIL " + (build.Length == 0 ? problem
                            : build + ": " + problem));
                    failures++;
                }
                else
                {
                    string label = "the effect stage"
                            + (build.Length == 0 ? "" : ", " + build);
                    Console.WriteLine(string.Format(
                            "OK   {0,-26} (timers, sanitize, mixer, skeleton)",
                            label));
                }
            }

            Console.WriteLine(failures == 0 ? "ALL YMX PLAYER TESTS PASS"
                    : failures + " FAILURES");
            return failures == 0 ? 0 : 1;
        }

        private static int Report(string problem, string label)
        {
            if (problem.Length != 0)
            {
                Console.WriteLine("FAIL " + problem);
                return 1;
            }
            Console.WriteLine("OK   " + label);
            return 0;
        }

        public static void Main(string[] args)
        {
            bool quick = Array.IndexOf(args, "--quick") >= 0;
            Environment.Exit(Battery(quick));
        }
    }
}
