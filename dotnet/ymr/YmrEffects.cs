using System;
using System.Collections.Generic;
using Ymx;

namespace Ymr
{
    /// <summary>
    /// Turns a parsed .YMR into a Tune, ported from org.ymr.YmrEffects: a PWM
    /// becomes a toggle stream, a Sample a PCM stream, an RTE a retrigger
    /// stream, each on one of the format's timer channels under the spec's
    /// normative binding - Timer A drives voice A, B voice B, D voice C. The
    /// code byte is built exactly as the YM front end builds it, with bit 3
    /// flipped on every sample trigger so two pops of one sample are two
    /// different codes; the Java tree's class doc carries the full story.
    /// </summary>
    public sealed class YmrEffects
    {
        /// <summary>Timer channels a .YMR fills.</summary>
        public const int Channels = YmrReader.TimerCount;

        /// <summary>The channel-to-timer map the T stream carries: the
        /// spec's normative binding, with the fourth channel no .YMR fills
        /// taking the leftover Timer C.</summary>
        public const int Timers = YmxFormat.TimerA
                | (YmxFormat.TimerB << 2)
                | (YmxFormat.TimerD << 4)
                | (YmxFormat.TimerC << 6);

        /// <summary>What the script has to be told about .YMR: a held PCM
        /// code does not retrigger, a voice playing a sample keeps its mixer
        /// bits, a channel's own commands end the sample running on it, and
        /// a rate pop under a running effect retunes live.</summary>
        public static readonly EffectScript.Semantics Semantics =
                new EffectScript.Semantics(false, false, true, false, true);

        /// <summary>Code bit 3: flipped on every sample trigger.</summary>
        public const int Trigger = 0x08;

        /// <summary>A YMX sample table entry stores its length in a word.</summary>
        private const int MaxSampleBytes = 65535;

        /// <summary>The window a looped sample stays armed for: it has no end
        /// of its own, so it stays armed until the source stops the timer.
        /// Large enough that no tune reaches it, small enough that a frame
        /// added to it stays an int.</summary>
        private const int Forever = 1 << 30;

        private const int REnvelopeShape = 13;
        private const int RVolumeA = 8;

        /// <summary>The shape an RTE restarts before the song has popped
        /// one: the spec says to assume it, and RhYMe's player primes its
        /// shadow with it.</summary>
        private const int ShapeBeforeAnyPop = 0x08;

        private const int MaxSamples = YmxFormat.MaxSamples;

        private readonly YmrReader.Song source;
        private readonly string name;
        private readonly int frames;
        private readonly byte[][] registers;
        private readonly byte[][] codes = new byte[Channels][];
        private readonly byte[][] counts = new byte[Channels][];
        private readonly Prepared[] samples;
        private readonly List<string> notes = new();

        // What each channel had to have changed, counted rather than
        // reported a frame at a time.
        private readonly int[] reservedEffect = new int[Channels];
        private readonly int[] reservedType = new int[Channels];
        private readonly int[] inertTimer = new int[Channels];
        private readonly int[] missingSample = new int[Channels];
        private readonly int[] cappedSample = new int[Channels];

        private YmrEffects(YmrReader.Song source, string name)
        {
            this.source = source;
            this.name = name;
            frames = source.FrameCount;
            registers = new byte[YmrReader.RegisterCount][];
            samples = PrepareSamples();
        }

        /// <summary>Converts a song; a .YMR carries no metadata, so the
        /// caller's file stem is the only name there is.</summary>
        public static Tune Convert(YmrReader.Song song, string name)
        {
            return new YmrEffects(song, name).Run();
        }

        private Tune Run()
        {
            for (int r = 0; r < YmrReader.RegisterCount; r++)
            {
                registers[r] = (byte[]) source.Registers[r].Clone();
            }
            for (int channel = 0; channel < Channels; channel++)
            {
                Walk(channel);
            }
            ReportChannels();

            // A header that gives no loop frame says so with -1, and a song
            // that does not start over has no frame to give: both cross as 0.
            int loopFrame = source.Loops() ? source.LoopFrame : 0;
            return Tune.Of(frames, source.FrameRate, source.YmClock,
                    source.Loops(), loopFrame, registers, codes, counts, Shapes(),
                    LevelsOf(samples), LoopsOf(samples), Semantics,
                    name, "", "", notes);
        }

        private static byte[][] LevelsOf(Prepared[] prepared)
        {
            byte[][] levels = new byte[prepared.Length][];
            for (int index = 0; index < prepared.Length; index++)
            {
                levels[index] = prepared[index].Data;
            }
            return levels;
        }

        private static int[] LoopsOf(Prepared[] prepared)
        {
            int[] loops = new int[prepared.Length];
            for (int index = 0; index < prepared.Length; index++)
            {
                loops[index] = prepared[index].LoopStart;
            }
            return loops;
        }

        /// <summary>The envelope shape a retrigger stream would restart,
        /// frame by frame: RhYMe files it where the chip does, so the shape
        /// in force is the last value the envelope-shape stream popped, with
        /// $08 assumed before the first pop.</summary>
        private byte[] Shapes()
        {
            byte[] shapes = new byte[frames];
            int shape = ShapeBeforeAnyPop;
            for (int frame = 0; frame < frames; frame++)
            {
                int written = registers[REnvelopeShape][frame];
                if (written != YmrReader.NoEnvelopeShape)
                {
                    shape = written & 15;
                }
                shapes[frame] = (byte) shape;
            }
            return shapes;
        }

        // ------------------------------------------------------ the samples

        /// <summary>The sample blocks as the file stores them, capped, with
        /// where they loop. Nothing is converted: the exporter has already
        /// reduced every sample to 4-bit levels.</summary>
        private Prepared[] PrepareSamples()
        {
            IReadOnlyList<YmrReader.Sample> blocks = source.Samples;
            int keep = Math.Min(blocks.Count, MaxSamples);
            if (blocks.Count > keep)
            {
                Note("samples " + keep + ".." + (blocks.Count - 1)
                        + " dropped: a YMX sample number is the five bits the"
                        + " script reads out of a volume register, so the format"
                        + " carries " + MaxSamples + " and this song has "
                        + blocks.Count);
            }
            var prepared = new Prepared[keep];
            for (int index = 0; index < keep; index++)
            {
                prepared[index] = Prepare(index, blocks[index]);
            }
            return prepared;
        }

        private sealed record Prepared(byte[] Data, int LoopStart);

        private Prepared Prepare(int index, YmrReader.Sample block)
        {
            byte[] data = Levels(index, block.Data);
            if (data.Length > MaxSampleBytes)
            {
                Note("sample " + index + " is " + data.Length + " bytes, past"
                        + " the " + MaxSampleBytes + " a YMX sample table"
                        + " entry's word-sized length can name: cut to fit");
                data = data[..MaxSampleBytes];
            }
            if (!block.Looped)
            {
                return new Prepared(data, YmxFormat.SampleOneShot);
            }
            int start = block.LoopStart;
            if (start >= data.Length)
            {
                Note("sample " + index + " is marked looped from " + start
                        + ", which is at or past the " + data.Length + " bytes it "
                        + (data.Length == block.Data.Length
                                ? "carries" : "keeps after the cut")
                        + ": played once instead");
                return new Prepared(data, YmxFormat.SampleOneShot);
            }
            return new Prepared(data, start);
        }

        /// <summary>The block's bytes, with anything above a 4-bit level
        /// masked away: a byte with bit 7 set would be read by the PCM tick
        /// as the end marker.</summary>
        private byte[] Levels(int index, byte[] data)
        {
            byte[] levels = (byte[]) data.Clone();
            int wrong = 0;
            for (int i = 0; i < levels.Length; i++)
            {
                if (levels[i] > 15)
                {
                    levels[i] = (byte) (levels[i] & 15);
                    wrong++;
                }
            }
            if (wrong > 0)
            {
                Note("sample " + index + " carries " + wrong + " byte"
                        + (wrong == 1 ? "" : "s") + " above the 4-bit level the"
                        + " format defines; masked, since a byte with bit 7 set"
                        + " is what ends a PCM stream");
            }
            return levels;
        }

        // ------------------------------------------------------ the streams

        /// <summary>One channel's whole timeline, replayed the way
        /// _ymr_process_tmr reconciles it: an effect pop configures or
        /// stops, a sample pop restarts on a running effect, a rate pop
        /// alone reprograms without disturbing anything.</summary>
        private void Walk(int channel)
        {
            int voice = channel;            // the binding; see Timers
            codes[channel] = new byte[frames];
            counts[channel] = new byte[frames];

            int running = YmrReader.TimerFrame.None;
            int prescaler = 0;
            int counter = 0;
            int sampleAt = 0;
            int trigger = 0;                // the code's bit 3
            int armedTo = 0;                // frame the armed PCM code goes
            int last = 0;                   // quiet on
            IReadOnlyList<YmrReader.TimerFrame> timer = source.Timer(channel);

            for (int frame = 0; frame < frames; frame++)
            {
                YmrReader.TimerFrame want = timer[frame];
                bool configure = false;
                if (want.EffectPopped)
                {
                    if (want.Effect == YmrReader.TimerFrame.None)
                    {
                        running = YmrReader.TimerFrame.None;
                    }
                    else
                    {
                        configure = true;
                    }
                }
                else if (running != YmrReader.TimerFrame.None && want.SamplePopped)
                {
                    configure = true;
                }
                bool started = false;
                if (configure)
                {
                    running = want.Effect;
                    prescaler = want.Prescaler;
                    counter = want.Counter;
                    sampleAt = want.Sample;
                    started = running == YmrReader.TimerFrame.SampleEffect;
                    if (started)
                    {
                        trigger ^= Trigger;
                    }
                }
                else if (running != YmrReader.TimerFrame.None && want.RatePopped)
                {
                    prescaler = want.Prescaler;
                    counter = want.Counter;
                }

                int code = Code(channel, voice, running, prescaler, counter,
                        sampleAt, trigger, started, frame, armedTo);
                if ((code & 0xC0) == Tune.KindPcm && code != last)
                {
                    // The script starts a sample wherever a PCM code changes,
                    // except where only the prescaler moved: this is a window
                    // only when it is a start.
                    armedTo = frame + Armed(sampleAt, prescaler, counter);
                }
                last = code;
                codes[channel][frame] = (byte) code;
                counts[channel][frame] = (byte) (code == 0 ? 0 : counter);
                Parameter(voice, code, frame, sampleAt);
            }
        }

        /// <summary>The code byte for one frame, or 0 for a channel with
        /// nothing to run: a reserved effect type, an inert rate and a
        /// sample with no block behind it all idle the channel.</summary>
        private int Code(int channel, int voice, int running, int prescaler,
                int counter, int sampleAt, int trigger, bool started, int frame,
                int armedTo)
        {
            if (running == YmrReader.TimerFrame.None)
            {
                return 0;
            }
            int kind = running switch
            {
                YmrReader.TimerFrame.Pwm => Tune.KindToggle,
                YmrReader.TimerFrame.SampleEffect => Tune.KindPcm,
                YmrReader.TimerFrame.Rte => Tune.KindRetrigger,
                _ => -1,
            };
            if (kind < 0)
            {
                reservedEffect[channel]++;
                reservedType[channel] = running;
                return 0;
            }
            if (Tune.Prescaler(prescaler & 7) == 0 || counter == 0)
            {
                inertTimer[channel]++;
                return 0;
            }
            int head = kind | ((voice + 1) << 4) | (prescaler & 7);
            if (kind != Tune.KindPcm)
            {
                return head;
            }
            if (sampleAt >= samples.Length)
            {
                if (started)
                {
                    if (sampleAt < source.Samples.Count)
                    {
                        cappedSample[channel]++;
                    }
                    else
                    {
                        missingSample[channel]++;
                    }
                }
                return 0;
            }
            // A sample that has played out leaves the channel idle rather
            // than holding a code nothing acts on.
            return started || frame < armedTo ? head | trigger : 0;
        }

        /// <summary>The parameter the script will read for this frame's code
        /// out of the voice's volume register: a PCM stream's sample number.
        /// A toggle stream's volume is already there, and a retrigger
        /// stream's shape travels in the script.</summary>
        private void Parameter(int voice, int code, int frame, int sampleAt)
        {
            if (code == 0)
            {
                return;
            }
            if ((code & 0xC0) == Tune.KindPcm)
            {
                registers[RVolumeA + voice][frame] = (byte) sampleAt;
            }
        }

        /// <summary>How many frames a sample armed with this rate stays
        /// armed for - EffectScript.Duration's arithmetic, deliberately
        /// repeated: the two have to agree on the frame the voice comes
        /// back.</summary>
        private int Armed(int sampleAt, int prescaler, int counter)
        {
            if (samples[sampleAt].LoopStart != YmxFormat.SampleOneShot)
            {
                return Forever;
            }
            long ticks = samples[sampleAt].Data.Length + 1L;
            long divisor = (long) Tune.Prescaler(prescaler & 7) * counter;
            long scaled = ticks * divisor * source.FrameRate + Tune.MfpClock / 16;
            return (int) ((scaled + Tune.MfpClock - 1) / Tune.MfpClock);
        }

        // -------------------------------------------------------- the notes

        private void Note(string what)
        {
            notes.Add(what);
        }

        /// <summary>One line per channel per thing that channel had to have
        /// changed.</summary>
        private void ReportChannels()
        {
            for (int channel = 0; channel < Channels; channel++)
            {
                string timer = "Timer " + "ABD"[channel];
                if (reservedEffect[channel] > 0)
                {
                    Note(timer + " runs effect type " + reservedType[channel]
                            + " on " + FrameCount(reservedEffect[channel])
                            + ", which version 1.3 reserves: dropped rather than"
                            + " guessed at");
                }
                if (inertTimer[channel] > 0)
                {
                    Note(timer + " is configured with a prescaler or counter of"
                            + " 0 on " + FrameCount(inertTimer[channel])
                            + ": a prescaler of 0 is the MFP's stopped state, a"
                            + " counter of 0 is 256, and neither is armed here");
                }
                if (missingSample[channel] > 0)
                {
                    Note(timer + " triggers a sample with no block behind it "
                            + Times(missingSample[channel]) + ": nothing plays");
                }
                if (cappedSample[channel] > 0)
                {
                    Note(timer + " triggers a sample past the " + MaxSamples
                            + " this format carries "
                            + Times(cappedSample[channel]) + ": nothing plays");
                }
            }
        }

        private static string FrameCount(int count)
        {
            return count + " frame" + (count == 1 ? "" : "s");
        }

        private static string Times(int count)
        {
            return count == 1 ? "once" : count + " times";
        }
    }
}
