package org.st4;

import java.util.ArrayDeque;
import java.util.HashMap;
import org.jspecify.annotations.Nullable;

/**
 * Rebuilds an optimal parse chain from what a forward cost pass recorded: the
 * winning cost per position and a three-int descriptor of the winning
 * candidate - its kind, its offset, and the one value that cannot be
 * recomputed later.
 *
 * <p>Everything else is re-derived on demand from those costs and the data
 * itself: a match run's extent and the previous match at an offset by scanning
 * the units, which costs what the chain covers; the best split of a new-offset
 * match by minimising over the same recorded costs the forward pass minimised
 * over. Only the blocks the winning chain actually contains are ever built.
 *
 * <p>Both {@link St4FastOptimizer} and {@link St4EventOptimizer} feed this
 * class. Their descriptors may name different winners where candidates tie -
 * any winner a forward pass records reconstructs to a chain of exactly the
 * recorded cost - so the chains may differ between them while the packed size
 * cannot.
 */
final class St4ChainRebuilder {

    private static final int NONE = Integer.MIN_VALUE;

    /** Winner kinds: a literal run, a match reusing the offset, a new offset. */
    static final byte LITERALS = 1;
    static final byte REP = 2;
    static final byte NEW = 3;

    private final int[] units;
    private final int literalBits;
    private final int[] optimalBits;
    private final byte[] winKind;
    private final int[] winOffset;
    private final int[] winAux;

    St4ChainRebuilder(int[] units, int literalBits, int[] optimalBits,
                      byte[] winKind, int[] winOffset, int[] winAux) {
        this.units = units;
        this.literalBits = literalBits;
        this.optimalBits = optimalBits;
        this.winKind = winKind;
        this.winOffset = winOffset;
        this.winAux = winAux;
    }

    private static int eliasGammaBits(int value) {
        return 2 * (31 - Integer.numberOfLeadingZeros(value)) + 1;
    }

    /** Does the DP's match branch run at this position and offset? */
    private boolean matches(int index, int offset) {
        return index >= offset && units[index] == units[index - offset];
    }


    /**
     * A pending resolution: the winner chain at {@code index}, or the state an
     * offset held when it last matched at {@code index}. Frames form a chain of
     * single dependencies, resolved with an explicit stack because a chain of
     * one-unit blocks is as deep as the input is long.
     */
    private static final class Frame {
        final boolean isState;
        final int offset;
        final int index;
        boolean scanned;
        int runStart;
        int prevEnd = NONE;      // the state before this run, NONE = no rep option
        int newLength;           // best split of a new-offset match here, 0 = none
        int newBits;

        Frame(boolean isState, int offset, int index) {
            this.isState = isState;
            this.offset = offset;
            this.index = index;
        }
    }

    /**
     * Builds the winning chain from the descriptors. Each winner's parent is
     * either an earlier winner - recorded - or the state some offset held at a
     * recorded position; a state is re-derived from its match run, the recorded
     * winning costs and, when it reused its offset, the state before it. Only
     * what the chain reaches is ever built.
     */
    St4Block rebuild() {
        int last = units.length - 1;
        var winner = new @Nullable St4Block[units.length];
        var states = new HashMap<Long, St4Block>();
        states.put(stateKey(St4Optimizer.INITIAL_OFFSET, -1), new St4Block(-1, -1, St4Optimizer.INITIAL_OFFSET, null));

        var stack = new ArrayDeque<Frame>();
        stack.push(new Frame(false, 0, last));
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.isState ? resolveState(frame, states, winner, stack)
                              : resolveWinner(frame, states, winner, stack)) {
                stack.pop();
            }
        }
        St4Block block = winner[last];
        if (block == null) {
            throw new AssertionError("reconstruction did not reach the last position");
        }
        return block;
    }

    private static long stateKey(int offset, int index) {
        return (long) offset << 32 | (index & 0xFFFFFFFFL);
    }

    /** Resolves one winner; true when done, false when a dependency was pushed. */
    private boolean resolveWinner(Frame frame, HashMap<Long, St4Block> states,
                                  @Nullable St4Block[] winner, ArrayDeque<Frame> stack) {
        int index = frame.index;
        if (winner[index] != null) {
            return true;
        }
        int offset = winOffset[index];
        switch (winKind[index]) {
            case LITERALS -> {
                St4Block state = states.get(stateKey(offset, winAux[index]));
                if (state == null) {
                    stack.push(new Frame(true, offset, winAux[index]));
                    return false;
                }
                winner[index] = new St4Block(optimalBits[index], index, 0, state);
            }
            case REP -> {
                int litAt = winAux[index];
                int prevEnd = previousStateEnd(offset, litAt);
                St4Block state = states.get(stateKey(offset, prevEnd));
                if (state == null) {
                    stack.push(new Frame(true, offset, prevEnd));
                    return false;
                }
                winner[index] = new St4Block(optimalBits[index], index, offset,
                        literalRun(state, litAt));
            }
            case NEW -> {
                St4Block previous = winner[index - winAux[index]];
                if (previous == null) {
                    stack.push(new Frame(false, 0, index - winAux[index]));
                    return false;
                }
                winner[index] = new St4Block(optimalBits[index], index, offset, previous);
            }
            default -> throw new AssertionError("position " + index + " has no winner");
        }
        return true;
    }

    /**
     * Resolves the state offset {@code frame.offset} held after matching at
     * {@code frame.index}: the cheaper of reusing the offset across the literal
     * run before this match run, and a new-offset match at the best split - the
     * same two candidates the forward pass weighed, with the same tie rule.
     */
    private boolean resolveState(Frame frame, HashMap<Long, St4Block> states,
                                 @Nullable St4Block[] winner, ArrayDeque<Frame> stack) {
        int offset = frame.offset;
        int end = frame.index;
        if (!frame.scanned) {
            frame.scanned = true;
            assert matches(end, offset) : "a state can only end on a match";
            int start = end;
            while (start - 1 >= offset && matches(start - 1, offset)) {
                start--;
            }
            frame.runStart = start;
            frame.prevEnd = previousStateEnd(offset, start - 1);
            int run = end - start + 1;
            if (run >= 2) {
                int bestCore = Integer.MAX_VALUE;
                for (int length = 2; length <= run; length++) {
                    int core = optimalBits[end - length] + eliasGammaBits(length - 1);
                    if (core <= bestCore) {          // ties go to the longer split
                        bestCore = core;
                        frame.newLength = length;
                    }
                }
                frame.newBits = bestCore + 3
                        + (offset > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8);
            }
        }

        if (frame.prevEnd != NONE) {                 // the rep candidate exists
            St4Block previous = states.get(stateKey(offset, frame.prevEnd));
            if (previous == null) {
                stack.push(new Frame(true, offset, frame.prevEnd));
                return false;
            }
            St4Block literal = literalRun(previous, frame.runStart - 1);
            int repBits = literal.bits() + 1 + eliasGammaBits(end - frame.runStart + 1);
            if (frame.newLength == 0 || repBits <= frame.newBits) {
                states.put(stateKey(offset, end),
                        new St4Block(repBits, end, offset, literal));
                return true;
            }
        }
        assert frame.newLength != 0 : "a state is a rep match or a new-offset match";
        St4Block previous = winner[end - frame.newLength];
        if (previous == null) {
            stack.push(new Frame(false, 0, end - frame.newLength));
            return false;
        }
        states.put(stateKey(offset, end),
                new St4Block(frame.newBits, end, offset, previous));
        return true;
    }

    /** The literal run from just after {@code state} through {@code litEnd}. */
    private St4Block literalRun(St4Block state, int litEnd) {
        int length = litEnd - state.index();
        int bits = state.bits() + 1 + eliasGammaBits(length) + length * literalBits;
        return new St4Block(bits, litEnd, 0, state);
    }

    /**
     * Where this offset's state ended at or before {@code from}, or NONE.
     *
     * <p>That is the last match at the offset - but only once any state exists,
     * because from then on every match updates it. State first appears at a
     * match whose predecessor also matches (a run of two, which is when a
     * new-offset match first fires); lone matches before that never created
     * one. So the answer is the last match, provided some adjacent-pair match
     * sits at or below it. Offset one is the exception: the fake block the
     * whole parse hangs from is state before the first unit, so its every
     * match counts - and with no match at all, the fake itself is the state.
     */
    private int previousStateEnd(int offset, int from) {
        int lastMatch = NONE;
        for (int index = from; index >= offset; index--) {
            if (units[index] == units[index - offset]) {
                if (lastMatch == NONE) {
                    lastMatch = index;
                }
                if (offset == St4Optimizer.INITIAL_OFFSET
                        || (index - 1 >= offset
                            && units[index - 1] == units[index - 1 - offset])) {
                    return lastMatch;
                }
            }
        }
        return offset == St4Optimizer.INITIAL_OFFSET ? -1 : NONE;
    }
}
