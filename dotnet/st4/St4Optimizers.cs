using System;
using System.Collections.Generic;
using System.Numerics;

namespace St4
{
    /// <summary>Shared pieces of the parsers.</summary>
    internal static class Parse
    {
        /// <summary>The offset a stream starts with, as ZX1: one unit.</summary>
        internal const int InitialOffset = 1;

        internal static int EliasGammaBits(int value)
        {
            return 2 * (31 - BitOperations.LeadingZeroCount((uint) value)) + 1;
        }

        internal static int Clamp(long value, int low, int high)
        {
            return (int) Math.Clamp(value, low, high);
        }
    }

    /// <summary>
    /// The forward DP on primitive arrays and the descriptor recording, ported
    /// from org.st4.St4FastOptimizer: the same candidates in the same order
    /// with the same strictly-better replacement rule, so ties fall exactly as
    /// in the Java tree and the output is byte-identical.
    /// </summary>
    public static class St4FastOptimizer
    {
        private const int None = int.MinValue;

        public static St4Block Optimize(int[] units, int unit, int offsetLimit,
                bool progress)
        {
            int literalBits = 8 * unit;
            int count = units.Length;
            var optimalBits = new int[count];
            var winKind = new byte[count];
            var winOffset = new int[count];
            var winAux = new int[count];
            Forward(units, literalBits, offsetLimit, optimalBits, winKind,
                    winOffset, winAux, progress);
            return new St4ChainRebuilder(units, literalBits, optimalBits,
                    winKind, winOffset, winAux).Rebuild();
        }

        internal static void Forward(int[] units, int literalBits, int offsetLimit,
                int[] optimalBits, byte[] winKind, int[] winOffset, int[] winAux,
                bool progress)
        {
            int count = units.Length;
            int width = Parse.Clamp(count - 1L, Parse.InitialOffset, offsetLimit);
            var stateBits = new int[width + 1];
            var stateEnd = new int[width + 1];
            var litBits = new int[width + 1];
            var litEnd = new int[width + 1];
            var matchLength = new int[width + 1];
            Array.Fill(stateEnd, None);
            Array.Fill(litEnd, None);
            var bestLength = new int[Math.Max(count, 3)];
            bestLength[2] = 2;

            // The fake block every chain hangs from: one unit back, ending
            // just before the stream.
            stateBits[Parse.InitialOffset] = -1;
            stateEnd[Parse.InitialOffset] = -1;

            var meter = new ProgressMeter(
                    ProgressMeter.TotalSteps(count, 0, offsetLimit), progress);

            for (int index = 0; index < count; index++)
            {
                int maxOffset = Parse.Clamp(index, Parse.InitialOffset, offsetLimit);
                int bestLengthSize = 2;
                int unitValue = units[index];
                int best = int.MaxValue;
                for (int offset = 1; offset <= maxOffset; offset++)
                {
                    if (index != 0 && unitValue == units[index - offset])
                    {
                        // Match reusing the last offset, after a literal run.
                        if (litEnd[offset] != None)
                        {
                            int bits = litBits[offset] + 1
                                    + Parse.EliasGammaBits(index - litEnd[offset]);
                            stateBits[offset] = bits;
                            stateEnd[offset] = index;
                            if (bits < best)
                            {
                                best = bits;
                                winKind[index] = St4ChainRebuilder.Rep;
                                winOffset[index] = offset;
                                winAux[index] = litEnd[offset];
                            }
                        }
                        // Match with a new offset, at the best split length.
                        if (++matchLength[offset] > 1)
                        {
                            if (bestLengthSize < matchLength[offset])
                            {
                                int bits = optimalBits[index - bestLength[bestLengthSize]]
                                        + Parse.EliasGammaBits(bestLength[bestLengthSize] - 1);
                                do
                                {
                                    bestLengthSize++;
                                    int shorterBits = optimalBits[index - bestLengthSize]
                                            + Parse.EliasGammaBits(bestLengthSize - 1);
                                    if (shorterBits <= bits)
                                    {
                                        bestLength[bestLengthSize] = bestLengthSize;
                                        bits = shorterBits;
                                    }
                                    else
                                    {
                                        bestLength[bestLengthSize] =
                                                bestLength[bestLengthSize - 1];
                                    }
                                } while (bestLengthSize < matchLength[offset]);
                            }
                            int length = bestLength[matchLength[offset]];
                            int newBits = optimalBits[index - length] + 3
                                    + (offset > St4Format.ByteOffsetLimit ? 16 : 8)
                                    + Parse.EliasGammaBits(length - 1);
                            if (stateEnd[offset] != index || stateBits[offset] > newBits)
                            {
                                stateBits[offset] = newBits;
                                stateEnd[offset] = index;
                                if (newBits < best)
                                {
                                    best = newBits;
                                    winKind[index] = St4ChainRebuilder.New;
                                    winOffset[index] = offset;
                                    winAux[index] = length;
                                }
                            }
                        }
                    }
                    else
                    {
                        // Literals, continuing from the offset's last match.
                        matchLength[offset] = 0;
                        if (stateEnd[offset] != None)
                        {
                            int length = index - stateEnd[offset];
                            int bits = stateBits[offset] + 1
                                    + Parse.EliasGammaBits(length)
                                    + length * literalBits;
                            litBits[offset] = bits;
                            litEnd[offset] = index;
                            if (bits < best)
                            {
                                best = bits;
                                winKind[index] = St4ChainRebuilder.Literals;
                                winOffset[index] = offset;
                                winAux[index] = stateEnd[offset];
                            }
                        }
                    }
                }
                Check.That(best != int.MaxValue, "every position has a winner");
                optimalBits[index] = best;
                meter.Advance(maxOffset);
            }
            meter.Finish();
        }
    }

    /// <summary>
    /// Rebuilds an optimal parse chain from what a forward cost pass recorded,
    /// ported from org.st4.St4ChainRebuilder: only the blocks the winning
    /// chain contains are ever built.
    /// </summary>
    internal sealed class St4ChainRebuilder
    {
        private const int None = int.MinValue;

        internal const byte Literals = 1;
        internal const byte Rep = 2;
        internal const byte New = 3;

        private readonly int[] units;
        private readonly int literalBits;
        private readonly int[] optimalBits;
        private readonly byte[] winKind;
        private readonly int[] winOffset;
        private readonly int[] winAux;

        internal St4ChainRebuilder(int[] units, int literalBits, int[] optimalBits,
                byte[] winKind, int[] winOffset, int[] winAux)
        {
            this.units = units;
            this.literalBits = literalBits;
            this.optimalBits = optimalBits;
            this.winKind = winKind;
            this.winOffset = winOffset;
            this.winAux = winAux;
        }

        private bool Matches(int index, int offset)
        {
            return index >= offset && units[index] == units[index - offset];
        }

        /// <summary>A pending resolution: the winner chain at an index, or the
        /// state an offset held when it last matched there. Frames form a
        /// chain of single dependencies, resolved with an explicit stack.</summary>
        private sealed class Frame
        {
            internal readonly bool IsState;
            internal readonly int Offset;
            internal readonly int Index;
            internal bool Scanned;
            internal int RunStart;
            internal int PrevEnd = None;    // the state before this run
            internal int NewLength;         // best new-offset split, 0 = none
            internal int NewBits;

            internal Frame(bool isState, int offset, int index)
            {
                IsState = isState;
                Offset = offset;
                Index = index;
            }
        }

        internal St4Block Rebuild()
        {
            int last = units.Length - 1;
            var winner = new St4Block?[units.Length];
            var states = new Dictionary<long, St4Block>();
            states[StateKey(Parse.InitialOffset, -1)] =
                    new St4Block(-1, -1, Parse.InitialOffset, null);

            var stack = new Stack<Frame>();
            stack.Push(new Frame(false, 0, last));
            while (stack.Count > 0)
            {
                Frame frame = stack.Peek();
                if (frame.IsState ? ResolveState(frame, states, winner, stack)
                                  : ResolveWinner(frame, states, winner, stack))
                {
                    stack.Pop();
                }
            }
            St4Block? block = winner[last];
            if (block == null)
            {
                throw new AssertionException(
                        "reconstruction did not reach the last position");
            }
            return block;
        }

        private static long StateKey(int offset, int index)
        {
            return (long) offset << 32 | (uint) index;
        }

        private bool ResolveWinner(Frame frame, Dictionary<long, St4Block> states,
                St4Block?[] winner, Stack<Frame> stack)
        {
            int index = frame.Index;
            if (winner[index] != null)
            {
                return true;
            }
            int offset = winOffset[index];
            switch (winKind[index])
            {
                case Literals:
                {
                    if (!states.TryGetValue(StateKey(offset, winAux[index]),
                            out St4Block? state))
                    {
                        stack.Push(new Frame(true, offset, winAux[index]));
                        return false;
                    }
                    winner[index] = new St4Block(optimalBits[index], index, 0, state);
                    break;
                }
                case Rep:
                {
                    int litAt = winAux[index];
                    int prevEnd = PreviousStateEnd(offset, litAt);
                    if (!states.TryGetValue(StateKey(offset, prevEnd),
                            out St4Block? state))
                    {
                        stack.Push(new Frame(true, offset, prevEnd));
                        return false;
                    }
                    winner[index] = new St4Block(optimalBits[index], index, offset,
                            LiteralRun(state, litAt));
                    break;
                }
                case New:
                {
                    St4Block? previous = winner[index - winAux[index]];
                    if (previous == null)
                    {
                        stack.Push(new Frame(false, 0, index - winAux[index]));
                        return false;
                    }
                    winner[index] = new St4Block(optimalBits[index], index, offset,
                            previous);
                    break;
                }
                default:
                    throw new AssertionException("position " + index + " has no winner");
            }
            return true;
        }

        /// <summary>The state an offset held after matching at frame.Index:
        /// the cheaper of reusing the offset across the literal run before
        /// this match run, and a new-offset match at the best split - the same
        /// two candidates the forward pass weighed, with the same tie rule.</summary>
        private bool ResolveState(Frame frame, Dictionary<long, St4Block> states,
                St4Block?[] winner, Stack<Frame> stack)
        {
            int offset = frame.Offset;
            int end = frame.Index;
            if (!frame.Scanned)
            {
                frame.Scanned = true;
                Check.That(Matches(end, offset), "a state can only end on a match");
                int start = end;
                while (start - 1 >= offset && Matches(start - 1, offset))
                {
                    start--;
                }
                frame.RunStart = start;
                frame.PrevEnd = PreviousStateEnd(offset, start - 1);
                int run = end - start + 1;
                if (run >= 2)
                {
                    int bestCore = int.MaxValue;
                    for (int length = 2; length <= run; length++)
                    {
                        int core = optimalBits[end - length]
                                + Parse.EliasGammaBits(length - 1);
                        if (core <= bestCore)
                        {           // ties go to the longer split
                            bestCore = core;
                            frame.NewLength = length;
                        }
                    }
                    frame.NewBits = bestCore + 3
                            + (offset > St4Format.ByteOffsetLimit ? 16 : 8);
                }
            }

            if (frame.PrevEnd != None)
            {                       // the rep candidate exists
                if (!states.TryGetValue(StateKey(offset, frame.PrevEnd),
                        out St4Block? previous))
                {
                    stack.Push(new Frame(true, offset, frame.PrevEnd));
                    return false;
                }
                St4Block literal = LiteralRun(previous, frame.RunStart - 1);
                int repBits = literal.Bits + 1
                        + Parse.EliasGammaBits(end - frame.RunStart + 1);
                if (frame.NewLength == 0 || repBits <= frame.NewBits)
                {
                    states[StateKey(offset, end)] =
                            new St4Block(repBits, end, offset, literal);
                    return true;
                }
            }
            Check.That(frame.NewLength != 0,
                    "a state is a rep match or a new-offset match");
            St4Block? before = winner[end - frame.NewLength];
            if (before == null)
            {
                stack.Push(new Frame(false, 0, end - frame.NewLength));
                return false;
            }
            states[StateKey(offset, end)] =
                    new St4Block(frame.NewBits, end, offset, before);
            return true;
        }

        /// <summary>The literal run from just after state through litEnd.</summary>
        private St4Block LiteralRun(St4Block state, int litEnd)
        {
            int length = litEnd - state.Index;
            int bits = state.Bits + 1 + Parse.EliasGammaBits(length)
                    + length * literalBits;
            return new St4Block(bits, litEnd, 0, state);
        }

        /// <summary>Where this offset's state ended at or before from, or
        /// None: the last match, provided some adjacent-pair match sits at or
        /// below it; offset one counts every match, and with none the fake
        /// block is the state.</summary>
        private int PreviousStateEnd(int offset, int from)
        {
            int lastMatch = None;
            for (int index = from; index >= offset; index--)
            {
                if (units[index] == units[index - offset])
                {
                    if (lastMatch == None)
                    {
                        lastMatch = index;
                    }
                    if (offset == Parse.InitialOffset
                            || (index - 1 >= offset
                                && units[index - 1] == units[index - 1 - offset]))
                    {
                        return lastMatch;
                    }
                }
            }
            return offset == Parse.InitialOffset ? -1 : None;
        }
    }
}
