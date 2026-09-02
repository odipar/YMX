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
/// ST4: ZX1's three block types at a unit size of 1, 2 or 4 bytes, in four
/// streams that a 68000 reads each at its own width. The Java
/// <c>St4Format</c> is the reference.
/// </summary>
/// <remarks>
/// <para>Stream A holds the bits, read a word at a time; stream B the literal
/// units, stream C the byte offsets, stream D the word offsets. Lengths and
/// offsets count units of k bytes. An offset of at most the window M is a
/// match; an offset beyond M copies offset minus M units from behind the
/// literal read pointer, which stays where it is, and advances the offset by
/// what it copied. The end marker's extra bit repeats the stream from a loop
/// point, the distance written as one last word in stream D; a loop longer
/// than the window is replayed by the caller from the rewind point the
/// header gives.</para>
/// <para>The header is twenty-eight bytes: a signature holding magic, version
/// and k in one long, the padded output size, where streams B, C and D begin
/// relative to the header, the rewind point, and the window. Stream A begins
/// where the header ends and each stream runs to the next.</para>
/// </remarks>
public static class St4Format
{
    /// <summary><c>'S4'</c>, the top half of every signature.</summary>
    public const int Magic = 0x53340000;

    /// <summary>The format version, the third byte of the signature.</summary>
    public const int Version = 7;

    /// <summary>Byte offset of the signature long in a container.</summary>
    public const int OffsetSignature = 0;

    /// <summary>Byte offset of the padded output size.</summary>
    public const int OffsetSize = 4;

    /// <summary>Byte offset of stream B's header-relative position: the literals.</summary>
    public const int OffsetLiteral = 8;

    /// <summary>Byte offset of stream C's header-relative position: the byte offsets.</summary>
    public const int OffsetByteOffsets = 12;

    /// <summary>Byte offset of stream D's header-relative position: the word offsets.</summary>
    public const int OffsetWordOffsets = 16;

    /// <summary>Byte offset of the rewind point, in bytes like the size.</summary>
    public const int OffsetRewind = 20;

    /// <summary>Byte offset of the window, in units.</summary>
    public const int OffsetWindow = 24;

    /// <summary>Twenty-eight bytes; stream A begins where the header ends.</summary>
    public const int HeaderSize = 28;

    /// <summary>The rewind field of a stream that ends or loops by itself.</summary>
    public const int NoRewind = -1;

    /// <summary>
    /// The furthest any offset reaches, in bytes. A word offset is stored as
    /// <c>-offset * k</c> and the decoder installs it unchanged, so the limit
    /// is what fits a signed word.
    /// </summary>
    public const int MaxOffset = 32_512;

    /// <summary>The furthest a byte offset reaches, in units: two banks of 256.</summary>
    public const int ByteOffsetLimit = 512;

    /// <summary>The longest operation the 68000 decoders can count, in units.</summary>
    public const int MaxOp = 65_535;

    /// <summary>Magic, version and unit size in one long: a decoder checks all three with one <c>cmp.l</c>.</summary>
    public static int Signature(int unit) => Magic | (Version << 8) | unit;

    /// <summary>Whether <paramref name="unit"/> is a unit size the format has.</summary>
    public static bool IsUnitSize(int unit) => unit is 1 or 2 or 4;

    /// <summary>The reason <paramref name="unit"/> cannot be used, or an empty string.</summary>
    public static string CheckUnit(int unit) =>
        IsUnitSize(unit) ? "" : $"unit size {unit} is not 1, 2 or 4";

    /// <summary>How far back a match may reach at this unit size, in units.</summary>
    public static int MaxOffsetUnits(int unit) => MaxOffset / unit;

    /// <summary>What a container holds.</summary>
    /// <param name="Unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="Size">The padded output size in bytes, a multiple of the unit.</param>
    /// <param name="Control">Stream A, the bits.</param>
    /// <param name="Literal">Stream B, the literal payload.</param>
    /// <param name="ByteOffsets">Stream C, one byte per offset.</param>
    /// <param name="WordOffsets">Stream D, one word per offset.</param>
    /// <param name="Rewind">The rewind point in bytes, or <see cref="NoRewind"/>.</param>
    /// <param name="Window">The window in units: matches within it, copies from stream B beyond.</param>
    public sealed record Container(int Unit, int Size, byte[] Control, byte[] Literal,
        byte[] ByteOffsets, byte[] WordOffsets, int Rewind, int Window);

    /// <summary>
    /// Reads a container, checking what a decoder trusts. The streams returned
    /// may carry up to three bytes of alignment padding, since each runs to
    /// the next.
    /// </summary>
    /// <param name="file">The complete container, header first.</param>
    /// <returns>What the container holds.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="file"/> is null.</exception>
    /// <exception cref="InvalidDataException">
    /// The file is not an ST4 file of this version, or its streams do not lie
    /// in order inside it.
    /// </exception>
    public static Container Read(byte[] file)
    {
        ArgumentNullException.ThrowIfNull(file);
        if (file.Length < HeaderSize)
        {
            throw new InvalidDataException("too short to be an ST4 file");
        }
        int signature = LongAt(file, OffsetSignature);
        if ((signature & unchecked((int)0xFFFF0000)) != Magic)
        {
            throw new InvalidDataException("not an ST4 file");
        }
        int version = (signature >> 8) & 0xFF;
        if (version != Version)
        {
            throw new InvalidDataException($"ST4 format version {version}, not {Version}");
        }
        int unit = signature & 0xFF;
        string problem = CheckUnit(unit);
        if (problem.Length != 0)
        {
            throw new InvalidDataException(problem);
        }
        int size = LongAt(file, OffsetSize);
        if (size < 0 || size % unit != 0)
        {
            throw new InvalidDataException(
                $"output size {size} is not a whole number of {unit}-byte units");
        }
        int rewind = LongAt(file, OffsetRewind);
        if (rewind != NoRewind && (rewind < 0 || rewind >= size || rewind % unit != 0))
        {
            throw new InvalidDataException($"rewind point {rewind} is not a unit of the output");
        }
        int window = LongAt(file, OffsetWindow);
        if (window < 1 || window > MaxOffsetUnits(unit))
        {
            throw new InvalidDataException($"window {window} is not 1..{MaxOffsetUnits(unit)} units");
        }

        // The streams lie in the file as A, B, C, D.
        int[] edge =
        {
            HeaderSize, LongAt(file, OffsetLiteral), LongAt(file, OffsetByteOffsets),
            LongAt(file, OffsetWordOffsets), file.Length,
        };
        for (int i = 1; i < edge.Length - 1; i++)
        {
            if (edge[i] % 4 != 0)
            {
                throw new InvalidDataException(
                    $"stream {"ABCD"[i]} does not start on a long boundary");
            }
            if (edge[i] < edge[i - 1] || edge[i] > file.Length)
            {
                throw new InvalidDataException($"stream {"ABCD"[i]} lies outside the file");
            }
        }
        return new Container(unit, size,
            file[edge[0]..edge[1]], file[edge[1]..edge[2]],
            file[edge[2]..edge[3]], file[edge[3]..edge[4]], rewind, window);
    }

    private static int LongAt(byte[] file, int at) =>
        (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
            | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
}
