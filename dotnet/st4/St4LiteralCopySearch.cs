// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System.Diagnostics;
using System.Globalization;

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace St4;

/// <summary>
/// The optimizer for streams with copies from the literal stream: a search
/// over which units are literal, each step scored by an exact parse for that
/// choice and by what the compressor then writes, for as long as it is given.
/// The port of the Java <c>St4LiteralCopySearch</c>.
/// </summary>
/// <remarks>
/// A dictionary is a set of forced literals: they stay literal, a copy comes
/// only from them, the parse decides the rest. The opening passes, what
/// <c>st4 -c</c> alone writes, take the literals of a full-window parse, fill
/// holes of a few units, and shrink the dictionary to what gets copied from.
/// Given time, a sweep frees or trims every literal run, keeping what packs
/// smaller; then random moves free, seed, extend or trim runs, accepted when
/// they pack smaller and by annealing when they do not, and the search
/// returns to the best and sweeps again when it stalls. The parse is
/// <see cref="St4FastOptimizer"/>'s DP with copies added: sources found through
/// two-unit chains over the dictionary, the rep of a copy as a ring rep at
/// the same output distance with literal shadows at the source, the literal
/// channel a min-tree keyed by match end, chains rebuilt from a node pool,
/// and every parse restarted from a checkpoint before the first changed unit.
/// A copy is costed with the dictionary's own literal count, a lower bound,
/// so every copy is valid; the compressor's bits are the score.
/// </remarks>
public static class St4LiteralCopySearch
{
    private const int None = int.MinValue;

    private const byte Literals = 0;
    private const byte Rep = 1;          // a ring match reusing the last offset
    private const byte New = 2;          // a ring match at a new offset
    private const byte Copy = 3;         // a copy from the literal stream
    private const byte CopyRep = 4;      // a rep of the last copy, after literals

    /// <summary>Holes of up to this many units between dictionary runs are filled in the opening passes.</summary>
    private const int Hole = 3;

    /// <summary>The opening passes, at most.</summary>
    private const int Passes = 4;

    /// <summary>The annealing temperature, in bits, at the start and at the end.</summary>
    private const double Hot = 10.0;
    private const double Cold = 0.3;

    /// <summary>Steps without a new best before the search returns to the best.</summary>
    private const int Patience = 2000;

    private static int EliasGammaBits(int value) =>
        2 * (31 - System.Numerics.BitOperations.LeadingZeroCount((uint)value)) + 1;

    /// <summary>
    /// Searches for <paramref name="seconds"/>, zero for the opening passes
    /// alone, and returns the best parse found: copies from the literal
    /// stream as negative offsets, matches within the window as positive ones.
    /// </summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit.</param>
    /// <param name="window">The furthest a match may reach back, in units.</param>
    /// <param name="maxOpLength">The compressor's operation limit, which the score counts.</param>
    /// <param name="seconds">How long to search.</param>
    /// <param name="progress">Whether to report improvements on stdout.</param>
    /// <returns>The final block of the best parse.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="units"/> is null.</exception>
    public static St4Block Optimize(int[] units, int unit, int window, int maxOpLength,
                                 double seconds, bool progress)
    {
        ArgumentNullException.ThrowIfNull(units);
        long deadline = Stopwatch.GetTimestamp() + (long)(seconds * Stopwatch.Frequency);
        return new Search(units, unit, window, maxOpLength, 1).Run(deadline, long.MaxValue,
            progress);
    }

    /// <summary>Searches for <paramref name="steps"/> steps from <paramref name="seed"/>, reproducibly; for the tests.</summary>
    internal static St4Block Optimize(int[] units, int unit, int window, int maxOpLength,
                                   long steps, long seed) =>
        new Search(units, unit, window, maxOpLength, seed).Run(long.MaxValue, steps, false);

    // ---------------------------------------------------------------- search

    private sealed class Search
    {
        private readonly int[] units;
        private readonly int unit;
        private readonly int window;
        private readonly int maxOpLength;
        private readonly int count;
        private readonly JavaRandom random;
        private readonly Parser parser;

        // The incumbent: its dictionary is exactly its literals.
        private bool[] forced;
        private St4Block chain;
        private int bits;
        private List<int[]> runs = new();         // literal runs {start, end, referenced}
        private List<int[]> copies = new();       // copies and matches {start, end, isCopy}

        private St4Block best;
        private int bestBits;

        // The budget: a deadline, a step count, and the steps taken.
        private long deadline;
        private long stepsAllowed;
        private long started;
        private long step;
        private long lastBest;
        private bool progress;

        internal Search(int[] units, int unit, int window, int maxOpLength, long seed)
        {
            this.units = units;
            this.unit = unit;
            this.window = window;
            this.maxOpLength = maxOpLength;
            count = units.Length;
            random = new JavaRandom(seed);
            parser = new Parser(units, unit, window);
            // The opening passes: the full-window parse's literals, holes
            // filled, shrunk to what gets copied from.
            int reach = St4Format.MaxOffsetUnits(unit);
            bool[] dictionary = Filled(St4LiteralCopySearch.LiteralMask(
                St4EventOptimizer.Optimize(units, unit, reach, false), count));
            St4Block first = parser.Parse(dictionary);
            chain = first;
            forced = St4LiteralCopySearch.LiteralMask(first, count);
            Adopt(first);
            best = chain;
            bestBits = bits;
            for (int pass = 1; pass < Passes; pass++)
            {
                bool[] next = Filled(Referenced());
                if (next.AsSpan().SequenceEqual(dictionary))
                {
                    break;
                }
                dictionary = next;
                Adopt(parser.Parse(dictionary));
                if (bits < bestBits)
                {
                    best = chain;
                    bestBits = bits;
                }
            }
            ReturnToBest();
        }

        /// <summary>Parses the best dictionary again, so the parser's base is the best.</summary>
        private void ReturnToBest() => Adopt(parser.Parse(LiteralMask(best)));

        /// <summary>
        /// Makes <paramref name="parsed"/>, the parse just made, the incumbent,
        /// its own literals the dictionary from here on.
        /// </summary>
        private void Adopt(St4Block parsed)
        {
            parser.Accept();
            chain = parsed;
            bits = St4Compressor.Compress(parsed, units, unit, maxOpLength, -1, window).Bits;
            forced = LiteralMask(parsed);
            runs = new List<int[]>();
            copies = new List<int[]>();
            bool[] referenced = Referenced();
            int previous = -1;
            foreach (St4Block block in Blocks(parsed))
            {
                int start = previous + 1;
                if (block.Offset == 0)
                {
                    int used = 0;
                    for (int p = start; p <= block.Index; p++)
                    {
                        used |= referenced[p] ? 1 : 0;
                    }
                    runs.Add(new[] { start, block.Index, used });
                }
                else
                {
                    copies.Add(new[] { start, block.Index, block.Offset < 0 ? 1 : 0 });
                }
                previous = block.Index;
            }
        }

        /// <summary>The positions the incumbent's copies read from.</summary>
        private bool[] Referenced()
        {
            bool[] referenced = new bool[count];
            int previous = -1;
            foreach (St4Block block in Blocks(chain))
            {
                if (block.Offset < 0)
                {
                    int distance = -block.Offset;
                    for (int p = previous + 1; p <= block.Index; p++)
                    {
                        referenced[p - distance] = true;
                    }
                }
                previous = block.Index;
            }
            return referenced;
        }

        internal St4Block Run(long deadline, long steps, bool progress)
        {
            this.deadline = deadline;
            stepsAllowed = steps;
            this.progress = progress;
            started = Stopwatch.GetTimestamp();
            long accepted = 0;
            if (progress)
            {
                Report("start");
            }
            // Descend first: most of what the opening passes force packs
            // smaller free, and a sweep finds that run by run.
            Sweep();
            while (!Exhausted())
            {
                double fraction = steps == long.MaxValue
                    ? (double)(Stopwatch.GetTimestamp() - started) / Math.Max(1, deadline - started)
                    : (double)step / steps;
                double temperature = Hot * Math.Pow(Cold / Hot, Math.Min(1.0, fraction));
                bool[] proposal = (bool[])forced.Clone();
                string move = Propose(proposal);
                St4Block parsed = parser.Parse(proposal);
                int score = Evaluate(parsed);
                int delta = score - bits;
                if (delta <= 0 || random.NextDouble() < Math.Exp(-delta / temperature))
                {
                    Adopt(parsed);
                    accepted++;
                    NoteBest(move);
                }
                if (step - lastBest > Patience)
                {
                    // Stuck: back to the best, and descend from there again.
                    ReturnToBest();
                    Sweep();
                    lastBest = step;
                }
            }
            if (progress)
            {
                Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                    "{0} steps, {1} accepted: {2} bits, {3} bytes", step, accepted, bestBits,
                    (bestBits + 7) / 8));
            }
            return best;
        }

        private bool Exhausted() =>
            step >= stepsAllowed || (step % 8 == 0 && Stopwatch.GetTimestamp() >= deadline);

        /// <summary>Scores a parse: the compressor's bits. A step of the budget.</summary>
        private int Evaluate(St4Block parsed)
        {
            step++;
            return St4Compressor.Compress(parsed, units, unit, maxOpLength, -1, window).Bits;
        }

        private void NoteBest(string move)
        {
            if (bits < bestBits)
            {
                best = chain;
                bestBits = bits;
                lastBest = step;
                if (progress)
                {
                    Report(move);
                }
            }
        }

        /// <summary>
        /// Greedy descent: every literal run of the incumbent, in a random
        /// order, freed whole and trimmed at either end, keeping each change
        /// that packs smaller.
        /// </summary>
        private void Sweep()
        {
            var order = new List<int[]>(runs);
            for (int i = order.Count - 1; i > 0; i--)
            {
                int j = random.NextInt(i + 1);
                (order[i], order[j]) = (order[j], order[i]);
            }
            foreach (int[] run in order)
            {
                if (Exhausted())
                {
                    return;
                }
                int start = run[0];
                int end = run[1];
                if (!forced[start] && !forced[end])
                {
                    continue;                           // gone already
                }
                if (Improve(start, end + 1, "sweep free"))
                {
                    continue;
                }
                if (end > start && !Improve(start, start + 1, "sweep trim"))
                {
                    Improve(end, end + 1, "sweep trim");
                }
            }
        }

        /// <summary>Frees [from, to) when that packs smaller.</summary>
        private bool Improve(int from, int to, string move)
        {
            bool[] proposal = (bool[])forced.Clone();
            Array.Fill(proposal, false, from, to - from);
            St4Block parsed = parser.Parse(proposal);
            int score = Evaluate(parsed);
            if (score < bits)
            {
                Adopt(parsed);
                NoteBest(move);
                return true;
            }
            return false;
        }

        private void Report(string move) =>
            Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                "{0,7:F1}s {1,8} steps: {2} bits, {3} bytes  ({4})",
                (Stopwatch.GetTimestamp() - started) / (double)Stopwatch.Frequency, step,
                bestBits, (bestBits + 7) / 8, move));

        /// <summary>Changes the dictionary in place, and says how.</summary>
        private string Propose(bool[] dictionary)
        {
            int kind = random.NextInt(20);
            if (kind < 6)
            {
                Free(dictionary);
                return "free";
            }
            if (kind < 12)
            {
                Seed(dictionary);
                return "seed";
            }
            if (kind < 15)
            {
                Extend(dictionary);
                return "extend";
            }
            if (kind < 18)
            {
                Trim(dictionary);
                return "trim";
            }
            Free(dictionary);
            Seed(dictionary);
            return "free+seed";
        }

        /// <summary>A literal run, unreferenced ones four times as likely, or none.</summary>
        private int[]? PickRun()
        {
            if (runs.Count == 0)
            {
                return null;
            }
            for (int attempt = 0; attempt < 4; attempt++)
            {
                int[] run = runs[random.NextInt(runs.Count)];
                if (run[2] == 0 || random.NextInt(4) == 0)
                {
                    return run;
                }
            }
            return runs[random.NextInt(runs.Count)];
        }

        /// <summary>Frees a literal run, or part of one, for the parse to match.</summary>
        private void Free(bool[] dictionary)
        {
            int[]? run = PickRun();
            if (run == null)
            {
                return;
            }
            int length = run[1] - run[0] + 1;
            if (random.NextBoolean())
            {
                Array.Fill(dictionary, false, run[0], length);
            }
            else
            {
                int size = 1 + random.NextInt(Math.Min(length, 8));
                int start = run[0] + random.NextInt(length - size + 1);
                Array.Fill(dictionary, false, start, size);
            }
        }

        /// <summary>Forces literals where a copy or match sits, so later copies can come from there.</summary>
        private void Seed(bool[] dictionary)
        {
            if (copies.Count == 0)
            {
                return;
            }
            int[] op = copies[random.NextInt(copies.Count)];
            for (int attempt = 0; attempt < 3 && op[2] == 0 && random.NextInt(4) != 0; attempt++)
            {
                op = copies[random.NextInt(copies.Count)];       // prefer copies
            }
            int length = op[1] - op[0] + 1;
            int size = random.NextBoolean() ? Math.Min(length, 32) : 1 + random.NextInt(Math.Min(length, 12));
            int start = op[0] + (random.NextBoolean() ? 0 : random.NextInt(length - size + 1));
            Array.Fill(dictionary, true, start, size);
        }

        /// <summary>Grows a literal run past its end by a few units.</summary>
        private void Extend(bool[] dictionary)
        {
            int[]? run = PickRun();
            if (run == null)
            {
                return;
            }
            int size = 1 + random.NextInt(8);
            if (random.NextBoolean())
            {
                int to = Math.Min(count, run[1] + 1 + size);
                Array.Fill(dictionary, true, run[1] + 1, to - (run[1] + 1));
            }
            else
            {
                int from = Math.Max(0, run[0] - size);
                Array.Fill(dictionary, true, from, run[0] - from);
            }
        }

        /// <summary>Shortens a literal run at either end by a unit or a few.</summary>
        private void Trim(bool[] dictionary)
        {
            int[]? run = PickRun();
            if (run == null)
            {
                return;
            }
            int length = run[1] - run[0] + 1;
            int size = 1 + random.NextInt(Math.Min(length, 3));
            if (random.NextBoolean())
            {
                Array.Fill(dictionary, false, run[1] + 1 - size, size);
            }
            else
            {
                Array.Fill(dictionary, false, run[0], size);
            }
        }

        private bool[] LiteralMask(St4Block parsed) => St4LiteralCopySearch.LiteralMask(parsed, count);
    }

    internal static List<St4Block> Blocks(St4Block chain)
    {
        var list = new List<St4Block>();
        for (St4Block? block = chain; block != null && block.Index >= 0; block = block.Chain)
        {
            list.Add(block);
        }
        list.Reverse();
        return list;
    }

    internal static bool[] LiteralMask(St4Block chain, int count)
    {
        bool[] literal = new bool[count];
        int previous = -1;
        foreach (St4Block block in Blocks(chain))
        {
            if (block.Offset == 0)
            {
                for (int p = previous + 1; p <= block.Index; p++)
                {
                    literal[p] = true;
                }
            }
            previous = block.Index;
        }
        return literal;
    }

    private static bool[] Filled(bool[] dictionary)
    {
        bool[] result = (bool[])dictionary.Clone();
        int run = 0;
        for (int p = 0; p < result.Length; p++)
        {
            if (result[p])
            {
                if (run > 0 && run <= Hole)
                {
                    Array.Fill(result, true, p - run, run);
                }
                run = 0;
            }
            else
            {
                run++;
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- parser

    /// <summary>
    /// The exact parse for one dictionary, on arrays reused across calls. Ring
    /// offsets 1..window and copy distances window+1..count-1 share one state
    /// index space and never meet.
    /// </summary>
    internal sealed class Parser
    {
        private readonly int[] units;
        private readonly int count;
        private readonly int literalBits;
        private readonly int window;
        private readonly int reach;

        // Per state index: the best chain ending in a match or copy there,
        // its cost, end and how to rebuild it, and its literal extension.
        private readonly int[] stateBits;
        private readonly int[] stateEnd;
        private readonly byte[] stateKind;
        private readonly int[] stateAux;
        private readonly int[] statePred;
        private readonly int[] stateNode;
        private readonly int[] litBits;
        private readonly int[] litEnd;
        private readonly int[] litNode;
        private readonly int[] matchLength;
        private readonly int[] stamp;

        // Per position: the winner, and the best match or copy ending there.
        private readonly int[] optimalBits;
        private readonly int[] winNode;
        private readonly int[] matchNodeSlot;         // by end + 1, so -1 has a slot
        private readonly int[] bestLength;

        // The dictionary as prefix counts, and the input's two-unit chains:
        // the previous position with the same two units, the same for every
        // dictionary.
        private readonly int[] forcedBefore;
        private readonly int[] prevSame2;
        private bool[] forced = Array.Empty<bool>();

        // Distances visited at the previous position, whose runs may end at
        // this one, and distances whose last copy could still be repped.
        private int[] activePrev;
        private int[] activeCur;
        private int activePrevCount;
        private int activeCurCount;
        private readonly int[] repable;
        private readonly bool[] inRepable;
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
        private readonly int half;
        private readonly long[] tree;

        // Checkpoints: the state before position k*checkpoint, for the base
        // dictionary, the last parse accepted, and for the parse under way.
        // A parse restarts from the last checkpoint before its dictionary
        // first differs from the base's, since nothing before depends on
        // what comes after. Nodes are appended past the base's, so a
        // rejected parse leaves the base's intact.
        private readonly int checkpoint;
        private readonly Snapshot[] baseline;
        private readonly Snapshot[] proposal;
        private bool[] baseForced = Array.Empty<bool>();
        private bool hasBase;
        private int poolTop;
        private int sharedUpTo;                    // checkpoints the parse under way shares
        private bool fresh;                        // the parse under way started from scratch
        private int fullNodes;                     // the nodes a parse from scratch takes

        internal Parser(int[] units, int unit, int window)
        {
            this.units = units;
            count = units.Length;
            literalBits = 8 * unit;
            this.window = window;
            reach = St4Format.MaxOffsetUnits(unit);
            int size = Math.Max(count, window) + 1;
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
            bestLength = new int[Math.Max(count, 3)];
            forcedBefore = new int[count + 1];
            prevSame2 = new int[count];
            activePrev = new int[size];
            activeCur = new int[size];
            repable = new int[size];
            inRepable = new bool[size];
            var last = new Dictionary<long, int>();
            for (int p = 0; p + 1 < count; p++)
            {
                long key = ((long)units[p] << 32) | (units[p + 1] & 0xFFFFFFFFL);
                prevSame2[p] = last.TryGetValue(key, out int previous) ? previous : -1;
                last[key] = p;
            }
            if (count > 0)
            {
                prevSame2[count - 1] = -1;
            }
            int h = 1;
            while (h < count + 1)
            {
                h <<= 1;
            }
            half = h;
            tree = new long[2 * h];
            checkpoint = Math.Max(1024, (count + 7) / 8);
            int slots = (count + checkpoint - 1) / checkpoint;
            baseline = new Snapshot[slots];
            proposal = new Snapshot[slots];
            for (int k = 0; k < slots; k++)
            {
                baseline[k] = new Snapshot(size, count);
                proposal[k] = new Snapshot(size, count);
            }
        }

        /// <summary>A parse's whole state before a checkpoint position.</summary>
        private sealed class Snapshot
        {
            internal readonly int[] StateBits;
            internal readonly int[] StateEnd;
            internal readonly byte[] StateKind;
            internal readonly int[] StateAux;
            internal readonly int[] StatePred;
            internal readonly int[] StateNode;
            internal readonly int[] LitBits;
            internal readonly int[] LitEnd;
            internal readonly int[] LitNode;
            internal readonly int[] MatchLength;
            internal readonly int[] Stamp;
            internal readonly int[] OptimalBits;
            internal readonly int[] WinNode;
            internal readonly int[] MatchNodeSlot;
            internal readonly long[] Leaves;
            internal readonly int[] ActivePrev;
            internal readonly int[] ActiveCur;
            internal readonly int[] Repable;
            internal int ActivePrevCount;
            internal int ActiveCurCount;
            internal int RepableCount;
            internal int Nodes;
            internal bool Valid;

            internal Snapshot(int size, int count)
            {
                StateBits = new int[size];
                StateEnd = new int[size];
                StateKind = new byte[size];
                StateAux = new int[size];
                StatePred = new int[size];
                StateNode = new int[size];
                LitBits = new int[size];
                LitEnd = new int[size];
                LitNode = new int[size];
                MatchLength = new int[size];
                Stamp = new int[size];
                OptimalBits = new int[count];
                WinNode = new int[count];
                MatchNodeSlot = new int[count + 1];
                Leaves = new long[count + 1];
                ActivePrev = new int[size];
                ActiveCur = new int[size];
                Repable = new int[size];
            }
        }

        internal St4Block Parse(bool[] dictionary)
        {
            int from = 0;
            if (hasBase)
            {
                from = count - 1;
                for (int p = 0; p < count; p++)
                {
                    if (dictionary[p] != baseForced[p])
                    {
                        from = p;
                        break;
                    }
                }
            }
            int slot = from / checkpoint;
            while (slot > 0 && !baseline[slot].Valid)
            {
                slot--;
            }
            sharedUpTo = slot;
            fresh = slot == 0;
            forced = dictionary;
            int start = slot * checkpoint;
            if (slot == 0)
            {
                Prepare();
            }
            else
            {
                Restore(baseline[slot]);
            }
            for (int p = start; p < count; p++)
            {
                forcedBefore[p + 1] = forcedBefore[p] + (forced[p] ? 1 : 0);
            }
            for (int index = start; index < count; index++)
            {
                if (index > 0 && index % checkpoint == 0)
                {
                    TakeSnapshot(proposal[index / checkpoint], index);
                }
                bool literalOnly = forced[index];
                int value = units[index];
                bestLengthSize = 2;

                // The literal channel: the best match or copy end, per gamma
                // class of the run length that reaches here from it.
                int litCand = int.MaxValue;
                int litE = 0;
                for (int k = 0; ; k++)
                {
                    int slotHi = index - (1 << k) + 1;
                    if (slotHi < 0)
                    {
                        break;
                    }
                    int slotLo = Math.Max(0, index - (2 << k) + 2);
                    long found = Query(slotLo, slotHi);
                    if (found != long.MaxValue)
                    {
                        int candidate = (int)(found >> 32) + index * literalBits + 2 + 2 * k;
                        if (candidate < litCand)
                        {
                            litCand = candidate;
                            litE = (int)found - 1;
                        }
                    }
                }

                bestMatch = int.MaxValue;
                bestMatchIdx = -1;

                // Ring offsets: the reference DP.
                int maxOffset = (int)Math.Clamp((long)index, St4Optimizer.InitialOffset, window);
                for (int offset = 1; offset <= maxOffset; offset++)
                {
                    if (!literalOnly && index != 0 && value == units[index - offset])
                    {
                        if (litEnd[offset] != None)
                        {
                            if (matchLength[offset] == 0)
                            {
                                litNode[offset] = NewNode(Literals, litEnd[offset], 0,
                                    stateEnd[offset], Node(offset), litBits[offset]);
                            }
                            int bits = litBits[offset] + 1
                                + EliasGammaBits(index - litEnd[offset]);
                            SetState(offset, bits, index, Rep, 0, litNode[offset]);
                            if (bits < bestMatch)
                            {
                                bestMatch = bits;
                                bestMatchIdx = offset;
                            }
                        }
                        if (++matchLength[offset] > 1)
                        {
                            bestLengthSize = ExtendBestLength(bestLengthSize, matchLength[offset],
                                index);
                            int length = bestLength[matchLength[offset]];
                            int bits = optimalBits[index - length] + 3
                                + (offset > St4Format.ByteOffsetLimit ? 16 : 8)
                                + EliasGammaBits(length - 1);
                            if (stateEnd[offset] != index || stateBits[offset] > bits)
                            {
                                SetState(offset, bits, index, New, length, winNode[index - length]);
                                if (bits < bestMatch)
                                {
                                    bestMatch = bits;
                                    bestMatchIdx = offset;
                                }
                            }
                        }
                    }
                    else
                    {
                        matchLength[offset] = 0;
                        if (stateEnd[offset] != None)
                        {
                            int length = index - stateEnd[offset];
                            litBits[offset] = stateBits[offset] + 1 + EliasGammaBits(length)
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
                (activePrev, activeCur) = (activeCur, activePrev);
                activePrevCount = activeCurCount;
                activeCurCount = 0;
                if (!literalOnly)
                {
                    if (index + 1 < count)
                    {
                        for (int p = prevSame2[index]; p >= 0; p = prevSame2[p])
                        {
                            if (forced[p] && forced[p + 1] && index - p > window)
                            {
                                Visit(index, index - p);
                            }
                        }
                    }
                    for (int a = 0; a < activePrevCount; a++)
                    {
                        int distance = activePrev[a];
                        if (stamp[distance] == index - 1 && value == units[index - distance]
                            && forced[index - distance])
                        {
                            Visit(index, distance);
                        }
                    }
                    for (int r = 0; r < repableCount; r++)
                    {
                        int distance = repable[r];
                        if (stamp[distance] != index && value == units[index - distance]
                            && forced[index - distance])
                        {
                            Visit(index, distance);
                        }
                    }
                }
                // A distance stays reppable while the literals since its copy
                // have literal shadows at the source.
                for (int r = repableCount - 1; r >= 0; r--)
                {
                    int distance = repable[r];
                    if (stateEnd[distance] < index && stamp[distance] != index
                        && !forced[index - distance])
                    {
                        inRepable[distance] = false;
                        repable[r] = repable[--repableCount];
                    }
                }

                // The winner, and the literal channel's next entry.
                if (bestMatch < litCand)
                {
                    optimalBits[index] = bestMatch;
                    winNode[index] = Node(bestMatchIdx);
                }
                else
                {
                    Debug.Assert(litCand != int.MaxValue, "a literal run always reaches");
                    optimalBits[index] = litCand;
                    winNode[index] = NewNode(Literals, index, 0, litE, matchNodeSlot[litE + 1],
                        litCand);
                }
                if (bestMatch != int.MaxValue)
                {
                    matchNodeSlot[index + 1] = Node(bestMatchIdx);
                    Update(index + 1, ((long)(bestMatch - index * literalBits) << 32)
                        | (long)(index + 1));
                }
            }
            return Rebuild(winNode[count - 1]);
        }

        /// <summary>
        /// A copy distance whose unit matches at <paramref name="index"/> with
        /// the source in the dictionary: continues or starts its run, and
        /// enters the rep of the last copy at that distance and the copy
        /// ending here.
        /// </summary>
        private void Visit(int index, int distance)
        {
            int p = index - distance;
            if (stamp[distance] != index - 1)
            {
                // A run starts. Its rep continues the last copy at this
                // distance when the literals since have literal shadows at
                // the source.
                matchLength[distance] = 1;
                litNode[distance] = -1;
                if (stateEnd[distance] != None)
                {
                    int end = stateEnd[distance];
                    int between = index - 1 - end;
                    if (forcedBefore[p] - forcedBefore[end - distance + 1] == between)
                    {
                        int bits = stateBits[distance] + 1 + EliasGammaBits(between)
                            + between * literalBits;
                        litBits[distance] = bits;
                        litEnd[distance] = index - 1;
                        litNode[distance] = NewNode(Literals, index - 1, 0, end,
                            Node(distance), bits);
                    }
                }
            }
            else
            {
                matchLength[distance]++;
            }
            stamp[distance] = index;
            activeCur[activeCurCount++] = distance;
            int run = matchLength[distance];
            if (litNode[distance] >= 0)
            {
                int bits = litBits[distance] + 1 + EliasGammaBits(run);
                SetState(distance, bits, index, CopyRep, 0, litNode[distance]);
                if (bits < bestMatch)
                {
                    bestMatch = bits;
                    bestMatchIdx = distance;
                }
            }
            if (run > 1)
            {
                // Literals from the source's last unit to here: a copy of n
                // units reads back n - 1 more and leaves at least one literal
                // between.
                int between = forcedBefore[index] - forcedBefore[p];
                if (between < 2)
                {
                    return;
                }
                int longest = Math.Min(run, reach - window - between + 1);
                if (longest < 2)
                {
                    return;
                }
                bestLengthSize = ExtendBestLength(bestLengthSize, longest, index);
                int length = bestLength[longest];
                int bits = optimalBits[index - length] + 3
                    + (window + between + length - 1 > St4Format.ByteOffsetLimit ? 16 : 8)
                    + EliasGammaBits(length - 1);
                int byteLongest = Math.Min(longest, St4Format.ByteOffsetLimit + 1 - window - between);
                if (byteLongest >= 2 && byteLongest < longest)
                {
                    int shorter = bestLength[byteLongest];
                    int shorterBits = optimalBits[index - shorter] + 3 + 8
                        + EliasGammaBits(shorter - 1);
                    if (shorterBits < bits)
                    {
                        bits = shorterBits;
                        length = shorter;
                    }
                }
                if (stateEnd[distance] != index || stateBits[distance] > bits)
                {
                    SetState(distance, bits, index, Copy, length, winNode[index - length]);
                    if (bits < bestMatch)
                    {
                        bestMatch = bits;
                        bestMatchIdx = distance;
                    }
                }
            }
        }

        /// <summary>
        /// Makes the parse just made the base for the ones to come: its
        /// checkpoints stand, its nodes are kept, and the next parse is
        /// compared against its dictionary.
        /// </summary>
        internal void Accept()
        {
            Settle();
            if (poolTop > 4L * fullNodes + 65536)
            {
                // The pool holds the tails of every parse since the last full
                // one; one full parse of the base compacts it. The limit is a
                // multiple of what a full parse takes, so the compaction does
                // not find the pool too big again.
                hasBase = false;
                poolTop = 0;
                Parse(baseForced);
                Settle();
            }
        }

        /// <summary>The parse just made becomes the base.</summary>
        private void Settle()
        {
            for (int m = sharedUpTo + 1; m < baseline.Length; m++)
            {
                (baseline[m], proposal[m]) = (proposal[m], baseline[m]);
                baseline[m].Valid = true;
            }
            baseForced = (bool[])forced.Clone();
            hasBase = true;
            if (fresh)
            {
                fullNodes = nodes - poolTop;
            }
            poolTop = nodes;
        }

        private void TakeSnapshot(Snapshot into, int position)
        {
            Array.Copy(stateBits, into.StateBits, stateBits.Length);
            Array.Copy(stateEnd, into.StateEnd, stateEnd.Length);
            Array.Copy(stateKind, into.StateKind, stateKind.Length);
            Array.Copy(stateAux, into.StateAux, stateAux.Length);
            Array.Copy(statePred, into.StatePred, statePred.Length);
            Array.Copy(stateNode, into.StateNode, stateNode.Length);
            Array.Copy(litBits, into.LitBits, litBits.Length);
            Array.Copy(litEnd, into.LitEnd, litEnd.Length);
            Array.Copy(litNode, into.LitNode, litNode.Length);
            Array.Copy(matchLength, into.MatchLength, matchLength.Length);
            Array.Copy(stamp, into.Stamp, stamp.Length);
            Array.Copy(optimalBits, into.OptimalBits, position);
            Array.Copy(winNode, into.WinNode, position);
            Array.Copy(matchNodeSlot, into.MatchNodeSlot, position + 1);
            Array.Copy(tree, half, into.Leaves, 0, position + 1);
            Array.Copy(activePrev, into.ActivePrev, activePrevCount);
            Array.Copy(activeCur, into.ActiveCur, activeCurCount);
            Array.Copy(repable, into.Repable, repableCount);
            into.ActivePrevCount = activePrevCount;
            into.ActiveCurCount = activeCurCount;
            into.RepableCount = repableCount;
            into.Nodes = nodes;
            into.Valid = true;
        }

        private void Restore(Snapshot from)
        {
            nodes = poolTop;
            Array.Copy(from.StateBits, stateBits, stateBits.Length);
            Array.Copy(from.StateEnd, stateEnd, stateEnd.Length);
            Array.Copy(from.StateKind, stateKind, stateKind.Length);
            Array.Copy(from.StateAux, stateAux, stateAux.Length);
            Array.Copy(from.StatePred, statePred, statePred.Length);
            Array.Copy(from.StateNode, stateNode, stateNode.Length);
            Array.Copy(from.LitBits, litBits, litBits.Length);
            Array.Copy(from.LitEnd, litEnd, litEnd.Length);
            Array.Copy(from.LitNode, litNode, litNode.Length);
            Array.Copy(from.MatchLength, matchLength, matchLength.Length);
            Array.Copy(from.Stamp, stamp, stamp.Length);
            int position = sharedUpTo * checkpoint;
            Array.Copy(from.OptimalBits, optimalBits, position);
            Array.Copy(from.WinNode, winNode, position);
            Array.Copy(from.MatchNodeSlot, matchNodeSlot, position + 1);
            Array.Fill(tree, long.MaxValue);
            Array.Copy(from.Leaves, 0, tree, half, position + 1);
            for (int i = half - 1; i >= 1; i--)
            {
                tree[i] = Math.Min(tree[2 * i], tree[2 * i + 1]);
            }
            Array.Copy(from.ActivePrev, activePrev, from.ActivePrevCount);
            Array.Copy(from.ActiveCur, activeCur, from.ActiveCurCount);
            activePrevCount = from.ActivePrevCount;
            activeCurCount = from.ActiveCurCount;
            for (int r = 0; r < repableCount; r++)
            {
                inRepable[repable[r]] = false;
            }
            Array.Copy(from.Repable, repable, from.RepableCount);
            repableCount = from.RepableCount;
            for (int r = 0; r < repableCount; r++)
            {
                inRepable[repable[r]] = true;
            }
            bestLength[2] = 2;
        }

        private void Prepare()
        {
            Array.Fill(stateEnd, None);
            Array.Fill(litEnd, None);
            Array.Fill(litNode, -1);
            Array.Fill(stateNode, -1);
            Array.Fill(matchLength, 0);
            Array.Fill(stamp, -2);
            Array.Fill(tree, long.MaxValue);
            bestLength[2] = 2;
            nodes = poolTop;
            // The fake block every chain hangs from: one unit back, ending
            // before the stream, costing -1 so the first flag is free.
            int root = NewNode(New, -1, St4Optimizer.InitialOffset, 0, -1, -1);
            stateBits[St4Optimizer.InitialOffset] = -1;
            stateEnd[St4Optimizer.InitialOffset] = -1;
            stateKind[St4Optimizer.InitialOffset] = New;
            stateNode[St4Optimizer.InitialOffset] = root;
            matchNodeSlot[0] = root;
            Update(0, (long)(literalBits - 1) << 32);
            activePrevCount = 0;
            activeCurCount = 0;
            for (int r = 0; r < repableCount; r++)
            {
                inRepable[repable[r]] = false;
            }
            repableCount = 0;
        }

        private int ExtendBestLength(int size, int target, int index)
        {
            if (size < target)
            {
                int bits = optimalBits[index - bestLength[size]] + EliasGammaBits(bestLength[size] - 1);
                do
                {
                    size++;
                    int shorterBits = optimalBits[index - size] + EliasGammaBits(size - 1);
                    if (shorterBits <= bits)
                    {
                        bestLength[size] = size;
                        bits = shorterBits;
                    }
                    else
                    {
                        bestLength[size] = bestLength[size - 1];
                    }
                }
                while (size < target);
            }
            return size;
        }

        private void SetState(int idx, int bits, int end, byte kind, int aux, int pred)
        {
            stateBits[idx] = bits;
            stateEnd[idx] = end;
            stateKind[idx] = kind;
            stateAux[idx] = aux;
            statePred[idx] = pred;
            stateNode[idx] = -1;
            if (idx > window && !inRepable[idx])
            {
                inRepable[idx] = true;
                repable[repableCount++] = idx;
            }
        }

        /// <summary>The state's node, made when first needed.</summary>
        private int Node(int idx)
        {
            if (stateNode[idx] < 0)
            {
                stateNode[idx] = NewNode(stateKind[idx], stateEnd[idx],
                    idx <= window ? idx : -idx, stateAux[idx], statePred[idx], stateBits[idx]);
            }
            return stateNode[idx];
        }

        private int NewNode(byte kind, int end, int offset, int aux, int pred, int bits)
        {
            if (nodes == nodeKind.Length)
            {
                int grown = nodes * 2;
                Array.Resize(ref nodeKind, grown);
                Array.Resize(ref nodeEnd, grown);
                Array.Resize(ref nodeOffset, grown);
                Array.Resize(ref nodeAux, grown);
                Array.Resize(ref nodePred, grown);
                Array.Resize(ref nodeBits, grown);
            }
            nodeKind[nodes] = kind;
            nodeEnd[nodes] = end;
            nodeOffset[nodes] = offset;
            nodeAux[nodes] = aux;
            nodePred[nodes] = pred;
            nodeBits[nodes] = bits;
            return nodes++;
        }

        private St4Block Rebuild(int last)
        {
            var order = new List<int>();
            for (int node = last; node >= 0; node = nodePred[node])
            {
                order.Add(node);
            }
            St4Block chain = new St4Block(-1, -1, St4Optimizer.InitialOffset, null);
            for (int i = order.Count - 2; i >= 0; i--)
            {
                int node = order[i];
                chain = new St4Block(nodeBits[node], nodeEnd[node],
                    nodeKind[node] == Literals ? 0 : nodeOffset[node], chain);
            }
            return chain;
        }

        private void Update(int slot, long value)
        {
            int i = half + slot;
            tree[i] = value;
            for (i >>= 1; i >= 1; i >>= 1)
            {
                tree[i] = Math.Min(tree[2 * i], tree[2 * i + 1]);
            }
        }

        private long Query(int lo, int hi)
        {
            long result = long.MaxValue;
            int l = half + lo;
            int r = half + hi + 1;
            while (l < r)
            {
                if ((l & 1) == 1)
                {
                    result = Math.Min(result, tree[l++]);
                }
                if ((r & 1) == 1)
                {
                    result = Math.Min(result, tree[--r]);
                }
                l >>= 1;
                r >>= 1;
            }
            return result;
        }
    }
}
