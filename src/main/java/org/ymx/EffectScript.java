package org.ymx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The compiled effect script - what this format carries instead of an effect
 * interpreter in the player.
 *
 * <p>The reference player re-derived, on every frame, decisions that are pure
 * functions of the tune: is this code new or held, does the count need
 * reloading, which channel's timer must stop for whose sample. This replays
 * exactly that decision logic - the branch structure of that player's
 * effect stage, transcribed - over the whole timeline at pack time, and
 * emits eight streams of prepared actions the player executes without
 * comparing anything against remembered state.
 *
 * <p>What it compiles is a {@link Tune} - the engine's own model, which a
 * front end has already put its format's bytes into - so the names here are
 * the engine's throughout. Every code on a timer channel is a TIMER STREAM,
 * values written to one register between frames at a rate a timer sets, and
 * what this class resolves for each is exactly a stream's lifecycle: start,
 * hold, retune (a new rate, the same place in the cycle), release, resume,
 * and which stream preempts which when two contend for one register.
 * {@code doc/terminology.md} is the vocabulary. The streams it emits are
 * script data, not frame streams: their bytes never reach the chip.

 * <p>The format carries four timer channels. A YM frame starts at most two
 * effects, so a YM tune uses two and the others' streams pack to nothing;
 * they are there for sources that need them. Which MFP timer a channel
 * runs on is stream T's to say - this class emits the packer's default
 * map, which puts channels 0 and 1 on Timers A and D.
 *
 * <h2>The stream ABI (frozen: packer, player and rigs all cite this)</h2>
 *
 * <pre>
 * stream 14  M   master byte. 0 = nothing anywhere this frame.
 *                bits 0-3 = timer channel 0, 1, 2, 3 acts (read its A,
 *                           maybe P)
 *                bit 4 = apply the skip bits in 7-5
 *                bits 7-5 = one bit per voice A/B/C; a set bit SKIPS that
 *                           voice - its volume register is left out of the
 *                           frame write. State, not an edge: idempotent to
 *                           re-assert
 * stream 15  X   the operands an action byte has no room for.
 *                bits 7-4 = the envelope shape a retrigger stream restarts.
 *                           One per frame, not one per channel: the chip has
 *                           one envelope generator, so two retrigger streams
 *                           cannot hold different shapes
 *                bits 3-0 = what START_PCM_PREEMPT stops, a bit per timer
 *                           channel whose timer must stop first
 * stream 16  T   the channel-to-timer map, two bits a channel: 0 = Timer
 *                A, 1 = B, 2 = C, 3 = D. One byte covers all four, and a
 *                tune that never re-assigns repeats it.
 * stream 17  A0  channel 0's action: opcode in bits 7-5, voice in bits 4-3,
 * stream 18  P0  bits 2-0 the prescaler (program opcodes) or HOLD flags;
 * stream 19  A1  P carries the MFP timer count for any action that
 * stream 20  P1  programs or reloads. Bytes on frames where a stream is
 * stream 21  A2  not consumed are unspecified; the encoder repeats the
 * stream 22  P2  previous byte, which the event optimizer packs away.
 * stream 23  A3  The channels come last, so a tune that uses fewer of
 * stream 24  P3  them leaves streams past what the player decodes.
 * </pre>
 *
 * The opcodes:
 *
 * <pre>
 * 0 RESUME             a masked toggle stream is unmasked: flags
 *                      1 = reload the count, 2 = reload the volume
 * 1 HOLD               flags: 1 = reload the count (P), 2 = track the
 *                      toggle stream's volume, 4 = track the retrigger
 *                      stream's shape - emitted only on frames where the
 *                      value changed (the reference player
 *                      repatched every frame)
 * 2 RELEASE            stop this channel's timer; bit 0 masks instead
 * 3 START_TOGGLE       selects, volume, vector := the loud half, full
 *                      program
 * 4 RETUNE             volume, then the control nibble and the reload
 *                      written with the timer running - nothing is
 *                      stopped and the vector is NOT touched, so the
 *                      square keeps its place in the cycle. Addressed to
 *                      voice 3, the same with no volume repatched
 * 5 START_RETRIGGER    shape, vector := the retrigger tick, full program
 * 6 START_PCM          a trigger, fresh or repeated: sample table lookup,
 *                      select, vector, full program
 * 7 START_PCM_PREEMPT  as START_PCM, but first stop the timer of every
 *                      channel X names - the stop-them-first order,
 *                      straight-line
 * </pre>
 *
 * <p>A toggle stream that went away and comes back - a released note, or
 * a PCM stream that took the voice - always re-enters through
 * START_TOGGLE, which restarts the square at phase zero: the player writes
 * the voice silent, and the first tick - one timer period later - plays the
 * loud half. Free-running
 * phase belongs only to a held code (and its retunes): the ym2149-rs
 * reference model, deterministic at every gap. RETUNE is ONLY the held
 * prescaler-slide.
 *
 * <p>A toggle stream's volume and a PCM stream's sample number are read by
 * the player out of the voice's own register ring, the reference player's
 * own mechanism, which
 * costs no stream and is where both genuinely live: they belong to the voice
 * the effect took over. A retrigger stream's shape does not - it belongs to
 * the one envelope generator - so it is CARRIED, in X, resolved by whichever
 * front end knew where its format filed it. So the player is independent of
 * sources: an operand it cannot derive is one it is handed. The ring byte of a voice playing a sample is NOT sanitized: the
 * frame write skips it ({@code ymx_skips} has overwritten that one write with
 * two nops, so the byte never reaches the chip), so nothing edits the
 * ring at runtime and the reference player's whole borrow/patch/restore
 * machinery has no counterpart here. R7 arrives with the disconnection of
 * sample-playing voices baked in ({@link Result#r7force}).
 *
 * <h2>Frame alignment</h2>
 *
 * A PCM stream's end is the one genuinely asynchronous event: its sample
 * runs out at tick rate, mid-frame, and only the marker tick lands on the
 * instant. The script returns the voice to the frame write, reconnects it and
 * re-starts a suppressed toggle stream at the frame boundary AFTER the
 * computed end - never before, so a frame write can never race a live
 * sample. The computation
 * allows for the arming phase (the trigger runs a bounded slice into its
 * frame) and no more: the reference player reopened at the marker tick
 * itself, and a whole frame of grace on top of that parks the voice at the
 * sample's tail volume, disconnected, 20ms longer than that - an audible
 * click after every sample.
 *
 * <p>A source that can end a sample itself ({@link Semantics#channelEndsPcm})
 * has no such event to wait for: the end is a frame's own command, so the
 * voice rejoins the frame write on that frame and its volume comes back out
 * of that frame's register burst.
 *
 * <h2>One pass, and then another</h2>
 *
 * A script is compiled once over the tune's own frames, from the state a
 * tune starts in. A tune that repeats plays that pass again: the player
 * stops what is running and opens every skip at the boundary, which is the
 * state this compile began from, so the second pass is the first. Nothing
 * has to be simulated past the end, and no pass is compiled twice.
 */
public final class EffectScript {

    // The action ABI. Opcode 0 is the SID resume - the maxYMiser model: a
    // release only MASKS the timer interrupt (the counter keeps counting,
    // the square's half stays frozen), and coming back is an unmask plus a
    // reload of whatever changed; the phase runs on through the gap.
    public static final int OPCODE_RESUME = 0;
    public static final int RESUME_RELOAD = 1;
    public static final int RESUME_VOLUME = 2;
    public static final int OPCODE_HOLD = 1 << 5;
    public static final int OPCODE_RELEASE = 2 << 5;
    /** RELEASE flag bit 0: mask instead of stopping - a toggle stream let go.
     * A retrigger stream
     * release hard-stops its timer. */
    public static final int RELEASE_MASK = 1;
    public static final int OPCODE_START_TOGGLE = 3 << 5;
    public static final int OPCODE_RETUNE = 4 << 5;

    /** The action byte's voice field addressing no voice. There are three,
     * so 3 is free, and RETUNE addressed to it is the live rate change. */
    public static final int VOICELESS = 3;
    public static final int OPCODE_START_RETRIGGER = 5 << 5;
    public static final int OPCODE_START_PCM = 6 << 5;
    public static final int OPCODE_START_PCM_PREEMPT = 7 << 5;
    public static final int HOLD_RELOAD = 1;
    public static final int HOLD_VOLUME = 2;
    public static final int HOLD_SHAPE = 4;

    // The master byte.
    /** M's bit per timer channel, numbered as the format numbers them,
     * from zero. Four channels take bits 0 to 3, the skip flag bit 4 and its
     * mask bits 7-5; {@code M_CHANNEL_0 << c} is channel c's, and the byte is
     * full. */
    public static final int M_CHANNEL_0 = 1;
    public static final int M_CHANNEL_1 = 2;
    public static final int M_CHANNEL_2 = 4;
    public static final int M_CHANNEL_3 = 8;
    public static final int M_SKIPS = 16;
    public static final int M_SKIP_SHIFT = 5;

    public static int action(int opcode, int voice, int low) {
        return opcode | (voice << 3) | low;
    }

    /**
     * The decisions the codes cannot make for themselves, because they
     * follow from how the source format triggers, mixes, stops and retunes
     * rather than from anything in the bytes.
     *
     * <p>YM is the reason the first three exist. A YM frame carries no trigger: a
     * digidrum is a code sitting in R1 or R3, repeated for as long as the
     * dump repeats it, so the reference player re-fires the sample on every
     * one of those frames and the script has to compile the same stutter to
     * sound the same. A format whose trigger is an explicit pop from a
     * stream says start once and means it, and re-firing would chop its
     * sample into frame-long pieces. Likewise a YM voice playing a sample is
     * disconnected from the mixer for as long as it plays, because the
     * PSG volume register IS the sample's output; a source that plays its
     * samples through something else needs R7 left alone.
     *
     * <p>{@code channelEndsPcm} is the same asymmetry at the other end. A YM
     * dump has no way to say STOP: the code stops being repeated, the
     * sample runs to its marker, and until it does, a code arriving for the
     * same voice waits its turn. A format whose commands are events can end a
     * sample where it likes - and every one of those commands programs the one
     * timer the sample is ticking on, so a stop, and equally a different
     * effect configured on that channel, ends the sample on that frame rather
     * than at the marker it will now never reach. Under this flag the script
     * gives the voice up there: a release hard-stops the timer and hands the
     * volume register back to the frame write, and an arriving toggle or
     * retrigger stream takes the voice instead of retrying for the rest
     * of the sample's computed length.
     *
     * <p>{@link #YM} is the set a YM tune is packed with. A tune carries the
     * set its own source implies, because the answer is a property of the
     * format the codes were read out of and nothing later in the pipeline can
     * work it out again.
     */
    public record Semantics(boolean pcmHoldRetriggers, boolean forceMixerOnPcm,
                            boolean channelEndsPcm, boolean sidResume,
                            boolean retunesLive) {

        /** The YM dialect: a held PCM code retriggers its sample every
         * frame, a voice a sample owns is forced off the mixer, nothing ends
         * a sample but its own marker tick, and a released toggle stream
         * comes back at phase zero rather than resuming - the ym2149-rs
         * model, which {@code -sidresume} swaps for maxYMiser's. */
        public static final Semantics YM = new Semantics(true, true, false, false, false);

        /** The same, with the maxYMiser gap model: a release only masks the
         * timer interrupt and a re-arrival resumes the square where it got
         * to. No YM file records which its own player used, so this is the
         * one source semantic a listener picks rather than a format. */
        public Semantics resuming() {
            return new Semantics(pcmHoldRetriggers, forceMixerOnPcm, channelEndsPcm, true,
                    retunesLive);
        }
    }

    /**
     * The compiled script: {@code frames} frames, the script streams - M plus
     * an action and a count byte per timer channel - and the mixer bits to OR
     * into R7. {@code reopens} lists {frame, voice} for every sample end edge
     * - the differential test's skew windows - and {@code notes} what a packer
     * should report.
     */
    public record Result(int frames,
                         byte[] m, byte[][] actions, byte[][] counts, byte[] x,
                         byte[] timers,
                         byte[] r7force, List<int[]> reopens, List<String> notes) {

        /** M, X, T, then each channel's action and count, in file order. */
        public byte[][] streams() {
            byte[][] out = new byte[3 + 2 * actions.length][];
            out[0] = m;
            out[1] = x;
            out[2] = timers;
            for (int c = 0; c < actions.length; c++) {
                out[3 + 2 * c] = actions[c];
                out[4 + 2 * c] = counts[c];
            }
            return out;
        }
    }

    /** A voice never rejoins the frame write: its sample was cut mid-play and
     * the marker that would have cleared the skip will never run - the
     * reference player's stuck flag, replicated for differential exactness. */
    private static final int STUCK = Integer.MAX_VALUE;


    private static final int KIND_TOGGLE = Tune.KIND_TOGGLE;
    private static final int KIND_PCM = Tune.KIND_PCM;
    private static final int KIND_RETRIGGER = Tune.KIND_RETRIGGER;

    /** One channel's remembered state - the reference player's descriptor,
     * field for field, minus the machine addresses. */
    private static final class Channel {
        int elast;                      // CH_ELAST
        int tlast;                      // CH_TLAST
        int vec = -1;                   // what the timer vector holds: -1
        int vecVoice = -1;              // parked, else the type, plus voice
        int sel = -1;                   // the ISR's patched select voice
        int vol = -1;                   // the ISR's patched toggle volume
        int shape = -1;                 // the ISR's patched retrigger shape
        boolean masked;                 // a released toggle: interrupt masked,
                                        // the timer still counting
        int prescaler = -1;             // what the control register runs at
    }

    private final Tune tune;
    // One descriptor per timer channel the format carries. A YM frame can
    // only start two effects, so the last two stay idle for every YM source
    // and their streams pack to nothing; they are here for sources that
    // need them, and the compiler walks all of them regardless.
    private final Channel[] channels = new Channel[YmxFormat.CHANNELS];
    private final int[] drumEnd = {-1, -1, -1};   // the frame the voice's
    private final int[] drumOwner = {-1, -1, -1}; // the skip lifts; -1 = free
    private int skips;                            // bit v = skipped
    private final List<int[]> reopens = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final Semantics semantics;  // what the source format fixes

    // The emission arrays, one byte a frame.
    private final byte[] m;
    private final byte[][] actions = new byte[YmxFormat.CHANNELS][];
    private final byte[][] counts = new byte[YmxFormat.CHANNELS][];
    private final byte[] x;
    private final byte[] timers;
    private final byte[] r7;
    private final int frames;
    private boolean stuckNoted;

    private EffectScript(Tune tune) {
        this.tune = tune;
        this.semantics = tune.semantics();
        frames = tune.frames();
        m = new byte[frames];
        x = new byte[frames];
        // The channel-to-timer map. A YM tune never moves it, so the stream
        // is one value repeated and packs to nothing.
        timers = new byte[frames];
        Arrays.fill(timers, (byte) YmxFormat.DEFAULT_TIMERS);
        for (int c = 0; c < YmxFormat.CHANNELS; c++) {
            channels[c] = new Channel();
            actions[c] = new byte[frames];
            counts[c] = new byte[frames];
        }
        r7 = new byte[frames];
    }

    /**
     * Compiles the script: one pass over the tune's frames, from the state a
     * tune starts in. A tune that repeats plays that pass again.
     */
    public static Result compile(Tune tune) {
        // The default map is a YM tune's; another front end may bind its
        // channels to other timers
        // A, B and D and passes its own. See YmxEncoder.encode's shorthand.
        return compile(tune, YmxFormat.DEFAULT_TIMERS);
    }

    /**
     * As above, with the channel-to-timer map the T stream will carry: two
     * bits a channel, {@link YmxFormat#DEFAULT_TIMERS} being the one a YM
     * tune is packed with. Naming a different timer changes nothing the
     * script computes - the channels are the same, and only which hardware
     * ticks them moves.
     *
     * <p>What the source format fixes is not a parameter here: the
     * tune carries its own {@link Semantics}, because the answer follows from
     * the format the codes were read out of and travels with them. The codes
     * arrive already normalized, and the semantics say only what the codes
     * leave open.
     */
    public static Result compile(Tune tune, int timerMap) {
        EffectScript script = new EffectScript(tune);
        Arrays.fill(script.timers, (byte) timerMap);
        return script.run();
    }

    /**
     * The same, compiled so the source's own loop frame can be entered.
     *
     * <p>A wrap stops every claimed timer, parks its vector and clears the
     * skips (SPEC.md §8), so a frame that continues a stream started
     * earlier plays differently on the second pass than on the first, and
     * {@link LoopFrame} will not keep it. At {@code loopFrame} this
     * compiler forgets what it is holding, so a stream running into that
     * frame compiles as a start and the skips standing there are set by
     * that frame's own M. The frame then reads the way the wrap leaves the
     * machine, and the same bytes play on every pass including the first.
     *
     * <p>It is the source's frame and not the file's: the file's is
     * resolved from the script this returns, so it is not known yet. A
     * frame outside the tune, or 0, changes nothing.
     */
    public static Result compile(Tune tune, int timerMap, int loopFrame) {
        EffectScript script = new EffectScript(tune);
        Arrays.fill(script.timers, (byte) timerMap);
        script.entersAt = loopFrame > 0 && loopFrame < script.frames
                ? loopFrame : -1;
        return script.run();
    }

    private Result run() {
        for (int p = 0; p < frames; p++) {
            frame(p);
        }
        // Copies, so a caller holding the result cannot reach this compiler's
        // own arrays.
        return new Result(frames, m.clone(), copy(actions), copy(counts),
                x.clone(), timers.clone(), r7.clone(),
                List.copyOf(reopens), List.copyOf(notes));
    }

    // -------------------------------------------------------------------------
    // One frame: expire sample windows, then every timer channel in
    // turn, lowest first - exactly the order the reference player discovers the same
    // events in, and the order arbitration between two channels wanting one
    // voice is decided by.
    // -------------------------------------------------------------------------

    private int skipsBefore;

    /** The frame compiled as though nothing were running, or -1. */
    private int entersAt = -1;


    private void frame(int p) {
        skipsBefore = skips;
        if (p == entersAt) {
            // What the wrap leaves: no timer claimed, no vector installed,
            // no parameter patched. Compiling against that makes a running
            // stream start here rather than be held or retuned.
            for (int c = 0; c < channels.length; c++) {
                channels[c] = new Channel();
            }
            // and a skip standing here is this frame's own to set, since the
            // wrap cleared them. Where none stands there is nothing to
            // re-state, and forcing M's bit would cost a byte for nothing.
            if (skips != 0) {
                skipsBefore = ~skips;
            }
        }
        // X's high nibble is this frame's shape - the packer resolved
        // it, so the player never has to look for it. It changes rarely, which is
        // what keeps a stream carrying one value on almost every frame.
        x[p] = (byte) (shape(p) << 4);

        for (int v = 0; v < 3; v++) {
            if (drumOwner[v] >= 0 && drumEnd[v] == p) {
                drumOwner[v] = -1;      // the marker has run by now: the
                drumEnd[v] = -1;        // the skip lifts, the mixer frees
                skips &= ~(1 << v);
                reopens.add(new int[] {p, v});
            }
        }

        for (int c = 0; c < channels.length; c++) {
            channel(p, c);
        }

        if (semantics.forceMixerOnPcm()) {
            for (int v = 0; v < 3; v++) {
                if (drumOwner[v] >= 0) {    // the forced mixer, baked into R7
                    r7[p] |= (byte) (0x09 << v);
                }
            }
        }
        if (skips != skipsBefore) {
            m[p] |= M_SKIPS | (skips << M_SKIP_SHIFT);
        }
    }

    /** ymx_slot, transcribed: the labels in the comments are the reference
     * player's. */
    private void channel(int p, int index) {
        Channel channel = channels[index];
        int code = tune.codes()[index][p] & 0xFF;
        int count = tune.counts()[index][p] & 0xFF;

        if (code == channel.elast) {
            if (code == 0) {
                return;                 // the idle frame
            }
            hold(p, index, channel, code, count);
            return;
        }
        int old = channel.elast;           // move.b (a5),d4
        channel.elast = code;              // move.b d0,(a5)
        if (code == 0) {                // .released
            released(p, index, channel, old);
            return;
        }
        int voice = ((code >> 4) & 3) - 1;
        int type = code & 0xC0;
        if (retunesLive(old, code)) {
            liveRetune(p, index, channel, code, count, type, voice,
                    parameterHeld(p, channel, type, voice));
        } else if (type == KIND_TOGGLE) {         // .toggle
            toggle(p, index, channel, code, count, voice, old);
        } else if (type == KIND_PCM && retunesPcm(old, code)) {
            pcmRetune(p, index, channel, code, count, voice);
        } else if (type == KIND_PCM) { // .pcm
            pcm(p, index, channel, code, count, voice, old);
        } else {                        // the retrigger arm
            retrigger(p, index, channel, code, count, voice);
        }
    }

    /**
     * Whether a rate pop can move the prescaler with the timer left running.
     *
     * <p>A source whose own player reprograms live - one that writes the
     * control register and then the data register without stopping - renders
     * a rate change as a bend, not as a restart. The period already in
     * flight runs to its own end at the new prescaler and the reload lands
     * at the next underflow, so a square keeps both its phase and its place
     * inside the half it is in. Stopping and running the timer instead
     * truncates that period, and a slide made of many small pops turns into
     * a rasp of clipped ones.
     *
     * <p>A YM file has no such moment to be faithful to: an effect there is
     * a code sitting in a register, and how a period ends is the player's
     * business rather than the file's, so {@code retunesLive} is false and
     * every rate change goes through the ordinary stop-load-run.
     */
    private boolean retunesLive(int old, int code) {
        return tune.semantics().retunesLive()
                && old != 0 && ((old ^ code) & 0xF8) == 0;  // only bits 2-0
    }

    /**
     * Whether the effect's parameter byte changed across the pop. The
     * live retune carries no voice - the encoding room it is packed into is
     * exactly the voice field - so it cannot repatch a volume or a shape
     * on the way through. When one of those moved on the same frame the
     * ordinary retune, which does repatch, is the accurate encoding, and
     * the period in flight is truncated. A PCM stream tracks no register,
     * so its rate can always move live.
     */
    private boolean parameterHeld(int p, Channel channel, int type, int voice) {
        if (type == KIND_TOGGLE) {
            return parameter(p, voice) == channel.vol;
        }
        return type == KIND_PCM || shape(p) == channel.shape;
    }

    /**
     * A rate under a running effect, with nothing stopped. Every form here
     * leaves the vector alone and moves the timer around it, so the period
     * in flight completes (SPEC.md §3.1); they differ in which parameter
     * they repatch on the way.
     *
     * <p>Where the parameter stood still there is nothing to repatch, and
     * RETUNE at voice 3 - no such voice, and the free corner of the action
     * byte - says so. Where it moved, the form that carries it is the one
     * to emit: a voiced RETUNE for a toggle stream's volume, and
     * START_RETRIGGER at voice 3 for a retrigger stream's shape, which no
     * RETUNE can reach because the shape is in X and not a voice's register
     * (§3.2, §3.4). Whichever repatches records what it wrote, or the next
     * {@link #hold} compares against a value the chip no longer holds.
     */
    private void liveRetune(int p, int index, Channel channel, int code,
                            int count, int type, int voice, boolean held) {
        channel.tlast = count;
        channel.prescaler = code & 7;
        if (held) {
            emit(p, index, action(OPCODE_RETUNE, VOICELESS, code & 7), count);
        } else if (type == KIND_RETRIGGER) {
            channel.shape = shape(p);
            emit(p, index, action(OPCODE_START_RETRIGGER, VOICELESS, code & 7),
                    count);
        } else {
            channel.vol = parameter(p, voice);
            emit(p, index, action(OPCODE_RETUNE, voice, code & 7), count);
        }
    }

    /**
     * Whether a changed PCM code is a rate moving under a running sample
     * rather than a fresh trigger.
     *
     * <p>A source that has no way to say TRIGGER - a YM file, where an
     * effect is a code sitting in a register - can only mean one by writing
     * a code, so every change of one is a trigger and this is never true.
     * A source that signals its own ({@link Semantics#pcmHoldRetriggers}
     * false, because the trigger is an event rather than the code's
     * continued presence) says so in bit 3, and then a code that differs
     * ONLY in its prescaler is a rate change: the same kind, the same voice,
     * the same sample, and no new trigger. Reading it as a trigger restarts
     * a sample the song meant to bend, the loudest thing the
     * conversion used to get wrong.
     */
    private boolean retunesPcm(int old, int code) {
        return !tune.semantics().pcmHoldRetriggers()
                && old != 0 && (old & 0xC0) == KIND_PCM
                && ((old ^ code) & 0xF8) == 0;      // only bits 2-0 moved
    }

    /**
     * A rate under a running sample. RETUNE leaves the vector alone, so the
     * PCM tick and the pointer it has walked to both survive; the timer is
     * stopped, loaded and run again, which truncates the period in flight
     * and nothing else. The sample plays on from where it was.
     */
    private void pcmRetune(int p, int index, Channel channel, int code, int count,
                           int voice) {
        channel.tlast = count;
        channel.prescaler = code & 7;
        emit(p, index, action(OPCODE_RETUNE, voice, code & 7), count);
    }

    /** .held: a running effect's count reload and parameter tracking -
     * emitted only on frames where a value changed. */
    private void hold(int p, int index, Channel channel, int code, int count) {
        int type = code & 0xC0;
        int voice = ((code >> 4) & 3) - 1;
        // A source with no trigger but the code itself fires the sample again
        // on every frame that repeats the code, with THAT frame's number; one
        // whose trigger is an explicit pop said start once, and means it.
        if (type == KIND_PCM && semantics.pcmHoldRetriggers()) {
            pcm(p, index, channel, code, count, voice, code);
            return;
        }
        int flags = 0;
        if (count != channel.tlast) {      // cmp.b CH_TLAST(a5),d1
            channel.tlast = count;
            flags |= HOLD_RELOAD;
        }
        // A PCM stream tracks no register - what it plays comes out of the
        // sample, not off the chip - so a held one carries the reload and
        // nothing else. Only a source that leaves samples playing gets here
        // with one at all.
        if (type == KIND_TOGGLE) {             // .track: the reference player
                                               // repatched blindly
            int value = parameter(p, voice);
            if (value != channel.vol) {
                channel.vol = value;
                flags |= HOLD_VOLUME;
            }
        } else if (type != KIND_PCM) {
            int value = shape(p);
            if (value != channel.shape) {
                channel.shape = value;
                flags |= HOLD_SHAPE;
            }
        }
        if (flags != 0) {
            emit(p, index, action(OPCODE_HOLD, voice, flags), count);
        }
    }

    /** .released: a retrigger stream ending stops its timer. A toggle stream
     * is where the two gap models fork: the default (ym2149-rs) stops the
     * timer too, so the next arrival restarts at phase zero; the resume
     * model (maxYMiser) only masks the interrupt - the counter keeps
     * counting, the square's half stays frozen, and {@code masked} routes
     * the next arrival through RESUME. A PCM stream finishes itself, unless
     * the source can say stop. */
    private void released(int p, int index, Channel channel, int old) {
        int type = old & 0xC0;
        if (type == KIND_PCM) {
            if (!semantics.channelEndsPcm()) {
                return;                 // timer left running: the marker ends it
            }
            // A source that says stop is applied on the frame it says it, and
            // the whole cut lands there: RELEASE with bit 0 clear stops the
            // timer outright (ymx_release), the voice stops being a sample's
            // and it rejoins the frame write. The player applies the frame's
            // state BEFORE the register burst and the script's actions after
            // it, so the frame write the voice rejoins is THIS frame's - the
            // voice's own volume is back on the chip in the same 20 ms the
            // source placed it in, with no skew to correct for. What the
            // burst cannot cover is the sliver between it and the release: a
            // tick landing there writes one more sample byte over the volume
            // just written, and it stands until the next frame. That is a
            // fraction of a frame at a level the sample itself named, against
            // a whole frame of a sample that should not be playing.
            if (endOwnPcm(p, index, -1)) {
                emit(p, index, action(OPCODE_RELEASE, 0, 0), 0);
            }
            return;                     // nothing left to stop: the code let go
        }                               // on the frame the marker ended it
        cut(p, index, -1);
        if (type == KIND_TOGGLE) {
            openOld(old);
            if (tune.semantics().sidResume()) {
                channel.masked = true;
                emit(p, index, action(OPCODE_RELEASE, 0, RELEASE_MASK), 0);
                return;
            }
        }
        emit(p, index, action(OPCODE_RELEASE, 0, 0), 0);
    }

    private void toggle(int p, int index, Channel channel, int code, int count,
                     int voice, int old) {
        // A sample this same channel is playing is not a rival for the voice:
        // one timer runs both, so arming the square necessarily ends the
        // sample, and there is nothing to wait for. Retrying instead would
        // wait out the sample's whole computed length - the arbitration below
        // is for a sample another channel owns, the only case a YM
        // dump can produce. The voice stays skipped, because the square
        // requires it shut too, and no reopen edge is recorded.
        if (semantics.channelEndsPcm()) {
            endOwnPcm(p, index, voice);
        }
        if (drumOwner[voice] >= 0) {    // a PCM stream owns the volume register:
            channel.elast = 0;             // clr.b (a5) - retry next frame
            openOld(old);
            return;                     // nothing armed, nothing emitted
        }
        int value = parameter(p, voice);
        // The gap models fork on {@code masked}, which only a resume-mode
        // release sets (doc/experiments.md, "SID phase semantics"):
        // a re-arrival on a channel whose masked timer still runs this stream's
        // square at the same prescaler RESUMES - unmask, reload only what
        // changed, the phase ran on through the gap. A prescaler change
        // across a masked gap needs the hardware's stop/load/start
        // (RETUNE, half kept). Everything else - and everything in the
        // default model - is a full START: phase zero, one silent timer
        // period, then the loud half. The skip bit is set on every path:
        // M carries the skips.
        boolean sameSid = channel.vec == KIND_TOGGLE && channel.vecVoice == voice
                && channel.sel == voice;
        boolean resume = channel.masked && sameSid && channel.prescaler == (code & 7);
        // A gap whose prescaler moved. RESUME at voice 3 programs the timer
        // at the index it carries, where the voiced RETUNE now moves it live
        // and would defer the new rate by up to one period of the old one:
        // after a gap the counter holds an arbitrary count (SPEC.md §3.5).
        boolean resumeRetuned = channel.masked && sameSid
                && channel.prescaler != (code & 7);
        boolean retune = old != 0 && ((code ^ old) & 0xF0) == 0;
        cut(p, index, -1);
        openOld(old);
        skips |= 1 << voice;
        channel.masked = false;
        if (resume) {
            int low = 0;
            if (count != channel.tlast) {
                channel.tlast = count;
                low |= RESUME_RELOAD;
            }
            if (value != channel.vol) {
                channel.vol = value;
                low |= RESUME_VOLUME;
            }
            emit(p, index, action(OPCODE_RESUME, voice, low), count);
            return;
        }
        channel.tlast = count;
        channel.vol = value;
        channel.prescaler = code & 7;
        if (resumeRetuned) {
            emit(p, index, action(OPCODE_RESUME, VOICELESS, code & 7), count);
            return;
        }
        if (retune) {
            emit(p, index, action(OPCODE_RETUNE, voice, code & 7), count);
            return;
        }
        channel.sel = voice;
        channel.vec = KIND_TOGGLE;
        channel.vecVoice = voice;
        emit(p, index, action(OPCODE_START_TOGGLE, voice, code & 7), count);
    }

    private void pcm(int p, int index, Channel channel, int code, int count,
                      int voice, int old) {
        if (old != code) {              // the old-effect cleanup; a
            if ((old & 0xC0) == KIND_TOGGLE && old != 0) {
                openOld(old);           // retrigger short-circuits it all
            } else if ((old & 0xC0) == KIND_PCM && old != 0
                    && ((old ^ code) & 0x30) != 0) {
                int orphan = ((old >> 4) & 3) - 1;
                if (drumOwner[orphan] == index) {
                    drumOwner[orphan] = -1;   // cut mid-sample: its marker
                    drumEnd[orphan] = -1;     // never runs, so the start
                    skips &= ~(1 << orphan);  // cleans up for it
                }
            }
        }
        // Preemption: another channel holds a toggle stream on this voice.
        // Its timer stops FIRST (inside the START_PCM_PREEMPT handler), and
        // it retries. X names what to stop, because the action byte has no
        // room to and a channel has no fixed voice.
        int stops = 0;
        for (int c = 0; c < channels.length; c++) {
            Channel other = channels[c];
            if (c != index && (other.elast & 0xC0) == KIND_TOGGLE && other.elast != 0
                    && ((other.elast >> 4) & 3) - 1 == voice) {
                other.elast = 0;
                stops |= M_CHANNEL_0 << c;
            }
        }
        int opcode = stops == 0 ? OPCODE_START_PCM : OPCODE_START_PCM_PREEMPT;
        x[p] |= (byte) stops;           // a union: one frame may name more than one
        cut(p, index, voice);           // the retrigger's own voice gets a
        channel.tlast = count;             // fresh window, not a stuck flag
        channel.masked = false;
        channel.prescaler = code & 7;
        channel.vec = KIND_PCM;
        channel.vecVoice = voice;
        skips |= 1 << voice;
        drumOwner[voice] = index;
        drumEnd[voice] = looped(p, voice) ? STUCK
                : p + duration(p, code, count, voice);
        emit(p, index, action(opcode, voice, code & 7), count);
    }

    private void retrigger(int p, int index, Channel channel, int code, int count,
                      int voice) {
        // The same takeover the toggle arm does, with the opposite skip: a
        // retrigger stream writes R13 and never a volume register, so the
        // voice a sample was holding goes straight back to the frame write.
        if (semantics.channelEndsPcm()) {
            endOwnPcm(p, index, -1);
        }
        cut(p, index, -1);
        channel.tlast = count;
        channel.masked = false;
        channel.prescaler = code & 7;
        channel.shape = shape(p);
        channel.vec = KIND_RETRIGGER;
        channel.vecVoice = voice;
        emit(p, index, action(OPCODE_START_RETRIGGER, voice, code & 7), count);
    }

    /** Only an old toggle stream's voice rejoins the frame write. */
    private void openOld(int old) {
        if (old != 0 && (old & 0xC0) == KIND_TOGGLE) {
            skips &= ~(1 << (((old >> 4) & 3) - 1));
        }
    }

    /**
     * Any action that programs or stops this channel's timer cuts a sample the
     * channel still owes ticks to: its marker will never run, so its voice
     * stays skipped and forced - the reference player's stuck flag,
     * replicated and logged.
     */
    private void cut(int p, int index, int keep) {
        for (int v = 0; v < 3; v++) {
            if (v == keep) {
                continue;
            }
            if (drumOwner[v] == index && drumEnd[v] > p && drumEnd[v] != STUCK) {
                drumEnd[v] = STUCK;
                if (!stuckNoted) {
                    stuckNoted = true;
                    notes.add("an effect armed over its own channel's running "
                            + "drum: voice " + (char) ('A' + v)
                            + " stays skipped, as the reference player left it");
                }
            }
        }
    }

    /**
     * The samples this channel still owns, ended here because the channel was
     * told to do something else - the {@link Semantics#channelEndsPcm} rule.
     * A channel has one timer, the sample was ticking on it, and whatever the
     * source just popped is about to program it: the marker tick that would
     * have ended the sample can no longer run, so the end is now.
     *
     * <p>{@code taken} names the voice the arriving stream keeps for itself,
     * or -1 when none does - the shape {@link #cut} uses for the same reason.
     * Every other voice rejoins the frame write on this frame and gets an
     * entry in {@code reopens}, because its volume register is the frame
     * write's again; a voice a toggle stream is taking must stay skipped, so
     * its skip bit and its edge are that stream's to set, two lines on.
     *
     * <p>Returns whether anything was taken away, which is how a
     * release tells an early stop from a sample that had already finished: at
     * the computed end the window has expired earlier in this same frame, the
     * marker tick stopped the timer itself, and a RELEASE there would be a
     * stream byte spent stopping a stopped timer.
     */
    private boolean endOwnPcm(int p, int index, int taken) {
        boolean ended = false;
        for (int v = 0; v < 3; v++) {
            if (drumOwner[v] != index) {
                continue;
            }
            drumOwner[v] = -1;
            drumEnd[v] = -1;
            ended = true;
            if (v != taken) {
                skips &= ~(1 << v);
                reopens.add(new int[] {p, v});
            }
        }
        return ended;
    }

    /**
     * The voice's parameter register byte, as the player reads it.
     *
     * <p>R8 plus the voice is the voice's VOLUME register, and a toggle
     * stream's set level, a retrigger stream's shape and a PCM stream's
     * sample number are none of them a volume. They are there because that is
     * YM6's filing convention - the format spends the spare bits of a
     * register the effect is about to take over anyway - and they stay there
     * because the player reads them from exactly this byte at run time, out
     * of the register ring it is already holding, which saves the
     * file a stream per parameter. So the byte has to carry the value
     * whatever wrote it: a front end for a format that files its parameters
     * somewhere else has to put them here.
     * Changing where the shape comes from is a format revision, not a
     * refactor.
     */
    private int parameter(int p, int voice) {
        return tune.registers()[8 + voice][p] & 15;
    }

    /**
     * The shape a retrigger stream would restart on this frame. The front end
     * resolved where it came from, so there is one answer here and the
     * compiler needs no such knowledge; the script carries it to the player in X's
     * high nibble, and the player needs none either.
     */
    private int shape(int p) {
        return tune.shapes()[p] & 15;
    }

    /**
     * A sample's length in frames, rounded so the reopen is never early: the
     * sample plus its marker tick at the (already downsample-scaled) timer
     * rate, plus a sixteenth of a frame for the arming phase - the trigger
     * action runs a bounded slice into its VBL, so the last tick lands that
     * much later than the tick count alone says. A whole frame here instead
     * held every voice skipped 20ms past its drum: a click the reference
     * player never had.
     */
    /**
     * Whether the sample this frame triggers on {@code voice} loops. A
     * looped sample runs until something else takes the voice, so the skip
     * that covers it does not lift on the schedule {@link #duration} gives
     * a one-shot: the frame write would reach a register the loop is still
     * writing.
     */
    private boolean looped(int p, int voice) {
        int number = tune.registers()[8 + voice][p] & 31;
        return number < tune.sampleLoops().length
                && tune.sampleLoops()[number] != YmxFormat.SAMPLE_ONE_SHOT;
    }

    private int duration(int p, int code, int count, int voice) {
        int number = tune.registers()[8 + voice][p] & 31;
        long ticks = tune.samples()[number].length + 1L;
        long divisor = (long) Tune.prescaler(code & 7) * count;
        long scaled = ticks * divisor * tune.frameRate()
                + Tune.MFP_CLOCK / 16;
        return (int) ((scaled + Tune.MFP_CLOCK - 1) / Tune.MFP_CLOCK);
    }

    private void emit(int p, int index, int action, int count) {
        m[p] |= (byte) (M_CHANNEL_0 << index);
        actions[index][p] = (byte) action;
        counts[index][p] = (byte) count;
    }

    /** Every channel's stream, copied. */
    private static byte[][] copy(byte[][] streams) {
        byte[][] out = new byte[streams.length][];
        for (int c = 0; c < streams.length; c++) {
            out[c] = streams[c].clone();
        }
        return out;
    }
}
