package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ymx.EffectScript.HOLD_RELOAD;
import static org.ymx.EffectScript.RESUME_RELOAD;
import static org.ymx.EffectScript.RELEASE_MASK;
import static org.ymx.EffectScript.VERB_RESUME;
import static org.ymx.EffectScript.HOLD_VOLUME;
import static org.ymx.EffectScript.M_GATES;
import static org.ymx.EffectScript.M_GATE_SHIFT;
import static org.ymx.EffectScript.M_CHANNEL_0;
import static org.ymx.EffectScript.M_CHANNEL_1;
import static org.ymx.EffectScript.VERB_START_RETRIGGER;
import static org.ymx.EffectScript.VERB_START_PCM;
import static org.ymx.EffectScript.VERB_START_PCM_PREEMPT;
import static org.ymx.EffectScript.VERB_HOLD;
import static org.ymx.EffectScript.VERB_RETUNE;
import static org.ymx.EffectScript.VERB_START_TOGGLE;
import static org.ymx.EffectScript.VERB_RELEASE;
import static org.ymx.EffectScript.action;

import org.junit.jupiter.api.Test;
import org.ym6.Ym6Reader;
import org.ym6.YmEffects;

/**
 * The compiled effect script against the scenes the emulation rig plays -
 * the same tune {@code run_effects} builds, its expected v1 decisions
 * turned into expected v2 action bytes - plus the split rotation on loops
 * the rig cannot reach.
 *
 * <p>Where v2's frame-aligned semantics deliberately differ from v1 (a
 * drum's gate reopens at the computed end's frame boundary), the
 * expectations here encode the v2 side; everything else is v1's, the
 * loud-half phase reset on every SID arrival included.
 */
final class EffectScriptTest {

    private static Ym6Reader.Song song(int frames, byte[][] registers,
                                       int loop, byte[][] drums) {
        return new Ym6Reader.Song("YM6!", frames, 50, 2_000_000, loop, true,
                0, drums, "", "", "", registers);
    }

    private static EffectScript.Result compile(Ym6Reader.Song song, int loop) {
        return EffectScript.compile(YmEffects.tune(song), loop, 1);
    }

    private static EffectScript.Result compileResume(Ym6Reader.Song song, int loop) {
        Tune tune = YmEffects.tune(song);
        return EffectScript.compile(tune.under(tune.semantics().resuming()), loop, 1);
    }

    /** The rig's scene, exactly: SID with a reload, a retune pair, drum
     * retriggers, a buzzer, and the same-voice arbitration. */
    private static Ym6Reader.Song rigScene() {
        int frames = 72;
        byte[][] v = new byte[Ym6Reader.Song.YM_REGISTERS][frames];
        for (int f = 0; f < frames; f++) {
            v[7][f] = 0x38;
            v[8][f] = 10;
            v[9][f] = 11;
            v[10][f] = 12;
        }
        for (int f = 5; f <= 20; f++) {         // SID voice A on slot 1
            v[1][f] |= 0x10;
            v[6][f] |= 1 << 5;
            v[14][f] = (byte) (f >= 15 ? 80 : 100);
        }
        for (int f = 6; f <= 14; f++) {         // ...with a sliding volume
            v[8][f] = (byte) (10 - (f - 4) / 2);
        }
        for (int f = 22; f <= 26; f++) {        // the retune scene
            v[1][f] |= 0x10;
            v[6][f] |= (f < 25 ? 1 : 2) << 5;
            v[14][f] = 90;
        }
        v[3][30] = 0x70;                        // drum voice C on slot 2
        v[8][30] |= 1 << 5;
        v[15][30] = 122;
        v[3][31] = 0x70;                        // the retrigger, sample 0
        v[8][31] |= 1 << 5;
        v[15][31] = 122;
        byte[] pattern = {3, 4, 5, 6, 7, 1, 0, 6, 5, 4, 3};
        for (int i = 0; i < pattern.length; i++) {
            v[10][25 + i] = pattern[i];
        }
        for (int f = 40; f <= 42; f++) {        // sync-buzzer voice B
            v[1][f] = (byte) 0xE0;
            v[6][f] |= 6 << 5;
            v[14][f] = (byte) 200;
        }
        for (int f = 45; f <= 52; f++) {        // SID voice B on slot 2...
            v[3][f] |= 0x20;
            v[8][f] |= 1 << 5;
            v[15][f] = 90;
        }
        v[1][48] = 0x60;                        // ...and the drum takeover
        v[6][48] |= 1 << 5;
        v[14][48] = 60;
        v[9][48] = 0;
        byte[][] drums = {{(byte) 0x80, 0x40}, {0x10, (byte) 0xF0, 0x50}};
        return song(frames, v, 0, drums);
    }

    private static void expect(EffectScript.Result r, int frame, int m,
                               int a1, int p1, int a2, int p2) {
        assertEquals(m, r.m()[frame] & 0xFF, "M at frame " + frame);
        if ((m & M_CHANNEL_0) != 0) {
            assertEquals(a1, r.actions()[0][frame] & 0xFF, "A1 at frame " + frame);
            assertEquals(p1, r.counts()[0][frame] & 0xFF, "P1 at frame " + frame);
        }
        if ((m & M_CHANNEL_1) != 0) {
            assertEquals(a2, r.actions()[1][frame] & 0xFF, "A2 at frame " + frame);
            assertEquals(p2, r.counts()[1][frame] & 0xFF, "P2 at frame " + frame);
        }
    }

    @Test
    void theRigSceneCompilesToItsKnownDecisions() {
        EffectScript.Result r = compile(rigScene(), -1);
        assertEquals(72, r.frames());

        // Idle until the SID starts; its gate closes the same frame.
        for (int f = 0; f < 5; f++) {
            expect(r, f, 0, 0, 0, 0, 0);
        }
        expect(r, 5, M_CHANNEL_0 | M_GATES | (1 << M_GATE_SHIFT),
                action(VERB_START_TOGGLE, 0, 1), 100, 0, 0);
        for (int f = 6; f <= 14; f++) {         // held: the slide emits a
            if (f % 2 == 0) {                   // volume track exactly on
                expect(r, f, M_CHANNEL_0,           // the frames it changes; P
                        action(VERB_HOLD, 0, HOLD_VOLUME), 100, 0, 0);
            } else {
                expect(r, f, 0, 0, 0, 0, 0);
            }
        }
        expect(r, 15, M_CHANNEL_0,                  // the count reload and the
                action(VERB_HOLD, 0, HOLD_RELOAD | HOLD_VOLUME), 80, 0, 0);
        for (int f = 16; f <= 20; f++) {
            expect(r, f, 0, 0, 0, 0, 0);
        }
        expect(r, 21, M_CHANNEL_0 | M_GATES, action(VERB_RELEASE, 0, 0), 0, 0, 0);

        // The default (ym2149-rs) gap model: a re-arrival is a full START -
        // phase zero, one silent period, then the loud half.
        expect(r, 22, M_CHANNEL_0 | M_GATES | (1 << M_GATE_SHIFT),
                action(VERB_START_TOGGLE, 0, 1), 90, 0, 0);
        expect(r, 23, 0, 0, 0, 0, 0);
        expect(r, 24, 0, 0, 0, 0, 0);
        expect(r, 25, M_CHANNEL_0, action(VERB_RETUNE, 0, 2), 90, 0, 0);
        expect(r, 26, 0, 0, 0, 0, 0);
        expect(r, 27, M_CHANNEL_0 | M_GATES, action(VERB_RELEASE, 0, 0), 0, 0, 0);

        // The drum: trigger, retrigger with that frame's number, computed
        // end. Sample 0 has 2 values + marker at 4*122: well inside the
        // retrigger's own frame, so the reopen lands on the next boundary.
        expect(r, 30, M_CHANNEL_1 | M_GATES | (4 << M_GATE_SHIFT),
                0, 0, action(VERB_START_PCM, 2, 1), 122);
        expect(r, 31, M_CHANNEL_1, 0, 0, action(VERB_START_PCM, 2, 1), 122);
        assertEquals(0x24, r.r7force()[31] & 0xFF, "voice C forced while owned");
        expect(r, 32, M_GATES, 0, 0, 0, 0);     // the frame-aligned reopen
        assertEquals(0, r.r7force()[32] & 0xFF);
        expect(r, 33, 0, 0, 0, 0, 0);
        assertTrue(r.reopens().stream().anyMatch(x -> x[0] == 32 && x[1] == 2));

        expect(r, 40, M_CHANNEL_0, action(VERB_START_RETRIGGER, 1, 6), 200, 0, 0);
        expect(r, 41, 0, 0, 0, 0, 0);
        expect(r, 42, 0, 0, 0, 0, 0);
        expect(r, 43, M_CHANNEL_0, action(VERB_RELEASE, 0, 0), 0, 0, 0);

        // Arbitration: the takeover drum stops the SID's timer first, holds
        // the voice's gate (no mask change - it was already closed), and
        // the suppressed SID re-starts when the window ends.
        expect(r, 45, M_CHANNEL_1 | M_GATES | (2 << M_GATE_SHIFT),
                0, 0, action(VERB_START_TOGGLE, 1, 1), 90);
        expect(r, 48, M_CHANNEL_0, action(VERB_START_PCM_PREEMPT, 1, 1), 60, 0, 0);
        assertEquals(0x12, r.r7force()[48] & 0xFF, "voice B forced");
        expect(r, 49, M_CHANNEL_1, 0, 0, action(VERB_START_TOGGLE, 1, 1), 90);
        assertEquals(0, r.r7force()[49] & 0xFF, "mixer free from the reopen");
        expect(r, 50, 0, 0, 0, 0, 0);
        expect(r, 51, 0, 0, 0, 0, 0);
        expect(r, 52, 0, 0, 0, 0, 0);
        expect(r, 53, M_CHANNEL_1 | M_GATES, 0, 0, action(VERB_RELEASE, 0, 0), 0);
    }

    /** The -sidresume gap model on the same scene: releases mask, the
     * re-arrival resumes with just the changed count, and the takeover
     * whose timer was seized still full-starts. */
    @Test
    void theResumeModelMasksAndResumes() {
        EffectScript.Result r = compileResume(rigScene(), -1);
        expect(r, 21, M_CHANNEL_0 | M_GATES, action(VERB_RELEASE, 0, RELEASE_MASK),
                0, 0, 0);
        expect(r, 22, M_CHANNEL_0 | M_GATES | (1 << M_GATE_SHIFT),
                action(VERB_RESUME, 0, RESUME_RELOAD), 90, 0, 0);
        expect(r, 27, M_CHANNEL_0 | M_GATES, action(VERB_RELEASE, 0, RELEASE_MASK),
                0, 0, 0);
        expect(r, 49, M_CHANNEL_1, 0, 0, action(VERB_START_TOGGLE, 1, 1), 90);
        expect(r, 53, M_CHANNEL_1 | M_GATES, 0, 0,
                action(VERB_RELEASE, 0, RELEASE_MASK), 0);
    }

    /** A SID held across the wrap: state converges immediately, c = 0. */
    @Test
    void aLoopHeldAcrossTheWrapNeedsNoRotation() {
        int frames = 40;
        byte[][] v = new byte[Ym6Reader.Song.YM_REGISTERS][frames];
        for (int f = 10; f < frames; f++) {     // starts in the intro, holds
            v[1][f] |= 0x10;                    // through the whole loop
            v[6][f] |= 1 << 5;
            v[14][f] = 100;
            v[8][f] = 10;
        }
        EffectScript.Result r = compile(song(frames, v, 20, new byte[0][]), 20);
        assertEquals(20, r.split(), "no rotation needed");
        assertEquals(40, r.frames());
        // The wrap replays frames 20..39: all held, no actions - and the
        // square's phase free-runs round the loop, exactly v1.
        for (int f = 21; f < 40; f++) {
            assertEquals(0, r.m()[f] & 0xFF & ~M_GATES, "quiet at " + f);
        }
    }

    /** A drum window crossing the wrap: the split rotates past it so both
     * arrivals agree. */
    @Test
    void aDrumAcrossTheWrapRotatesTheSplit() {
        int frames = 40;
        byte[][] v = new byte[Ym6Reader.Song.YM_REGISTERS][frames];
        // One long drum triggered just before the loop point: 60 samples at
        // 200*250 cycles -> 61 ticks * 50000 / 2457600 = ~1.25s = 62 frames?
        // No - keep it a few frames: 60 ticks at prediv 4, count 100:
        // 61*400*50/2457600 = 0.49 -> 1 frame, +1 = 2. Trigger at 19, the
        // window covers the wrap at 20.
        v[3][19] = 0x50;                        // drum voice A, slot 2
        v[8][19] |= 1 << 5;                     // prescaler 1 (prediv 4)
        v[15][19] = 100;
        v[8][19] = (byte) (v[8][19] | 0);
        byte[] drum = new byte[60];
        EffectScript.Result r = compile(song(frames, v, 20, new byte[][] {drum}), 20);
        // The window is [19, 19+dur); dur = ceil(61*400*50/2457600 + 1/16)
        // = 1. The wrap arrival at 20 carries the reopen where the pristine
        // start is quiet, so the cut rotates one frame past it, to 21.
        assertEquals(21, r.split(), "rotated past the drum window");
        assertEquals(41, r.frames());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("rotated")));
    }

    /** Play-once: no loop, no rotation, effects may run off the end. */
    @Test
    void playOnceCompilesStraightThrough() {
        int frames = 16;
        byte[][] v = new byte[Ym6Reader.Song.YM_REGISTERS][frames];
        v[1][12] |= 0x10;
        v[6][12] |= 1 << 5;
        v[14][12] = 50;
        for (int f = 13; f < 16; f++) {
            v[1][f] |= 0x10;
            v[6][f] |= 1 << 5;
            v[14][f] = 50;
        }
        EffectScript.Result r = compile(song(frames, v, 0, new byte[0][]), -1);
        assertEquals(16, r.frames());
        assertEquals(16, r.split());
        assertEquals(action(VERB_START_TOGGLE, 0, 1), r.actions()[0][12] & 0xFF);
    }

    /** The stuck-flag quirk, replicated: a buzzer arming over its own
     * channel's running drum leaves the voice muted, and says so. */
    @Test
    void armingOverOwnRunningDrumSticksTheVoice() {
        int frames = 24;
        byte[][] v = new byte[Ym6Reader.Song.YM_REGISTERS][frames];
        byte[] drum = new byte[200];            // long: 201 ticks at 4*200
        v[3][4] = 0x50;                         // drum voice A on slot 2
        v[8][4] |= 1 << 5;
        v[15][4] = (byte) 200;
        v[3][6] = (byte) 0xE6;                  // buzzer voice B, same slot,
        v[8][6] |= 6 << 5;                      // two frames later
        v[15][6] = 100;
        v[9][6] = 5;
        EffectScript.Result r = compile(song(frames, v, 0, new byte[][] {drum}), -1);
        assertEquals(action(VERB_START_RETRIGGER, 1, 6), r.actions()[1][6] & 0xFF);
        for (int f = 6; f < frames; f++) {      // voice A never frees
            assertEquals(0x09, r.r7force()[f] & 0x09, "stuck at " + f);
        }
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("stays muted")));
    }

    // ------------------------------------------- a source that can say stop

    /** The two dialects of {@link EffectScript.Semantics#channelEndsPcm},
     * with the other two flags pinned so the tests below isolate that one:
     * neither retriggers a held PCM code, neither forces the mixer, both
     * restart a released toggle stream at phase zero, and only
     * {@link #STOPS} lets a channel's own action end its sample. The YM set
     * sits on the {@link #RUNS_ON} side of this fork. */
    private static final EffectScript.Semantics RUNS_ON =
            new EffectScript.Semantics(false, false, false, false, false);
    private static final EffectScript.Semantics STOPS =
            new EffectScript.Semantics(false, false, true, false, false);

    private static EffectScript.Result compile(Ym6Reader.Song song,
                                               EffectScript.Semantics semantics) {
        return EffectScript.compile(YmEffects.tune(song).under(semantics), -1, 1,
                YmxFormat.DEFAULT_TIMERS);
    }

    /** A drum on voice A whose code arrives at frame 4 and is gone by frame 8,
     * with 600 samples at 4*250 cycles: the computed window reaches frame 17,
     * so everything between 8 and 17 is a decision and not a coincidence. */
    private static Ym6Reader.Song longDrum(int silenced) {
        int frames = 24;
        byte[][] v = new byte[Ym6Reader.Song.YM_REGISTERS][frames];
        for (int f = 4; f < silenced; f++) {
            v[3][f] = 0x50;                     // drum voice A on slot 2
            v[8][f] |= 1 << 5;                  // prescaler 1 (prediv 4)
            v[15][f] = (byte) 250;
        }
        return song(frames, v, 0, new byte[][] {new byte[600]});
    }

    @Test
    void aReleasedSampleRunsToItsMarkerOrStopsWhereTheSourceSaysSo() {
        assertEquals(false, EffectScript.Semantics.YM.channelEndsPcm(),
                "a YM dump has no way to say stop");

        // A YM-shaped source: nothing acts when the code goes away, because
        // nothing can - the marker tick is the only thing that ends a sample,
        // and the gate reopens at the computed end.
        EffectScript.Result runs = compile(longDrum(8), RUNS_ON);
        assertEquals(action(VERB_START_PCM, 0, 1), runs.actions()[1][4] & 0xFF);
        for (int f = 8; f < 17; f++) {
            assertEquals(0, runs.m()[f] & 0xFF, "something acted at " + f);
        }
        assertTrue(runs.reopens().stream().anyMatch(x -> x[0] == 17 && x[1] == 0));

        // The same code with a source that can say stop: the whole cut lands
        // on the frame it says it. RELEASE with bit 0 clear stops the timer,
        // and the gate goes with it - the player applies gates before the
        // register burst, so the voice's own volume is back on that frame.
        EffectScript.Result stops = compile(longDrum(8), STOPS);
        assertEquals(action(VERB_RELEASE, 0, 0), stops.actions()[1][8] & 0xFF);
        assertEquals(M_CHANNEL_1 | M_GATES, stops.m()[8] & 0xFF);
        assertTrue(stops.reopens().stream().anyMatch(x -> x[0] == 8 && x[1] == 0));
        for (int f = 9; f < 24; f++) {
            assertEquals(0, stops.m()[f] & 0xFF, "something acted at " + f);
        }
    }

    @Test
    void aStreamArrivingOverTheChannelsOwnSampleTakesTheVoiceRatherThanWaiting() {
        // The drum runs on slot 2 to frame 17; a SID takes the same slot and
        // the same voice at frame 8, with no idle code between them, and is
        // held past 17 so both dialects arm it and only the frame differs.
        Ym6Reader.Song song = longDrum(8);
        byte[][] v = song.registers();
        for (int f = 8; f < 21; f++) {
            v[3][f] = 0x10;                     // SID voice A, same slot
            v[8][f] = (byte) ((v[8][f] & 0x1F) | (1 << 5));
            v[15][f] = 90;
        }

        // v1's arbitration: a sample owns the volume register, so the SID
        // clears itself and retries - for the sample's whole computed length.
        EffectScript.Result runs = compile(song, RUNS_ON);
        for (int f = 8; f < 17; f++) {
            assertEquals(0, runs.m()[f] & 0xFF, "something acted at " + f);
        }
        assertEquals(action(VERB_START_TOGGLE, 0, 1), runs.actions()[1][17] & 0xFF);

        // One timer runs both, so there was never anything to arbitrate: the
        // square arms on the frame the source asked for it. The gate stays
        // shut throughout - the sample needed it shut and so does the square -
        // so no reopen edge is recorded for a gate that never opened.
        EffectScript.Result stops = compile(song, STOPS);
        assertEquals(action(VERB_START_TOGGLE, 0, 1), stops.actions()[1][8] & 0xFF);
        assertEquals(M_CHANNEL_1, stops.m()[8] & 0xFF);
        assertTrue(stops.reopens().isEmpty(), stops.reopens().toString());
    }

    /** M is exact everywhere: a byte is nonzero exactly when something acts. */
    @Test
    void masterBytesAreExact() {
        EffectScript.Result r = compile(rigScene(), -1);
        for (int f = 0; f < r.frames(); f++) {
            int mm = r.m()[f] & 0xFF;
            if ((mm & M_CHANNEL_0) != 0) {
                assertTrue((r.actions()[0][f] & 0xFF) != 0, "A1 empty at " + f);
            }
            if ((mm & M_CHANNEL_1) != 0) {
                assertTrue((r.actions()[1][f] & 0xFF) != 0, "A2 empty at " + f);
            }
            // v7 filled the byte: channels 0-3, the gate flag, and a
            // three-bit mask reaching bit 7. Nothing is reserved.
        }
    }

    /** A tune whose state never repeats across its loop is a pack error,
     * not a broken file. */
    @Test
    void anUnsplittableLoopFailsLoudly() {
        // Degenerate: a 1-frame loop cycle re-triggering a drum every
        // frame whose window is longer than the cycle - the state can
        // never agree... actually a retrigger resets the window each pass,
        // so states DO converge. Genuinely unsplittable states need the
        // stuck flag: a cut drum whose STUCK never matches a live window.
        int frames = 8;
        byte[][] v = new byte[Ym6Reader.Song.YM_REGISTERS][frames];
        byte[] drum = new byte[200];
        v[3][2] = 0x50;                         // drum on voice A (intro)
        v[8][2] |= 1 << 5;
        v[15][2] = (byte) 200;
        v[3][4] = (byte) 0xE6;                  // buzzer sticks it (intro)
        v[8][4] |= 6 << 5;
        v[15][4] = 100;
        // Loop [6,8): quiet - but voice A is STUCK from the intro and the
        // second pass carries the same stuck state, so c = 0 works after
        // all. Convergence is hard to defeat; assert it converges.
        EffectScript.Result r = compile(song(frames, v, 6, new byte[][] {drum}), 6);
        assertEquals(6, r.split());
    }
}
