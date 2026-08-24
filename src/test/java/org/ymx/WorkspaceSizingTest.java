package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The workspace a host reserves against the ring size a file carries.
 *
 * <p>A pass that fits a ring costs no file bytes, so the packer raises
 * {@code N} above the ring size it was asked for to hold one ({@link
 * LoopFrame}). A host that reserved for the size it packed with is then
 * short by twenty-five times the difference, which nothing checks at run
 * time, so the sizing rule the sources state is held here against a header
 * the packer wrote: the run-time form reads {@code N}, and the build-time
 * form reserves for the cap.
 *
 * <p>The numbers in the guidance are read back out of it rather than
 * repeated here: {@code YMX_FIXED} from the player's own equates, the cap
 * from {@link YmxFormat}.
 */
final class WorkspaceSizingTest {

    private static final Path PLAYER = Path.of("68k", "YMX.S");
    private static final int FRAMES = 1500;
    private static final int RATE = 50;
    private static final int LOOP = 100;

    /** A tune with no effect script, R13 held at the do-not-write marker
     * except on the one frame the wrap can enter, and voice A following the
     * envelope throughout - {@link LoopFrameTest}'s shape, long enough that
     * one pass is past a 960-byte ring. */
    private static Tune tune() {
        byte[][] registers = new byte[YmxFormat.REGISTER_STREAMS][FRAMES];
        for (int f = 0; f < FRAMES; f++) {
            registers[13][f] = (byte) 0xFF;
            registers[8][f] = 0x10;
        }
        registers[13][LOOP] = 0x0A;
        byte[][] codes = new byte[YmxFormat.CHANNELS][FRAMES];
        byte[][] counts = new byte[YmxFormat.CHANNELS][FRAMES];
        return new Tune(FRAMES, RATE, 2000000L, true, LOOP, registers,
                codes, counts, new byte[FRAMES], new byte[0][], new int[0],
                EffectScript.Semantics.YM, "", "", "", List.of());
    }

    /** The header's ring size, packed at {@code ringSize} bytes a ring. */
    private static int packedRing(int ringSize) {
        byte[] file = YmxEncoder.encode(tune(), ringSize, 24, true, false).file();
        return ((file[YmxFormat.OFFSET_RING_SIZE] & 0xFF) << 8)
                | (file[YmxFormat.OFFSET_RING_SIZE + 1] & 0xFF);
    }

    /**
     * The raise itself: a tune whose pass is past the ring asked for is
     * packed with a larger one, so the flag the file was packed with is not
     * the ring size its header gives.
     */
    @Test
    void thePackerRaisesTheRingAboveTheOneAskedFor() {
        int asked = YmxFormat.DEFAULT_RING_SIZE;
        int carried = packedRing(asked);
        assertTrue(carried > asked, "a pass of " + (FRAMES - LOOP) + " frames"
                + " needs more than " + asked + " bytes of ring; the header"
                + " carries " + carried);
        assertTrue(carried <= YmxFormat.MAX_RING_SIZE,
                "the header's ring size is past the format's cap: " + carried);
    }

    /**
     * The build-time form: the reservation the player's guidance states
     * covers the workspace the player lays out for that header, and the
     * reservation for the ring size asked for does not.
     */
    @Test
    void theDocumentedReservationCoversTheHeaderThePackerWrote()
            throws IOException {
        int fixed = fixedFromEquates();
        int asked = YmxFormat.DEFAULT_RING_SIZE;
        int carried = packedRing(asked);
        int laidOut = fixed + YmxFormat.STREAMS * carried;
        assertTrue(laidOut <= reserved(), "the player lays out " + laidOut
                + " bytes for a header of " + carried + ", past the "
                + reserved() + " the build-time reservation in " + PLAYER
                + " covers");
        assertTrue(fixed + YmxFormat.STREAMS * asked < laidOut,
                "a reservation for the ring size asked for is no longer"
                + " short, so the guidance may size from the flag again");
    }

    /**
     * The guidance's own numbers: the run-time formula's fixed part and
     * stream count are the player's equates, and the build-time reservation
     * is the format's cap with the byte count that follows from it.
     */
    @Test
    void theGuidanceNumbersReadBack() throws IOException {
        String source = Files.readString(PLAYER);
        Matcher formula = Pattern.compile(
                "YMX_SIZE = YMX_FIXED \\+ (\\d+) \\* N\\s+; (\\d+) \\+ \\1 \\* N")
                .matcher(source);
        assertTrue(formula.find(), PLAYER + " no longer carries the workspace"
                + " formula");
        assertEquals(YmxFormat.STREAMS, Integer.parseInt(formula.group(1)),
                "the formula's stream count");
        assertEquals(fixedFromEquates(), Integer.parseInt(formula.group(2)),
                "the formula's fixed part");
        assertEquals(YmxFormat.MAX_RING_SIZE, reservedRing(),
                "the build-time reservation reserves for the format's cap");
        assertEquals(fixedFromEquates()
                        + YmxFormat.STREAMS * YmxFormat.MAX_RING_SIZE,
                reserved(), "the build-time reservation's byte count");
    }

    /** The cap the tools document, against the format's own. */
    @Test
    void theToolsDocumentTheCapThePackerRaisesTo() throws IOException {
        String row = Files.readAllLines(Path.of("doc", "tools.md")).stream()
                .filter(line -> line.startsWith("| `-nN` |"))
                .findFirst().orElse("");
        assertTrue(row.contains("cap of " + YmxFormat.MAX_RING_SIZE),
                "doc/tools.md's -nN row no longer gives the cap "
                + YmxFormat.MAX_RING_SIZE + ": " + row);
    }

    /** The reservation's ring size, from the {@code ds.b} the guidance
     * states. */
    private static int reservedRing() throws IOException {
        return reservation()[0];
    }

    /** The byte count the reservation's own comment gives. */
    private static int reserved() throws IOException {
        return reservation()[1];
    }

    private static int[] reservation() throws IOException {
        Matcher line = Pattern.compile("ds\\.b\\s+YMX_FIXED\\+\\(YMX_STREAMS"
                + "\\*(\\d+)\\)(?:\\s+; (\\d+) bytes)?")
                .matcher(Files.readString(PLAYER));
        assertTrue(line.find(), PLAYER + " no longer carries the build-time"
                + " reservation");
        assertTrue(line.group(2) != null, PLAYER + "'s build-time reservation"
                + " no longer states the bytes it takes");
        return new int[] {Integer.parseInt(line.group(1)),
                Integer.parseInt(line.group(2))};
    }

    /** {@code YMX_FIXED}, computed from the player's plain-number equates
     * the way the player computes it. */
    private static int fixedFromEquates() throws IOException {
        String source = Files.readString(PLAYER);
        return equate(source, "YMX_STATE")
                + equate(source, "YMX_STREAMS") * equate(source, "YMX_STATE_SIZE");
    }

    private static int equate(String source, String name) {
        Matcher equ = Pattern.compile("(?m)^" + name + "\\s+equ\\s+(\\d+)\\s*(?:;.*)?$")
                .matcher(source);
        assertTrue(equ.find(), PLAYER + " no longer defines " + name);
        return Integer.parseInt(equ.group(1));
    }
}
