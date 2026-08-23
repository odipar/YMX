using System;
using System.IO;

namespace Jx1
{
    /// <summary>
    /// ZX1 decompressor: the C# port of org.jx1.Decompressor, itself a port of
    /// dzx1.c from Einar Saukas's ZX1. Output streams through an externally
    /// supplied ring buffer; Flip decides where its bytes go each time it
    /// fills, and Decompress(byte[]) collects everything in memory.
    /// </summary>
    public abstract class Decompressor
    {
        /// <summary>Ring size of the C reference; covers the full offset range.</summary>
        public const int DefaultBufferSize = 65536;

        /// <summary>The distance a stream starts at, before any offset.</summary>
        internal const int InitialOffset = 1;

        private enum State { Start, Literals, Match, Done }

        private readonly byte[] input;
        private readonly byte[] buffer;
        private readonly int chunkSize;
        private int inputIndex;
        private int bitMask;
        private int bitValue;
        private int bufferIndex;
        private long flushedSize;
        private int lastOffset;
        private int remaining;
        private State state = State.Start;

        protected Decompressor(byte[] input, byte[] buffer)
            : this(input, buffer, buffer.Length) { }

        protected Decompressor(byte[] input, byte[] buffer, int chunkSize)
        {
            Check.That(buffer.Length > 0, "Empty ring buffer");
            Check.That(chunkSize > 0, "Chunk size must be positive");
            this.input = input;
            this.buffer = buffer;
            this.chunkSize = chunkSize;
            Reset();
        }

        /// <summary>The first length bytes of the ring, each time it flips and
        /// once more at the end of the stream for the remainder.</summary>
        protected abstract void Flip(byte[] buffer, int length);

        public static byte[] Decompress(byte[] input)
        {
            return Decompress(input, new byte[DefaultBufferSize]);
        }

        public static byte[] Decompress(byte[] input, byte[] buffer)
        {
            var output = new MemoryStream();
            new Collector(input, buffer, output).Decompress();
            return output.ToArray();
        }

        private sealed class Collector : Decompressor
        {
            private readonly MemoryStream output;

            internal Collector(byte[] input, byte[] buffer, MemoryStream output)
                : base(input, buffer)
            {
                this.output = output;
            }

            protected override void Flip(byte[] flipped, int length)
            {
                output.Write(flipped, 0, length);
            }
        }

        /// <summary>Decompresses the whole input stream; resets on entry, so
        /// an instance may be reused.</summary>
        public void Decompress()
        {
            Reset();
            while (Resume()) { }
        }

        /// <summary>At most one chunk of output, then control returns; false
        /// once the stream is fully processed.</summary>
        public bool Resume()
        {
            int budget = chunkSize;
            while (state != State.Done)
            {
                if (remaining == 0)
                {
                    Next();
                }
                else if (budget == 0)
                {
                    return true;
                }
                else
                {
                    WriteByte(state == State.Literals ? ReadByte() : ReadBufferByte());
                    remaining--;
                    budget--;
                }
            }
            return false;
        }

        private void Reset()
        {
            inputIndex = 0;
            bitMask = 0;
            bitValue = 0;
            bufferIndex = 0;
            flushedSize = 0;
            lastOffset = InitialOffset;
            remaining = 0;
            state = State.Start;
        }

        /// <summary>The next block header; dzx1.c's goto graph.</summary>
        private void Next()
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

        private void BeginLiterals()
        {
            remaining = ReadInterlacedEliasGamma();
            Check.That(remaining > 0, "Invalid data in input file");
            state = State.Literals;
        }

        private void BeginMatchFromLastOffset()
        {
            remaining = ReadInterlacedEliasGamma();
            Check.That(remaining > 0, "Invalid data in input file");
            CheckOffset();
            state = State.Match;
        }

        private void BeginMatchFromNewOffset()
        {
            int offset = ReadOffset();
            if (offset <= 0)
            {
                // End marker: flush the remainder, like the C original.
                if (bufferIndex != 0)
                {
                    Flip(buffer, bufferIndex);
                }
                Check.That(inputIndex == input.Length, "Input file too long");
                state = State.Done;
                return;
            }
            lastOffset = offset;
            remaining = ReadInterlacedEliasGamma() + 1;
            Check.That(remaining > 0, "Invalid data in input file");
            CheckOffset();
            state = State.Match;
        }

        private void CheckOffset()
        {
            Check.That(lastOffset <= flushedSize + bufferIndex, "Invalid data in input file");
            Check.That(lastOffset <= buffer.Length,
                    "Backreference beyond ring buffer in input file");
        }

        private int ReadOffset()
        {
            int offset = ReadByte();
            if ((offset & 1) != 0)
            {
                int high = ReadByte();
                return 32512 - (high & 254) * 128 - (offset & 254) - (high & 1);
            }
            return 128 - offset / 2;
        }

        private int ReadByte()
        {
            Check.That(inputIndex < input.Length,
                    input.Length == 0 ? "Empty input file" : "Truncated input file");
            return input[inputIndex++];
        }

        private bool ReadBit()
        {
            bitMask >>= 1;
            if (bitMask == 0)
            {
                bitMask = 128;
                bitValue = ReadByte();
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

        private int ReadBufferByte()
        {
            int index = bufferIndex - lastOffset;
            return buffer[index >= 0 ? index : buffer.Length + index];
        }

        private void WriteByte(int value)
        {
            buffer[bufferIndex++] = (byte) value;
            if (bufferIndex == buffer.Length)
            {
                Flip(buffer, bufferIndex);
                flushedSize += bufferIndex;
                bufferIndex = 0;
            }
        }
    }
}
