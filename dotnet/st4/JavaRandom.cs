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
/// java.util.Random, the 48-bit LCG, so the search steps as the Java one does
/// from the same seed and the test fixtures are byte-identical to the Java
/// suite's. System.Random produces a different sequence.
/// </summary>
internal sealed class JavaRandom
{
    private const long Multiplier = 0x5DEECE66D;
    private const long Addend = 0xB;
    private const long Mask = (1L << 48) - 1;

    private long seed;

    internal JavaRandom(long seed)
    {
        this.seed = (seed ^ Multiplier) & Mask;
    }

    internal int NextInt(int bound)
    {
        if (bound <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(bound));
        }

        if ((bound & -bound) == bound)
        {
            return (int)((bound * (long)Next(31)) >> 31);
        }

        int bits;
        int value;
        do
        {
            bits = Next(31);
            value = bits % bound;
        }
        while (unchecked(bits - value + (bound - 1)) < 0);

        return value;
    }

    internal bool NextBoolean() => Next(1) != 0;

    internal double NextDouble() => (((long)Next(26) << 27) + Next(27)) * (1.0 / (1L << 53));

    internal void NextBytes(byte[] bytes)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        for (int index = 0; index < bytes.Length;)
        {
            int random = Next(32);
            for (int count = Math.Min(bytes.Length - index, sizeof(int)); count-- > 0; random >>= 8)
            {
                bytes[index++] = unchecked((byte)random);
            }
        }
    }

    private int Next(int bits)
    {
        seed = unchecked((seed * Multiplier + Addend) & Mask);
        return unchecked((int)(seed >>> (48 - bits)));
    }
}
