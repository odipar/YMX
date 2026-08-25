package org.ymx.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * A smoke slice of the emulator rig, so {@code mvn test} catches bit-rot in
 * the binding and the harness on any machine that carries rmac and
 * libunicorn. The whole battery is {@code ymx/test/rig.sh}; the sweeps are
 * {@code ymx/test/sweep.sh}.
 */
final class RigSmokeTest {

    private static void assumeRig() throws IOException, InterruptedException {
        assumeTrue(Unicorn.available(), "libunicorn is not on this machine");
        assumeTrue(new ProcessBuilder("rmac", "-?").start().waitFor() >= 0,
                "rmac is not on the PATH");
    }

    @Test
    void oneShapePlaysThroughTheEmulatedPlayer()
            throws IOException, InterruptedException {
        assumeRig();
        assertEquals("", PlayerTests.runShape(120, 960, 24, "smoke", true, 1, 1));
    }
}
