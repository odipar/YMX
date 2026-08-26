package org.ym6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * What {@link Packing#quietly} keeps and what it drops.
 *
 * <p>It dropped nothing at all for a long time, and no test said so: the
 * suppression was only ever read off a screen, and the packer's other output
 * looked right. The stream it installed had autoflush on, which flushes the
 * sink after every byte array written and tests for no newline, and a printf
 * arrives one piece of the format at a time - so every table line reached the
 * filter in fragments, each one already out before the newline that would have
 * matched it. These pack the line through the real printf that shredded it.
 */
final class PackingTest {

    /** One table line, printed the way the packer prints it. */
    private static void printTableLine() {
        System.out.printf(Locale.ROOT, "  %s %6d -> %6d bytes (%5.1f%%)%n",
                "R0 ", 5378, 604, 11.2);
    }

    /** What quietly let through while the given output was printed. */
    private static String through(Runnable printing) {
        PrintStream original = System.out;
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        System.setOut(new PrintStream(kept, true, StandardCharsets.ISO_8859_1));
        try {
            Packing.quietly(printing);
        } finally {
            System.out.flush();
            System.setOut(original);
        }
        return kept.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void aTableLineDoesNotSurviveThePrintfThatFormatsIt() {
        String kept = through(PackingTest::printTableLine);
        assertEquals("", kept, "a per-stream line reached the caller's output");
    }

    @Test
    void everyOtherLineSurvives() {
        String kept = through(() -> {
            System.out.println("YMX: YM chiptune packer, streaming ST4");
            printTableLine();
            System.out.printf(Locale.ROOT,
                    "Packed %d register bytes into %d (%.1f%%), file %d bytes%n",
                    134450, 7844, 5.8, 7988);
        });
        assertTrue(kept.contains("YMX: YM chiptune packer"),
                "the banner was dropped: " + kept);
        assertTrue(kept.contains("Packed 134450 register bytes"),
                "the summary was dropped: " + kept);
        assertFalse(kept.contains("bytes ( 11.2%)"),
                "a per-stream line survived: " + kept);
        assertEquals(2, kept.lines().count(), "kept: " + kept);
    }

    /**
     * The progress meter draws with carriage returns and no newline, and
     * flushes for itself. A filter that held every unfinished line would stop
     * it animating; one that forwarded every unfinished line would let the
     * fragments of a table line past.
     */
    @Test
    void theMeterDrawsAndTheTableStillDoesNot() {
        String kept = through(() -> {
            for (int percent = 0; percent < 3; percent++) {
                System.out.print("\r[" + percent + "%]");
                System.out.flush();
            }
            System.out.println();
            printTableLine();
        });
        assertTrue(kept.contains("[0%]") && kept.contains("[2%]"),
                "the meter did not draw: " + kept);
        assertFalse(kept.contains("bytes ( 11.2%)"),
                "a per-stream line survived beside the meter: " + kept);
    }

    /** Text with no closing newline is still the caller's output. */
    @Test
    void aLineWithNoNewlineIsNotLost() {
        assertEquals("no newline here",
                through(() -> System.out.print("no newline here")));
    }
}
