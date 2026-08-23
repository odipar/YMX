package org.ymx.rig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Corpus sweep: pack each .ym tune and verify the player's chip writes
 * against the YM truth, frame by frame, in the emulator rig.
 *
 * <p>{@code ymx/test/sweep.sh song.ym [more.ym ...]}
 *
 * <p>Each tune is packed at k=1 - no padding, so the YM registers are the
 * exact expectation - then played through the real 68000 player under
 * emulation. Every chip write is compared against the masked YM data, R13's
 * hold and shape semantics included, the wrap exercised for tunes up to
 * 3000 frames (longer ones play their first 1200). The effect-owned
 * registers are checked against an INDEPENDENT model of the script
 * semantics, written here: the model recomputes drum windows - downsample
 * factors, durations, retriggers, cuts, arbitration - from the YM data
 * alone, so a packer bug and a player bug cannot cancel out. The tick
 * handlers' own audio is the directed effect test's side, not this one's.
 *
 * <p>One status line per tune: OK, ISSUE, PACKFAIL or SKIP.
 */
final class Sweep {

    private static final int[] MASK = {0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F,
            0x1F, 0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0xFF};
    private static final int[] PREDIV = {0, 4, 10, 16, 50, 64, 100, 200};
    private static final int MFP_CLOCK = 2457600;
    private static final int MAX_HZ = 25600;

    private Sweep() {}

    /** The YM registers and drum lengths, via the Java reader. */
    record Dump(int format, int frames, int drums, int hz, byte[][] registers,
            int[] lengths) {}

    static Dump readYm(Path path) throws IOException {
        byte[] source = Files.readAllBytes(path);
        org.ym6.Ym6Reader.Song song;
        try {
            song = org.ym6.Ym6Reader.read(source);
        } catch (RuntimeException e) {
            throw new IOException(String.valueOf(e.getMessage()));
        }
        int[] lengths = new int[song.drums().length];
        for (int i = 0; i < lengths.length; i++) {
            lengths[i] = song.drums()[i].length;
        }
        return new Dump(song.format().startsWith("YM6") ? 6 : 5, song.frames(),
                lengths.length, song.playerHz(), song.registers(), lengths);
    }

    /** The two slots' (code, prescaler, count) for source frame f, both
     * dialects, exactly as the packer normalizes them. */
    static int[][] slotCodes(Dump dump, int f) {
        byte[][] regs = dump.registers();
        if (dump.format() == 6) {
            return new int[][] {
                    {regs[1][f] & 0xF0, (regs[6][f] >> 5) & 7, regs[14][f] & 0xFF},
                    {regs[3][f] & 0xF0, (regs[8][f] >> 5) & 7, regs[15][f] & 0xFF}};
        }
        int second = (regs[3][f] & 0x30) != 0 ? 0x40 | (regs[3][f] & 0x30) : 0;
        return new int[][] {
                {regs[1][f] & 0x30, (regs[6][f] >> 5) & 7, regs[14][f] & 0xFF},
                {second, (regs[8][f] >> 5) & 7, regs[15][f] & 0xFF}};
    }

    /** The packer's drop rules: the effective divisor, or 0 when the slot is
     * idle this frame. */
    static long validate(int code, int tp, int tc, Dump dump, int f,
            long[][] scale) {
        int v = (code >> 4) & 3;
        if (v == 0 || tp == 0 || tc == 0) {
            return 0;
        }
        int kind = code & 0xC0;
        if (kind == 0x80) {
            return 0;                       // sinus: never packs
        }
        if (kind == 0x40) {
            int n = dump.registers()[8 + v - 1][f] & 0x1F;
            if (n >= dump.drums()) {
                return 0;                   // missing drum
            }
            return PREDIV[tp] * tc * scale[n][0] / scale[n][1];
        }
        if (MFP_CLOCK / (PREDIV[tp] * tc) > MAX_HZ) {
            return 0;                       // too-fast SID or buzzer
        }
        return (long) PREDIV[tp] * tc;
    }

    private static boolean representable(long divisor) {
        for (int p = 1; p < 8; p++) {
            if (divisor % PREDIV[p] == 0 && divisor / PREDIV[p] >= 1
                    && divisor / PREDIV[p] <= 255) {
                return true;
            }
        }
        return false;
    }

    /** The smallest representable divisor at or under the rate ceiling. */
    private static long ceilingDivisor() {
        long needed = (MFP_CLOCK + MAX_HZ - 1) / MAX_HZ;
        long best = Long.MAX_VALUE;
        for (int p = 1; p < 8; p++) {
            long count = (needed + PREDIV[p] - 1) / PREDIV[p];
            if (count <= 255) {
                best = Math.min(best, PREDIV[p] * count);
            }
        }
        return best;
    }

    /** Each drum's divisor scale num/den, mirroring the packer: resample to
     * the highest representable rate under the ceiling when every trigger
     * takes the exact ratio, the power-of-two factor otherwise. */
    static long[][] drumScales(Dump dump) {
        List<Set<Long>> seen = new ArrayList<>();
        for (int n = 0; n < dump.drums(); n++) {
            seen.add(new HashSet<>());
        }
        for (int f = 0; f < dump.frames(); f++) {
            for (int[] slot : slotCodes(dump, f)) {
                int code = slot[0];
                int tp = slot[1];
                int tc = slot[2];
                if ((code & 0xC0) != 0x40 || (code & 0x30) == 0
                        || tp == 0 || tc == 0) {
                    continue;
                }
                int n = dump.registers()[8 + ((code >> 4) & 3) - 1][f] & 0x1F;
                if (n < dump.drums()) {
                    seen.get(n).add((long) PREDIV[tp] * tc);
                }
            }
        }
        long[][] scale = new long[dump.drums()][];
        for (int n = 0; n < dump.drums(); n++) {
            scale[n] = new long[] {1, 1};
            if (seen.get(n).isEmpty()) {
                continue;
            }
            long fastest = seen.get(n).stream().min(Long::compare).orElseThrow();
            if ((long) MAX_HZ * fastest >= MFP_CLOCK) {
                continue;
            }
            long target = ceilingDivisor();
            long g = gcd(target, fastest);
            long num = target / g;
            long den = fastest / g;
            boolean exact = true;
            for (long d : seen.get(n)) {
                if (d * num % den != 0 || !representable(d * num / den)) {
                    exact = false;
                    break;
                }
            }
            if (exact) {
                scale[n] = new long[] {num, den};
            } else {
                long factor = 1;
                while ((long) MAX_HZ * fastest * factor < MFP_CLOCK && factor < 64) {
                    factor *= 2;
                }
                scale[n] = new long[] {factor, 1};
            }
        }
        return scale;
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /**
     * An independent replay of the script semantics: which voices are
     * skipped and which drums force the mixer, per played frame. The same
     * decision rules as the packer's simulator, written a second time so the
     * two implementations check each other through the player in between.
     */
    static final class Model {
        private final Dump dump;
        private final long[][] scale;
        private final int[] lengths;
        private final int[] elast = {0, 0};
        private final int[] owner = {-1, -1, -1};   // per voice: the owning slot
        private final int[] left = {0, 0, 0};       // frames until the skip
        private int skipped;                        // lifts; -1 = stuck (a cut
        private int silenced;                       // drum)

        Model(Dump dump) {
            this.dump = dump;
            scale = drumScales(dump);
            lengths = new int[dump.drums()];
            for (int n = 0; n < dump.drums(); n++) {
                lengths[n] = n < dump.lengths().length
                        ? (int) Math.max(1,
                                dump.lengths()[n] * scale[n][1] / scale[n][0])
                        : 1;
            }
        }

        /** The tune starts over, so nothing is running from its end: the
         * state the compiler began from, which the player puts the machine
         * back into on the frame that ends the tune. */
        void restart() {
            elast[0] = 0;
            elast[1] = 0;
            for (int v = 0; v < 3; v++) {
                owner[v] = -1;
                left[v] = 0;
            }
            skipped = 0;
            silenced = 0;
        }

        /** Advances one played frame showing source frame f; returns
         * {skipped, forced, silenced} voice masks. A voice in the silenced
         * mask is one whose SID starts this frame: the gap model writes its
         * volume register to zero before installing the loud half, so a
         * skipped voice leaves exactly that one write instead of none. */
        int[] step(int f) {
            silenced = 0;
            for (int v = 0; v < 3; v++) {
                if (owner[v] >= 0 && left[v] > 0) {
                    left[v]--;
                    if (left[v] == 0) {
                        owner[v] = -1;
                        skipped &= ~(1 << v);
                    }
                }
            }
            for (int slot = 0; slot < 2; slot++) {
                int[] codes = slotCodes(dump, f)[slot];
                long divisor = validate(codes[0], codes[1], codes[2], dump, f,
                        scale);
                int code = divisor == 0 ? 0 : codes[0];
                if (code == elast[slot]) {
                    if (code != 0 && (code & 0xC0) == 0x40) {
                        drum(slot, code, divisor, f);       // retrigger
                    }
                    continue;
                }
                int old = elast[slot];
                elast[slot] = code;
                if (code == 0) {
                    if ((old & 0xC0) == 0x00 && old != 0) {
                        skipped &= ~(1 << (((old >> 4) & 3) - 1));
                    }
                    if ((old & 0xC0) != 0x40) {
                        cut(slot, -1);
                    }
                    continue;
                }
                int v = ((code >> 4) & 3) - 1;
                int kind = code & 0xC0;
                if (kind == 0x00) {                         // SID
                    if (owner[v] >= 0) {
                        elast[slot] = 0;                    // suppressed
                        if ((old & 0xC0) == 0x00 && old != 0) {
                            skipped &= ~(1 << (((old >> 4) & 3) - 1));
                        }
                        continue;
                    }
                    if ((old & 0xC0) == 0x00 && old != 0) {
                        skipped &= ~(1 << (((old >> 4) & 3) - 1));
                    }
                    cut(slot, -1);
                    skipped |= 1 << v;
                    silenced |= 1 << v;         // SID_START silences first
                } else if (kind == 0x40) {                  // drum
                    if ((old & 0xC0) == 0x00 && old != 0) {
                        skipped &= ~(1 << (((old >> 4) & 3) - 1));
                    } else if ((old & 0xC0) == 0x40 && old != 0
                            && ((old ^ code) & 0x30) != 0) {
                        int o = ((old >> 4) & 3) - 1;
                        if (owner[o] == slot) {
                            owner[o] = -1;                  // orphan cleanup
                            left[o] = 0;
                            skipped &= ~(1 << o);
                        }
                    }
                    int other = elast[1 - slot];
                    if ((other & 0xC0) == 0x00 && other != 0
                            && ((other >> 4) & 3) - 1 == v) {
                        elast[1 - slot] = 0;                // arbitration
                    }
                    cut(slot, v);
                    drum(slot, code, divisor, f);
                } else {                                    // buzzer
                    cut(slot, -1);
                }
            }
            int forced = 0;
            for (int v = 0; v < 3; v++) {
                if (owner[v] >= 0) {
                    forced |= 1 << v;
                }
            }
            return new int[] {skipped, forced, silenced};
        }

        private void drum(int slot, int code, long divisor, int f) {
            int v = ((code >> 4) & 3) - 1;
            int n = dump.registers()[8 + v][f] & 0x1F;
            long ticks = lengths[n] + 1;
            // the packer's duration(): a sixteenth of a frame covers the
            // arming phase
            long scaled = ticks * divisor * dump.hz() + MFP_CLOCK / 16;
            int frames = (int) ((scaled + MFP_CLOCK - 1) / MFP_CLOCK);
            owner[v] = slot;
            left[v] = frames;
            skipped |= 1 << v;
        }

        /** A program on this slot's timer: a drum it still owes ticks to is
         * cut, its marker never runs, its voice stays skipped - as the
         * reference player left it. */
        private void cut(int slot, int skip) {
            for (int v = 0; v < 3; v++) {
                if (v != skip && owner[v] == slot && left[v] > 0) {
                    left[v] = -1;                           // stuck
                }
            }
        }
    }

    /** Extra packer options, for a shape the corpus never asks for:
     * {@code YMX_PACK_OPTIONS='-timersBC' ymx/test/sweep.sh one.ym}. */
    private static String[] packOptions() {
        String options = System.getenv("YMX_PACK_OPTIONS");
        return options == null || options.isBlank() ? new String[0]
                : options.trim().split("\\s+");
    }

    static String sweep(Path path) {
        String name = String.valueOf(path.getFileName());
        Dump dump;
        try {
            dump = readYm(path);
        } catch (IOException e) {
            return "SKIP " + name + ": " + e.getMessage();
        }
        Path ymx;
        try {
            ymx = Files.createTempFile("sweep", ".ymx");
        } catch (IOException e) {
            return "SKIP " + name + ": " + e;
        }
        try {
            List<String> command = new ArrayList<>(List.of("java", "-cp",
                    Rig.CLASSES.toString(), "org.ym6.Ymx", "-f", "-k1"));
            command.addAll(List.of(packOptions()));
            command.add(path.toAbsolutePath().toString());
            command.add(ymx.toString());
            Rig.Finished packed = Rig.tryRun(command);
            if (packed.code() != 0) {
                String[] lines = packed.output().strip().split("\n");
                return "PACKFAIL " + name + ": " + lines[lines.length - 1];
            }
            List<String> warns = new ArrayList<>();
            for (String line : packed.output().split("\n")) {
                if (line.contains("Warning") || line.contains("Padded")) {
                    warns.add(line.strip());
                }
            }
            return play(name, dump, Files.readAllBytes(ymx), warns);
        } catch (IOException | IllegalStateException e) {
            return "ISSUE " + name + ": " + e.getMessage();
        } finally {
            try {
                Files.deleteIfExists(ymx);
            } catch (IOException e) {
                // the temp directory's own business
            }
        }
    }

    private static String play(String name, Dump dump, byte[] packed,
            List<String> warns) {
        int ring = ((packed[16] & 0xFF) << 8) | (packed[17] & 0xFF);
        int headerFrames = (int) ((long) (packed[8] & 0xFF) << 24
                | (packed[9] & 0xFF) << 16 | (packed[10] & 0xFF) << 8
                | (packed[11] & 0xFF));
        boolean loops = (packed[7] & 1) != 0;
        Player player = new Player(packed, Rig.workspaceSize(ring));
        if (player.init() != 0) {
            return "INITFAIL " + name;
        }
        Model model = new Model(dump);
        int[] strict = {0, 1, 2, 3, 4, 5, 6, 11, 12};
        int budget = dump.frames() <= 3000 ? dump.frames() + 200 : 1200;
        boolean wrapped = false;
        int played = 0;
        for (int f = 0; f < budget; f++) {
            int src = played % headerFrames;    // the same frames, over and
            if (played != 0 && src == 0) {      // over
                model.restart();                // the player silenced everything
            }
            Player.Frame frame = player.frame();
            if (frame.result() == -1) {
                if (f < dump.frames()) {
                    return "ISSUE " + name + ": ended early at frame " + f
                            + "/" + dump.frames();
                }
                break;
            }
            if (frame.result() == 1) {
                wrapped = true;
            }
            int[] masks = model.step(src);
            int skipped = masks[0];
            int forced = masks[1];
            int silenced = masks[2];
            Map<Integer, Integer> got = new HashMap<>();
            for (Player.Pair pair : frame.writes()) {
                got.put(pair.register(), pair.value());
            }
            for (int r : strict) {
                int want = dump.registers()[r][src] & MASK[r];
                if (r == 7) {
                    want |= forced | forced << 3;
                }
                Integer value = got.get(r);
                if (value == null || value != want) {
                    return "ISSUE " + name + ": frame " + f + " R" + r
                            + " wrote " + value + " want " + want;
                }
            }
            for (int v = 0; v < 3; v++) {
                int r = 8 + v;
                Integer value = got.get(r);
                if ((silenced & (1 << v)) != 0) {
                    // the SID start's own silence write, then the loud half
                    if (value == null || value != 0) {
                        return "ISSUE " + name + ": frame " + f
                                + " started a SID on voice " + "ABC".charAt(v)
                                + " without silencing R" + r + " (wrote "
                                + value + ")";
                    }
                } else if ((skipped & (1 << v)) != 0) {
                    if (value != null) {
                        return "ISSUE " + name + ": frame " + f + " wrote R"
                                + r + " while it was skipped";
                    }
                } else {
                    int want = dump.registers()[r][src] & MASK[r];
                    if (value == null || value != want) {
                        return "ISSUE " + name + ": frame " + f + " R" + r
                                + " wrote " + value + " want " + want;
                    }
                }
            }
            int r13 = dump.registers()[13][src] & 0xFF;
            Integer shape = got.get(13);
            if (r13 == 0xFF && shape != null) {
                return "ISSUE " + name + ": frame " + f + " wrote held R13";
            }
            if (r13 != 0xFF && (shape == null || shape != (r13 & 0x0F))) {
                return "ISSUE " + name + ": frame " + f + " R13 " + shape
                        + " want " + (r13 & 0x0F);
            }
            played++;
            if (!loops && played == dump.frames()) {
                break;
            }
        }
        String loop = wrapped ? "started over"
                : dump.frames() > 3000 ? "partial" : "once";
        String extra = warns.isEmpty() ? ""
                : " [" + String.join("; ", warns) + "]";
        return "OK " + name + " (" + Math.min(budget, dump.frames() + 200)
                + "f " + loop + ")" + extra;
    }

    public static void main(String[] args) {
        int failed = 0;
        for (String tune : args) {
            String line = sweep(Path.of(tune));
            if (line.startsWith("ISSUE") || line.startsWith("PACKFAIL")
                    || line.startsWith("INITFAIL")) {
                failed = 1;
            }
            System.out.println(line);
        }
        System.exit(failed);
    }
}
