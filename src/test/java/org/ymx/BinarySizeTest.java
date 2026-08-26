package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The sizes the documents state, read back off the binaries they describe.
 * doc/performance.md's memory table carried the stub at 2,992 bytes for as
 * long as nothing measured it, while the assembled stub had moved on.
 */
class BinarySizeTest {

    /** The stub's row in the memory table is the stub's own size. */
    @Test
    void theMemoryTableStatesTheStubsSize() throws IOException {
        assertEquals(built("ymxprg"), stated("PRG stub"),
                "doc/performance.md's PRG stub row and the assembled stub");
    }

    /** And the player's row is the unit-2 core's player, as the README says. */
    @Test
    void theMemoryTableStatesThePlayersSize() throws IOException {
        int stated = stated("player, unit size 2");
        assertTrue(stated > 3000 && stated < 4000,
                "the player row reads " + stated + " bytes");
        String readme = Files.readString(Path.of("README.md"));
        assertTrue(readme.contains(String.format(java.util.Locale.ROOT,
                        "%,d bytes", stated)),
                "README.md does not state the player's " + stated + " bytes");
    }

    /** A row of the memory table, by its label. */
    private static int stated(String row) throws IOException {
        String text = Files.readString(Path.of("doc", "performance.md"));
        Matcher m = Pattern.compile("\\| " + Pattern.quote(row)
                + " \\| ([0-9,]+) \\|").matcher(text);
        assertTrue(m.find(), "doc/performance.md has no row " + row);
        return Integer.parseInt(m.group(1).replace(",", ""));
    }

    /** A binary in dist/, assembled if it is not there yet. */
    private static int built(String stem) throws IOException {
        Path dist = Tools.repo().resolve("dist");
        Path bin = dist.resolve(stem + Tools.binarySuffix() + ".bin");
        if (!Files.exists(bin)) {
            MkCores.stub(dist);
        }
        return (int) Files.size(bin);
    }
}
