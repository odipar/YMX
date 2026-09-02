using System;
using System.Collections.Generic;
using St4;

namespace Ymx
{
    /// <summary>
    /// Turns a Tune into a .ymx file, ported from org.ymx.YmxEncoder:
    /// fourteen register vectors masked down to what a plain YM2149 receives,
    /// the compiled script streams, and the sample table, each vector packed
    /// as its own embedded ST4 container - or stored plain where the values
    /// are smaller than a container.
    ///
    /// <para>A tune that repeats reaches its loop frame again in one of two
    /// ways: the player moves the read position in every ring back one pass, or
    /// every stream is packed as two sections - the frames before the loop
    /// frame, then the frames from it - and the player opens the second one at
    /// the wrap. Which frame the file carries, and which of the two reaches it,
    /// is LoopFrame's answer; the ring size the plan comes back with is the one
    /// the file carries.</para>
    /// </summary>
    public static class YmxEncoder
    {
        /// <summary>What packing one stream's vector produced.</summary>
        public sealed record Stream(int Register, int Frames, int PackedSize,
                int LongestOp);

        /// <summary>The finished file plus the per-stream numbers the CLI
        /// reports; Tune is the one packed, the padded one where the
        /// length needed padding, RingSize the one the file carries, and
        /// LoopFrame the L it holds. Notes is what the loop frame moved or
        /// cost, for the CLI to report.</summary>
        public sealed record Result(byte[] File, IReadOnlyList<Stream> Streams,
                int RingSize, int Chunk, bool Loops, int Unit, int LoopFrame,
                Tune Tune, EffectScript.Result Script, IReadOnlyList<string> Notes)
        {
            /// <summary>What the end of the tune does, and the frame it goes
            /// back to - one sentence, so both CLIs report it in the same
            /// words.</summary>
            public string StartingOver()
            {
                if (!Loops)
                {
                    return "Plays once, then stops";
                }
                if (LoopFrame == 0)
                {
                    return string.Format("Plays through, then starts over from frame"
                            + " 0, replaying all of its {0} frames", Tune.Frames);
                }
                return string.Format("Plays through, then starts over from frame {0},"
                        + " replaying {1} of its {2} frames", LoopFrame,
                        Tune.Frames - LoopFrame, Tune.Frames);
            }

            public int PackedSize()
            {
                int total = 0;
                foreach (Stream stream in Streams)
                {
                    total += stream.PackedSize;
                }
                return total;
            }

            /// <summary>The longest operation in any stream; over 65535 the
            /// file is unsafe for the 68000 decoders' word counters.</summary>
            public int LongestOp()
            {
                int longest = 0;
                foreach (Stream stream in Streams)
                {
                    longest = Math.Max(longest, stream.LongestOp);
                }
                return longest;
            }
        }

        public static Result Encode(Tune tune, int ringSize, int chunk,
                bool loops, bool progress, int unit)
        {
            return Encode(tune, ringSize, chunk, loops, progress, unit,
                    YmxFormat.DefaultTimers);
        }

        /// <summary>The whole encoder, with the channel-to-timer map the T
        /// stream carries. From this line down nothing depends on which
        /// format the tune was read out of.</summary>
        public static Result Encode(Tune tune, int ringSize, int chunk,
                bool loops, bool progress, int unit, int timerMap)
        {
            return Encode(tune, ringSize, chunk, loops, progress, unit, timerMap, -1);
        }

        /// <summary>As above, with copies from the literal stream (SPEC.md
        /// Appendix A.5): copies below 0 packs none, 0 packs the search's
        /// opening passes, and above 0 searches for that many seconds a
        /// stream. A file with a copy in it sets flag bit 5 and plays only on
        /// a player built for its ring as a window.</summary>
        public static Result Encode(Tune tune, int ringSize, int chunk,
                bool loops, bool progress, int unit, int timerMap, double copies)
        {
            // The floor first, on what every tune decodes; the exact check
            // waits for the script, since a tune that leaves channels idle
            // decodes fewer streams and may use a smaller chunk.
            string problem = YmxFormat.CheckShape(ringSize, chunk, unit,
                    YmxFormat.StreamA0);
            if (problem.Length != 0)
            {
                throw new ArgumentException(problem);
            }
            if (tune.Frames % unit != 0)
            {
                throw new ArgumentException("a tune of " + tune.Frames
                        + " frames cannot be packed in " + unit + "-byte units:"
                        + " its length must be a multiple of " + unit);
            }

            // The source's own loop frame is compiled as an entry, so a
            // stream running into it starts there and LoopFrame can keep it.
            EffectScript.Result script = EffectScript.Compile(tune, timerMap,
                    loops ? tune.LoopFrame : 0);
            int channels = ChannelsUsed(script);
            problem = YmxFormat.CheckShape(ringSize, chunk, unit,
                    YmxFormat.LiveStreams(channels));
            if (problem.Length != 0)
            {
                throw new ArgumentException(problem);
            }

            // The loop frame comes before the packing rather than after it: a
            // body that needs a bigger ring gets one, and a bigger ring lets a
            // back-reference reach further, so the sections are packed against
            // the ring the file ends up carrying. A plan that cuts the streams
            // doubles the sections there are to pack.
            LoopFrame.Plan plan = LoopFrame.Resolve(tune, script, loops, ringSize,
                    chunk, unit);
            ringSize = plan.RingSize;

            // A back-reference may never reach out of the ring the player
            // decodes through, and the format's own ceiling applies above.
            int offsetLimit = Math.Min(ringSize / unit,
                    St4Format.MaxOffsetUnits(unit));
            int frames = script.Frames;
            byte[][] vectors = new byte[YmxFormat.Streams][];
            for (int register = 0; register < YmxFormat.RegisterStreams; register++)
            {
                byte[] values = Ym2149.Mask(register, tune.Registers[register]);
                if (register == 7)
                {
                    values = (byte[]) values.Clone();
                    for (int p = 0; p < frames; p++)
                    {
                        values[p] |= script.R7Force[p];
                    }
                }
                vectors[register] = values;
            }
            vectors[YmxFormat.StreamM] = script.M;
            vectors[YmxFormat.StreamX] = script.X;
            vectors[YmxFormat.StreamT] = script.Timers;
            for (int c = 0; c < YmxFormat.Channels; c++)
            {
                int acts = EffectScript.MChannel0 << c;
                byte[] action = script.Actions[c];
                vectors[YmxFormat.StreamAction(c)] =
                        Carry(action, script.M, acts, null);
                vectors[YmxFormat.StreamAction(c) + 1] =
                        Carry(script.Counts[c], script.M, acts, action);
            }

            // One section per stream. Where the plan rewinds, the container's
            // rewind point is the loop frame and the frames from it are parsed
            // on their own, so a pass after the first reads the history the
            // first one did (SPEC.md section 8).
            var streams = new List<Stream>(YmxFormat.Streams);
            var sections = new Section[YmxFormat.Streams];
            int rewindAt = plan.Rewinds ? plan.Frame : -1;
            bool copied = false;
            for (int stream = 0; stream < YmxFormat.Streams; stream++)
            {
                byte[] values = vectors[stream];
                sections[stream] = Pack(progress, values, offsetLimit, unit, rewindAt,
                        copies);
                copied |= sections[stream].Copies > 0;
                streams.Add(new Stream(stream, values.Length,
                        sections[stream].Bytes.Length, sections[stream].LongestOp));
            }
            // The twenty-five containers of one file take one loop form. A
            // stream's section holds one byte a frame, so every container's
            // rewind point is the loop frame in bytes, or none.
            LoopsAlign(sections, rewindAt, offsetLimit);

            byte[] file = Build(tune, ringSize, chunk, frames, loops, plan.Frame,
                    sections, tune.Samples,
                    channels | (copied ? YmxFormat.FlagCopies : 0));
            return new Result(file, streams, ringSize, chunk, loops, unit,
                    plan.Frame, tune, script, plan.Notes);
        }

        /// <summary>A stream byte is meaningful only on frames its master
        /// bit marks - and for a count stream, only when the action reads
        /// the count. Everywhere else the previous byte repeats, which
        /// costs nothing packed.</summary>
        internal static byte[] Carry(byte[] values, byte[] master, int bit,
                byte[]? actions)
        {
            byte[] carried = (byte[]) values.Clone();
            byte last = 0;
            for (int p = 0; p < carried.Length; p++)
            {
                bool read = (master[p] & bit) != 0;
                if (read && actions != null)
                {
                    int opcode = actions[p] & 0xE0;
                    // RESUME at voice 3 programs the timer, so its low bits
                    // are a prescaler and not the flags ResumeReload sits
                    // among: it always reads a count (SPEC.md 3.5).
                    bool resumeRetuned = opcode == EffectScript.OpcodeResume
                            && ((actions[p] >> 3) & 3) == EffectScript.Voiceless;
                    read = opcode >= EffectScript.OpcodeStartToggle
                            || resumeRetuned
                            || opcode == EffectScript.OpcodeHold
                                    && (actions[p] & EffectScript.HoldReload) != 0
                            || opcode == EffectScript.OpcodeResume
                                    && (actions[p] & EffectScript.ResumeReload) != 0;
                }
                if (read)
                {
                    last = carried[p];
                }
                else
                {
                    carried[p] = last;
                }
            }
            return carried;
        }

        /// <summary>One section as it goes into the file: packed, or the
        /// values themselves; Copies counts the blocks copied from the
        /// literal stream.</summary>
        private sealed record Section(byte[] Bytes, bool Stored, int LongestOp,
                int Copies)
        {
            internal static readonly Section Absent =
                    new Section(new byte[0], false, 0, 0);
        }

        /// <summary>Packs one section; a short section costs more as a
        /// container than as itself, and then the values are what the file
        /// gets, with the section's offset saying so. rewindAt is the frame
        /// the container carries as its rewind point, or -1: the frames from
        /// it are parsed apart from the frames before it.</summary>
        private static Section Pack(bool progress, byte[] values, int offsetLimit,
                int unit, int rewindAt, double copies)
        {
            if (values.Length == 0)
            {
                return Section.Absent;
            }
            int[] units = Units.Split(values, unit);
            St4Compressor.Result result;
            if (rewindAt < 0)
            {
                result = St4Compressor.Compress(
                        Parse(units, unit, offsetLimit, copies, progress),
                        units, unit, St4Format.MaxOp, -1, offsetLimit);
            }
            else
            {
                // A value is one byte, so the rewind point in units is the
                // frame over the unit size; the plan holds the frame to a unit
                // boundary.
                int rewindIndex = rewindAt / unit;
                int[] intro = new int[rewindIndex];
                int[] loop = new int[units.Length - rewindIndex];
                Array.Copy(units, 0, intro, 0, intro.Length);
                Array.Copy(units, rewindIndex, loop, 0, loop.Length);
                result = St4Compressor.CompressRewinding(
                        Parse(intro, unit, offsetLimit, copies, progress),
                        Parse(loop, unit, offsetLimit, copies, progress),
                        units, unit, St4Format.MaxOp, rewindIndex, offsetLimit);
            }
            byte[] container = St4Cli.Container(result);
            bool stored = values.Length < container.Length;
            return new Section(stored ? values : container, stored, result.LongestOp,
                    stored ? 0 : result.Copies);
        }

        /// <summary>The parse: the event-driven optimizer, or with copies the
        /// search that copies from the literal stream, for copies seconds. Both
        /// report as they go where progress asks: the search its opening
        /// passes, then each improvement.</summary>
        private static St4Block Parse(int[] units, int unit, int window,
                double copies, bool progress)
        {
            if (copies < 0)
            {
                return St4EventOptimizer.Optimize(units, unit, window, progress);
            }
            return St4LiteralCopySearch.Optimize(units, unit, window, St4Format.MaxOp,
                    copies, progress);
        }

        /// <summary>Every container of the file carries the one loop form the
        /// plan chose: a rewind point of rewindAt bytes, or none, and the
        /// window the ring gives. A stored section carries no header and takes
        /// its form from the file's.</summary>
        private static void LoopsAlign(Section[] sections, int rewindAt, int window)
        {
            int want = rewindAt < 0 ? St4Format.NoRewind : rewindAt;
            for (int stream = 0; stream < sections.Length; stream++)
            {
                if (sections[stream].Stored || sections[stream].Bytes.Length == 0)
                {
                    continue;
                }
                St4Format.Container container = St4Format.Read(sections[stream].Bytes);
                if (container.Rewind != want)
                {
                    throw new InvalidOperationException("stream " + stream
                            + " carries rewind " + container.Rewind
                            + " where the file's loop form is " + want);
                }
                if (container.Window != window)
                {
                    throw new InvalidOperationException("stream " + stream
                            + " carries window " + container.Window
                            + " where the ring gives " + window);
                }
            }
        }

        private static byte[] Build(Tune tune, int ringSize, int chunk, int frames,
                bool loops, int loopFrame, Section[] sections, byte[][] samples,
                int channels)
        {
            // Each section is placed on a long boundary: containers carry
            // alignment rules of their own, and a stored section takes the
            // same boundary - one placement rule.
            int total = YmxFormat.HeaderSize;
            foreach (Section section in sections)
            {
                total = Align(total) + section.Bytes.Length;
            }
            int sampleTable = samples.Length == 0 ? 0 : Align(total);
            if (samples.Length > 0)
            {
                total = sampleTable + YmxFormat.SampleEntrySize * samples.Length;
                foreach (byte[] sample in samples)
                {
                    total += sample.Length + 1;         // the end marker byte
                }
            }

            byte[] file = new byte[Align(total)];
            PutLong(file, YmxFormat.OffsetMagic, YmxFormat.Magic);
            PutWord(file, YmxFormat.OffsetVersion, YmxFormat.Version);
            // One flag per timer channel: the player claims a timer for each
            // channel named here and leaves the rest to the host.
            PutWord(file, YmxFormat.OffsetFlags,
                    (loops ? YmxFormat.FlagLoops : 0) | channels);
            PutLong(file, YmxFormat.OffsetFrames, frames);
            PutWord(file, YmxFormat.OffsetPlayerHz, tune.FrameRate);
            PutWord(file, YmxFormat.OffsetStreamCount, YmxFormat.Streams);
            PutWord(file, YmxFormat.OffsetRingSize, ringSize);
            PutWord(file, YmxFormat.OffsetChunk, chunk);
            PutLong(file, YmxFormat.OffsetMasterClock, tune.MasterClock);
            PutLong(file, YmxFormat.OffsetSampleTable, sampleTable);
            PutWord(file, YmxFormat.OffsetSampleCount, samples.Length);
            // L, the frame the tune starts over from: 0 where it plays once
            // through, and 0 where the packer could not keep the source's own.
            PutLong(file, YmxFormat.OffsetLoopFrame, loopFrame);
            // Q: this version carries no extension stream, so the mask names
            // the twenty-five section 2 defines and nothing above them.
            PutLong(file, YmxFormat.OffsetRequired, YmxFormat.RequiredBase);

            Place(file, YmxFormat.OffsetSectionTable, sections, YmxFormat.HeaderSize);

            // The sample table: entries first, then the samples, each closed
            // by the end marker the PCM tick handler stops on.
            if (samples.Length > 0)
            {
                int sample = sampleTable + YmxFormat.SampleEntrySize * samples.Length;
                for (int i = 0; i < samples.Length; i++)
                {
                    PutLong(file, sampleTable + YmxFormat.SampleEntrySize * i, sample);
                    PutWord(file, sampleTable + YmxFormat.SampleEntrySize * i + 4,
                            samples[i].Length);
                    PutWord(file, sampleTable + YmxFormat.SampleEntrySize * i + 6,
                            tune.SampleLoops[i]);
                    Array.Copy(samples[i], 0, file, sample, samples[i].Length);
                    sample += samples[i].Length;
                    file[sample++] = unchecked((byte) YmxFormat.SampleEndMark);
                }
            }
            return file;
        }

        /// <summary>Copies one table's sections into the file and fills in its
        /// offsets, and reports where the next part may begin.</summary>
        private static int Place(byte[] file, int table, Section[] sections, int at)
        {
            for (int register = 0; register < YmxFormat.Streams; register++)
            {
                byte[] bytes = sections[register].Bytes;
                if (bytes.Length == 0)
                {
                    continue;           // no such section: the offset stays 0
                }
                at = Align(at);
                PutLong(file, table + 4 * register, sections[register].Stored
                        ? at | YmxFormat.SectionStored : at);
                Array.Copy(bytes, 0, file, at, bytes.Length);
                at += bytes.Length;
            }
            return at;
        }

        /// <summary>The header's channel flags: a bit per timer channel the
        /// script ever gives something to do.</summary>
        private static int ChannelsUsed(EffectScript.Result script)
        {
            int acting = 0;
            foreach (byte b in script.M)
            {
                acting |= b;
            }
            int flags = 0;
            for (int c = 0; c < YmxFormat.Channels; c++)
            {
                if ((acting & (EffectScript.MChannel0 << c)) != 0)
                {
                    flags |= YmxFormat.FlagChannel(c);
                }
            }
            return flags;
        }

        private static int Align(int at)
        {
            return at + ((-at) & 3);
        }

        private static void PutWord(byte[] file, int at, int value)
        {
            file[at] = (byte) (value >>> 8);
            file[at + 1] = (byte) value;
        }

        private static void PutLong(byte[] file, int at, long value)
        {
            PutWord(file, at, (int) (value >>> 16));
            PutWord(file, at + 2, (int) value);
        }
    }
}
