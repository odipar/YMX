package org.st4;

import java.util.ArrayList;
import java.util.List;

/**
 * The optimum of a parse with copies from the literal stream, by trying every
 * parse the format allows: feasible on a dozen units, and there to measure
 * {@link St4LiteralCopySearch} against. It costs what the compressor writes:
 * literal runs, matches new or repeated, copies at the window plus the
 * literals between and shorter than that count, and reps of a copy, which
 * resume past it.
 */
public final class St4LiteralCopyOracle {

    private final int[] units;
    private final int literalBits;
    private final int window;
    private final int reach;

    // The parse so far: which units are literal, in emission order, and the
    // blocks chosen, so the best parse can be kept when it is found.
    private final boolean[] literal;
    private final int[] literalPositions;      // output position of each literal, in order
    private int literals;
    private final List<int[]> blocks = new ArrayList<>();    // {index, offset} per block
    private List<int[]> best = List.of();
    private int bestBits = Integer.MAX_VALUE;

    private St4LiteralCopyOracle(int[] units, int unit, int window) {
        this.units = units;
        this.literalBits = 8 * unit;
        this.window = window;
        this.reach = St4Format.maxOffsetUnits(unit);
        this.literal = new boolean[units.length];
        this.literalPositions = new int[units.length];
    }

    private static int eliasGammaBits(int value) {
        return 2 * (31 - Integer.numberOfLeadingZeros(value)) + 1;
    }

    /**
     * The cheapest parse of {@code units} at {@code window}, as a chain of
     * blocks the compressor writes, copies as negative offsets, whose bits
     * are the compressor's.
     */
    public static St4Block optimize(int[] units, int unit, int window) {
        var oracle = new St4LiteralCopyOracle(units, unit, window);
        oracle.search(0, 0, St4Optimizer.INITIAL_OFFSET, false, true);
        St4Block chain = new St4Block(-1, -1, St4Optimizer.INITIAL_OFFSET, null);
        int previous = -1;
        for (int[] block : oracle.best) {
            chain = new St4Block(block[2], block[0], block[1], chain);
            previous = block[0];
        }
        assert previous == units.length - 1;
        return chain;
    }

    /**
     * Extends the parse from {@code index} with every block the format
     * allows there, keeping the cheapest complete parse.
     *
     * @param bits          the cost so far
     * @param lastOffset    the offset the decoder holds, as the compressor
     *                      writes it: beyond the window for a copy, less what
     *                      the copy copied
     * @param afterLiterals whether the last block was a literal run
     * @param first         whether the first block, which has no flag, is
     *                      still to come
     */
    private void search(int index, int bits, int lastOffset, boolean afterLiterals,
                        boolean first) {
        int count = units.length;
        if (bits >= bestBits) {
            return;                                 // no parse from here can win
        }
        if (index == count) {
            bestBits = bits;
            best = new ArrayList<>(blocks.size());
            for (int[] block : blocks) {
                best.add(block.clone());
            }
            return;
        }
        // A literal run, unless one just ended.
        if (!afterLiterals) {
            int mark = literals;
            for (int length = 1; index + length <= count; length++) {
                literal[index + length - 1] = true;
                literalPositions[literals++] = index + length - 1;
                push(index + length - 1, 0, bits + (first ? 0 : 1) + eliasGammaBits(length)
                        + length * literalBits);
                search(index + length, bits + (first ? 0 : 1) + eliasGammaBits(length)
                        + length * literalBits, lastOffset, true, false);
                blocks.remove(blocks.size() - 1);
            }
            for (int p = index; p < index + (literals - mark); p++) {
                literal[p] = false;
            }
            literals = mark;
        }
        // A rep of the last offset, after literals: a match, or a copy.
        if (afterLiterals) {
            if (lastOffset <= window) {
                if (lastOffset <= index) {
                    for (int length = 1; index + length <= count
                            && units[index + length - 1] == units[index + length - 1 - lastOffset];
                            length++) {
                        push(index + length - 1, lastOffset, bits + 1 + eliasGammaBits(length));
                        search(index + length, bits + 1 + eliasGammaBits(length), lastOffset,
                                false, false);
                        blocks.remove(blocks.size() - 1);
                    }
                }
            } else {
                int back = lastOffset - window;
                if (back <= literals) {
                    int source = literals - back;    // the literal to copy first
                    for (int length = 1; index + length <= count && length < back
                            && units[index + length - 1]
                               == units[literalPositions[source + length - 1]];
                            length++) {
                        int distance = index - literalPositions[source];
                        push(index + length - 1, -distance, bits + 1 + eliasGammaBits(length));
                        search(index + length, bits + 1 + eliasGammaBits(length),
                                lastOffset - length, false, false);
                        blocks.remove(blocks.size() - 1);
                    }
                }
            }
        }
        // A match at a new offset, within the window.
        for (int offset = 1; offset <= Math.min(index, window); offset++) {
            int cost = 3 + (offset > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8);
            for (int length = 2; index + length <= count
                    && units[index + length - 1] == units[index + length - 1 - offset];
                    length++) {
                if (length == 2 && units[index] != units[index - offset]) {
                    break;
                }
                push(index + length - 1, offset, bits + cost + eliasGammaBits(length - 1));
                search(index + length, bits + cost + eliasGammaBits(length - 1), offset,
                        false, false);
                blocks.remove(blocks.size() - 1);
            }
        }
        // A copy from the literal stream: from any literal so far, strictly
        // shorter than the literals between it and here.
        for (int source = 0; source < literals; source++) {
            int back = literals - source;
            int wire = window + back;
            if (wire > reach) {
                continue;
            }
            int cost = 3 + (wire > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8);
            int distance = index - literalPositions[source];
            for (int length = 2; index + length <= count && length < back
                    && units[index + length - 1] == units[literalPositions[source + length - 1]];
                    length++) {
                if (length == 2 && units[index] != units[literalPositions[source]]) {
                    break;
                }
                // The copied units are one run in the output: the compressor
                // gives the source as an output position.
                if (literalPositions[source + length - 1] != literalPositions[source] + length - 1) {
                    break;
                }
                push(index + length - 1, -distance, bits + cost + eliasGammaBits(length - 1));
                search(index + length, bits + cost + eliasGammaBits(length - 1),
                        wire - length, false, false);
                blocks.remove(blocks.size() - 1);
            }
        }
    }

    private void push(int index, int offset, int bits) {
        blocks.add(new int[] {index, offset, bits});
    }
}
