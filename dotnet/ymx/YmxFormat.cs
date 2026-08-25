using System;
using System.IO;
using St4;

namespace Ymx
{
    /// <summary>
    /// The .ymx container, ported from org.ymx.YmxFormat: a fixed 138-byte
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
        /// numerically.</summary>
        public const int Version = 0x0006;

        /// <summary>The released binaries' patch number: it moves when
        /// the binaries change and the format does not - an optimized
        /// player, a fixed stub. The format version above is the
        /// compatibility gate; this number never reaches the format
        /// word.</summary>
        public const int Patch = 0;

        /// <summary>The release's version as prose: the format version,
        /// then the patch, a dot between them.</summary>
        public static string ReleaseName()
        {
            return VersionName() + "." + Patch;
        }

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

        /// <summary>The stream ceiling, at this version and at every later
        /// one: Q, the required-streams mask, is one long with one bit per
        /// stream, so a thirty-third stream has no bit to be required by.
        /// Streams Streams to MaxStreams - 1 are the extension streams of
        /// SPEC.md section 1.6.</summary>
        public const int MaxStreams = 32;

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

        /// <summary>L, the frame a tune that starts over goes back to. It
        /// has a meaning only where FlagLoops is set, and a tune that plays
        /// once through carries 0.</summary>
        public const int OffsetLoopFrame = 30;

        /// <summary>Byte offset of the loop table: one long per stream, read
        /// exactly as the section table is, locating the section that covers
        /// frames [L, O). Zero where one section per stream covers the whole
        /// tune, which is every file whose pass fits a ring.</summary>
        public const int OffsetLoopTable = 34;

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
        public const int OffsetSectionTable = 42;

        /// <summary>Q, the required-streams mask: bit k for stream k. A set
        /// bit requires the stream, and a consumer that does not understand
        /// it rejects the file; a clear bit on a stream the file carries
        /// makes it advisory (SPEC.md section 1.6).</summary>
        public const int OffsetRequired = 38;

        /// <summary>The mask a file carrying no extension stream holds: the
        /// twenty-five streams section 2 defines, and nothing above
        /// them.</summary>
        public const int RequiredBase = 0x01FFFFFF;

        /// <summary>The header of a file storing this many sections.</summary>
        public static int SizeOfHeader(int streams)
        {
            return OffsetSectionTable + 4 * streams;
        }

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

        /// <summary>The largest ring the format allows: the player reads
        /// stream k's ring through an assembled-in displacement of k*N, and
        /// 13*N must fit a signed word.</summary>
        public const int MaxRingSize = 2520;

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
            if (ringSize > MaxRingSize)
            {
                return "ring " + ringSize + " exceeds " + MaxRingSize
                        + ": the player reads"
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

        /// <summary>Register 8, voice A's volume; voices B and C follow
        /// it.</summary>
        public const int VolumeA = 8;

        /// <summary>Bit 4 of a volume register: the voice takes its level
        /// from the envelope generator.</summary>
        public const int EnvelopeMode = 0x10;

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
    /// org.ymx.YmxHeader. Two of them are not in the header: the unit size
    /// lives in the first embedded ST4 container's signature, and a tune
    /// whose sections are all stored reads at any unit and reports 0; the
    /// channel-to-timer map lives in the T stream, whose first frame this
    /// unpacks.
    /// </summary>
    public sealed record YmxHeader(int Ring, int Chunk, int Unit, int Hz,
            int Flags, int Frames, int Timers)
    {
        public bool AnyUnit()
        {
            return Unit == 0;
        }

        /// <summary>
        /// The MFP timers the tune claims, one bit per timer, 1 shifted by
        /// the timer number YmxFormat.TimerA and its neighbours give. The
        /// player claims one timer per timer channel the flags mark, and
        /// Timers says which timer that channel runs on.
        /// </summary>
        public int ClaimedTimers()
        {
            int claimed = 0;
            for (int channel = 0; channel < YmxFormat.Channels; channel++)
            {
                if ((Flags & YmxFormat.FlagChannel(channel)) != 0)
                {
                    claimed |= 1 << YmxFormat.TimerOf(Timers, channel);
                }
            }
            return claimed;
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
                        + " - repack the tune from its .ym or .ymr source");
            }
            // A stored section carries no signature, so the unit size comes
            // from the first section that is a container - out of either
            // table, since a file cut at its loop frame may store the frames
            // before it and pack the frames from it.
            int section = Container(file, YmxFormat.OffsetSectionTable);
            long loopTable = LongAt(file, YmxFormat.OffsetLoopTable);
            if (section == 0 && loopTable != 0)
            {
                section = Container(file, (int) loopTable);
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
                    (int) LongAt(file, YmxFormat.OffsetFrames),
                    TimerMap(file, path));
        }

        /// <summary>
        /// The T stream's first frame: the packer writes one channel-to-timer
        /// map over a whole tune, so frame 0 gives the map. A container says
        /// its own size, so what is unpacked is the rest of the file from the
        /// section's first byte.
        /// </summary>
        private static int TimerMap(byte[] file, string path)
        {
            long entry = LongAt(file,
                    YmxFormat.OffsetSectionTable + 4 * YmxFormat.StreamT);
            int from = (int) YmxFormat.SectionOffset(entry);
            if (entry == 0 || from >= file.Length)
            {
                throw new IOException(path + " has no timer stream");
            }
            if (YmxFormat.IsStored(entry))
            {
                return file[from];
            }
            St4Format.Container section;
            try
            {
                section = St4Format.Read(file[from..]);
            }
            catch (ArgumentException e)
            {
                throw new IOException(path + ": its timer stream is not"
                        + " readable: " + e.Message);
            }
            return St4Decompressor.Decompress(section.Control, section.Literal,
                    section.ByteOffsets, section.WordOffsets, section.Unit,
                    section.Size)[0];
        }

        /// <summary>The offset of one table's first section that is a
        /// container, or 0 where every section it locates is stored.</summary>
        private static int Container(byte[] file, int table)
        {
            for (int stream = 0; stream < YmxFormat.Streams; stream++)
            {
                long entry = LongAt(file, table + 4 * stream);
                if (entry != 0 && !YmxFormat.IsStored(entry))
                {
                    return (int) YmxFormat.SectionOffset(entry);
                }
            }
            return 0;
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
