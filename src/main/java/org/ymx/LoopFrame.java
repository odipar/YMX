package org.ymx;

import java.util.ArrayList;
import java.util.List;

/**
 * Which frame a packed tune starts over from, and what keeping it costs.
 *
 * <p>A source gives the frame its own player went back to. The file's
 * {@code L} is that frame where two rules allow it: what the wrap leaves at
 * that frame, and how the player reaches it again.
 *
 * <p>The first is the state the wrap leaves behind. At the end of a pass every
 * claimed timer is stopped, its vector parked and every skip bit cleared, so
 * frame {@code L} is entered with nothing carried in, exactly as frame 0 is
 * entered on the first pass. A frame that reads state an earlier frame set
 * plays differently on the second pass than on the first, and
 * {@link #qualifies} holds where three conditions do:
 *
 * <ul>
 *   <li>every timer stream running at that frame starts at that frame, so
 *       nothing running was started earlier;</li>
 *   <li>every skip bit set there is set by that frame's own M;</li>
 *   <li>no voice follows the envelope generator before the first frame at or
 *       after it that writes R13, since the wrap does not restart the
 *       envelope and the phase a voice on the envelope is driven at differs
 *       between passes.</li>
 * </ul>
 *
 * <p>The second is how the player reaches the frame again, in one of two
 * ways. A wrap that moves the read position in every ring back {@code O - L}
 * bytes reaches only as far as the ring holds, so it needs {@code O - L} at or
 * under {@code N}; raising {@code N} to hold the body costs workspace and no
 * file bytes, so that is what the packer does, up to the format's cap. Past
 * the cap the file carries two sections per stream instead - frames
 * {@code [0, L)} in the section table's, {@code [L, O)} in the loop table's -
 * which the player opens in turn (SPEC.md 1.4, 8). That one costs file bytes,
 * since the replayed frames are packed on their own, so the ring form is
 * taken where it reaches and the cut only past the cap.
 *
 * <p>Where the state rule holds for no frame within the budget, and where a
 * cut has no frame it can start at, {@code L} is 0: the tune starts over from
 * its first frame, as every file before format version 0.5 did, and the packer
 * reports it.
 */
public final class LoopFrame {

    private LoopFrame() {}

    /**
     * How far past the frame its source gives the packer looks for one it can
     * enter, in seconds. The advance moves the repeat that much later, which
     * bounds it; past the bound the file carries 0 and the tune starts over
     * from its first frame.
     */
    public static final int BUDGET_SECONDS = 1;

    /** The budget in frames, for a tune at {@code frameRate} frames a second. */
    public static int budget(int frameRate) {
        return BUDGET_SECONDS * frameRate;
    }

    /**
     * What the packer settled on: the {@code frame} the file carries, the
     * {@code ringSize} it needs to reach it, whether the streams are
     * {@code cut} in two at that frame, and the {@code notes} saying what moved
     * and what it cost.
     */
    public record Plan(int frame, int ringSize, boolean cut, List<String> notes) {

        public Plan {
            notes = List.copyOf(notes);
        }
    }

    /**
     * Resolves the frame a file starts over from.
     *
     * <p>{@code loops} is what the file's flag bit 0 will say: a tune that
     * plays once through has no loop frame and carries 0. {@code ringSize} and
     * {@code chunk} are the shape the caller asked for; the plan's ring size is
     * that one or a larger multiple of the chunk. {@code unit} is the size the
     * sections are packed at, which a cut has to fall on: each of the two
     * sections is a whole number of units.
     */
    public static Plan resolve(Tune tune, EffectScript.Result script, boolean loops,
                               int ringSize, int chunk, int unit) {
        List<String> notes = new ArrayList<>();
        if (!loops || tune.loopFrame() == 0) {
            return new Plan(0, ringSize, false, notes);
        }
        int given = tune.loopFrame();
        int budget = budget(tune.frameRate());
        int last = Math.min(given + budget, tune.frames() - 1);
        // The loop point the file carries is the source's, or the first frame
        // from it that can be entered. The form follows from that frame: the
        // rings carry the body where they hold it, and a cut carries it
        // otherwise. Neither moves the loop point to suit itself.
        int entered = -1;
        int ringFrame = -1;
        int cutFrame = -1;
        for (int candidate = given; candidate <= last; candidate++) {
            if (qualifies(tune, script, candidate)) {
                entered = candidate;
                break;
            }
        }
        // Nothing from the source's loop frame on can be entered with the
        // timers stopped, the skips cleared and the envelope generator not
        // restarted. The tune starts over there anyway: a stream an earlier
        // frame left running is not running on the second pass, which is
        // audible, and the alternative is to lose the loop.
        boolean forced = entered < 0;
        if (forced) {
            entered = given;
            notes.add(String.format("Frame %d, where the tune starts over, cannot be"
                    + " entered with the timers stopped, the skips cleared and the"
                    + " envelope generator not restarted, and no frame from there to"
                    + " %d can: the tune starts over there, and what an earlier frame"
                    + " left running is not running on the second pass",
                    given, last));
        }
        // A ring of n bytes holds n frames, and the rings stay the size the
        // caller asked for.
        if (tune.frames() - entered <= ringSize) {
            ringFrame = entered;
        } else {
            // Each section is a whole number of units, so a cut falls on one.
            // The search runs to the end of the tune, since a cut holds any
            // body: the loop point stays as near the source's as a unit allows.
            for (int candidate = entered; candidate < tune.frames(); candidate++) {
                if (candidate % unit == 0
                        && (forced || qualifies(tune, script, candidate))) {
                    cutFrame = candidate;
                    break;
                }
            }
            if (cutFrame < 0) {
                notes.add(String.format("The tune starts over at frame %d, and no"
                        + " frame from there on falls on a %d-byte unit and can be"
                        + " entered with the timers stopped, the skips cleared and"
                        + " the envelope generator not restarted: the tune starts"
                        + " over from frame 0 instead, so its first %d frames are"
                        + " heard on every pass", given, unit, given));
                notes.add(idealNote(tune, entered, chunk, ringSize));
                return new Plan(0, ringSize, false, notes);
            }
        }

        int frame = ringFrame >= 0 ? ringFrame : cutFrame;
        if (entered != given) {
            notes.add(String.format("The source starts over at frame %d, which cannot be"
                    + " entered with the timers stopped, the skips cleared and the"
                    + " envelope generator not restarted: the tune starts over from"
                    + " frame %d instead, %d frame%s later",
                    given, entered, entered - given, entered - given == 1 ? "" : "s"));
        }
        if (ringFrame >= 0) {
            return new Plan(frame, ringSize, false, notes);
        }
        if (frame != entered) {
            notes.add(String.format("Frame %d is not a whole number of %d-byte units,"
                    + " which a section is: the tune starts over from frame %d"
                    + " instead, %d frame%s later", entered, unit, frame,
                    frame - entered, frame - entered == 1 ? "" : "s"));
        }
        int body = tune.frames() - frame;
        notes.add(String.format("The %d frames from frame %d are past the %d bytes the"
                + " rings hold, so every stream is packed as two sections - one of"
                + " the %d frames before it, one of the %d from it - and the file"
                + " carries a loop table locating the second: file bytes rather than"
                + " workspace", body, frame, ringSize, frame, body));
        notes.add(idealNote(tune, frame, chunk, ringSize));
        return new Plan(frame, ringSize, true, notes);
    }

    /**
     * The rings that hold the frames replayed from {@code at}, against the
     * {@code ringSize} asked for, rounded up to whole {@code chunk}s.
     */
    private static String idealNote(Tune tune, int at, int chunk, int ringSize) {
        int body = tune.frames() - at;
        int span = ((body + chunk - 1) / chunk) * chunk;
        int ideal = span <= YmxFormat.MAX_RING_SIZE ? span : 0;
        return ideal > 0
                ? String.format("Rings of %d bytes hold the %d frames from frame %d,"
                        + " and start it over there: %d bytes of rings rather than"
                        + " %d, and no file bytes", ideal, body, at,
                        YmxFormat.STREAMS * ideal, YmxFormat.STREAMS * ringSize)
                : String.format("No ring holds the %d frames from frame %d, since"
                        + " the largest holds %d bytes", body, at,
                        YmxFormat.MAX_RING_SIZE);
    }

    /**
     * Whether frame {@code at} can be entered with nothing carried in.
     *
     * <p>The walk up to {@code at} collects what an earlier frame leaves
     * behind. The skip bits hold the last value M gave them, and a channel is
     * armed from the frame a toggle or retrigger stream programs its timer
     * until a release stops it: a release that only masks the interrupt leaves
     * the counter running towards a RESUME, so it leaves the channel armed. A
     * PCM stream is the one that stops its own timer, at the marker its sample
     * ends with, and the voice it owns carries a skip bit for exactly as long
     * as it plays - so the skip bits mark a sample in flight, and a PCM start
     * leaves the channel unarmed.
     */
    public static boolean qualifies(Tune tune, EffectScript.Result script, int at) {
        boolean[] armed = new boolean[script.actions().length];
        int skips = 0;
        for (int p = 0; p < at; p++) {
            int master = script.m()[p] & 0xFF;
            if ((master & EffectScript.M_SKIPS) != 0) {
                skips = (master >> EffectScript.M_SKIP_SHIFT) & 7;
            }
            for (int c = 0; c < armed.length; c++) {
                if ((master & (EffectScript.M_CHANNEL_0 << c)) == 0) {
                    continue;
                }
                int action = script.actions()[c][p] & 0xFF;
                int opcode = action & 0xE0;
                if (opcode == EffectScript.OPCODE_START_TOGGLE
                        || opcode == EffectScript.OPCODE_START_RETRIGGER) {
                    armed[c] = true;
                } else if (opcode == EffectScript.OPCODE_START_PCM
                        || opcode == EffectScript.OPCODE_START_PCM_PREEMPT
                        || opcode == EffectScript.OPCODE_RELEASE
                                && (action & EffectScript.RELEASE_MASK) == 0) {
                    armed[c] = false;
                }
            }
        }

        int master = script.m()[at] & 0xFF;
        for (int c = 0; c < armed.length; c++) {
            boolean startsHere = (master & (EffectScript.M_CHANNEL_0 << c)) != 0
                    && starts(script.actions()[c][at] & 0xFF);
            if (armed[c] && !startsHere) {
                return false;
            }
        }
        if (skips != 0 && (master & EffectScript.M_SKIPS) == 0) {
            return false;
        }
        return envelopeIsSetBeforeAVoiceHearsIt(tune, at);
    }

    /** Whether this action byte programs a timer from nothing: the four start
     * opcodes, and not the RETUNE that sits among them, nor the
     * START_RETRIGGER at voice 3 that retunes a stream already running
     * (SPEC.md §3.4). */
    private static boolean starts(int action) {
        int opcode = action & 0xE0;
        if (opcode == EffectScript.OPCODE_START_RETRIGGER
                && ((action >> 3) & 3) == EffectScript.VOICELESS) {
            return false;
        }
        return opcode >= EffectScript.OPCODE_START_TOGGLE
                && opcode != EffectScript.OPCODE_RETUNE;
    }

    /**
     * Whether the envelope generator is restarted before any voice is put on
     * it, counting from frame {@code at}.
     *
     * <p>R13's write is the restart, and the frame write puts it after R8, R9
     * and R10, so a frame that both writes R13 and puts a voice on the envelope
     * ends with the phase set. Before that frame, a voice on the envelope is
     * driven at a phase that depends on the frames played earlier, and those
     * differ between the first pass and the rest.
     */
    private static boolean envelopeIsSetBeforeAVoiceHearsIt(Tune tune, int at) {
        byte[][] registers = tune.registers();
        for (int p = at; p < tune.frames(); p++) {
            if ((registers[Ym2149.ENVELOPE_SHAPE][p] & 0xFF)
                    != Ym2149.NO_ENVELOPE_CHANGE) {
                return true;
            }
            for (int voice = 0; voice < 3; voice++) {
                if ((registers[Ym2149.VOLUME_A + voice][p] & Ym2149.ENVELOPE_MODE) != 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
