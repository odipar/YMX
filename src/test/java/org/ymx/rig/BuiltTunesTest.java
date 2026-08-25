package org.ymx.rig;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The two built dumps under {@code ym/test} are what {@link BuiltTunes}
 * produces.
 *
 * <p>Two tunes in the conformance kit once had no source in the tree: they
 * came from a converter that has since gone, so a format change that moved
 * a body byte would have stranded them. These carry the effects those did.
 * The check is that the file and the source that makes it stay one thing.
 */
final class BuiltTunesTest {

    @Test
    void theBuzzerIsWhatItsSourceBuilds() throws IOException {
        assertBuilt("Sync buzzer, built.ym", BuiltTunes.buzzer());
    }

    @Test
    void thePreemptTuneIsWhatItsSourceBuilds() throws IOException {
        assertBuilt("Digidrum preempt, built.ym", BuiltTunes.preempt());
    }

    private static void assertBuilt(String name, byte[] built)
            throws IOException {
        Path file = Rig.REPO.resolve("ym").resolve("test").resolve(name);
        assertArrayEquals(Files.readAllBytes(file), built,
                "ym/test/" + name + " is no longer what BuiltTunes builds."
                        + " Rebuild it from the source, or the tune and the"
                        + " account of how it is made have parted");
    }
}
