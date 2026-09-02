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
/// <see cref="St4Optimizer"/> without allocation: the same parse, the same bytes
/// out, in a fraction of the time.
/// </summary>
/// <remarks>
/// The same DP runs forward on primitive arrays, recording per position the
/// winning cost and a three-int descriptor of the winner, and
/// <see cref="St4ChainRebuilder"/> builds only the winning chain. Candidates are
/// evaluated in the same order with the same strictly-better rule, so ties
/// fall as in the reference and the output is byte-identical, which a test
/// asserts.
/// </remarks>
public sealed class St4FastOptimizer
{
    /// <summary>The offset a stream starts with, as ZX1: one unit.</summary>
    public const int InitialOffset = St4Optimizer.InitialOffset;

    /// <summary>No state, and no literal run: nothing has happened at this offset yet.</summary>
    private const int None = int.MinValue;

    private readonly int[] units;
    private readonly int literalBits;
    private readonly int offsetLimit;

    // Per position: the winning cost, and the descriptor to rebuild it.
    private readonly int[] optimalBits;
    private readonly byte[] winKind;
    private readonly int[] winOffset;
    private readonly int[] winAux;

    private St4FastOptimizer(int[] units, int unit, int offsetLimit)
    {
        this.units = units;
        this.literalBits = 8 * unit;
        this.offsetLimit = offsetLimit;
        this.optimalBits = new int[units.Length];
        this.winKind = new byte[units.Length];
        this.winOffset = new int[units.Length];
        this.winAux = new int[units.Length];
    }

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

    /// <summary>
    /// The last block of the optimal parse of <paramref name="units"/>: the
    /// chain <see cref="St4Optimizer.Optimize(int[], int, int, bool)"/> returns,
    /// byte for byte.
    /// </summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit, which sets what a literal costs.</param>
    /// <param name="offsetLimit">The furthest a match may reach back, in units.</param>
    /// <param name="progress">Whether to report on stdout, as <see cref="ProgressMeter"/>.</param>
    /// <returns>The final block of the optimal parse chain.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="units"/> is null.</exception>
    public static St4Block Optimize(int[] units, int unit, int offsetLimit, bool progress)
    {
        ArgumentNullException.ThrowIfNull(units);
        var optimizer = new St4FastOptimizer(units, unit, offsetLimit);
        optimizer.Forward(progress);
        return new St4ChainRebuilder(units, optimizer.literalBits, optimizer.optimalBits,
            optimizer.winKind, optimizer.winOffset, optimizer.winAux).Rebuild();
    }

    /// <summary>
    /// The winning cost per position, for the tests that hold other optimizers
    /// to this one: the optimum is unique, so an exact optimizer produces this
    /// array.
    /// </summary>
    internal static int[] Costs(int[] units, int unit, int offsetLimit)
    {
        var optimizer = new St4FastOptimizer(units, unit, offsetLimit);
        optimizer.Forward(false);
        return optimizer.optimalBits;
    }

    private static int EliasGammaBits(int value) =>
        2 * (31 - System.Numerics.BitOperations.LeadingZeroCount((uint)value)) + 1;

    /// <summary>
    /// The DP of <see cref="St4Optimizer"/>, candidate for candidate, on
    /// primitives. Per offset: the best chain ending in a match at
    /// <c>stateEnd</c> costing <c>stateBits</c>, and the best chain ending in
    /// a literal run at <c>litEnd</c> costing <c>litBits</c>. A position's
    /// winner is recorded when it takes the lead, and replaced only by a
    /// strictly better one, so ties keep the earlier candidate.
    /// </summary>
    private void Forward(bool progress)
    {
        int count = units.Length;
        int width = (int)Math.Clamp(count - 1L, InitialOffset, offsetLimit);
        int[] stateBits = new int[width + 1];
        int[] stateEnd = new int[width + 1];
        int[] litBits = new int[width + 1];
        int[] litEnd = new int[width + 1];
        int[] matchLength = new int[width + 1];
        Array.Fill(stateEnd, None);
        Array.Fill(litEnd, None);
        int[] bestLength = new int[Math.Max(count, 3)];
        bestLength[2] = 2;

        // The fake block every chain hangs from, as the reference: one unit
        // back, ending just before the stream.
        stateBits[InitialOffset] = -1;
        stateEnd[InitialOffset] = -1;

        var meter = new ProgressMeter(
            ProgressMeter.TotalSteps(count, 0, offsetLimit), progress);

        for (int index = 0; index < count; index++)
        {
            int maxOffset = (int)Math.Clamp((long)index, InitialOffset, offsetLimit);
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
                            + EliasGammaBits(index - litEnd[offset]);
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
                                + EliasGammaBits(bestLength[bestLengthSize] - 1);
                            do
                            {
                                bestLengthSize++;
                                int shorterBits = optimalBits[index - bestLengthSize]
                                    + EliasGammaBits(bestLengthSize - 1);
                                if (shorterBits <= bits)
                                {
                                    bestLength[bestLengthSize] = bestLengthSize;
                                    bits = shorterBits;
                                }
                                else
                                {
                                    bestLength[bestLengthSize] = bestLength[bestLengthSize - 1];
                                }
                            }
                            while (bestLengthSize < matchLength[offset]);
                        }
                        int length = bestLength[matchLength[offset]];
                        int newBits = optimalBits[index - length] + 3
                            + (offset > St4Format.ByteOffsetLimit ? 16 : 8)
                            + EliasGammaBits(length - 1);
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
                        int bits = stateBits[offset] + 1 + EliasGammaBits(length)
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
            optimalBits[index] = best;
            meter.Advance(maxOffset);
        }
        meter.Finish();
    }
}
