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
/// The optimal parser: ZX1's, moved from bytes to k-byte units, and the
/// readable reference the fast optimizers are held to.
/// </summary>
/// <remarks>
/// For every position it keeps, per offset, the cheapest chain ending in a
/// literal run and the cheapest ending in a match, and takes the best. Only
/// the costs differ from ZX1's: a literal unit costs <c>8 * k</c> bits, an
/// offset counts units, a new-offset match pays three control bits and a
/// byte or a word. The result is a chain of <see cref="St4Block"/>s, last block
/// first, which <see cref="St4Compressor"/> walks in reverse.
/// </remarks>
public static class St4Optimizer
{
    /// <summary>The offset a stream starts with, as ZX1: one unit.</summary>
    public const int InitialOffset = 1;

    /// <summary>
    /// The last block of the optimal parse of <paramref name="units"/>,
    /// reporting progress on stdout.
    /// </summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit, which sets what a literal costs.</param>
    /// <param name="offsetLimit">The furthest a match may reach back, in units.</param>
    /// <returns>The final block of the optimal parse chain.</returns>
    public static St4Block Optimize(int[] units, int unit, int offsetLimit) =>
        Optimize(units, unit, offsetLimit, true);

    /// <summary>The last block of the optimal parse of <paramref name="units"/>: every position against every offset.</summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit, which sets what a literal costs.</param>
    /// <param name="offsetLimit">The furthest a match may reach back, in units.</param>
    /// <param name="progress">Whether to report on stdout, as <see cref="ProgressMeter"/>.</param>
    /// <returns>The final block of the optimal parse chain.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="units"/> is null.</exception>
    public static St4Block Optimize(int[] units, int unit, int offsetLimit, bool progress)
    {
        ArgumentNullException.ThrowIfNull(units);
        int literalBits = 8 * unit;
        int maxOffset = OffsetCeiling(units.Length - 1, offsetLimit);
        var lastLiteral = new St4Block?[maxOffset + 1];
        var lastMatch = new St4Block?[maxOffset + 1];
        var optimal = new St4Block?[units.Length];
        int[] matchLength = new int[maxOffset + 1];
        int[] bestLength = new int[Math.Max(units.Length, 3)];
        bestLength[2] = 2;

        // A fake block for the first real one to chain from.
        lastMatch[InitialOffset] = new St4Block(-1, -1, InitialOffset, null);

        var meter = new ProgressMeter(
            ProgressMeter.TotalSteps(units.Length, 0, offsetLimit), progress);

        for (int index = 0; index < units.Length; index++)
        {
            maxOffset = OffsetCeiling(index, offsetLimit);
            int bestLengthSize = 2;
            for (int offset = 1; offset <= maxOffset; offset++)
            {
                if (index != 0 && index >= offset && units[index] == units[index - offset])
                {
                    // A match reusing the last offset: one unit at least, which
                    // at k = 4 replaces four bytes with a few bits.
                    St4Block? literal = lastLiteral[offset];
                    if (literal != null)
                    {
                        int length = index - literal.Index;
                        int bits = literal.Bits + 1 + EliasGammaBits(length);
                        var match = new St4Block(bits, index, offset, literal);
                        lastMatch[offset] = match;
                        optimal[index] = Better(optimal[index], match);
                    }
                    // A match with a new offset: two units at least.
                    if (++matchLength[offset] > 1)
                    {
                        if (bestLengthSize < matchLength[offset])
                        {
                            St4Block best = optimal[index - bestLength[bestLengthSize]]!;
                            int bestBits = best.Bits + EliasGammaBits(bestLength[bestLengthSize] - 1);
                            do
                            {
                                bestLengthSize++;
                                St4Block shorter = optimal[index - bestLengthSize]!;
                                int shorterBits = shorter.Bits + EliasGammaBits(bestLengthSize - 1);
                                if (shorterBits <= bestBits)
                                {
                                    bestLength[bestLengthSize] = bestLengthSize;
                                    bestBits = shorterBits;
                                }
                                else
                                {
                                    bestLength[bestLengthSize] = bestLength[bestLengthSize - 1];
                                }
                            }
                            while (bestLengthSize < matchLength[offset]);
                        }
                        int length = bestLength[matchLength[offset]];
                        St4Block previous = optimal[index - length]!;
                        int bits = previous.Bits + 3
                            + (offset > St4Format.ByteOffsetLimit ? 16 : 8)
                            + EliasGammaBits(length - 1);
                        St4Block? match = lastMatch[offset];
                        if (match == null || match.Index != index || match.Bits > bits)
                        {
                            match = new St4Block(bits, index, offset, previous);
                            lastMatch[offset] = match;
                            optimal[index] = Better(optimal[index], match);
                        }
                    }
                }
                else
                {
                    // Literals: the run's length goes in stream A, its payload
                    // in stream B, and both are paid for here.
                    matchLength[offset] = 0;
                    St4Block? match = lastMatch[offset];
                    if (match != null)
                    {
                        int length = index - match.Index;
                        int bits = match.Bits + 1 + EliasGammaBits(length)
                            + length * literalBits;
                        var literal = new St4Block(bits, index, 0, match);
                        lastLiteral[offset] = literal;
                        optimal[index] = Better(optimal[index], literal);
                    }
                }
            }
            meter.Advance(maxOffset);
        }
        meter.Finish();

        return optimal[units.Length - 1]!;
    }

    private static int OffsetCeiling(int index, int offsetLimit) =>
        Math.Clamp(index, InitialOffset, offsetLimit);

    private static int EliasGammaBits(int value)
    {
        int bits = 1;
        while ((value >>= 1) != 0)
        {
            bits += 2;
        }
        return bits;
    }

    private static St4Block Better(St4Block? current, St4Block candidate) =>
        current == null || current.Bits > candidate.Bits ? candidate : current;
}
