package org.ymx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import org.ym6.Ym6Reader;
import org.ym6.YmEffects;

/**
 * Every tune in the conformance kit is the `.ym` its row names, packed
 * with the options its row gives.
 *
 * <p>Two of the ten had no source in the tree at all: they came from a
 * converter that has since gone, and version 0.6 came through only
 * because it added header bytes and left the body alone. This holds the
 * recipe in a document the build reads back, so the next format change is
 * a repack rather than an edit.
 */
final class ConformanceSourcesTest {

    private static final Path KIT = Path.of("doc", "conformance");
    private static final Pattern ROW = Pattern.compile(
            "^\\| `(\\w+)` \\| `([^`]+)` \\| (`[^`]+`|none) \\|$");

    /**
     * The two figures {@code doc/conformance/README.md} states about the
     * kit itself: the entries it comes to, and the opcodes its retrigger
     * tune carries. Both are measured here rather than remembered, and a
     * reworded sentence fails rather than carrying a stale number.
     */
    @Test
    void theKitReadmeCarriesMeasuredFigures() throws IOException {
        String readme = Files.readString(KIT.resolve("README.md"));

        int entries = 0;
        for (String line : Files.readAllLines(KIT.resolve("MANIFEST.txt"))) {
            String[] row = line.trim().split("\\s+");
            if (row.length == 5 && row[4].length() == 64) {
                entries += Integer.parseInt(row[3]);
            }
        }
        assertTrue(readme.contains("comes to " + format(entries) + " entries"),
                "doc/conformance/README.md does not say the kit comes to "
                        + format(entries) + " entries, which is what"
                        + " MANIFEST.txt's rows add up to");

        int ticks = 0;
        for (String line : Files.readAllLines(KIT.resolve("MANIFEST-ticks.txt"))) {
            String[] row = line.trim().split("\\s+");
            if (row.length == 6 && row[5].length() == 64) {
                ticks += Integer.parseInt(row[4]);
            }
        }
        assertTrue(readme.contains("come to " + format(ticks) + " ticks"),
                "doc/conformance/README.md does not say the tunes come to "
                        + format(ticks) + " ticks, which is what"
                        + " MANIFEST-ticks.txt's rows add up to");

        int opcodes = 0;
        Tune tune = YmEffects.tune(Ym6Reader.read(Files.readAllBytes(
                Path.of("ym", "test", "Sync buzzer, built.ym"))));
        for (byte[] actions : EffectScript.compile(tune).actions()) {
            for (byte action : actions) {
                if (action != 0) {
                    opcodes++;
                }
            }
        }
        assertTrue(readme.contains("carries " + format(opcodes) + " of them"),
                "doc/conformance/README.md does not say retrigger.ymx"
                        + " carries " + format(opcodes) + " opcodes, which is"
                        + " what its source compiles to");
    }

    /**
     * The calls {@code TASK.md} asks each tune for are the calls
     * {@code MANIFEST.txt} holds a digest of. An implementer works from
     * TASK.md and the comparison is against the manifest, so a tune
     * repacked to a new length has to move both.
     */
    @Test
    void theTaskAsksForTheCallsTheManifestHolds() throws IOException {
        String task = Files.readString(KIT.resolve("TASK.md"));
        for (String line : Files.readAllLines(KIT.resolve("MANIFEST.txt"))) {
            String[] row = line.trim().split("\\s+");
            if (row.length == 5 && row[4].length() == 64) {
                String want = "| `" + row[0] + ".ymx` | "
                        + format(Integer.parseInt(row[1])) + " |";
                assertTrue(task.contains(want), "doc/conformance/TASK.md does"
                        + " not ask " + row[0] + " for the "
                        + format(Integer.parseInt(row[1])) + " calls"
                        + " MANIFEST.txt holds a digest of");
            }
        }
    }

    /** A count as the documents write one, with thousands separated. */
    private static String format(int count) {
        return String.format(java.util.Locale.ROOT, "%,d", count);
    }

    @Test
    void everyKitTuneIsWhatItsRowPacks() throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(KIT.resolve("SOURCES.md"))) {
            Matcher m = ROW.matcher(line.trim());
            if (m.matches()) {
                rows.add(new String[] {m.group(1), m.group(2), m.group(3)});
            }
        }
        assertEquals(11, rows.size(), "doc/conformance/SOURCES.md no longer"
                + " names eleven tunes, and the kit holds eleven");

        Path work = Files.createTempDirectory("kit");
        for (String[] row : rows) {
            Path tune = KIT.resolve("tunes").resolve(row[0] + ".ymx");
            Path source = Path.of("ym", "test", row[1]);
            assertTrue(Files.exists(source), source + " is named by"
                    + " doc/conformance/SOURCES.md and is not in the tree");

            List<String> args = new ArrayList<>(List.of("-f"));
            if (!row[2].equals("none")) {
                args.addAll(List.of(row[2].replace("`", "").split(" ")));
            }
            Path packed = work.resolve(row[0] + ".ymx");
            args.add(source.toString());
            args.add(packed.toString());
            java.io.PrintStream out = System.out;
            System.setOut(new java.io.PrintStream(
                    java.io.OutputStream.nullOutputStream()));
            try {
                org.ym6.Ymx.main(args.toArray(new String[0]));
            } finally {
                System.setOut(out);
            }

            assertArrayEquals(Files.readAllBytes(tune),
                    Files.readAllBytes(packed),
                    "doc/conformance/tunes/" + row[0] + ".ymx is no longer"
                            + " what its row in doc/conformance/SOURCES.md"
                            + " packs. Repack the kit, or the tunes and the"
                            + " account of where they come from have parted");
        }
    }
}
