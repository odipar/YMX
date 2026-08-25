package org.ymx.rig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One emulated ST running YMX over a packed tune. */
final class Player {

    /** One raw memory write the player made: where and what. */
    record Write(long address, int value) {}

    /** One chip write, as the sound chip pairs them: register and value. */
    record Pair(int register, int value) {}

    /** One play call's outcome: d0 and the frame's chip writes. */
    record Frame(int result, List<Pair> writes) {}

    // The player's own contract: these come back untouched from every call.
    // ST4's decoder state spans a4 and a5, so YMX's contract shrank to these.
    private static final int[][] PRESERVED = {
            {Unicorn.D6, 0xD6D6D6D6}, {Unicorn.D7, 0xD7D7D7D7},
            {Unicorn.A6, 0x00A6A600}};
    private static final int[] SCRATCH_REGISTERS = {Unicorn.D1, Unicorn.D2,
            Unicorn.D3, Unicorn.D4, Unicorn.D5, Unicorn.A2, Unicorn.A3,
            Unicorn.A4, Unicorn.A5};

    final Unicorn uc = new Unicorn();
    final byte[] binary;
    final Map<String, Integer> symbols;
    final long file;
    final long work;
    private final long workEnd;
    final List<Write> writes = new ArrayList<>();
    final List<Write> mfp = new ArrayList<>();      // writes to the MFP page
    final List<Integer> palette = new ArrayList<>(); // $FFFF8240 words (perf)
    private final List<Write> stray = new ArrayList<>();

    /** The workspace is the file's own: {@link Rig#workspaceFor} reads the
     * ring out of its header, since the packer raises that above the size it
     * was asked for. No caller passes a size, so none can pass a short one. */
    Player(byte[] packed, int unit, boolean perf) {
        int workspaceSize = Rig.workspaceFor(packed);
        long[][] map = {{Rig.CODE, 0x4000}, {Rig.FILE, 0x30000},
                {Rig.WORK, 0x40000}, {Rig.STACK_TOP - 0x8000, 0x8000},
                {Rig.MAGIC, 0x1000}, {Rig.PSG_PAGE, 0x1000},
                {Rig.MFP_PAGE, 0x1000}, {Rig.VECTORS, 0x1000}};
        for (long[] region : map) {
            uc.map(region[0], region[1]);
        }
        Rig.Build build = Rig.assemble(unit, perf);
        binary = build.binary();
        symbols = build.symbols();
        uc.write(Rig.CODE, binary);
        // Odd-but-even addresses on purpose: the 68000 needs word alignment,
        // not long alignment, and the player must not assume more.
        file = Rig.FILE + 2;
        work = Rig.WORK + 2;
        workEnd = work + workspaceSize;
        uc.write(file, packed);
        byte[] dirty = new byte[workspaceSize];
        java.util.Arrays.fill(dirty, (byte) 0xA5);
        uc.write(work, dirty);
        uc.onWrite(this::watch);
        uc.write(Rig.MAGIC, new byte[] {0x4E, 0x71});
    }

    Player(byte[] packed) {
        this(packed, 1, false);
    }

    Player(byte[] packed, int unit) {
        this(packed, unit, false);
    }

    private void watch(long address, int size, long value) {
        if (address == 0xFFFF8240L) {       // the raster monitor's background
            palette.add((int) value);
            return;
        }
        if (address >= Rig.PSG_PAGE && address < Rig.PSG_PAGE + 0x1000) {
            // A wide write lands one byte per bus lane: a move.l to $8800 is
            // the select at $8800 and the data at $8802, exactly as the chip
            // sees it; the odd lanes fall into shadow.
            for (int lane = 0; lane < size; lane++) {
                writes.add(new Write(address + lane,
                        (int) (value >>> (8 * (size - 1 - lane))) & 0xFF));
            }
        } else if (address >= Rig.MFP_PAGE && address < Rig.MFP_PAGE + 0x1000) {
            mfp.add(new Write(address, (int) value & 0xFF));
        } else if (address < 0x1000) {
            // the timer vectors
        } else if (address >= Rig.CODE && address < Rig.CODE + binary.length) {
            // the skeletons' self-modified operands
        } else if (!(address >= work && address + size <= workEnd
                || address >= Rig.STACK_TOP - 0x8000 && address < Rig.STACK_TOP)) {
            stray.add(new Write(address, size));
        }
    }

    int symbol(String name) {
        return Rig.symbol(symbols, name);
    }

    int call(String entry, long[][] registers) {
        long stack = Rig.STACK_TOP - 256;
        uc.write(stack, new byte[] {0x00, 0x0A, 0x00, 0x00});   // MAGIC
        // Supervisor state, interrupts enabled: what a VBL handler runs in,
        // and what the player needs - it touches the sound chip and its own
        // mask. Set before a7, which is a different register in each state.
        uc.set(Unicorn.SR, 0x2000);
        uc.set(Unicorn.A7, stack);
        for (int[] preserved : PRESERVED) {
            uc.set(preserved[0], preserved[1]);
        }
        for (int register : SCRATCH_REGISTERS) {
            uc.set(register, 0xBAD0BAD0L);
        }
        for (long[] given : registers) {
            uc.set((int) given[0], given[1]);
        }
        long address = Rig.CODE + symbol(entry);
        int code = uc.start(address, Rig.MAGIC, 50_000_000);
        if (code != 0) {
            throw new IllegalStateException(entry + ": " + Unicorn.error(code));
        }
        if (uc.register(Unicorn.PC) != Rig.MAGIC) {
            throw new IllegalStateException(entry + " did not return");
        }
        for (int[] preserved : PRESERVED) {
            if (uc.register(preserved[0]) != (preserved[1] & 0xFFFFFFFFL)) {
                throw new IllegalStateException(entry
                        + " clobbered a preserved register");
            }
        }
        if (!stray.isEmpty()) {
            StringBuilder where = new StringBuilder();
            for (Write write : stray.subList(0, Math.min(3, stray.size()))) {
                where.append(where.isEmpty() ? "" : ", ")
                        .append(Long.toHexString(write.address()));
            }
            throw new IllegalStateException("wrote outside the workspace at " + where);
        }
        long result = uc.register(Unicorn.D0);
        return (int) result;                        // d0 is signed
    }

    int init() {
        return call("YMX_init", new long[][] {{Unicorn.A0, file},
                {Unicorn.A1, work}});
    }

    int stop() {
        return call("YMX_stop", new long[][] {{Unicorn.A0, work}});
    }

    /** Plays one frame: d0 and the (register, value) pairs it sent. */
    Frame frame() {
        writes.clear();
        mfp.clear();
        int result = call("YMX_play", new long[][] {{Unicorn.A0, work}});
        return new Frame(result, decodeWrites());
    }

    /** Pairs up select and write accesses the way the sound chip sees them. */
    List<Pair> decodeWrites() {
        List<Pair> pairs = new ArrayList<>();
        int selected = -1;
        for (Write write : writes) {
            if (write.address() == Rig.PSG) {
                selected = write.value();
            } else if (write.address() == Rig.PSG + 2) {
                if (selected < 0) {
                    throw new IllegalStateException(
                            "wrote a value before selecting a register");
                }
                pairs.add(new Pair(selected, write.value()));
            } else if (write.address() == Rig.PSG + 1
                    || write.address() == Rig.PSG + 3) {
                // a wide write's shadow lanes
            } else {
                throw new IllegalStateException("wrote to "
                        + Long.toHexString(write.address()) + ", not the sound chip");
            }
        }
        return pairs;
    }
}
