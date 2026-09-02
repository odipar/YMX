package org.st4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import org.jspecify.annotations.Nullable;

/**
 * The optimizer for streams with copies from the literal stream: a search
 * over which units are literal, each step scored by an exact parse for that
 * choice and by what the compressor then writes, for as long as it is given.
 *
 * <p>A dictionary is a set of forced literals: they stay literal, a copy
 * comes only from them, the parse decides the rest. The opening passes, what
 * {@code st4 -c} alone writes, take the literals of a full-window parse,
 * fill holes of a few units, and shrink the dictionary to what gets copied
 * from. Given time, a sweep frees or trims every literal run, keeping what
 * packs smaller; then random moves free, seed, extend or trim runs, accepted
 * when they pack smaller and by annealing when they do not, and the search
 * returns to the best and sweeps again when it stalls. The parse is
 * {@link St4FastOptimizer}'s DP with copies added: sources found through
 * two-unit chains over the dictionary, the rep of a copy as a ring rep at
 * the same output distance with literal shadows at the source, the literal
 * channel a min-tree keyed by match end, chains rebuilt from a node pool,
 * and every parse restarted from a checkpoint before the first changed unit.
 * A copy is costed with the dictionary's own literal count, a lower bound,
 * so every copy is valid; the compressor's bits are the score.
 */
public final class St4LiteralCopySearch {

    private static final int NONE = Integer.MIN_VALUE;

    private static final byte LITERALS = 0;
    private static final byte REP = 1;          // a ring match reusing the last offset
    private static final byte NEW = 2;          // a ring match at a new offset
    private static final byte COPY = 3;         // a copy from the literal stream
    private static final byte COPYREP = 4;      // a rep of the last copy, after literals

    /**
     * Holes of up to this many units between dictionary runs are filled in
     * the opening passes.
     */
    private static final int HOLE = 3;

    /** The opening passes, at most. */
    private static final int PASSES = 4;

    /** The annealing temperature, in bits, at the start and at the end. */
    private static final double HOT = 10.0;
    private static final double COLD = 0.3;

    /** Steps without a new best before the search returns to the best. */
    private static final int PATIENCE = 2000;

    private St4LiteralCopySearch() {}

    private static int eliasGammaBits(int value) {
        return 2 * (31 - Integer.numberOfLeadingZeros(value)) + 1;
    }

    /**
     * Searches for {@code seconds}, zero for the opening passes alone, and
     * returns the best parse found: copies from the literal stream as
     * negative offsets, matches within {@code window} as positive ones.
     *
     * @param unit        bytes per unit
     * @param window      the furthest a match may reach back, in units
     * @param maxOpLength the compressor's operation limit, which the score
     *                    counts
     * @param progress    whether to report improvements on stdout
     */
    public static St4Block optimize(int[] units, int unit, int window, int maxOpLength,
                                    double seconds, boolean progress) {
        long deadline = System.nanoTime() + (long) (seconds * 1e9);
        return new Search(units, unit, window, maxOpLength, 1).run(deadline, Long.MAX_VALUE,
                progress);
    }

    /**
     * Searches for {@code steps} steps from {@code seed}, reproducibly; for
     * the tests.
     */
    static St4Block optimize(int[] units, int unit, int window, int maxOpLength, long steps,
                             long seed) {
        return new Search(units, unit, window, maxOpLength, seed).run(Long.MAX_VALUE, steps,
                false);
    }

    // ---------------------------------------------------------------- search

    private static final class Search {
        private final int[] units;
        private final int unit;
        private final int window;
        private final int maxOpLength;
        private final int count;
        private final Random random;
        private final Parser parser;

        // The incumbent: its dictionary is exactly its literals.
        private boolean[] forced;
        private St4Block chain;
        private int bits;
        private List<int[]> runs = List.of();         // literal runs {start, end, referenced}
        private List<int[]> copies = List.of();       // copies and matches {start, end, isCopy}

        private St4Block best;
        private int bestBits;

        // The budget: a deadline, a step count, and the steps taken.
        private long deadline;
        private long stepsAllowed;
        private long started;
        private long step;
        private long lastBest;
        private boolean progress;

        Search(int[] units, int unit, int window, int maxOpLength, long seed) {
            this.units = units;
            this.unit = unit;
            this.window = window;
            this.maxOpLength = maxOpLength;
            this.count = units.length;
            this.random = new Random(seed);
            this.parser = new Parser(units, unit, window);
            // The opening passes: the full-window parse's literals, holes
            // filled, shrunk to what gets copied from.
            int reach = St4Format.maxOffsetUnits(unit);
            boolean[] dictionary = filled(St4LiteralCopySearch.literalMask(
                    St4EventOptimizer.optimize(units, unit, reach, false), count));
            St4Block first = parser.parse(dictionary);
            chain = first;
            forced = St4LiteralCopySearch.literalMask(first, count);
            adopt(dictionary, first);
            best = chain;
            bestBits = bits;
            for (int pass = 1; pass < PASSES; pass++) {
                boolean[] next = filled(referenced());
                if (Arrays.equals(next, dictionary)) {
                    break;
                }
                dictionary = next;
                adopt(dictionary, parser.parse(dictionary));
                if (bits < bestBits) {
                    best = chain;
                    bestBits = bits;
                }
            }
            returnToBest();
        }

        /** Parses the best dictionary again, so the parser's base is the best. */
        private void returnToBest() {
            boolean[] dictionary = literalMask(best);
            adopt(dictionary, parser.parse(dictionary));
        }

        /**
         * Makes {@code parsed}, the parse of {@code dictionary} just made, the
         * incumbent, its own literals the dictionary from here on.
         */
        private void adopt(boolean[] dictionary, St4Block parsed) {
            parser.accept();
            chain = parsed;
            bits = St4Compressor.compress(parsed, units, unit, maxOpLength, -1, window).bits();
            forced = literalMask(parsed);
            runs = new ArrayList<>();
            copies = new ArrayList<>();
            boolean[] referenced = referenced();
            int previous = -1;
            for (St4Block block : blocks(parsed)) {
                int start = previous + 1;
                if (block.offset() == 0) {
                    int used = 0;
                    for (int p = start; p <= block.index(); p++) {
                        used |= referenced[p] ? 1 : 0;
                    }
                    runs.add(new int[] {start, block.index(), used});
                } else {
                    copies.add(new int[] {start, block.index(), block.offset() < 0 ? 1 : 0});
                }
                previous = block.index();
            }
        }

        /** The positions the incumbent's copies read from. */
        private boolean[] referenced() {
            boolean[] referenced = new boolean[count];
            int previous = -1;
            for (St4Block block : blocks(chain)) {
                if (block.offset() < 0) {
                    int distance = -block.offset();
                    for (int p = previous + 1; p <= block.index(); p++) {
                        referenced[p - distance] = true;
                    }
                }
                previous = block.index();
            }
            return referenced;
        }

        St4Block run(long deadline, long steps, boolean progress) {
            this.deadline = deadline;
            this.stepsAllowed = steps;
            this.progress = progress;
            started = System.nanoTime();
            long accepted = 0;
            if (progress) {
                report("start");
            }
            // Descend first: most of what the opening passes force packs
            // smaller free, and a sweep finds that run by run.
            sweep();
            while (!exhausted()) {
                double fraction = steps == Long.MAX_VALUE
                        ? (double) (System.nanoTime() - started) / Math.max(1, deadline - started)
                        : (double) step / steps;
                double temperature = HOT * Math.pow(COLD / HOT, Math.min(1.0, fraction));
                boolean[] proposal = forced.clone();
                String move = propose(proposal);
                St4Block parsed = parser.parse(proposal);
                int score = evaluate(parsed);
                int delta = score - bits;
                if (delta <= 0 || random.nextDouble() < Math.exp(-delta / temperature)) {
                    adopt(proposal, parsed);
                    accepted++;
                    noteBest(move);
                }
                if (step - lastBest > PATIENCE) {
                    // Stuck: back to the best, and descend from there again.
                    returnToBest();
                    sweep();
                    lastBest = step;
                }
            }
            if (progress) {
                System.out.printf("%d steps, %d accepted: %d bits, %d bytes%n", step, accepted,
                        bestBits, (bestBits + 7) / 8);
            }
            return best;
        }

        private boolean exhausted() {
            return step >= stepsAllowed || (step % 8 == 0 && System.nanoTime() >= deadline);
        }

        /** Scores a parse: the compressor's bits. A step of the budget. */
        private int evaluate(St4Block parsed) {
            step++;
            return St4Compressor.compress(parsed, units, unit, maxOpLength, -1, window).bits();
        }

        private void noteBest(String move) {
            if (bits < bestBits) {
                best = chain;
                bestBits = bits;
                lastBest = step;
                if (progress) {
                    report(move);
                }
            }
        }

        /**
         * Greedy descent: every literal run of the incumbent, in a random
         * order, freed whole and trimmed at either end, keeping each change
         * that packs smaller.
         */
        private void sweep() {
            var order = new ArrayList<>(runs);
            for (int i = order.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int[] swap = order.get(i);
                order.set(i, order.get(j));
                order.set(j, swap);
            }
            for (int[] run : order) {
                if (exhausted()) {
                    return;
                }
                int start = run[0];
                int end = run[1];
                if (!forced[start] && !forced[end]) {
                    continue;                           // gone already
                }
                if (improve(start, end + 1, "sweep free")) {
                    continue;
                }
                if (end > start) {
                    if (!improve(start, start + 1, "sweep trim")) {
                        improve(end, end + 1, "sweep trim");
                    }
                }
            }
        }

        /** Frees {@code [from, to)} when that packs smaller. */
        private boolean improve(int from, int to, String move) {
            boolean[] proposal = forced.clone();
            Arrays.fill(proposal, from, to, false);
            St4Block parsed = parser.parse(proposal);
            int score = evaluate(parsed);
            if (score < bits) {
                adopt(proposal, parsed);
                noteBest(move);
                return true;
            }
            return false;
        }

        private void report(String move) {
            System.out.printf("%7.1fs %8d steps: %d bits, %d bytes  (%s)%n",
                    (System.nanoTime() - started) / 1e9, step, bestBits, (bestBits + 7) / 8, move);
        }

        /** Changes the dictionary in place, and says how. */
        private String propose(boolean[] dictionary) {
            int kind = random.nextInt(20);
            if (kind < 6) {
                free(dictionary);
                return "free";
            } else if (kind < 12) {
                seed(dictionary);
                return "seed";
            } else if (kind < 15) {
                extend(dictionary);
                return "extend";
            } else if (kind < 18) {
                trim(dictionary);
                return "trim";
            } else {
                free(dictionary);
                seed(dictionary);
                return "free+seed";
            }
        }

        /** A literal run, unreferenced ones four times as likely, or none. */
        private int @Nullable [] pickRun() {
            if (runs.isEmpty()) {
                return null;
            }
            for (int attempt = 0; attempt < 4; attempt++) {
                int[] run = runs.get(random.nextInt(runs.size()));
                if (run[2] == 0 || random.nextInt(4) == 0) {
                    return run;
                }
            }
            return runs.get(random.nextInt(runs.size()));
        }

        /** Frees a literal run, or part of one, for the parse to match. */
        private void free(boolean[] dictionary) {
            int[] run = pickRun();
            if (run == null) {
                return;
            }
            int length = run[1] - run[0] + 1;
            if (random.nextBoolean()) {
                Arrays.fill(dictionary, run[0], run[1] + 1, false);
            } else {
                int size = 1 + random.nextInt(Math.min(length, 8));
                int start = run[0] + random.nextInt(length - size + 1);
                Arrays.fill(dictionary, start, start + size, false);
            }
        }

        /** Forces literals where a copy or match sits, so later copies can come from there. */
        private void seed(boolean[] dictionary) {
            if (copies.isEmpty()) {
                return;
            }
            int[] op = copies.get(random.nextInt(copies.size()));
            for (int attempt = 0; attempt < 3 && op[2] == 0 && random.nextInt(4) != 0; attempt++) {
                op = copies.get(random.nextInt(copies.size()));       // prefer copies
            }
            int length = op[1] - op[0] + 1;
            int size = random.nextBoolean() ? Math.min(length, 32) : 1 + random.nextInt(Math.min(length, 12));
            int start = op[0] + (random.nextBoolean() ? 0 : random.nextInt(length - size + 1));
            Arrays.fill(dictionary, start, start + size, true);
        }

        /** Grows a literal run past its end by a few units. */
        private void extend(boolean[] dictionary) {
            int[] run = pickRun();
            if (run == null) {
                return;
            }
            int size = 1 + random.nextInt(8);
            if (random.nextBoolean()) {
                Arrays.fill(dictionary, run[1] + 1, Math.min(count, run[1] + 1 + size), true);
            } else {
                Arrays.fill(dictionary, Math.max(0, run[0] - size), run[0], true);
            }
        }

        /** Shortens a literal run at either end by a unit or a few. */
        private void trim(boolean[] dictionary) {
            int[] run = pickRun();
            if (run == null) {
                return;
            }
            int length = run[1] - run[0] + 1;
            int size = 1 + random.nextInt(Math.min(length, 3));
            if (random.nextBoolean()) {
                Arrays.fill(dictionary, run[1] + 1 - size, run[1] + 1, false);
            } else {
                Arrays.fill(dictionary, run[0], run[0] + size, false);
            }
        }

        private boolean[] literalMask(St4Block parsed) {
            return St4LiteralCopySearch.literalMask(parsed, count);
        }
    }

    /** The blocks of a chain, first block first. */
    static List<St4Block> blocks(St4Block chain) {
        var list = new java.util.ArrayList<St4Block>();
        for (St4Block block = chain; block != null && block.index() >= 0; block = block.chain()) {
            list.add(block);
        }
        java.util.Collections.reverse(list);
        return list;
    }

    static boolean[] literalMask(St4Block chain, int count) {
        boolean[] literal = new boolean[count];
        int previous = -1;
        for (St4Block block : blocks(chain)) {
            if (block.offset() == 0) {
                for (int p = previous + 1; p <= block.index(); p++) {
                    literal[p] = true;
                }
            }
            previous = block.index();
        }
        return literal;
    }

    private static boolean[] filled(boolean[] dictionary) {
        boolean[] result = dictionary.clone();
        int run = 0;
        for (int p = 0; p < result.length; p++) {
            if (result[p]) {
                if (run > 0 && run <= HOLE) {
                    Arrays.fill(result, p - run, p, true);
                }
                run = 0;
            } else {
                run++;
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- parser

    /**
     * The exact parse for one dictionary, on arrays reused across calls. Ring
     * offsets 1..window and copy distances window+1..count-1 share one state
     * index space and never meet.
     */
    static final class Parser {
        private final int[] units;
        private final int count;
        private final int literalBits;
        private final int window;
        private final int reach;

        // Per state index: the best chain ending in a match or copy there,
        // its cost, end and how to rebuild it, and its literal extension.
        private final int[] stateBits;
        private final int[] stateEnd;
        private final byte[] stateKind;
        private final int[] stateAux;
        private final int[] statePred;
        private final int[] stateNode;
        private final int[] litBits;
        private final int[] litEnd;
        private final int[] litNode;
        private final int[] matchLength;
        private final int[] stamp;

        // Per position: the winner, and the best match or copy ending there.
        private final int[] optimalBits;
        private final int[] winNode;
        private final int[] matchNodeSlot;         // by end + 1, so -1 has a slot
        private final int[] bestLength;

        // The dictionary as prefix counts, and the input's two-unit chains:
        // the previous position with the same two units, the same for every
        // dictionary.
        private final int[] forcedBefore;
        private final int[] prevSame2;
        private boolean[] forced = new boolean[0];

        // Distances visited at the previous position, whose runs may end at
        // this one, and distances whose last copy could still be repped.
        private int[] activePrev;
        private int[] activeCur;
        private int activePrevCount;
        private int activeCurCount;
        private final int[] repable;
        private final boolean[] inRepable;
        private int repableCount;

        // The position being parsed, and its best match or copy so far.
        private int bestMatch;
        private int bestMatchIdx;
        private int bestLengthSize;

        // The node pool.
        private byte[] nodeKind = new byte[1024];
        private int[] nodeEnd = new int[1024];
        private int[] nodeOffset = new int[1024];
        private int[] nodeAux = new int[1024];
        private int[] nodePred = new int[1024];
        private int[] nodeBits = new int[1024];
        private int nodes;

        // The literal channel: a min-tree by match end + 1 over bits - end*literalBits.
        private final int half;
        private final long[] tree;

        // Checkpoints: the state before position k*checkpoint, for the base
        // dictionary, the last parse accepted, and for the parse under way.
        // A parse restarts from the last checkpoint before its dictionary
        // first differs from the base's, since nothing before depends on
        // what comes after. Nodes are appended past the base's, so a
        // rejected parse leaves the base's intact.
        private final int checkpoint;
        private final Snapshot[] base;
        private final Snapshot[] proposal;
        private boolean[] baseForced = new boolean[0];
        private boolean hasBase;
        private int poolTop;
        private int sharedUpTo;                    // checkpoints the parse under way shares
        private boolean fresh;                     // the parse under way started from scratch
        private int fullNodes;                     // the nodes a parse from scratch takes

        Parser(int[] units, int unit, int window) {
            this.units = units;
            this.count = units.length;
            this.literalBits = 8 * unit;
            this.window = window;
            this.reach = St4Format.maxOffsetUnits(unit);
            int size = Math.max(count, window) + 1;
            stateBits = new int[size];
            stateEnd = new int[size];
            stateKind = new byte[size];
            stateAux = new int[size];
            statePred = new int[size];
            stateNode = new int[size];
            litBits = new int[size];
            litEnd = new int[size];
            litNode = new int[size];
            matchLength = new int[size];
            stamp = new int[size];
            optimalBits = new int[count];
            winNode = new int[count];
            matchNodeSlot = new int[count + 1];
            bestLength = new int[Math.max(count, 3)];
            forcedBefore = new int[count + 1];
            prevSame2 = new int[count];
            activePrev = new int[size];
            activeCur = new int[size];
            repable = new int[size];
            inRepable = new boolean[size];
            var last = new HashMap<Long, Integer>();
            for (int p = 0; p + 1 < count; p++) {
                long key = ((long) units[p] << 32) | (units[p + 1] & 0xFFFFFFFFL);
                @Nullable Integer previous = last.put(key, p);
                prevSame2[p] = previous == null ? -1 : previous;
            }
            if (count > 0) {
                prevSame2[count - 1] = -1;
            }
            int h = 1;
            while (h < count + 1) {
                h <<= 1;
            }
            half = h;
            tree = new long[2 * h];
            checkpoint = Math.max(1024, (count + 7) / 8);
            int slots = (count + checkpoint - 1) / checkpoint;
            base = new Snapshot[slots];
            proposal = new Snapshot[slots];
            for (int k = 0; k < slots; k++) {
                base[k] = new Snapshot(size, count);
                proposal[k] = new Snapshot(size, count);
            }
        }

        /** A parse's whole state before a checkpoint position. */
        private static final class Snapshot {
            final int[] stateBits;
            final int[] stateEnd;
            final byte[] stateKind;
            final int[] stateAux;
            final int[] statePred;
            final int[] stateNode;
            final int[] litBits;
            final int[] litEnd;
            final int[] litNode;
            final int[] matchLength;
            final int[] stamp;
            final int[] optimalBits;
            final int[] winNode;
            final int[] matchNodeSlot;
            final long[] leaves;
            final int[] activePrev;
            final int[] activeCur;
            final int[] repable;
            int activePrevCount;
            int activeCurCount;
            int repableCount;
            int nodes;
            boolean valid;

            Snapshot(int size, int count) {
                stateBits = new int[size];
                stateEnd = new int[size];
                stateKind = new byte[size];
                stateAux = new int[size];
                statePred = new int[size];
                stateNode = new int[size];
                litBits = new int[size];
                litEnd = new int[size];
                litNode = new int[size];
                matchLength = new int[size];
                stamp = new int[size];
                optimalBits = new int[count];
                winNode = new int[count];
                matchNodeSlot = new int[count + 1];
                leaves = new long[count + 1];
                activePrev = new int[size];
                activeCur = new int[size];
                repable = new int[size];
            }
        }

        St4Block parse(boolean[] dictionary) {
            int from = 0;
            if (hasBase) {
                from = count - 1;
                for (int p = 0; p < count; p++) {
                    if (dictionary[p] != baseForced[p]) {
                        from = p;
                        break;
                    }
                }
            }
            int slot = from / checkpoint;
            while (slot > 0 && !base[slot].valid) {
                slot--;
            }
            sharedUpTo = slot;
            fresh = slot == 0;
            forced = dictionary;
            int start = slot * checkpoint;
            if (slot == 0) {
                prepare();
            } else {
                restore(base[slot]);
            }
            for (int p = start; p < count; p++) {
                forcedBefore[p + 1] = forcedBefore[p] + (forced[p] ? 1 : 0);
            }
            for (int index = start; index < count; index++) {
                if (index > 0 && index % checkpoint == 0) {
                    snapshot(proposal[index / checkpoint], index);
                }
                boolean literalOnly = forced[index];
                int value = units[index];
                bestLengthSize = 2;

                // The literal channel: the best match or copy end, per gamma
                // class of the run length that reaches here from it.
                int litCand = Integer.MAX_VALUE;
                int litE = 0;
                for (int k = 0; ; k++) {
                    int slotHi = index - (1 << k) + 1;
                    if (slotHi < 0) {
                        break;
                    }
                    int slotLo = Math.max(0, index - (2 << k) + 2);
                    long found = query(slotLo, slotHi);
                    if (found != Long.MAX_VALUE) {
                        int candidate = (int) (found >> 32) + index * literalBits + 2 + 2 * k;
                        if (candidate < litCand) {
                            litCand = candidate;
                            litE = (int) found - 1;
                        }
                    }
                }

                bestMatch = Integer.MAX_VALUE;
                bestMatchIdx = -1;

                // Ring offsets: the reference DP.
                int maxOffset = (int) Math.clamp((long) index, St4Optimizer.INITIAL_OFFSET, window);
                for (int offset = 1; offset <= maxOffset; offset++) {
                    if (!literalOnly && index != 0 && value == units[index - offset]) {
                        if (litEnd[offset] != NONE) {
                            if (matchLength[offset] == 0) {
                                litNode[offset] = newNode(LITERALS, litEnd[offset], 0,
                                        stateEnd[offset], node(offset), litBits[offset]);
                            }
                            int bits = litBits[offset] + 1
                                    + eliasGammaBits(index - litEnd[offset]);
                            setState(offset, bits, index, REP, 0, litNode[offset]);
                            if (bits < bestMatch) {
                                bestMatch = bits;
                                bestMatchIdx = offset;
                            }
                        }
                        if (++matchLength[offset] > 1) {
                            bestLengthSize = extendBestLength(bestLengthSize, matchLength[offset],
                                    index);
                            int length = bestLength[matchLength[offset]];
                            int bits = optimalBits[index - length] + 3
                                    + (offset > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8)
                                    + eliasGammaBits(length - 1);
                            if (stateEnd[offset] != index || stateBits[offset] > bits) {
                                setState(offset, bits, index, NEW, length, winNode[index - length]);
                                if (bits < bestMatch) {
                                    bestMatch = bits;
                                    bestMatchIdx = offset;
                                }
                            }
                        }
                    } else {
                        matchLength[offset] = 0;
                        if (stateEnd[offset] != NONE) {
                            int length = index - stateEnd[offset];
                            litBits[offset] = stateBits[offset] + 1 + eliasGammaBits(length)
                                    + length * literalBits;
                            litEnd[offset] = index;
                        }
                    }
                }

                // Copies. A copy needs two units, so the two-unit chain finds
                // the runs, restricted to dictionary pairs beyond the window;
                // a run in progress the chain no longer lists ends here and is
                // visited for its last unit; a distance whose last copy could
                // still be repped is visited wherever its unit matches, since
                // a rep may be one unit.
                int[] swap = activePrev;
                activePrev = activeCur;
                activePrevCount = activeCurCount;
                activeCur = swap;
                activeCurCount = 0;
                if (!literalOnly) {
                    if (index + 1 < count) {
                        for (int p = prevSame2[index]; p >= 0; p = prevSame2[p]) {
                            if (forced[p] && forced[p + 1] && index - p > window) {
                                visit(index, index - p);
                            }
                        }
                    }
                    for (int a = 0; a < activePrevCount; a++) {
                        int distance = activePrev[a];
                        if (stamp[distance] == index - 1 && value == units[index - distance]
                                && forced[index - distance]) {
                            visit(index, distance);
                        }
                    }
                    for (int r = 0; r < repableCount; r++) {
                        int distance = repable[r];
                        if (stamp[distance] != index && value == units[index - distance]
                                && forced[index - distance]) {
                            visit(index, distance);
                        }
                    }
                }
                // A distance stays reppable while the literals since its copy
                // have literal shadows at the source.
                for (int r = repableCount - 1; r >= 0; r--) {
                    int distance = repable[r];
                    if (stateEnd[distance] < index && stamp[distance] != index
                            && !forced[index - distance]) {
                        inRepable[distance] = false;
                        repable[r] = repable[--repableCount];
                    }
                }

                // The winner, and the literal channel's next entry.
                if (bestMatch < litCand) {
                    optimalBits[index] = bestMatch;
                    winNode[index] = node(bestMatchIdx);
                } else {
                    assert litCand != Integer.MAX_VALUE : "a literal run always reaches";
                    optimalBits[index] = litCand;
                    winNode[index] = newNode(LITERALS, index, 0, litE, matchNodeSlot[litE + 1],
                            litCand);
                }
                if (bestMatch != Integer.MAX_VALUE) {
                    matchNodeSlot[index + 1] = node(bestMatchIdx);
                    update(index + 1, ((long) (bestMatch - index * literalBits) << 32)
                            | (index + 1));
                }
            }
            return rebuild(winNode[count - 1]);
        }

        /**
         * A copy distance whose unit matches at {@code index} with the source
         * in the dictionary: continues or starts its run, and enters the rep
         * of the last copy at that distance and the copy ending here.
         */
        private void visit(int index, int distance) {
            int p = index - distance;
            if (stamp[distance] != index - 1) {
                // A run starts. Its rep continues the last copy at this
                // distance when the literals since have literal shadows at
                // the source.
                matchLength[distance] = 1;
                litNode[distance] = -1;
                if (stateEnd[distance] != NONE) {
                    int end = stateEnd[distance];
                    int between = index - 1 - end;
                    if (forcedBefore[p] - forcedBefore[end - distance + 1] == between) {
                        int bits = stateBits[distance] + 1 + eliasGammaBits(between)
                                + between * literalBits;
                        litBits[distance] = bits;
                        litEnd[distance] = index - 1;
                        litNode[distance] = newNode(LITERALS, index - 1, 0, end,
                                node(distance), bits);
                    }
                }
            } else {
                matchLength[distance]++;
            }
            stamp[distance] = index;
            activeCur[activeCurCount++] = distance;
            int run = matchLength[distance];
            if (litNode[distance] >= 0) {
                int bits = litBits[distance] + 1 + eliasGammaBits(run);
                setState(distance, bits, index, COPYREP, 0, litNode[distance]);
                if (bits < bestMatch) {
                    bestMatch = bits;
                    bestMatchIdx = distance;
                }
            }
            if (run > 1) {
                // Literals from the source's last unit to here: a copy of n
                // units reads back n - 1 more and leaves at least one literal
                // between.
                int between = forcedBefore[index] - forcedBefore[p];
                if (between < 2) {
                    return;
                }
                int longest = Math.min(run, reach - window - between + 1);
                if (longest < 2) {
                    return;
                }
                bestLengthSize = extendBestLength(bestLengthSize, longest, index);
                int length = bestLength[longest];
                int bits = optimalBits[index - length] + 3
                        + (window + between + length - 1 > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8)
                        + eliasGammaBits(length - 1);
                int byteLongest = Math.min(longest,
                        St4Format.BYTE_OFFSET_LIMIT + 1 - window - between);
                if (byteLongest >= 2 && byteLongest < longest) {
                    int shorter = bestLength[byteLongest];
                    int shorterBits = optimalBits[index - shorter] + 3 + 8
                            + eliasGammaBits(shorter - 1);
                    if (shorterBits < bits) {
                        bits = shorterBits;
                        length = shorter;
                    }
                }
                if (stateEnd[distance] != index || stateBits[distance] > bits) {
                    setState(distance, bits, index, COPY, length, winNode[index - length]);
                    if (bits < bestMatch) {
                        bestMatch = bits;
                        bestMatchIdx = distance;
                    }
                }
            }
        }

        /**
         * Makes the parse just made the base for the ones to come: its
         * checkpoints stand, its nodes are kept, and the next parse is
         * compared against its dictionary.
         */
        void accept() {
            settle();
            if (poolTop > 4L * fullNodes + 65536) {
                // The pool holds the tails of every parse since the last full
                // one; one full parse of the base compacts it. The limit is a
                // multiple of what a full parse takes, so the compaction does
                // not find the pool too big again.
                hasBase = false;
                poolTop = 0;
                parse(baseForced);
                settle();
            }
        }

        /** The parse just made becomes the base. */
        private void settle() {
            for (int m = sharedUpTo + 1; m < base.length; m++) {
                Snapshot kept = base[m];
                base[m] = proposal[m];
                proposal[m] = kept;
                base[m].valid = true;
            }
            baseForced = forced.clone();
            hasBase = true;
            if (fresh) {
                fullNodes = nodes - poolTop;
            }
            poolTop = nodes;
        }

        private void snapshot(Snapshot into, int position) {
            System.arraycopy(stateBits, 0, into.stateBits, 0, stateBits.length);
            System.arraycopy(stateEnd, 0, into.stateEnd, 0, stateEnd.length);
            System.arraycopy(stateKind, 0, into.stateKind, 0, stateKind.length);
            System.arraycopy(stateAux, 0, into.stateAux, 0, stateAux.length);
            System.arraycopy(statePred, 0, into.statePred, 0, statePred.length);
            System.arraycopy(stateNode, 0, into.stateNode, 0, stateNode.length);
            System.arraycopy(litBits, 0, into.litBits, 0, litBits.length);
            System.arraycopy(litEnd, 0, into.litEnd, 0, litEnd.length);
            System.arraycopy(litNode, 0, into.litNode, 0, litNode.length);
            System.arraycopy(matchLength, 0, into.matchLength, 0, matchLength.length);
            System.arraycopy(stamp, 0, into.stamp, 0, stamp.length);
            System.arraycopy(optimalBits, 0, into.optimalBits, 0, position);
            System.arraycopy(winNode, 0, into.winNode, 0, position);
            System.arraycopy(matchNodeSlot, 0, into.matchNodeSlot, 0, position + 1);
            System.arraycopy(tree, half, into.leaves, 0, position + 1);
            System.arraycopy(activePrev, 0, into.activePrev, 0, activePrevCount);
            System.arraycopy(activeCur, 0, into.activeCur, 0, activeCurCount);
            System.arraycopy(repable, 0, into.repable, 0, repableCount);
            into.activePrevCount = activePrevCount;
            into.activeCurCount = activeCurCount;
            into.repableCount = repableCount;
            into.nodes = nodes;
            into.valid = true;
        }

        private void restore(Snapshot from) {
            nodes = poolTop;
            System.arraycopy(from.stateBits, 0, stateBits, 0, stateBits.length);
            System.arraycopy(from.stateEnd, 0, stateEnd, 0, stateEnd.length);
            System.arraycopy(from.stateKind, 0, stateKind, 0, stateKind.length);
            System.arraycopy(from.stateAux, 0, stateAux, 0, stateAux.length);
            System.arraycopy(from.statePred, 0, statePred, 0, statePred.length);
            System.arraycopy(from.stateNode, 0, stateNode, 0, stateNode.length);
            System.arraycopy(from.litBits, 0, litBits, 0, litBits.length);
            System.arraycopy(from.litEnd, 0, litEnd, 0, litEnd.length);
            System.arraycopy(from.litNode, 0, litNode, 0, litNode.length);
            System.arraycopy(from.matchLength, 0, matchLength, 0, matchLength.length);
            System.arraycopy(from.stamp, 0, stamp, 0, stamp.length);
            int position = sharedUpTo * checkpoint;
            System.arraycopy(from.optimalBits, 0, optimalBits, 0, position);
            System.arraycopy(from.winNode, 0, winNode, 0, position);
            System.arraycopy(from.matchNodeSlot, 0, matchNodeSlot, 0, position + 1);
            Arrays.fill(tree, Long.MAX_VALUE);
            System.arraycopy(from.leaves, 0, tree, half, position + 1);
            for (int i = half - 1; i >= 1; i--) {
                tree[i] = Math.min(tree[2 * i], tree[2 * i + 1]);
            }
            System.arraycopy(from.activePrev, 0, activePrev, 0, from.activePrevCount);
            System.arraycopy(from.activeCur, 0, activeCur, 0, from.activeCurCount);
            activePrevCount = from.activePrevCount;
            activeCurCount = from.activeCurCount;
            for (int r = 0; r < repableCount; r++) {
                inRepable[repable[r]] = false;
            }
            System.arraycopy(from.repable, 0, repable, 0, from.repableCount);
            repableCount = from.repableCount;
            for (int r = 0; r < repableCount; r++) {
                inRepable[repable[r]] = true;
            }
            bestLength[2] = 2;
        }

        private void prepare() {
            Arrays.fill(stateEnd, NONE);
            Arrays.fill(litEnd, NONE);
            Arrays.fill(litNode, -1);
            Arrays.fill(stateNode, -1);
            Arrays.fill(matchLength, 0);
            Arrays.fill(stamp, -2);
            Arrays.fill(tree, Long.MAX_VALUE);
            bestLength[2] = 2;
            nodes = poolTop;
            // The fake block every chain hangs from: one unit back, ending
            // before the stream, costing -1 so the first flag is free.
            int root = newNode(NEW, -1, St4Optimizer.INITIAL_OFFSET, 0, -1, -1);
            stateBits[St4Optimizer.INITIAL_OFFSET] = -1;
            stateEnd[St4Optimizer.INITIAL_OFFSET] = -1;
            stateKind[St4Optimizer.INITIAL_OFFSET] = NEW;
            stateNode[St4Optimizer.INITIAL_OFFSET] = root;
            matchNodeSlot[0] = root;
            update(0, ((long) (literalBits - 1) << 32));
            activePrevCount = 0;
            activeCurCount = 0;
            for (int r = 0; r < repableCount; r++) {
                inRepable[repable[r]] = false;
            }
            repableCount = 0;
        }

        private int extendBestLength(int size, int target, int index) {
            if (size < target) {
                int bits = optimalBits[index - bestLength[size]] + eliasGammaBits(bestLength[size] - 1);
                do {
                    size++;
                    int shorterBits = optimalBits[index - size] + eliasGammaBits(size - 1);
                    if (shorterBits <= bits) {
                        bestLength[size] = size;
                        bits = shorterBits;
                    } else {
                        bestLength[size] = bestLength[size - 1];
                    }
                } while (size < target);
            }
            return size;
        }

        private void setState(int idx, int bits, int end, byte kind, int aux, int pred) {
            stateBits[idx] = bits;
            stateEnd[idx] = end;
            stateKind[idx] = kind;
            stateAux[idx] = aux;
            statePred[idx] = pred;
            stateNode[idx] = -1;
            if (idx > window && !inRepable[idx]) {
                inRepable[idx] = true;
                repable[repableCount++] = idx;
            }
        }

        /** The state's node, made when first needed. */
        private int node(int idx) {
            if (stateNode[idx] < 0) {
                stateNode[idx] = newNode(stateKind[idx], stateEnd[idx],
                        idx <= window ? idx : -idx, stateAux[idx], statePred[idx], stateBits[idx]);
            }
            return stateNode[idx];
        }

        private int newNode(byte kind, int end, int offset, int aux, int pred, int bits) {
            if (nodes == nodeKind.length) {
                int grown = nodes * 2;
                nodeKind = Arrays.copyOf(nodeKind, grown);
                nodeEnd = Arrays.copyOf(nodeEnd, grown);
                nodeOffset = Arrays.copyOf(nodeOffset, grown);
                nodeAux = Arrays.copyOf(nodeAux, grown);
                nodePred = Arrays.copyOf(nodePred, grown);
                nodeBits = Arrays.copyOf(nodeBits, grown);
            }
            nodeKind[nodes] = kind;
            nodeEnd[nodes] = end;
            nodeOffset[nodes] = offset;
            nodeAux[nodes] = aux;
            nodePred[nodes] = pred;
            nodeBits[nodes] = bits;
            return nodes++;
        }

        private St4Block rebuild(int last) {
            var order = new ArrayList<Integer>();
            for (int node = last; node >= 0; node = nodePred[node]) {
                order.add(node);
            }
            St4Block chain = new St4Block(-1, -1, St4Optimizer.INITIAL_OFFSET, null);
            for (int i = order.size() - 2; i >= 0; i--) {
                int node = order.get(i);
                chain = new St4Block(nodeBits[node], nodeEnd[node],
                        nodeKind[node] == LITERALS ? 0 : nodeOffset[node], chain);
            }
            return chain;
        }

        private void update(int slot, long value) {
            int i = half + slot;
            tree[i] = value;
            for (i >>= 1; i >= 1; i >>= 1) {
                tree[i] = Math.min(tree[2 * i], tree[2 * i + 1]);
            }
        }

        private long query(int lo, int hi) {
            long result = Long.MAX_VALUE;
            int l = half + lo;
            int r = half + hi + 1;
            while (l < r) {
                if ((l & 1) == 1) {
                    result = Math.min(result, tree[l++]);
                }
                if ((r & 1) == 1) {
                    result = Math.min(result, tree[--r]);
                }
                l >>= 1;
                r >>= 1;
            }
            return result;
        }
    }
}
