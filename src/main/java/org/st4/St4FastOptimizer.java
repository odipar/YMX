package org.st4;


/**
 * {@link St4Optimizer}, restructured to not allocate: the same parse, found the
 * same way, producing byte-identical output - measured at a fraction of the
 * time.
 *
 * <p>The original walks the same dynamic program but materialises every
 * candidate as an {@link St4Block}, and nearly all of them lose and become
 * garbage: packing a 300 KB asset was measured allocating 37 GB of blocks.
 * This version runs in two passes:
 *
 * <ol>
 *   <li><b>Forward</b>, the identical DP on primitive arrays: per offset, the
 *       cost and end of the best chain ending in a match and in a literal run;
 *       per position, the winning cost and a three-int descriptor of which
 *       candidate won - its kind, its offset, and the one value that cannot be
 *       recomputed later.</li>
 *   <li><b>Backward</b>, chain reconstruction: only the blocks the winning
 *       parse actually contains are built, by replaying each recorded winner's
 *       local decision from the descriptor, the winning costs, and the data
 *       itself. A match run's extent and the previous match at an offset are
 *       found by scanning the units, which costs what the chain covers; the
 *       best split of a new-offset match is re-derived from the same recorded
 *       costs the forward pass minimised over.</li>
 * </ol>
 *
 * <p>The candidates are evaluated in the same order with the same
 * strictly-better replacement rule, so ties fall exactly as in the original
 * and the output is byte-identical - which the equivalence test asserts, and
 * which is the reason {@link St4Optimizer} stays in the tree: it is the
 * specification this class is checked against.
 */
public final class St4FastOptimizer {

    /** The offset a stream starts with, as ZX1: one unit. */
    public static final int INITIAL_OFFSET = St4Optimizer.INITIAL_OFFSET;

    /** No state, and no literal run: nothing has happened at this offset yet. */
    private static final int NONE = Integer.MIN_VALUE;


    private final int[] units;
    private final int literalBits;
    private final int offsetLimit;

    /** Per position: the winning cost, and the descriptor to rebuild it. */
    private final int[] optimalBits;
    private final byte[] winKind;
    private final int[] winOffset;
    private final int[] winAux;

    private St4FastOptimizer(int[] units, int unit, int offsetLimit) {
        this.units = units;
        this.literalBits = 8 * unit;
        this.offsetLimit = offsetLimit;
        this.optimalBits = new int[units.length];
        this.winKind = new byte[units.length];
        this.winOffset = new int[units.length];
        this.winAux = new int[units.length];
    }

    /**
     * Returns the last block of the optimal parse of {@code units}, reporting
     * progress on stdout while it works.
     */
    public static St4Block optimize(int[] units, int unit, int offsetLimit) {
        return optimize(units, unit, offsetLimit, true);
    }

    /**
     * Returns the last block of the optimal parse of {@code units} - the same
     * chain {@link St4Optimizer#optimize} returns, byte for byte.
     *
     * @param unit        bytes per unit, which sets what a literal costs
     * @param offsetLimit the furthest a match may reach back, in units
     * @param progress    whether to report on stdout, as {@link ProgressMeter}
     */
    public static St4Block optimize(int[] units, int unit, int offsetLimit,
                                    boolean progress) {
        var optimizer = new St4FastOptimizer(units, unit, offsetLimit);
        optimizer.forward(progress);
        return new St4ChainRebuilder(units, optimizer.literalBits, optimizer.optimalBits,
                optimizer.winKind, optimizer.winOffset, optimizer.winAux).rebuild();
    }

    /**
     * The winning cost per position, for the tests that hold other optimizers
     * to this one: the optimum is unique, so any exact optimizer must produce
     * this exact array.
     */
    static int[] costs(int[] units, int unit, int offsetLimit) {
        var optimizer = new St4FastOptimizer(units, unit, offsetLimit);
        optimizer.forward(false);
        return optimizer.optimalBits;
    }

    private static int eliasGammaBits(int value) {
        return 2 * (31 - Integer.numberOfLeadingZeros(value)) + 1;
    }

    // ------------------------------------------------------------- forward

    /**
     * The DP of {@link St4Optimizer#optimize}, candidate for candidate, on
     * primitives. State per offset: the best chain ending in a match at
     * {@code stateEnd} costing {@code stateBits}, and the best chain ending in
     * a literal run at {@code litEnd} costing {@code litBits}. A position's
     * winner is recorded the moment it takes the lead; replacement is strictly
     * better, as {@code better()} was, so ties keep the earlier candidate.
     */
    private void forward(boolean progress) {
        int count = units.length;
        int width = (int) Math.clamp(count - 1L, INITIAL_OFFSET, offsetLimit);
        int[] stateBits = new int[width + 1];
        int[] stateEnd = new int[width + 1];
        int[] litBits = new int[width + 1];
        int[] litEnd = new int[width + 1];
        int[] matchLength = new int[width + 1];
        java.util.Arrays.fill(stateEnd, NONE);
        java.util.Arrays.fill(litEnd, NONE);
        int[] bestLength = new int[Math.max(count, 3)];
        bestLength[2] = 2;

        // The fake block every chain hangs from, as the reference: one unit
        // back, ending just before the stream.
        stateBits[INITIAL_OFFSET] = -1;
        stateEnd[INITIAL_OFFSET] = -1;

        var meter = new ProgressMeter(ProgressMeter.totalSteps(count, 0, offsetLimit),
                progress);

        for (int index = 0; index < count; index++) {
            int maxOffset = (int) Math.clamp((long) index, INITIAL_OFFSET, offsetLimit);
            int bestLengthSize = 2;
            int unitValue = units[index];
            int best = Integer.MAX_VALUE;
            for (int offset = 1; offset <= maxOffset; offset++) {
                if (index != 0 && unitValue == units[index - offset]) {
                    // Match reusing the last offset, after a literal run.
                    if (litEnd[offset] != NONE) {
                        int bits = litBits[offset] + 1
                                + eliasGammaBits(index - litEnd[offset]);
                        stateBits[offset] = bits;
                        stateEnd[offset] = index;
                        if (bits < best) {
                            best = bits;
                            winKind[index] = St4ChainRebuilder.REP;
                            winOffset[index] = offset;
                            winAux[index] = litEnd[offset];
                        }
                    }
                    // Match with a new offset, at the best split length.
                    if (++matchLength[offset] > 1) {
                        if (bestLengthSize < matchLength[offset]) {
                            int bits = optimalBits[index - bestLength[bestLengthSize]]
                                    + eliasGammaBits(bestLength[bestLengthSize] - 1);
                            do {
                                bestLengthSize++;
                                int shorterBits = optimalBits[index - bestLengthSize]
                                        + eliasGammaBits(bestLengthSize - 1);
                                if (shorterBits <= bits) {
                                    bestLength[bestLengthSize] = bestLengthSize;
                                    bits = shorterBits;
                                } else {
                                    bestLength[bestLengthSize] = bestLength[bestLengthSize - 1];
                                }
                            } while (bestLengthSize < matchLength[offset]);
                        }
                        int length = bestLength[matchLength[offset]];
                        int bits = optimalBits[index - length] + 3
                                + (offset > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8)
                                + eliasGammaBits(length - 1);
                        if (stateEnd[offset] != index || stateBits[offset] > bits) {
                            stateBits[offset] = bits;
                            stateEnd[offset] = index;
                            if (bits < best) {
                                best = bits;
                                winKind[index] = St4ChainRebuilder.NEW;
                                winOffset[index] = offset;
                                winAux[index] = length;
                            }
                        }
                    }
                } else {
                    // Literals, continuing from the offset's last match.
                    matchLength[offset] = 0;
                    if (stateEnd[offset] != NONE) {
                        int length = index - stateEnd[offset];
                        int bits = stateBits[offset] + 1 + eliasGammaBits(length)
                                + length * literalBits;
                        litBits[offset] = bits;
                        litEnd[offset] = index;
                        if (bits < best) {
                            best = bits;
                            winKind[index] = St4ChainRebuilder.LITERALS;
                            winOffset[index] = offset;
                            winAux[index] = stateEnd[offset];
                        }
                    }
                }
            }
            assert best != Integer.MAX_VALUE : "every position has a winner";
            optimalBits[index] = best;
            meter.advance(maxOffset);
        }
        meter.finish();
    }
}
