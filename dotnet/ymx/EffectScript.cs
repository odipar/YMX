using System;
using System.Collections.Generic;

namespace Ymx
{
    /// <summary>
    /// The compiled effect script, ported from org.ymx.EffectScript: the
    /// reference player's per-frame decision logic replayed over the whole
    /// timeline at pack time, emitting prepared actions the player executes
    /// without comparing anything against remembered state. The stream ABI
    /// and the opcode table are org.ymx.EffectScript's, byte for byte; the
    /// Java tree carries the full story.
    /// </summary>
    public sealed class EffectScript
    {
        // The action ABI. Opcode 0 is the SID resume - the maxYMiser model.
        public const int OpcodeResume = 0;
        public const int ResumeReload = 1;
        public const int ResumeVolume = 2;
        public const int OpcodeHold = 1 << 5;
        public const int OpcodeRelease = 2 << 5;
        /// <summary>RELEASE flag bit 0: mask instead of stopping.</summary>
        public const int ReleaseMask = 1;
        public const int OpcodeStartToggle = 3 << 5;
        public const int OpcodeRetune = 4 << 5;

        /// <summary>The action byte's voice field addressing no voice;
        /// RETUNE addressed to it is the live rate change.</summary>
        public const int Voiceless = 3;
        public const int OpcodeStartRetrigger = 5 << 5;
        public const int OpcodeStartPcm = 6 << 5;
        public const int OpcodeStartPcmPreempt = 7 << 5;
        public const int HoldReload = 1;
        public const int HoldVolume = 2;
        public const int HoldShape = 4;

        // The master byte.
        public const int MChannel0 = 1;
        public const int MChannel1 = 2;
        public const int MChannel2 = 4;
        public const int MChannel3 = 8;
        public const int MSkips = 16;
        public const int MSkipShift = 5;

        public static int Action(int opcode, int voice, int low)
        {
            return opcode | (voice << 3) | low;
        }

        /// <summary>The decisions the codes cannot make for themselves,
        /// because they follow from how the source format triggers, mixes,
        /// stops and retunes rather than from anything in the bytes.</summary>
        public sealed record Semantics(bool PcmHoldRetriggers, bool ForceMixerOnPcm,
                bool ChannelEndsPcm, bool SidResume, bool RetunesLive)
        {
            /// <summary>The YM dialect: a held PCM code retriggers its sample
            /// every frame, a voice a sample owns is forced off the mixer,
            /// nothing ends a sample but its own marker tick, and a released
            /// toggle stream comes back at phase zero.</summary>
            public static readonly Semantics Ym =
                    new Semantics(true, true, false, false, false);

            /// <summary>The same, with the maxYMiser gap model.</summary>
            public Semantics Resuming()
            {
                return this with { SidResume = true };
            }
        }

        /// <summary>The compiled script: the script streams, the mixer bits
        /// to OR into R7, the sample end edges, and the packer's notes.</summary>
        public sealed record Result(int Frames, byte[] M, byte[][] Actions,
                byte[][] Counts, byte[] X, byte[] Timers, byte[] R7Force,
                IReadOnlyList<int[]> Reopens, IReadOnlyList<string> Notes)
        {
            /// <summary>M, X, T, then each channel's action and count.</summary>
            public byte[][] Streams()
            {
                byte[][] streams = new byte[3 + 2 * Actions.Length][];
                streams[0] = M;
                streams[1] = X;
                streams[2] = Timers;
                for (int c = 0; c < Actions.Length; c++)
                {
                    streams[3 + 2 * c] = Actions[c];
                    streams[4 + 2 * c] = Counts[c];
                }
                return streams;
            }
        }

        /// <summary>A voice never rejoins the frame write: its sample was cut
        /// mid-play - the reference player's stuck flag.</summary>
        private const int Stuck = int.MaxValue;

        private const int KindToggle = Tune.KindToggle;
        private const int KindPcm = Tune.KindPcm;
        private const int KindRetrigger = Tune.KindRetrigger;

        /// <summary>One channel's remembered state - the reference player's
        /// descriptor, field for field, minus the machine addresses.</summary>
        private sealed class Channel
        {
            internal int Elast;
            internal int Tlast;
            internal int Vec = -1;          // what the timer vector holds
            internal int VecVoice = -1;
            internal int Sel = -1;          // the ISR's patched select voice
            internal int Vol = -1;          // the ISR's patched toggle volume
            internal int Shape = -1;        // the ISR's patched retrigger shape
            internal bool Masked;           // a released toggle: interrupt
                                            // masked, the timer still counting
            internal int Prescaler = -1;
        }

        private readonly Tune tune;
        private readonly Channel[] channels = new Channel[YmxFormat.Channels];
        private readonly int[] drumEnd = {-1, -1, -1};
        private readonly int[] drumOwner = {-1, -1, -1};
        private int skips;
        private readonly List<int[]> reopens = new();
        private readonly List<string> notes = new();
        private readonly Semantics semantics;

        private readonly byte[] m;
        private readonly byte[][] actions = new byte[YmxFormat.Channels][];
        private readonly byte[][] counts = new byte[YmxFormat.Channels][];
        private readonly byte[] x;
        private readonly byte[] timers;
        private readonly byte[] r7;
        private readonly int frames;
        private bool stuckNoted;

        private EffectScript(Tune tune)
        {
            this.tune = tune;
            semantics = tune.Semantics;
            frames = tune.Frames;
            m = new byte[frames];
            x = new byte[frames];
            timers = new byte[frames];
            Array.Fill(timers, (byte) YmxFormat.DefaultTimers);
            for (int c = 0; c < YmxFormat.Channels; c++)
            {
                channels[c] = new Channel();
                actions[c] = new byte[frames];
                counts[c] = new byte[frames];
            }
            r7 = new byte[frames];
        }

        public static Result Compile(Tune tune)
        {
            return Compile(tune, YmxFormat.DefaultTimers);
        }

        /// <summary>Compiles the script: one pass over the tune's frames,
        /// from the state a tune starts in, with the channel-to-timer map
        /// the T stream will carry.</summary>
        public static Result Compile(Tune tune, int timerMap)
        {
            var script = new EffectScript(tune);
            Array.Fill(script.timers, (byte) timerMap);
            return script.Run();
        }

        private Result Run()
        {
            for (int p = 0; p < frames; p++)
            {
                Frame(p);
            }
            return new Result(frames, (byte[]) m.Clone(), Copy(actions),
                    Copy(counts), (byte[]) x.Clone(), (byte[]) timers.Clone(),
                    (byte[]) r7.Clone(), new List<int[]>(reopens),
                    new List<string>(notes));
        }

        // One frame: expire sample windows, then every timer channel in
        // turn, lowest first - the order the reference player discovers the
        // same events in, and the order arbitration is decided by.

        private int skipsBefore;

        private void Frame(int p)
        {
            skipsBefore = skips;
            // X's high nibble is this frame's shape, resolved at pack time.
            x[p] = (byte) (Shape(p) << 4);

            for (int v = 0; v < 3; v++)
            {
                if (drumOwner[v] >= 0 && drumEnd[v] == p)
                {
                    drumOwner[v] = -1;      // the marker has run by now: the
                    drumEnd[v] = -1;        // skip lifts, the mixer frees
                    skips &= ~(1 << v);
                    reopens.Add(new[] {p, v});
                }
            }

            for (int c = 0; c < channels.Length; c++)
            {
                DoChannel(p, c);
            }

            if (semantics.ForceMixerOnPcm)
            {
                for (int v = 0; v < 3; v++)
                {
                    if (drumOwner[v] >= 0)
                    {
                        r7[p] |= (byte) (0x09 << v);
                    }
                }
            }
            if (skips != skipsBefore)
            {
                m[p] |= (byte) (MSkips | (skips << MSkipShift));
            }
        }

        /// <summary>ymx_slot, transcribed: the labels in the comments are
        /// the reference player's.</summary>
        private void DoChannel(int p, int index)
        {
            Channel channel = channels[index];
            int code = tune.Codes[index][p];
            int count = tune.Counts[index][p];

            if (code == channel.Elast)
            {
                if (code == 0)
                {
                    return;                 // the idle frame
                }
                Hold(p, index, channel, code, count);
                return;
            }
            int old = channel.Elast;
            channel.Elast = code;
            if (code == 0)
            {                               // .released
                Released(p, index, channel, old);
                return;
            }
            int voice = ((code >> 4) & 3) - 1;
            int type = code & 0xC0;
            if (RetunesLive(old, code) && ParameterHeld(p, channel, type, voice))
            {
                LiveRetune(p, index, channel, code, count);
            }
            else if (type == KindToggle)
            {                               // .toggle
                Toggle(p, index, channel, code, count, voice, old);
            }
            else if (type == KindPcm && RetunesPcm(old, code))
            {
                PcmRetune(p, index, channel, code, count, voice);
            }
            else if (type == KindPcm)
            {                               // .pcm
                Pcm(p, index, channel, code, count, voice, old);
            }
            else
            {                               // the retrigger arm
                Retrigger(p, index, channel, code, count, voice);
            }
        }

        /// <summary>Whether a rate pop can move the prescaler with the timer
        /// left running - a source whose own player reprograms live renders
        /// a rate change as a bend, not a restart.</summary>
        private bool RetunesLive(int old, int code)
        {
            return tune.Semantics.RetunesLive
                    && old != 0 && ((old ^ code) & 0xF8) == 0;  // only bits 2-0
        }

        /// <summary>Whether the effect's parameter byte changed across the
        /// pop; the live retune carries no voice, so it cannot repatch a
        /// volume or a shape on the way through.</summary>
        private bool ParameterHeld(int p, Channel channel, int type, int voice)
        {
            if (type == KindToggle)
            {
                return Parameter(p, voice) == channel.Vol;
            }
            return type == KindPcm || Shape(p) == channel.Shape;
        }

        /// <summary>A rate under a running effect, with nothing stopped:
        /// RETUNE addressed to voice 3.</summary>
        private void LiveRetune(int p, int index, Channel channel, int code, int count)
        {
            channel.Tlast = count;
            channel.Prescaler = code & 7;
            Emit(p, index, Action(OpcodeRetune, Voiceless, code & 7), count);
        }

        /// <summary>Whether a changed PCM code is a rate moving under a
        /// running sample rather than a fresh trigger.</summary>
        private bool RetunesPcm(int old, int code)
        {
            return !tune.Semantics.PcmHoldRetriggers
                    && old != 0 && (old & 0xC0) == KindPcm
                    && ((old ^ code) & 0xF8) == 0;      // only bits 2-0 moved
        }

        /// <summary>A rate under a running sample: RETUNE leaves the vector
        /// alone, so the PCM tick and its pointer survive.</summary>
        private void PcmRetune(int p, int index, Channel channel, int code,
                int count, int voice)
        {
            channel.Tlast = count;
            channel.Prescaler = code & 7;
            Emit(p, index, Action(OpcodeRetune, voice, code & 7), count);
        }

        /// <summary>.held: a running effect's count reload and parameter
        /// tracking - emitted only on frames where a value changed.</summary>
        private void Hold(int p, int index, Channel channel, int code, int count)
        {
            int type = code & 0xC0;
            int voice = ((code >> 4) & 3) - 1;
            // A source with no trigger but the code itself fires the sample
            // again on every frame that repeats the code.
            if (type == KindPcm && semantics.PcmHoldRetriggers)
            {
                Pcm(p, index, channel, code, count, voice, code);
                return;
            }
            int flags = 0;
            if (count != channel.Tlast)
            {
                channel.Tlast = count;
                flags |= HoldReload;
            }
            // A PCM stream tracks no register, so a held one carries the
            // reload and nothing else.
            if (type == KindToggle)
            {                               // .track
                int value = Parameter(p, voice);
                if (value != channel.Vol)
                {
                    channel.Vol = value;
                    flags |= HoldVolume;
                }
            }
            else if (type != KindPcm)
            {
                int value = Shape(p);
                if (value != channel.Shape)
                {
                    channel.Shape = value;
                    flags |= HoldShape;
                }
            }
            if (flags != 0)
            {
                Emit(p, index, Action(OpcodeHold, voice, flags), count);
            }
        }

        /// <summary>.released: a retrigger stream ending stops its timer; a
        /// toggle stream forks on the gap model; a PCM stream finishes
        /// itself, unless the source can say stop.</summary>
        private void Released(int p, int index, Channel channel, int old)
        {
            int type = old & 0xC0;
            if (type == KindPcm)
            {
                if (!semantics.ChannelEndsPcm)
                {
                    return;             // timer left running: the marker ends it
                }
                // A stop is applied on the frame it is said: RELEASE with bit
                // 0 clear stops the timer outright and the voice rejoins this
                // frame's own write.
                if (EndOwnPcm(p, index, -1))
                {
                    Emit(p, index, Action(OpcodeRelease, 0, 0), 0);
                }
                return;
            }
            Cut(p, index, -1);
            if (type == KindToggle)
            {
                OpenOld(old);
                if (tune.Semantics.SidResume)
                {
                    channel.Masked = true;
                    Emit(p, index, Action(OpcodeRelease, 0, ReleaseMask), 0);
                    return;
                }
            }
            Emit(p, index, Action(OpcodeRelease, 0, 0), 0);
        }

        private void Toggle(int p, int index, Channel channel, int code, int count,
                int voice, int old)
        {
            // A sample this same channel is playing is not a rival for the
            // voice: one timer runs both, so arming the square necessarily
            // ends the sample. The arbitration below is for a sample another
            // channel owns.
            if (semantics.ChannelEndsPcm)
            {
                EndOwnPcm(p, index, voice);
            }
            if (drumOwner[voice] >= 0)
            {                               // a PCM stream owns the register:
                channel.Elast = 0;          // retry next frame
                OpenOld(old);
                return;                     // nothing armed, nothing emitted
            }
            int value = Parameter(p, voice);
            // The gap models fork on Masked: a re-arrival on a channel whose
            // masked timer still runs this stream's square at the same
            // prescaler RESUMES; a prescaler change across a masked gap needs
            // the hardware's stop/load/start (RETUNE, half kept); everything
            // else is a full START at phase zero.
            bool sameSid = channel.Vec == KindToggle && channel.VecVoice == voice
                    && channel.Sel == voice;
            bool resume = channel.Masked && sameSid
                    && channel.Prescaler == (code & 7);
            bool retune = old != 0 && ((code ^ old) & 0xF0) == 0
                    || channel.Masked && sameSid && channel.Prescaler != (code & 7);
            Cut(p, index, -1);
            OpenOld(old);
            skips |= 1 << voice;
            channel.Masked = false;
            if (resume)
            {
                int low = 0;
                if (count != channel.Tlast)
                {
                    channel.Tlast = count;
                    low |= ResumeReload;
                }
                if (value != channel.Vol)
                {
                    channel.Vol = value;
                    low |= ResumeVolume;
                }
                Emit(p, index, Action(OpcodeResume, voice, low), count);
                return;
            }
            channel.Tlast = count;
            channel.Vol = value;
            channel.Prescaler = code & 7;
            if (retune)
            {
                Emit(p, index, Action(OpcodeRetune, voice, code & 7), count);
                return;
            }
            channel.Sel = voice;
            channel.Vec = KindToggle;
            channel.VecVoice = voice;
            Emit(p, index, Action(OpcodeStartToggle, voice, code & 7), count);
        }

        private void Pcm(int p, int index, Channel channel, int code, int count,
                int voice, int old)
        {
            if (old != code)
            {                               // the old-effect cleanup
                if ((old & 0xC0) == KindToggle && old != 0)
                {
                    OpenOld(old);
                }
                else if ((old & 0xC0) == KindPcm && old != 0
                        && ((old ^ code) & 0x30) != 0)
                {
                    int orphan = ((old >> 4) & 3) - 1;
                    if (drumOwner[orphan] == index)
                    {
                        drumOwner[orphan] = -1;     // cut mid-sample: its
                        drumEnd[orphan] = -1;       // marker never runs, so
                        skips &= ~(1 << orphan);    // the start cleans up
                    }
                }
            }
            // Preemption: another channel holds a toggle stream on this
            // voice. Its timer stops FIRST, and it retries; X names what to
            // stop.
            int stops = 0;
            for (int c = 0; c < channels.Length; c++)
            {
                Channel other = channels[c];
                if (c != index && (other.Elast & 0xC0) == KindToggle
                        && other.Elast != 0
                        && ((other.Elast >> 4) & 3) - 1 == voice)
                {
                    other.Elast = 0;
                    stops |= MChannel0 << c;
                }
            }
            int opcode = stops == 0 ? OpcodeStartPcm : OpcodeStartPcmPreempt;
            x[p] |= (byte) stops;       // a union: one frame may name several
            Cut(p, index, voice);       // the retrigger's own voice gets a
            channel.Tlast = count;      // fresh window, not a stuck flag
            channel.Masked = false;
            channel.Prescaler = code & 7;
            channel.Vec = KindPcm;
            channel.VecVoice = voice;
            skips |= 1 << voice;
            drumOwner[voice] = index;
            drumEnd[voice] = Looped(p, voice) ? Stuck
                    : p + Duration(p, code, count, voice);
            Emit(p, index, Action(opcode, voice, code & 7), count);
        }

        private void Retrigger(int p, int index, Channel channel, int code,
                int count, int voice)
        {
            // The same takeover the toggle arm does, with the opposite skip:
            // a retrigger stream writes R13 and never a volume register.
            if (semantics.ChannelEndsPcm)
            {
                EndOwnPcm(p, index, -1);
            }
            Cut(p, index, -1);
            channel.Tlast = count;
            channel.Masked = false;
            channel.Prescaler = code & 7;
            channel.Shape = Shape(p);
            channel.Vec = KindRetrigger;
            channel.VecVoice = voice;
            Emit(p, index, Action(OpcodeStartRetrigger, voice, code & 7), count);
        }

        /// <summary>Only an old toggle stream's voice rejoins the frame write.</summary>
        private void OpenOld(int old)
        {
            if (old != 0 && (old & 0xC0) == KindToggle)
            {
                skips &= ~(1 << (((old >> 4) & 3) - 1));
            }
        }

        /// <summary>Any action that programs or stops this channel's timer
        /// cuts a sample the channel still owes ticks to: its marker will
        /// never run, so its voice stays skipped and forced.</summary>
        private void Cut(int p, int index, int keep)
        {
            for (int v = 0; v < 3; v++)
            {
                if (v == keep)
                {
                    continue;
                }
                if (drumOwner[v] == index && drumEnd[v] > p && drumEnd[v] != Stuck)
                {
                    drumEnd[v] = Stuck;
                    if (!stuckNoted)
                    {
                        stuckNoted = true;
                        notes.Add("an effect armed over its own channel's running "
                                + "drum: voice " + (char) ('A' + v)
                                + " stays skipped, as the reference player left it");
                    }
                }
            }
        }

        /// <summary>The samples this channel still owns, ended here because
        /// the channel was told to do something else - the ChannelEndsPcm
        /// rule. Returns whether anything was taken away.</summary>
        private bool EndOwnPcm(int p, int index, int taken)
        {
            bool ended = false;
            for (int v = 0; v < 3; v++)
            {
                if (drumOwner[v] != index)
                {
                    continue;
                }
                drumOwner[v] = -1;
                drumEnd[v] = -1;
                ended = true;
                if (v != taken)
                {
                    skips &= ~(1 << v);
                    reopens.Add(new[] {p, v});
                }
            }
            return ended;
        }

        /// <summary>The voice's parameter register byte, as the player reads
        /// it: YM6's filing convention, and the byte every front end must
        /// fill whatever its own format does.</summary>
        private int Parameter(int p, int voice)
        {
            return tune.Registers[8 + voice][p] & 15;
        }

        /// <summary>The shape a retrigger stream would restart on this frame.</summary>
        private int Shape(int p)
        {
            return tune.Shapes[p] & 15;
        }

        /// <summary>A sample's length in frames, rounded so the reopen is
        /// never early: the sample plus its marker tick at the timer rate,
        /// plus a sixteenth of a frame for the arming phase.</summary>
        /// <summary>Whether the sample this frame triggers on voice loops. A
        /// looped sample runs until something else takes the voice, so the
        /// skip that covers it does not lift on the schedule Duration gives a
        /// one-shot: the frame write would reach a register the loop is still
        /// writing.</summary>
        private bool Looped(int p, int voice)
        {
            int number = tune.Registers[8 + voice][p] & 31;
            return number < tune.SampleLoops.Length
                    && tune.SampleLoops[number] != YmxFormat.SampleOneShot;
        }

        private int Duration(int p, int code, int count, int voice)
        {
            int number = tune.Registers[8 + voice][p] & 31;
            long ticks = tune.Samples[number].Length + 1L;
            long divisor = (long) Tune.Prescaler(code & 7) * count;
            long scaled = ticks * divisor * tune.FrameRate + Tune.MfpClock / 16;
            return (int) ((scaled + Tune.MfpClock - 1) / Tune.MfpClock);
        }

        private void Emit(int p, int index, int action, int count)
        {
            m[p] |= (byte) (MChannel0 << index);
            actions[index][p] = (byte) action;
            counts[index][p] = (byte) count;
        }

        private static byte[][] Copy(byte[][] streams)
        {
            byte[][] copies = new byte[streams.Length][];
            for (int c = 0; c < streams.Length; c++)
            {
                copies[c] = (byte[]) streams[c].Clone();
            }
            return copies;
        }
    }
}
