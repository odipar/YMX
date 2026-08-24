using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;

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

        /// <summary>Plays a whole tune (and passes times more) and checks it.</summary>
        public static string RunShape(int frames, int ring, int chunk,
                string label, bool loops, int passes, int unit)
        {
            byte[][] source = GenYm.Registers(frames);
            byte[] packed = Rig.Pack(GenYm.Ym6File(frames, source), ring, chunk,
                    loops, unit);
            int played = loops ? frames * (1 + passes) : frames;
            List<GenYm.ChipState> expected = GenYm.ChipStates(frames, source,
                    loops, played);

            var player = new Player(packed, Rig.WorkspaceSize(ring), unit);
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
                // frame 0 again". A tune that plays once never reports it:
                // it reports -1 on the call after its last frame instead.
                bool wrapped = position >= frames && loops;
                if (wrapped)
                {
                    position = 0;
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

        /// <summary>Where a retrigger stream reads the shape it restarts:
        /// the YM front end out of the low nibble of the voice the channel
        /// runs on, the .ymr front end out of R13, told apart by making them
        /// DISAGREE and reading the tick's own patched immediate.</summary>
        public static string RunShapeSource()
        {
            // The flag-clear path, every YM tune: reading the shadow would
            // restart 8 rather than the voice nibble's 11.
            int frames = 16;
            byte[][] values = NewValues(frames);
            for (int frame = 0; frame < frames; frame++)
            {
                values[7][frame] = 0x38;
                values[9][frame] = 0x0B;    // voice B's level, nibble 11
                values[13][frame] = (byte) GenYm.NoEnvelopeChange;
            }
            for (int frame = 4; frame < 12; frame++)
            {                               // sync-buzzer, voice B
                values[1][frame] = 0xE0;
                values[6][frame] |= 6 << 5;
                values[14][frame] = 200;
            }
            var player = new Player(Rig.Pack(GenYm.Ym6File(frames, values),
                    960, 24, true, 1), Rig.WorkspaceSize(960));
            if (player.Init() != 0)
            {
                return "shape source: YMX_init rejected the YM tune";
            }
            for (int frame = 0; frame < 6; frame++)
            {
                player.PlayFrame();
            }
            int got = PatchedShape(player, "a");    // a YM tune's slot 1 is
            if (got != 11)                          // Timer A
            {
                return "shape source: a YM buzzer restarts shape " + got
                        + ", want 11 - the nibble of the voice it runs on";
            }

            // The flag-set path: R13 popped to $0A on the very frame the RTE
            // arms, while voice B's level is $0C - the arm must take the NEW
            // shape. Frame 3 then moves the shape under the running buzzer.
            int[][] pops = EmptyPops(8);
            pops[0] = new[] {5, 7, 10, 14, 15};
            pops[3] = new[] {10};
            byte[] image = Rig.YmrImage(8, pops, new Dictionary<int, byte[]>
            {
                [5] = new byte[] {0x38},            // mixer
                [7] = new byte[] {0x0C},            // voice B's level: nibble 12
                [10] = new byte[] {0x0A, 0x04},     // the shapes: 10, then 4
                [14] = new byte[] {3},              // Timer B runs an RTE
                [15] = new byte[] {6, 200},         // prescaler 6, count 200
            }, 0);
            player = new Player(Rig.PackYmr(image, 960, 24), Rig.WorkspaceSize(960));
            if (player.Init() != 0)
            {
                return "shape source: YMX_init rejected the .ymr tune";
            }
            player.PlayFrame();             // frame 0: R13 := 10, RTE arms
            got = PatchedShape(player, "b");    // channel 1 of a .ymr is Timer B
            if (got != 10)
            {
                return "shape source: a .ymr buzzer arms on shape " + got
                        + ", want 10 - R13 as this frame wrote it, not the 12"
                        + " in the volume nibble";
            }
            for (int frame = 1; frame < 4; frame++)
            {                               // frame 3 pops the shape to 4
                player.PlayFrame();
            }
            got = PatchedShape(player, "b");
            if (got != 4)
            {
                return "shape source: a shape moving under a running buzzer"
                        + " left the tick on " + got + ", want 4 - the hold"
                        + " path reads R13 too";
            }

            // An RTE that arms before the tune has written any shape: the
            // spec says to assume 8.
            pops = EmptyPops(8);
            pops[0] = new[] {5, 8, 17, 18};
            image = Rig.YmrImage(8, pops, new Dictionary<int, byte[]>
            {
                [5] = new byte[] {0x38},
                [8] = new byte[] {0x1F},            // voice C: nibble 15
                [17] = new byte[] {3},              // Timer D runs an RTE
                [18] = new byte[] {6, 200},
            }, 0);
            player = new Player(Rig.PackYmr(image, 960, 24), Rig.WorkspaceSize(960));
            if (player.Init() != 0)
            {
                return "shape source: YMX_init rejected the unshaped .ymr tune";
            }
            player.PlayFrame();
            got = PatchedShape(player, "d");    // channel 2 of a .ymr is Timer D
            if (got != 8)
            {
                return "shape source: an RTE armed before any shape restarts "
                        + got + ", want 8 - the assumed shape, not voice C's"
                        + " nibble";
            }
            return "";
        }

        /// <summary>A looped sample, which the player loops rather than
        /// stopping: the proof is the pointer - play the block out, and the
        /// tick after the marker must be reading the loop start rather than
        /// a stopped timer.</summary>
        public static string RunSampleLoop(bool perf)
        {
            int[][] pops = EmptyPops(8);
            pops[0] = new[] {5, 7, 14, 15, 16};
            byte[] image = Rig.YmrImage(8, pops, new Dictionary<int, byte[]>
            {
                [5] = new byte[] {0x38},            // mixer
                [7] = new byte[] {0x0C},            // voice B's level
                [14] = new byte[] {2},              // Timer B runs a Sample
                [15] = new byte[] {6, 200},         // prescaler 6, count 200
                [16] = new byte[] {0},              // sample 0
            }, 0, new Rig.SampleBlock(new byte[] {1, 2, 3, 4}, true, 1));
            var player = new Player(Rig.PackYmr(image, 960, 24),
                    Rig.WorkspaceSize(960), 1, false, perf);
            if (player.Init() != 0)
            {
                return "sample loop: YMX_init rejected the tune";
            }
            player.PlayFrame();             // frame 0 starts the sample

            long table = DrumTable(player);
            long offset = player.Uc.Value(player.File + (ulong) table, 4);
            long loop = player.Uc.Value(player.File + (ulong) table + 6, 2);
            if (loop != 1)
            {
                return "sample loop: the file stores loop point " + loop
                        + ", want 1";
            }

            ulong code = Rig.Code + (ulong) player.Symbol("ymx_pcm_b");
            int register = 9;               // voice B's volume, which the
            int[] levels = {1, 2, 3, 4};    // tick selects itself
            for (int tick = 0; tick < levels.Length; tick++)
            {
                List<Player.Pair> pairs = InvokeIsr(player, code);
                if (!OnePair(pairs, register, levels[tick]))
                {
                    return "sample loop: tick " + tick + " wrote "
                            + Show(pairs) + ", want level " + levels[tick];
                }
            }

            // The marker tick: it must NOT stop the timer, and must leave
            // the pointer on the loop start rather than past the end.
            int at = player.Mfp.Count;
            List<Player.Pair> marker = InvokeIsr(player, code);
            if (!OnePair(marker, register, 0x80))
            {
                return "sample loop: the marker tick wrote " + Show(marker)
                        + ", want the marker alone";
            }
            const ulong tbcr = 0xFFFFFA1B;  // every tick ends with an EOI;
            for (int i = at; i < player.Mfp.Count; i++)
            {                               // only a stop touches the control
                if (player.Mfp[i].Address == tbcr)
                {
                    return "sample loop: the marker tick programmed the"
                            + " control - a looping stream stops nothing";
                }
            }
            // The tick is reached, never returned from, so a stack left
            // unbalanced is invisible unless it is read off directly.
            long left = player.Uc.Register(Unicorn.A7);
            if (left != (long) (Rig.StackTop - 512))
            {
                return "sample loop: the marker tick reached its rte with the"
                        + " stack " + ((long) (Rig.StackTop - 512) - left)
                        + " bytes off";
            }
            long position = player.Uc.Value(code + (ulong) player.Symbol("ISR_PCM_PTR"), 4);
            if ((ulong) position != player.File + (ulong) offset + 1)
            {
                return "sample loop: after the marker the tick reads "
                        + position.ToString("x") + ", want "
                        + (player.File + (ulong) offset + 1).ToString("x")
                        + " - the loop start";
            }

            int[] again = {2, 3, 4};        // round it goes again
            for (int tick = 0; tick < again.Length; tick++)
            {
                List<Player.Pair> pairs = InvokeIsr(player, code);
                if (!OnePair(pairs, register, again[tick]))
                {
                    return "sample loop: pass 2 tick " + tick + " wrote "
                            + Show(pairs) + ", want " + again[tick];
                }
            }
            return "";
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
            var player = new Player(packed, Rig.WorkspaceSize(960));
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
            int at = 130;
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

        /// <summary>A rate pop under a running effect, done without stopping
        /// it: the ordinary retune stops the timer and runs it again, and
        /// this must never write a zero into the timer's nibble.</summary>
        public static string RunLiveRetune()
        {
            int[][] pops = EmptyPops(6);
            pops[0] = new[] {5, 7, 14, 15};
            pops[3] = new[] {15};           // the rate alone moves
            byte[] image = Rig.YmrImage(6, pops, new Dictionary<int, byte[]>
            {
                [5] = new byte[] {0x38},
                [7] = new byte[] {0x0C},    // voice B's level, unmoved
                [14] = new byte[] {1},      // Timer B runs a PWM
                [15] = new byte[] {6, 200, 5, 200},     // prescaler 6 -> 5
            }, 0);
            var player = new Player(Rig.PackYmr(image, 960, 24),
                    Rig.WorkspaceSize(960));
            if (player.Init() != 0)
            {
                return "live retune: YMX_init rejected the tune";
            }
            for (int frame = 0; frame < 3; frame++)
            {
                player.PlayFrame();
            }
            player.Mfp.Clear();
            player.PlayFrame();             // frame 3: the rate pop
            const ulong ctrl = 0xFFFFFA1B;  // Timer B's control, data
            const ulong data = 0xFFFFFA21;
            var written = new List<Player.Write>();
            foreach (Player.Write write in player.Mfp)
            {
                if (write.Address == ctrl || write.Address == data)
                {
                    written.Add(write);
                }
            }
            if (written.Count != 2 || written[0].Address != ctrl
                    || written[1].Address != data)
            {
                return "live retune: frame 3 touched the MFP with " + written.Count
                        + " writes, want the control register then the data"
                        + " register, once each";
            }
            if ((written[0].Value & 7) == 0)
            {
                return "live retune: the control write stopped the timer, which"
                        + " a live retune never does";
            }
            if ((written[0].Value & 7) != 5 || written[1].Value != 200)
            {
                return "live retune: frame 3 programmed prescaler "
                        + (written[0].Value & 7) + " count " + written[1].Value
                        + ", want 5 and 200";
            }
            return "";
        }

        /// <summary>The packer's own report for one .ymr, as the
        /// documentation quotes it.</summary>
        public static string PackerReport(string tune)
        {
            string outAt = Path.Combine(Rig.Scratch, Stem(tune) + ".ymx");
            List<string> command = Rig.OwnTool("ymr");
            command.AddRange(new[] {"-f", tune, outAt});
            return Rig.Run(command);
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

        /// <summary>The figures ymr/CONVERSION.md quotes, against the tunes
        /// it names - re-measured the way the document says they were taken.</summary>
        public static string RunConversionNumbers()
        {
            string flat;
            try
            {
                flat = string.Join(" ", File.ReadAllText(Path.Combine(Rig.Repo,
                        "ymr", "CONVERSION.md")).Split((char[]?) null,
                        StringSplitOptions.RemoveEmptyEntries));
            }
            catch (IOException e)
            {
                return "conversion numbers: " + e.Message;
            }

            List<string>? tuneAndLength = Said(flat,
                    "`([\\w./-]+\\.ymr)` is ([\\d,]+) frames at (\\d+) Hz",
                    "the tune and its length");
            List<string>? packedSizes = Said(flat,
                    "reports ([\\d,]+) bytes of register and script data packed"
                    + " into ([\\d,]+) \\(([\\d,.]+)%\\) in a ([\\d,]+)-byte file",
                    "the packed sizes");
            List<string>? ringShape = Said(flat,
                    "([\\d,]+) rings of ([\\d,]+) bytes, decoding (\\d+) of the"
                    + " (\\d+) streams", "the ring shape");
            List<string>? scriptStreams = Said(flat,
                    "([\\d,]+) of those ([\\d,]+) packed bytes are the eleven"
                    + " script streams, which the `\\.YMR` - ([\\d,]+) bytes",
                    "the script streams");
            if (tuneAndLength == null || packedSizes == null || ringShape == null
                    || scriptStreams == null)
            {
                return reworded;
            }
            string tune = tuneAndLength[0];
            List<string>? opcodeCounts = Said(flat,
                    "on `" + Regex.Escape(tune) + "` the compiled script carries"
                    + " ([\\d,]+) live reloads and ([\\d,]+) live retunes"
                    + " against no opcode that stops", "the opcode counts");
            List<string>? otherOpcodes = Said(flat,
                    "`([\\w./-]+\\.ymr)` has ([\\d,]+) live retunes and (\\d+)"
                    + " that stop", "the second tune's opcode counts");
            if (opcodeCounts == null || otherOpcodes == null)
            {
                return reworded;
            }
            string other = otherOpcodes[0];
            string tunePath = Path.Combine(Rig.Repo, tune);
            string otherPath = Path.Combine(Rig.Repo, other);
            if (!File.Exists(tunePath) || !File.Exists(otherPath))
            {
                return "conversion numbers: a named tune is not in the tree";
            }

            string report = PackerReport(tunePath);
            var measured = new List<Measured>();
            Match got = Regex.Match(report, "Packed (\\d+) register bytes into"
                    + " (\\d+) \\([\\d,.]+%\\), file (\\d+) bytes");
            if (!got.Success)
            {
                return "conversion numbers: the packer no longer reports its"
                        + " packed sizes";
            }
            measured.Add(new Measured("register bytes",
                    long.Parse(got.Groups[1].Value), Number(packedSizes[0])));
            measured.Add(new Measured("packed bytes",
                    long.Parse(got.Groups[2].Value), Number(packedSizes[1])));
            measured.Add(new Measured("file bytes",
                    long.Parse(got.Groups[3].Value), Number(packedSizes[3])));

            Match shape = Regex.Match(report, "Player needs (\\d+) bytes of ring"
                    + "[\\s\\S]*? decodes (\\d+) of the (\\d+) streams");
            if (!shape.Success)
            {
                return "conversion numbers: the packer no longer reports its"
                        + " ring shape";
            }
            measured.Add(new Measured("ring bytes",
                    long.Parse(shape.Groups[1].Value),
                    Number(ringShape[0]) * Number(ringShape[1])));
            measured.Add(new Measured("streams decoded",
                    long.Parse(shape.Groups[2].Value), Number(ringShape[2])));
            measured.Add(new Measured("streams stored",
                    long.Parse(shape.Groups[3].Value), Number(ringShape[3])));

            // the eleven script streams, summed out of the per-stream listing
            long script = 0;
            foreach (Match stream in Regex.Matches(report,
                    "(?m)^\\s+(M|X|T|A[0-3]|P[0-3])\\s+\\d+\\s+->\\s+(\\d+) bytes"))
            {
                script += long.Parse(stream.Groups[2].Value);
            }
            measured.Add(new Measured("script bytes", script,
                    Number(scriptStreams[0])));
            measured.Add(new Measured("packed bytes, again",
                    long.Parse(got.Groups[2].Value), Number(scriptStreams[1])));
            measured.Add(new Measured(".YMR bytes",
                    new FileInfo(tunePath).Length, Number(scriptStreams[2])));
            long frames = 0;
            foreach (Match perStream in Regex.Matches(report,
                    "(?m)^\\s+\\S+\\s+(\\d+)\\s+->"))
            {
                frames = Math.Max(frames, long.Parse(perStream.Groups[1].Value));
            }
            measured.Add(new Measured("source frames", frames,
                    Number(tuneAndLength[1])));

            Dictionary<string, int> opcodes = ScriptOpcodes(tunePath);
            measured.Add(new Measured("live reloads", opcodes["live reload"],
                    Number(opcodeCounts[0])));
            measured.Add(new Measured("live retunes", opcodes["live retune"],
                    Number(opcodeCounts[1])));
            measured.Add(new Measured("stopping retunes",
                    opcodes["stopping retune"], 0));
            Dictionary<string, int> second = ScriptOpcodes(otherPath);
            measured.Add(new Measured(other + " live retunes",
                    second["live retune"], Number(otherOpcodes[1])));
            measured.Add(new Measured(other + " stopping retunes",
                    second["stopping retune"], Number(otherOpcodes[2])));

            var wrong = new System.Text.StringBuilder();
            foreach (Measured entry in measured)
            {
                if (entry.Is != entry.Said)
                {
                    wrong.Append(wrong.Length == 0 ? "" : "; ").Append(entry.What)
                            .Append(' ').Append(entry.Is).Append(" not ")
                            .Append(entry.Said);
                }
            }
            return wrong.Length == 0 ? "" : "conversion numbers: " + wrong;
        }

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
            Rig.Build build = Rig.Assemble(unit, false, false);
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
            int frames = 200;
            int[][] signatures = new int[3][];
            for (int s = 0; s < 3; s++)
            {
                signatures[s] = new int[frames];
            }
            for (int f = 0; f < frames; f++)
            {
                signatures[0][f] = (3 * f + 1) & 0xFF;
                signatures[1][f] = 0x55;
                signatures[2][f] = (0xA0 + f) & 0xFF;
            }
            Directory.CreateDirectory(Rig.Scratch);
            List<string> command = Rig.OwnTool("mksndh");
            command.Add("-tRig");
            command.Add(Path.Combine(Rig.Scratch, "sndh_test.sndh"));
            for (int i = 0; i < 3; i++)
            {
                byte[][] values = NewValues(frames);
                for (int f = 0; f < frames; f++)
                {
                    values[2][f] = (byte) signatures[i][f];
                    values[13][f] = (byte) GenYm.NoEnvelopeChange;
                }
                for (int f = 5; f < frames; f++)
                {                           // a held SID on voice A, so the
                    values[1][f] |= 0x10;   // tune claims channel 0's timer
                    values[6][f] |= 1 << 5;
                    values[14][f] = 100;
                    values[8][f] = 10;
                }
                string[] extra = i == 2 ? new[] {"-timersB"} : new string[0];
                string tune = Path.Combine(Rig.Scratch,
                        "sndh_tune" + (i + 1) + ".ymx");
                File.WriteAllBytes(tune, Rig.Pack(GenYm.Ym6File(frames, values),
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
            for (int f = 0; f < 30; f++)
            {
                Sndh.Frame frame = player.PlayFrame();
                if (frame.Problem.Length != 0)
                {
                    return "sndh: " + frame.Problem;
                }
                if (!frame.Writes.TryGetValue(2, out int got)
                        || got != signatures[which - 1][f])
                {
                    return "sndh: subtune " + which + " frame " + f + " played "
                            + (frame.Writes.ContainsKey(2) ? got.ToString() : "nothing")
                            + " want " + signatures[which - 1][f];
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
                string problem = RunShape((int) shape[0], (int) shape[1],
                        (int) shape[2], label, loops, (int) shape[5],
                        (int) shape[6]);
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
            failures += Report(RunShapeSource(),
                    "the retrigger shape      (both sources, off the patched tick)");
            foreach (bool perf in new[] {false, true})
            {
                string build = perf ? ", PERF build" : "";
                failures += Report(RunSampleLoop(perf), string.Format(
                        "the sample loop{0,-9}    (back to the loop, not stopped)",
                        build));
            }
            failures += Report(RunLoopPointResolve(),
                    "the loop-point resolve   (an unsigned word, $8000 and up)");
            failures += Report(RunLiveRetune(),
                    "the live retune          (the timer is never stopped)");
            failures += Report(RunReadmeSizes(),
                    "the README sizes         (the two byte counts, measured)");
            failures += Report(RunConversionNumbers(),
                    "the conversion numbers   (ymr/CONVERSION.md, re-measured)");

            bool[][] builds = {new[] {false, false}, new[] {true, false},
                    new[] {false, true}};
            foreach (bool[] flags in builds)
            {
                bool superHost = flags[0];
                bool perf = flags[1];
                string problem = Effects.RunEffects(superHost, perf);
                string build = superHost ? "SUPER_HOST" : perf ? "PERF build" : "";
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
