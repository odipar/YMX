using System;
using System.Numerics;

namespace St4
{
    /// <summary>The input as an array of k-byte units, big-endian, zero-padded
    /// to a whole number of them.</summary>
    public static class Units
    {
        public static int[] Split(byte[] data, int unit)
        {
            int count = (data.Length + unit - 1) / unit;
            int[] units = new int[count];
            for (int index = 0; index < count; index++)
            {
                int value = 0;
                for (int byteIndex = 0; byteIndex < unit; byteIndex++)
                {
                    int at = index * unit + byteIndex;
                    value = (value << 8) | (at < data.Length ? data[at] : 0);
                }
                units[index] = value;
            }
            return units;
        }

        /// <summary>Writes one unit's bytes, most significant first.</summary>
        public static void Write(byte[] target, int at, int value, int unit)
        {
            for (int byteIndex = unit - 1; byteIndex >= 0; byteIndex--)
            {
                target[at + byteIndex] = (byte) value;
                value >>>= 8;
            }
        }

        /// <summary>The padded length in bytes: what the decoder produces.</summary>
        public static int PaddedLength(int length, int unit)
        {
            return (length + unit - 1) / unit * unit;
        }
    }

    /// <summary>One block of an ST4 parse chain: a literal run when Offset is
    /// 0, otherwise a match. Index is the last unit the block covers, and Bits
    /// the cost of the whole chain up to and including it.</summary>
    public sealed record St4Block(int Bits, int Index, int Offset, St4Block? Chain);

    /// <summary>ST4's twenty-byte container and its limits; doc/ and the Java
    /// tree's St4Format carry the format story.</summary>
    public static class St4Format
    {
        public const int Magic = 0x53340000;
        public const int Version = 4;

        public const int OffsetSignature = 0;
        public const int OffsetSize = 4;
        public const int OffsetLiteral = 8;
        public const int OffsetByteOffsets = 12;
        public const int OffsetWordOffsets = 16;
        public const int HeaderSize = 20;

        /// <summary>Magic, version and unit size in one long, so a decoder
        /// built for one k checks an asset with a single cmp.l.</summary>
        public static int Signature(int unit)
        {
            return Magic | (Version << 8) | unit;
        }

        /// <summary>The furthest any offset reaches, in bytes: what fits a
        /// signed word as -offset*k.</summary>
        public const int MaxOffset = 32512;

        /// <summary>The furthest a byte offset reaches, in units.</summary>
        public const int ByteOffsetLimit = 512;

        /// <summary>The longest operation the 68000 decoders count, in units.</summary>
        public const int MaxOp = 65535;

        public static bool IsUnitSize(int unit)
        {
            return unit == 1 || unit == 2 || unit == 4;
        }

        /// <summary>The reason unit cannot be used, or an empty string.</summary>
        public static string CheckUnit(int unit)
        {
            return IsUnitSize(unit) ? "" : "unit size " + unit + " is not 1, 2 or 4";
        }

        /// <summary>How far back a match may reach at this unit size, in units.</summary>
        public static int MaxOffsetUnits(int unit)
        {
            return MaxOffset / unit;
        }

        public sealed record Container(int Unit, int Size, byte[] Control,
                byte[] Literal, byte[] ByteOffsets, byte[] WordOffsets);

        /// <summary>Reads a container, checking everything a decoder would
        /// otherwise accept; the streams may carry up to three bytes of
        /// alignment padding.</summary>
        public static Container Read(byte[] file)
        {
            if (file.Length < HeaderSize)
            {
                throw new ArgumentException("too short to be an ST4 file");
            }
            int signature = LongAt(file, OffsetSignature);
            if ((signature & unchecked((int) 0xFFFF0000)) != Magic)
            {
                throw new ArgumentException("not an ST4 file");
            }
            int version = (signature >> 8) & 0xFF;
            if (version != Version)
            {
                throw new ArgumentException(
                        "ST4 format version " + version + ", not " + Version);
            }
            int unit = signature & 0xFF;
            string problem = CheckUnit(unit);
            if (problem.Length != 0)
            {
                throw new ArgumentException(problem);
            }
            int size = LongAt(file, OffsetSize);
            if (size < 0 || size % unit != 0)
            {
                throw new ArgumentException("output size " + size
                        + " is not a whole number of " + unit + "-byte units");
            }

            int[] edge = {HeaderSize, LongAt(file, OffsetLiteral),
                    LongAt(file, OffsetByteOffsets), LongAt(file, OffsetWordOffsets),
                    file.Length};
            for (int i = 1; i < edge.Length - 1; i++)
            {
                if (edge[i] % 4 != 0)
                {
                    throw new ArgumentException("stream " + "ABCD"[i]
                            + " does not start on a long boundary");
                }
                if (edge[i] < edge[i - 1] || edge[i] > file.Length)
                {
                    throw new ArgumentException("stream " + "ABCD"[i]
                            + " lies outside the file");
                }
            }
            return new Container(unit, size, file[edge[0]..edge[1]],
                    file[edge[1]..edge[2]], file[edge[2]..edge[3]],
                    file[edge[3]..edge[4]]);
        }

        private static int LongAt(byte[] file, int at)
        {
            return file[at] << 24 | file[at + 1] << 16 | file[at + 2] << 8
                    | file[at + 3];
        }
    }

    /// <summary>The progress report the optimal parsers print: an exact
    /// percentage of the parse's inner-loop steps, and a time estimate fitted
    /// to how the parse has been slowing down.</summary>
    public sealed class ProgressMeter
    {
        private const int Warmup = 5;
        private const int Baseline = 15;

        private readonly bool enabled;
        private readonly long total;
        private readonly long started;
        private readonly long[] tickNanos = new long[101];
        private long steps;
        private int shown = -1;

        public ProgressMeter(long total, bool enabled)
        {
            this.total = total;
            this.enabled = enabled;
            started = Nanos();
        }

        private static long Nanos()
        {
            return (long) (System.Diagnostics.Stopwatch.GetTimestamp()
                    * (1_000_000_000.0 / System.Diagnostics.Stopwatch.Frequency));
        }

        /// <summary>The parse's total steps: positions skip..count-1, each
        /// against its window.</summary>
        public static long TotalSteps(int count, int skip, int offsetLimit)
        {
            return StepsBefore(count, offsetLimit) - StepsBefore(skip, offsetLimit);
        }

        private static long StepsBefore(int end, int offsetLimit)
        {
            if (end <= 0)
            {
                return 0;
            }
            long ramp = Math.Min(end - 1L, offsetLimit);
            long flat = Math.Max(0L, end - 1L - offsetLimit);
            return 1 + ramp * (ramp + 1) / 2 + flat * offsetLimit;
        }

        /// <summary>One position's worth of steps; reports when the percent moves.</summary>
        public void Advance(long delta)
        {
            steps += delta;
            if (!enabled)
            {
                return;
            }
            int percent = (int) (steps * 100 / total);
            if (percent != shown)
            {
                shown = percent;
                long now = Nanos();
                tickNanos[percent] = now;
                Console.Write($"\r[{percent,3}%] {Estimate(percent, now),-12}");
                Console.Out.Flush();
            }
        }

        /// <summary>The 100% line with the elapsed time; once, at the end.</summary>
        public void Finish()
        {
            Check.That(steps == total,
                    "the step count is meant to be exact, not an estimate");
            if (enabled)
            {
                Console.Write($"\r[100%] {Duration(Nanos() - started),-12}\n");
            }
        }

        /// <summary>Time left, or "" until there is enough history to say.</summary>
        private string Estimate(int percent, long now)
        {
            int baseAt = Warmup;
            while (baseAt < percent && tickNanos[baseAt] == 0)
            {
                baseAt++;                       // a percent the loop stepped over
            }
            int mid = (baseAt + percent) / 2;
            while (mid > baseAt && tickNanos[mid] == 0)
            {
                mid--;
            }
            if (mid <= baseAt || mid >= percent || percent - baseAt < Baseline)
            {
                return "";                      // too little history to fit
            }
            double half = mid - baseAt;
            double span = percent - baseAt;
            double untilMid = tickNanos[mid] - tickNanos[baseAt];
            double untilNow = now - tickNanos[baseAt];
            double square = (untilNow * half - untilMid * span)
                    / (half * span * (span - half));
            double linear = (untilMid - square * half * half) / half;
            double whole = 100.0 - baseAt;
            double left = linear * whole + square * whole * whole - untilNow;
            if (!(left > 0))
            {
                return "";                      // NaN, or already there
            }
            return Duration((long) left) + " left";
        }

        /// <summary>Seconds, in the shortest readable form, rounded not floored.</summary>
        private static string Duration(long nanos)
        {
            long seconds = (Math.Max(0, nanos) + 500_000_000L) / 1_000_000_000L;
            return seconds < 60 ? seconds + "s"
                    : $"{seconds / 60}m {seconds % 60:00}s";
        }
    }
}
