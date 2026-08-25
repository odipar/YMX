package org.ymx.rig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What one tune writes to the sound chip, ticks included.
 *
 * <p>{@link RefDump} records a call's own writes and nothing else, which is
 * the reader of SPEC.md §9.4. A player also runs the timers: §5 sets the
 * rate a tick lands at and §6 says which byte it carries, and neither
 * reaches a reader. This runs the timers too, so the record covers what
 * §3, §5 and §6 state and a reader's record cannot.
 *
 * <p>The MFP is not emulated - Unicorn raises no interrupt - so the timers
 * are modelled here and their handlers called at the addresses the vectors
 * hold. A timer's period is its prescaler times its count, read out of the
 * registers the player programmed, and its phase carries across frames. A
 * timer the player stopped and restarted begins its count again; one
 * reprogrammed while it runs (§3.1's live retune) keeps the count in
 * flight, which is the difference between the two forms, and the MFP write
 * order is where the difference is visible.
 *
 * <p><b>The convention this record fixes:</b> a call's own writes come
 * first, then every tick falling between that call and the next, in time
 * order. On a machine a tick also lands inside the frame handler; here
 * none does. The rates, the tick count and what each tick writes are the
 * machine's, the interleaving within one frame is this record's.
 */
final class TickDump {

    private static final int MFP_CLOCK = 2457600;
    private static final int[] PREDIV = {0, 4, 10, 16, 50, 64, 100, 200};

    /** Control register, its shift in that register, data register, vector. */
    private static final long[][] TIMER = {
            {0xFFFFFA19L, 0, 0xFFFFFA1FL, 0x134},
            {0xFFFFFA1BL, 0, 0xFFFFFA21L, 0x120},
            {0xFFFFFA1DL, 4, 0xFFFFFA23L, 0x114},
            {0xFFFFFA1DL, 0, 0xFFFFFA25L, 0x110}};

    private static final String NAMES = "ABCD";

    private TickDump() {
    }

    public static void main(String[] args) throws IOException {
        System.out.println(dump(Files.readAllBytes(Path.of(args[0])),
                Integer.parseInt(args[1]),
                args.length > 2 ? Integer.parseInt(args[2]) : 2));
    }

    /** One tune's calls and ticks, as the JSON a digest is taken of. */
    static String dump(byte[] packed, int budget, int unit) {
        Player player = new Player(packed, unit, false);
        if (player.init() != 0) {
            return "{\"error\":\"init rejected\"}";
        }
        int rate = ((packed[12] & 0xFF) << 8) | (packed[13] & 0xFF);
        long frameCycles = MFP_CLOCK / rate;
        long[] period = new long[4];
        long[] due = new long[4];
        long now = 0;

        StringBuilder out = new StringBuilder("{\"frames\":[");
        for (int f = 0; f < budget; f++) {
            Player.Frame frame = player.frame();
            List<Player.Write> programming = List.copyOf(player.mfp);
            if (f > 0) {
                out.append(',');
            }
            out.append("{\"result\":").append(frame.result()).append(",\"w\":{");
            appendMap(out, frame.writes());
            out.append("},\"t\":[");

            for (int t = 0; t < 4; t++) {
                long fresh = period(player, t);
                if (fresh == 0) {
                    period[t] = 0;
                } else if (period[t] == 0 || stopped(programming, t)) {
                    period[t] = fresh;              // a fresh count starts now
                    due[t] = now + fresh;
                } else if (fresh != period[t]) {
                    period[t] = fresh;              // live: the count in flight
                }                                   // runs to the end already due
            }

            long end = now + frameCycles;
            boolean first = true;
            while (true) {
                int soonest = -1;
                for (int t = 0; t < 4; t++) {
                    if (period[t] > 0 && due[t] < end
                            && (soonest < 0 || due[t] < due[soonest])) {
                        soonest = t;
                    }
                }
                if (soonest < 0) {
                    break;
                }
                long vector = player.uc.value(TIMER[soonest][3], 4);
                List<Player.Pair> wrote = PlayerTests.invokeIsr(player, vector);
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append("{\"n\":\"").append(NAMES.charAt(soonest)).append("\",\"w\":[");
                for (int i = 0; i < wrote.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append('[').append(wrote.get(i).register()).append(',')
                            .append(wrote.get(i).value()).append(']');
                }
                out.append("]}");
                long after = period(player, soonest);
                if (after == 0) {
                    period[soonest] = 0;            // the handler stopped it
                } else {
                    period[soonest] = after;
                    due[soonest] += after;
                }
            }
            out.append("]}");
            now = end;
            if (frame.result() == -1) {
                break;                              // the entry is written, then done
            }
        }
        out.append("]}");
        return out.toString();
    }

    /** A call's own writes, one value per register, as RefDump records them. */
    private static void appendMap(StringBuilder out, List<Player.Pair> writes) {
        java.util.TreeMap<Integer, Integer> map = new java.util.TreeMap<>();
        for (Player.Pair pair : writes) {
            map.put(pair.register(), pair.value());
        }
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
    }

    /** The period in MFP cycles, or 0 where the timer is stopped. */
    private static long period(Player player, int timer) {
        int control = (int) player.uc.value(TIMER[timer][0], 1);
        int index = (control >> (int) TIMER[timer][1]) & 7;
        if (index == 0) {
            return 0;
        }
        int count = (int) player.uc.value(TIMER[timer][2], 1);
        return (long) PREDIV[index] * (count == 0 ? 256 : count);
    }

    /** Whether this call wrote a stop to that timer's control field. */
    private static boolean stopped(List<Player.Write> programming, int timer) {
        for (Player.Write write : programming) {
            if (write.address() == TIMER[timer][0]
                    && ((write.value() >> (int) TIMER[timer][1]) & 7) == 0) {
                return true;
            }
        }
        return false;
    }
}
