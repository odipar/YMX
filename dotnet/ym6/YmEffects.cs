using System;
using System.Collections.Generic;
using Ymx;

namespace Ym6
{
    /// <summary>
    /// The YM front end's far side, ported from org.ym6.YmEffects: a Song in,
    /// a Tune out, every dialect and every unplayable code normalized away.
    /// A YM6 frame carries up to two effect slots smeared across spare
    /// register bits; both dialects come out as the same code and count byte
    /// pairs per frame. Codes the reference player would not start are
    /// dropped and counted; a too-fast drum is rescued by resampling.
    /// </summary>
    public sealed class YmEffects
    {
        private const int KindToggle = Tune.KindToggle;
        private const int KindPcm = Tune.KindPcm;
        private const int KindCurve = Tune.KindCurve;
        private const int KindRetrigger = Tune.KindRetrigger;

        /// <summary>The fastest tick rate a real player programs.</summary>
        public const int MaxTimerHz = 25600;

        /// <summary>What the reader's frames become, and what this extraction
        /// has to say about the file: codes and counts per timer channel per
        /// frame, the converted samples, the drop counters, and one note per
        /// resampled sample.</summary>
        public sealed record Extraction(byte[][] Codes, byte[][] Counts,
                byte[][] Samples, int Inert, int TooFast, int Sinus,
                int MissingDrum, IReadOnlyList<string> Notes)
        {
            public int Dropped()
            {
                return Inert + TooFast + Sinus + MissingDrum;
            }
        }

        private readonly Ym6Reader.Song song;
        private readonly byte[][] samples;
        private readonly int[] num;     // per-sample divisor scale num/den;
        private readonly int[] den;     // 1/1 = the sample plays as dumped
        private readonly List<SortedSet<int>> divisors;
        private readonly int drumHz;
        private readonly List<string> notes = new();
        private int inert;
        private int tooFast;
        private int sinus;
        private int missingDrum;

        private YmEffects(Ym6Reader.Song song, int drumHz)
        {
            this.song = song;
            this.drumHz = drumHz;
            samples = ConvertSamples(song);
            num = new int[samples.Length];
            den = new int[samples.Length];
            divisors = new List<SortedSet<int>>();
            for (int i = 0; i < samples.Length; i++)
            {
                num[i] = 1;
                den[i] = 1;
                divisors.Add(new SortedSet<int>());
            }
        }

        /// <summary>A dump as the engine has it, at the standard ceiling.</summary>
        public static Tune BuildTune(Ym6Reader.Song song)
        {
            return BuildTune(song, Extract(song));
        }

        /// <summary>A dump as the engine has it, over an extraction already
        /// made. Only the fourteen sound registers cross, UNMASKED: the
        /// script still reads a PCM stream's sample number and a toggle
        /// stream's volume out of a voice's volume register, and the encoder
        /// masks the frame streams itself.</summary>
        public static Tune BuildTune(Ym6Reader.Song song, Extraction fx)
        {
            // A YM header always names a loop frame, and its players always
            // went round. Where the header names a frame other than 0, the
            // tune goes round to 0 instead.
            IReadOnlyList<string> notes = fx.Notes;
            if (song.LoopFrame > 0 && song.LoopFrame < song.Frames)
            {
                var noted = new List<string>(notes);
                noted.Add(string.Format("The YM header loops from frame {0} of"
                        + " {1}; the tune starts over from frame 0 instead, so"
                        + " its first {2} frames are heard on every pass",
                        song.LoopFrame, song.Frames, song.LoopFrame));
                notes = noted;
            }
            byte[][] registers = new byte[YmxFormat.RegisterStreams][];
            Array.Copy(song.Registers, registers, YmxFormat.RegisterStreams);
            return Tune.Of(song.Frames, song.PlayerHz, song.MasterClock, true,
                    registers, fx.Codes, fx.Counts, Shapes(song, fx), fx.Samples,
                    OneShot(fx.Samples.Length), EffectScript.Semantics.Ym,
                    song.Name, song.Author, song.Comment, notes);
        }

        /// <summary>A digidrum is a hit: YM has no way to say a sample
        /// loops, and the reference player's own drum tick stops at the end.</summary>
        private static int[] OneShot(int samples)
        {
            int[] loops = new int[samples];
            Array.Fill(loops, YmxFormat.SampleOneShot);
            return loops;
        }

        /// <summary>The envelope shape a retrigger stream would restart,
        /// frame by frame, as ST-Sound arrives at it: R13's write first,
        /// then each slot's buzzer nibble, the second slot winning by
        /// arriving last.</summary>
        private static byte[] Shapes(Ym6Reader.Song song, Extraction fx)
        {
            byte[] shapes = new byte[song.Frames];
            int shape = 0;                  // ST-Sound's reset leaves it here
            for (int frame = 0; frame < song.Frames; frame++)
            {
                int written = song.Registers[Ym2149.EnvelopeShape][frame];
                if (written != Ym2149.NoEnvelopeChange)
                {
                    shape = written & 15;
                }
                for (int slot = 0; slot < fx.Codes.Length; slot++)
                {
                    int code = fx.Codes[slot][frame];
                    if (code != 0 && (code & 0xC0) == KindRetrigger)
                    {
                        int voice = ((code >> 4) & 3) - 1;
                        shape = song.Registers[8 + voice][frame] & 15;
                    }
                }
                shapes[frame] = (byte) shape;
            }
            return shapes;
        }

        public static Extraction Extract(Ym6Reader.Song song)
        {
            return Extract(song, MaxTimerHz);
        }

        public static Extraction Extract(Ym6Reader.Song song, int drumHz)
        {
            var effects = new YmEffects(song, drumHz);
            effects.Downsample();
            int frames = song.Frames;
            // A YM frame carries two effect slots and no more, so only the
            // first two channels are ever written here.
            byte[][] codes = new byte[YmxFormat.Channels][];
            byte[][] counts = new byte[YmxFormat.Channels][];
            for (int c = 0; c < YmxFormat.Channels; c++)
            {
                codes[c] = new byte[frames];
                counts[c] = new byte[frames];
            }
            bool ym6 = song.Format.StartsWith("YM6");
            for (int frame = 0; frame < frames; frame++)
            {
                long slot1;
                long slot2;
                if (ym6)
                {
                    slot1 = effects.Validate(effects.Register(1, frame) & 0xF0,
                            effects.Register(6, frame) >> 5,
                            effects.Register(14, frame), frame);
                    slot2 = effects.Validate(effects.Register(3, frame) & 0xF0,
                            effects.Register(8, frame) >> 5,
                            effects.Register(15, frame), frame);
                }
                else
                {
                    // YM5: R1 bits 5-4 are a SID voice, R3 bits 5-4 a drum
                    // voice, and a YM5 drum's prescaler always sits in R8.
                    slot1 = effects.Validate(
                            KindToggle | (effects.Register(1, frame) & 0x30),
                            effects.Register(6, frame) >> 5,
                            effects.Register(14, frame), frame);
                    slot2 = effects.Validate(
                            KindPcm | (effects.Register(3, frame) & 0x30),
                            effects.Register(8, frame) >> 5,
                            effects.Register(15, frame), frame);
                }
                codes[0][frame] = (byte) (slot1 >> 8);
                counts[0][frame] = (byte) slot1;
                codes[1][frame] = (byte) (slot2 >> 8);
                counts[1][frame] = (byte) slot2;
            }
            return new Extraction(codes, counts, effects.samples, effects.inert,
                    effects.tooFast, effects.sinus, effects.missingDrum,
                    new List<string>(effects.notes));
        }

        /// <summary>Surveys every drum trigger and rescues the samples whose
        /// rate exceeds the ceiling: each is resampled to the highest
        /// MFP-representable rate under it, every trigger's divisor scaled
        /// by the same exact ratio, with the power-of-two factor as the
        /// fallback.</summary>
        private void Downsample()
        {
            bool ym6 = song.Format.StartsWith("YM6");
            for (int frame = 0; frame < song.Frames; frame++)
            {
                SurveyDrum(ym6 ? Register(1, frame) & 0xF0 : 0,
                        Register(6, frame) >> 5, Register(14, frame), frame);
                SurveyDrum(ym6 ? Register(3, frame) & 0xF0
                                : (Register(3, frame) & 0x30) != 0
                                        ? KindPcm | (Register(3, frame) & 0x30) : 0,
                        Register(8, frame) >> 5, Register(15, frame), frame);
            }
            for (int i = 0; i < samples.Length; i++)
            {
                if (divisors[i].Count == 0)
                {
                    continue;
                }
                int fastest = divisors[i].Min;
                if ((long) drumHz * fastest >= Tune.MfpClock)
                {
                    continue;           // the fastest trigger fits already
                }
                int target = CeilingDivisor();
                int g = Gcd(target, fastest);
                int n = target / g;
                int d = fastest / g;
                bool exact = true;
                foreach (int divisor in divisors[i])
                {
                    long scaled = (long) divisor * n;
                    if (scaled % d != 0 || !Representable((int) (scaled / d)))
                    {
                        exact = false;
                        break;
                    }
                }
                if (!exact)
                {                       // the old rescue: a power of two
                    n = 1;
                    d = 1;
                    while ((long) drumHz * fastest * n < Tune.MfpClock && n < 64)
                    {
                        n *= 2;
                    }
                }
                num[i] = n;
                den[i] = d;
                byte[] source = samples[i];
                int outLength = Math.Max(1, (int) ((long) source.Length * d / n));
                samples[i] = Resample(source, outLength);
                notes.Add("drum " + i + " resampled "
                        + Tune.MfpClock / fastest + " -> "
                        + (long) Tune.MfpClock * d / ((long) fastest * n)
                        + " Hz to fit " + drumHz + " Hz (-drumhz to change)");
            }
        }

        private void SurveyDrum(int code, int prescaler, int count, int frame)
        {
            if ((code & 0xC0) != KindPcm || (code & 0x30) == 0)
            {
                return;
            }
            prescaler &= 7;
            count &= 0xFF;
            if (Tune.Prescaler(prescaler) == 0 || count == 0)
            {
                return;
            }
            int number = Register(8 + ((code & 0x30) >> 4) - 1, frame) & 31;
            if (number >= samples.Length)
            {
                return;
            }
            divisors[number].Add(Tune.Prescaler(prescaler) * count);
        }

        /// <summary>The smallest MFP-representable divisor whose rate is at
        /// or under the ceiling.</summary>
        private int CeilingDivisor()
        {
            int needed = (Tune.MfpClock + drumHz - 1) / drumHz;
            int best = int.MaxValue;
            for (int p = 1; p < Tune.Prescalers; p++)
            {
                int count = (needed + Tune.Prescaler(p) - 1) / Tune.Prescaler(p);
                if (count <= 255 && Tune.Prescaler(p) * count < best)
                {
                    best = Tune.Prescaler(p) * count;
                }
            }
            return best;
        }

        private static bool Representable(int divisor)
        {
            for (int p = 1; p < Tune.Prescalers; p++)
            {
                if (divisor % Tune.Prescaler(p) == 0)
                {
                    int count = divisor / Tune.Prescaler(p);
                    if (count >= 1 && count <= 255)
                    {
                        return true;
                    }
                }
            }
            return false;
        }

        private static int Gcd(int a, int b)
        {
            while (b != 0)
            {
                int t = a % b;
                a = b;
                b = t;
            }
            return a;
        }

        /// <summary>The chip's volume curve, per the reference player.</summary>
        private static readonly int[] Curve = {62, 161, 265, 377, 580, 774, 1155,
                1575, 2260, 3088, 4570, 6233, 9330, 13187, 21220, 32767};

        /// <summary>Windowed-sinc resample of a volume-index sample: indices
        /// become amplitudes through the chip curve, a Hann-windowed sinc
        /// low-passes at the target band, and the result quantizes back to
        /// the nearest index.</summary>
        private static byte[] Resample(byte[] source, int outLength)
        {
            const int taps = 12;
            double step = (double) source.Length / outLength;
            double cutoff = Math.Min(1.0, 1.0 / step);
            byte[] resampled = new byte[outLength];
            for (int j = 0; j < outLength; j++)
            {
                double center = (j + 0.5) * step - 0.5;
                int baseAt = (int) Math.Floor(center);
                double acc = 0;
                double weight = 0;
                for (int m = baseAt - taps + 1; m <= baseAt + taps; m++)
                {
                    double t = (m - center) * cutoff;
                    double x = (m - center) / taps;
                    double w = (0.5 + 0.5 * Math.Cos(Math.PI * x))
                            * (t == 0 ? 1.0 : Math.Sin(Math.PI * t) / (Math.PI * t));
                    int at = Math.Min(source.Length - 1, Math.Max(0, m));
                    acc += w * Curve[source[at] & 15];
                    weight += w;
                }
                double amplitude = acc / weight;
                int nearest = 0;
                for (int i = 1; i < 16; i++)
                {
                    if (Math.Abs(Curve[i] - amplitude)
                            < Math.Abs(Curve[nearest] - amplitude))
                    {
                        nearest = i;
                    }
                }
                resampled[j] = (byte) nearest;
            }
            return resampled;
        }

        /// <summary>Fits a timer divisor into the MFP's prescaler table: the
        /// smallest prescaler whose count divides exactly and fits a byte,
        /// or 0 when none does.</summary>
        private static long Fit(int code, int divisor)
        {
            for (int p = 1; p < Tune.Prescalers; p++)
            {
                if (divisor % Tune.Prescaler(p) == 0)
                {
                    int count = divisor / Tune.Prescaler(p);
                    if (count >= 1 && count <= 255)
                    {
                        return ((long) ((code & 0xF0) | p) << 8) | (uint) count;
                    }
                }
            }
            return 0;
        }

        private int Register(int register, int frame)
        {
            return song.Registers[register][frame];
        }

        /// <summary>One slot's E and T bytes packed as (E &lt;&lt; 8) | T;
        /// zero when the slot is idle or the code cannot be played.</summary>
        private long Validate(int code, int prescaler, int count, int frame)
        {
            int voiceBits = code & 0x30;
            if (voiceBits == 0)
            {
                return 0;               // the slot is idle this frame
            }
            int type = code & 0xC0;
            if (type == KindCurve)
            {
                sinus++;
                return 0;
            }
            prescaler &= 7;
            count &= 0xFF;
            if (Tune.Prescaler(prescaler) == 0 || count == 0)
            {
                inert++;                // the reference player's no-op
                return 0;
            }
            if (type == KindPcm)
            {
                int voice = (voiceBits >> 4) - 1;
                int number = Register(8 + voice, frame) & 31;
                if (number >= samples.Length)
                {
                    missingDrum++;
                    return 0;
                }
                // The drum's sample may have been resampled: every trigger
                // scales its divisor by the same exact ratio.
                if (num[number] > den[number])
                {
                    long scaled = (long) Tune.Prescaler(prescaler) * count
                            * num[number] / den[number];
                    long fitted = Fit(code, (int) scaled);
                    if (fitted == 0)
                    {
                        tooFast++;
                    }
                    return fitted;
                }
            }
            int hz = Tune.MfpClock / (Tune.Prescaler(prescaler) * count);
            if (hz > (type == KindPcm ? drumHz : MaxTimerHz))
            {
                tooFast++;              // samples use their own ceiling, so
                return 0;               // -drumhz above 25600 works too
            }
            return ((long) ((code & 0xF0) | prescaler) << 8) | (uint) count;
        }

        /// <summary>The drum samples as PSG volume values 0..15, one per
        /// byte, without the end markers.</summary>
        private static byte[][] ConvertSamples(Ym6Reader.Song song)
        {
            int count = Math.Min(song.Digidrums(), YmxFormat.MaxSamples);
            byte[][] converted = new byte[count][];
            bool fourBit = (song.Attributes & Ym6Reader.Song.ADrum4Bits) != 0;
            for (int i = 0; i < count; i++)
            {
                byte[] source = song.Drums[i];
                byte[] drum = new byte[source.Length];
                for (int j = 0; j < source.Length; j++)
                {
                    drum[j] = (byte) (fourBit ? source[j] & 15 : source[j] >> 4);
                }
                converted[i] = drum;
            }
            return converted;
        }
    }
}
