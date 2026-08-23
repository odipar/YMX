using System;
using System.IO;
using Jx1;

namespace Ymr
{
    /// <summary>
    /// Decodes one ZX1 stream out of a .YMR image, ported from org.ymr.Zx1:
    /// the bitstream work is the vendored Jx1 decompressor's, and what is
    /// here is .YMR's part - where a stream sits, how far it may reach back,
    /// and what to say when it will not decode. Every condition is
    /// established from outside the decoder, by decoding the stream several
    /// times with the window, the ring fill and the stored length varied one
    /// at a time; a throw from the decoder is read as no more than "that did
    /// not run to the end". A ring of 0 means the stream is stored
    /// uncompressed. Everything rejected is a YmrReader.FormatException.
    /// </summary>
    public static class Zx1
    {
        /// <summary>The largest distance ZX1's two-byte offset form can
        /// name; a ring at least this big can never be lapped.</summary>
        private const int MaxOffset = 32512;

        /// <summary>The window the reference decode runs through: past
        /// MaxOffset, so it cannot be outrun.</summary>
        private const int ReferenceWindow = Decompressor.DefaultBufferSize;

        /// <summary>Decodes a whole stream held on its own.</summary>
        public static byte[] Decode(byte[] stream, int ringSize)
        {
            return Decode(stream, 0, stream.Length, ringSize, "a ZX1 stream");
        }

        /// <summary>Decodes the stream stored at from for length bytes,
        /// against the ring the map gives it.</summary>
        public static byte[] Decode(byte[] image, int from, int length,
                int ringSize, string what)
        {
            if (from < 0 || length < 0 || from > image.Length
                    || length > image.Length - from)
            {
                throw new YmrReader.FormatException("truncated file: " + what
                        + " claims " + length + " bytes at offset " + from
                        + ", past the " + image.Length + " bytes in the file");
            }
            if (ringSize == 0)
            {
                return image[from..(from + length)];
            }
            if (ringSize < 0)
            {
                throw new YmrReader.FormatException(what + ": a ring of "
                        + ringSize + " bytes");
            }
            byte[] stream = image[from..(from + length)];

            // The probes, in an order that lets each rest on the one before:
            // the whole decode, the last byte dropped (the end marker has to
            // land on it), and a different fill (a match past the stream's
            // own first byte decodes to two different things).
            byte[]? decoded = Attempt(stream, ReferenceWindow, 0x00);
            if (decoded == null
                    || Attempt(stream[..(length - 1)], ReferenceWindow, 0x00) != null
                    || !Same(decoded, Attempt(stream, ReferenceWindow, 0xFF)))
            {
                throw new YmrReader.FormatException(what + ": the " + length
                        + " bytes the map gives this stream are not one whole"
                        + " ZX1 stream - it ends mid-operation, reaches its end"
                        + " marker before its last byte, or reaches back for"
                        + " bytes it never wrote");
            }
            // A stream that never grows past its ring cannot overreach it,
            // and nothing can overreach MaxOffset; the rest decode again
            // through the ring the map declares - the decode the Atari
            // performs - and must agree.
            if (decoded.Length > ringSize && ringSize < MaxOffset
                    && !Same(decoded, Attempt(stream, ringSize, 0x00)))
            {
                throw new YmrReader.FormatException(what + ": a match reaches"
                        + " back further than the " + ringSize + "-byte ring the"
                        + " map gives this stream, so the Atari would decode"
                        + " this stream to something else again");
            }
            return decoded;
        }

        private static bool Same(byte[] left, byte[]? right)
        {
            return right != null && left.AsSpan().SequenceEqual(right);
        }

        /// <summary>One pass of the vendored decoder: what it wrote, or null
        /// if it did not run to the end.</summary>
        private static byte[]? Attempt(byte[] stream, int window, int fill)
        {
            var output = new MemoryStream(Math.Max(64, stream.Length * 2));
            byte[] ring = new byte[window];
            Array.Fill(ring, (byte) fill);
            try
            {
                new Collector(stream, ring, output).Decompress();
            }
            catch (Exception e) when (e is AssertionException
                    || e is IndexOutOfRangeException)
            {
                return null;
            }
            return output.ToArray();
        }

        private sealed class Collector : Decompressor
        {
            private readonly MemoryStream output;

            internal Collector(byte[] stream, byte[] ring, MemoryStream output)
                    : base(stream, ring)
            {
                this.output = output;
            }

            protected override void Flip(byte[] flushed, int flushedLength)
            {
                output.Write(flushed, 0, flushedLength);
            }
        }
    }
}
