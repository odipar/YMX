package org.st4;

import org.jspecify.annotations.Nullable;

/**
 * Optimal LZ parser for ST4: ZX1's optimal parser (jx1's, in odipar/ST1),
 * moved from bytes to k-byte units.
 *
 * <p>The algorithm is unchanged - for every position it keeps, per offset, the
 * cheapest chain that ends in a literal run and the cheapest that ends in a
 * match, then picks the best of the two - because the format's shape is
 * unchanged. Only the units differ, and with them two things in the cost model:
 * a literal unit costs {@code 8 * k} bits rather than 8, and an offset counts
 * units rather than bytes, so the byte encoding reaches {@code 512 * k} bytes
 * back. A new-offset match pays three control bits - the flag and the two that
 * name its offset's width and bank - plus eight or sixteen for the offset
 * itself.
 *
 * <p>The result is a chain of {@link St4Block}s, last block first, which
 * {@link St4Compressor} walks in reverse.
 */
public final class St4Optimizer {

    /** The offset a stream starts with, as ZX1: one unit. */
    public static final int INITIAL_OFFSET = 1;

    /**
     * Inner-loop steps this parse will take, which is known before it starts.
     *
     * <p>The COUNT is exact and owes nothing to the data: position {@code
     * index} is tried against every offset from 1 to {@code clamp(index, 1,
     * offsetLimit)}. What the steps cost is another matter entirely - one that
     * finds a match allocates a block and walks the best-length ladder, one
     * that finds nothing does neither - so this measures the parse's progress,
     * not its remaining time. The time comes from {@link #estimate} instead.
     *
     * <p>Positions are also not equal work: the early ones try a handful of
     * offsets and the later ones the whole window, which is why counting
     * positions rather than steps would run fast and then crawl.
     */
    private static long totalSteps(int count, int offsetLimit) {
        long ramp = Math.min(count - 1L, offsetLimit);      // 1..L, one step longer each
        long flat = Math.max(0L, count - 1L - offsetLimit); // the rest, at the full window
        return 1 + ramp * (ramp + 1) / 2 + flat * offsetLimit;
    }

    private St4Optimizer() {}

    /**
     * Percent of the work to finish before estimating anything, so the JIT's
     * warm-up is not counted against the rest.
     */
    private static final int WARMUP = 5;

    /**
     * Percent of history the fit needs before it says anything. A curve drawn
     * through three points a couple of percent apart is mostly noise, and a
     * confidently wrong number is worse than no number.
     */
    private static final int BASELINE = 15;



    /**
     * Time left, or "" until there is enough history to say.
     *
     * <p>Elapsed time is fitted as {@code a*x + b*x^2} in the percentage x,
     * through the warm-up point, the midpoint and now. The square makes
     * it work on real assets: a step costs what its neighbourhood costs, so a
     * parse that finds more matches as it goes gets steadily slower, and a rate
     * measured over any window - however recent - keeps predicting the past. On
     * an asset whose cost per step is flat, {@code b} comes out near zero and
     * this is the straight-line estimate it should be.
     */
    private static String estimate(int percent, long now, long[] tickNanos) {
        int base = WARMUP;
        while (base < percent && tickNanos[base] == 0) {
            base++;                                     // a percent the loop stepped over
        }
        int mid = (base + percent) / 2;
        while (mid > base && tickNanos[mid] == 0) {
            mid--;
        }
        if (mid <= base || mid >= percent || percent - base < BASELINE) {
            return "";                                  // too little history to fit
        }
        double half = mid - base;
        double span = percent - base;
        double untilMid = tickNanos[mid] - tickNanos[base];
        double untilNow = now - tickNanos[base];
        double square = (untilNow * half - untilMid * span) / (half * span * (span - half));
        double linear = (untilMid - square * half * half) / half;
        double whole = 100.0 - base;
        double left = linear * whole + square * whole * whole - untilNow;
        if (!(left > 0)) {
            return "";                                  // NaN, or already there
        }
        return duration((long) left) + " left";
    }

    /** Seconds, in the shortest form that stays readable, rounded not floored. */
    private static String duration(long nanos) {
        long seconds = (Math.max(0, nanos) + 500_000_000L) / 1_000_000_000L;
        return seconds < 60 ? seconds + "s"
                : String.format("%dm %02ds", seconds / 60, seconds % 60);
    }


    private static int offsetCeiling(int index, int offsetLimit) {
        return Math.clamp(index, INITIAL_OFFSET, offsetLimit);
    }

    private static int eliasGammaBits(int value) {
        int bits = 1;
        while ((value >>= 1) != 0) {
            bits += 2;
        }
        return bits;
    }

    /**
     * Returns the last block of the optimal parse of {@code units}, drawing a
     * progress bar on stdout while it works.
     *
     * @param unit        bytes per unit, which sets what a literal costs
     * @param offsetLimit the furthest a match may reach back, in units
     */
    public static St4Block optimize(int[] units, int unit, int offsetLimit) {
        return optimize(units, unit, offsetLimit, true);
    }

    /**
     * Returns the last block of the optimal parse of {@code units}.
     *
     * <p>This is the slow half of packing - every position against every offset
     * - so it reports as it goes, exactly as jx1 does. Callers that are not a
     * person waiting at a terminal pass {@code false}.
     *
     * @param unit        bytes per unit, which sets what a literal costs
     * @param offsetLimit the furthest a match may reach back, in units
     * @param progress    whether to report on stdout: a percentage of the
     *                    parse's steps, which is exact, and a time estimate
     *                    fitted to how the parse has been slowing, which is not
     */
    public static St4Block optimize(int[] units, int unit, int offsetLimit,
                                    boolean progress) {
        int literalBits = 8 * unit;
        int maxOffset = offsetCeiling(units.length - 1, offsetLimit);
        var lastLiteral = new @Nullable St4Block[maxOffset + 1];
        var lastMatch = new @Nullable St4Block[maxOffset + 1];
        var optimal = new @Nullable St4Block[units.length];
        int[] matchLength = new int[maxOffset + 1];
        int[] bestLength = new int[Math.max(units.length, 3)];
        bestLength[2] = 2;

        // A fake block for the first real one to chain from.
        lastMatch[INITIAL_OFFSET] = new St4Block(-1, -1, INITIAL_OFFSET, null);

        long steps = 0;
        long total = totalSteps(units.length, offsetLimit);
        long started = System.nanoTime();
        long[] tickNanos = new long[101];
        int shown = -1;

        for (int index = 0; index < units.length; index++) {
            maxOffset = offsetCeiling(index, offsetLimit);
            int bestLengthSize = 2;
            for (int offset = 1; offset <= maxOffset; offset++) {
                if (index != 0 && index >= offset && units[index] == units[index - offset]) {
                    // Match reusing the last offset: its length may be one unit,
                    // which at k = 4 replaces four bytes with a couple of bits.
                    St4Block literal = lastLiteral[offset];
                    if (literal != null) {
                        int length = index - literal.index();
                        int bits = literal.bits() + 1 + eliasGammaBits(length);
                        St4Block match = new St4Block(bits, index, offset, literal);
                        lastMatch[offset] = match;
                        optimal[index] = better(optimal[index], match);
                    }
                    // Match with a new offset, which the format cannot express
                    // shorter than two units.
                    if (++matchLength[offset] > 1) {
                        if (bestLengthSize < matchLength[offset]) {
                            St4Block best = optimal[index - bestLength[bestLengthSize]];
                            assert best != null;
                            int bits = best.bits() + eliasGammaBits(bestLength[bestLengthSize] - 1);
                            do {
                                bestLengthSize++;
                                St4Block shorter = optimal[index - bestLengthSize];
                                assert shorter != null;
                                int shorterBits = shorter.bits() + eliasGammaBits(bestLengthSize - 1);
                                if (shorterBits <= bits) {
                                    bestLength[bestLengthSize] = bestLengthSize;
                                    bits = shorterBits;
                                } else {
                                    bestLength[bestLengthSize] = bestLength[bestLengthSize - 1];
                                }
                            } while (bestLengthSize < matchLength[offset]);
                        }
                        int length = bestLength[matchLength[offset]];
                        St4Block previous = optimal[index - length];
                        assert previous != null;
                        int bits = previous.bits() + 3
                                + (offset > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8)
                                + eliasGammaBits(length - 1);
                        St4Block match = lastMatch[offset];
                        if (match == null || match.index() != index || match.bits() > bits) {
                            match = new St4Block(bits, index, offset, previous);
                            lastMatch[offset] = match;
                            optimal[index] = better(optimal[index], match);
                        }
                    }
                } else {
                    // Literals: the run's length goes in stream A, its payload
                    // in stream B, and both are paid for here.
                    matchLength[offset] = 0;
                    St4Block match = lastMatch[offset];
                    if (match != null) {
                        int length = index - match.index();
                        int bits = match.bits() + 1 + eliasGammaBits(length) + length * literalBits;
                        St4Block literal = new St4Block(bits, index, 0, match);
                        lastLiteral[offset] = literal;
                        optimal[index] = better(optimal[index], literal);
                    }
                }
            }

            steps += maxOffset;
            if (progress) {
                int percent = (int) (steps * 100 / total);
                if (percent != shown) {
                    shown = percent;
                    long now = System.nanoTime();
                    tickNanos[percent] = now;
                    System.out.printf("\r[%3d%%] %-12s", percent,
                            estimate(percent, now, tickNanos));
                    System.out.flush();
                }
            }
        }

        assert steps == total : "the step count is meant to be exact, not an estimate";
        if (progress) {
            System.out.printf("\r[100%%] %-12s%n", duration(System.nanoTime() - started));
        }

        St4Block last = optimal[units.length - 1];
        assert last != null;
        return last;
    }

    private static St4Block better(@Nullable St4Block current, St4Block candidate) {
        return current == null || current.bits() > candidate.bits() ? candidate : current;
    }
}
