package org.ymx.rig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The effect stage, frame by frame, against the compiled script: a SID
 * held, reloaded and retuned on slot 1, drums triggered and retriggered on
 * slot 2, a buzzer, the same-voice arbitration - asserted at this format's
 * frame-aligned edges. The tick handlers are then driven by direct
 * invocation, after the walk, so the script and the hand-run ticks never
 * disagree about state.
 */
final class Effects {

    private Effects() {}

    private static Player.Write w(long address, int value) {
        return new Player.Write(address, value);
    }

    private static Map<Integer, Integer> registerMap(List<Player.Pair> writes) {
        Map<Integer, Integer> map = new HashMap<>();
        for (Player.Pair pair : writes) {
            map.put(pair.register(), pair.value());
        }
        return map;
    }

    private static int get(Map<Integer, Integer> map, int register) {
        Integer value = map.get(register);
        return value == null ? -1 : value;
    }

    static String runEffects(boolean perf) {
        int frames = 72;
        byte[][] values = new byte[16][frames];
        for (int frame = 0; frame < frames; frame++) {
            values[7][frame] = 0x38;                // tone on, noise off
            values[8][frame] = 10;                  // steady volumes
            values[9][frame] = 11;
            values[10][frame] = 12;
            values[13][frame] = (byte) GenYm.NO_ENVELOPE_CHANGE;
        }
        for (int frame = 5; frame < 21; frame++) {  // SID voice A on slot 1
            values[1][frame] |= 0x10;
            values[6][frame] |= 1 << 5;
            values[14][frame] = (byte) (frame >= 15 ? 80 : 100);
        }
        for (int frame = 6; frame < 15; frame++) {  // ...whose volume SLIDES:
            values[8][frame] = (byte) (10 - (frame - 4) / 2);   // the script
        }                                           // must track it
        // The retune scene: the same SID slides across a prescaler boundary
        // - E $11 to $12 at frame 25 - as wobbling basses do many times a
        // second. The script emits SID_RETUNE: the timer reprograms, the
        // vector is untouched, the square keeps whichever half was
        // installed.
        for (int frame = 22; frame < 27; frame++) {
            values[1][frame] |= 0x10;
            values[6][frame] |= (frame < 25 ? 1 : 2) << 5;
            values[14][frame] = 90;
        }
        values[3][30] = 0x70;                       // drum voice C on slot 2
        values[8][30] |= 1 << 5;                    // its prescaler rides R8
        values[15][30] = 122;                       // ...at 5036 Hz
        // Real dumps trigger drums on back-to-back frames - an attack
        // sample, then a body sample - so frame 31 codes the same drum again
        // with a different number underneath: the script emits a fresh DRUM
        // either way.
        values[3][31] = 0x70;
        values[8][31] |= 1 << 5;
        values[15][31] = 122;
        // The ring-integrity trap: an 11-byte R10 pattern spanning the drum
        // frame, repeated 30 frames later - the packer emits a match that
        // copies those ring positions. Nothing edits the ring at runtime, so
        // the trap proves the drum number travels in the ring unharmed.
        // Frame 30's value doubles as the drum number: sample 1; frame 31's
        // is sample 0.
        int[] pattern = {3, 4, 5, 6, 7, 1, 0, 6, 5, 4, 3};
        for (int i = 0; i < pattern.length; i++) {
            values[10][25 + i] = (byte) pattern[i];
            values[10][55 + i] = (byte) pattern[i];
        }
        for (int frame = 40; frame < 43; frame++) { // sync-buzzer voice B, 123 Hz
            values[1][frame] = (byte) 0xE0;
            values[6][frame] |= 6 << 5;
            values[14][frame] = (byte) 200;
        }
        // The arbitration scene: a SID runs on voice B from slot 2, and at
        // frame 48 a drum fires on the SAME voice from slot 1. The script
        // compiled the whole exchange: START_PCM_PREEMPT stops the SID's
        // timer first, the suppressed SID costs nothing, and it resumes BY
        // RETUNE - phase intact - at the frame after the drum's computed
        // end.
        for (int frame = 45; frame < 53; frame++) {
            values[3][frame] |= 0x20;
            values[8][frame] |= 1 << 5;
            values[15][frame] = 90;
        }
        values[1][48] = 0x60;
        values[6][48] |= 1 << 5;
        values[14][48] = 60;
        values[9][48] = 0;                          // its number: sample 0
        byte[] drum0 = {(byte) 0x80, 0x40};
        byte[] drum1 = {0x10, (byte) 0xF0, 0x50};

        byte[] packed = Rig.pack(GenYm.ym6File(frames, values, drum0, drum1),
                960, 24, true, 1);
        Player player = new Player(packed, 1, perf);
        if (player.init() != 0) {
            return "effects: YMX_init rejected the file";
        }

        // The mechanism itself: stream T's byte, decoded into the channel
        // descriptors. Driven directly, since no YM tune moves the map.
        String problem = checkAssignment(player);
        if (!problem.isEmpty()) {
            return problem;
        }
        if (player.init() != 0) {                   // put the tune's own map back
            return "effects: YMX_init rejected the file on the second pass";
        }

        // Every tick-handler block must be byte-congruent with channel 1's:
        // the action handlers reach every patched operand through offsets
        // measured there, and ymx_link walks the blocks at a fixed stride.
        String[] timers = {"a", "b", "c", "d"};
        for (String shape : new String[] {"ymx_toggle_%s_on", "ymx_toggle_%s_off",
                "ymx_retrigger_%s", "ymx_park_%s"}) {
            int want = player.symbol(String.format(shape, "a"))
                    - player.symbol("ymx_pcm_a");
            for (int i = 1; i < timers.length; i++) {
                String label = String.format(shape, timers[i]);
                if (player.symbol(label)
                        - player.symbol("ymx_pcm_" + timers[i]) != want) {
                    return "effects: " + label + " broke the ISR block congruence";
                }
            }
        }
        int stride = player.symbol("ymx_pcm_b") - player.symbol("ymx_pcm_a");
        for (int i = 0; i < timers.length; i++) {
            if (player.symbol("ymx_pcm_" + timers[i])
                    - player.symbol("ymx_pcm_a") != stride * i) {
                return "effects: block " + timers[i] + " is not one stride along";
            }
        }

        // Channel 0 runs on Timer A and channel 1 on Timer D, which is what
        // the packer's default map says; the blocks are the timers'.
        long drumD = Rig.CODE + player.symbol("ymx_pcm_d");
        long sidOn = Rig.CODE + player.symbol("ymx_toggle_a_on");
        long sidOff = Rig.CODE + player.symbol("ymx_toggle_a_off");

        // A burst write is ten bytes and ends in the movep that sends it;
        // a skip replaces that instruction with two nops, so the state reads
        // as the opcode itself against $4E71.
        int writeSize = 10;
        int writeMovep = 6;
        long movepOpcode = player.uc.value(Rig.CODE + player.symbol("ymx_movep"), 2);

        long enableA = 0xFFFFFA07L;
        long enableB = 0xFFFFFA09L;
        for (int frame = 0; frame < 72; frame++) {
            if (frame == 25) {                      // flip the square to its
                PlayerTests.invokeIsr(player, sidOn);   // quiet half: the
            }                                       // retune below must
            Player.Frame played = player.frame();   // preserve it
            List<Player.Write> mfp = List.copyOf(player.mfp);
            Map<Integer, Integer> registers = registerMap(played.writes());
            if (frame == 5) {                       // SID start: stop, count,
                if (!mfp.equals(List.of(w(PlayerTests.TACR, 0),    // run,
                        w(PlayerTests.TADR, 100), w(PlayerTests.TACR, 1),
                        w(enableA, 0x20)))) {                      // enabled
                    return "effects: frame 5 programmed " + mfp;
                }
                if (skipped(player, movepOpcode, writeMovep, writeSize, 0) != 0) {
                    return "effects: frame 5 left the SID voice open";
                }
            } else if (frame >= 6 && frame <= 20 && frame != 15) {
                if (!mfp.isEmpty()) {               // held: the slide patches
                    return "effects: frame " + frame + " wrote " + mfp;
                }                                   // the tick's immediate,
                if (registers.containsKey(8)) {     // never the MFP
                    return "effects: frame " + frame + " wrote the SID voice volume";
                }
                int want = frame <= 14 ? 10 - (frame - 4) / 2 : 10;
                long vol = player.uc.value(Rig.CODE + player.symbol("ymx_pcm_a")
                        + player.symbol("ISR_TOGGLE_VOL"), 1);
                if (vol != want) {
                    return "effects: frame " + frame + " tick volume " + vol
                            + ", the slide says " + want;
                }
            } else if (frame == 15) {               // the count changed: a
                if (!mfp.equals(List.of(w(PlayerTests.TADR, 80)))) {
                    return "effects: frame 15 wrote " + mfp;    // HELD
                }                                               // reload,
            } else if (frame == 21) {               // released: stopped, and
                if (!mfp.equals(List.of(w(PlayerTests.TACR, 0)))) {
                    return "effects: frame 21 wrote " + mfp;    // the skip
                }                                   // lifts with the frame
                if (!registers.containsKey(8)) {
                    return "effects: frame 21 kept skipping the SID volume";
                }
            } else if (frame == 22) {               // a re-start is a FULL
                if (!mfp.equals(List.of(w(PlayerTests.TACR, 0),     // start
                        w(PlayerTests.TADR, 90), w(PlayerTests.TACR, 1),
                        w(enableA, 0x20)))) {                   // at phase zero
                    return "effects: frame 22 programmed " + mfp;
                }
                if (get(registers, 8) != 0) {       // ...the voice silenced
                    return "effects: frame 22 volume " + registers.get(8)
                            + ", not silent";       // for its first period
                }
                long vector = player.uc.value(0x134, 4);
                if (vector != sidOn) {
                    return "effects: the start did not install the loud half: "
                            + Long.toHexString(vector);
                }
            } else if (frame == 23 || frame == 24) {
                if (!mfp.isEmpty()) {
                    return "effects: frame " + frame + " wrote " + mfp;
                }
            } else if (frame == 25) {               // prescaler-only change:
                if (!mfp.equals(List.of(w(PlayerTests.TACR, 0),     // full
                        w(PlayerTests.TADR, 90), w(PlayerTests.TACR, 2),
                        w(enableA, 0x20)))) {               // timer reprogram
                    return "effects: frame 25 programmed " + mfp;
                }
                long vector = player.uc.value(0x134, 4);
                if (vector != sidOff) {             // ...but the phase lives:
                    return "effects: the retune reset the square to "
                            + Long.toHexString(vector)
                            + ", not the installed quiet half";
                }
            } else if (frame == 26) {
                if (!mfp.isEmpty()) {
                    return "effects: frame 26 wrote " + mfp;
                }
            } else if (frame == 27) {               // the scene's release
                if (!mfp.equals(List.of(w(PlayerTests.TACR, 0)))) {
                    return "effects: frame 27 wrote " + mfp;
                }
            } else if (frame == 30) {               // the drum start, slot 2
                if (!mfp.equals(List.of(w(PlayerTests.TCDCR, 0),
                        w(PlayerTests.TDDR, 122), w(PlayerTests.TCDCR, 1),
                        w(enableB, 0x10)))) {
                    return "effects: frame 30 programmed " + mfp;
                }
                if (registers.containsKey(10)) {    // the drum owns R10 now
                    return "effects: frame 30 wrote the drummed volume";
                }
                if (get(registers, 7) != (0x38 | 0xC0 | 0x24)) {    // baked,
                    return "effects: frame 30 mixer " + registers.get(7);
                }                                   // not forced
                long position = player.uc.value(
                        drumD + player.symbol("ISR_PCM_PTR"), 4);
                long drum = player.uc.value(
                        player.file + PlayerTests.drumTable(player) + 8, 4);
                if (position != player.file + drum) {
                    return "effects: the trigger points at the wrong sample";
                }
            } else if (frame == 31) {               // the same code again: a
                if (!mfp.equals(List.of(w(PlayerTests.TCDCR, 0),    // fresh
                        w(PlayerTests.TDDR, 122), w(PlayerTests.TCDCR, 1),
                        w(enableB, 0x10)))) {                       // DRUM
                    return "effects: frame 31 programmed " + mfp;
                }
                long position = player.uc.value(
                        drumD + player.symbol("ISR_PCM_PTR"), 4);
                long drum = player.uc.value(
                        player.file + PlayerTests.drumTable(player), 4);
                if (position != player.file + drum) {
                    return "effects: the retrigger points at the wrong sample";
                }
            } else if (frame == 32) {               // the computed end, frame
                if (!mfp.isEmpty()) {               // aligned: skip and mixer
                    return "effects: frame 32 wrote " + mfp;    // come back
                }                                               // as one
                if (get(registers, 7) != (0x38 | 0xC0)) {
                    return "effects: frame 32 mixer still forced";
                }
                if (!registers.containsKey(10)) {
                    return "effects: frame 32 kept skipping the drum voice";
                }
            } else if (frame == 40) {               // buzzer start on slot 1
                if (!mfp.equals(List.of(w(PlayerTests.TACR, 0),
                        w(PlayerTests.TADR, 200), w(PlayerTests.TACR, 6),
                        w(enableA, 0x20)))) {
                    return "effects: frame 40 programmed " + mfp;
                }
            } else if (frame == 41 || frame == 42) { // held, nothing changed
                if (!mfp.isEmpty()) {
                    return "effects: frame " + frame + " wrote " + mfp;
                }
            } else if (frame == 43) {
                if (!mfp.equals(List.of(w(PlayerTests.TACR, 0)))) {
                    return "effects: frame 43 wrote " + mfp;
                }
            } else if (frame == 45) {               // the scene's SID, slot 2
                if (!mfp.equals(List.of(w(PlayerTests.TCDCR, 0),
                        w(PlayerTests.TDDR, 90), w(PlayerTests.TCDCR, 1),
                        w(enableB, 0x10)))) {
                    return "effects: frame 45 programmed " + mfp;
                }
            } else if (frame == 46 || frame == 47) { // held: its volume is
                if (registers.containsKey(9)) {      // skipped
                    return "effects: frame " + frame + " wrote the skipped volume";
                }
            } else if (frame == 48) {               // START_PCM_PREEMPT: the
                if (!mfp.equals(List.of(w(PlayerTests.TCDCR, 0),    // SID's
                        w(PlayerTests.TACR, 0), w(PlayerTests.TADR, 60),
                        w(PlayerTests.TACR, 1),     // timer stops FIRST, then
                        w(enableA, 0x20)))) {       // the drum arms
                    return "effects: frame 48 programmed " + mfp;
                }
                if (registers.containsKey(9)) {
                    return "effects: frame 48 wrote the drummed volume";
                }
                if (get(registers, 7) != (0x38 | 0xC0 | 0x12)) {
                    return "effects: frame 48 mixer " + registers.get(7);
                }
            } else if (frame == 49) {               // the computed end: the
                if (!mfp.equals(List.of(w(PlayerTests.TCDCR, 0),    // SID
                        w(PlayerTests.TDDR, 90), w(PlayerTests.TCDCR, 1),
                        w(enableB, 0x10)))) {       // re-STARTS - phase 0
                    return "effects: frame 49 programmed " + mfp;
                }
                if (get(registers, 9) != 0) {       // the start's own silence
                    return "effects: frame 49 volume " + registers.get(9)
                            + ", not the silent first half";
                }
                if (get(registers, 7) != (0x38 | 0xC0)) {
                    return "effects: frame 49 mixer " + registers.get(7);
                }
            } else if (frame >= 50 && frame <= 52) {
                if (!mfp.isEmpty()) {
                    return "effects: frame " + frame + " wrote " + mfp;
                }
            } else if (frame == 53) {               // the restarted SID
                if (!mfp.equals(List.of(w(PlayerTests.TCDCR, 0)))) {
                    return "effects: frame 53 wrote " + mfp;     // releases
                }
                if (!registers.containsKey(9)) {
                    return "effects: frame 53 kept skipping the voice";
                }
            } else if (frame == 71) {               // the tune starts over:
                if (!mfp.equals(List.of(w(PlayerTests.TACR, 0),     // both
                        w(0xFFFFFA0BL, 0), w(enableA, 0x20),    // claimed
                        w(PlayerTests.TCDCR, 0),                // timers stop,
                        w(0xFFFFFA0DL, 0), w(enableB, 0x10)))) { // nothing
                    return "effects: the wrap wrote " + mfp;    // pending,
                }                                               // enabled again
                for (long vector : new long[] {0x134, 0x110}) {
                    long parked = player.uc.value(vector, 4);
                    if (parked != Rig.CODE + player.symbol("ymx_park_a")
                            && parked != Rig.CODE + player.symbol("ymx_park_d")) {
                        return "effects: the wrap left the vector at "
                                + Long.toHexString(vector) + " pointing at "
                                + Long.toHexString(parked) + ", not a park entry";
                    }
                }
            } else if (!mfp.isEmpty()) {
                return "effects: frame " + frame + " unexpectedly wrote " + mfp;
            }
            if (frame == 60 && get(registers, 10) != 1) {
                return "effects: frame 60 played " + registers.get(10)
                        + " - the ring did not keep the byte the drum number"
                        + " rode in on";
            }
        }

        // Both drums, tick by tick, by direct invocation: frame 31's on
        // Timer D (voice C), frame 48's on Timer A (voice B). Sample 0 is
        // 0x80, 0x40 -> nibbles 8, 4, then the marker parks the volume and
        // stops the timer - and nothing else: the script already scheduled
        // the skip and mixer edges at the frame boundary.
        problem = drumTicks(player, drumD, 10, PlayerTests.TCDCR, 0xFFFFFA11L, 0xEF);
        if (!problem.isEmpty()) {
            return problem;
        }
        problem = drumTicks(player, Rig.CODE + player.symbol("ymx_pcm_a"), 9,
                PlayerTests.TACR, 0xFFFFFA0FL, 0xDF);
        if (!problem.isEmpty()) {
            return problem;
        }
        if (perf && acc(player) != 2 * (21 + 21 + 23)) {    // both drums'
            return "effects: the drum ticks accumulated " + acc(player)
                    + ", not 130";                          // playouts
        }

        // The toggle tick: the loud half writes the volume and installs the
        // quiet half as a whole vector, and back.
        List<Player.Pair> pairs = PlayerTests.invokeIsr(player, sidOn);
        long vector = player.uc.value(0x134, 4);    // the A block still holds
        if (!pairs.equals(List.of(new Player.Pair(8, 10))) || vector != sidOff) {
            return "effects: the loud half wrote " + pairs + ", vector "
                    + Long.toHexString(vector);     // frame 5's voice A and
        }                                           // frame 25's volume
        pairs = PlayerTests.invokeIsr(player, sidOff);
        vector = player.uc.value(0x134, 4);
        if (!pairs.equals(List.of(new Player.Pair(8, 0))) || vector != sidOn) {
            return "effects: the quiet half wrote " + pairs + ", vector "
                    + Long.toHexString(vector);
        }

        // And the buzzer from frame 40: every tick rewrites the shape to R13.
        pairs = PlayerTests.invokeIsr(player,
                Rig.CODE + player.symbol("ymx_retrigger_a"));
        if (!pairs.equals(List.of(new Player.Pair(13, 11)))) {
            return "effects: the retrigger tick wrote " + pairs;
        }
        if (perf && acc(player) != 130 + 15 + 15 + 12) {
            return "effects: the ticks accumulated " + acc(player);
        }

        // One more frame clears the raster monitor's accumulator; the frame
        // itself replays the loop head, whose writes are the script's
        // business.
        player.frame();
        if (perf && acc(player) != 0) {
            return "effects: the frame did not clear the perf accumulator";
        }

        // The library's stop contract: it quiesces its claim - timers
        // stopped, their interrupt bits disabled and masked, no voice
        // skipped - and restores nothing; the host owns the machine state
        // (assumption 5).
        player.stop();
        if ((player.uc.value(0xFFFFFA19L, 1) & 0x0F) != 0
                || (player.uc.value(0xFFFFFA1DL, 1) & 0x0F) != 0) {
            return "effects: stop left a timer running";
        }
        if ((player.uc.value(0xFFFFFA07L, 1) & 0x20) != 0
                || (player.uc.value(0xFFFFFA13L, 1) & 0x20) != 0
                || (player.uc.value(0xFFFFFA09L, 1) & 0x10) != 0
                || (player.uc.value(0xFFFFFA15L, 1) & 0x10) != 0) {
            return "effects: stop left its claim enabled";
        }
        for (int voice = 0; voice < 3; voice++) {
            if (skipped(player, movepOpcode, writeMovep, writeSize, voice) != 2) {
                return "effects: stop left voice " + voice + " muted";
            }
        }

        // Claiming is per timer channel, and a second YMX_init must hand
        // back what the first one took: the file says which channels it
        // uses, so a tune that uses fewer leaves the player holding timers
        // nothing needs unless init gives them back first. Init the effect
        // tune, then init an effect-free one into the same blob and
        // workspace.
        byte[] quiet = GenYm.ym6File(40, new byte[16][40]);
        Player reused = new Player(packed, 1, perf);
        if (reused.init() != 0) {
            return "effects: init rejected the two-channel pack";
        }
        for (int i = 0; i < 32; i++) {              // far enough in to be
            reused.frame();                         // running
        }
        reused.uc.write(reused.file, Rig.pack(quiet, 960, 24, true, 1));
        if (reused.init() != 0) {
            return "effects: init rejected the effect-free pack";
        }
        if ((reused.uc.value(0xFFFFFA19L, 1) & 0x0F) != 0
                || (reused.uc.value(0xFFFFFA1DL, 1) & 0x0F) != 0) {
            return "effects: re-init left an unclaimed timer running";
        }
        if ((reused.uc.value(0xFFFFFA07L, 1) & 0x20) != 0
                || (reused.uc.value(0xFFFFFA13L, 1) & 0x20) != 0
                || (reused.uc.value(0xFFFFFA09L, 1) & 0x10) != 0
                || (reused.uc.value(0xFFFFFA15L, 1) & 0x10) != 0) {
            return "effects: re-init left an unclaimed channel enabled";
        }
        for (int i = 0; i < 20; i++) {
            Player.Frame frame = reused.frame();
            if (frame.result() != 0) {
                return "effects: the re-inited tune returned " + frame.result();
            }
        }

        // The -sidresume gap model, on the same tune: a fresh player walks
        // to the release and resume and must see the mask, the counting-on
        // timer, and the reload-only comeback - the player's resume opcodes,
        // live.
        Player resumed = new Player(Rig.pack(
                GenYm.ym6File(frames, values, drum0, drum1), 960, 24, true, 1,
                "-sidresume"), 1, perf);
        if (resumed.init() != 0) {
            return "effects: init rejected the -sidresume pack";
        }
        for (int frame = 0; frame < 30; frame++) {
            Player.Frame played = resumed.frame();
            Map<Integer, Integer> registers = registerMap(played.writes());
            List<Player.Write> mfp = List.copyOf(resumed.mfp);
            if (frame == 21) {
                if (!mfp.equals(List.of(w(enableA, 0x00)))) {   // masked:
                    return "effects: resume-model frame 21 wrote " + mfp;
                }                                               // IER bit
                if (!registers.containsKey(8)) {
                    return "effects: resume-model frame 21 kept the voice skipped";
                }
            } else if (frame == 22) {
                if (!mfp.equals(List.of(w(PlayerTests.TADR, 90),
                        w(enableA, 0x20)))) {
                    return "effects: resume-model frame 22 programmed " + mfp;
                }
                if (get(registers, 8) == 0) {
                    return "effects: the resume silenced a running square";
                }
            } else if (frame == 27) {
                if (!mfp.equals(List.of(w(enableA, 0x00)))) {
                    return "effects: resume-model frame 27 wrote " + mfp;
                }
            }
        }

        // The monitor's color protocol: every frame paints the yellow timer
        // bar, then its own red, then puts the original back; every tick
        // paints its timer's color and restores.
        if (perf) {
            List<Integer> seen = player.palette;
            Set<Integer> distinct = new HashSet<>(seen);
            if (!distinct.equals(Set.of(0x770, 0x700, 0x070, 0x007, 0))) {
                return "effects: the monitor painted " + distinct;
            }
            int bars = count(seen, 0x770);
            if (bars != count(seen, 0x700)) {
                return "effects: a timer bar without its frame band";
            }
            if (seen.get(seen.size() - 1) != 0 || count(seen, 0)
                    != seen.size() - 2 * bars - count(seen, 0x070)
                            - count(seen, 0x007)) {
                return "effects: the monitor did not restore the background";
            }
        } else if (!player.palette.isEmpty()) {
            return "effects: the monitor painted in a build without it";
        }
        return "";
    }

    /** 2 when the voice's burst write is open, 0 when it is muted - the two
     * values the old displacement trick reported. */
    private static int skipped(Player player, long movepOpcode, int writeMovep,
            int writeSize, int voice) {
        long at = Rig.CODE + player.symbol("ymx_wB") + writeMovep
                + writeSize * voice;
        long word = player.uc.value(at, 2);
        return word == movepOpcode ? 2 : word == 0x4E71 ? 0 : -1;
    }

    private static int acc(Player player) {
        return (int) player.uc.value(Rig.CODE + player.symbol("ymx_perf_acc"), 2);
    }

    private static int count(List<Integer> values, int wanted) {
        int count = 0;
        for (int value : values) {
            if (value == wanted) {
                count++;
            }
        }
        return count;
    }

    /** Plays a patched drum out by direct invocation: two sample nibbles,
     * then the marker - which parks the volume and stops the timer, nothing
     * more: the script owns every frame-side consequence. Sample 0 is 0x80,
     * 0x40 -> nibbles 8, 4. */
    static String drumTicks(Player player, long code, int register, long ctrl,
            long eoiRegister, int eoiValue) {
        int[] nibbles = {8, 4};
        for (int tick = 0; tick < nibbles.length; tick++) {
            List<Player.Pair> pairs = PlayerTests.invokeIsr(player, code);
            if (!pairs.equals(List.of(new Player.Pair(register, nibbles[tick])))) {
                return "effects: PCM tick " + tick + " wrote " + pairs;
            }
        }
        List<Player.Pair> pairs = PlayerTests.invokeIsr(player, code);
        if (!pairs.equals(List.of(new Player.Pair(register, 0x80),
                new Player.Pair(register, 0x0D)))) {
            return "effects: the marker tick wrote " + pairs;
        }
        List<Player.Write> tail = player.mfp.subList(
                Math.max(0, player.mfp.size() - 2), player.mfp.size());
        if (!tail.equals(List.of(w(ctrl, 0), w(eoiRegister, eoiValue)))) {
            return "effects: the marker tick programmed " + tail;
        }
        return "";
    }

    /** ymx_assign, driven directly: every map the T stream can express must
     * put the right timer's row into the right channel's descriptor. The
     * rows are the player's own, so this checks the copy and the two-bit
     * decode. */
    static String checkAssignment(Player player) {
        byte[][] rows = new byte[4][];
        String[] timers = {"a", "b", "c", "d"};
        for (int i = 0; i < 4; i++) {
            rows[i] = player.uc.read(
                    Rig.CODE + player.symbol("ymx_timer_" + timers[i]), 18);
        }
        int[] maps = {0x1B,         // 0->D 1->C 2->B 3->A: reversed
                0x00,               // every channel on Timer A, which the
                                    // packer never emits but the copy must
                0xE4,               // still do; 0->A 1->B 2->C 3->D: straight
                Rig.YMX_DEFAULT_MAP};
        for (int assignments : maps) {
            long stack = Rig.STACK_TOP - 512;
            player.uc.write(stack, new byte[] {0x00, 0x0A, 0x00, 0x00});
            player.uc.set(Unicorn.SR, 0x2700);
            player.uc.set(Unicorn.A7, stack);
            player.uc.set(Unicorn.D0, assignments);
            int code = player.uc.start(Rig.CODE + player.symbol("ymx_assign"),
                    Rig.MAGIC, 100_000);
            if (code != 0) {
                return "assign: " + Unicorn.error(code);
            }
            for (int channel = 0; channel < 4; channel++) {
                int timer = (assignments >> (2 * channel)) & 3;
                byte[] got = player.uc.read(
                        Rig.CODE + player.symbol("ymx_desc_" + channel), 18);
                if (!java.util.Arrays.equals(got, rows[timer])) {
                    return String.format("assign: map %#04x put the wrong row"
                            + " in channel %d (wanted timer %d)",
                            assignments, channel, timer);
                }
            }
        }
        return "";
    }
}
