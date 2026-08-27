using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using Ymx;

namespace Rig
{
    /// <summary>
    /// A .ymx against SPEC.md §9.3 - the rules a player does not check.
    ///
    /// <para>A file that breaks one of those rules is undefined behaviour
    /// (§9.1): the player reads it, drives the chip from it and reports
    /// nothing, so a writer that breaks one hears the result rather than
    /// reading it. This decodes the streams and reads the rules back off
    /// them.</para>
    ///
    /// <para>What it reads is listed in doc/tools.md. Two rules are outside
    /// it: the sample table's own bounds, which this reads without checking,
    /// and R13's $FF on every frame that must not restart the envelope - a
    /// marker whose absence is a value the file is free to carry.</para>
    ///
    /// <para>The class is not named Check: the assertion helper of that name
    /// sits in the global namespace, and a Rig.Check would take its place for
    /// every Check.That in this namespace.</para>
    /// </summary>
    public static class SpecCheck
    {
        /// <summary>The bits §2's table leaves in each register's value.</summary>
        private static readonly int[] Mask = {0xFF, 0x0F, 0xFF, 0x0F, 0xFF,
                0x0F, 0x1F, 0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F};

        private static readonly string[] Opcode = {"RESUME", "HOLD", "RELEASE",
                "START_TOGGLE", "RETUNE", "START_RETRIGGER", "START_PCM",
                "START_PCM_PREEMPT"};

        private const int Resume = 0;
        private const int Hold = 1;
        private const int Release = 2;
        private const int StartToggle = 3;
        private const int Retune = 4;
        private const int StartRetrigger = 5;
        private const int StartPcm = 6;
        private const int StartPcmPreempt = 7;

        /// <summary>M bit 4: bits 7-5 are read this frame.</summary>
        private const int MSkips = 0x10;

        /// <summary>No voice: the value a two-bit voice field carries for
        /// RETUNE's live form, and this class's mark for a channel driving no
        /// voice.</summary>
        private const int NoVoice = 3;

        /// <summary>The kinds a channel's stream can be, and the absence of
        /// one.</summary>
        private enum Kind { None, Toggle, Retrigger, Pcm }

        /// <summary>One place the file leaves the rules, and where.</summary>
        public sealed record Fault(int Frame, string Rule, string Detail)
        {
            public override string ToString()
            {
                return (Frame < 0 ? "header" : "frame " + Frame)
                        + ": " + Rule + " - " + Detail;
            }
        }

        /// <summary>What one channel's timer is carrying between two action
        /// bytes.</summary>
        private sealed class Channel
        {
            public Kind Kind = Kind.None;
            public int Voice = NoVoice;
            public int Prescaler;
            /// <summary>The frame a one-shot could first have finished on.</summary>
            public int Rejoin;
            /// <summary>The timer counts.</summary>
            public bool Running;
            /// <summary>Released, its interrupt down.</summary>
            public bool Disabled;
        }

        public static void Main(string[] args)
        {
            Console.OutputEncoding = new UTF8Encoding(false);
            int failed = 0;
            foreach (string name in args)
            {
                List<Fault> faults = Read(File.ReadAllBytes(name));
                Console.WriteLine(name + ": "
                        + (faults.Count == 0 ? "within §9.3"
                                : faults.Count + " outside §9.3"));
                foreach (Fault fault in faults)
                {
                    Console.WriteLine("  " + fault);
                    failed = 1;
                }
            }
            Environment.Exit(failed);
        }

        /// <summary>Every rule this reads that the file breaks, in frame
        /// order.</summary>
        public static List<Fault> Read(byte[] file)
        {
            var faults = new List<Fault>();
            if (file.Length < YmxFormat.HeaderSize
                    || LongAt(file, YmxFormat.OffsetMagic) != YmxFormat.Magic)
            {
                faults.Add(new Fault(-1, "§1.1 magic",
                        "the file does not open with 'YMX!'"));
                return faults;
            }
            int version = WordAt(file, YmxFormat.OffsetVersion);
            if (version != YmxFormat.Version)
            {
                faults.Add(new Fault(-1, "§1.1 version", "format "
                        + YmxFormat.VersionName(version) + ", not "
                        + YmxFormat.VersionName()));
                return faults;
            }
            int frames = LongAt(file, YmxFormat.OffsetFrames);
            int streams = WordAt(file, YmxFormat.OffsetStreamCount);
            int ring = WordAt(file, YmxFormat.OffsetRingSize);
            int flags = WordAt(file, YmxFormat.OffsetFlags);
            int loopFrame = LongAt(file, YmxFormat.OffsetLoopFrame);
            int loopTable = LongAt(file, YmxFormat.OffsetLoopTable);
            Shape(file, faults, frames, streams, ring, loopFrame, loopTable);
            if (faults.Count > 0)
            {
                return faults;
            }

            // §6's table, which the rejoin bound below reads: one entry of
            // eight bytes per sample, its length at 4 and its loop point at 6.
            int sampleTable = LongAt(file, YmxFormat.OffsetSampleTable);
            int sampleCount = sampleTable == 0 ? 0
                    : WordAt(file, YmxFormat.OffsetSampleCount);
            int[] length = new int[sampleCount];
            int[] loop = new int[sampleCount];
            for (int sample = 0; sample < sampleCount; sample++)
            {
                int at = sampleTable + YmxFormat.SampleEntrySize * sample;
                if (at < 0
                        || at > file.Length - YmxFormat.SampleEntrySize)
                {
                    faults.Add(new Fault(-1, "§6 sample table",
                            "entry " + sample + " lies outside the file"));
                    return faults;
                }
                length[sample] = WordAt(file, at + 4);
                loop[sample] = WordAt(file, at + 6);
            }
            int rate = WordAt(file, YmxFormat.OffsetPlayerHz);

            byte[][] value = new byte[YmxFormat.Streams][];
            for (int stream = 0; stream < YmxFormat.Streams; stream++)
            {
                try
                {
                    value[stream] = Stream(file, stream, frames, loopFrame,
                            loopTable);
                }
                catch (Exception e) when (e is SystemException
                        || e is AssertionException)
                {
                    faults.Add(new Fault(-1, "§1.4 section", "stream " + stream
                            + " does not decode: " + e.Message));
                }
            }
            if (faults.Count > 0)
            {
                return faults;
            }
            Registers(faults, value, frames);
            Script(faults, value, frames, flags, length, loop, rate);
            return faults;
        }

        // -----------------------------------------------------------------
        // The shape
        // -----------------------------------------------------------------

        private static void Shape(byte[] file, List<Fault> faults, int frames,
                int streams, int ring, int loopFrame, int loopTable)
        {
            if (frames < 1)
            {
                faults.Add(new Fault(-1, "§9.3 shape",
                        "O is " + frames + ", not at least 1"));
            }
            if (streams < YmxFormat.Streams || streams > YmxFormat.MaxStreams)
            {
                faults.Add(new Fault(-1, "§1.5 S", "the stream count is "
                        + streams + ", outside " + YmxFormat.Streams + " to "
                        + YmxFormat.MaxStreams));
            }
            if (ring < 1 || ring > YmxFormat.MaxRingSize)
            {
                faults.Add(new Fault(-1, "§1.3 N", "the ring size is " + ring
                        + ", outside 1 to " + YmxFormat.MaxRingSize));
            }
            for (int stream = 0; stream < YmxFormat.Streams; stream++)
            {
                if (Entry(file, YmxFormat.OffsetSectionTable, stream) == 0)
                {
                    faults.Add(new Fault(-1, "§9.3 shape",
                            "section-table entry " + stream + " is 0"));
                }
            }
            if (loopFrame < 0)
            {
                faults.Add(new Fault(-1, "§9.3 shape",
                        "L is " + loopFrame + ", not a frame index"));
                return;                     // O - L is read below
            }
            if (loopFrame != 0 && loopFrame >= frames)
            {
                faults.Add(new Fault(-1, "§9.3 shape",
                        "L is " + loopFrame + ", not below O at " + frames));
                return;                     // O - L is read below
            }
            if (loopTable == 0)
            {
                if (loopFrame != 0 && frames - loopFrame > ring)
                {
                    faults.Add(new Fault(-1, "§9.3 shape",
                            "one section per stream and O - L is "
                            + (frames - loopFrame) + ", past the ring at "
                            + ring + ": a wrap reaches back further than a"
                            + " pass"));
                }
                return;
            }
            if (loopTable % 4 != 0)
            {
                faults.Add(new Fault(-1, "§9.3 shape", "the loop table is at "
                        + loopTable + ", off a long boundary"));
            }
            if (loopFrame == 0)
            {
                faults.Add(new Fault(-1, "§9.3 shape",
                        "the file carries a loop table and L is 0"));
            }
            if (frames - loopFrame <= ring)
            {
                faults.Add(new Fault(-1, "§9.3 shape",
                        "the file carries a loop table and O - L is "
                        + (frames - loopFrame) + ", within the ring at " + ring
                        + ": one section per stream is the form"));
            }
            // The table's own extent, which the entries below are read from: a
            // header naming a table past the file's end has no entries to read.
            if (loopTable < 0
                    || loopTable > file.Length - 4 * YmxFormat.Streams)
            {
                faults.Add(new Fault(-1, "§9.3 shape", "the loop table is at "
                        + loopTable + ", outside the file at " + file.Length
                        + " bytes"));
                return;
            }
            for (int stream = 0; stream < YmxFormat.Streams; stream++)
            {
                if (Entry(file, loopTable, stream) == 0)
                {
                    faults.Add(new Fault(-1, "§9.3 shape",
                            "loop-table entry " + stream + " is 0"));
                }
            }
        }

        // -----------------------------------------------------------------
        // The register values
        // -----------------------------------------------------------------

        private static void Registers(List<Fault> faults, byte[][] value,
                int frames)
        {
            for (int register = 0; register < YmxFormat.RegisterStreams;
                    register++)
            {
                for (int frame = 0; frame < frames; frame++)
                {
                    int byteValue = value[register][frame];
                    if (register == 13 && byteValue == 0xFF)
                    {
                        continue;           // the marker: R13 is not written
                    }
                    if ((byteValue & ~Mask[register]) != 0)
                    {
                        faults.Add(new Fault(frame, "§2 register mask",
                                "R" + register + " carries " + Hex(byteValue)
                                + ", outside the mask " + Hex(Mask[register])));
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // The script: M, T and the action bytes
        // -----------------------------------------------------------------

        private static void Script(List<Fault> faults, byte[][] value,
                int frames, int flags, int[] length, int[] loop, int rate)
        {
            byte[] master = value[YmxFormat.StreamM];
            byte[] spare = value[YmxFormat.StreamX];
            byte[] timers = value[YmxFormat.StreamT];
            int live = 0;
            for (int channel = 0; channel < YmxFormat.Channels; channel++)
            {
                if ((flags & YmxFormat.FlagChannel(channel)) != 0)
                {
                    live |= 1 << channel;
                }
            }
            var channels = new Channel[YmxFormat.Channels];
            for (int channel = 0; channel < channels.Length; channel++)
            {
                channels[channel] = new Channel();
            }
            int claimed = TimerMap(faults, timers, live);
            int skips = 0;          // a player begins with all three clear
            int previousMap = timers[0];
            bool[] reported = new bool[3];

            for (int frame = 0; frame < frames; frame++)
            {
                int m = master[frame];
                if ((m & ~(0x0F | MSkips | 0xE0)) != 0)
                {
                    faults.Add(new Fault(frame, "§2.1 M",
                            "the master byte is " + Hex(m)));
                }
                if ((m & 0x0F & ~live) != 0)
                {
                    faults.Add(new Fault(frame, "§9.3 values", "M marks channel "
                            + System.Numerics.BitOperations.TrailingZeroCount(
                                    (uint) (m & 0x0F & ~live))
                            + ", which §1.2's flags do not"));
                }
                if ((m & MSkips) != 0)
                {
                    skips = (m >> 5) & 7;
                }
                Map(faults, frame, timers, previousMap, live, claimed, channels);
                previousMap = timers[frame];
                for (int channel = 0; channel < YmxFormat.Channels; channel++)
                {
                    if ((m & (1 << channel)) != 0)
                    {
                        Act(faults, frame, channel, channels, value,
                                spare[frame], length, loop, rate);
                    }
                }
                Ownership(faults, frame, skips, channels, reported);
            }
        }

        /// <summary>Frame 0's byte claims a timer per flagged channel, all
        /// distinct.</summary>
        private static int TimerMap(List<Fault> faults, byte[] timers, int live)
        {
            int byteValue = timers[0];
            int claimed = 0;
            for (int channel = 0; channel < YmxFormat.Channels; channel++)
            {
                if ((live & (1 << channel)) == 0)
                {
                    continue;
                }
                int timer = 1 << YmxFormat.TimerOf(byteValue, channel);
                if ((claimed & timer) != 0)
                {
                    faults.Add(new Fault(0, "§9.3 actions",
                            "two flagged channels name Timer "
                            + "ABCD"[YmxFormat.TimerOf(byteValue, channel)]
                            + " at frame 0"));
                }
                claimed |= timer;
            }
            return claimed;
        }

        /// <summary>A changed T entry moves a channel with nothing running, to
        /// a timer frame 0 claimed.</summary>
        private static void Map(List<Fault> faults, int frame, byte[] timers,
                int previous, int live, int claimed, Channel[] channels)
        {
            int byteValue = timers[frame];
            if (byteValue == previous)
            {
                return;
            }
            for (int channel = 0; channel < YmxFormat.Channels; channel++)
            {
                if ((live & (1 << channel)) == 0
                        || YmxFormat.TimerOf(byteValue, channel)
                                == YmxFormat.TimerOf(previous, channel))
                {
                    continue;
                }
                int timer = YmxFormat.TimerOf(byteValue, channel);
                if (channels[channel].Running)
                {
                    faults.Add(new Fault(frame, "§2.3 T", "channel " + channel
                            + " moves to Timer " + "ABCD"[timer]
                            + " with a timer still running"));
                }
                if ((claimed & (1 << timer)) == 0)
                {
                    faults.Add(new Fault(frame, "§9.3 actions", "channel "
                            + channel + " moves to Timer " + "ABCD"[timer]
                            + ", which frame 0 did not claim"));
                }
            }
        }

        /// <summary>One channel's action byte, and what it leaves the channel
        /// carrying.</summary>
        private static void Act(List<Fault> faults, int frame, int channel,
                Channel[] channels, byte[][] value, int spare, int[] length,
                int[] loop, int rate)
        {
            int action = value[YmxFormat.StreamAction(channel)][frame];
            int opcode = action >> 5;
            int voice = (action >> 3) & 3;
            int low = action & 7;
            Channel state = channels[channel];
            string name = Opcode[opcode] + " on channel " + channel;

            if (opcode == Release && voice != 0)
            {
                faults.Add(new Fault(frame, "§2.4 A", name + " names voice "
                        + voice + "; the field is written as 0"));
            }
            if (opcode != Retune && opcode != Release && voice == NoVoice)
            {
                faults.Add(new Fault(frame, "§2.4 A", name + " names voice 3"));
            }
            if (Programs(opcode) && (low < 1 || low > 7))
            {
                faults.Add(new Fault(frame, "§9.3 actions", name
                        + " carries prescaler index " + low + ", outside 1 to 7"));
            }
            switch (opcode)
            {
                case StartToggle:
                    Claim(faults, frame, channels, channel, Kind.Toggle, voice,
                            low);
                    break;
                case StartRetrigger:
                    Claim(faults, frame, channels, channel, Kind.Retrigger,
                            voice, low);
                    break;
                case StartPcm:
                {
                    Triggered(state, value, frame, channel, voice, low, length,
                            loop, rate);
                    if (Silenced(channels, channel, voice) != 0)
                    {
                        faults.Add(new Fault(frame, "§9.3 actions", name
                                + " leaves a running timer standing;"
                                + " START_PCM_PREEMPT is the encoding where one"
                                + " is stopped"));
                    }
                    Claim(faults, frame, channels, channel, Kind.Pcm, voice, low);
                    break;
                }
                case StartPcmPreempt:
                {
                    Triggered(state, value, frame, channel, voice, low, length,
                            loop, rate);
                    int nibble = spare & 0x0F;
                    int stops = Silenced(channels, channel, voice);
                    if (nibble != stops)
                    {
                        faults.Add(new Fault(frame, "§9.3 actions", name
                                + " marks channels " + Hex(nibble)
                                + " in X where the silenced ones are "
                                + Hex(stops)));
                    }
                    for (int other = 0; other < YmxFormat.Channels; other++)
                    {
                        if ((nibble & (1 << other)) != 0)
                        {
                            Stop(channels[other]);
                        }
                    }
                    Claim(faults, frame, channels, channel, Kind.Pcm, voice, low);
                    break;
                }
                case Release:
                    if (state.Kind == Kind.None)
                    {
                        faults.Add(new Fault(frame, "§3",
                                name + " stops a channel with no stream"));
                    }
                    if ((low & 1) != 0)
                    {
                        state.Disabled = true;  // the timer counts on
                    }
                    else
                    {
                        Stop(state);
                    }
                    break;
                case Retune:
                    if (state.Kind == Kind.None)
                    {
                        faults.Add(new Fault(frame, "§3.1",
                                name + " retunes no running stream"));
                    }
                    else if (voice != NoVoice && voice != state.Voice)
                    {
                        faults.Add(new Fault(frame, "§9.3 actions", name
                                + " moves voice " + "ABC"[state.Voice] + " to "
                                + "ABC"[voice] + "; a changed voice re-enters"
                                + " through a start opcode"));
                    }
                    state.Prescaler = low;
                    state.Running = true;
                    state.Disabled = false;
                    break;
                case Resume:
                    if (!state.Disabled)
                    {
                        faults.Add(new Fault(frame, "§9.3 actions",
                                name + " follows no disabling release"));
                    }
                    if (state.Kind != Kind.Toggle)
                    {
                        faults.Add(new Fault(frame, "§3.3", name
                                + " resumes a stream that is not a toggle"
                                + " stream"));
                    }
                    state.Disabled = false;
                    break;
                case Hold:
                    if (state.Kind == Kind.None)
                    {
                        faults.Add(new Fault(frame, "§3",
                                name + " updates no running stream"));
                    }
                    if ((low & 2) != 0 && (low & 4) != 0)
                    {
                        faults.Add(new Fault(frame, "§9.3 actions", name
                                + " sets both flag 2 and flag 4; a channel runs"
                                + " one stream kind"));
                    }
                    break;
                default:
                    break;
            }
        }

        /// <summary>A start opcode takes the channel, and the voice it
        /// names.</summary>
        private static void Claim(List<Fault> faults, int frame,
                Channel[] channels, int channel, Kind kind, int voice,
                int prescaler)
        {
            if (kind != Kind.Retrigger)
            {
                for (int other = 0; other < YmxFormat.Channels; other++)
                {
                    if (other != channel && channels[other].Voice == voice
                            && channels[other].Kind != Kind.None
                            && channels[other].Kind != Kind.Retrigger)
                    {
                        faults.Add(new Fault(frame, "§9.3 actions", "channel "
                                + channel + " starts a second timer stream on"
                                + " voice " + "ABC"[voice] + ", which channel "
                                + other + " already runs"));
                    }
                }
            }
            Channel state = channels[channel];
            state.Kind = kind;
            state.Voice = kind == Kind.Retrigger ? NoVoice : voice;
            state.Prescaler = prescaler;
            state.Running = true;
            state.Disabled = false;
        }

        private static void Stop(Channel state)
        {
            state.Kind = Kind.None;
            state.Voice = NoVoice;
            state.Running = false;
            state.Disabled = false;
        }

        private static bool Programs(int opcode)
        {
            return opcode == Retune || opcode == StartToggle
                    || opcode == StartRetrigger || opcode == StartPcm
                    || opcode == StartPcmPreempt;
        }

        /// <summary>
        /// A trigger's sample and its rate, kept so the rejoin below can be
        /// read off them. The sample number is the voice's register byte on
        /// this frame, which the skip keeps off the chip (§3.2), and the count
        /// is the trigger's own P.
        /// </summary>
        private static void Triggered(Channel state, byte[][] value, int frame,
                int channel, int voice, int prescaler, int[] length, int[] loop,
                int rate)
        {
            int sample = value[8 + voice][frame];
            int count = value[YmxFormat.StreamAction(channel) + 1][frame];
            state.Rejoin = RejoinOf(length, loop, sample, prescaler, count,
                    rate, frame);
        }

        /// <summary>
        /// The frame a one-shot sample started on frame could first have ended
        /// on, which is §6's rejoin bound:
        ///
        /// <pre>
        /// frames = ceil(((length + 1) · prescaler[index] · count · rate
        ///                + 2457600/16) / 2457600)
        /// </pre>
        ///
        /// <para>A looping sample ends of itself at no frame, so it gives
        /// int.MaxValue: a voice it owns rejoins the frame write only where
        /// something stops it.</para>
        /// </summary>
        private static int RejoinOf(int[] length, int[] loop, int sample,
                int prescaler, int count, int rate, int frame)
        {
            if (sample < 0 || sample >= length.Length)
            {
                return int.MaxValue;    // no such sample: §6 has it
            }
            if (loop[sample] != YmxFormat.SampleOneShot)
            {
                return int.MaxValue;    // it loops, and ends at no frame
            }
            long ticks = (long) (length[sample] + 1) * Tune.Prescaler(prescaler)
                    * (count == 0 ? 256 : count);
            const long clock = 2457600L;
            long frames = (ticks * rate + clock / 16 + clock - 1) / clock;
            return frame + (int) frames;
        }

        /// <summary>
        /// The channels a trigger silences: §9.3's rule is what the trigger
        /// stops, not what happens to be running. A trigger takes one voice, so
        /// it silences the channels holding a toggle stream on that voice, and
        /// no others. Its own channel is reprogrammed rather than stopped, and
        /// a stream on another voice is untouched.
        ///
        /// <para>Counting every running channel instead reported 4,888 faults
        /// over 36 of the 543 tunes in the collection, all of them a repeated
        /// trigger meeting its own channel's timer.</para>
        /// </summary>
        private static int Silenced(Channel[] channels, int trigger, int voice)
        {
            int stops = 0;
            for (int channel = 0; channel < YmxFormat.Channels; channel++)
            {
                Channel other = channels[channel];
                if (channel != trigger && other.Kind == Kind.Toggle
                        && other.Running && other.Voice == voice)
                {
                    stops |= 1 << channel;
                }
            }
            return stops;
        }

        /// <summary>
        /// The skip field against what the streams own.
        ///
        /// <para>A voice is skipped while a timer stream writes its volume
        /// register (§2.1), so a skip set on a voice no stream owns locks that
        /// voice out of the frame write for as long as it stands, and a skip
        /// clear on a voice a toggle stream owns has the frame write and the
        /// ticks both writing it. A channel released under the resume model
        /// lands no tick while its interrupt is down (§3.3), so it owns nothing
        /// across the gap and the voice rejoins the frame write there. A PCM
        /// stream ends at its sample's marker rather than at an opcode (§6), so
        /// a cleared skip over one is the rejoin the file is entitled to and
        /// ends this reader's ownership of the voice.</para>
        ///
        /// <para>One fault a run: the frames after an edge carry the same
        /// mismatch as the edge, and the edge is where the writer put it.</para>
        /// </summary>
        private static void Ownership(List<Fault> faults, int frame, int skips,
                Channel[] channels, bool[] reported)
        {
            for (int voice = 0; voice < 3; voice++)
            {
                Kind owner = Kind.None;
                foreach (Channel state in channels)
                {
                    if (state.Voice == voice && state.Kind != Kind.None
                            && !state.Disabled)
                    {
                        owner = state.Kind;
                    }
                }
                bool skipped = (skips & (1 << voice)) != 0;
                string? detail = null;
                if (skipped && owner == Kind.None)
                {
                    detail = " is skipped and no timer stream owns its volume"
                            + " register: the frame write omits R" + (8 + voice)
                            + " and no tick writes it";
                }
                else if (!skipped && owner == Kind.Toggle)
                {
                    detail = " is not skipped and a toggle stream owns its"
                            + " volume register: the frame write and the ticks"
                            + " both write R" + (8 + voice);
                }
                else if (!skipped && owner == Kind.Pcm)
                {
                    // A sample ends at its own marker and the file says nothing
                    // of it, so an unskipped voice reads as one that finished.
                    // §6 bounds when it could have: before that frame it cannot
                    // have, and the skip is one the writer did not set.
                    int earliest = int.MaxValue;
                    foreach (Channel state in channels)
                    {
                        if (state.Voice == voice && state.Kind == Kind.Pcm)
                        {
                            earliest = Math.Min(earliest, state.Rejoin);
                        }
                    }
                    if (frame < earliest)
                    {
                        detail = " is not skipped and a PCM stream owns its"
                                + " volume register: the sample cannot have"
                                + " finished" + (earliest == int.MaxValue
                                        ? ", since it loops"
                                        : " before frame " + earliest);
                    }
                    else
                    {
                        foreach (Channel state in channels)
                        {
                            if (state.Voice == voice && state.Kind == Kind.Pcm)
                            {
                                Stop(state); // the sample reached its marker
                            }
                        }
                    }
                }
                if (detail == null)
                {
                    reported[voice] = false;
                }
                else if (!reported[voice])
                {
                    faults.Add(new Fault(frame, "§9.3 values",
                            "voice " + "ABC"[voice] + detail));
                    reported[voice] = true;
                }
            }
        }

        // -----------------------------------------------------------------
        // Reading the container
        // -----------------------------------------------------------------

        /// <summary>One stream's O values, out of its section and its loop
        /// section.</summary>
        private static byte[] Stream(byte[] file, int index, int frames,
                int loopFrame, int loopTable)
        {
            if (loopTable == 0)
            {
                return Section(file, YmxFormat.OffsetSectionTable, index,
                        frames);
            }
            byte[] head = Section(file, YmxFormat.OffsetSectionTable, index,
                    loopFrame);
            byte[] tail = Section(file, loopTable, index, frames - loopFrame);
            byte[] whole = new byte[frames];
            Array.Copy(head, whole, Math.Min(head.Length, frames));
            Array.Copy(tail, 0, whole, loopFrame, frames - loopFrame);
            return whole;
        }

        /// <summary>One section, decoded to at least count values.</summary>
        private static byte[] Section(byte[] file, int table, int index,
                int count)
        {
            long item = Entry(file, table, index);
            int start = (int) YmxFormat.SectionOffset(item);
            if (start < 0 || start > file.Length)
            {
                throw new InvalidOperationException("the section is at " + start
                        + ", outside the file");
            }
            if (YmxFormat.IsStored(item))
            {
                return RangeOf(file, start, start + count);
            }
            St4.St4Format.Container container = St4.St4Format.Read(
                    RangeOf(file, start, Next(file, start)));
            byte[] outValues = St4.St4Decompressor.Decompress(container.Control,
                    container.Literal, container.ByteOffsets,
                    container.WordOffsets, container.Unit, container.Size);
            if (outValues.Length < count)
            {
                throw new InvalidOperationException(outValues.Length
                        + " values, not " + count);
            }
            return outValues;
        }

        /// <summary>file[from..to] with a short file read as zeros past its
        /// end, which is how the other trees read one.</summary>
        private static byte[] RangeOf(byte[] file, int from, int to)
        {
            byte[] outBytes = new byte[to - from];
            if (from < file.Length)
            {
                Array.Copy(file, from, outBytes, 0,
                        Math.Min(to, file.Length) - from);
            }
            return outBytes;
        }

        /// <summary>
        /// Where the body item at start ends: the next offset any table names,
        /// or the file's end. Content in the body is located by offset alone
        /// (§1.1), so a section's extent is the distance to its neighbour.
        /// </summary>
        private static int Next(byte[] file, int start)
        {
            var offsets = new SortedSet<int> {file.Length};
            int sampleTable = LongAt(file, YmxFormat.OffsetSampleTable);
            if (sampleTable != 0)
            {
                offsets.Add(sampleTable);
            }
            int loopTable = LongAt(file, YmxFormat.OffsetLoopTable);
            if (loopTable != 0)
            {
                offsets.Add(loopTable);
            }
            for (int index = 0; index < YmxFormat.Streams; index++)
            {
                offsets.Add((int) YmxFormat.SectionOffset(
                        Entry(file, YmxFormat.OffsetSectionTable, index)));
                if (loopTable != 0)
                {
                    offsets.Add((int) YmxFormat.SectionOffset(
                            Entry(file, loopTable, index)));
                }
            }
            foreach (int offset in offsets)
            {
                if (offset > start)
                {
                    return offset;
                }
            }
            return file.Length;
        }

        private static long Entry(byte[] file, int table, int index)
        {
            return LongAt(file, table + 4 * index) & 0xFFFF_FFFFL;
        }

        private static int LongAt(byte[] file, int at)
        {
            if (at < 0 || at > file.Length - 4)
            {
                return 0;
            }
            return (file[at] << 24) | (file[at + 1] << 16)
                    | (file[at + 2] << 8) | file[at + 3];
        }

        private static int WordAt(byte[] file, int at)
        {
            if (at < 0 || at > file.Length - 2)
            {
                return 0;
            }
            return (file[at] << 8) | file[at + 1];
        }

        private static string Hex(int value)
        {
            return "$" + value.ToString("X2");
        }
    }
}
