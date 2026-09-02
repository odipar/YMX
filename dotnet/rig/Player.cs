using System;
using System.Collections.Generic;

namespace Rig
{
    /// <summary>One emulated ST running YMX over a packed tune, ported from
    /// the Java rig's Player.</summary>
    public sealed class Player
    {
        /// <summary>One raw memory write the player made: where and what.</summary>
        public sealed record Write(ulong Address, int Value);

        /// <summary>One chip write, as the sound chip pairs them.</summary>
        public sealed record Pair(int Register, int Value);

        /// <summary>One play call's outcome: d0 and the frame's chip writes.</summary>
        public sealed record Frame(int Result, List<Pair> Writes);

        // The player's own contract: these come back untouched from every
        // call. ST4's decoder state spans a4 and a5, so YMX's contract
        // shrank to these.
        private static readonly long[][] Preserved = {
                new long[] {Unicorn.D6, 0xD6D6D6D6},
                new long[] {Unicorn.D7, 0xD7D7D7D7},
                new long[] {Unicorn.A6, 0x00A6A600}};
        private static readonly int[] ScratchRegisters = {Unicorn.D1, Unicorn.D2,
                Unicorn.D3, Unicorn.D4, Unicorn.D5, Unicorn.A2, Unicorn.A3,
                Unicorn.A4, Unicorn.A5};

        public readonly Unicorn Uc = new();
        public readonly byte[] Binary;
        public readonly Dictionary<string, int> Symbols;
        public readonly ulong File;
        public readonly ulong Work;
        private readonly ulong workEnd;
        public readonly List<Write> Writes = new();
        public readonly List<Write> Mfp = new();    // writes to the MFP page
        public readonly List<int> Palette = new();  // $FFFF8240 words (perf)
        private readonly List<Write> stray = new();

        /// <summary>The workspace is the file's own: Rig.WorkspaceFor reads
        /// the ring out of its header, since the packer raises that above the
        /// size it was asked for. No caller passes a size, so none can pass a
        /// short one.</summary>
        public Player(byte[] packed, int unit, bool perf)
                : this(packed, unit, perf, 0)
        {
        }

        /// <summary>The same on a build for window units, 0 for none.</summary>
        public Player(byte[] packed, int unit, bool perf, int window)
        {
            int workspaceSize = Rig.WorkspaceFor(packed);
            ulong[][] map = {new[] {Rig.Code, 0x4000UL},
                    new[] {Rig.FileAt, 0x30000UL}, new[] {Rig.Work, 0x40000UL},
                    new[] {Rig.StackTop - 0x8000, 0x8000UL},
                    new[] {Rig.Magic, 0x1000UL}, new[] {Rig.PsgPage, 0x1000UL},
                    new[] {Rig.MfpPage, 0x1000UL}, new[] {Rig.Vectors, 0x1000UL}};
            foreach (ulong[] region in map)
            {
                Uc.Map(region[0], region[1]);
            }
            Rig.Build build = Rig.Assemble(unit, perf, window);
            Binary = build.Binary;
            Symbols = build.Symbols;
            Uc.Write(Rig.Code, Binary);
            // Odd-but-even addresses on purpose: the 68000 needs word
            // alignment, not long alignment, and the player must not assume
            // more.
            File = Rig.FileAt + 2;
            Work = Rig.Work + 2;
            workEnd = Work + (ulong) workspaceSize;
            Uc.Write(File, packed);
            byte[] dirty = new byte[workspaceSize];
            Array.Fill(dirty, (byte) 0xA5);
            Uc.Write(Work, dirty);
            Uc.OnWrite(Watch);
            Uc.Write(Rig.Magic, new byte[] {0x4E, 0x71});
        }

        public Player(byte[] packed) : this(packed, 1, false) { }

        public Player(byte[] packed, int unit) : this(packed, unit, false) { }

        private void Watch(ulong address, int size, long value)
        {
            if (address == 0xFFFF8240)
            {                           // the raster monitor's background
                Palette.Add((int) value);
                return;
            }
            if (address >= Rig.PsgPage && address < Rig.PsgPage + 0x1000)
            {
                // A wide write lands one byte per bus lane: a move.l to
                // $8800 is the select at $8800 and the data at $8802,
                // exactly as the chip sees it; the odd lanes fall into
                // shadow.
                for (int lane = 0; lane < size; lane++)
                {
                    Writes.Add(new Write(address + (ulong) lane,
                            (int) (value >>> (8 * (size - 1 - lane))) & 0xFF));
                }
            }
            else if (address >= Rig.MfpPage && address < Rig.MfpPage + 0x1000)
            {
                Mfp.Add(new Write(address, (int) value & 0xFF));
            }
            else if (address < 0x1000)
            {
                // the timer vectors
            }
            else if (address >= Rig.Code
                    && address < Rig.Code + (ulong) Binary.Length)
            {
                // the skeletons' self-modified operands
            }
            else if (!(address >= Work && address + (ulong) size <= workEnd
                    || address >= Rig.StackTop - 0x8000 && address < Rig.StackTop))
            {
                stray.Add(new Write(address, size));
            }
        }

        public int Symbol(string name)
        {
            return Rig.Symbol(Symbols, name);
        }

        public int Call(string entry, long[][] registers)
        {
            ulong stack = Rig.StackTop - 256;
            Uc.Write(stack, new byte[] {0x00, 0x0A, 0x00, 0x00});   // Magic
            // Supervisor state, interrupts enabled: what a VBL handler runs
            // in, and what the player needs. Set before a7, which is a
            // different register in each state.
            Uc.Set(Unicorn.SR, 0x2000);
            Uc.Set(Unicorn.A7, (long) stack);
            foreach (long[] preserved in Preserved)
            {
                Uc.Set((int) preserved[0], preserved[1]);
            }
            foreach (int register in ScratchRegisters)
            {
                Uc.Set(register, 0xBAD0BAD0);
            }
            foreach (long[] given in registers)
            {
                Uc.Set((int) given[0], given[1]);
            }
            ulong address = Rig.Code + (ulong) Symbol(entry);
            int code = Uc.Start(address, Rig.Magic, 50_000_000);
            if (code != 0)
            {
                throw new InvalidOperationException(entry + ": "
                        + Unicorn.Error(code));
            }
            if ((ulong) Uc.Register(Unicorn.PC) != Rig.Magic)
            {
                throw new InvalidOperationException(entry + " did not return");
            }
            foreach (long[] preserved in Preserved)
            {
                if (Uc.Register((int) preserved[0])
                        != (preserved[1] & 0xFFFFFFFF))
                {
                    throw new InvalidOperationException(entry
                            + " clobbered a preserved register");
                }
            }
            if (stray.Count != 0)
            {
                var where = new System.Text.StringBuilder();
                for (int i = 0; i < Math.Min(3, stray.Count); i++)
                {
                    where.Append(where.Length == 0 ? "" : ", ")
                            .Append(stray[i].Address.ToString("x"));
                }
                throw new InvalidOperationException(
                        "wrote outside the workspace at " + where);
            }
            long result = Uc.Register(Unicorn.D0);
            return (int) result;                // d0 is signed
        }

        public int Init()
        {
            return Call("YMX_init", new[] {new long[] {Unicorn.A0, (long) File},
                    new long[] {Unicorn.A1, (long) Work}});
        }

        public int Stop()
        {
            return Call("YMX_stop", new[] {new long[] {Unicorn.A0, (long) Work}});
        }

        /// <summary>Plays one frame: d0 and the (register, value) pairs it
        /// sent.</summary>
        public Frame PlayFrame()
        {
            Writes.Clear();
            Mfp.Clear();
            int result = Call("YMX_play",
                    new[] {new long[] {Unicorn.A0, (long) Work}});
            return new Frame(result, DecodeWrites());
        }

        /// <summary>Pairs up select and write accesses the way the sound
        /// chip sees them.</summary>
        public List<Pair> DecodeWrites()
        {
            var pairs = new List<Pair>();
            int selected = -1;
            foreach (Write write in Writes)
            {
                if (write.Address == Rig.Psg)
                {
                    selected = write.Value;
                }
                else if (write.Address == Rig.Psg + 2)
                {
                    if (selected < 0)
                    {
                        throw new InvalidOperationException(
                                "wrote a value before selecting a register");
                    }
                    pairs.Add(new Pair(selected, write.Value));
                }
                else if (write.Address == Rig.Psg + 1
                        || write.Address == Rig.Psg + 3)
                {
                    // a wide write's shadow lanes
                }
                else
                {
                    throw new InvalidOperationException("wrote to "
                            + write.Address.ToString("x") + ", not the sound chip");
                }
            }
            return pairs;
        }
    }

    /// <summary>One emulated ST driving an SNDH blob through its three
    /// entries, ported from the Java rig's Sndh.</summary>
    public sealed class Sndh
    {
        private static readonly long[][] Canary = {
                new long[] {Unicorn.D0, 0xD0D0D0D0}, new long[] {Unicorn.D1, 0xD1D1D1D1},
                new long[] {Unicorn.D2, 0xD2D2D2D2}, new long[] {Unicorn.D3, 0xD3D3D3D3},
                new long[] {Unicorn.D4, 0xD4D4D4D4}, new long[] {Unicorn.D5, 0xD5D5D5D5},
                new long[] {Unicorn.D6, 0xD6D6D6D6}, new long[] {Unicorn.D7, 0xD7D7D7D7},
                new long[] {Unicorn.A0, 0xA0A0A0A0}, new long[] {Unicorn.A1, 0xA1A1A1A1},
                new long[] {Unicorn.A2, 0xA2A2A2A2}, new long[] {Unicorn.A3, 0xA3A3A3A3},
                new long[] {Unicorn.A4, 0xA4A4A4A4}, new long[] {Unicorn.A5, 0xA5A5A5A5},
                new long[] {Unicorn.A6, 0xA6A6A6A6}};

        public readonly Unicorn Uc = new();
        private readonly ulong baseAt;
        private readonly List<Player.Write> writes = new();

        public Sndh(byte[] blob)
        {
            ulong offset = 0x1002;              // any even address must do
            ulong size = ((ulong) blob.Length + offset + 0xFFFF) & ~0xFFFUL;
            ulong[][] map = {new[] {Rig.Code, size},
                    new[] {Rig.StackTop - 0x8000, 0x8000UL},
                    new[] {Rig.Magic, 0x1000UL}, new[] {Rig.PsgPage, 0x1000UL},
                    new[] {Rig.MfpPage, 0x1000UL}, new[] {Rig.Vectors, 0x1000UL}};
            foreach (ulong[] region in map)
            {
                Uc.Map(region[0], region[1]);
            }
            baseAt = Rig.Code + offset;
            Uc.Write(baseAt, blob);
            Uc.OnWrite((address, width, value) =>
            {
                for (int lane = 0; lane < width; lane++)
                {
                    writes.Add(new Player.Write(address + (ulong) lane,
                            (int) (value >>> (8 * (width - 1 - lane))) & 0xFF));
                }
            }, Rig.Psg, Rig.Psg + 4);
            Uc.Write(Rig.Magic, new byte[] {0x4E, 0x71});
        }

        /// <summary>Runs one SNDH entry; every register d0-a6 must come
        /// back.</summary>
        public string Call(int entry, long d0)
        {
            Uc.Set(Unicorn.SR, 0x2300);     // supervisor FIRST: writing SR
            foreach (long[] canary in Canary)
            {                               // banks a7, so the stack goes in
                Uc.Set((int) canary[0], canary[1]);     // after
            }
            Uc.Set(Unicorn.D0, d0);
            ulong stack = Rig.StackTop - 256;
            Uc.Write(stack, new byte[] {0x00, 0x0A, 0x00, 0x00});   // Magic
            Uc.Set(Unicorn.A7, (long) stack);
            int code = Uc.Start(baseAt + (ulong) entry, Rig.Magic, 50_000_000);
            if (code != 0)
            {
                return "entry +" + entry + ": " + Unicorn.Error(code);
            }
            if ((ulong) Uc.Register(Unicorn.PC) != Rig.Magic)
            {
                return "entry +" + entry + " did not return";
            }
            foreach (long[] canary in Canary)
            {
                long want = canary[0] == Unicorn.D0 ? d0 & 0xFFFFFFFF
                        : canary[1] & 0xFFFFFFFF;
                if (Uc.Register((int) canary[0]) != want)
                {
                    return "entry +" + entry + " clobbered a register";
                }
            }
            return "";
        }

        /// <summary>One play call: a problem, or the frame's chip writes by
        /// register.</summary>
        public sealed record Frame(string Problem, Dictionary<int, int> Writes);

        public Frame PlayFrame()
        {
            writes.Clear();
            string problem = Call(8, 0xD0D0D0D0);
            var pairs = new Dictionary<int, int>();
            int selected = -1;
            foreach (Player.Write write in writes)
            {
                if (write.Address == Rig.Psg)
                {
                    selected = write.Value;
                }
                else if (write.Address == Rig.Psg + 2 && selected >= 0)
                {
                    pairs[selected] = write.Value;
                }
            }
            return new Frame(problem, pairs);
        }
    }
}
