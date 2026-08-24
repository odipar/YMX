using System;
using System.IO;

namespace Ymx
{
    /// <summary>
    /// The .ymx container, ported from org.ymx.YmxFormat: a fixed 130-byte
    /// big-endian header, then one embedded ST4 container (or stored section)
    /// per stream - fourteen frame streams carrying the YM2149's registers
    /// and eleven carrying the compiled effect script - then the sample
    /// table. doc/SPEC.md defines the format.
    /// </summary>
    public static class YmxFormat
    {
        /// <summary>'YMX!', the first four bytes of every file.</summary>
        public const int Magic = 0x594D5821;

        /// <summary>The only version this release writes or reads: the
        /// major in the high byte, the minor in the low, so versions order
        /// numerically - $0004, version 0.4, sorts before $0100, version
        /// 1.0.</summary>
        public const int Version = 0x0004;

        /// <summary>A version word as prose: VersionName(0x0102) reads
        /// "1.2".</summary>
        public static string VersionName(int word)
        {
            return (word >> 8) + "." + (word & 0xFF);
        }

        /// <summary>This release's version as prose.</summary>
        public static string VersionName()
        {
            return VersionName(Version);
        }

        /// <summary>Flag bit 0: the tune starts over at frame 0.</summary>
        public const int FlagLoops = 1;

        /// <summary>Flag bit 1 + channel: the tune uses that timer channel.</summary>
        public static int FlagChannel(int channel)
        {
            return 2 << channel;
        }

        /// <summary>R0..R13 plus the script streams M, X, T and four A/P pairs.</summary>
        public const int Streams = 25;

        /// <summary>The frame streams: one per YM2149 sound register.</summary>
        public const int RegisterStreams = 14;

        public const int StreamM = 14;
        public const int StreamX = 15;
        public const int StreamT = 16;
        public const int StreamA0 = 17;

        /// <summary>Channel c's action stream; its count stream is the next.</summary>
        public static int StreamAction(int channel)
        {
            return StreamA0 + 2 * channel;
        }

        /// <summary>The streams a player must keep decoding for a tune with
        /// these header flags: everything up to and including the last
        /// channel it names.</summary>
        public static int LiveStreams(int flags)
        {
            int live = StreamA0;
            for (int c = 0; c < Channels; c++)
            {
                if ((flags & FlagChannel(c)) != 0)
                {
                    live = StreamAction(c) + 2;
                }
            }
            return live;
        }

        /// <summary>Timer channels the format allows, numbered 0 to 3.</summary>
        public const int Channels = 4;

        /// <summary>T's two bits per channel: the timer a channel runs on.</summary>
        public const int TimerA = 0;
        public const int TimerB = 1;
        public const int TimerC = 2;
        public const int TimerD = 3;

        /// <summary>The map a YM tune is packed with: channels 0 and 1 on
        /// Timers A and D, where the reference player put its first two.</summary>
        public const int DefaultTimers =
                TimerA | (TimerD << 2) | (TimerB << 4) | (TimerC << 6);

        /// <summary>Channel c's timer, out of a T byte.</summary>
        public static int TimerOf(int assignments, int channel)
        {
            return (assignments >> (2 * channel)) & 3;
        }

        public const int OffsetMagic = 0;
        public const int OffsetVersion = 4;
        public const int OffsetFlags = 6;
        public const int OffsetFrames = 8;
        public const int OffsetPlayerHz = 12;
        public const int OffsetStreamCount = 14;
        public const int OffsetRingSize = 16;
        public const int OffsetChunk = 18;
        public const int OffsetMasterClock = 20;
        public const int OffsetSampleTable = 24;
        public const int OffsetSampleCount = 28;

        /// <summary>Bit 31 of a section offset: the bytes there are the
        /// section's values, one per frame, with no container around them.</summary>
        public const long SectionStored = 0x8000_0000L;

        /// <summary>Where a section's bytes begin, stored or container.</summary>
        public static long SectionOffset(long entry)
        {
            return entry & ~SectionStored;
        }

        /// <summary>Whether a section's bytes are its values rather than a
        /// container.</summary>
        public static bool IsStored(long entry)
        {
            return (entry & SectionStored) != 0;
        }

        /// <summary>One long offset per stream, in stream order.</summary>
        public const int OffsetSectionTable = 30;

        public const int HeaderSize = OffsetSectionTable + 4 * Streams;

        /// <summary>A sample table entry: long offset, word length, word loop.</summary>
        public const int SampleEntrySize = 8;

        /// <summary>A sample's loop point when it does not loop.</summary>
        public const int SampleOneShot = 0xFFFF;

        /// <summary>The byte after a sample's last value has this bit set;
        /// the PCM tick reads it as negative and stops.</summary>
        public const int SampleEndMark = 0x80;

        /// <summary>The format's ceiling: a sample number is five bits.</summary>
        public const int MaxSamples = 32;

        /// <summary>Default ring size: what the README's timings are quoted for.</summary>
        public const int DefaultRingSize = 960;

        /// <summary>Default chunk size, the round-robin player's group size.</summary>
        public const int DefaultChunk = 24;

        public static string CheckShape(int ringSize, int chunk)
        {
            return CheckShape(ringSize, chunk, 1);
        }

        public static string CheckShape(int ringSize, int chunk, int unit)
        {
            return CheckShape(ringSize, chunk, unit, Streams);
        }

        /// <summary>Checks a ring/chunk pair against what the format and the
        /// player require: N mod C = 0 is ST4_wrap's rule, C at or above the
        /// live stream count is the refill schedule's, N at least 2C keeps
        /// the read and write groups apart, and 2520 caps 13*N to a signed
        /// word displacement.</summary>
        public static string CheckShape(int ringSize, int chunk, int unit, int live)
        {
            if (!St4.St4Format.IsUnitSize(unit))
            {
                return St4.St4Format.CheckUnit(unit);
            }
            if (chunk % unit != 0)
            {
                return "chunk " + chunk + " is not a whole number of " + unit
                        + "-byte units";
            }
            if (chunk < live)
            {
                return "chunk " + chunk + " is below the " + live
                        + " streams this tune decodes, so the round-robin refill"
                        + " cannot fit in one cycle";
            }
            if (ringSize < 2 * chunk)
            {
                return "ring " + ringSize + " must hold two chunks of " + chunk;
            }
            if (ringSize % chunk != 0)
            {
                return "ring " + ringSize + " is not a multiple of chunk " + chunk;
            }
            if (ringSize > 2520)
            {
                return "ring " + ringSize + " exceeds 2520: the player reads"
                        + " register k's ring through an assembled-in displacement"
                        + " of k*N, and 13*N must fit a signed word";
            }
            return "";
        }
    }

    /// <summary>
    /// What a plain YM2149 receives, ported from org.ymx.Ym2149: every YM6
    /// effect bit masked away at packing time. R13 is the exception: $FF
    /// means "leave the envelope alone" and survives packing.
    /// </summary>
    public static class Ym2149
    {
        /// <summary>Envelope shape value meaning "do not write R13".</summary>
        public const int NoEnvelopeChange = 0xFF;

        /// <summary>Register 13, the envelope shape.</summary>
        public const int EnvelopeShape = 13;

        private static readonly int[] Masks = {
            0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F, 0x1F,
            0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F,
        };

        /// <summary>The value the chip would use, effect bits removed.</summary>
        public static int Mask(int register, int value)
        {
            if (register == EnvelopeShape && (value & 0xFF) == NoEnvelopeChange)
            {
                return NoEnvelopeChange;
            }
            return value & Masks[register];
        }

        /// <summary>Masks a whole register vector, input untouched.</summary>
        public static byte[] Mask(int register, byte[] values)
        {
            byte[] masked = new byte[values.Length];
            for (int i = 0; i < values.Length; i++)
            {
                masked[i] = (byte) Mask(register, values[i]);
            }
            return masked;
        }
    }

    /// <summary>
    /// The .ymx header fields the build tools need, ported from
    /// org.ymx.YmxHeader. The unit size lives in the first embedded ST4
    /// container's signature; a tune whose sections are all stored reads at
    /// any unit and reports 0.
    /// </summary>
    public sealed record YmxHeader(int Ring, int Chunk, int Unit, int Hz,
            int Flags, int Frames)
    {
        public bool AnyUnit()
        {
            return Unit == 0;
        }

        public bool Loops()
        {
            return (Flags & YmxFormat.FlagLoops) != 0;
        }

        /// <summary>What SNDH's FRMS tag requires: a tune that starts over
        /// is endless, so zero.</summary>
        public int Frms()
        {
            return Loops() ? 0 : Frames;
        }

        /// <summary>The configuration one player build serves - the string
        /// the mismatch messages compare.</summary>
        public string Shape()
        {
            return "n" + Ring + " c" + Chunk + " k" + Unit;
        }

        public static YmxHeader Read(string path)
        {
            byte[] file = File.ReadAllBytes(path);
            if (file.Length < YmxFormat.HeaderSize
                    || Word(file, YmxFormat.OffsetMagic) != (YmxFormat.Magic >>> 16)
                    || Word(file, YmxFormat.OffsetMagic + 2)
                            != (YmxFormat.Magic & 0xFFFF))
            {
                throw new IOException(path + " is not a .ymx file");
            }
            int version = Word(file, YmxFormat.OffsetVersion);
            if (version != YmxFormat.Version)
            {
                throw new IOException(path + " is format version "
                        + YmxFormat.VersionName(version) + ", this build reads "
                        + YmxFormat.VersionName()
                        + " - repack the tune from its .ym source");
            }
            int section = 0;
            for (int stream = 0; stream < YmxFormat.Streams && section == 0; stream++)
            {
                long entry = LongAt(file, YmxFormat.OffsetSectionTable + 4 * stream);
                if (entry != 0 && !YmxFormat.IsStored(entry))
                {
                    section = (int) YmxFormat.SectionOffset(entry);
                }
            }
            if (section + 3 >= file.Length)
            {
                throw new IOException(path + " has no readable first section");
            }
            return new YmxHeader(Word(file, YmxFormat.OffsetRingSize),
                    Word(file, YmxFormat.OffsetChunk),
                    section == 0 ? 0 : file[section + 3],
                    Word(file, YmxFormat.OffsetPlayerHz),
                    Word(file, YmxFormat.OffsetFlags),
                    (int) LongAt(file, YmxFormat.OffsetFrames));
        }

        private static int Word(byte[] file, int at)
        {
            return (file[at] << 8) | file[at + 1];
        }

        private static long LongAt(byte[] file, int at)
        {
            return ((long) Word(file, at) << 16) | (uint) Word(file, at + 2);
        }
    }
}
