using System;
using System.Collections.Generic;
using System.IO;

namespace Rig
{
    /// <summary>
    /// Corpus sweep for the .ymr front end, ported from the Java rig's
    /// YmrSweep: pack each RhYMe register dump and verify the real player's
    /// chip writes against an independent model of the .YMR image - its own
    /// ZX1 decoder, its own stream map walk, its own replay of the command
    /// stream; nothing here calls the ymr namespace except the packer under
    /// test. One status line per tune: OK, ISSUE, PACKFAIL or SKIP; a
    /// non-zero exit on any ISSUE.
    /// </summary>
    public static class YmrSweep
    {
        // The bits a YM2149 keeps; R13's $FF passes through as "do not
        // write it at all".
        private static readonly int[] Mask = {0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F,
                0x1F, 0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F};
        private const int NoShape = 0xFF;
        private const int ShapeBeforeAnyPop = 0x08;
        private const int Ports = 0xC0;

        private static readonly int[] Prescale = {0, 4, 10, 16, 50, 64, 100, 200};
        private const int MfpClock = 2457600;

        // Effect types, as the timer_*_effect stream carries them.
        private const int FxNone = 0;
        private const int FxPwm = 1;
        private const int FxSample = 2;
        private const int FxRte = 3;

        // The engine's code-byte kinds.
        private const int KindToggle = 0x00;
        private const int KindPcm = 0x40;
        private const int KindRetrigger = 0xC0;
        private const int TriggerBit = 0x08;

        private const int MaxSamples = 32;
        private const int MaxSampleBytes = 65535;

        // One entry's width per stream, and the first YM register a register
        // stream's entry writes - two registers in REGISTER ORDER, not a
        // big-endian word.
        private static readonly int[] Width = {0, 2, 2, 2, 1, 1, 1, 1, 1, 2, 1,
                1, 2, 1, 1, 2, 1, 1, 2, 1};
        private static readonly int[] FirstRegister = {-1, 0, 2, 4, 6, 7, 8, 9,
                10, 11, 13};
        private const int StreamCount = 20;
        private const int LastRegisterStream = 10;
        private const int FirstTimerStream = 11;
        private const int HeaderSize = 28 + StreamCount * 12;

        /// <summary>Anything about the .YMR image this file will not read.</summary>
        public sealed class Malformed : Exception
        {
            public Malformed(string reason) : base(reason) { }
        }

        // -------------------------------------------------------------- ZX1

        /// <summary>One ZX1 stream out of a .YMR image, decoded through its
        /// own ring - written here rather than borrowed, so the C# reader
        /// has something to be checked against. A ring of 0 means the
        /// stream is stored uncompressed.</summary>
        public static byte[] DecodeZx1(byte[] image, int at, int length, int ring)
        {
            if (ring == 0)
            {
                return image[at..(at + length)];
            }
            byte[] source = image[at..(at + length)];
            var decoded = new MemoryStream();
            byte[] window = new byte[ring];
            int atIn = 0;
            int mask = 0;
            int bits = 0;
            int atWin = 0;

            int NextByte()
            {
                if (atIn >= source.Length)
                {
                    throw new Malformed("the stream ends mid-operation");
                }
                return source[atIn++];
            }

            int Bit()
            {
                mask >>= 1;
                if (mask == 0)
                {
                    mask = 128;
                    bits = NextByte();
                }
                return (bits & mask) != 0 ? 1 : 0;
            }

            int Gamma()
            {
                int value = 1;
                while (Bit() != 0)
                {
                    value = value * 2 + Bit();
                }
                return value;
            }

            void Emit(int value)
            {
                decoded.WriteByte((byte) value);
                window[atWin] = (byte) value;
                atWin = (atWin + 1) % ring;
            }

            int offset = 1;                 // the distance a stream starts at
            bool literals = true;
            int lengthLeft = Gamma();
            while (true)
            {
                for (int i = 0; i < lengthLeft; i++)
                {
                    Emit(literals ? NextByte()
                            : window[((atWin - offset) % ring + ring) % ring]);
                }
                if (Bit() != 0)
                {                   // a match from a new offset, or the end
                    int first = NextByte();
                    if ((first & 1) != 0)
                    {
                        int second = NextByte();
                        offset = 32512 - (second & 254) * 128 - (first & 254)
                                - (second & 1);
                    }
                    else
                    {
                        offset = 128 - first / 2;
                    }
                    if (offset <= 0)
                    {
                        if (atIn != source.Length)
                        {
                            throw new Malformed(
                                    "the end marker lands before the stream does");
                        }
                        return decoded.ToArray();
                    }
                    if (offset > ring)
                    {
                        throw new Malformed("a match reaches back further than"
                                + " the " + ring + "-byte ring");
                    }
                    if (offset > decoded.Length)
                    {
                        // Past the stream's own first byte, into whatever the
                        // ring happened to hold.
                        throw new Malformed("a match reaches back for bytes the"
                                + " stream never wrote");
                    }
                    literals = false;
                    lengthLeft = Gamma() + 1;
                }
                else if (literals)
                {                   // a match from the offset in hand
                    literals = false;
                    lengthLeft = Gamma();
                }
                else
                {
                    literals = true;
                    lengthLeft = Gamma();
                }
            }
        }

        // ------------------------------------------------- the .YMR image

        /// <summary>One frame of one timer: what it should be doing, and
        /// which of its three streams said so - the flags are the events and
        /// are not the same information as the values.</summary>
        public sealed record TimerFrame(int Effect, int Prescaler, int Counter,
                int Sample, bool EffectPop, bool RatePop, bool SamplePop);

        /// <summary>A .YMR v1.3 register dump, replayed onto the flat
        /// per-frame view: the only way in is to replay the command stream
        /// from the start, once, and write down what the chip held on every
        /// frame as it goes.</summary>
        public sealed class Ymr
        {
            public readonly int Frames;
            public readonly int Rate;
            public readonly int Loop;
            public readonly int[][] Registers = new int[14][];
            public readonly TimerFrame[][] Timers = new TimerFrame[3][];
            public readonly int[][] Codes = new int[3][];
            public readonly int[][] Window = new int[3][];
            public int Used;                // the channels that ever act
            public int Triggers;
            private readonly byte[] image;
            private readonly int[][] map = new int[StreamCount][];
            private readonly byte[][] entries = new byte[StreamCount][];
            private readonly int[] samples; // playing lengths; -1 = never stops

            public Ymr(byte[] image)
            {
                this.image = image;
                if (image.Length < 4 || image[0] != 'Y' || image[1] != 'M'
                        || image[2] != 'R' || image[3] != '!')
                {
                    throw new Malformed("not a .YMR image");
                }
                int version = Word(4);
                if (version != 0x0103)
                {
                    throw new Malformed(".YMR version " + (version >> 8) + "."
                            + (version & 0xFF) + ", not the 1.3 this reads");
                }
                Frames = LongWord(6);
                int loopField = LongWord(10);
                Rate = Word(14);
                int sampleCount = Word(16);
                int streams = Word(22);
                if (streams != StreamCount)
                {
                    throw new Malformed("the stream map has " + streams
                            + " entries, not " + StreamCount);
                }
                if (Frames <= 0)
                {
                    throw new Malformed("unusable frame count " + Frames);
                }
                Loop = loopField == -1 ? -1 : loopField;
                if (Loop >= Frames)
                {
                    throw new Malformed("loop frame " + Loop + " is past the "
                            + Frames + " frames");
                }
                for (int s = 0; s < StreamCount; s++)
                {
                    map[s] = new[] {LongWord(28 + 12 * s), LongWord(32 + 12 * s),
                            Word(36 + 12 * s), Word(38 + 12 * s)};
                }
                samples = ReadSamples(sampleCount);
                for (int s = 0; s < StreamCount; s++)
                {
                    entries[s] = Decode(s);
                }
                if (map[0][0] == 0)
                {
                    throw new Malformed("no command stream: nothing says what"
                            + " any frame pops");
                }
                Replay();
                Walk();
            }

            private int Word(int at)
            {
                return (image[at] << 8) | image[at + 1];
            }

            private int LongWord(int at)
            {
                return (Word(at) << 16) | Word(at + 2);
            }

            /// <summary>The sample blocks, between the map and the streams;
            /// the value kept is how many bytes a PCM stream plays before it
            /// stops, or -1 for a looped block.</summary>
            private int[] ReadSamples(int count)
            {
                int at = HeaderSize;
                var playing = new List<int>();
                for (int index = 0; index < count; index++)
                {
                    int size = LongWord(at);
                    at += 4;
                    if (size > image.Length - at - 4)
                    {
                        throw new Malformed("sample " + index + " claims " + size
                                + " bytes past the file");
                    }
                    int dataLength = Math.Min(size, MaxSampleBytes);
                    bool looped = (image[at + size] & 1) != 0;
                    int start = Word(at + size + 1);
                    at += size + 4;
                    if (playing.Count < MaxSamples)
                    {
                        playing.Add(looped && start < dataLength ? -1 : dataLength);
                    }
                }
                return playing.ToArray();
            }

            /// <summary>A stream's stored length is the distance to the next
            /// present stream's offset, the last one running to the end of
            /// the file.</summary>
            private byte[] Decode(int stream)
            {
                int offset = map[stream][0];
                int ring = map[stream][2];
                if (offset == 0)
                {
                    return new byte[0];     // not in the file: never popped
                }
                int end = image.Length;
                for (int later = stream + 1; later < StreamCount; later++)
                {
                    if (map[later][0] != 0)
                    {
                        end = map[later][0];
                        break;
                    }
                }
                if (end < offset)
                {
                    throw new Malformed("stream " + stream
                            + " starts after the stream that follows it");
                }
                try
                {
                    return DecodeZx1(image, offset, end - offset, ring);
                }
                catch (Malformed problem)
                {
                    throw new Malformed("stream " + stream + ": "
                            + problem.Message);
                }
            }

            /// <summary>The command stream, one byte per command: $00 ends
            /// the frame, $01-$BF pops the stream with that index, and $C0
            /// upwards is reserved - meeting one is a stop.</summary>
            private void Replay()
            {
                for (int register = 0; register < 14; register++)
                {
                    Registers[register] = new int[Frames];
                }
                for (int timer = 0; timer < 3; timer++)
                {
                    Timers[timer] = new TimerFrame[Frames];
                }
                int[] cursor = new int[StreamCount];
                int[] held = new int[14];
                int[] effect = new int[3];
                int[] prescaler = new int[3];
                int[] counter = new int[3];
                int[] sample = new int[3];
                bool[][] popped = {new bool[3], new bool[3], new bool[3]};
                bool shapePopped = false;
                int frame = 0;
                foreach (byte raw in entries[0])
                {
                    int command = raw;
                    if (command == 0)
                    {
                        if (frame == Frames)
                        {
                            throw new Malformed("more end-of-frame bytes than"
                                    + " the header asks for");
                        }
                        for (int register = 0; register < 14; register++)
                        {
                            Registers[register][frame] = held[register];
                        }
                        Registers[13][frame] = shapePopped ? held[13] : NoShape;
                        shapePopped = false;
                        for (int timer = 0; timer < 3; timer++)
                        {
                            Timers[timer][frame] = new TimerFrame(effect[timer],
                                    prescaler[timer], counter[timer],
                                    sample[timer], popped[timer][0],
                                    popped[timer][1], popped[timer][2]);
                            popped[timer] = new bool[3];
                        }
                        frame++;
                        continue;
                    }
                    if (command >= 0xC0)
                    {
                        throw new Malformed(string.Format(
                                "frame {0} carries reserved command ${1:X2}",
                                frame, command));
                    }
                    if (command >= StreamCount)
                    {
                        throw new Malformed("frame " + frame + " pops stream "
                                + command + ", past the map");
                    }
                    int width = Width[command];
                    int at = cursor[command];
                    byte[] entry = entries[command];
                    if (width > entry.Length - at)
                    {
                        throw new Malformed("frame " + frame + " pops stream "
                                + command + ", which has nothing left");
                    }
                    cursor[command] = at + width;
                    if (command <= LastRegisterStream)
                    {
                        int first = FirstRegister[command];
                        for (int i = 0; i < width; i++)
                        {
                            held[first + i] = entry[at + i];
                        }
                        shapePopped |= command == LastRegisterStream;
                        continue;
                    }
                    int timerAt = (command - FirstTimerStream) / 3;
                    switch ((command - FirstTimerStream) % 3)
                    {
                        case 0:
                            effect[timerAt] = entry[at];
                            popped[timerAt][0] = true;
                            break;
                        case 1:
                            prescaler[timerAt] = entry[at];
                            counter[timerAt] = entry[at + 1];
                            popped[timerAt][1] = true;
                            break;
                        default:
                            sample[timerAt] = entry[at];
                            popped[timerAt][2] = true;
                            break;
                    }
                }
                if (frame != Frames)
                {
                    throw new Malformed("the command stream holds " + frame
                            + " end-of-frame bytes, not " + Frames);
                }
            }

            /// <summary>What every dump frame asks of each timer, as one
            /// code byte: this is the dump timeline, and what the effect
            /// stage MAKES of a run of code bytes is the played timeline's
            /// business, in Stage.</summary>
            private void Walk()
            {
                for (int channel = 0; channel < 3; channel++)
                {
                    Codes[channel] = new int[Frames];
                    Window[channel] = new int[Frames];
                }
                for (int channel = 0; channel < 3; channel++)
                {
                    WalkChannel(channel);
                }
            }

            private void WalkChannel(int channel)
            {
                int running = FxNone;
                int trigger = 0;
                int armedTo = 0;
                int last = 0;
                for (int frame = 0; frame < Frames; frame++)
                {
                    TimerFrame want = Timers[channel][frame];
                    bool configure = false;
                    if (want.EffectPop)
                    {
                        if (want.Effect == FxNone)
                        {
                            running = FxNone;
                        }
                        else
                        {
                            configure = true;
                        }
                    }
                    else if (running != FxNone && want.SamplePop)
                    {
                        configure = true;
                    }
                    bool started = false;
                    if (configure)
                    {
                        running = want.Effect;
                        started = running == FxSample;
                        if (started)
                        {
                            trigger ^= TriggerBit;
                            Triggers++;
                        }
                    }
                    int code = CodeOf(channel, running, want, trigger, started,
                            frame, armedTo);
                    if (code != 0 && (code & 0xC0) == KindPcm)
                    {
                        // Every armed frame carries the window its rate
                        // would give a sample starting there: the arming
                        // frame is the one whose rate the window is measured
                        // at.
                        Window[channel][frame] = Armed(want);
                        if (code != last)
                        {
                            armedTo = frame + Window[channel][frame];
                        }
                    }
                    last = code;
                    Codes[channel][frame] = code;
                    if (code != 0)
                    {
                        Used |= 1 << channel;
                    }
                }
            }

            /// <summary>The code byte a frame hands the effect stage, or 0
            /// for a channel with nothing to run; the trigger bit makes two
            /// pops of one sample at one rate two different codes.</summary>
            private int CodeOf(int channel, int running, TimerFrame want,
                    int trigger, bool started, int frame, int armedTo)
            {
                int kind = running switch
                {
                    FxPwm => KindToggle,
                    FxSample => KindPcm,
                    FxRte => KindRetrigger,
                    _ => -1,    // idle, or a type the format reserves
                };
                if (kind < 0)
                {
                    return 0;
                }
                if (Prescale[want.Prescaler & 7] == 0 || want.Counter == 0)
                {
                    return 0;   // prescaler 0 stops it; counter 0 is dropped
                }
                int head = kind | ((channel + 1) << 4) | (want.Prescaler & 7);
                if (kind != KindPcm)
                {
                    return head;
                }
                if (want.Sample >= samples.Length)
                {
                    return 0;   // no block behind it: nothing plays
                }
                return started || frame < armedTo ? head | trigger : 0;
            }

            /// <summary>How many frames a sample armed with this rate stays
            /// armed for, rounded up so the skip never lifts early.</summary>
            private int Armed(TimerFrame want)
            {
                if (samples[want.Sample] < 0)
                {
                    return 1 << 30;     // a looped sample: the skip never
                }                       // reopens on its own
                long ticks = samples[want.Sample] + 1;
                long divisor = (long) Prescale[want.Prescaler & 7] * want.Counter;
                long scaled = ticks * divisor * Rate + MfpClock / 16;
                return (int) ((scaled + MfpClock - 1) / MfpClock);
            }
        }

        // -------------------------------------------------------- the stage

        /// <summary>The effect stage, replayed frame by frame: the skip is
        /// state, so this steps once per played frame, given the dump frame
        /// that frame shows. A .YMR binds each timer to one voice, so every
        /// branch reads only its own voice.</summary>
        public sealed class Stage
        {
            private readonly Ymr dump;
            private int played;
            private readonly int[] last = new int[3];
            private readonly int[] owner = {-1, -1, -1};
            private readonly int[] end = {-1, -1, -1};
            private int skips;

            public Stage(Ymr dump)
            {
                this.dump = dump;
            }

            /// <summary>The tune starts over, so nothing is running from its
            /// end.</summary>
            public void Restart()
            {
                for (int i = 0; i < 3; i++)
                {
                    last[i] = 0;
                    owner[i] = -1;
                    end[i] = -1;
                }
                skips = 0;
            }

            /// <summary>Advances one played frame showing dump frame frame;
            /// returns {skipped, buzzing, started}, each a voice mask.</summary>
            public int[] Step(int frame)
            {
                int now = played++;
                for (int voice = 0; voice < 3; voice++)
                {
                    if (owner[voice] >= 0 && end[voice] == now)
                    {
                        owner[voice] = -1;  // the marker tick has run by now
                        end[voice] = -1;
                        skips &= ~(1 << voice);
                    }
                }
                int started = 0;
                int buzzing = 0;
                for (int channel = 0; channel < 3; channel++)
                {
                    int code = dump.Codes[channel][frame];
                    if (code != 0 && (code & 0xC0) == KindRetrigger)
                    {
                        buzzing |= 1 << channel;
                    }
                    int old = last[channel];
                    last[channel] = code;
                    if (code == old)
                    {
                        continue;   // held: a .YMR's trigger is a pop, so
                    }               // nothing re-fires on a repeated code
                    int voice = channel;
                    if (code == 0)
                    {
                        // A .YMR can say stop, and every command that does
                        // programs the one timer the sample was ticking on,
                        // so a sample ends on the frame it is stopped.
                        Drop(channel, voice);
                        if ((old & 0xC0) == KindToggle && old != 0)
                        {
                            skips &= ~(1 << voice);
                        }
                        continue;
                    }
                    int kind = code & 0xC0;
                    if (kind == KindRetrigger)
                    {
                        Drop(channel, voice);
                        continue;   // a buzzer writes R13, never a volume
                    }
                    if (kind == KindToggle)
                    {
                        // The sample this channel was playing ends here too,
                        // and the skip stands: the square requires it as
                        // well.
                        if (owner[voice] == channel)
                        {
                            owner[voice] = -1;
                            end[voice] = -1;
                        }
                        // A start and a retune differ in the top nibble of
                        // the code, and only the start touches the chip.
                        if (old == 0 || ((code ^ old) & 0xF0) != 0)
                        {
                            started |= 1 << voice;
                        }
                    }
                    else
                    {
                        owner[voice] = channel;
                        end[voice] = now + dump.Window[channel][frame];
                    }
                    skips |= 1 << voice;
                }
                return new[] {skips, buzzing, started};
            }

            /// <summary>The sample this channel still owns, ended because
            /// the channel was told to do something else.</summary>
            private void Drop(int channel, int voice)
            {
                if (owner[voice] == channel)
                {
                    owner[voice] = -1;
                    end[voice] = -1;
                    skips &= ~(1 << voice);
                }
            }
        }

        // --------------------------------------------------------------- MFP

        // One row per MFP timer, in A B C D order: control, data, the
        // interrupt enable and mask registers its bit lives in, and that
        // bit. Timers C and D share TCDCR, so a claim is checked as a
        // nibble.
        private static readonly ulong[][] Timers = {
                new ulong[] {0xFFFFFA19, 0xFFFFFA1F, 0xFFFFFA07, 0xFFFFFA13, 5},
                new ulong[] {0xFFFFFA1B, 0xFFFFFA21, 0xFFFFFA07, 0xFFFFFA13, 0},
                new ulong[] {0xFFFFFA1D, 0xFFFFFA23, 0xFFFFFA09, 0xFFFFFA15, 5},
                new ulong[] {0xFFFFFA1D, 0xFFFFFA25, 0xFFFFFA09, 0xFFFFFA15, 4}};

        // The spec's normative binding: channel 0 runs on Timer A, 1 on B,
        // 2 on D, and the fourth channel no .YMR fills takes the leftover
        // Timer C.
        private static readonly int[] ChannelTimer = {0, 1, 3, 2};
        private static readonly char[] TimerNames = {'A', 'B', 'C', 'D'};

        private const ulong Tcdcr = 0xFFFFFA1D;
        private const ulong Tcdr = 0xFFFFFA23;

        // The interrupt registers each timer's bit lives in, by group, in
        // the same A B C D order.
        private static readonly ulong[][] Interrupt = {
                new ulong[] {0xFFFFFA07, 0xFFFFFA0B, 0xFFFFFA0F, 0xFFFFFA13},
                new ulong[] {0xFFFFFA07, 0xFFFFFA0B, 0xFFFFFA0F, 0xFFFFFA13},
                new ulong[] {0xFFFFFA09, 0xFFFFFA0D, 0xFFFFFA11, 0xFFFFFA15},
                new ulong[] {0xFFFFFA09, 0xFFFFFA0D, 0xFFFFFA11, 0xFFFFFA15}};

        /// <summary>What the MFP writes say about who claimed which timer:
        /// Timer C must stay untouched whatever the tune does - its control
        /// bits share a byte with Timer D's, so every write to that byte
        /// must leave the high nibble alone, and its data register is never
        /// written.</summary>
        public static string MfpProblem(List<Player.Write> writes, int used)
        {
            var allowed = new HashSet<ulong>();
            for (int channel = 0; channel < 3; channel++)
            {
                if ((used & (1 << channel)) != 0)
                {
                    ulong[] row = Timers[ChannelTimer[channel]];
                    allowed.Add(row[0]);
                    allowed.Add(row[1]);
                    foreach (ulong register in Interrupt[ChannelTimer[channel]])
                    {
                        allowed.Add(register);
                    }
                }
            }
            var seen = new Dictionary<ulong, int>();    // address -> bits set
            foreach (Player.Write write in writes)
            {
                seen[write.Address] = (seen.TryGetValue(write.Address,
                        out int bits) ? bits : 0) | write.Value;
                if (write.Address == Tcdr)
                {
                    return "wrote Timer C's data register";
                }
                if (write.Address == Tcdcr && (write.Value & 0xF0) != 0)
                {
                    return string.Format(
                            "programmed Timer C in TCDCR (0x{0:x2})", write.Value);
                }
                if (!allowed.Contains(write.Address))
                {
                    return string.Format("wrote 0x{0:x8}, which no timer this"
                            + " tune uses owns", write.Address);
                }
            }
            for (int channel = 0; channel < 4; channel++)
            {
                ulong[] row = Timers[ChannelTimer[channel]];
                char timerName = TimerNames[ChannelTimer[channel]];
                ulong data = row[1];
                ulong enable = row[2];
                ulong unmask = row[3];
                int bit = (int) row[4];
                bool claimed = channel < 3 && (used & (1 << channel)) != 0;
                foreach ((ulong register, string what) in new[] {
                        (enable, "enabled"), (unmask, "unmasked")})
                {
                    bool live = seen.TryGetValue(register, out int bits)
                            && (bits & (1 << bit)) != 0;
                    if (claimed && !live)
                    {
                        return "never " + what + " Timer " + timerName
                                + ", which channel " + channel + " uses";
                    }
                    if (!claimed && live)
                    {
                        return what + " Timer " + timerName + " for channel "
                                + channel + ", which the tune never uses";
                    }
                }
                if (!claimed && seen.ContainsKey(data))
                {
                    return "wrote Timer " + timerName
                            + "'s data register for an idle channel";
                }
            }
            return "";
        }

        // --------------------------------------------------------- the sweep

        /// <summary>How far the walk goes into a long tune; raising it is
        /// the only way to reach the wrap.</summary>
        private static int FrameCap()
        {
            string? cap = Environment.GetEnvironmentVariable("YMR_FRAME_CAP");
            return cap == null ? 1200 : int.Parse(cap);
        }

        public static string SweepOne(string path)
        {
            string name = Path.GetFileName(path);
            Ymr dump;
            try
            {
                dump = new Ymr(File.ReadAllBytes(path));
            }
            catch (Malformed problem)
            {
                return "SKIP " + name + ": " + problem.Message;
            }
            catch (IOException problem)
            {
                return "SKIP " + name + ": " + problem.Message;
            }
            catch (IndexOutOfRangeException)
            {
                // A field or a block that runs off the end of the image; the
                // parser checks what it can name, and this is the rest.
                return "SKIP " + name + ": truncated .YMR image";
            }

            string ymx = Path.Combine(Path.GetTempPath(),
                    "ymr_sweep" + Environment.ProcessId + ".ymx");
            try
            {
                // -k1 so the packer inserts no padding frames: every played
                // frame is a frame the .YMR carries, and the expectation is
                // exact.
                List<string> command = Rig.OwnTool("ymr");
                command.AddRange(new[] {"-f", "-k1", Path.GetFullPath(path), ymx});
                Rig.Finished packed = Rig.TryRun(command);
                if (packed.ExitCode != 0)
                {
                    string[] lines = packed.Output.Trim().Split('\n');
                    return "PACKFAIL " + name + ": " + lines[^1];
                }
                var warns = new List<string>();
                foreach (string line in packed.Output.Replace("\r", "\n").Split('\n'))
                {
                    if (line.StartsWith("Warning"))
                    {
                        warns.Add(line);
                    }
                }
                return Play(name, dump, File.ReadAllBytes(ymx), warns);
            }
            catch (Exception problem) when (problem is IOException
                    || problem is InvalidOperationException)
            {
                return "ISSUE " + name + ": " + problem.Message;
            }
            finally
            {
                File.Delete(ymx);
            }
        }

        /// <summary>Runs the packed tune through the rig and compares every
        /// frame; its length and whether it starts over come from the
        /// PACKED FILE's header, the contract the player itself reads.</summary>
        private static string Play(string name, Ymr dump, byte[] packed,
                List<string> warns)
        {
            int flags = (packed[6] << 8) | packed[7];
            int played = (packed[8] << 24) | (packed[9] << 16)
                    | (packed[10] << 8) | packed[11];
            int ring = (packed[16] << 8) | packed[17];
            bool loops = (flags & 1) != 0;
            if (((flags >> 1) & 15) != dump.Used)
            {
                return string.Format("ISSUE {0}: the header marks timer channels"
                        + " 0x{1:x}, the dump uses 0x{2:x}", name,
                        (flags >> 1) & 15, dump.Used);
            }

            var player = new Player(packed, Rig.WorkspaceSize(ring));
            if (player.Init() != 0)
            {
                return "INITFAIL " + name;
            }
            string problem = MfpProblem(player.Mfp, dump.Used);
            if (problem.Length != 0)
            {
                return "ISSUE " + name + ": YMX_init " + problem;
            }
            var claim = new List<Player.Write>(player.Mfp);

            // The same budget the .ym sweep plays: a short tune goes right
            // round and out the other side, a long one plays its first
            // FRAME_CAP frames.
            int cap = FrameCap();
            int budget = played <= 3000 ? played + 200 : cap;
            var stage = new Stage(dump);
            bool wrapped = false;
            int walked = 0;
            // What the walk actually got to see, so a cap that crossed
            // nothing interesting says so on its own status line.
            int edges = 0;
            int pops = 0;
            int buzzers = 0;
            int starts = 0;
            int wasSkipped = 0;
            for (int frame = 0; frame < budget; frame++)
            {
                int source = frame % played;    // the same frames, over and
                if (frame != 0 && source == 0)  // over
                {
                    stage.Restart();    // the player silenced everything
                }
                Player.Frame result = player.PlayFrame();
                if (result.Result == -1)
                {
                    return "ISSUE " + name + ": ended early at frame " + frame
                            + "/" + played;
                }
                if (result.Result == 1)
                {
                    wrapped = true;
                }
                int[] masks = stage.Step(source);
                problem = Compare(dump, frame, source, result.Writes, masks[0],
                        masks[2]);
                if (problem.Length != 0)
                {
                    return "ISSUE " + name + ": " + problem;
                }
                var all = new List<Player.Write>(claim);
                all.AddRange(player.Mfp);
                problem = MfpProblem(all, dump.Used);
                if (problem.Length != 0)
                {
                    return "ISSUE " + name + ": frame " + frame + " " + problem;
                }
                edges += System.Numerics.BitOperations.PopCount(
                        (uint) (masks[0] ^ wasSkipped));
                wasSkipped = masks[0];
                pops += dump.Registers[13][source] != NoShape ? 1 : 0;
                buzzers += masks[1] != 0 ? 1 : 0;
                starts += System.Numerics.BitOperations.PopCount((uint) masks[2]);
                walked = frame + 1;
                if (!loops && walked == played)
                {
                    break;
                }
            }

            var timers = new System.Text.StringBuilder();
            for (int channel = 0; channel < 3; channel++)
            {
                if ((dump.Used & (1 << channel)) != 0)
                {
                    timers.Append(TimerNames[ChannelTimer[channel]]);
                }
            }
            string where = wrapped ? "started over"
                    : walked < played ? "partial" : "once";
            string crossings = string.Format(
                    "{0} skip edge{1}, {2} PWM start{3}, {4} buzzer frame{5},"
                    + " {6} shape pop{7}",
                    edges, edges == 1 ? "" : "s", starts, starts == 1 ? "" : "s",
                    buzzers, buzzers == 1 ? "" : "s", pops, pops == 1 ? "" : "s");
            string extra = warns.Count == 0 ? ""
                    : " [" + string.Join("; ", warns) + "]";
            return string.Format("OK {0} ({1}f of {2} played, cap {3}, {4};"
                    + " timers {5}; {6}; {7} sample trigger{8} in the whole"
                    + " dump){9}", name, walked, played, cap, where,
                    timers.Length == 0 ? "none" : timers.ToString(), crossings,
                    dump.Triggers, dump.Triggers == 1 ? "" : "s", extra);
        }

        /// <summary>One frame's chip writes against the .YMR's own frame,
        /// with the effect stage's verdict on the three volume registers.</summary>
        private static string Compare(Ymr dump, int frame, int source,
                List<Player.Pair> writes, int skipped, int started)
        {
            var counted = new Dictionary<int, int>();
            var got = new Dictionary<int, int>();
            foreach (Player.Pair pair in writes)
            {
                if (pair.Register > 13)
                {
                    return "frame " + frame + " wrote R" + pair.Register
                            + ", which is an I/O port";
                }
                counted[pair.Register] = (counted.TryGetValue(pair.Register,
                        out int count) ? count : 0) + 1;
                got[pair.Register] = pair.Value;
            }

            // The periods, the noise and the envelope period: the burst
            // writes every one of them every frame, so a missing or repeated
            // write is as wrong as a wrong value.
            foreach (int register in new[] {0, 1, 2, 3, 4, 5, 6, 11, 12})
            {
                int want = dump.Registers[register][source] & Mask[register];
                if (!counted.TryGetValue(register, out int count) || count != 1)
                {
                    return "frame " + frame + " wrote R" + register + " "
                            + (counted.ContainsKey(register) ? count : 0)
                            + " times";
                }
                if (!got.TryGetValue(register, out int value) || value != want)
                {
                    return "frame " + frame + " R" + register + " wrote "
                            + value + ", want " + want;
                }
            }

            // R7 is the mixer plus the ST's port directions and nothing
            // else: a bit here that the .YMR did not ask for is a bit nobody
            // can account for.
            int want7 = (dump.Registers[7][source] & Mask[7]) | Ports;
            if (!counted.TryGetValue(7, out int count7) || count7 != 1)
            {
                return "frame " + frame + " wrote R7 "
                        + (counted.ContainsKey(7) ? count7 : 0) + " times";
            }
            int got7 = got.TryGetValue(7, out int mixer) ? mixer : -1;
            if (got7 != want7)
            {
                int unexplained = got7 & ~want7 & 0xFF;
                return string.Format("frame {0} R7 wrote 0x{1:x2}, want"
                        + " 0x{2:x2}{3}", frame, got7, want7,
                        unexplained != 0 ? string.Format(
                                " (unexplained bits 0x{0:x2})", unexplained) : "");
            }

            // The volumes, against the skips: a skipped voice's register
            // must be absent, an open one exact, and a PWM's first frame is
            // one write of zero.
            for (int voice = 0; voice < 3; voice++)
            {
                int register = 8 + voice;
                bool wroteIt = got.TryGetValue(register, out int wrote);
                if ((started & (1 << voice)) != 0)
                {
                    if (!counted.TryGetValue(register, out int startCount)
                            || startCount != 1 || !wroteIt || wrote != 0)
                    {
                        return "frame " + frame + " started a PWM on voice "
                                + "ABC"[voice] + " and wrote R" + register + " "
                                + (wroteIt ? wrote.ToString() : "nothing")
                                + ", want one write of 0";
                    }
                    continue;
                }
                if ((skipped & (1 << voice)) != 0)
                {
                    if (counted.ContainsKey(register))
                    {
                        return "frame " + frame + " wrote R" + register
                                + " that a skip covers (voice " + "ABC"[voice]
                                + " is running a PWM or a sample)";
                    }
                    continue;
                }
                if (!counted.TryGetValue(register, out int openCount)
                        || openCount != 1)
                {
                    return "frame " + frame + " wrote R" + register + " "
                            + (counted.ContainsKey(register) ? openCount : 0)
                            + " times";
                }
                int open = dump.Registers[register][source] & Mask[register];
                // A buzzing voice is no special case: the byte is the dump's
                // own and is compared whole.
                if (!wroteIt || wrote != open)
                {
                    return "frame " + frame + " R" + register + " wrote "
                            + (wroteIt ? wrote.ToString() : "nothing")
                            + ", want " + open;
                }
            }

            // R13 is the one register a frame may decline to write, and the
            // write is an event in its own right.
            int shape = dump.Registers[13][source];
            bool wroteShape = got.TryGetValue(13, out int shapeWrote);
            if (shape == NoShape)
            {
                if (counted.ContainsKey(13))
                {
                    return "frame " + frame + " wrote R13 (" + shapeWrote
                            + ") on a frame that popped no shape";
                }
            }
            else if (!counted.TryGetValue(13, out int shapeCount)
                    || shapeCount != 1)
            {
                return "frame " + frame + " wrote R13 "
                        + (counted.ContainsKey(13) ? counted[13] : 0)
                        + " times, want once";
            }
            else if (!wroteShape || shapeWrote != (shape & Mask[13]))
            {
                return "frame " + frame + " R13 wrote "
                        + (wroteShape ? shapeWrote.ToString() : "nothing")
                        + ", want " + (shape & Mask[13]);
            }
            return "";
        }

        public static void Main(string[] args)
        {
            var tunes = new List<string>(args);
            if (tunes.Count == 0)
            {
                tunes.Add(Path.Combine(Rig.Repo, "ymr", "test", "deeper.ymr"));
            }
            int failed = 0;
            foreach (string tune in tunes)
            {
                string line = SweepOne(tune);
                if (line.StartsWith("ISSUE") || line.StartsWith("PACKFAIL")
                        || line.StartsWith("INITFAIL"))
                {
                    failed = 1;
                }
                Console.WriteLine(line);
            }
            Environment.Exit(failed);
        }
    }
}
