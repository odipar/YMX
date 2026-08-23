using System;
using System.Text;

namespace Ym6
{
    /// <summary>
    /// Reads a YM5!/YM6! register dump, ported from org.ym6.Ym6Reader.
    /// Everything here speaks the YM format's own language; the engine's
    /// vocabulary starts downstream, at the Tune YmEffects builds. LHA
    /// archives are unpacked on the way in.
    /// </summary>
    public sealed class Ym6Reader
    {
        /// <summary>One parsed tune, in the file's own terms: what the
        /// header said, the frames as read, and the samples as stored.
        /// Registers[r][frame] is Rr's raw value, all sixteen - the I/O
        /// ports included, where this format files effect counts.</summary>
        public sealed record Song(string Format, int Frames, int PlayerHz,
                long MasterClock, long LoopFrame, bool Interleaved,
                long Attributes, byte[][] Drums, string Name, string Author,
                string Comment, byte[][] Registers)
        {
            /// <summary>Register count in the file: R0..R15.</summary>
            public const int YmRegisters = 16;

            /// <summary>Attribute bit 2: drums hold 4-bit values.</summary>
            public const int ADrum4Bits = 4;

            public int Digidrums()
            {
                return Drums.Length;
            }
        }

        /// <summary>Anything this reader will not accept, with a usable
        /// message.</summary>
        public sealed class FormatException : Exception
        {
            public FormatException(string message) : base(message) { }
        }

        private readonly byte[] data;
        private int at;

        private Ym6Reader(byte[] data)
        {
            this.data = data;
        }

        public static Song Read(byte[] data)
        {
            if (Lha.IsArchive(data))
            {
                try
                {
                    data = Lha.Unpack(data);
                }
                catch (ArgumentException e)
                {
                    throw new FormatException(
                            "cannot unpack this .ym's LHA wrapper: " + e.Message);
                }
            }
            return new Ym6Reader(data).Run();
        }

        private Song Run()
        {
            string format = Ascii(4);
            if (format != "YM6!" && format != "YM5!")
            {
                throw new FormatException("not a YM5!/YM6! file (starts with \""
                        + format + "\"); YM2/YM3 and packed .ym files are not"
                        + " supported");
            }
            string check = Ascii(8);
            if (check != "LeOnArD!")
            {
                throw new FormatException(
                        "missing the LeOnArD! check string after " + format);
            }

            long frames = U32();
            long attributes = U32();
            int digidrums = U16();
            long masterClock = U32();
            int playerHz = U16();
            long loopFrame = U32();
            int additional = U16();
            Skip(additional, "additional data");

            byte[][] drums = new byte[digidrums][];
            for (int i = 0; i < digidrums; i++)
            {
                long size = U32();
                if (size < 0 || size > data.Length - at)
                {
                    throw new FormatException("truncated file: digidrum " + i
                            + " claims " + size + " bytes");
                }
                drums[i] = new byte[(int) size];
                Array.Copy(data, at, drums[i], 0, (int) size);
                at += (int) size;
            }
            string name = ReadString();
            string author = ReadString();
            string comment = ReadString();

            if (frames <= 0 || frames > int.MaxValue)
            {
                throw new FormatException("unusable frame count " + frames);
            }
            if (playerHz <= 0)
            {
                throw new FormatException("unusable player frequency "
                        + playerHz + " Hz");
            }
            int count = (int) frames;
            bool interleaved = (attributes & 1) != 0;
            byte[][] registers = interleaved ? ReadInterleaved(count)
                    : ReadPerFrame(count);

            // 'End!' closes the file. Some tools omit it; the frames are all
            // read by now, so this only reports, it does not reject.
            if (at + 4 <= data.Length && Ascii(4) != "End!")
            {
                Console.Error.WriteLine("Warning: no End! marker after the frames");
            }
            return new Song(format, count, playerHz, masterClock, loopFrame,
                    interleaved, attributes, drums, name, author, comment,
                    registers);
        }

        private byte[][] ReadInterleaved(int frames)
        {
            Need((long) frames * Song.YmRegisters, "interleaved frame data");
            byte[][] registers = new byte[Song.YmRegisters][];
            for (int r = 0; r < Song.YmRegisters; r++)
            {
                registers[r] = new byte[frames];
                Array.Copy(data, at, registers[r], 0, frames);
                at += frames;
            }
            return registers;
        }

        private byte[][] ReadPerFrame(int frames)
        {
            Need((long) frames * Song.YmRegisters, "frame data");
            byte[][] registers = new byte[Song.YmRegisters][];
            for (int r = 0; r < Song.YmRegisters; r++)
            {
                registers[r] = new byte[frames];
            }
            for (int frame = 0; frame < frames; frame++)
            {
                for (int r = 0; r < Song.YmRegisters; r++)
                {
                    registers[r][frame] = data[at++];
                }
            }
            return registers;
        }

        private void Need(long bytes, string what)
        {
            if (bytes > data.Length - at)
            {
                throw new FormatException("truncated file: " + what + " needs "
                        + bytes + " bytes but only " + (data.Length - at)
                        + " are left");
            }
        }

        private void Skip(int bytes, string what)
        {
            if (bytes < 0)
            {
                throw new FormatException("negative size for " + what);
            }
            Need(bytes, what);
            at += bytes;
        }

        private string Ascii(int bytes)
        {
            Need(bytes, "header field");
            string text = Encoding.ASCII.GetString(data, at, bytes);
            at += bytes;
            return text;
        }

        private string ReadString()
        {
            int end = at;
            while (end < data.Length && data[end] != 0)
            {
                end++;
            }
            if (end == data.Length)
            {
                throw new FormatException("unterminated header string");
            }
            string text = Encoding.Latin1.GetString(data, at, end - at);
            at = end + 1;
            return text;
        }

        private int U16()
        {
            Need(2, "header field");
            return (data[at++] << 8) | data[at++];
        }

        private long U32()
        {
            return ((long) U16() << 16) | (uint) U16();
        }
    }
}
