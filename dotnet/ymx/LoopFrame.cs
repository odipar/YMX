using System;
using System.Collections.Generic;

namespace Ymx
{
    /// <summary>
    /// Which frame a packed tune starts over from, and what keeping it costs,
    /// ported from org.ymx.LoopFrame.
    ///
    /// <para>A source gives the frame its own player went back to. The file's L
    /// is the packer's answer to it, and two things stand between the two.</para>
    ///
    /// <para>The first is the state the wrap leaves behind. At the end of a pass
    /// every claimed timer is stopped, its vector parked and every skip bit
    /// cleared, so frame L is entered with nothing carried in, exactly as frame
    /// 0 is entered on the first pass. Qualifies holds where a frame reads no
    /// state an earlier frame set, on three conditions: every
    /// timer stream running there starts there, every skip bit set there is set
    /// by that frame's own M, and no voice follows the envelope generator before
    /// the first frame at or after it that writes R13.</para>
    ///
    /// <para>The second is the ring. A player wraps to L by moving the read
    /// position in every ring back O - L bytes, so a file with L above 0 has to
    /// keep O - L at or under N (SPEC.md 9.3). Raising N to hold the body costs
    /// workspace and no file bytes, so that is what the packer does, up to the
    /// format's cap. Where neither the state rule nor the ring can be met, L is
    /// 0 and the packer reports it.</para>
    /// </summary>
    public static class LoopFrame
    {
        /// <summary>How far past the frame its source gives the packer looks
        /// for one it can enter, in seconds. The advance moves the repeat that
        /// much later, which bounds it; past the bound the file carries 0 and
        /// the tune starts over from its first frame.</summary>
        public const int BudgetSeconds = 1;

        /// <summary>The budget in frames, for a tune at frameRate frames a
        /// second.</summary>
        public static int Budget(int frameRate)
        {
            return BudgetSeconds * frameRate;
        }

        /// <summary>What the packer settled on: the Frame the file carries,
        /// the RingSize it needs to reach it, and the Notes saying what moved
        /// and what it cost.</summary>
        public sealed record Plan(int Frame, int RingSize, IReadOnlyList<string> Notes);

        /// <summary>Resolves the frame a file starts over from. loops is what
        /// the file's flag bit 0 will say; ringSize and chunk are the shape the
        /// caller asked for, and the plan's ring size is that one or a larger
        /// multiple of the chunk.</summary>
        public static Plan Resolve(Tune tune, EffectScript.Result script, bool loops,
                int ringSize, int chunk)
        {
            var notes = new List<string>();
            if (!loops || tune.LoopFrame == 0)
            {
                return new Plan(0, ringSize, notes);
            }
            int given = tune.LoopFrame;
            int budget = Budget(tune.FrameRate);
            int frame = -1;
            for (int candidate = given;
                    candidate <= given + budget && candidate < tune.Frames; candidate++)
            {
                if (Qualifies(tune, script, candidate))
                {
                    frame = candidate;
                    break;
                }
            }
            if (frame < 0)
            {
                notes.Add(string.Format("The source starts over at frame {0}, and no"
                        + " frame from there to {1} can be entered with the timers"
                        + " stopped and the skips cleared: the tune starts over from"
                        + " frame 0 instead, so its first {2} frames are heard on"
                        + " every pass",
                        given, Math.Min(given + budget, tune.Frames - 1), given));
                return new Plan(0, ringSize, notes);
            }

            int body = tune.Frames - frame;
            int ring = ringSize;
            if (body > ring)
            {
                // The chunk divides it and two chunks fit, since the body is
                // already past a ring of at least two; the cap is what is left
                // to check.
                int needed = ((body + chunk - 1) / chunk) * chunk;
                if (needed > YmxFormat.MaxRingSize)
                {
                    notes.Add(string.Format("The source starts over at frame {0},"
                            + " leaving {1} frames to replay, and a ring of {2} bytes"
                            + " is past the {3} the format allows: the tune starts"
                            + " over from frame 0 instead, so its first {4} frames"
                            + " are heard on every pass",
                            given, body, needed, YmxFormat.MaxRingSize, given));
                    return new Plan(0, ringSize, notes);
                }
                notes.Add(string.Format("Rings raised from {0} to {1} bytes so the {2}"
                        + " frames from the loop frame fit one: {3} bytes of workspace"
                        + " rather than {4}, and no file bytes",
                        ring, needed, body, YmxFormat.Streams * needed,
                        YmxFormat.Streams * ring));
                ring = needed;
            }
            if (frame != given)
            {
                notes.Add(string.Format("The source starts over at frame {0}, which"
                        + " cannot be entered with the timers stopped and the skips"
                        + " cleared: the tune starts over from frame {1} instead,"
                        + " {2} frame{3} later",
                        given, frame, frame - given, frame - given == 1 ? "" : "s"));
            }
            return new Plan(frame, ring, notes);
        }

        /// <summary>
        /// Whether frame at can be entered with nothing carried in.
        ///
        /// <para>The walk up to at collects what an earlier frame leaves behind.
        /// The skip bits hold the last value M gave them, and a channel is armed
        /// from the frame a toggle or retrigger stream programs its timer until
        /// a release stops it: a release that only masks the interrupt leaves
        /// the counter running towards a RESUME, so it leaves the channel armed.
        /// A PCM stream is the one that stops its own timer, at the marker its
        /// sample ends with, and the voice it owns carries a skip bit for
        /// exactly as long as it plays - so the skip bits mark a sample in
        /// flight, and a PCM start leaves the channel unarmed.</para>
        /// </summary>
        public static bool Qualifies(Tune tune, EffectScript.Result script, int at)
        {
            bool[] armed = new bool[script.Actions.Length];
            int skips = 0;
            for (int p = 0; p < at; p++)
            {
                int master = script.M[p];
                if ((master & EffectScript.MSkips) != 0)
                {
                    skips = (master >> EffectScript.MSkipShift) & 7;
                }
                for (int c = 0; c < armed.Length; c++)
                {
                    if ((master & (EffectScript.MChannel0 << c)) == 0)
                    {
                        continue;
                    }
                    int action = script.Actions[c][p];
                    int opcode = action & 0xE0;
                    if (opcode == EffectScript.OpcodeStartToggle
                            || opcode == EffectScript.OpcodeStartRetrigger)
                    {
                        armed[c] = true;
                    }
                    else if (opcode == EffectScript.OpcodeStartPcm
                            || opcode == EffectScript.OpcodeStartPcmPreempt
                            || opcode == EffectScript.OpcodeRelease
                                    && (action & EffectScript.ReleaseMask) == 0)
                    {
                        armed[c] = false;
                    }
                }
            }

            int here = script.M[at];
            for (int c = 0; c < armed.Length; c++)
            {
                bool startsHere = (here & (EffectScript.MChannel0 << c)) != 0
                        && Starts(script.Actions[c][at]);
                if (armed[c] && !startsHere)
                {
                    return false;
                }
            }
            if (skips != 0 && (here & EffectScript.MSkips) == 0)
            {
                return false;
            }
            return EnvelopeIsSetBeforeAVoiceHearsIt(tune, at);
        }

        /// <summary>Whether this action byte programs a timer from nothing: the
        /// four start opcodes, and not the RETUNE that sits among them.</summary>
        private static bool Starts(int action)
        {
            int opcode = action & 0xE0;
            return opcode >= EffectScript.OpcodeStartToggle
                    && opcode != EffectScript.OpcodeRetune;
        }

        /// <summary>
        /// Whether the envelope generator is restarted before any voice is put
        /// on it, counting from frame at. R13's write is the restart, and the
        /// frame write puts it after R8, R9 and R10, so a frame that both writes
        /// R13 and puts a voice on the envelope ends with the phase set. A frame
        /// before that one with a voice on the envelope hears a phase that
        /// depends on the frames played earlier, and those differ between the
        /// first pass and the rest.
        /// </summary>
        private static bool EnvelopeIsSetBeforeAVoiceHearsIt(Tune tune, int at)
        {
            byte[][] registers = tune.Registers;
            for (int p = at; p < tune.Frames; p++)
            {
                if (registers[Ym2149.EnvelopeShape][p] != Ym2149.NoEnvelopeChange)
                {
                    return true;
                }
                for (int voice = 0; voice < 3; voice++)
                {
                    if ((registers[Ym2149.VolumeA + voice][p] & Ym2149.EnvelopeMode) != 0)
                    {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
