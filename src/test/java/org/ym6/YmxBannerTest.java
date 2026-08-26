package org.ym6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.ymx.YmxFormat;

/**
 * The packer's first line names a version, and it is this release's. A
 * version written into the line by hand said 1.0 for as long as nobody
 * ran the tool and read it, while doc/RELEASES.md called the project
 * short of what 1.0 states.
 */
class YmxBannerTest {

    /** Both trees read the release name rather than spelling one out. */
    @Test
    void neitherTreeWritesAVersionIntoTheBanner() throws IOException {
        String[][] sources = {
                {"src/main/java/org/ym6/Ymx.java", "YmxFormat.releaseName()"},
                {"dotnet/ym6/Ymx.cs", "YmxFormat.ReleaseName()"}};
        for (String[] source : sources) {
            String text = Files.readString(Path.of(source[0]));
            int at = text.indexOf("YM chiptune packer v");
            assertTrue(at >= 0, source[0] + " prints no banner");
            String line = text.substring(at, text.indexOf(';', at));
            assertTrue(line.contains(source[1]),
                    source[0] + " writes a version into the banner instead of"
                            + " reading " + source[1] + ": " + line);
        }
    }

    /** And the release name is the one the binaries carry. */
    @Test
    void theBannerNamesThisRelease() {
        assertEquals(YmxFormat.versionName() + "." + 1,
                YmxFormat.releaseName().replaceAll("\\.\\d+$", ".1"),
                "the release name is the format version and a patch");
        assertTrue(YmxFormat.releaseName().startsWith(YmxFormat.versionName()),
                "the release name opens with the format version");
    }
}
