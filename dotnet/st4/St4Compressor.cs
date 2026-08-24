using System;
using System.Collections.Generic;
using System.Numerics;

namespace St4
{
    /// <summary>
    /// Writes an ST4 parse out as the format's streams, ported from
    /// org.st4.St4Compressor: bits in stream A, literal payload in B, byte
    /// offsets in C, word offsets in D. Matches longer than maxOpLength units
    /// are split evenly, and stream A is padded to an even length for the
    /// 68000's word-wide refill.
    /// </summary>
    public sealed class St4Compressor
    {
        /// <summary>The four streams, and what the caller needs to know.</summary>
        public sealed record Result(byte[] Control, byte[] Literal,
                byte[] ByteOffsets, byte[] WordOffsets, int Unit, int PaddedSize,
                int LongestOp, int Operations)
        {
            /// <summary>Bytes all four streams take together.</summary>
            public int PackedSize()
            {
                return Control.Length + Literal.Length + ByteOffsets.Length
                        + WordOffsets.Length;
            }
        }

        private readonly int[] units;
        private readonly int unit;
        private byte[] control = new byte[256];
        private int controlIndex;
        private byte[] literal;
        private int literalIndex;
        private byte[] byteOffsets = new byte[64];
        private int byteOffsetIndex;
        private byte[] wordOffsets = new byte[64];
        private int wordOffsetIndex;
        private int bitMask;
        private int bitIndex;
        private int longestOp;
        private int operations;

        private St4Compressor(int[] units, int unit)
        {
            this.units = units;
            this.unit = unit;
            literal = new byte[Math.Max(unit, units.Length * unit)];
        }

        public static Result Compress(St4Block optimal, int[] units, int unit,
                int maxOpLength)
        {
            return new St4Compressor(units, unit).Run(optimal, maxOpLength);
        }

        private Result Run(St4Block optimal, int maxOpLength)
        {
            // Un-reverse the chain; its head is the parser's fake block.
            var blocks = new Stack<St4Block>();
            for (St4Block? block = optimal; block != null; block = block.Chain)
            {
                blocks.Push(block);
            }
            St4Block previous = blocks.Pop();

            int readIndex = 0;
            int lastOffset = Parse.InitialOffset;
            bool first = true;
            bool afterLiterals = false;

            foreach (St4Block block in blocks)
            {
                int length = block.Index - previous.Index;
                previous = block;

                if (block.Offset == 0)
                {
                    if (first)
                    {
                        first = false;          // a stream opens with literals
                    }
                    else
                    {
                        WriteBit(false);
                    }
                    WriteInterlacedEliasGamma(length);
                    for (int i = 0; i < length; i++)
                    {
                        Units.Write(literal, literalIndex, units[readIndex++], unit);
                        literalIndex += unit;
                    }
                    afterLiterals = true;
                    operations++;
                    longestOp = Math.Max(longestOp, length);
                }
                else
                {
                    int offset = block.Offset;
                    // Split evenly rather than greedily: every piece after
                    // the first has to be a new-offset match, and those
                    // cannot be shorter than two units, so a greedy remainder
                    // of one would be unwritable.
                    int pieces = maxOpLength < 3 ? 1 : (length - 1) / maxOpLength + 1;
                    int baseSize = length / pieces;
                    int wider = length % pieces;
                    for (int piece = 0; piece < pieces; piece++)
                    {
                        int size = baseSize + (piece < wider ? 1 : 0);
                        if (afterLiterals && offset == lastOffset)
                        {
                            WriteBit(false);
                            WriteInterlacedEliasGamma(size);
                        }
                        else
                        {
                            WriteBit(true);
                            WriteOffsetOf(offset);
                            WriteInterlacedEliasGamma(size - 1);
                            lastOffset = offset;
                        }
                        afterLiterals = false;
                        operations++;
                        readIndex += size;
                        longestOp = Math.Max(longestOp, size);
                    }
                }
            }

            // End marker: the one control code that names no stream.
            WriteBit(true);
            WriteBit(false);
            WriteBit(true);

            return new Result(control[..(controlIndex + (controlIndex & 1))],
                    literal[..literalIndex], byteOffsets[..byteOffsetIndex],
                    wordOffsets[..wordOffsetIndex], unit,
                    units.Length * unit, longestOp, operations);
        }

        /// <summary>The two class bits, then the offset itself into whichever
        /// stream it belongs to.</summary>
        private void WriteOffsetOf(int offset)
        {
            if (offset <= St4Format.ByteOffsetLimit)
            {
                int bank = (offset - 1) / 256;      // 0 for 1..256, 1 for 257..512
                WriteBit(true);
                WriteBit(bank != 0);
                if (byteOffsetIndex == byteOffsets.Length)
                {
                    Array.Resize(ref byteOffsets, byteOffsets.Length * 2);
                }
                byteOffsets[byteOffsetIndex++] = (byte) (bank * 256 + 256 - offset);
            }
            else
            {
                int scaled = offset * unit;
                Check.That(scaled <= 32768,
                        "a word offset must fit -offset*k in a signed word");
                WriteBit(false);
                WriteBit(false);
                if (wordOffsetIndex + 2 > wordOffsets.Length)
                {
                    Array.Resize(ref wordOffsets, wordOffsets.Length * 2);
                }
                wordOffsets[wordOffsetIndex++] = (byte) (-scaled >> 8);
                wordOffsets[wordOffsetIndex++] = (byte) -scaled;
            }
        }

        private void WriteControl(int value)
        {
            if (controlIndex == control.Length)
            {
                Array.Resize(ref control, control.Length * 2);
            }
            control[controlIndex++] = (byte) value;
        }

        /// <summary>Bits live in stream A, in the byte reserved when the
        /// reservoir ran dry - so a set bit patches that byte where it sits.</summary>
        private void WriteBit(bool value)
        {
            if (bitMask == 0)
            {
                bitMask = 128;
                bitIndex = controlIndex;
                WriteControl(0);
            }
            if (value)
            {
                control[bitIndex] |= (byte) bitMask;
            }
            bitMask >>= 1;
        }

        private void WriteInterlacedEliasGamma(int value)
        {
            for (int bit = HighestOneBit(value) >> 1; bit != 0; bit >>= 1)
            {
                WriteBit(true);
                WriteBit((value & bit) != 0);
            }
            WriteBit(false);
        }

        private static int HighestOneBit(int value)
        {
            return value == 0 ? 0
                    : 1 << (31 - BitOperations.LeadingZeroCount((uint) value));
        }
    }
}
