using System;

namespace St4
{
    /// <summary>
    /// The reference ST4 decoder, ported from org.st4.St4Decompressor: ZX1's
    /// state machine with the pieces read from the format's own streams, every
    /// length and offset counted in units.
    /// </summary>
    public sealed class St4Decompressor
    {
        private enum State { Start, Literals, Match, Done }

        private readonly int offsetLimit;
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
        private int lastOffset = Parse.InitialOffset;
        private State state = State.Start;

        private St4Decompressor(byte[] control, byte[] literal, byte[] byteOffsets,
                byte[] wordOffsets, byte[] output, int unit, int offsetLimit)
        {
            this.offsetLimit = offsetLimit;
            this.control = control;
            this.literal = literal;
            this.byteOffsets = byteOffsets;
            this.wordOffsets = wordOffsets;
            this.output = output;
            this.unit = unit;
        }

        public static byte[] Decompress(byte[] control, byte[] literal,
                byte[] byteOffsets, byte[] wordOffsets, int unit, int size)
        {
            return Decompress(control, literal, byteOffsets, wordOffsets, unit,
                    size, St4Format.MaxOffsetUnits(unit));
        }

        /// <summary>As above, refusing any back-reference further than
        /// offsetLimit units - which is what makes a stream safe for a ring of
        /// that many units.</summary>
        public static byte[] Decompress(byte[] control, byte[] literal,
                byte[] byteOffsets, byte[] wordOffsets, int unit, int size,
                int offsetLimit)
        {
            Check.That(St4Format.IsUnitSize(unit), "unit size must be 1, 2 or 4");
            Check.That(size % unit == 0, "output size must be a whole number of units");
            var decoder = new St4Decompressor(control, literal, byteOffsets,
                    wordOffsets, new byte[size], unit, offsetLimit);
            decoder.Run();
            return decoder.output;
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
                    default:
                        throw new AssertionException("unreachable");
                }
            }
            Check.That(outputIndex == output.Length,
                    "the streams did not fill the output");
        }

        private void BeginLiterals()
        {
            int length = ReadInterlacedEliasGamma();
            Check.That(length > 0, "invalid literal length");
            for (int i = 0; i < length * unit; i++)
            {
                output[outputIndex++] = literal[literalIndex++];
            }
            state = State.Literals;
        }

        private void BeginMatchFromLastOffset()
        {
            Copy(ReadInterlacedEliasGamma());
            state = State.Match;
        }

        private void BeginMatchFromNewOffset()
        {
            // Two class bits: byte or word, then which bank - or, for a word,
            // the one code that means the stream is over.
            if (ReadBit())
            {
                int bank = ReadBit() ? 1 : 0;
                Check.That(byteOffsetIndex < byteOffsets.Length,
                        "truncated byte offsets");
                lastOffset = bank * 256 + 256 - byteOffsets[byteOffsetIndex++];
            }
            else
            {
                if (ReadBit())
                {
                    state = State.Done;
                    return;
                }
                Check.That(wordOffsetIndex + 2 <= wordOffsets.Length,
                        "truncated word offsets");
                int scaled = wordOffsets[wordOffsetIndex] << 8
                        | wordOffsets[wordOffsetIndex + 1];
                wordOffsetIndex += 2;
                lastOffset = ((1 << 16) - scaled) / unit;   // stored as -offset*unit
            }
            Check.That(lastOffset > 0, "an offset must reach back at least one unit");
            if (lastOffset > offsetLimit)
            {
                throw new InvalidOperationException("offset " + lastOffset
                        + " units reaches past the " + offsetLimit + "-unit limit");
            }
            Copy(ReadInterlacedEliasGamma() + 1);
            state = State.Match;
        }

        /// <summary>Copies length units from lastOffset units back.</summary>
        private void Copy(int length)
        {
            Check.That(length > 0, "invalid match length");
            int distance = lastOffset * unit;
            Check.That(distance <= outputIndex, "match reaches before the output");
            for (int i = 0; i < length * unit; i++)
            {
                output[outputIndex] = output[outputIndex - distance];
                outputIndex++;
            }
        }

        private int ReadControl()
        {
            Check.That(controlIndex < control.Length, "truncated control stream");
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
}
