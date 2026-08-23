package org.ymx.rig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One emulated ST driving an SNDH blob through its three entries. */
final class Sndh {

    private static final int[][] CANARY = {
            {Unicorn.D0, 0xD0D0D0D0}, {Unicorn.D1, 0xD1D1D1D1},
            {Unicorn.D2, 0xD2D2D2D2}, {Unicorn.D3, 0xD3D3D3D3},
            {Unicorn.D4, 0xD4D4D4D4}, {Unicorn.D5, 0xD5D5D5D5},
            {Unicorn.D6, 0xD6D6D6D6}, {Unicorn.D7, 0xD7D7D7D7},
            {Unicorn.A0, 0xA0A0A0A0}, {Unicorn.A1, 0xA1A1A1A1},
            {Unicorn.A2, 0xA2A2A2A2}, {Unicorn.A3, 0xA3A3A3A3},
            {Unicorn.A4, 0xA4A4A4A4}, {Unicorn.A5, 0xA5A5A5A5},
            {Unicorn.A6, 0xA6A6A6A6}};

    final Unicorn uc = new Unicorn();
    private final long base;
    private final List<Player.Write> writes = new ArrayList<>();

    Sndh(byte[] blob) {
        long offset = 0x1002;                       // any even address must do
        long size = (blob.length + offset + 0xFFFF) & ~0xFFFL;
        long[][] map = {{Rig.CODE, size}, {Rig.STACK_TOP - 0x8000, 0x8000},
                {Rig.MAGIC, 0x1000}, {Rig.PSG_PAGE, 0x1000},
                {Rig.MFP_PAGE, 0x1000}, {Rig.VECTORS, 0x1000}};
        for (long[] region : map) {
            uc.map(region[0], region[1]);
        }
        base = Rig.CODE + offset;
        uc.write(base, blob);
        uc.onWrite((address, width, value) -> {
            for (int lane = 0; lane < width; lane++) {
                writes.add(new Player.Write(address + lane,
                        (int) (value >>> (8 * (width - 1 - lane))) & 0xFF));
            }
        }, Rig.PSG, Rig.PSG + 4);
        uc.write(Rig.MAGIC, new byte[] {0x4E, 0x71});
    }

    /** Runs one SNDH entry; every register d0-a6 must come back. */
    String call(int entry, long d0) {
        uc.set(Unicorn.SR, 0x2300);         // supervisor FIRST: writing SR
        for (int[] canary : CANARY) {       // banks a7, so the stack goes in
            uc.set(canary[0], canary[1]);   // after
        }
        uc.set(Unicorn.D0, d0);
        long stack = Rig.STACK_TOP - 256;
        uc.write(stack, new byte[] {0x00, 0x0A, 0x00, 0x00});   // MAGIC
        uc.set(Unicorn.A7, stack);
        int code = uc.start(base + entry, Rig.MAGIC, 50_000_000);
        if (code != 0) {
            return "entry +" + entry + ": " + Unicorn.error(code);
        }
        if (uc.register(Unicorn.PC) != Rig.MAGIC) {
            return "entry +" + entry + " did not return";
        }
        for (int[] canary : CANARY) {
            long want = canary[0] == Unicorn.D0 ? d0 & 0xFFFFFFFFL
                    : canary[1] & 0xFFFFFFFFL;
            if (uc.register(canary[0]) != want) {
                return "entry +" + entry + " clobbered a register";
            }
        }
        return "";
    }

    /** One play call: a problem, or the frame's chip writes by register. */
    record Frame(String problem, Map<Integer, Integer> writes) {}

    Frame frame() {
        writes.clear();
        String problem = call(8, 0xD0D0D0D0L);
        Map<Integer, Integer> pairs = new HashMap<>();
        int selected = -1;
        for (Player.Write write : writes) {
            if (write.address() == Rig.PSG) {
                selected = write.value();
            } else if (write.address() == Rig.PSG + 2 && selected >= 0) {
                pairs.put(selected, write.value());
            }
        }
        return new Frame(problem, pairs);
    }
}
