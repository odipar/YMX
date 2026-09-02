package org.st4;

import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.TreeSet;

/**
 * The event-driven optimizer: the costs of {@link St4FastOptimizer} without
 * visiting every (position, offset) pair.
 *
 * <p>Between the start and end of a match run, and along a literal stretch,
 * every candidate's cost is a closed form of the position; only a run
 * starting or ending changes anything, and on repetitive data those are
 * orders of magnitude fewer than DP steps. So this class walks positions
 * taking three channel minima from range structures, the literal channel
 * keyed by state end, the rep channel by run start, the new-offset channel a
 * range minimum over recorded costs per gamma class, and does per-offset
 * work only at run boundaries, found through occurrence chains keyed by
 * (value, predecessor) and (value, successor). It reproduces the fast
 * optimizer's cost array exactly, which a test asserts; where candidates tie
 * the chain may differ, the packed size cannot. Per-event overhead loses
 * where runs are a step or two long, so {@link #optimize} counts the events
 * first and falls back to {@link St4FastOptimizer} on such data.
 */
public final class St4EventOptimizer {

    private static final int NONE = Integer.MIN_VALUE;

    /** Fall back to the plain DP when events exceed positions this many times. */
    private static final int CHURN = 8;

    private final int[] units;
    private final int literalBits;
    private final int offsetLimit;

    private final int[] optimalBits;
    private final byte[] winKind;
    private final int[] winOffset;
    private final int[] winAux;

    // Per offset: the state (best chain ending in its last match), the current
    // run's start and frozen literal key, NONE/-1 when absent.
    private final int[] stateS;
    private final int[] stateE;
    private final int[] runStartOf;
    private final int[] litKeyOf;

    // Channel structures: min-trees with per-slot sets where entries retire.
    private final SlotTree literalTree;      // by state end e (slot e+1)
    private final SlotTree repTree;          // by run start s
    private final MinTree costTree;          // recorded costs, argmin position
    private final PriorityQueue<Long> byteRuns = new PriorityQueue<>();
    private final PriorityQueue<Long> wordRuns = new PriorityQueue<>();

    // Occurrence chains: positions by (value, predecessor) and (value,
    // successor), newest first, for exact run start and end enumeration.
    private final HashMap<Integer, HashMap<Integer, Integer>> byPred = new HashMap<>();
    private final HashMap<Integer, HashMap<Integer, Integer>> bySucc = new HashMap<>();
    private final int[] predNext;
    private final int[] succNext;

    private St4EventOptimizer(int[] units, int unit, int offsetLimit) {
        this.units = units;
        this.literalBits = 8 * unit;
        this.offsetLimit = offsetLimit;
        int count = units.length;
        this.optimalBits = new int[count];
        this.winKind = new byte[count];
        this.winOffset = new int[count];
        this.winAux = new int[count];
        int width = (int) Math.clamp(count - 1L, 1, offsetLimit);
        this.stateS = new int[width + 1];
        this.stateE = new int[width + 1];
        this.runStartOf = new int[width + 1];
        this.litKeyOf = new int[width + 1];
        java.util.Arrays.fill(stateE, NONE);
        java.util.Arrays.fill(runStartOf, -1);
        this.literalTree = new SlotTree(count + 1);
        this.repTree = new SlotTree(count + 1);
        this.costTree = new MinTree(count);
        this.predNext = new int[count];
        this.succNext = new int[count];
    }

    /**
     * The last block of an optimal parse of {@code units}: the cost of
     * {@link St4FastOptimizer}, not always its chain. Falls back to the fast
     * optimizer when an event count says the DP would be faster.
     */
    public static St4Block optimize(int[] units, int unit, int offsetLimit,
                                    boolean progress) {
        var optimizer = new St4EventOptimizer(units, unit, offsetLimit);
        if (optimizer.countEvents() > (long) CHURN * units.length) {
            return St4FastOptimizer.optimize(units, unit, offsetLimit, progress);
        }
        optimizer.run(progress);
        return new St4ChainRebuilder(units, optimizer.literalBits, optimizer.optimalBits,
                optimizer.winKind, optimizer.winOffset, optimizer.winAux).rebuild();
    }

    /** As above, reporting progress on stdout. */
    public static St4Block optimize(int[] units, int unit, int offsetLimit) {
        return optimize(units, unit, offsetLimit, true);
    }

    /** The winning cost per position, for the equivalence tests. */
    static int[] costs(int[] units, int unit, int offsetLimit) {
        var optimizer = new St4EventOptimizer(units, unit, offsetLimit);
        optimizer.run(false);
        return optimizer.optimalBits;
    }

    private static int eliasGammaBits(int value) {
        return 2 * (31 - Integer.numberOfLeadingZeros(value)) + 1;
    }

    // ---------------------------------------------------------------- events

    /**
     * Run starts at j: offsets whose unit matches at j but not at j-1. Those
     * are the in-window occurrences p of units[j] whose predecessor differs
     * from units[j-1], or that have none, so the chains keyed by (value,
     * predecessor) enumerate exactly them, newest first, stopping at the
     * window's edge.
     */
    private interface RunEvent {
        void accept(int offset);
    }

    private void forEachRunStart(int j, RunEvent event) {
        HashMap<Integer, Integer> groups = byPred.get(units[j]);
        if (groups == null) {
            return;
        }
        Integer predecessor = units[j - 1];
        long lowest = Math.max(0, (long) j - offsetLimit);
        for (var group : groups.entrySet()) {
            if (predecessor.equals(group.getKey())) {
                continue;                       // those continue a run
            }
            for (int p = group.getValue(); p >= lowest; p = predNext[p]) {
                event.accept(j - p);
            }
        }
    }

    /** Run ends at e = j-1: matches at j-1 whose successor differs at j. */
    private void forEachRunEnd(int j, RunEvent event) {
        HashMap<Integer, Integer> groups = bySucc.get(units[j - 1]);
        if (groups == null) {
            return;
        }
        Integer successor = units[j];
        long lowest = Math.max(0, (long) (j - 1) - offsetLimit);
        for (var group : groups.entrySet()) {
            if (successor.equals(group.getKey())) {
                continue;                       // those keep matching
            }
            for (int p = group.getValue(); p >= lowest; p = succNext[p]) {
                event.accept(j - 1 - p);
            }
        }
    }

    /** Chains position j for future starts, and j-1 for future ends. */
    private void chain(int j) {
        Integer predecessor = j > 0 ? units[j - 1] : null;
        Integer old = byPred.computeIfAbsent(units[j], v -> new HashMap<>())
                .put(predecessor, j);
        predNext[j] = old == null ? Integer.MIN_VALUE : old;
        if (j > 0) {
            old = bySucc.computeIfAbsent(units[j - 1], v -> new HashMap<>())
                    .put(units[j], j - 1);
            succNext[j - 1] = old == null ? Integer.MIN_VALUE : old;
        }
    }

    /** One pass counting run events, to choose events or the plain DP. */
    private long countEvents() {
        long[] events = {0};
        for (int j = 0; j < units.length; j++) {
            if (j > 0) {
                forEachRunStart(j, offset -> events[0]++);
                forEachRunEnd(j, offset -> events[0]++);
            }
            chain(j);
        }
        // The pass filled the chains; the real run fills them again.
        byPred.clear();
        bySucc.clear();
        return events[0];
    }

    // ------------------------------------------------------------- the loop

    private void run(boolean progress) {
        int count = units.length;
        var meter = new ProgressMeter(
                ProgressMeter.totalSteps(count, 0, offsetLimit), progress);

        // The fake state every chain hangs from: offset one, before the
        // stream, as the reference seeds it.
        stateS[1] = -1;
        stateE[1] = -1;
        literalTree.insert(0, encode(-1 - (-1) * literalBits, 1));

        for (int j = 0; j < count; j++) {
            if (j > 0) {
                final int at = j;
                forEachRunEnd(j, offset -> endRun(offset, at - 1));
                forEachRunStart(j, offset -> startRun(offset, at));
            }

            int best = Integer.MAX_VALUE;
            byte kind = 0;
            int bestOffset = 0;
            int aux = 0;

            // Literal channel: one range-min per gamma class of the age j-e.
            for (int t = 0; (1L << t) <= j + 1; t++) {
                int lowest = j - (1 << (t + 1)) + 1;        // e range for this class
                int highest = j - (1 << t);
                long enc = literalTree.min(Math.max(0, lowest + 1), highest + 1);
                if (enc == Long.MAX_VALUE) {
                    continue;
                }
                int candidate = key(enc) + j * literalBits + 1 + (2 * t + 1);
                if (candidate < best) {
                    best = candidate;
                    kind = St4ChainRebuilder.LITERALS;
                    bestOffset = offset(enc);
                    aux = stateE[bestOffset];
                }
            }

            // Rep channel: the same, keyed by run start.
            for (int t = 0; (1L << t) <= j + 1; t++) {
                int lowest = j - (1 << (t + 1)) + 2;        // s range for this class
                int highest = j - (1 << t) + 1;
                if (highest < 1) {
                    continue;
                }
                long enc = repTree.min(Math.max(1, lowest), highest);
                if (enc == Long.MAX_VALUE) {
                    continue;
                }
                int candidate = key(enc) + 1 + (2 * t + 1);
                if (candidate < best) {
                    best = candidate;
                    kind = St4ChainRebuilder.REP;
                    bestOffset = offset(enc);
                    aux = runStartOf[bestOffset] - 1;
                }
            }

            // New-offset channel: range-mins over recorded costs, cut to the
            // longest active run of each offset class.
            long byteTop = top(byteRuns);
            long wordTop = top(wordRuns);
            int maxByte = byteTop == Long.MAX_VALUE ? 0 : j - (int) (byteTop >>> 16) + 1;
            int maxWord = wordTop == Long.MAX_VALUE ? 0 : j - (int) (wordTop >>> 16) + 1;
            for (int t = 0; ; t++) {
                int lenLo = (1 << t) + 1;
                if (lenLo > maxWord) {
                    break;
                }
                int lenHi = 1 << (t + 1);
                int gammaBits = 2 * t + 1;
                for (int half = 0; half < 2; half++) {
                    int reach = half == 0 ? Math.min(maxByte, lenHi)
                                          : Math.min(maxWord, lenHi);
                    if (reach < lenLo) {
                        continue;
                    }
                    long enc = costTree.min(j - reach, j - lenLo);
                    if (enc == Long.MAX_VALUE) {
                        continue;
                    }
                    int candidate = (int) (enc >>> 22) + gammaBits + 3
                            + (half == 0 ? 8 : 16);
                    if (candidate < best) {
                        best = candidate;
                        kind = St4ChainRebuilder.NEW;
                        long runTop = half == 0 ? byteTop : wordTop;
                        bestOffset = (int) (runTop & 0xFFFF);
                        aux = j - (int) (enc & 0x3FFFFF);   // the split length
                    }
                }
            }

            assert best != Integer.MAX_VALUE : "every position has a winner";
            optimalBits[j] = best;
            winKind[j] = kind;
            winOffset[j] = bestOffset;
            winAux[j] = aux;
            costTree.set(j, ((long) best << 22) | j);

            chain(j);
            meter.advance((int) Math.clamp((long) j, 1, offsetLimit));
        }
        meter.finish();
    }

    private void startRun(int offset, int start) {
        runStartOf[offset] = start;
        if (stateE[offset] != NONE) {
            int length = (start - 1) - stateE[offset];
            int litKey = stateS[offset] + 1 + eliasGammaBits(length)
                    + length * literalBits;
            litKeyOf[offset] = litKey;
            repTree.insert(start, encode(litKey, offset));
        } else {
            litKeyOf[offset] = NONE;
        }
        long entry = ((long) start << 16) | offset;
        wordRuns.add(entry);
        if (offset <= St4Format.BYTE_OFFSET_LIMIT) {
            byteRuns.add(entry);
        }
    }

    private void endRun(int offset, int end) {
        int start = runStartOf[offset];
        assert start >= 0 : "a run can only end after it started";
        int run = end - start + 1;
        int state = Integer.MAX_VALUE;
        if (litKeyOf[offset] != NONE) {
            repTree.remove(start, encode(litKeyOf[offset], offset));
            state = litKeyOf[offset] + 1 + eliasGammaBits(run);
        }
        if (run >= 2) {
            int core = bestSplit(end, run);
            if (core != Integer.MAX_VALUE) {
                state = Math.min(state, core + 3
                        + (offset > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8));
            }
        }
        if (state != Integer.MAX_VALUE) {
            if (stateE[offset] != NONE) {
                // The reference overwrites an offset's state at its next match
                // run whatever the cost; this does the same.
                literalTree.remove(stateE[offset] + 1,
                        encode(stateS[offset] - stateE[offset] * literalBits, offset));
            }
            literalTree.insert(end + 1, encode(state - end * literalBits, offset));
            stateS[offset] = state;
            stateE[offset] = end;
        }
        runStartOf[offset] = -1;
    }

    /** min over lengths 2..reach of cost[end-length] + gamma(length-1). */
    private int bestSplit(int end, int reach) {
        int best = Integer.MAX_VALUE;
        for (int t = 0; ; t++) {
            int lenLo = (1 << t) + 1;
            if (lenLo > reach) {
                break;
            }
            int lenHi = Math.min(reach, 1 << (t + 1));
            long enc = costTree.min(end - lenHi, end - lenLo);
            if (enc != Long.MAX_VALUE) {
                best = Math.min(best, (int) (enc >>> 22) + 2 * t + 1);
            }
        }
        return best;
    }

    private static long encode(int keyValue, int offset) {
        return ((long) keyValue << 16) | offset;
    }

    private static int key(long encoded) {
        return (int) (encoded >> 16);
    }

    private static int offset(long encoded) {
        return (int) (encoded & 0xFFFF);
    }

    /** The smallest valid (start, offset) entry; stale runs pop lazily. */
    private long top(PriorityQueue<Long> runs) {
        while (!runs.isEmpty()) {
            long entry = runs.peek();
            if (runStartOf[(int) (entry & 0xFFFF)] == (int) (entry >>> 16)) {
                return entry;
            }
            runs.poll();
        }
        return Long.MAX_VALUE;
    }

    // ----------------------------------------------------------- structures

    /** An iterative min segment tree over longs. */
    private static class MinTree {
        final long[] nodes;
        final int size;

        MinTree(int width) {
            size = Integer.highestOneBit(Math.max(2, width - 1)) * 2;
            nodes = new long[2 * size];
            java.util.Arrays.fill(nodes, Long.MAX_VALUE);
        }

        void set(int at, long value) {
            int node = at + size;
            nodes[node] = value;
            for (node >>= 1; node > 0; node >>= 1) {
                nodes[node] = Math.min(nodes[2 * node], nodes[2 * node + 1]);
            }
        }

        /** Minimum over the inclusive range, MAX_VALUE when empty or invalid. */
        long min(int from, int to) {
            if (from < 0) {
                from = 0;
            }
            if (to >= size) {
                to = size - 1;
            }
            long best = Long.MAX_VALUE;
            int lo = from + size;
            int hi = to + size + 1;
            while (lo < hi) {
                if ((lo & 1) != 0) {
                    best = Math.min(best, nodes[lo++]);
                }
                if ((hi & 1) != 0) {
                    best = Math.min(best, nodes[--hi]);
                }
                lo >>= 1;
                hi >>= 1;
            }
            return best;
        }
    }

    /** A min tree whose slots hold sets, so an entry can be removed. */
    private static final class SlotTree extends MinTree {
        private final HashMap<Integer, TreeSet<Long>> slots = new HashMap<>();

        SlotTree(int width) {
            super(width);
        }

        void insert(int slot, long entry) {
            TreeSet<Long> set = slots.computeIfAbsent(slot, s -> new TreeSet<>());
            set.add(entry);
            set(slot, set.first());
        }

        void remove(int slot, long entry) {
            TreeSet<Long> set = slots.get(slot);
            if (set != null) {
                set.remove(entry);
                set(slot, set.isEmpty() ? Long.MAX_VALUE : set.first());
            }
        }
    }
}
