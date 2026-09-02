// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace St4;

/// <summary>The reference decoder in C#, which the 68000 decoders have to agree with.</summary>
/// <remarks>
/// ZX1's state machine with four changes: literals come from stream B and
/// offsets from stream C or D by width; lengths and offsets count units; the
/// end marker's extra bit turns the end into an endless match, the repeat;
/// and an offset beyond the window copies <c>offset - window</c> units from
/// behind the literal read pointer, which stays where it is, and advances
/// the offset by what was copied. A malformed stream throws
/// <see cref="InvalidDataException"/>, where the Java reference trips an
/// assertion.
/// </remarks>
public sealed class St4Decompressor
{
    private enum State
    {
        Start,
        Literals,
        Match,
        Done,
    }

    private readonly int window;
    private readonly int rewindAt;
    private readonly byte[] control;
    private readonly byte[] literal;
    private readonly byte[] byteOffsets;
    private readonly byte[] wordOffsets;
    private readonly byte[] output;
    private readonly int unit;
    private int controlIndex;
    private int literalIndex;
    private int byteOffsetIndex;
    private int wordOffsetIndex;
    private int outputIndex;
    private int bitMask;
    private int bitValue;
    private int lastOffset = St4Optimizer.InitialOffset;
    private int repeatIndex = -1;
    private State state = State.Start;

    private St4Decompressor(byte[] control, byte[] literal, byte[] byteOffsets,
        byte[] wordOffsets, byte[] output, int unit, int window, int rewindAt)
    {
        this.window = window;
        this.rewindAt = rewindAt;
        this.control = control;
        this.literal = literal;
        this.byteOffsets = byteOffsets;
        this.wordOffsets = wordOffsets;
        this.output = output;
        this.unit = unit;
    }

    /// <summary>Decodes the streams into <paramref name="size"/> bytes.</summary>
    /// <param name="control">Stream A, the bits.</param>
    /// <param name="literal">Stream B, the literal payload.</param>
    /// <param name="byteOffsets">Stream C, one byte per offset.</param>
    /// <param name="wordOffsets">Stream D, one word per offset.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="size">The output size in bytes, a multiple of the unit.</param>
    /// <returns>The decoded bytes, padding included.</returns>
    public static byte[] Decompress(byte[] control, byte[] literal, byte[] byteOffsets,
        byte[] wordOffsets, int unit, int size) =>
        Decompress(control, literal, byteOffsets, wordOffsets, unit, size,
            St4Format.MaxOffsetUnits(unit));

    /// <summary>
    /// The output and how the stream ended: the loop point R of a repeating
    /// stream, which decodes as <c>units[0..R) units[R..O)</c> forever, or
    /// -1. A repeating stream decodes to any size from one pass up.
    /// </summary>
    /// <param name="Output">The decoded bytes, padding included.</param>
    /// <param name="RepeatIndex">The loop point as a unit index, or -1.</param>
    public sealed record Decoded(byte[] Output, int RepeatIndex);

    /// <summary>
    /// As above, at the window the stream was packed for: a match reaches at
    /// most <paramref name="window"/> units back, so a stream that decodes is
    /// safe for a ring of that many units, and an offset beyond it copies
    /// from the literal stream.
    /// </summary>
    /// <param name="control">Stream A, the bits.</param>
    /// <param name="literal">Stream B, the literal payload.</param>
    /// <param name="byteOffsets">Stream C, one byte per offset.</param>
    /// <param name="wordOffsets">Stream D, one word per offset.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="size">The output size in bytes, a multiple of the unit.</param>
    /// <param name="window">The window in units: matches within it, copies from stream B beyond.</param>
    /// <returns>The decoded bytes, padding included.</returns>
    /// <exception cref="ArgumentNullException">A stream is null.</exception>
    /// <exception cref="ArgumentOutOfRangeException"><paramref name="unit"/> is not 1, 2 or 4.</exception>
    /// <exception cref="ArgumentException"><paramref name="size"/> is not a whole number of units.</exception>
    /// <exception cref="InvalidDataException">
    /// The streams are malformed or truncated, or reach further back than
    /// <paramref name="window"/>.
    /// </exception>
    public static byte[] Decompress(byte[] control, byte[] literal, byte[] byteOffsets,
        byte[] wordOffsets, int unit, int size, int window) =>
        Decode(control, literal, byteOffsets, wordOffsets, unit, size, window).Output;

    /// <summary>As <see cref="Decompress(byte[], byte[], byte[], byte[], int, int, int)"/>,
    /// also reporting whether the stream repeats.</summary>
    /// <param name="control">Stream A, the bits.</param>
    /// <param name="literal">Stream B, the literal payload.</param>
    /// <param name="byteOffsets">Stream C, one byte per offset.</param>
    /// <param name="wordOffsets">Stream D, one word per offset.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="size">The output size in bytes, a multiple of the unit.</param>
    /// <param name="window">The window in units: matches within it, copies from stream B beyond.</param>
    /// <returns>The decoded bytes and the loop point, -1 when the stream ends.</returns>
    /// <exception cref="ArgumentNullException">A stream is null.</exception>
    /// <exception cref="ArgumentOutOfRangeException"><paramref name="unit"/> is not 1, 2 or 4.</exception>
    /// <exception cref="ArgumentException"><paramref name="size"/> is not a whole number of units.</exception>
    /// <exception cref="InvalidDataException">
    /// The streams are malformed or truncated, or reach further back than
    /// <paramref name="window"/>.
    /// </exception>
    public static Decoded Decode(byte[] control, byte[] literal, byte[] byteOffsets,
        byte[] wordOffsets, int unit, int size, int window) =>
        Decode(control, literal, byteOffsets, wordOffsets, unit, size, window,
            St4Format.NoRewind);

    /// <summary>
    /// As above, holding a stream to its rewind point: from
    /// <paramref name="rewindAt"/> bytes on, no match reaches before it, so
    /// the loop replays from the state saved there and every pass sees the
    /// same history. A stream that reaches before it would loop wrongly on
    /// the 68000, and is rejected here.
    /// </summary>
    /// <param name="control">Stream A, the bits.</param>
    /// <param name="literal">Stream B, the literal payload.</param>
    /// <param name="byteOffsets">Stream C, one byte per offset.</param>
    /// <param name="wordOffsets">Stream D, one word per offset.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="size">The output size in bytes, a multiple of the unit.</param>
    /// <param name="window">The window in units: matches within it, copies from stream B beyond.</param>
    /// <param name="rewindAt">The rewind point in bytes, or <see cref="St4Format.NoRewind"/>.</param>
    /// <returns>The decoded bytes and the loop point, -1 when the stream does not repeat.</returns>
    /// <exception cref="ArgumentNullException">A stream is null.</exception>
    /// <exception cref="ArgumentOutOfRangeException"><paramref name="unit"/> is not 1, 2 or 4.</exception>
    /// <exception cref="ArgumentException"><paramref name="size"/> is not a whole number of units.</exception>
    /// <exception cref="InvalidDataException">
    /// The streams are malformed or truncated, reach further back than
    /// <paramref name="window"/>, or reach before the rewind point.
    /// </exception>
    public static Decoded Decode(byte[] control, byte[] literal, byte[] byteOffsets,
        byte[] wordOffsets, int unit, int size, int window, int rewindAt)
    {
        ArgumentNullException.ThrowIfNull(control);
        ArgumentNullException.ThrowIfNull(literal);
        ArgumentNullException.ThrowIfNull(byteOffsets);
        ArgumentNullException.ThrowIfNull(wordOffsets);
        if (!St4Format.IsUnitSize(unit))
        {
            throw new ArgumentOutOfRangeException(nameof(unit), unit, St4Format.CheckUnit(unit));
        }
        if (size < 0 || size % unit != 0)
        {
            throw new ArgumentException(
                $"output size {size} is not a whole number of {unit}-byte units",
                nameof(size));
        }
        var decoder = new St4Decompressor(control, literal, byteOffsets, wordOffsets,
            new byte[size], unit, window, rewindAt);
        decoder.Run();
        return new Decoded(decoder.output, decoder.repeatIndex);
    }

    private void Run()
    {
        while (state != State.Done)
        {
            switch (state)
            {
                case State.Start:
                    BeginLiterals();
                    break;
                case State.Literals:
                    if (ReadBit())
                    {
                        BeginMatchFromNewOffset();
                    }
                    else
                    {
                        BeginMatchFromLastOffset();
                    }
                    break;
                case State.Match:
                    if (ReadBit())
                    {
                        BeginMatchFromNewOffset();
                    }
                    else
                    {
                        BeginLiterals();
                    }
                    break;
                case State.Done:
                default:
                    throw new InvalidOperationException("unreachable");
            }
        }
        if (outputIndex != output.Length)
        {
            throw new InvalidDataException("the streams did not fill the output");
        }
    }

    private void BeginLiterals()
    {
        int length = ReadInterlacedEliasGamma();
        if (literalIndex + length * unit > literal.Length)
        {
            throw new InvalidDataException("truncated literal stream");
        }
        for (int i = 0; i < length * unit; i++)
        {
            output[outputIndex++] = literal[literalIndex++];
        }
        state = State.Literals;
    }

    private void BeginMatchFromLastOffset()
    {
        int length = ReadInterlacedEliasGamma();
        if (lastOffset > window)
        {
            CopyFromLiterals(length);
        }
        else
        {
            Copy(length);
        }
        state = State.Match;
    }

    private void BeginMatchFromNewOffset()
    {
        // Two class bits: byte or word, then the bank, or for a word the end
        // of the stream.
        if (ReadBit())
        {
            int bank = ReadBit() ? 1 : 0;
            if (byteOffsetIndex >= byteOffsets.Length)
            {
                throw new InvalidDataException("truncated byte offsets");
            }
            lastOffset = bank * 256 + 256 - byteOffsets[byteOffsetIndex++];
        }
        else
        {
            if (ReadBit())
            {
                EndOrRepeat();
                return;
            }
            lastOffset = ReadWordOffset();
        }
        if (lastOffset <= 0)
        {
            throw new InvalidDataException("an offset must reach back at least one unit");
        }
        int length = ReadInterlacedEliasGamma() + 1;
        if (lastOffset > window)
        {
            CopyFromLiterals(length);
        }
        else
        {
            Copy(length);
        }
        state = State.Match;
    }

    /// <summary>
    /// Copies <paramref name="length"/> units from the literal stream,
    /// <c>lastOffset - window</c> units behind the read pointer, which stays
    /// where it is, and advances the offset by what it copied.
    /// </summary>
    private void CopyFromLiterals(int length)
    {
        int back = lastOffset - window;
        if (back <= length)
        {
            throw new InvalidDataException($"a copy of {length} units from {back} units back "
                + "does not stay behind the literal read pointer");
        }
        int source = literalIndex - back * unit;
        if (source < 0)
        {
            throw new InvalidDataException("a copy reaches before the literal stream");
        }
        for (int i = 0; i < length * unit; i++)
        {
            output[outputIndex++] = literal[source + i];
        }
        lastOffset -= length;
    }

    /// <summary>
    /// The end code's extra bit: a plain end, or the repeat, one last word
    /// offset from stream D matched until the output is full. The 68000
    /// decoders run the same match 65535 units at a time, re-armed forever.
    /// </summary>
    private void EndOrRepeat()
    {
        if (ReadBit())
        {
            // Stream D holds the distance back to the loop point.
            int distance = ReadWordOffset();
            if (distance <= 0)
            {
                throw new InvalidDataException("a repeat must reach back at least one unit");
            }
            if (distance > window)
            {
                throw new InvalidDataException(
                    $"the loop distance {distance} units reaches past the "
                    + $"{window}-unit window");
            }
            repeatIndex = (outputIndex / unit) - distance;
            if (repeatIndex < 0)
            {
                throw new InvalidDataException("the loop point must be a unit of the stream");
            }
            lastOffset = distance;
            int remaining = (output.Length - outputIndex) / unit;
            if (remaining > 0)
            {
                Copy(remaining);
            }
        }
        state = State.Done;
    }

    private int ReadWordOffset()
    {
        if (wordOffsetIndex + 2 > wordOffsets.Length)
        {
            throw new InvalidDataException("truncated word offsets");
        }
        int scaled = wordOffsets[wordOffsetIndex] << 8
            | wordOffsets[wordOffsetIndex + 1];
        wordOffsetIndex += 2;
        return ((1 << 16) - scaled) / unit;   // stored as -offset * unit
    }

    /// <summary>Copies <paramref name="length"/> units from <c>lastOffset</c> units back.</summary>
    private void Copy(int length)
    {
        int distance = lastOffset * unit;
        if (distance > outputIndex)
        {
            throw new InvalidDataException("match reaches before the output");
        }
        if (outputIndex + length * unit > output.Length)
        {
            throw new InvalidDataException("the streams overfill the output");
        }
        for (int i = 0; i < length * unit; i++)
        {
            // With no rewind point this never fires: -1 is below every source.
            if (outputIndex >= rewindAt && outputIndex - distance < rewindAt)
            {
                throw new InvalidDataException(
                    $"the loop reaches before the rewind point {rewindAt} at byte {outputIndex}");
            }
            output[outputIndex] = output[outputIndex - distance];
            outputIndex++;
        }
    }

    private int ReadControl()
    {
        if (controlIndex >= control.Length)
        {
            throw new InvalidDataException("truncated control stream");
        }
        return control[controlIndex++];
    }

    private bool ReadBit()
    {
        bitMask >>= 1;
        if (bitMask == 0)
        {
            bitMask = 128;
            bitValue = ReadControl();
        }
        return (bitValue & bitMask) != 0;
    }

    private int ReadInterlacedEliasGamma()
    {
        int value = 1;
        while (ReadBit())
        {
            value = value << 1 | (ReadBit() ? 1 : 0);
        }
        return value;
    }
}
