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
/// The input as k-byte units, one per <c>int</c>, big-endian as the 68000
/// loads them. Input that is not a multiple of k is padded with zeros, and
/// the padding is part of the decoder's output.
/// </summary>
public static class Units
{
    /// <summary>The units, big-endian.</summary>
    /// <param name="data">The bytes to pack.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <returns>The units, the last padded with zero bytes if needed.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="data"/> is null.</exception>
    public static int[] Split(byte[] data, int unit)
    {
        ArgumentNullException.ThrowIfNull(data);
        int count = (data.Length + unit - 1) / unit;
        int[] units = new int[count];
        for (int index = 0; index < count; index++)
        {
            int value = 0;
            for (int byteIndex = 0; byteIndex < unit; byteIndex++)
            {
                int at = index * unit + byteIndex;
                value = (value << 8) | (at < data.Length ? data[at] : 0);
            }
            units[index] = value;
        }
        return units;
    }

    /// <summary>Writes one unit's bytes, most significant first.</summary>
    /// <param name="target">The destination array.</param>
    /// <param name="at">Where the unit's first byte goes.</param>
    /// <param name="value">The unit.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    public static void Write(byte[] target, int at, int value, int unit)
    {
        ArgumentNullException.ThrowIfNull(target);
        for (int byteIndex = unit - 1; byteIndex >= 0; byteIndex--)
        {
            target[at + byteIndex] = unchecked((byte)value);
            value >>>= 8;
        }
    }

    /// <summary>The padded length in bytes: what the decoder produces.</summary>
    public static int PaddedLength(int length, int unit) =>
        (length + unit - 1) / unit * unit;
}
