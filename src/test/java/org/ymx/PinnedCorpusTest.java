package org.ymx;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * A sample of real tunes, packed and pinned: the same source must go on
 * producing the same {@code .ymx}, byte for byte.
 *
 * <p>The unit tests say what each stage does with a fixture built to exercise
 * it. This one says nothing about any stage and everything about the whole:
 * a change that alters a packed file fails here, on tunes that were
 * chosen for their variety - both YM dialects, digidrums, SID voices, tunes
 * with two voices on the envelope, the shortest and longest in the collection,
 * and the drum rates it reaches.
 *
 * <p>An intended change repins them: {@code mvn test -Dymx.pin=refresh}
 * rewrites every pinned file instead of comparing it, and the diff that
 * follows is the review.
 */
final class PinnedCorpusTest {

    /** The tunes and their pinned output, paired by stem, beside the
     * account of what the conversion costs. */
    private static final List<Path> CORPORA = List.of(Path.of("ym", "test"));

    /** What a packed source is called beside it. */
    private static final String PINNED = ".ymx";

    @TestFactory
    Stream<DynamicTest> everyPinnedTunePacksToTheSameBytes() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path corpus : CORPORA) {
            try (Stream<Path> listing = Files.list(corpus)) {
                listing.filter(p -> p.toString().endsWith(".ym"))
                        .sorted()
                        .forEach(sources::add);
            }
        }
        assertTrue(!sources.isEmpty(), "no tunes in " + CORPORA);
        // The README counts them in the line that says what mvn test covers.
        String readme = Files.readString(Path.of("README.md"));
        Matcher counted = Pattern.compile("the packer, (\\d+) pinned tunes").matcher(readme);
        assertTrue(counted.find(), "README.md no longer says how many tunes are"
                + " pinned - this test reads the count out of that line");
        assertTrue(Integer.parseInt(counted.group(1)) == sources.size(),
                "README.md says " + counted.group(1) + " pinned tunes; "
                        + CORPORA + " hold " + sources.size());
        boolean refresh = "refresh".equals(System.getProperty("ymx.pin"));
        return sources.stream().map(source -> dynamicTest(source.getFileName().toString(),
                () -> checkOrRefresh(source, refresh)));
    }

    private static void checkOrRefresh(Path source, boolean refresh) throws IOException {
        Path pinned = pinnedFor(source);
        Path packed = Files.createTempFile("pinned", PINNED);
        try {
            pack(source, packed);
            byte[] fresh = Files.readAllBytes(packed);
            if (refresh) {
                Files.write(pinned, fresh);
                return;
            }
            assertTrue(Files.exists(pinned), source.getFileName()
                    + " has no pinned " + pinned.getFileName()
                    + " - pack it and commit it, or take the tune out of the corpus");
            byte[] was = Files.readAllBytes(pinned);
            assertTrue(java.util.Arrays.equals(was, fresh),
                    () -> source.getFileName() + " packs differently now: "
                            + describe(was, fresh)
                            + ". If that is the intended change, repin with"
                            + " -Dymx.pin=refresh and read the diff");
        } finally {
            Files.deleteIfExists(packed);
        }
    }

    /** Where the two differ, in the terms a reader of the diff needs. */
    private static String describe(byte[] was, byte[] now) {
        if (was.length != now.length) {
            return "pinned " + was.length + " bytes, packed " + now.length;
        }
        for (int at = 0; at < was.length; at++) {
            if (was[at] != now[at]) {
                return String.format("same length, first difference at byte %d: %02X not %02X",
                        at, now[at], was[at]);
            }
        }
        return "no difference";        // unreachable while the arrays differ
    }

    /** The packed file that belongs to one source. */
    private static Path pinnedFor(Path source) {
        String name = source.getFileName().toString();
        return source.resolveSibling(name.substring(0, name.lastIndexOf('.')) + PINNED);
    }

    /**
     * One tune through its own front end, the way the tools run it.
     *
     * <p>Both CLIs are called rather than the encoder underneath, because the
     * padding and the unit choice a tune gets are the CLI's and belong in what
     * is pinned. Their per-stream tables go nowhere while the test runs.
     */
    private static void pack(Path source, Path out) {
        String[] argv = {"-f", source.toString(), out.toString()};
        PrintStream spoken = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream(), true,
                StandardCharsets.ISO_8859_1));
        try {
            org.ym6.Ymx.main(argv);
        } finally {
            System.setOut(spoken);
        }
    }
}
