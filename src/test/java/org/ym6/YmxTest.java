package org.ym6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ymx.Tune;
import org.ymx.YmxFormat;

/**
 * The .ym packer's own decisions, as far as they can be made without a
 * command line: which frame a YM dump may have duplicated when its shape has
 * to be padded to a unit boundary.
 *
 * <p>The stretching itself is {@link Tune#padToUnit}'s and is tested through
 * the engine. What is tested here is the half only this front end can supply -
 * the predicate that reads a RAW YM dump and says a frame is safe to hold a
 * tick longer - and that the two fit together.
 */
final class YmxTest {

    private static final int FRAMES = 1500;

    private static Ym6Reader.Song song() {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        return Ym6Reader.read(Ym6TestData.file(registers, FRAMES, true));
    }

    @Test
    void padsOddShapesWithSafeDuplicateFrames() {
        Ym6Reader.Song dump = song();               // an even-shaped tune:
        Tune source = YmEffects.tune(dump);
        assertSame(source, Ymx.padToUnit(dump, source, 200, 2));  // nothing to do

        // An odd loop split: one duplicated frame evens it, and the whole
        // tune grows by one more to keep the length even too.
        Tune padded = Ymx.padToUnit(dump, source, 201, 2);
        assertNotNull(padded);
        assertEquals(source.frames() + 2, padded.frames());
        assertEquals(202, padded.loopFrame());
        // Every stream grew by the same two frames, because a frame is a
        // column across all of them and the effects would otherwise play
        // against the wrong registers from the duplicate on.
        for (byte[] codes : padded.codes()) {
            assertEquals(padded.frames(), codes.length);
        }
        // The intro gained a duplicate near the split: frame content around
        // it is a copy, and everything before is untouched.
        for (int r = 0; r < YmxFormat.REGISTER_STREAMS; r++) {
            assertEquals(source.registers()[r][0], padded.registers()[r][0]);
        }
    }

    @Test
    void givesUpOnAShapeWithNoSafeFrameNearTheBoundary() {
        // Every frame writes R13, so no frame may be duplicated: an envelope
        // restarted twice is audible, the whole of the predicate.
        byte[][] registers = Ym6TestData.registers(FRAMES);
        for (int frame = 0; frame < FRAMES; frame++) {
            registers[13][frame] = 0x0A;
        }
        Ym6Reader.Song dump = Ym6Reader.read(Ym6TestData.file(registers, FRAMES, true));

        assertNull(Ymx.padToUnit(dump, YmEffects.tune(dump), 201, 2),
                "no safe frame near the split, so the caller has to fall back to -k1");
    }

    // ------------------------------------------------------- the command line

    /**
     * The CLI's own arithmetic, which nothing exercised before: trimming,
     * the loop-frame override, and what each does to a tune whose numbers do
     * not fit. These run {@link Ymx#main} rather than reaching past it,
     * because the bugs they are here for lived in the order its steps run in
     * rather than in any one of them.
     */
    private static String pack(@TempDir Path dir, String name, String... options)
            throws Exception {
        Path input = dir.resolve("tune.ym");
        if (!Files.exists(input)) {
            Files.write(input, Ym6TestData.file(Ym6TestData.registers(FRAMES),
                    FRAMES, true));
        }
        String[] argv = new String[options.length + 3];
        argv[0] = "-f";
        System.arraycopy(options, 0, argv, 1, options.length);
        argv[options.length + 1] = input.toString();
        argv[options.length + 2] = dir.resolve(name).toString();

        PrintStream out = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.ISO_8859_1));
        try {
            Ymx.main(argv);
        } finally {
            System.setOut(out);
        }
        return captured.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void aLoopFrameOffTheEndOfTheTuneIsRefusedRatherThanReachedFor(@TempDir Path dir)
            throws Exception {
        // The padding probes the frame BEFORE the split, so a -lF past the end
        // used to walk off the register array with an index out of bounds. An
        // ODD one is the case that did it: an even one leaves no split padding
        // to do and reaches the encoder's own complaint instead.
        String report = pack(dir, "far.ymx", "-l" + (FRAMES + 501));

        assertTrue(report.contains("looping from the start instead"), report);
        assertTrue(Files.exists(dir.resolve("far.ymx")), "it should still pack");
    }

    @Test
    void aTrimWindowMovesTheLoopFrameWithIt(@TempDir Path dir) throws Exception {
        String report = pack(dir, "cut.ymx", "-startframe300", "-frames500");

        assertTrue(report.contains("Trimmed to frames 300-799: 500 frames"), report);
        assertTrue(Files.exists(dir.resolve("cut.ymx")));
    }

    @Test
    void zeroIsARealAnswerForATrimAndAnEmptyWindowSaysSo(@TempDir Path dir)
            throws Exception {
        // -min0 -sec13 is how a caller says thirteen seconds in, and it used
        // to be refused as an invalid value: the parser took zero for every
        // option to mean nonsense, which it does for a ring or a unit and
        // does not here.
        String report = pack(dir, "zero.ymx", "-min0", "-sec2");
        assertTrue(report.contains("Trimmed to frames 100-"), report);

        // The other half of the fix cannot be asserted from here: a window
        // that comes out empty is still refused, but the refusal goes through
        // this CLI's error(), which prints and calls System.exit - so a test
        // that provoked it would take the JVM with it rather than fail. What
        // it now says is "Empty trim window: frames 0..0 of 900" instead of
        // "Invalid parameter value 0", the point of moving the check
        // off the parser, and it is checked by hand.
        //
        // The same exit is why a regression here does not read as a failed
        // assertion: putting -min back on the strict parser makes this test
        // exit the forked JVM, and surefire reports "The forked VM terminated
        // without properly saying goodbye" over a BUILD FAILURE. The build
        // still goes red, which is what matters, but the message names the
        // symptom rather than the test - so it is written down here for
        // whoever meets it.
    }

    @Test
    void theReportCountsTheBytesTheFileActuallyCarries(@TempDir Path dir)
            throws Exception {
        // A rotated split hands the file some frames twice. The tune's length
        // is what a musician has and stays in the first line; the ratio has to
        // be against what was packed, or it flatters itself by the rotation.
        String report = pack(dir, "rot.ymx", "-l401");

        int played = Integer.parseInt(report.replaceAll("(?s).*Packed (\\d+) register.*",
                "$1")) / YmxFormat.STREAMS;
        byte[] file = Files.readAllBytes(dir.resolve("rot.ymx"));
        int header = ((file[8] & 0xFF) << 24) | ((file[9] & 0xFF) << 16)
                | ((file[10] & 0xFF) << 8) | (file[11] & 0xFF);
        assertEquals(header, played, "the report and the header must agree on O");
    }
}
