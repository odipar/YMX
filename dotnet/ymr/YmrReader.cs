using System;
using System.Collections.Generic;
using System.Text;

namespace Ymr
{
    /// <summary>
    /// Reads a RhYMe .YMR v1.3 register dump and replays its command stream
    /// onto the played timeline, ported from org.ymr.YmrReader. A .YMR
    /// stores no frames: a separate command stream lists, per frame, the
    /// streams to pop, so replaying it from the start is the only way in. A
    /// width-2 entry is two registers in REGISTER ORDER, not a big-endian
    /// number - the Java tree's class doc carries the measurement.
    /// </summary>
    public sealed class YmrReader
    {
        /// <summary>The four bytes every .YMR image opens with.</summary>
        public const string Magic = "YMR!";

        private const int Version13 = 0x0103;

        /// <summary>Entries in the stream map; stream 0 is the command
        /// stream itself.</summary>
        public const int StreamCount = 20;

        /// <summary>Registers a frame carries: R0..R13.</summary>
        public const int RegisterCount = 14;

        /// <summary>Timers available for effects: A, B and D, in that order.</summary>
        public const int TimerCount = 3;

        /// <summary>The R13 value that means "leave the envelope alone this
        /// frame": the pop IS the retrigger, and $FF is already what the
        /// pipeline downstream reads as exactly this.</summary>
        public const int NoEnvelopeShape = 0xFF;

        private const int HeaderFields = 28;
        private const int MapEntry = 12;
        private const int HeaderSize = HeaderFields + StreamCount * MapEntry;

        private const int Command = 0;
        private const int EndOfFrame = 0x00;
        private const int FirstReservedCommand = 0xC0;

        private const int EnvelopeShapeStream = 10;
        private const int LastRegisterStream = EnvelopeShapeStream;
        private const int REnvelopeShape = 13;
        private const int FirstTimerStream = 11;
        private const int StreamsPerTimer = 3;

        /// <summary>Bytes in one entry of each stream.</summary>
        private static readonly int[] Width = {
            0, 2, 2, 2, 1, 1, 1, 1, 1, 2, 1,
            1, 2, 1,
            1, 2, 1,
            1, 2, 1,
        };

        /// <summary>The first YM register a register stream's entry writes.</summary>
        private static readonly int[] FirstRegister =
                {-1, 0, 2, 4, 6, 7, 8, 9, 10, 11, 13};

        /// <summary>The stream names the format uses, for messages.</summary>
        private static readonly string[] Name = {
            "command", "tone_a", "tone_b", "tone_c", "noise", "mixer",
            "volume_a", "volume_b", "volume_c", "envelope_period",
            "envelope_shape",
            "timer_a_effect", "timer_a_rate", "timer_a_sample",
            "timer_b_effect", "timer_b_rate", "timer_b_sample",
            "timer_d_effect", "timer_d_rate", "timer_d_sample",
        };

        /// <summary>One parsed .YMR image, on the played timeline. The three
        /// timers are named as the file names them - A, B and D, bound to
        /// voices A, B and C; there is no Timer C among them.</summary>
        public sealed record Song(int FrameCount, int LoopFrame, int FrameRate,
                long YmClock, byte[][] Registers, IReadOnlyList<Sample> Samples,
                IReadOnlyList<TimerFrame> TimerA, IReadOnlyList<TimerFrame> TimerB,
                IReadOnlyList<TimerFrame> TimerD)
        {
            /// <summary>One register on one frame, as an unsigned byte.</summary>
            public int Register(int register, int frame)
            {
                return Registers[register][frame];
            }

            /// <summary>Timer 0, 1 and 2 are Timers A, B and D.</summary>
            public IReadOnlyList<TimerFrame> Timer(int timer)
            {
                return timer switch
                {
                    0 => TimerA,
                    1 => TimerB,
                    2 => TimerD,
                    _ => throw new IndexOutOfRangeException("timer " + timer),
                };
            }

            /// <summary>Whether the header names a loop frame.</summary>
            public bool Loops()
            {
                return LoopFrame >= 0;
            }
        }

        /// <summary>What one frame asked of one timer, and which of its
        /// three streams said so - the values are held state, the flags are
        /// the events, and the two are not the same information.</summary>
        public sealed record TimerFrame(int Effect, int Prescaler, int Counter,
                int Sample, bool EffectPopped, bool RatePopped, bool SamplePopped)
        {
            public const int None = 0;
            public const int Pwm = 1;
            public const int SampleEffect = 2;
            public const int Rte = 3;

            public bool Popped()
            {
                return EffectPopped || RatePopped || SamplePopped;
            }
        }

        /// <summary>One sample block: pre-converted 4-bit YM volume levels,
        /// one per byte, and where a looped one comes back to.</summary>
        public sealed record Sample(byte[] Data, bool Looped, int LoopStart);

        /// <summary>Anything this reader will not accept, with a usable
        /// message.</summary>
        public sealed class FormatException : Exception
        {
            public FormatException(string message) : base(message) { }
        }

        private readonly byte[] data;
        private int at;

        private YmrReader(byte[] data)
        {
            this.data = data;
        }

        public static Song Read(byte[] data)
        {
            return new YmrReader(data).Run();
        }

        private Song Run()
        {
            string magic = Ascii(4);
            if (magic != Magic)
            {
                throw new FormatException("not a .YMR image (starts with \""
                        + magic + "\")");
            }
            int version = U16();
            if (version != Version13)
            {
                throw new FormatException("this is .YMR version " + (version >> 8)
                        + "." + (version & 0xFF)
                        + "; only 1.3 has the stream map this reader reads");
            }

            long frames = U32();
            long loop = U32();
            int frameRate = U16();
            int sampleCount = U16();
            long ymClock = U32();
            int streamCount = U16();
            if (streamCount != StreamCount)
            {
                throw new FormatException("the stream map has " + streamCount
                        + " entries, not the " + StreamCount
                        + " version 1.3 defines");
            }
            U32();                          // reserved; written 0, ignored here

            if (frames <= 0 || frames > int.MaxValue)
            {
                throw new FormatException("unusable frame count " + frames);
            }
            int frameCount = (int) frames;
            if (loop != 0xFFFFFFFFL && loop >= frames)
            {
                throw new FormatException("loop frame " + loop + " is past the "
                        + frameCount + " frames the song has");
            }
            int loopFrame = loop == 0xFFFFFFFFL ? -1 : (int) loop;

            int[] offsets = new int[StreamCount];
            int[] rings = new int[StreamCount];
            ReadMap(offsets, rings);

            List<Sample> samples = ReadSamples(sampleCount);

            byte[][] entries = new byte[StreamCount][];
            bool[] present = new bool[StreamCount];
            for (int stream = 0; stream < StreamCount; stream++)
            {
                present[stream] = offsets[stream] != 0;
                entries[stream] = present[stream]
                        ? DecodeStream(stream, offsets, rings)
                        : new byte[0];
            }
            if (!present[Command])
            {
                throw new FormatException("the map has no command stream, so"
                        + " nothing says which streams any frame pops");
            }

            return Replay(entries, present, frameCount, loopFrame, frameRate,
                    ymClock, samples);
        }

        /// <summary>Reads the map, whose entries are (offset, loop offset,
        /// ring size, reserved). An offset of 0 means the stream is not in
        /// the file. The loop offset serves a player re-entering the loop;
        /// this reader replays once through and never needs it.</summary>
        private void ReadMap(int[] offsets, int[] rings)
        {
            for (int stream = 0; stream < StreamCount; stream++)
            {
                long offset = U32();
                U32();                      // loop offset; see above
                rings[stream] = U16();
                U16();                      // reserved

                if (offset > data.Length)
                {
                    throw new FormatException("truncated file: " + Name[stream]
                            + " starts at offset " + offset + ", past the "
                            + data.Length + " bytes in the file");
                }
                offsets[stream] = (int) offset;
            }
        }

        /// <summary>A stream's stored length is the distance to the next
        /// present stream's offset, the last one running to the end of the
        /// file.</summary>
        private byte[] DecodeStream(int stream, int[] offsets, int[] rings)
        {
            int end = data.Length;
            for (int next = stream + 1; next < StreamCount; next++)
            {
                if (offsets[next] != 0)
                {
                    end = offsets[next];
                    break;
                }
            }
            if (end < offsets[stream])
            {
                throw new FormatException(Name[stream] + " starts at offset "
                        + offsets[stream] + ", after the stream that follows it"
                        + " in the map; the streams are stored in map order and"
                        + " their lengths are the distances between them");
            }

            byte[] decoded = Zx1.Decode(data, offsets[stream],
                    end - offsets[stream], rings[stream], Name[stream]);
            int width = Width[stream];
            if (width != 0 && decoded.Length % width != 0)
            {
                throw new FormatException(Name[stream] + " decodes to "
                        + decoded.Length + " bytes, which is not a whole number"
                        + " of " + width + "-byte entries");
            }
            return decoded;
        }

        /// <summary>Reads the sample blocks, between the map and the
        /// streams: a padded size, that many levels, then a four-byte
        /// trailer.</summary>
        private List<Sample> ReadSamples(int count)
        {
            at = HeaderSize;                // the blocks follow the map
            var samples = new List<Sample>(count);
            for (int index = 0; index < count; index++)
            {
                long size = U32();
                if (size > data.Length - at)
                {
                    throw new FormatException("truncated file: sample " + index
                            + " claims " + size + " bytes but only "
                            + (data.Length - at) + " are left");
                }
                byte[] levels = data[at..(at + (int) size)];
                at += (int) size;

                Need(4, "sample " + index + "'s trailer");
                bool looped = (data[at++] & 1) != 0;
                int loopStart = U16();
                at++;                       // reserved
                samples.Add(new Sample(levels, looped, loopStart));
            }
            return samples;
        }

        // The replay's running state.
        private readonly int[] cursor = new int[StreamCount];
        private readonly int[] held = new int[RegisterCount];
        private readonly int[] effect = new int[TimerCount];
        private readonly int[] prescaler = new int[TimerCount];
        private readonly int[] counter = new int[TimerCount];
        private readonly int[] sample = new int[TimerCount];
        private readonly bool[] effectPopped = new bool[TimerCount];
        private readonly bool[] ratePopped = new bool[TimerCount];
        private readonly bool[] samplePopped = new bool[TimerCount];
        private bool shapePopped;

        /// <summary>Walks the command stream: $00 ends the frame, $01-$BF
        /// pops the stream with that index, $C0 upwards is reserved - a
        /// reader that meets one cannot skip it and must stop.</summary>
        private Song Replay(byte[][] entries, bool[] present, int frameCount,
                int loopFrame, int frameRate, long ymClock, List<Sample> samples)
        {
            byte[][] registers = new byte[RegisterCount][];
            for (int register = 0; register < RegisterCount; register++)
            {
                registers[register] = new byte[frameCount];
            }
            var timers = new List<List<TimerFrame>>(TimerCount);
            for (int timer = 0; timer < TimerCount; timer++)
            {
                timers.Add(new List<TimerFrame>(frameCount));
            }

            byte[] commands = entries[Command];
            int frame = 0;
            foreach (byte b in commands)
            {
                int command = b;
                if (command == EndOfFrame)
                {
                    if (frame == frameCount)
                    {
                        throw new FormatException("the command stream holds more"
                                + " than the " + frameCount
                                + " end-of-frame bytes the header declares");
                    }
                    Settle(registers, timers, frame);
                    frame++;
                    continue;
                }
                if (command >= FirstReservedCommand)
                {
                    throw new FormatException("frame " + frame
                            + " carries command $"
                            + command.ToString("X")
                            + ", which version 1.3 reserves; a reader has no way"
                            + " to tell its length");
                }
                if (command >= StreamCount)
                {
                    throw new FormatException("frame " + frame + " pops stream "
                            + command + ", past the " + StreamCount
                            + " in the map");
                }
                Pop(command, frame, entries, present);
            }

            if (frame != frameCount)
            {
                throw new FormatException("the command stream holds " + frame
                        + " end-of-frame bytes, not the " + frameCount
                        + " the header declares");
            }
            if (commands.Length != 0 && commands[^1] != EndOfFrame)
            {
                throw new FormatException("the command stream ends in the middle"
                        + " of a frame: there is no end-of-song marker, so its"
                        + " last byte has to end a frame");
            }

            return new Song(frameCount, loopFrame, frameRate, ymClock, registers,
                    samples, timers[0], timers[1], timers[2]);
        }

        /// <summary>Applies one stream's next entry, the whole of what a pop
        /// does.</summary>
        private void Pop(int stream, int frame, byte[][] entries, bool[] present)
        {
            if (!present[stream])
            {
                throw new FormatException("frame " + frame + " pops "
                        + Name[stream] + ", which the map says is not in the file");
            }
            byte[] entry = entries[stream];
            int width = Width[stream];
            int from = cursor[stream];
            if (width > entry.Length - from)
            {
                throw new FormatException("frame " + frame + " pops "
                        + Name[stream] + ", which has nothing left to pop: all "
                        + entry.Length + " of its decoded bytes have been read");
            }
            cursor[stream] = from + width;

            if (stream <= LastRegisterStream)
            {
                // A width-2 entry is two register values in register order.
                int register = FirstRegister[stream];
                for (int i = 0; i < width; i++)
                {
                    held[register + i] = entry[from + i];
                }
                if (stream == EnvelopeShapeStream)
                {
                    shapePopped = true;
                }
                return;
            }

            // A timer's rate entry is (prescaler, counter): the MFP's control
            // and data registers, two halves of one decision.
            int timerAt = (stream - FirstTimerStream) / StreamsPerTimer;
            switch ((stream - FirstTimerStream) % StreamsPerTimer)
            {
                case 0:
                    effect[timerAt] = entry[from];
                    effectPopped[timerAt] = true;
                    break;
                case 1:
                    prescaler[timerAt] = entry[from];
                    counter[timerAt] = entry[from + 1];
                    ratePopped[timerAt] = true;
                    break;
                default:
                    sample[timerAt] = entry[from];
                    samplePopped[timerAt] = true;
                    break;
            }
        }

        /// <summary>Writes down the frame the commands just finished.</summary>
        private void Settle(byte[][] registers, List<List<TimerFrame>> timers,
                int frame)
        {
            for (int register = 0; register < RegisterCount; register++)
            {
                registers[register][frame] = (byte) held[register];
            }
            registers[REnvelopeShape][frame] = (byte) (shapePopped
                    ? held[REnvelopeShape] : NoEnvelopeShape);
            shapePopped = false;

            for (int timer = 0; timer < TimerCount; timer++)
            {
                timers[timer].Add(new TimerFrame(effect[timer], prescaler[timer],
                        counter[timer], sample[timer], effectPopped[timer],
                        ratePopped[timer], samplePopped[timer]));
                effectPopped[timer] = false;
                ratePopped[timer] = false;
                samplePopped[timer] = false;
            }
        }

        // ---------------------------------------------- reading the bytes

        private void Need(int bytes, string what)
        {
            if (bytes > data.Length - at)
            {
                throw new FormatException("truncated file: " + what + " needs "
                        + bytes + " bytes but only " + (data.Length - at)
                        + " are left");
            }
        }

        private string Ascii(int bytes)
        {
            Need(bytes, "the magic");
            string text = Encoding.ASCII.GetString(data, at, bytes);
            at += bytes;
            return text;
        }

        private int U16()
        {
            Need(2, "a header field");
            return (data[at++] << 8) | data[at++];
        }

        private long U32()
        {
            return ((long) U16() << 16) | (uint) U16();
        }
    }
}
