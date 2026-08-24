package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ym6.Ym6Reader;
import org.ym6.YmEffects;

/**
 * {@link LoopFrame} on the paths a real tune does not reach.
 *
 * <p>Of the tunes in {@code ym/test} some keep the frame their header
 * gives and some fall back, so those two are covered by the pinned
 * corpus. The advance, the empty budget and the ring that cannot hold
 * the body are reached here instead, from tunes built for each.
 */
final class LoopFrameTest {

    private static final int FRAMES = 200;
    private static final int RATE = 50;

    /** A tune with no effect script: registers alone, R13 held at the
     * do-not-write marker except on the frames listed. Voice A follows the
     * envelope throughout, so the frames R13 is written on are exactly the
     * frames the tune can start over from. */
    private static Tune plain(int loopFrame, int... writesR13) {
        return plainOfLength(FRAMES, loopFrame, writesR13);
    }

    /** The same, of a chosen length: a tune long enough that no ring the
     * format allows reaches back over its body. */
    private static Tune plainOfLength(int frames, int loopFrame, int... writesR13) {
        byte[][] registers = new byte[YmxFormat.REGISTER_STREAMS][frames];
        for (int f = 0; f < frames; f++) {
            registers[13][f] = (byte) 0xFF;
            registers[8][f] = 0x10;             // voice A follows the envelope
        }
        for (int f : writesR13) {
            registers[13][f] = 0x0A;
        }
        byte[][] codes = new byte[YmxFormat.CHANNELS][frames];
        byte[][] counts = new byte[YmxFormat.CHANNELS][frames];
        return new Tune(frames, RATE, 2000000L, true, loopFrame, registers,
                codes, counts, new byte[frames], new byte[0][], new int[0],
                EffectScript.Semantics.YM, "", "", "", List.of());
    }

    /** A tune whose body is past the largest ring, starting over at 101 or
     * 103 - the two frames it can be entered at. */
    private static Tune longTune() {
        return plainOfLength(3000, 100, 101, 103);
    }

    private static LoopFrame.Plan resolve(Tune tune, int ringSize) {
        return resolve(tune, ringSize, 1);
    }

    /** The same, packing at {@code unit} bytes a unit: a cut falls on one. */
    private static LoopFrame.Plan resolve(Tune tune, int ringSize, int unit) {
        return LoopFrame.resolve(tune, EffectScript.compile(tune), true,
                ringSize, 24, unit);
    }

    /** A voice hears the envelope at the frame the source gives, so the
     * repeat moves to the frame that sets the shape. */
    @Test
    void theRepeatAdvancesToAFrameItCanEnter() {
        LoopFrame.Plan plan = resolve(plain(100, 130), 960);
        assertEquals(130, plan.frame(), "the frame the file carries");
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("130")),
                "the advance is reported: " + plan.notes());
    }

    /** Nothing within the budget can be entered, so the tune starts over
     * from its first frame and says so. */
    @Test
    void anEmptyBudgetFallsBackToTheBeginning() {
        LoopFrame.Plan plan = resolve(plain(100), 960);
        assertEquals(0, plan.frame(), "no frame qualifies");
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("100")),
                "the frame the source gave is reported: " + plan.notes());
    }

    /** The budget is a second of frames, so a frame past it is out of
     * reach even though it could be entered. */
    @Test
    void theBudgetIsASecondOfFrames() {
        assertEquals(RATE, LoopFrame.budget(RATE));
        assertEquals(0, resolve(plain(100, 100 + RATE + 1), 960).frame(),
                "one frame past the budget is out of reach");
        assertEquals(100 + RATE, resolve(plain(100, 100 + RATE), 960).frame(),
                "the last frame of the budget is within it");
    }

    /** A body larger than the ring raises the ring to hold it. */
    @Test
    void aBodyPastTheRingRaisesIt() throws IOException {
        Tune tune = source("Turrican - world 4-3").startingOverAt(160);
        LoopFrame.Plan plan = resolve(tune, 960);
        assertEquals(160, plan.frame(), "the frame survives");
        assertTrue(plan.ringSize() >= tune.frames() - 160,
                "the ring holds the body: " + plan.ringSize());
        assertEquals(0, plan.ringSize() % 24, "the ring is a whole number of chunks");
        assertTrue(plan.ringSize() <= YmxFormat.MAX_RING_SIZE, "within the cap");
    }

    /** A body past the largest ring is replayed out of a second section per
     * stream instead, cut at the frame the file carries. */
    @Test
    void aBodyPastTheLargestRingIsCutInTwo() {
        LoopFrame.Plan plan = resolve(longTune(), 960);
        assertEquals(101, plan.frame(), "the frame survives the cut");
        assertTrue(plan.cut(), "the streams are cut at it");
        assertEquals(960, plan.ringSize(), "a cut leaves the ring where it was");
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("two sections")),
                "the cut is reported: " + plan.notes());
    }

    /** Each of the two sections is a whole number of units, so a cut at a
     * unit size above 1 falls on one. Frame 101 cannot be cut at, and the
     * next frame that can be entered is 103, which cannot either. */
    @Test
    void aCutWithNoUnitBoundaryToFallOnFallsBack() {
        LoopFrame.Plan plan = resolve(longTune(), 960, 2);
        assertEquals(0, plan.frame(), "no frame the tune can be cut at");
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("2-byte unit")),
                "the unit the cut needs is reported: " + plan.notes());
    }

    /** With a frame on a unit boundary in reach, the cut moves to it. */
    @Test
    void aCutMovesToAUnitBoundary() {
        LoopFrame.Plan plan = resolve(plainOfLength(3000, 100, 101, 104), 960, 4);
        assertEquals(104, plan.frame(), "the first frame a section can end on");
        assertTrue(plan.cut(), "the streams are cut at it");
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("4-byte units")),
                "the move is reported: " + plan.notes());
    }

    /** A tune that plays once carries no frame, whatever it was given. */
    @Test
    void aTuneThatPlaysOnceCarriesNoFrame() {
        Tune tune = plain(100, 100);
        LoopFrame.Plan plan = LoopFrame.resolve(tune, EffectScript.compile(tune),
                false, 960, 24, 1);
        assertEquals(0, plan.frame());
        assertTrue(plan.notes().isEmpty(), "nothing to report: " + plan.notes());
    }

    private static Tune source(String stem) throws IOException {
        return YmEffects.tune(Ym6Reader.read(
                Files.readAllBytes(Path.of("ym", "test", stem + ".ym"))));
    }
}
