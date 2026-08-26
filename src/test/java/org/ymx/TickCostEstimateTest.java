package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The raster monitor's per-tick cost estimates, against what a tick was
 * measured to cost.
 *
 * <p>The {@code -perf} player adds a fixed number of 10-cycle quanta to an
 * accumulator on every tick and burns the total as a yellow bar, so the
 * bar is only as truthful as those constants. {@code doc/performance.md}
 * states what each tick measured, taken with {@code ymx/test/cost.sh}
 * under a cycle-exact Hatari, and this holds the constants to those
 * figures.
 *
 * <p>The rig's effect stage checks the accumulator's arithmetic - that a
 * drum playout adds its three ticks - and passed while the PCM constant
 * was a sixth too high, because arithmetic over a wrong constant is still
 * consistent. That is the hole this closes: a constant changed here fails
 * until the measurement behind it is taken again.
 */
final class TickCostEstimateTest {

    /** Each tick's quanta, and the cycles doc/performance.md measured. */
    private static final int[][] ESTIMATES = {
            // quanta, measured cycles, the ratio's numerator and denominator
            {18, 164},          // a PCM tick
            {15, 132},          // a toggle tick
            {12, 108},          // a retrigger tick
    };

    @Test
    void theEstimatesAreTheOnesTheMeasurementSupports() throws IOException {
        List<Integer> found = new ArrayList<>();
        Matcher add = Pattern.compile("add\\.w\\s+#(\\d+),\\(a0\\)")
                .matcher(Files.readString(Path.of("68k", "YMX.S")));
        while (add.find()) {
            found.add(Integer.parseInt(add.group(1)));
        }
        assertEquals(List.of(18, 20, 20, 15, 15, 12), found,
                "68k/YMX.S's tick cost estimates have moved. They are the"
                        + " raster monitor's, measured in doc/performance.md:"
                        + " a PCM tick 18 quanta and its end tick 20, a toggle"
                        + " 15, a retrigger 12. Move them only with a fresh"
                        + " ymx/test/cost.sh run behind the change");

        // Each estimate stands a little above its measurement, because the
        // measured span excludes the exception entry and the rte that a
        // tick also costs. What matters is that one kind is not weighted
        // against another: the ratios stay within a tenth of each other.
        double lowest = Double.MAX_VALUE;
        double highest = 0;
        for (int[] estimate : ESTIMATES) {
            double ratio = estimate[0] * 10.0 / estimate[1];
            lowest = Math.min(lowest, ratio);
            highest = Math.max(highest, ratio);
        }
        assertTrue(highest - lowest < 0.10, "the tick estimates weigh one"
                + " kind against another: their claimed-over-measured ratios"
                + " run from " + String.format("%.2f", lowest) + " to "
                + String.format("%.2f", highest) + ", so the yellow bar"
                + " over-reports whichever tune runs the dearest kind");
    }
}
