package org.ymx;

import java.util.ArrayList;
import java.util.List;

/**
 * Which frame a packed tune starts over from, and what keeping it costs.
 *
 * <p>A source gives the frame its own player went back to. The file's {@code L}
 * is the packer's answer to it, and two things stand between the two.
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
 *       envelope and the phase a voice would hear differs between passes.</li>
 * </ul>
 *
 * <p>The second is the ring. A player wraps to {@code L} by moving the read
 * position in every ring back {@code O - L} bytes, which reaches only as far
 * as the ring holds, so a file with {@code L} above 0 has to keep {@code O - L}
 * at or under {@code N} (SPEC.md 9.3). Raising {@code N} to hold the body costs
 * workspace and no file bytes, so that is what the packer does, up to the
 * format's cap.
 *
 * <p>Where neither the state rule nor the ring can be met, {@code L} is 0: the
 * tune starts over from its first frame, as every file before format version
 * 0.5 did, and the packer reports it.
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
     * {@code ringSize} it needs to reach it, and the {@code notes} saying what
     * moved and what it cost.
     */
    public record Plan(int frame, int ringSize, List<String> notes) {

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
     * that one or a larger multiple of the chunk.
     */
    public static Plan resolve(Tune tune, EffectScript.Result script, boolean loops,
                               int ringSize, int chunk) {
        List<String> notes = new ArrayList<>();
        if (!loops || tune.loopFrame() == 0) {
            return new Plan(0, ringSize, notes);
        }
        int given = tune.loopFrame();
        int budget = budget(tune.frameRate());
        int frame = -1;
        for (int candidate = given;
                candidate <= given + budget && candidate < tune.frames(); candidate++) {
            if (qualifies(tune, script, candidate)) {
                frame = candidate;
                break;
            }
        }
        if (frame < 0) {
            notes.add(String.format("The source starts over at frame %d, and no frame"
                    + " from there to %d can be entered with the timers stopped and"
                    + " the skips cleared: the tune starts over from frame 0 instead,"
                    + " so its first %d frames are heard on every pass",
                    given, Math.min(given + budget, tune.frames() - 1), given));
            return new Plan(0, ringSize, notes);
        }

        int body = tune.frames() - frame;
        int ring = ringSize;
        if (body > ring) {
            // The chunk divides it and two chunks fit, since the body is
            // already past a ring of at least two; the cap is what is left to
            // check.
            int needed = ((body + chunk - 1) / chunk) * chunk;
            if (needed > YmxFormat.MAX_RING_SIZE) {
                notes.add(String.format("The source starts over at frame %d, leaving"
                        + " %d frames to replay, and a ring of %d bytes is past the"
                        + " %d the format allows: the tune starts over from frame 0"
                        + " instead, so its first %d frames are heard on every pass",
                        given, body, needed, YmxFormat.MAX_RING_SIZE, given));
                return new Plan(0, ringSize, notes);
            }
            notes.add(String.format("Rings raised from %d to %d bytes so the %d frames"
                    + " from the loop frame fit one: %d bytes of workspace rather"
                    + " than %d, and no file bytes",
                    ring, needed, body, YmxFormat.STREAMS * needed,
                    YmxFormat.STREAMS * ring));
            ring = needed;
        }
        if (frame != given) {
            notes.add(String.format("The source starts over at frame %d, which cannot be"
                    + " entered with the timers stopped and the skips cleared: the tune"
                    + " starts over from frame %d instead, %d frame%s later",
                    given, frame, frame - given, frame - given == 1 ? "" : "s"));
        }
        return new Plan(frame, ring, notes);
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
     * opcodes, and not the RETUNE that sits among them. */
    private static boolean starts(int action) {
        int opcode = action & 0xE0;
        return opcode >= EffectScript.OPCODE_START_TOGGLE
                && opcode != EffectScript.OPCODE_RETUNE;
    }

    /**
     * Whether the envelope generator is restarted before any voice is put on
     * it, counting from frame {@code at}.
     *
     * <p>R13's write is the restart, and the frame write puts it after R8, R9
     * and R10, so a frame that both writes R13 and puts a voice on the envelope
     * ends with the phase set. A frame before that one with a voice on the
     * envelope hears a phase that depends on the frames played earlier, and
     * those differ between the first pass and the rest.
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
