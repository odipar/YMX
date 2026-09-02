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
/// The event-driven optimizer: the costs of <see cref="St4FastOptimizer"/>
/// without visiting every (position, offset) pair. The port of the Java
/// <c>St4EventOptimizer</c>.
/// </summary>
/// <remarks>
/// Between the start and end of a match run every candidate's cost is a
/// closed form of the position, so this class takes three channel minima per
/// position from range structures and does per-offset work only where a run
/// starts or ends. It reproduces the fast optimizer's cost array exactly;
/// where candidates tie the chain may differ, the packed size cannot.
/// <see cref="Optimize(int[], int, int, bool)"/> counts the events first and
/// falls back to <see cref="St4FastOptimizer"/> where runs are a step or two
/// long.
/// </remarks>
public sealed class St4EventOptimizer
{
    private const int None = int.MinValue;

    /// <summary>Fall back to the plain DP when events exceed positions this many times.</summary>
    private const int Churn = 8;

    /// <summary>The predecessor key of a position with no predecessor.</summary>
    private const long NoValue = long.MinValue;

    private readonly int[] units;
    private readonly int literalBits;
    private readonly int offsetLimit;

    private readonly int[] optimalBits;
    private readonly byte[] winKind;
    private readonly int[] winOffset;
    private readonly int[] winAux;

    // Per offset: the state (best chain ending in its last match), the current
    // run's start and frozen literal key, None/-1 when absent.
    private readonly int[] stateS;
    private readonly int[] stateE;
    private readonly int[] runStartOf;
    private readonly int[] litKeyOf;

    // Channel structures: min-trees with per-slot sets where entries retire.
    private readonly SlotTree literalTree;      // by state end e (slot e+1)
    private readonly SlotTree repTree;          // by run start s
    private readonly MinTree costTree;          // recorded costs, argmin position
    private readonly PriorityQueue<long, long> byteRuns = new();
    private readonly PriorityQueue<long, long> wordRuns = new();

    // Occurrence chains: positions by (value, predecessor) and (value,
    // successor), newest first, for exact run start and end enumeration.
    private readonly Dictionary<int, Dictionary<long, int>> byPred = new();
    private readonly Dictionary<int, Dictionary<long, int>> bySucc = new();
    private readonly int[] predNext;
    private readonly int[] succNext;

    private St4EventOptimizer(int[] units, int unit, int offsetLimit)
    {
        this.units = units;
        this.literalBits = 8 * unit;
        this.offsetLimit = offsetLimit;
        int count = units.Length;
        this.optimalBits = new int[count];
        this.winKind = new byte[count];
        this.winOffset = new int[count];
        this.winAux = new int[count];
        int width = (int)Math.Clamp(count - 1L, 1, offsetLimit);
        this.stateS = new int[width + 1];
        this.stateE = new int[width + 1];
        this.runStartOf = new int[width + 1];
        this.litKeyOf = new int[width + 1];
        Array.Fill(stateE, None);
        Array.Fill(runStartOf, -1);
        this.literalTree = new SlotTree(count + 1);
        this.repTree = new SlotTree(count + 1);
        this.costTree = new MinTree(count);
        this.predNext = new int[count];
        this.succNext = new int[count];
    }

    /// <summary>
    /// The last block of an optimal parse of <paramref name="units"/>: the
    /// cost of <see cref="St4FastOptimizer"/>, not always its chain. Falls back
    /// to the fast optimizer when an event count says the DP would be faster.
    /// </summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit, which sets what a literal costs.</param>
    /// <param name="offsetLimit">The furthest a match may reach back, in units.</param>
    /// <param name="progress">Whether to report on stdout, as <see cref="ProgressMeter"/>.</param>
    /// <returns>The final block of an optimal parse chain.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="units"/> is null.</exception>
    public static St4Block Optimize(int[] units, int unit, int offsetLimit, bool progress)
    {
        ArgumentNullException.ThrowIfNull(units);
        var optimizer = new St4EventOptimizer(units, unit, offsetLimit);
        if (optimizer.CountEvents() > (long)Churn * units.Length)
        {
            return St4FastOptimizer.Optimize(units, unit, offsetLimit, progress);
        }
        optimizer.Run(progress);
        return new St4ChainRebuilder(units, optimizer.literalBits, optimizer.optimalBits,
            optimizer.winKind, optimizer.winOffset, optimizer.winAux).Rebuild();
    }

    /// <summary>As above, reporting progress on stdout.</summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit, which sets what a literal costs.</param>
    /// <param name="offsetLimit">The furthest a match may reach back, in units.</param>
    /// <returns>The final block of an optimal parse chain.</returns>
    public static St4Block Optimize(int[] units, int unit, int offsetLimit) =>
        Optimize(units, unit, offsetLimit, true);

    /// <summary>The winning cost per position, for the equivalence tests.</summary>
    internal static int[] Costs(int[] units, int unit, int offsetLimit)
    {
        var optimizer = new St4EventOptimizer(units, unit, offsetLimit);
        optimizer.Run(false);
        return optimizer.optimalBits;
    }

    private static int EliasGammaBits(int value) =>
        2 * (31 - System.Numerics.BitOperations.LeadingZeroCount((uint)value)) + 1;

    // ------------------------------------------------------------------ events

    /// <summary>
    /// Run starts at j: offsets whose unit matches at j but not at j-1. Those
    /// are the in-window occurrences p of <c>units[j]</c> whose predecessor
    /// differs from <c>units[j-1]</c>, or that have none, so the chains keyed
    /// by (value, predecessor) enumerate exactly them, newest first, stopping
    /// at the window's edge.
    /// </summary>
    private void ForEachRunStart(int j, Action<int> gotOffset)
    {
        if (!byPred.TryGetValue(units[j], out Dictionary<long, int>? groups))
        {
            return;
        }
        long predecessor = (uint)units[j - 1];
        long lowest = Math.Max(0, (long)j - offsetLimit);
        foreach ((long key, int newest) in groups)
        {
            if (key == predecessor)
            {
                continue;                       // those continue a run
            }
            for (int p = newest; p >= lowest; p = predNext[p])
            {
                gotOffset(j - p);
            }
        }
    }

    /// <summary>Run ends at e = j-1: matches at j-1 whose successor differs at j.</summary>
    private void ForEachRunEnd(int j, Action<int> gotOffset)
    {
        if (!bySucc.TryGetValue(units[j - 1], out Dictionary<long, int>? groups))
        {
            return;
        }
        long successor = (uint)units[j];
        long lowest = Math.Max(0, (long)(j - 1) - offsetLimit);
        foreach ((long key, int newest) in groups)
        {
            if (key == successor)
            {
                continue;                       // those keep matching
            }
            for (int p = newest; p >= lowest; p = succNext[p])
            {
                gotOffset(j - 1 - p);
            }
        }
    }

    /// <summary>Chains position j for future starts, and j-1 for future ends.</summary>
    private void Chain(int j)
    {
        long predecessor = j > 0 ? (uint)units[j - 1] : NoValue;
        if (!byPred.TryGetValue(units[j], out Dictionary<long, int>? starts))
        {
            byPred[units[j]] = starts = new Dictionary<long, int>();
        }
        predNext[j] = starts.TryGetValue(predecessor, out int old) ? old : int.MinValue;
        starts[predecessor] = j;
        if (j > 0)
        {
            if (!bySucc.TryGetValue(units[j - 1], out Dictionary<long, int>? ends))
            {
                bySucc[units[j - 1]] = ends = new Dictionary<long, int>();
            }
            succNext[j - 1] = ends.TryGetValue((uint)units[j], out old) ? old : int.MinValue;
            ends[(uint)units[j]] = j - 1;
        }
    }

    /// <summary>One pass counting run events, to choose between events and the plain DP.</summary>
    private long CountEvents()
    {
        long events = 0;
        for (int j = 0; j < units.Length; j++)
        {
            if (j > 0)
            {
                ForEachRunStart(j, _ => events++);
                ForEachRunEnd(j, _ => events++);
            }
            Chain(j);
        }
        // The pass filled the chains; the real run fills them again.
        byPred.Clear();
        bySucc.Clear();
        return events;
    }

    // ---------------------------------------------------------------- the loop

    private void Run(bool progress)
    {
        int count = units.Length;
        var meter = new ProgressMeter(
            ProgressMeter.TotalSteps(count, 0, offsetLimit), progress);

        // The fake state every chain hangs from: offset one, before the
        // stream, as the reference seeds it.
        stateS[1] = -1;
        stateE[1] = -1;
        literalTree.Insert(0, Encode(-1 - (-1 * literalBits), 1));

        for (int j = 0; j < count; j++)
        {
            if (j > 0)
            {
                int at = j;
                ForEachRunEnd(j, offset => EndRun(offset, at - 1));
                ForEachRunStart(j, offset => StartRun(offset, at));
            }

            int best = int.MaxValue;
            byte kind = 0;
            int bestOffset = 0;
            int aux = 0;

            // Literal channel: one range-min per gamma class of the age j-e.
            for (int t = 0; (1L << t) <= j + 1; t++)
            {
                int lowest = j - (1 << (t + 1)) + 1;        // e range for this class
                int highest = j - (1 << t);
                long enc = literalTree.Min(Math.Max(0, lowest + 1), highest + 1);
                if (enc == long.MaxValue)
                {
                    continue;
                }
                int candidate = Key(enc) + j * literalBits + 1 + (2 * t + 1);
                if (candidate < best)
                {
                    best = candidate;
                    kind = St4ChainRebuilder.Literals;
                    bestOffset = Offset(enc);
                    aux = stateE[bestOffset];
                }
            }

            // Rep channel: the same, keyed by run start.
            for (int t = 0; (1L << t) <= j + 1; t++)
            {
                int lowest = j - (1 << (t + 1)) + 2;        // s range for this class
                int highest = j - (1 << t) + 1;
                if (highest < 1)
                {
                    continue;
                }
                long enc = repTree.Min(Math.Max(1, lowest), highest);
                if (enc == long.MaxValue)
                {
                    continue;
                }
                int candidate = Key(enc) + 1 + (2 * t + 1);
                if (candidate < best)
                {
                    best = candidate;
                    kind = St4ChainRebuilder.Rep;
                    bestOffset = Offset(enc);
                    aux = runStartOf[bestOffset] - 1;
                }
            }

            // New-offset channel: range-mins over recorded costs, cut to the
            // longest active run of each offset class.
            long byteTop = Top(byteRuns);
            long wordTop = Top(wordRuns);
            int maxByte = byteTop == long.MaxValue ? 0 : j - (int)(byteTop >>> 16) + 1;
            int maxWord = wordTop == long.MaxValue ? 0 : j - (int)(wordTop >>> 16) + 1;
            for (int t = 0; ; t++)
            {
                int lenLo = (1 << t) + 1;
                if (lenLo > maxWord)
                {
                    break;
                }
                int lenHi = 1 << (t + 1);
                int gammaBits = 2 * t + 1;
                for (int half = 0; half < 2; half++)
                {
                    int reach = half == 0 ? Math.Min(maxByte, lenHi)
                                          : Math.Min(maxWord, lenHi);
                    if (reach < lenLo)
                    {
                        continue;
                    }
                    long enc = costTree.Min(j - reach, j - lenLo);
                    if (enc == long.MaxValue)
                    {
                        continue;
                    }
                    int candidate = (int)(enc >>> 22) + gammaBits + 3
                        + (half == 0 ? 8 : 16);
                    if (candidate < best)
                    {
                        best = candidate;
                        kind = St4ChainRebuilder.New;
                        long runTop = half == 0 ? byteTop : wordTop;
                        bestOffset = (int)(runTop & 0xFFFF);
                        aux = j - (int)(enc & 0x3FFFFF);    // the split length
                    }
                }
            }

            optimalBits[j] = best;
            winKind[j] = kind;
            winOffset[j] = bestOffset;
            winAux[j] = aux;
            costTree.Set(j, ((long)best << 22) | (uint)j);

            Chain(j);
            meter.Advance(Math.Clamp(j, 1, offsetLimit));
        }
        meter.Finish();
    }

    private void StartRun(int offset, int start)
    {
        runStartOf[offset] = start;
        if (stateE[offset] != None)
        {
            int length = (start - 1) - stateE[offset];
            int litKey = stateS[offset] + 1 + EliasGammaBits(length)
                + length * literalBits;
            litKeyOf[offset] = litKey;
            repTree.Insert(start, Encode(litKey, offset));
        }
        else
        {
            litKeyOf[offset] = None;
        }
        long entry = ((long)start << 16) | (uint)offset;
        wordRuns.Enqueue(entry, entry);
        if (offset <= St4Format.ByteOffsetLimit)
        {
            byteRuns.Enqueue(entry, entry);
        }
    }

    private void EndRun(int offset, int end)
    {
        int start = runStartOf[offset];
        int run = end - start + 1;
        int state = int.MaxValue;
        if (litKeyOf[offset] != None)
        {
            repTree.Remove(start, Encode(litKeyOf[offset], offset));
            state = litKeyOf[offset] + 1 + EliasGammaBits(run);
        }
        if (run >= 2)
        {
            int core = BestSplit(end, run);
            if (core != int.MaxValue)
            {
                state = Math.Min(state, core + 3
                    + (offset > St4Format.ByteOffsetLimit ? 16 : 8));
            }
        }
        if (state != int.MaxValue)
        {
            if (stateE[offset] != None)
            {
                // The reference overwrites an offset's state at its next match
                // run whatever the cost; this does the same.
                literalTree.Remove(stateE[offset] + 1,
                    Encode(stateS[offset] - stateE[offset] * literalBits, offset));
            }
            literalTree.Insert(end + 1, Encode(state - end * literalBits, offset));
            stateS[offset] = state;
            stateE[offset] = end;
        }
        runStartOf[offset] = -1;
    }

    /// <summary>min over lengths 2..reach of cost[end-length] + gamma(length-1).</summary>
    private int BestSplit(int end, int reach)
    {
        int best = int.MaxValue;
        for (int t = 0; ; t++)
        {
            int lenLo = (1 << t) + 1;
            if (lenLo > reach)
            {
                break;
            }
            int lenHi = Math.Min(reach, 1 << (t + 1));
            long enc = costTree.Min(end - lenHi, end - lenLo);
            if (enc != long.MaxValue)
            {
                best = Math.Min(best, (int)(enc >>> 22) + 2 * t + 1);
            }
        }
        return best;
    }

    private static long Encode(int keyValue, int offset) =>
        ((long)keyValue << 16) | (uint)offset;

    private static int Key(long encoded) => (int)(encoded >> 16);

    private static int Offset(long encoded) => (int)(encoded & 0xFFFF);

    /// <summary>The smallest valid (start, offset) entry; stale runs pop lazily.</summary>
    private long Top(PriorityQueue<long, long> runs)
    {
        while (runs.TryPeek(out long entry, out _))
        {
            if (runStartOf[(int)(entry & 0xFFFF)] == (int)(entry >>> 16))
            {
                return entry;
            }
            runs.Dequeue();
        }
        return long.MaxValue;
    }

    // ------------------------------------------------------------- structures

    /// <summary>An iterative min segment tree over longs.</summary>
    private class MinTree
    {
        private readonly long[] nodes;
        private readonly int size;

        internal MinTree(int width)
        {
            size = HighestOneBit(Math.Max(2, width - 1)) * 2;
            nodes = new long[2 * size];
            Array.Fill(nodes, long.MaxValue);
        }

        private static int HighestOneBit(int value) =>
            1 << (31 - System.Numerics.BitOperations.LeadingZeroCount((uint)value));

        internal void Set(int at, long value)
        {
            int node = at + size;
            nodes[node] = value;
            for (node >>= 1; node > 0; node >>= 1)
            {
                nodes[node] = Math.Min(nodes[2 * node], nodes[2 * node + 1]);
            }
        }

        /// <summary>Minimum over the inclusive range, MaxValue when empty or invalid.</summary>
        internal long Min(int from, int to)
        {
            if (from < 0)
            {
                from = 0;
            }
            if (to >= size)
            {
                to = size - 1;
            }
            long best = long.MaxValue;
            int lo = from + size;
            int hi = to + size + 1;
            while (lo < hi)
            {
                if ((lo & 1) != 0)
                {
                    best = Math.Min(best, nodes[lo++]);
                }
                if ((hi & 1) != 0)
                {
                    best = Math.Min(best, nodes[--hi]);
                }
                lo >>= 1;
                hi >>= 1;
            }
            return best;
        }
    }

    /// <summary>A min tree whose slots hold sets, so an entry can be removed.</summary>
    private sealed class SlotTree : MinTree
    {
        private readonly Dictionary<int, SortedSet<long>> slots = new();

        internal SlotTree(int width)
            : base(width)
        {
        }

        internal void Insert(int slot, long entry)
        {
            if (!slots.TryGetValue(slot, out SortedSet<long>? set))
            {
                slots[slot] = set = new SortedSet<long>();
            }
            set.Add(entry);
            Set(slot, set.Min);
        }

        internal void Remove(int slot, long entry)
        {
            if (slots.TryGetValue(slot, out SortedSet<long>? set))
            {
                set.Remove(entry);
                Set(slot, set.Count == 0 ? long.MaxValue : set.Min);
            }
        }
    }
}
