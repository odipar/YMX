package org.ymx.rig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.st4.St4Decompressor;
import org.st4.St4Format;
import org.ymx.Tune;
import org.ymx.YmxFormat;

/**
 * A {@code .ymx} against SPEC.md §9.3 - the rules a player does not check.
 *
 * <p>A file that breaks one of those rules is undefined behaviour (§9.1):
 * the player reads it, drives the chip from it and reports nothing, so a
 * writer that breaks one hears the result rather than reading it. This
 * decodes the streams and reads the rules back off them.
 *
 * <p>{@link RefDump} and {@link TickDump} run the real player and record
 * what it writes; neither says whether the file was within the rules. This
 * drives nothing and needs neither rmac nor libunicorn.
 *
 * <p>What it reads is listed in {@code doc/tools.md}. Two rules are outside
 * it: the sample table's own bounds, which this reads without checking, and
 * R13's {@code $FF} on every frame that must not restart the envelope - a
 * marker whose absence is a value the file is free to carry.
 */
final class Check {

    /** The bits §2's table leaves in each register's value. */
    private static final int[] MASK = {0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F,
            0x1F, 0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F};

    private static final String[] OPCODE = {"RESUME", "HOLD", "RELEASE",
            "START_TOGGLE", "RETUNE", "START_RETRIGGER", "START_PCM",
            "START_PCM_PREEMPT"};

    private static final int RESUME = 0;
    private static final int HOLD = 1;
    private static final int RELEASE = 2;
    private static final int START_TOGGLE = 3;
    private static final int RETUNE = 4;
    private static final int START_RETRIGGER = 5;
    private static final int START_PCM = 6;
    private static final int START_PCM_PREEMPT = 7;

    /** M bit 4: bits 7-5 are read this frame. */
    private static final int M_SKIPS = 0x10;

    /** No voice: the value a two-bit voice field carries for RETUNE's live
     * form, and this class's mark for a channel driving no voice. */
    private static final int NO_VOICE = 3;

    /** The kinds a channel's stream can be, and the absence of one. */
    private enum Kind { NONE, TOGGLE, RETRIGGER, PCM }

    /** One place the file leaves the rules, and where. */
    record Fault(int frame, String rule, String detail) {
        @Override
        public String toString() {
            return (frame < 0 ? "header" : "frame " + frame)
                    + ": " + rule + " - " + detail;
        }
    }

    /** What one channel's timer is carrying between two action bytes. */
    private static final class Channel {
        Kind kind = Kind.NONE;
        int voice = NO_VOICE;
        int prescaler;
        int rejoin;                         // the frame a one-shot could
                                            // first have finished on
        boolean running;                    // the timer counts
        boolean disabled;                   // released, its interrupt down
    }

    private Check() {}

    public static void main(String[] args) throws IOException {
        int failed = 0;
        for (String name : args) {
            byte[] file;
            try {
                file = Files.readAllBytes(Path.of(name));
            } catch (IOException unreadable) {
                // A file that is not there is the caller's mistake, not a
                // fault in a tune: it is reported and not thrown, the way
                // the Go and C# checkers report it.
                System.err.println("ymxcheck: cannot read " + name);
                System.exit(1);
                return;
            }
            List<Fault> faults = check(file);
            System.out.println(name + ": "
                    + (faults.isEmpty() ? "within §9.3"
                            : faults.size() + " outside §9.3"));
            for (Fault fault : faults) {
                System.out.println("  " + fault);
                failed = 1;
            }
        }
        System.exit(failed);
    }

    /** Every rule this reads that the file breaks, in frame order. */
    static List<Fault> check(byte[] file) {
        List<Fault> faults = new ArrayList<>();
        if (file.length < YmxFormat.HEADER_SIZE
                || longAt(file, YmxFormat.OFFSET_MAGIC) != YmxFormat.MAGIC) {
            faults.add(new Fault(-1, "§1.1 magic", "the file does not open with 'YMX!'"));
            return faults;
        }
        int version = wordAt(file, YmxFormat.OFFSET_VERSION);
        if (version != YmxFormat.VERSION) {
            faults.add(new Fault(-1, "§1.1 version", "format "
                    + YmxFormat.versionName(version) + ", not "
                    + YmxFormat.versionName()));
            return faults;
        }
        int frames = longAt(file, YmxFormat.OFFSET_FRAMES);
        int streams = wordAt(file, YmxFormat.OFFSET_STREAM_COUNT);
        int ring = wordAt(file, YmxFormat.OFFSET_RING_SIZE);
        int flags = wordAt(file, YmxFormat.OFFSET_FLAGS);
        int loopFrame = longAt(file, YmxFormat.OFFSET_LOOP_FRAME);
        shape(file, faults, frames, streams, ring, loopFrame);
        if (!faults.isEmpty()) {
            return faults;
        }

        // §6's table, which the rejoin bound below reads: one entry of eight
        // bytes per sample, its length at 4 and its loop point at 6.
        int sampleTable = longAt(file, YmxFormat.OFFSET_SAMPLE_TABLE);
        int sampleCount = sampleTable == 0 ? 0
                : wordAt(file, YmxFormat.OFFSET_SAMPLE_COUNT);
        int[] length = new int[sampleCount];
        int[] loop = new int[sampleCount];
        for (int sample = 0; sample < sampleCount; sample++) {
            int at = sampleTable + 8 * sample;
            if (at < 0 || at > file.length - 8) {
                faults.add(new Fault(-1, "§6 sample table",
                        "entry " + sample + " lies outside the file"));
                return faults;
            }
            length[sample] = wordAt(file, at + 4);
            loop[sample] = wordAt(file, at + 6);
        }
        int rate = wordAt(file, YmxFormat.OFFSET_PLAYER_HZ);

        byte[][] value = new byte[YmxFormat.STREAMS][];
        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            try {
                value[stream] = stream(file, stream, frames);
            } catch (RuntimeException | AssertionError e) {
                faults.add(new Fault(-1, "§1.4 section",
                        "stream " + stream + " does not decode: " + e.getMessage()));
            }
        }
        if (!faults.isEmpty()) {
            return faults;
        }
        registers(faults, value, frames);
        script(faults, value, frames, flags, length, loop, rate);
        return faults;
    }

    // -----------------------------------------------------------------
    // The shape
    // -----------------------------------------------------------------

    private static void shape(byte[] file, List<Fault> faults, int frames,
            int streams, int ring, int loopFrame) {
        if (frames < 1) {
            faults.add(new Fault(-1, "§9.3 shape", "O is " + frames + ", not at least 1"));
        }
        if (streams < YmxFormat.STREAMS || streams > YmxFormat.MAX_STREAMS) {
            faults.add(new Fault(-1, "§1.5 S", "the stream count is " + streams
                    + ", outside " + YmxFormat.STREAMS + " to " + YmxFormat.MAX_STREAMS));
        }
        if (ring < 1 || ring > YmxFormat.MAX_RING_SIZE) {
            faults.add(new Fault(-1, "§1.3 N", "the ring size is " + ring
                    + ", outside 1 to " + YmxFormat.MAX_RING_SIZE));
        }
        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            if (entry(file, YmxFormat.OFFSET_SECTION_TABLE, stream) == 0) {
                faults.add(new Fault(-1, "§9.3 shape",
                        "section-table entry " + stream + " is 0"));
            }
        }
        if (loopFrame < 0) {
            faults.add(new Fault(-1, "§9.3 shape",
                    "L is " + loopFrame + ", not a frame index"));
            return;                         // O - L is read below
        }
        if (loopFrame != 0 && loopFrame >= frames) {
            faults.add(new Fault(-1, "§9.3 shape",
                    "L is " + loopFrame + ", not below O at " + frames));
            return;                         // O - L is read below
        }
        // The loop form every container carries: a rewind point of L bytes
        // where a pass is longer than a ring, none where it is not. A
        // container's own header says which, and the twenty-five say the
        // same thing or the player cannot tell (§8).
        int rewind = loopFrame != 0 && frames - loopFrame > ring
                ? loopFrame : St4Format.NO_REWIND;
        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            long entry = entry(file, YmxFormat.OFFSET_SECTION_TABLE, stream);
            if (entry == 0 || YmxFormat.isStored(entry)) {
                continue;
            }
            int start = (int) YmxFormat.sectionOffset(entry);
            if (start < 0 || start + St4Format.HEADER_SIZE > file.length) {
                continue;                       // reported where the section is read
            }
            int carried = longAt(file, start + St4Format.OFFSET_REWIND);
            int window = longAt(file, start + St4Format.OFFSET_WINDOW);
            if (carried != rewind) {
                faults.add(new Fault(-1, "§9.3 shape", "stream " + stream
                        + "'s container carries rewind point " + carried + " where O - L is "
                        + (frames - loopFrame) + " against a ring of " + ring
                        + ": every container carries " + rewind));
            }
            int unit = file[start + 3] & 0xFF;
            if (unit > 0 && window != ring / unit) {
                faults.add(new Fault(-1, "§9.3 shape", "stream " + stream
                        + "'s container carries window " + window + " where the ring of "
                        + ring + " at unit " + unit + " gives " + ring / unit));
            }
        }
    }

    // -----------------------------------------------------------------
    // The register values
    // -----------------------------------------------------------------

    private static void registers(List<Fault> faults, byte[][] value, int frames) {
        for (int register = 0; register < YmxFormat.REGISTER_STREAMS; register++) {
            for (int frame = 0; frame < frames; frame++) {
                int byteValue = value[register][frame] & 0xFF;
                if (register == 13 && byteValue == 0xFF) {
                    continue;               // the marker: R13 is not written
                }
                if ((byteValue & ~MASK[register]) != 0) {
                    faults.add(new Fault(frame, "§2 register mask",
                            "R" + register + " carries " + hex(byteValue)
                            + ", outside the mask " + hex(MASK[register])));
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // The script: M, T and the action bytes
    // -----------------------------------------------------------------

    private static void script(List<Fault> faults, byte[][] value, int frames,
            int flags, int[] length, int[] loop, int rate) {
        byte[] master = value[YmxFormat.STREAM_M];
        byte[] spare = value[YmxFormat.STREAM_X];
        byte[] timers = value[YmxFormat.STREAM_T];
        int live = 0;
        for (int channel = 0; channel < YmxFormat.CHANNELS; channel++) {
            if ((flags & YmxFormat.flagChannel(channel)) != 0) {
                live |= 1 << channel;
            }
        }
        Channel[] channels = new Channel[YmxFormat.CHANNELS];
        Arrays.setAll(channels, c -> new Channel());
        int claimed = timerMap(faults, timers, live);
        int skips = 0;                      // a player begins with all three clear
        int previousMap = timers[0] & 0xFF;
        boolean[] reported = new boolean[3];

        for (int frame = 0; frame < frames; frame++) {
            int m = master[frame] & 0xFF;
            if ((m & ~(0x0F | M_SKIPS | 0xE0)) != 0) {
                faults.add(new Fault(frame, "§2.1 M", "the master byte is " + hex(m)));
            }
            if ((m & 0x0F & ~live) != 0) {
                faults.add(new Fault(frame, "§9.3 values", "M marks channel "
                        + Integer.numberOfTrailingZeros(m & 0x0F & ~live)
                        + ", which §1.2's flags do not"));
            }
            if ((m & M_SKIPS) != 0) {
                skips = (m >> 5) & 7;
            }
            map(faults, frame, timers, previousMap, live, claimed, channels);
            previousMap = timers[frame] & 0xFF;
            for (int channel = 0; channel < YmxFormat.CHANNELS; channel++) {
                if ((m & (1 << channel)) != 0) {
                    act(faults, frame, channel, channels, value,
                            spare[frame] & 0xFF, length, loop, rate);
                }
            }
            ownership(faults, frame, skips, channels, reported);
        }
    }

    /** Frame 0's byte claims a timer per flagged channel, all distinct. */
    private static int timerMap(List<Fault> faults, byte[] timers, int live) {
        int byteValue = timers[0] & 0xFF;
        int claimed = 0;
        for (int channel = 0; channel < YmxFormat.CHANNELS; channel++) {
            if ((live & (1 << channel)) == 0) {
                continue;
            }
            int timer = 1 << YmxFormat.timerOf(byteValue, channel);
            if ((claimed & timer) != 0) {
                faults.add(new Fault(0, "§9.3 actions", "two flagged channels name Timer "
                        + "ABCD".charAt(YmxFormat.timerOf(byteValue, channel))
                        + " at frame 0"));
            }
            claimed |= timer;
        }
        return claimed;
    }

    /** A changed T entry moves a channel with nothing running, to a timer
     * frame 0 claimed. */
    private static void map(List<Fault> faults, int frame, byte[] timers,
            int previous, int live, int claimed, Channel[] channels) {
        int byteValue = timers[frame] & 0xFF;
        if (byteValue == previous) {
            return;
        }
        for (int channel = 0; channel < YmxFormat.CHANNELS; channel++) {
            if ((live & (1 << channel)) == 0
                    || YmxFormat.timerOf(byteValue, channel)
                            == YmxFormat.timerOf(previous, channel)) {
                continue;
            }
            int timer = YmxFormat.timerOf(byteValue, channel);
            if (channels[channel].running) {
                faults.add(new Fault(frame, "§2.3 T", "channel " + channel
                        + " moves to Timer " + "ABCD".charAt(timer)
                        + " with a timer still running"));
            }
            if ((claimed & (1 << timer)) == 0) {
                faults.add(new Fault(frame, "§9.3 actions", "channel " + channel
                        + " moves to Timer " + "ABCD".charAt(timer)
                        + ", which frame 0 did not claim"));
            }
        }
    }

    /** One channel's action byte, and what it leaves the channel carrying. */
    private static void act(List<Fault> faults, int frame, int channel,
            Channel[] channels, byte[][] value, int spare,
            int[] length, int[] loop, int rate) {
        int action = value[YmxFormat.streamAction(channel)][frame] & 0xFF;
        int opcode = action >> 5;
        int voice = (action >> 3) & 3;
        int low = action & 7;
        Channel state = channels[channel];
        String name = OPCODE[opcode] + " on channel " + channel;

        if (opcode == RELEASE && voice != 0) {
            faults.add(new Fault(frame, "§2.4 A",
                    name + " names voice " + voice + "; the field is written as 0"));
        }
        if (opcode != RETUNE && opcode != RELEASE && opcode != START_RETRIGGER
                && opcode != RESUME && voice == NO_VOICE) {
            faults.add(new Fault(frame, "§2.4 A", name + " names voice 3"));
        }
        if ((programs(opcode) || programsAtVoiceThree(opcode, voice))
                && (low < 1 || low > 7)) {
            faults.add(new Fault(frame, "§9.3 actions",
                    name + " carries prescaler index " + low + ", outside 1 to 7"));
        }
        switch (opcode) {
            case START_TOGGLE -> claim(faults, frame, channels, channel, Kind.TOGGLE, voice, low);
            case START_RETRIGGER ->
                    claim(faults, frame, channels, channel, Kind.RETRIGGER, voice, low);
            case START_PCM -> {
                triggered(channels[channel], value, frame, channel, voice,
                        low, length, loop, rate);
                if (silenced(channels, channel, voice) != 0) {
                    faults.add(new Fault(frame, "§9.3 actions", name
                            + " leaves a running timer standing; START_PCM_PREEMPT"
                            + " is the encoding where one is stopped"));
                }
                claim(faults, frame, channels, channel, Kind.PCM, voice, low);
            }
            case START_PCM_PREEMPT -> {
                triggered(channels[channel], value, frame, channel, voice,
                        low, length, loop, rate);
                int nibble = spare & 0x0F;
                int stops = silenced(channels, channel, voice);
                if (nibble != stops) {
                    faults.add(new Fault(frame, "§9.3 actions", name + " marks channels "
                            + hex(nibble) + " in X where the silenced ones are "
                            + hex(stops)));
                }
                for (int other = 0; other < YmxFormat.CHANNELS; other++) {
                    if ((nibble & (1 << other)) != 0) {
                        stop(channels[other]);
                    }
                }
                claim(faults, frame, channels, channel, Kind.PCM, voice, low);
            }
            case RELEASE -> {
                if (state.kind == Kind.NONE) {
                    faults.add(new Fault(frame, "§3", name + " stops a channel with no stream"));
                }
                if ((low & 1) != 0) {
                    state.disabled = true;  // the timer counts on
                } else {
                    stop(state);
                }
            }
            case RETUNE -> {
                if (state.kind == Kind.NONE) {
                    faults.add(new Fault(frame, "§3.1", name + " retunes no running stream"));
                } else if (voice != NO_VOICE && voice != state.voice) {
                    faults.add(new Fault(frame, "§9.3 actions", name + " moves voice "
                            + "ABC".charAt(state.voice) + " to " + "ABC".charAt(voice)
                            + "; a changed voice re-enters through a start opcode"));
                }
                state.prescaler = low;
                state.running = true;
                state.disabled = false;
            }
            case RESUME -> {
                if (!state.disabled) {
                    faults.add(new Fault(frame, "§9.3 actions",
                            name + " follows no disabling release"));
                }
                if (state.kind != Kind.TOGGLE) {
                    faults.add(new Fault(frame, "§3.3",
                            name + " resumes a stream that is not a toggle stream"));
                }
                state.disabled = false;
            }
            case HOLD -> {
                if (state.kind == Kind.NONE) {
                    faults.add(new Fault(frame, "§3", name + " updates no running stream"));
                }
                if ((low & 2) != 0 && (low & 4) != 0) {
                    faults.add(new Fault(frame, "§9.3 actions", name
                            + " sets both flag 2 and flag 4; a channel runs one stream kind"));
                }
            }
            default -> { }
        }
    }

    /** A start opcode takes the channel, and the voice it names. */
    private static void claim(List<Fault> faults, int frame, Channel[] channels,
            int channel, Kind kind, int voice, int prescaler) {
        if (kind != Kind.RETRIGGER) {
            for (int other = 0; other < YmxFormat.CHANNELS; other++) {
                if (other != channel && channels[other].voice == voice
                        && channels[other].kind != Kind.NONE
                        && channels[other].kind != Kind.RETRIGGER) {
                    faults.add(new Fault(frame, "§9.3 actions", "channel " + channel
                            + " starts a second timer stream on voice " + "ABC".charAt(voice)
                            + ", which channel " + other + " already runs"));
                }
            }
        }
        Channel state = channels[channel];
        state.kind = kind;
        state.voice = kind == Kind.RETRIGGER ? NO_VOICE : voice;
        state.prescaler = prescaler;
        state.running = true;
        state.disabled = false;
    }

    private static void stop(Channel state) {
        state.kind = Kind.NONE;
        state.voice = NO_VOICE;
        state.running = false;
        state.disabled = false;
    }

    private static boolean programs(int opcode) {
        return opcode == RETUNE || opcode == START_TOGGLE || opcode == START_RETRIGGER
                || opcode == START_PCM || opcode == START_PCM_PREEMPT;
    }

    /** Whether this byte is one of the two forms whose low bits are a
     * prescaler because voice 3 discriminates them (SPEC.md §3.4, §3.5). */
    private static boolean programsAtVoiceThree(int opcode, int voice) {
        return voice == NO_VOICE
                && (opcode == START_RETRIGGER || opcode == RESUME);
    }

    /** The channels with a timer counting. */
    /**
     * The channels a trigger silences: §9.3's rule is what the trigger stops,
     * not what happens to be running. A trigger takes one voice, so it
     * silences the channels holding a toggle stream on that voice, and no
     * others. Its own channel is reprogrammed rather than stopped, and a
     * stream on another voice is untouched.
     *
     * <p>Counting every running channel instead reported 4,888 faults over 36
     * of the 543 tunes in the collection, all of them a repeated trigger
     * meeting its own channel's timer.</p>
     */

    /**
     * A trigger's sample and its rate, kept so the rejoin below can be read
     * off them. The sample number is the voice's register byte on this frame,
     * which the skip keeps off the chip (§3.2), and the count is the trigger's
     * own P.
     */
    private static void triggered(Channel state, byte[][] value, int frame,
            int channel, int voice, int prescaler, int[] length, int[] loop,
            int rate) {
        int sample = value[8 + voice][frame] & 0xFF;
        int count = value[YmxFormat.streamAction(channel) + 1][frame] & 0xFF;
        state.rejoin = rejoinOf(length, loop, sample, prescaler, count, rate,
                frame);
    }

    /**
     * The frame a one-shot sample started on {@code frame} could first have
     * ended on, which is §6's rejoin bound:
     *
     * <pre>
     * frames = ceil(((length + 1) · prescaler[index] · count · rate
     *                + 2457600/16) / 2457600)
     * </pre>
     *
     * <p>A looping sample never ends of itself, so it gives
     * {@link Integer#MAX_VALUE}: a voice it owns rejoins the frame write
     * only where something stops it.</p>
     */
    private static int rejoinOf(int[] length, int[] loop, int sample,
            int prescaler, int count, int rate, int frame) {
        if (sample < 0 || sample >= length.length) {
            return Integer.MAX_VALUE;       // no such sample: §6 has it
        }
        if (loop[sample] != YmxFormat.SAMPLE_ONE_SHOT) {
            return Integer.MAX_VALUE;       // it loops, and ends at no frame
        }
        long ticks = (long) (length[sample] + 1) * Tune.prescaler(prescaler)
                * (count == 0 ? 256 : count);
        long clock = 2457600L;
        long frames = (ticks * rate + clock / 16 + clock - 1) / clock;
        return frame + (int) frames;
    }

    private static int silenced(Channel[] channels, int trigger, int voice) {
        int stops = 0;
        for (int channel = 0; channel < YmxFormat.CHANNELS; channel++) {
            Channel other = channels[channel];
            if (channel != trigger && other.kind == Kind.TOGGLE && other.running
                    && other.voice == voice) {
                stops |= 1 << channel;
            }
        }
        return stops;
    }

    /**
     * The skip field against what the streams own.
     *
     * <p>A voice is skipped while a timer stream writes its volume register
     * (§2.1), so a skip set on a voice no stream owns locks that voice out
     * of the frame write for as long as it stands, and a skip clear on a
     * voice a toggle stream owns has the frame write and the ticks both
     * writing it. A channel released under the resume model lands no tick
     * while its interrupt is down (§3.3), so it owns nothing across the
     * gap and the voice rejoins the frame write there. A PCM stream ends at
     * its sample's marker rather than at an opcode (§6), so a cleared skip
     * over one is the rejoin the file is entitled to and ends this reader's
     * ownership of the voice.
     *
     * <p>One fault a run: the frames after an edge carry the same mismatch
     * as the edge, and the edge is where the writer put it.
     */
    private static void ownership(List<Fault> faults, int frame, int skips,
            Channel[] channels, boolean[] reported) {
        for (int voice = 0; voice < 3; voice++) {
            Kind owner = Kind.NONE;
            for (Channel state : channels) {
                if (state.voice == voice && state.kind != Kind.NONE && !state.disabled) {
                    owner = state.kind;
                }
            }
            boolean skipped = (skips & (1 << voice)) != 0;
            String detail = null;
            if (skipped && owner == Kind.NONE) {
                detail = " is skipped and no timer stream owns its volume register:"
                        + " the frame write omits R" + (8 + voice) + " and no tick writes it";
            } else if (!skipped && owner == Kind.TOGGLE) {
                detail = " is not skipped and a toggle stream owns its volume register:"
                        + " the frame write and the ticks both write R" + (8 + voice);
            } else if (!skipped && owner == Kind.PCM) {
                // A sample ends at its own marker and the file says nothing
                // of it, so an unskipped voice reads as one that finished.
                // §6 bounds when it could have: before that frame it cannot
                // have, and the skip is one the writer did not set.
                int earliest = Integer.MAX_VALUE;
                for (Channel state : channels) {
                    if (state.voice == voice && state.kind == Kind.PCM) {
                        earliest = Math.min(earliest, state.rejoin);
                    }
                }
                if (frame < earliest) {
                    detail = " is not skipped and a PCM stream owns its volume"
                            + " register: the sample cannot have finished"
                            + (earliest == Integer.MAX_VALUE
                                    ? ", since it loops"
                                    : " before frame " + earliest);
                } else {
                    for (Channel state : channels) {
                        if (state.voice == voice && state.kind == Kind.PCM) {
                            stop(state);    // the sample reached its marker
                        }
                    }
                }
            }
            if (detail == null) {
                reported[voice] = false;
            } else if (!reported[voice]) {
                faults.add(new Fault(frame, "§9.3 values",
                        "voice " + "ABC".charAt(voice) + detail));
                reported[voice] = true;
            }
        }
    }

    // -----------------------------------------------------------------
    // Reading the container
    // -----------------------------------------------------------------

    /** One stream's O values, out of its section. */
    private static byte[] stream(byte[] file, int index, int frames) {
        return section(file, YmxFormat.OFFSET_SECTION_TABLE, index, frames);
    }

    /** One section, decoded to at least {@code count} values. */
    private static byte[] section(byte[] file, int table, int index, int count) {
        long entry = entry(file, table, index);
        int start = (int) YmxFormat.sectionOffset(entry);
        // Named here rather than left to the copy below, whose own words are
        // the runtime's and differ from tree to tree.
        if (start < 0 || start > file.length) {
            throw new IllegalStateException(
                    "the section is at " + start + ", outside the file");
        }
        if (YmxFormat.isStored(entry)) {
            return Arrays.copyOfRange(file, start, start + count);
        }
        byte[] bytes = Arrays.copyOfRange(file, start, next(file, start));
        St4Format.Container container = St4Format.read(bytes);
        // Decoded at the window and the rewind point the container carries,
        // so a match past the ring or before the rewind point is a fault
        // rather than a value, and a stream that repeats is one too: a
        // section of this version ends (§1.4).
        St4Decompressor.Decoded decoded = St4Decompressor.decode(container.control(),
                container.literal(), container.byteOffsets(), container.wordOffsets(),
                container.unit(), container.size(), container.window(),
                container.rewind());
        if (decoded.repeatIndex() >= 0) {
            throw new IllegalStateException("the section repeats from unit "
                    + decoded.repeatIndex() + ", and a section ends");
        }
        byte[] out = decoded.output();
        if (out.length < count) {
            throw new IllegalStateException(out.length + " values, not " + count);
        }
        return out;
    }

    /**
     * Where the body item at {@code start} ends: the next offset any table
     * names, or the file's end. Content in the body is located by offset
     * alone (§1.1), so a section's extent is the distance to its neighbour.
     */
    private static int next(byte[] file, int start) {
        var offsets = new TreeSet<Integer>();
        offsets.add(file.length);
        int sampleTable = longAt(file, YmxFormat.OFFSET_SAMPLE_TABLE);
        if (sampleTable != 0) {
            offsets.add(sampleTable);
        }
        for (int index = 0; index < YmxFormat.STREAMS; index++) {
            offsets.add((int) YmxFormat.sectionOffset(
                    entry(file, YmxFormat.OFFSET_SECTION_TABLE, index)));
        }
        Integer end = offsets.higher(start);
        return end == null ? file.length : end;
    }

    private static long entry(byte[] file, int table, int index) {
        return longAt(file, table + 4 * index) & 0xFFFF_FFFFL;
    }

    /**
     * The long at {@code at}, or 0 where that lies outside the file. The
     * bound is written as a subtraction: {@code at + 4} overflows on an
     * offset near the ceiling, and a guard that overflows passes the value
     * it was written to stop.
     */
    private static int longAt(byte[] file, int at) {
        if (at < 0 || at > file.length - 4) {
            return 0;
        }
        return ((file[at] & 0xFF) << 24) | ((file[at + 1] & 0xFF) << 16)
                | ((file[at + 2] & 0xFF) << 8) | (file[at + 3] & 0xFF);
    }

    /** The word at {@code at}, or 0 where that lies outside the file. */
    private static int wordAt(byte[] file, int at) {
        if (at < 0 || at > file.length - 2) {
            return 0;
        }
        return ((file[at] & 0xFF) << 8) | (file[at + 1] & 0xFF);
    }

    private static String hex(int value) {
        return String.format("$%02X", value);
    }
}
