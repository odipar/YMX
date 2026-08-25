package org.ymx.rig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tick reference against a real MFP.
 *
 * <p>{@link TickDump} models the timers, because Unicorn raises no
 * interrupt. Hatari emulates the MFP, so a run under it with
 * {@code --trace psg_write} is what the chip actually receives. This reads
 * that trace back and compares it, timer by timer, with what the model
 * produced.
 *
 * <p>The trace names the address each write came from. The frame write is
 * one unrolled block - a run of addresses close together, writing registers
 * 0 upwards in order - so a write from anywhere else in the program is a
 * tick's or an action's. Addresses in the TOS ROM are the machine's own and
 * are dropped.
 *
 * <p>What the comparison shows, measured: the rates and the values agree
 * exactly. The phase between ticks and frames does not, and the cause is
 * not the model - a file states its frame rate as a whole number of Hz
 * (§1.1) and a PAL ST's VBL is 49.9201 Hz, so the model's frame is 78.6 MFP
 * cycles short of the machine's and the two drift apart by 0.160% of a
 * frame each time. That shows up only where a frame write changes what a
 * tick carries.
 */
final class TickHatari {

    private static final Pattern WRITE = Pattern.compile(
            "ym write data reg=0x([0-9a-f]+) val=0x([0-9a-f]+)"
            + " video_cyc=(\\d+) \\S+ pc=([0-9a-f]+)");
    private static final long ROM = 0xE00000L;
    private static final int BLOCK_GAP = 0x40;

    /** One write the trace recorded: which register, what value, from where. */
    record Traced(int register, int value, long pc) {}

    private TickHatari() {
    }

    public static void main(String[] args) throws IOException {
        List<Traced> trace = read(Path.of(args[0]));
        Map<Long, int[]> handlers = handlers(trace);
        System.out.print(report(trace, handlers));
        if (args.length > 1) {
            System.out.print(compare(trace, handlers,
                    Files.readString(Path.of(args[1]))));
        }
    }

    /** Every chip write the program made, in order, the ROM's dropped. */
    static List<Traced> read(Path file) throws IOException {
        List<Traced> writes = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            Matcher m = WRITE.matcher(line);
            if (!m.find()) {
                continue;
            }
            long pc = Long.parseLong(m.group(4), 16);
            if (pc >= ROM) {
                continue;                       // TOS setting up the chip
            }
            writes.add(new Traced(Integer.parseInt(m.group(1), 16),
                    Integer.parseInt(m.group(2), 16), pc));
        }
        return writes;
    }

    /**
     * The addresses that are not the frame write, with the register each
     * one writes and how often. The frame write is the block of addresses
     * within {@code BLOCK_GAP} of each other whose registers ascend from 0.
     */
    static Map<Long, int[]> handlers(List<Traced> trace) {
        Map<Long, TreeMap<Integer, Integer>> byPc = new TreeMap<>();
        for (Traced write : trace) {
            byPc.computeIfAbsent(write.pc(), k -> new TreeMap<>())
                    .merge(write.register(), 1, Integer::sum);
        }
        List<Map.Entry<Long, TreeMap<Integer, Integer>>> single = new ArrayList<>();
        for (var entry : byPc.entrySet()) {
            if (entry.getValue().size() == 1) {
                single.add(entry);
            }
        }
        java.util.Set<Long> block = new java.util.HashSet<>();
        long previous = -1;
        int expect = 0;
        for (var one : single) {
            long pc = one.getKey();
            int register = one.getValue().firstKey();
            if (previous >= 0 && (pc - previous > BLOCK_GAP || register < expect)) {
                break;
            }
            block.add(pc);
            previous = pc;
            expect = register + 1;
        }
        Map<Long, int[]> rest = new LinkedHashMap<>();
        for (var entry : byPc.entrySet()) {
            if (!block.contains(entry.getKey())) {
                int total = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
                rest.put(entry.getKey(), new int[] {entry.getValue().firstKey(), total});
            }
        }
        return rest;
    }

    /** One line per handler address: what it wrote and how often. */
    static String report(List<Traced> trace, Map<Long, int[]> handlers) {
        StringBuilder out = new StringBuilder();
        out.append(trace.size()).append(" writes from the program\n");
        for (var entry : handlers.entrySet()) {
            out.append(String.format("  %06x writes R%-2d %6d times%n",
                    entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        return out.toString();
    }

    /**
     * The machine's tick writes against the model's, register by register.
     * A register's ticks come from the handler addresses that write it
     * often; an address writing it a handful of times is an action's, not a
     * tick's (§9.2 has `START_TOGGLE` write R8+v among a frame's actions),
     * and is left out.
     */
    static String compare(List<Traced> trace, Map<Long, int[]> handlers,
            String modelJson) {
        Map<Integer, Integer> busiest = new TreeMap<>();
        for (int[] what : handlers.values()) {
            busiest.merge(what[0], what[1], Math::max);
        }
        java.util.Set<Long> ticking = new java.util.HashSet<>();
        for (var entry : handlers.entrySet()) {
            int[] what = entry.getValue();
            Integer most = busiest.get(what[0]);
            if (most != null && what[1] * 1000L >= most) {
                ticking.add(entry.getKey());
            }
        }
        Map<Integer, List<Integer>> machine = new TreeMap<>();
        for (Traced write : trace) {
            if (ticking.contains(write.pc())) {
                machine.computeIfAbsent(write.register(), k -> new ArrayList<>())
                        .add(write.value());
            }
        }
        Map<Integer, List<Integer>> model = modelByRegister(modelJson);

        StringBuilder out = new StringBuilder();
        for (var entry : machine.entrySet()) {
            List<Integer> got = entry.getValue();
            List<Integer> want = model.getOrDefault(entry.getKey(), List.of());
            int n = Math.min(got.size(), want.size());
            int agree = 0;
            while (agree < n && got.get(agree).equals(want.get(agree))) {
                agree++;
            }
            int differ = 0;
            for (int i = 0; i < n; i++) {
                if (!got.get(i).equals(want.get(i))) {
                    differ++;
                }
            }
            out.append(String.format(
                    "  R%-2d machine %6d ticks, model %6d; %d compared,"
                    + " %d agree from the start, %d differ (%.2f%%)%n",
                    entry.getKey(), got.size(), want.size(), n, agree, differ,
                    n == 0 ? 0.0 : 100.0 * differ / n));
        }
        return out.toString();
    }

    /** The model's tick values, gathered by the register each one writes. */
    static Map<Integer, List<Integer>> modelByRegister(String json) {
        Map<Integer, List<Integer>> values = new TreeMap<>();
        Matcher pair = Pattern.compile("\\[(\\d+),(\\d+)\\]").matcher(json);
        Matcher ticks = Pattern.compile("\"t\":\\[(.*?)\\]\\}(?=,\\{\"result\"|\\]\\})")
                .matcher(json);
        while (ticks.find()) {
            pair.reset(ticks.group(1));
            while (pair.find()) {
                values.computeIfAbsent(Integer.parseInt(pair.group(1)),
                        k -> new ArrayList<>()).add(Integer.parseInt(pair.group(2)));
            }
        }
        return values;
    }

    /** The values one address wrote, in order - one timer's own tick record. */
    static List<Integer> sequence(List<Traced> trace, long pc) {
        List<Integer> values = new ArrayList<>();
        for (Traced write : trace) {
            if (write.pc() == pc) {
                values.add(write.value());
            }
        }
        return values;
    }
}
