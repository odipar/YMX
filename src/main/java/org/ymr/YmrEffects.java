package org.ymr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.ymx.EffectScript;
import org.ymx.Tune;
import org.ymx.YmxFormat;

/**
 * Turns a parsed .YMR into a {@link Tune}: the engine's own model, with a
 * frame stream per register and a timer stream per channel.
 *
 * <p>This is where the vocabulary changes, and it is the only place in
 * {@code org.ymr} that says a word of the engine's language. On the way in the
 * names are .YMR's, because the values are its: streams, pops, Timer A/B/D,
 * PWM, RTE, Sample. On the way out they are the engine's - a PWM becomes a
 * TOGGLE STREAM, a Sample a PCM STREAM, an RTE a RETRIGGER STREAM, and each
 * runs on one of the format's four TIMER CHANNELS. {@code doc/terminology.md}
 * maps the two. {@link org.ym6.YmEffects} is the PEER that does the same job
 * for a YM dump - it hands over the same {@link Tune} and neither front end
 * is downstream of the other - and reading the two side by side is the
 * quickest way to see which decisions belong to a format and which belong to
 * the engine.
 *
 * <h2>The binding</h2>
 *
 * <p>A .YMR names its timers rather than its voices, and the spec's Timer
 * Effects table fixes which voice each one drives: Timer A drives voice A,
 * Timer B voice B, and Timer D - not C, which the format leaves to the host's
 * 200 Hz clock - drives voice C. That binding is normative, so it is a
 * constant here rather than an option: channel 0 takes Timer A, channel 1
 * Timer B, channel 2 Timer D, and the fourth channel, which no .YMR fills,
 * takes the leftover Timer C. {@link #TIMERS} is that map in the T
 * stream's own two-bits-a-channel encoding, ready for the encoder.
 *
 * <h2>The code byte</h2>
 *
 * <p>Each channel's per-frame code byte is built exactly as
 * {@link org.ym6.YmEffects} builds it - kind in bits 7-6, voice plus one in
 * bits 5-4, MFP prescaler index in bits 2-0, and zero for an idle channel -
 * with the count byte carrying the MFP timer's data register. That leaves bit
 * 3 unspoken for, and a .YMR needs it.
 *
 * <p>In a YM dump an effect is a code sitting in a register, and a digidrum
 * fires again on every frame that repeats it. In a .YMR an effect is state and
 * a trigger is an event: popping {@code timer_*_sample} restarts the sample
 * whether or not the index changed, and a sample nothing pops keeps playing.
 * The two halves of that are handled in two places. {@link EffectScript} is
 * told, through {@link EffectScript.Semantics}, that a held PCM code does not
 * retrigger - which stops a sustained sample being chopped into
 * frame-long pieces. And because the script acts when a code byte CHANGES, an
 * explicit re-trigger has to change one: bit 3 flips on every trigger, so two
 * pops of the same sample at the same rate produce two different codes and the
 * script starts the sample twice. Nothing else reads that bit - the script
 * takes the kind from bits 7-6, the voice from bits 5-4 and the prescaler from
 * {@code code & 7}, compares whole codes for equality, and tests {@code code !=
 * 0} for idleness, which a trigger bit alone can never fake because it is only
 * ever set on a code that already names a kind and a voice.
 *
 * <p>The end of a sample is an event too. A .YMR can say stop - an effect pop of
 * 0 - and can hand the channel to a different effect while a sample is still
 * playing, and both mean the sample ends on that frame, because both program the
 * one timer it is ticking on. The script is told that as well, through the same
 * semantics, and the code byte carries it the plain way: a stop is the idle code
 * 0 and a new effect is a new code, so no bit has to be invented for either.
 *
 * <h2>What the script still reads off the chip</h2>
 *
 * <p>Two of a stream's parameters are read out of the voice's own volume
 * register rather than out of a stream, because that is where the player
 * finds them at run time and where they belong: they are the voice's, and
 * the effect took the voice over. A front end has to present them there, and
 * this one does:
 *
 * <ul>
 *   <li>A PWM needs nothing written. RhYMe's PWM handler toggles the voice
 *       between the shadow volume and zero at the timer's rate, and the shadow
 *       volume is what {@code volume_a}/{@code b}/{@code c} already popped into
 *       that register - so the toggle stream's volume is already in place.</li>
 *   <li>A Sample's index is written over the volume byte on every frame the
 *       PCM code is armed. That is invisible: the script skips the voice for
 *       the whole time a sample owns it, and {@code ymx_skips} drops a write
 *       by overwriting it with two {@code nop}s, so the byte never reaches
 *       the chip. It is also load-bearing - the script recomputes a sample's
 *       length wherever its code changes, so the index has to be
 *       readable on every one of those frames and not only on the first.</li>
 * </ul>
 *
 * <p>An RTE's shape is the third parameter and is not one of them, because it
 * is not a voice's: there is one envelope generator and any number of voices
 * may follow it. It is carried in the script instead - see {@link #shapes()}
 * - so nothing is written over a volume register, and the volume byte a .YMR
 * popped reaches the chip exactly as the dump had it.
 *
 *
 * <h2>What a .YMR can express and a .ymx cannot</h2>
 *
 * <p>One thing is converted rather than carried, and it leaves a note. The
 * format allows 65535 samples and a YMX sample number is five bits, so
 * everything past {@link YmxFormat#MAX_SAMPLES} is dropped and a trigger of a
 * dropped one is reported.
 *
 * <p>Two more used to be losses and are now carried. A looped sample rides
 * with the point it comes back to and the player does the coming back, and a
 * rate pop that moves a running effect's prescaler compiles to the live
 * retune - the fifth {@link EffectScript.Semantics} flag - which reprograms
 * the timer without stopping it.
 * RhYMe reprograms a RUNNING timer: control register, then data register, the
 * timer never stopped, so the effect keeps its place and only its rate moves.
 * Half of that a YMX verb does do. A .YMR rate entry is a prescaler and a
 * counter, only the prescaler is in the code byte, and a pop that moves the
 * counter alone therefore leaves the code where it was: it compiles to a HOLD
 * carrying the reload flag, and {@code ymx_hold} writes the new count to a
 * timer it never stops. That is RhYMe's live reload exactly, and it is what a
 * pitch slide is made of, so the ordinary case costs nothing.
 *
 * <p>What no verb can say is the other half. A pop that moves the PRESCALER
 * changes the code byte, so it compiles to a program verb, and every verb that
 * carries a rate goes through {@code ymx_program}, which stops the timer, loads
 * the count and runs it again, so the period in flight is truncated whichever
 * verb is used - and that is why a prescaler change under a running RTE
 * compiles to a plain START_RETRIGGER and no gentler verb is invented for it.
 * Against START_RETRIGGER, a RETUNE would save one vector write and one patch
 * of a shape the arm has to get right anyway; it would cost the same stream
 * bytes, truncate the same period, and sound the same. The gap that is
 * actually audible - a rate that moves without disturbing the effect under it -
 * is one no verb in the format can close, so it is named here rather than
 * papered over.
 *
 * <p>One thing is knowingly not handled. A frame that pops the effect stream
 * with the type already running, at the same rate and the same sample, is a
 * re-configure: RhYMe restarts the timer for it, which puts a PWM's phase back
 * to zero and re-primes an RTE's shape. Here it produces a code byte identical
 * to the last one, and the script acts on a code that CHANGED, so nothing is
 * emitted. RhYMe's own exporter cannot write that frame - its stream set
 * suppresses an effect pop that repeats the type - so only a .YMR from some
 * other writer reaches it, and what such a file loses is one truncated timer
 * period and, for a PWM, a phase discontinuity of one tick in a frame twenty
 * milliseconds long. Making the code differ would mean flipping the trigger bit
 * on every configure and not only on a sample's, which would compile a file
 * that pops redundantly every frame into a full restart every frame - real
 * stream bytes and a hard phase reset at frame rate, bought for a difference at
 * the edge of hearing. The trigger bit stays a sample's alone.
 */
public final class YmrEffects {

    /** Timer channels a .YMR fills: one per timer the format gives effects. */
    public static final int CHANNELS = YmrReader.TIMER_COUNT;

    /**
     * The channel-to-timer map the T stream carries, two bits a channel.
     *
     * <p>It is the spec's normative binding and nothing else: channel 0 on
     * Timer A, 1 on B, 2 on D. The fourth channel no .YMR fills takes Timer C,
     * which keeps the map a permutation and costs nothing, since the header
     * never flags a channel the tune leaves idle and the player claims no
     * timer for it.
     */
    public static final int TIMERS = YmxFormat.TIMER_A
            | (YmxFormat.TIMER_B << 2)
            | (YmxFormat.TIMER_D << 4)
            | (YmxFormat.TIMER_C << 6);

    /**
     * What the script has to be told about .YMR, in its own words.
     *
     * <p>A held PCM code does not retrigger, because a .YMR's trigger is a pop
     * and not the code's continued presence. A voice playing a sample keeps its
     * mixer bits, because RhYMe's player never touches R7 for an effect: the
     * mixer is the song's, written by the {@code mixer} stream like any other
     * register, and a song needing its sample clean has already disconnected
     * the voice itself. And a channel's own commands end the sample running on
     * it: an effect pop of 0 routes to {@code _ymr_stop_channel}, which stops
     * the timer, drops the effect and the sample and writes the voice's
     * volume back out of the shadow, and an effect pop of anything else
     * reprograms the one timer the sample was ticking on. Either way the sample
     * ends on that frame, so the script must not leave it running to its marker.
     */
    public static final EffectScript.Semantics SEMANTICS =
            new EffectScript.Semantics(false, false, true, false, true);

    /** Code bit 3: flipped on every sample trigger, so that two pops of one
     * index at one rate are two different code bytes and the script starts the
     * sample twice. See this class's javadoc for why the bit is free. */
    public static final int TRIGGER = 0x08;

    /** A YMX sample table entry stores its length in a word, so no sample
     * may pass this. */
    private static final int MAX_SAMPLE_BYTES = 65535;

    /** Bit 4 of a volume register: the voice takes its level from the envelope
     * generator, and the volume nibble is ignored. */

    /** The shape an RTE retriggers before the song has ever popped one -
     * {@code ENV_SHAPE_INIT} in the RhYMe player, and a value the spec names. */

    /** R13, the envelope shape, and R8, the first of the three volume
     * registers a timer effect's parameter is read out of. */
    private static final int R_ENVELOPE_SHAPE = 13;
    private static final int R_VOLUME_A = 8;

    /** The largest sample number a YMX PCM action can name, from the five bits
     * the script reads it out of. */
    /** The shape an RTE restarts before the song has popped one. The .YMR
     * spec says to assume it and RhYMe's player primes its shadow with it. */
    private static final int SHAPE_BEFORE_ANY_POP = 0x08;

    private static final int SAMPLES = YmxFormat.MAX_SAMPLES;

    private final YmrReader.Song source;
    private final String name;
    private final int frames;
    private final byte[][] registers;
    private final byte[][] codes = new byte[CHANNELS][];
    private final byte[][] counts = new byte[CHANNELS][];
    private final Prepared[] samples;
    private final List<String> notes = new ArrayList<>();

    // What each channel had to have changed, counted rather than reported a
    // frame at a time: a song 9984 frames long can break one rule on a
    // thousand of them and still only be doing one thing wrong.
    private final int[] reservedEffect = new int[CHANNELS];
    private final int[] reservedType = new int[CHANNELS];
    private final int[] inertTimer = new int[CHANNELS];
    private final int[] missingSample = new int[CHANNELS];
    private final int[] cappedSample = new int[CHANNELS];

    private YmrEffects(YmrReader.Song source, String name) {
        this.source = source;
        this.name = name;
        this.frames = source.frameCount();
        this.registers = new byte[YmrReader.REGISTER_COUNT][];
        this.samples = prepareSamples();
    }

    /**
     * Converts a song.
     *
     * @param name what to call the song. A .YMR carries no metadata,
     *             so the caller's file stem is the only name there is, and
     *             the title and composer come out empty.
     */
    public static Tune convert(YmrReader.Song song, String name) {
        return new YmrEffects(song, name).run();
    }

    private Tune run() {
        for (int r = 0; r < YmrReader.REGISTER_COUNT; r++) {
            registers[r] = source.registers()[r].clone();
        }
        for (int channel = 0; channel < CHANNELS; channel++) {
            walk(channel);
        }
        reportChannels();

        // A .YMR carries no metadata, so the author and the comment
        // come out empty and the caller's file stem is the only name there is.
        return new Tune(frames, source.frameRate(), source.ymClock(), source.loops(),
                registers, codes, counts, shapes(), levelsOf(samples),
                loopsOf(samples), SEMANTICS,
                name, "", "", notes);
    }

    /**
     * The envelope shape a retrigger stream would restart, frame by frame.
     *
     * <p>RhYMe files it where the chip does. An RTE handler rewrites R13 with
     * the player's own copy of the shape - {@code _ymr_shadow+R_ENVS}, primed
     * with {@code $08} - so the shape in force is the last value the
     * envelope-shape stream popped. The reader marks a frame that popped
     * nothing with {@link YmrReader#NO_ENVELOPE_SHAPE}, the marker
     * the frame write means by it, so what a retrigger needs is the last
     * value before it rather than this frame's absence of one.
     *
     * <p>Before the song has popped a shape at all the spec says to assume
     * {@code $08}, and RhYMe's player primes its shadow with the same. That
     * assumption is a fact about the .YMR format, so it belongs here rather
     * than in a player that would otherwise have to hold one assumption per
     * format it might be fed.
     */
    private static byte[][] levelsOf(Prepared[] prepared) {
        byte[][] out = new byte[prepared.length][];
        for (int index = 0; index < prepared.length; index++) {
            out[index] = prepared[index].data();
        }
        return out;
    }

    private static int[] loopsOf(Prepared[] prepared) {
        int[] out = new int[prepared.length];
        for (int index = 0; index < prepared.length; index++) {
            out[index] = prepared[index].loopStart();
        }
        return out;
    }

    private byte[] shapes() {
        byte[] shapes = new byte[frames];
        int shape = SHAPE_BEFORE_ANY_POP;
        for (int frame = 0; frame < frames; frame++) {
            int written = registers[R_ENVELOPE_SHAPE][frame] & 0xFF;
            if (written != YmrReader.NO_ENVELOPE_SHAPE) {
                shape = written & 15;
            }
            shapes[frame] = (byte) shape;
        }
        return shapes;
    }

    // ------------------------------------------------------------- the samples

    /**
     * The sample blocks as the file stores them, capped, with where they loop.
     *
     * <p>Nothing is converted on the way: RhYMe's exporter has already
     * reduced every sample to the 4-bit levels the PSG's volume register
     * takes, which lets its timer ISR write a byte straight to the chip with
     * no table in between - and what makes this the one thing a .YMR hands
     * over that needs no work. A YM digidrum arrives 8-bit and its own front
     * end has to fold it down; here the bytes are the levels.
     */
    private Prepared[] prepareSamples() {
        List<YmrReader.Sample> blocks = source.samples();
        int keep = Math.min(blocks.size(), SAMPLES);
        if (blocks.size() > keep) {
            note("samples " + keep + ".." + (blocks.size() - 1) + " dropped: a YMX sample"
                    + " number is the five bits the script reads out of a volume"
                    + " register, so the format carries " + SAMPLES + " and this song has "
                    + blocks.size());
        }
        Prepared[] prepared = new Prepared[keep];
        for (int index = 0; index < keep; index++) {
            prepared[index] = prepare(index, blocks.get(index));
        }
        return prepared;
    }

    /**
     * One .YMR sample block, ready for the table, and where it loops back to.
     *
     * <p>The loop point is the block's own, because a PCM stream has one:
     * the tick's end test already sets the condition codes off the byte it
     * just played, so resuming instead of stopping costs nothing per tick and
     * a few instructions on a path that runs once a sample. The alternative -
     * unrolling, the loop region written out again and again towards a
     * ceiling - costs the file real bytes and still stops in the end.
     */
    private record Prepared(byte[] data, int loopStart) {}

    private Prepared prepare(int index, YmrReader.Sample block) {
        byte[] data = levels(index, block.data());
        if (data.length > MAX_SAMPLE_BYTES) {
            // The format allows a sample of 65536 bytes and a YMX sample table
            // entry holds its length in a word, so the very largest a .YMR may
            // carry is one byte too long to describe.
            note("sample " + index + " is " + data.length + " bytes, past the "
                    + MAX_SAMPLE_BYTES + " a YMX sample table entry's word-sized length"
                    + " can name: cut to fit");
            data = Arrays.copyOf(data, MAX_SAMPLE_BYTES);
        }
        if (!block.looped()) {
            return new Prepared(data, YmxFormat.SAMPLE_ONE_SHOT);
        }
        int start = block.loopStart();
        if (start >= data.length) {
            note("sample " + index + " is marked looped from " + start + ", which is past"
                    + " its " + data.length + " bytes: played once instead");
            return new Prepared(data, YmxFormat.SAMPLE_ONE_SHOT);
        }
        return new Prepared(data, start);
    }

    /**
     * The block's bytes, with anything above a 4-bit level masked away. The
     * exporter writes 0..15 and nothing else, but a byte with bit 7 set would
     * be read by the PCM tick as the end marker and cut the sample there, so
     * this is worth the one pass it costs.
     */
    private byte[] levels(int index, byte[] data) {
        byte[] out = data.clone();
        int wrong = 0;
        for (int i = 0; i < out.length; i++) {
            if ((out[i] & 0xFF) > 15) {
                out[i] = (byte) (out[i] & 15);
                wrong++;
            }
        }
        if (wrong > 0) {
            note("sample " + index + " carries " + wrong + " byte" + (wrong == 1 ? "" : "s")
                    + " above the 4-bit level the format defines; masked, since a byte"
                    + " with bit 7 set is what ends a PCM stream");
        }
        return out;
    }


    // ------------------------------------------------------------- the streams

    /**
     * One channel's whole timeline, replayed the way {@code _ymr_process_tmr}
     * reconciles it once a frame's commands have been read.
     *
     * <p>The rule is about pops, because a value that did not pop did
     * not change: popping the effect stream with something in it CONFIGURES
     * the timer - which restarts a sample even when the index it names is the
     * one already playing - popping it with 0 stops the timer, popping the
     * sample stream restarts the sample on a timer that is already running,
     * and a rate pop on its own reprograms the prescaler and counter without
     * disturbing anything, so a pitch slide does not restart a sample or reset
     * a PWM's phase. A frame that pops none of the three changes nothing at
     * all.
     */
    private void walk(int channel) {
        int voice = channel;                    // the binding; see TIMERS
        codes[channel] = new byte[frames];
        counts[channel] = new byte[frames];

        int running = YmrReader.TimerFrame.NONE;
        int prescaler = 0;
        int counter = 0;
        int sample = 0;
        int trigger = 0;                        // the code's bit 3, flipped per trigger
        int armedTo = 0;                        // frame the armed PCM code goes quiet on
        int last = 0;
        List<YmrReader.TimerFrame> timer = source.timer(channel);

        for (int frame = 0; frame < frames; frame++) {
            YmrReader.TimerFrame want = timer.get(frame);
            boolean configure = false;
            if (want.effectPopped()) {
                if (want.effect() == YmrReader.TimerFrame.NONE) {
                    running = YmrReader.TimerFrame.NONE;
                } else {
                    configure = true;
                }
            } else if (running != YmrReader.TimerFrame.NONE && want.samplePopped()) {
                configure = true;
            }
            boolean started = false;
            if (configure) {
                running = want.effect();
                prescaler = want.prescaler();
                counter = want.counter();
                sample = want.sample();
                started = running == YmrReader.TimerFrame.SAMPLE;
                if (started) {
                    trigger ^= TRIGGER;
                }
            } else if (running != YmrReader.TimerFrame.NONE && want.ratePopped()) {
                prescaler = want.prescaler();
                counter = want.counter();
            }

            int code = code(channel, voice, running, prescaler, counter, sample,
                    trigger, started, frame, armedTo);
            if ((code & 0xC0) == Tune.KIND_PCM && code != last) {
                // The script starts a sample wherever a PCM code changes,
                // except where only the prescaler moved: bit 3 says whether a
                // trigger happened, so a rate pop bends the sample rather than
                // restarting it, and this is a window only when it is a start.
                armedTo = frame + armed(sample, prescaler, counter);
            }
            last = code;
            codes[channel][frame] = (byte) code;
            counts[channel][frame] = (byte) (code == 0 ? 0 : counter);
            parameter(channel, voice, code, frame, sample);
        }
    }

    /**
     * The code byte for one frame, or 0 for a channel with nothing to run.
     *
     * <p>Three things put a channel back to idle whatever its streams say. A
     * reserved effect type is one this converter will not guess at: RhYMe's
     * own player falls through to PWM for anything it does not recognise, but
     * the spec reserves 4-255 and a wrong guess is a wrong sound. A prescaler
     * index of 0 is the MFP's stopped state. A counter of 0 is not - the MFP
     * reads it as 256 counts - but neither is played: a rate byte of zero is
     * not a rate. And a
     * sample whose block is not in the file - or was dropped past the cap -
     * has nothing to play.
     */
    private int code(int channel, int voice, int running, int prescaler, int counter,
                     int sample, int trigger, boolean started, int frame, int armedTo) {
        if (running == YmrReader.TimerFrame.NONE) {
            return 0;
        }
        int kind = switch (running) {
            case YmrReader.TimerFrame.PWM -> Tune.KIND_TOGGLE;
            case YmrReader.TimerFrame.SAMPLE -> Tune.KIND_PCM;
            case YmrReader.TimerFrame.RTE -> Tune.KIND_RETRIGGER;
            default -> -1;
        };
        if (kind < 0) {
            reservedEffect[channel]++;
            reservedType[channel] = running;
            return 0;
        }
        if (Tune.prescaler(prescaler & 7) == 0 || counter == 0) {
            inertTimer[channel]++;
            return 0;
        }
        int head = kind | ((voice + 1) << 4) | (prescaler & 7);
        if (kind != Tune.KIND_PCM) {
            return head;
        }
        if (sample >= samples.length) {
            if (started) {
                if (sample < source.samples().size()) {
                    cappedSample[channel]++;
                } else {
                    missingSample[channel]++;
                }
            }
            return 0;
        }
        // A sample that has played out leaves the channel idle rather than
        // holding a code nothing acts on: the script releases a PCM channel
        // without emitting anything - the marker tick ended it -
        // and letting the code go frees the volume register's frame write,
        // which is where the voice's own volume comes back from.
        return started || frame < armedTo ? head | trigger : 0;
    }

    /**
     * The parameter the script will read for this frame's code out of the
     * voice's volume register - a PCM stream's sample number, or a retrigger
     * stream's envelope shape. A toggle stream's volume is already there.
     */
    private void parameter(int channel, int voice, int code, int frame, int sample) {
        if (code == 0) {
            return;
        }
        // A retrigger stream needs nothing written. From format v9 the shape
        // such a stream restarts is carried in the script, and this front end
        // reads it off R13, because that is where RhYMe keeps it - not off a
        // volume register it never touches. So the volume byte stays the
        // .YMR's own on every frame, which is what it was before this front
        // end existed.
        if ((code & 0xC0) == Tune.KIND_PCM) {
            registers[R_VOLUME_A + voice][frame] = (byte) sample;
        }
    }

    /**
     * How many frames a sample armed with this rate stays armed for.
     *
     * <p>This is {@code EffectScript.duration}'s arithmetic, deliberately
     * repeated: the two have to agree on the frame the voice comes back, and
     * they are computing it from the same four numbers - the sample's length
     * plus its end marker, the timer divisor, the frame rate, and the
     * sixteenth of a frame the trigger action itself runs into. If they
     * disagree the code would still be armed on the frame the skip lifts,
     * and the sample number sitting in the volume register would be written to
     * the chip as a volume.
     */
    private int armed(int sample, int prescaler, int counter) {
        long ticks = samples[sample].data().length + 1L;
        long divisor = (long) Tune.prescaler(prescaler & 7) * counter;
        long scaled = ticks * divisor * source.frameRate() + Tune.MFP_CLOCK / 16;
        return (int) ((scaled + Tune.MFP_CLOCK - 1) / Tune.MFP_CLOCK);
    }

    // --------------------------------------------------------------- the notes

    private void note(String what) {
        notes.add(what);
    }

    /** One line per channel per thing that channel had to have changed. */
    private void reportChannels() {
        for (int channel = 0; channel < CHANNELS; channel++) {
            String timer = "Timer " + "ABD".charAt(channel);
            String voice = "voice " + (char) ('A' + channel);
            if (reservedEffect[channel] > 0) {
                note(timer + " runs effect type " + reservedType[channel] + " on "
                        + frameCount(reservedEffect[channel]) + ", which version 1.3"
                        + " reserves: dropped rather than guessed at");
            }
            if (inertTimer[channel] > 0) {
                note(timer + " is configured with a prescaler or counter of 0 on "
                        + frameCount(inertTimer[channel]) + ": a prescaler of 0 is"
                        + " the MFP's stopped state, a counter of 0 is 256, and"
                        + " neither is armed here");
            }
            if (missingSample[channel] > 0) {
                note(timer + " triggers a sample with no block behind it "
                        + times(missingSample[channel]) + ": nothing plays");
            }
            if (cappedSample[channel] > 0) {
                note(timer + " triggers a sample past the " + SAMPLES + " this format"
                        + " carries " + times(cappedSample[channel]) + ": nothing plays");
            }
        }
    }

    private static String frameCount(int count) {
        return count + " frame" + (count == 1 ? "" : "s");
    }

    private static String times(int count) {
        return count == 1 ? "once" : count + " times";
    }
}
