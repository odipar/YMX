// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace St4;

/// <summary>
/// Rebuilds a parse chain from what a forward cost pass recorded: the winning
/// cost per position and a three-int descriptor of the winner.
/// </summary>
/// <remarks>
/// The rest is derived from those costs and the data, and only the blocks of
/// the winning chain are built. <see cref="St4FastOptimizer"/> and
/// <see cref="St4EventOptimizer"/> both feed it; their winners may differ where
/// candidates tie, their packed size cannot.
/// </remarks>
internal sealed class St4ChainRebuilder
{
    private const int None = int.MinValue;

    /// <summary>Winner kind: a literal run.</summary>
    internal const byte Literals = 1;

    /// <summary>Winner kind: a match reusing the offset.</summary>
    internal const byte Rep = 2;

    /// <summary>Winner kind: a match at a new offset.</summary>
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

    private static int EliasGammaBits(int value) =>
        2 * (31 - System.Numerics.BitOperations.LeadingZeroCount((uint)value)) + 1;

    /// <summary>Does the DP's match branch run at this position and offset?</summary>
    private bool Matches(int index, int offset) =>
        index >= offset && units[index] == units[index - offset];

    /// <summary>
    /// A pending resolution: the winner chain at <c>Index</c>, or the state an
    /// offset held when it last matched at <c>Index</c>. Frames form a chain
    /// of single dependencies, resolved on an explicit stack, since a chain of
    /// one-unit blocks is as deep as the input is long.
    /// </summary>
    private sealed class Frame
    {
        internal readonly bool IsState;
        internal readonly int Offset;
        internal readonly int Index;
        internal bool Scanned;
        internal int RunStart;
        internal int PrevEnd = None;    // the state before this run, None = no rep option
        internal int NewLength;         // best split of a new-offset match here, 0 = none
        internal int NewBits;

        internal Frame(bool isState, int offset, int index)
        {
            IsState = isState;
            Offset = offset;
            Index = index;
        }
    }

    /// <summary>
    /// Builds the winning chain from the descriptors. A winner's parent is an
    /// earlier winner, recorded, or the state an offset held at a recorded
    /// position; a state is derived from its match run, the recorded winning
    /// costs and, when it reused its offset, the state before it.
    /// </summary>
    internal St4Block Rebuild()
    {
        int last = units.Length - 1;
        var winner = new St4Block?[units.Length];
        var states = new Dictionary<long, St4Block>
        {
            [StateKey(St4Optimizer.InitialOffset, -1)] =
                new St4Block(-1, -1, St4Optimizer.InitialOffset, null),
        };

        var stack = new Stack<Frame>();
        stack.Push(new Frame(false, 0, last));
        while (stack.Count != 0)
        {
            Frame frame = stack.Peek();
            if (frame.IsState ? ResolveState(frame, states, winner, stack)
                              : ResolveWinner(frame, states, winner, stack))
            {
                stack.Pop();
            }
        }
        return winner[last]
            ?? throw new InvalidOperationException("reconstruction did not reach the last position");
    }

    private static long StateKey(int offset, int index) =>
        (long)offset << 32 | (uint)index;

    /// <summary>Resolves one winner; true when done, false when a dependency was pushed.</summary>
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
                if (!states.TryGetValue(StateKey(offset, winAux[index]), out St4Block? state))
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
                if (!states.TryGetValue(StateKey(offset, prevEnd), out St4Block? state))
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
                winner[index] = new St4Block(optimalBits[index], index, offset, previous);
                break;
            }
            default:
                throw new InvalidOperationException($"position {index} has no winner");
        }
        return true;
    }

    /// <summary>
    /// Resolves the state offset <c>frame.Offset</c> held after matching at
    /// <c>frame.Index</c>: the cheaper of reusing the offset across the
    /// literal run before this match run, and a new-offset match at the best
    /// split, the two candidates the forward pass weighed, with its tie rule.
    /// </summary>
    private bool ResolveState(Frame frame, Dictionary<long, St4Block> states,
        St4Block?[] winner, Stack<Frame> stack)
    {
        int offset = frame.Offset;
        int end = frame.Index;
        if (!frame.Scanned)
        {
            frame.Scanned = true;
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
                    int core = optimalBits[end - length] + EliasGammaBits(length - 1);
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
            if (!states.TryGetValue(StateKey(offset, frame.PrevEnd), out St4Block? previous))
            {
                stack.Push(new Frame(true, offset, frame.PrevEnd));
                return false;
            }
            St4Block literal = LiteralRun(previous, frame.RunStart - 1);
            int repBits = literal.Bits + 1 + EliasGammaBits(end - frame.RunStart + 1);
            if (frame.NewLength == 0 || repBits <= frame.NewBits)
            {
                states[StateKey(offset, end)] = new St4Block(repBits, end, offset, literal);
                return true;
            }
        }
        St4Block? split = winner[end - frame.NewLength];
        if (split == null)
        {
            stack.Push(new Frame(false, 0, end - frame.NewLength));
            return false;
        }
        states[StateKey(offset, end)] = new St4Block(frame.NewBits, end, offset, split);
        return true;
    }

    /// <summary>The literal run from just after <paramref name="state"/> through
    /// <paramref name="litEnd"/>.</summary>
    private St4Block LiteralRun(St4Block state, int litEnd)
    {
        int length = litEnd - state.Index;
        int bits = state.Bits + 1 + EliasGammaBits(length) + length * literalBits;
        return new St4Block(bits, litEnd, 0, state);
    }

    /// <summary>Where this offset's state ended at or before <paramref name="from"/>, or None.</summary>
    /// <remarks>
    /// That is the last match at the offset, once a state exists: from then
    /// on every match updates it. A state first appears at a match whose
    /// predecessor also matches, a run of two, where a new-offset match first
    /// fires; lone matches before that create none. So the answer is the last
    /// match, provided a run of two sits at or below it. Offset one is the
    /// exception: the fake block the parse hangs from is a state before the
    /// first unit, so its every match counts, and with no match the fake is
    /// the state.
    /// </remarks>
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
                if (offset == St4Optimizer.InitialOffset
                    || (index - 1 >= offset
                        && units[index - 1] == units[index - 1 - offset]))
                {
                    return lastMatch;
                }
            }
        }
        return offset == St4Optimizer.InitialOffset ? -1 : None;
    }
}
