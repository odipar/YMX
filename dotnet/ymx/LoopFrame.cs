using System;
using System.Collections.Generic;

namespace Ymx
{
    /// <summary>
    /// Which frame a packed tune starts over from, and what keeping it costs,
    /// ported from org.ymx.LoopFrame.
    ///
    /// <para>A source gives the frame its own player went back to. The file's L
    /// is that frame where two rules allow it: what the wrap leaves at that
    /// frame, and how the player reaches it again.</para>
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
    /// <para>The second is how the player reaches the frame again, in one of
    /// two ways. A wrap that moves the read position in every ring back O - L
    /// bytes needs O - L at or under N; raising N to hold the body costs
    /// workspace and no file bytes, so that is what the packer does, up to the
    /// format's cap. Past the cap the file carries two sections per stream
    /// instead - frames [0, L) in the section table's, [L, O) in the loop
    /// table's - which the player opens in turn (SPEC.md 1.4, 8), and that one
    /// costs file bytes. Where the state rule holds for no frame within the
    /// budget, and where a cut has no frame it can start at, L is 0 and the
    /// packer reports it.</para>
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
        /// the RingSize it needs to reach it, whether the streams are Cut in
        /// two at that frame, and the Notes saying what moved and what it
        /// cost.</summary>
        public sealed record Plan(int Frame, int RingSize, bool Cut,
                IReadOnlyList<string> Notes);

        /// <summary>Resolves the frame a file starts over from. loops is what
        /// the file's flag bit 0 will say; ringSize and chunk are the shape the
        /// caller asked for, and the plan's ring size is that one or a larger
        /// multiple of the chunk. unit is the size the sections are packed at,
        /// which a cut has to fall on: each of the two sections is a whole
        /// number of units.</summary>
        public static Plan Resolve(Tune tune, EffectScript.Result script, bool loops,
                int ringSize, int chunk, int unit)
        {
            var notes = new List<string>();
            if (!loops || tune.LoopFrame == 0)
            {
                return new Plan(0, ringSize, false, notes);
            }
            int given = tune.LoopFrame;
            int budget = Budget(tune.FrameRate);
            int last = Math.Min(given + budget, tune.Frames - 1);
            // The loop point the file carries is the source's, or the first
            // frame from it that can be entered. The form follows from that
            // frame: the rings carry the body where they hold it, and a cut
            // carries it otherwise. Neither moves the loop point to suit
            // itself.
            int entered = -1;
            int ringFrame = -1;
            int cutFrame = -1;
            for (int candidate = given; candidate <= last; candidate++)
            {
                if (Qualifies(tune, script, candidate))
                {
                    entered = candidate;
                    break;
                }
            }
            // Nothing from the source's loop frame on can be entered with the
            // timers stopped, the skips cleared and the envelope generator not
            // restarted. The tune starts over there anyway: a stream an earlier
            // frame left running is not running on the second pass, which is
            // audible, and the alternative is to lose the loop.
            bool forced = entered < 0;
            if (forced)
            {
                entered = given;
                notes.Add(string.Format("Frame {0}, where the tune starts over, cannot"
                        + " be entered with the timers stopped, the skips cleared and"
                        + " the envelope generator not restarted, and no frame from"
                        + " there to {1} can: the tune starts over there, and what an"
                        + " earlier frame left running is not running on the second"
                        + " pass", given, last));
            }
            // A ring of n bytes holds n frames, and the rings stay the size the
            // caller asked for.
            if (tune.Frames - entered <= ringSize)
            {
                ringFrame = entered;
            }
            else
            {
                // Each section is a whole number of units, so a cut falls on
                // one. The search runs to the end of the tune, since a cut
                // holds any body: the loop point stays as near the source's as
                // a unit allows.
                for (int candidate = entered; candidate < tune.Frames; candidate++)
                {
                    if (candidate % unit == 0
                            && (forced || Qualifies(tune, script, candidate)))
                    {
                        cutFrame = candidate;
                        break;
                    }
                }
                if (cutFrame < 0)
                {
                    notes.Add(string.Format("The tune starts over at frame {0}, and no"
                            + " frame from there on falls on a {1}-byte unit and can"
                            + " be entered with the timers stopped, the skips cleared"
                            + " and the envelope generator not restarted: the tune"
                            + " starts over from frame 0 instead, so its first {2}"
                            + " frames are heard on every pass", given, unit, given));
                    notes.Add(IdealNote(tune, entered, chunk, ringSize));
                    return new Plan(0, ringSize, false, notes);
                }
            }

            int frame = ringFrame >= 0 ? ringFrame : cutFrame;
            if (entered != given)
            {
                notes.Add(string.Format("The source starts over at frame {0}, which"
                        + " cannot be entered with the timers stopped, the skips"
                        + " cleared and the envelope generator not restarted: the tune"
                        + " starts over from frame {1} instead, {2} frame{3} later",
                        given, entered, entered - given,
                        entered - given == 1 ? "" : "s"));
            }
            if (ringFrame >= 0)
            {
                return new Plan(frame, ringSize, false, notes);
            }
            if (frame != entered)
            {
                notes.Add(string.Format("Frame {0} is not a whole number of {1}-byte"
                        + " units, which a section is: the tune starts over from frame"
                        + " {2} instead, {3} frame{4} later", entered, unit, frame,
                        frame - entered, frame - entered == 1 ? "" : "s"));
            }
            int replayed = tune.Frames - frame;
            notes.Add(string.Format("The {0} frames from frame {1} are past the {2}"
                    + " bytes the rings hold, so every stream is packed as two"
                    + " sections - one of the {3} frames before it, one of the {4}"
                    + " from it - and the file carries a loop table locating the"
                    + " second: file bytes rather than workspace", replayed, frame,
                    ringSize, frame, replayed));
            notes.Add(IdealNote(tune, frame, chunk, ringSize));
            return new Plan(frame, ringSize, true, notes);
        }

        /// <summary>
        /// The rings that hold the frames replayed from at, against the
        /// ringSize asked for, rounded up to whole chunks.
        /// </summary>
        private static string IdealNote(Tune tune, int at, int chunk, int ringSize)
        {
            int body = tune.Frames - at;
            int span = ((body + chunk - 1) / chunk) * chunk;
            if (span > YmxFormat.MaxRingSize)
            {
                return string.Format("No ring holds the {0} frames from frame {1},"
                        + " since the largest holds {2} bytes", body, at,
                        YmxFormat.MaxRingSize);
            }
            return string.Format("Rings of {0} bytes hold the {1} frames from frame"
                    + " {2}, and start it over there: {3} bytes of rings rather than"
                    + " {4}, and no file bytes", span, body, at,
                    YmxFormat.Streams * span, YmxFormat.Streams * ringSize);
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
            // START_RETRIGGER at voice 3 retunes a stream already running
            // and programs nothing from nothing (SPEC.md 3.4).
            if (opcode == EffectScript.OpcodeStartRetrigger
                    && ((action >> 3) & 3) == EffectScript.Voiceless)
            {
                return false;
            }
            return opcode >= EffectScript.OpcodeStartToggle
                    && opcode != EffectScript.OpcodeRetune;
        }

        /// <summary>
        /// Whether the envelope generator is restarted before any voice is put
        /// on it, counting from frame at. R13's write is the restart, and the
        /// frame write puts it after R8, R9 and R10, so a frame that both writes
        /// R13 and puts a voice on the envelope ends with the phase set. A frame
        /// before that one with a voice on the envelope is driven at a phase
        /// that depends on the frames played earlier, and those differ between
        /// the first pass and the rest.
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
