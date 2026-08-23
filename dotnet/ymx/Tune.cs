using System;
using System.Collections.Generic;

namespace Ymx
{
    /// <summary>
    /// A tune as the engine has one, ported from org.ymx.Tune: the streams to
    /// play, the sources they play from, and the few numbers that say how
    /// fast it runs. This is the handover point - a front end reads its own
    /// format and stops here, at a record with no format in it.
    ///
    /// <para>Registers[r][frame] is the frame stream targeting register r,
    /// R0 to R13. Codes[c][frame] and Counts[c][frame] are timer channel c's
    /// timer stream: a code carries the kind in bits 7-6, the voice plus one
    /// in bits 5-4, the prescaler index in bits 2-0; bit 3 is a front end's
    /// trigger bit. Shapes is the envelope shape a retrigger stream would
    /// restart on each frame; Samples and SampleLoops are the PCM streams'
    /// sources and where each goes back to.</para>
    /// </summary>
    public sealed record Tune(int Frames, int FrameRate, long MasterClock,
            bool Loops, byte[][] Registers, byte[][] Codes, byte[][] Counts,
            byte[] Shapes, byte[][] Samples, int[] SampleLoops,
            EffectScript.Semantics Semantics,
            string Name, string Author, string Comment, IReadOnlyList<string> Notes)
    {
        /// <summary>The same tune under other semantics: how a caller says
        /// what no file records, without every layer carrying a flag.</summary>
        public Tune Under(EffectScript.Semantics semantics)
        {
            return this with { Semantics = semantics };
        }

        public const int KindToggle = 0x00;
        public const int KindPcm = 0x40;
        public const int KindCurve = 0x80;
        public const int KindRetrigger = 0xC0;

        /// <summary>The MFP's own clock in Hz; MasterClock is the YM2149's.</summary>
        public const int MfpClock = 2457600;

        /// <summary>How many prescaler indices a code byte's bits 2-0 name.</summary>
        public const int Prescalers = 8;

        // Index 0 is the MFP's stopped state, so a code selecting it starts
        // nothing.
        private static readonly int[] PrescalerTable = {0, 4, 10, 16, 50, 64, 100, 200};

        public static int Prescaler(int index)
        {
            return PrescalerTable[index];
        }

        /// <summary>How far a safe frame is looked for either side of a
        /// boundary that needs padding.</summary>
        private const int PadSearch = 64;

        /// <summary>Builds a tune, widening the timer streams to the format's
        /// four channels and validating what the encoder relies on.</summary>
        public static Tune Of(int frames, int frameRate, long masterClock,
                bool loops, byte[][] registers, byte[][] codes, byte[][] counts,
                byte[] shapes, byte[][] samples, int[] sampleLoops,
                EffectScript.Semantics semantics, string name, string author,
                string comment, IReadOnlyList<string> notes)
        {
            if (registers.Length != YmxFormat.RegisterStreams)
            {
                throw new ArgumentException("a tune carries "
                        + YmxFormat.RegisterStreams + " frame streams, R0 to R13,"
                        + " not " + registers.Length
                        + ": the I/O ports are not chip state");
            }
            codes = Widen(codes, frames);
            counts = Widen(counts, frames);
            if (shapes.Length != frames)
            {
                throw new ArgumentException("a tune carries one envelope shape a"
                        + " frame, not " + shapes.Length + " for " + frames);
            }
            // A code names a kind in bits 7-6 and a voice PLUS ONE in bits
            // 5-4, so zero voice bits mean the channel is idle and the whole
            // byte must be 0; a voiceless kind would compile to a negative
            // voice that floods the verb above it.
            for (int channel = 0; channel < codes.Length; channel++)
            {
                for (int frame = 0; frame < frames; frame++)
                {
                    int code = codes[channel][frame];
                    if (code != 0 && (code & 0x30) == 0)
                    {
                        throw new ArgumentException(string.Format(
                                "channel {0} carries the code ${1:X2} on frame {2},"
                                + " which names a kind but no voice; an idle"
                                + " channel's code is 0", channel, code, frame));
                    }
                }
            }
            if (sampleLoops.Length != samples.Length)
            {
                throw new ArgumentException("a tune carries one loop point per"
                        + " sample, not " + sampleLoops.Length + " for "
                        + samples.Length);
            }
            for (int sample = 0; sample < samples.Length; sample++)
            {
                int loop = sampleLoops[sample];
                if (loop != YmxFormat.SampleOneShot && loop >= samples[sample].Length)
                {
                    throw new ArgumentException("sample " + sample + " loops from "
                            + loop + ", which is past its " + samples[sample].Length
                            + " bytes; a sample that does not loop says "
                            + YmxFormat.SampleOneShot);
                }
            }
            return new Tune(frames, frameRate, masterClock, loops, registers,
                    codes, counts, shapes, samples, sampleLoops, semantics,
                    name, author, comment, new List<string>(notes));
        }

        /// <summary>Pads the tune so its length is a whole number of units,
        /// by duplicating a frame the front end says is safe to duplicate -
        /// every stream stretched at the same frame, since a frame is a
        /// column across all of them. Returns the tune itself when the
        /// length fits, or null when no safe frame exists in the window.</summary>
        public static Tune? PadToUnit(Tune tune, int unit,
                Func<int, bool> safeToDuplicate)
        {
            int endPad = (unit - tune.Frames % unit) % unit;
            if (endPad == 0)
            {
                return tune;
            }
            int atEnd = SafeFrame(safeToDuplicate, tune.Frames - 1,
                    tune.Frames - PadSearch);
            if (atEnd < 0)
            {
                return null;
            }
            var padding = new Padding(tune.Frames, atEnd, endPad,
                    tune.Frames + endPad);
            return new Tune(padding.Total, tune.FrameRate, tune.MasterClock,
                    tune.Loops, padding.Stretch(tune.Registers),
                    padding.Stretch(tune.Codes), padding.Stretch(tune.Counts),
                    padding.Stretch(tune.Shapes), tune.Samples, tune.SampleLoops,
                    tune.Semantics, tune.Name, tune.Author, tune.Comment,
                    tune.Notes);
        }

        private static int SafeFrame(Func<int, bool> safe, int from, int floor)
        {
            int stop = Math.Max(floor, from - (PadSearch - 1));
            for (int frame = from; frame >= stop; frame--)
            {
                if (safe(frame))
                {
                    return frame;
                }
            }
            return -1;
        }

        /// <summary>Which frame is duplicated and how often - one plan,
        /// applied to every stream.</summary>
        private sealed record Padding(int Frames, int AtEnd, int EndPad, int Total)
        {
            internal byte[] Stretch(byte[] values)
            {
                return Stretch(new[] {values})[0];
            }

            internal byte[][] Stretch(byte[][] streams)
            {
                byte[][] outStreams = new byte[streams.Length][];
                for (int stream = 0; stream < streams.Length; stream++)
                {
                    byte[] values = streams[stream];
                    byte[] padded = new byte[Total];
                    int at = 0;
                    for (int frame = 0; frame < Frames; frame++)
                    {
                        padded[at++] = values[frame];
                        if (frame == AtEnd)
                        {
                            for (int copy = 0; copy < EndPad; copy++)
                            {
                                padded[at++] = values[frame];
                            }
                        }
                    }
                    outStreams[stream] = padded;
                }
                return outStreams;
            }
        }

        private static byte[][] Widen(byte[][] streams, int frames)
        {
            if (streams.Length > YmxFormat.Channels)
            {
                throw new ArgumentException("a tune offers " + streams.Length
                        + " timer channels and the format carries "
                        + YmxFormat.Channels + "; widening cannot drop the rest"
                        + " quietly, so a front end with more to say has to say"
                        + " it to a format that has room");
            }
            if (streams.Length == YmxFormat.Channels)
            {
                return streams;
            }
            byte[][] widened = new byte[YmxFormat.Channels][];
            Array.Copy(streams, widened, streams.Length);
            for (int channel = streams.Length; channel < YmxFormat.Channels; channel++)
            {
                widened[channel] = new byte[frames];
            }
            return widened;
        }
    }
}
