package org.ymx.rig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.ymx.YmxFormat;

/**
 * The §9.3 reader against a file whose bytes have been changed.
 *
 * <p>A reader that stops the run on one damaged file reports nothing about
 * that file, and nothing about every file after it. Three inputs did exactly
 * that, and none of the batteries running until then reached any of them: a
 * negative sample-table offset, which the bounds test missed; a loop table
 * lying outside the file, whose entries were read unchecked; and a malformed
 * container, which throws an {@code AssertionError} the catch did not name.
 * Each was one changed byte of a packed tune.
 *
 * <p>{@code ymx/test/damage.sh} runs the same files through all three trees
 * and compares what they report. This covers the Java reader alone, and runs
 * wherever the tests do.
 */
final class DamagedFileTest {

    private static final Path REPO = Path.of(System.getProperty("ymx.repo", "."));

    /** The values one byte is changed by, each reaching a different bit. */
    private static final int[] DELTA = {0x01, 0x80, 0xFF};

    private static byte[] tune() throws IOException {
        return Files.readAllBytes(REPO.resolve(
                "doc/conformance/tunes/plain_packed.ymx"));
    }

    /**
     * The bytes to change: every header byte, and a spread of four hundred
     * through the body. A body read whole would take a mutant per byte, and
     * the faults come in runs long enough that a spread reaches each of them.
     */
    private static int[] spots(int length) {
        List<Integer> spots = new ArrayList<>();
        for (int at = 0; at < YmxFormat.HEADER_SIZE; at++) {
            spots.add(at);
        }
        int step = Math.max(1, length / 400);
        for (int at = YmxFormat.HEADER_SIZE; at < length; at += step) {
            spots.add(at);
        }
        return spots.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void aDamagedFileIsReportedRatherThanEndingTheRun() throws IOException {
        byte[] original = tune();
        int read = 0;
        var rules = new TreeSet<String>();
        for (int at : spots(original.length)) {
            for (int delta : DELTA) {
                byte[] damaged = original.clone();
                damaged[at] ^= (byte) delta;
                List<Check.Fault> faults;
                try {
                    faults = Check.check(damaged);
                } catch (Throwable thrown) {
                    fail(String.format(
                            "byte %d changed by $%02X ended the run: %s",
                            at, delta, thrown));
                    return;
                }
                read++;
                for (Check.Fault fault : faults) {
                    rules.add(fault.rule());
                }
            }
        }
        assertTrue(read > 1000, "only " + read + " files were read");
        // A sweep that reports one rule is a sweep that reached one path.
        assertTrue(rules.size() >= 8,
                "the damaged files reached only these rules: " + rules);
        assertTrue(rules.contains("§1.4 section"),
                "no damaged file made a section fail to decode, so the catch"
                + " around it was never taken: " + rules);
    }

    /**
     * A sample table at a negative offset. The bounds test read
     * {@code at + 8 > file.length}, which a negative {@code at} passes, and
     * the read threw rather than reporting.
     */
    @Test
    void aSampleTableOutsideTheFileIsAFault() throws IOException {
        byte[] damaged = tune();
        putLong(damaged, YmxFormat.OFFSET_SAMPLE_TABLE, 0x8000_0000);
        assertReports(damaged, "§6 sample table");
    }

    /**
     * A sample table so near the int ceiling that the offset of its first
     * entry plus the entry size wraps negative. The guard read
     * {@code at + 8 > file.length}, which the wrapped value passes.
     */
    @Test
    void aSampleTableNearTheIntCeilingIsAFault() throws IOException {
        byte[] damaged = tune();
        putLong(damaged, YmxFormat.OFFSET_SAMPLE_TABLE, 0x7FFF_FFF8);
        assertReports(damaged, "§6 sample table");
    }

    /**
     * A loop table past the file's end. Its entries were read with no check
     * that the table lies inside the file.
     */
    @Test
    void aLoopTableOutsideTheFileIsAFault() throws IOException {
        byte[] damaged = tune();
        putLong(damaged, YmxFormat.OFFSET_LOOP_TABLE, 0x7FFF_FFFC);
        assertReports(damaged, "outside the file at");
    }

    /** The reader returns a fault whose text carries {@code wanted}. */
    private static void assertReports(byte[] damaged, String wanted) {
        List<Check.Fault> faults;
        try {
            faults = Check.check(damaged);
        } catch (Throwable thrown) {
            fail("the read ended the run rather than reporting: " + thrown);
            return;
        }
        assertFalse(faults.isEmpty(), "the damaged file was read as sound");
        boolean found = faults.stream()
                .anyMatch(fault -> fault.toString().contains(wanted));
        assertTrue(found, "no fault names " + wanted + ": " + faults);
    }

    private static void putLong(byte[] file, int at, int value) {
        file[at] = (byte) (value >> 24);
        file[at + 1] = (byte) (value >> 16);
        file[at + 2] = (byte) (value >> 8);
        file[at + 3] = (byte) value;
    }
}
