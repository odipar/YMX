package org.ym6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.ymx.Tune;

/**
 * The corpus figures {@code doc/terminology.md} quotes, taken again.
 *
 * <p>Every one of them is a measurement over the YM collection, and a
 * measurement in prose goes stale the first time the collection or the reader
 * moves. The numbers are read back out of the document, so a sentence reworded
 * away fails here rather than passing for ever after.
 *
 * <p>The collection is not in the tree. {@code YM_CORPUS} says which directory
 * holds it, and without that this test is skipped.
 */
final class CorpusNumbersTest {

    /** The documents whose figures these are. */
    private static final Path DOC = Path.of("doc", "terminology.md");
    private static final Path CONVERSION = Path.of("ym", "CONVERSION.md");

    /** One tune, reduced to what the documents say about the collection. */
    private record Surveyed(String name, int playerHz, int drumFrames, long slowestDrum,
                            long fastestDrum, int sinus, int voicesOnEnvelopeAtOnce,
                            int loopFrame, int frames) {}

    @Test
    void theCorpusIsWhatTheVocabularySaysItIs() throws IOException {
        String corpus = System.getenv("YM_CORPUS");
        assumeTrue(corpus != null && Files.isDirectory(Path.of(corpus)),
                "set YM_CORPUS to the directory holding the YM collection"
                        + " doc/terminology.md counts");

        String said = String.join(" ", Files.readString(DOC).split("\\s+"));
        int[] size = numbers(said, "the (\\d+) YM files YMX is tested against; (\\d+) readable",
                "the collection's size");
        int[] rate = numbers(said,
                "All ([\\d,]+) readable files of the ([\\d,]+)-file \\*\\*corpus\\*\\* run at (\\d+)",
                "the frame rate they all run at");
        int[] samples = numbers(said,
                "([\\d,]+) corpus tunes play samples, mostly between ([\\d,]+) and ([\\d,]+) a second",
                "the tunes that play samples");
        int[] envelope = numbers(said, "which real tunes do - ([\\d,]+) of the corpus's ([\\d,]+)",
                "the tunes with two voices on the envelope");
        assertTrue(said.contains("no corpus tune uses it"),
                "the sentence saying no corpus tune uses sinus-SID has been reworded:"
                        + " this test reads that claim out of it");

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of(corpus))) {
            walk.filter(p -> p.toString().toLowerCase().endsWith(".ym")).sorted()
                    .forEach(files::add);
        }
        List<Surveyed> readable = new ArrayList<>();
        for (Path file : files) {
            Ym6Reader.Song song;
            try {
                song = Ym6Reader.read(Files.readAllBytes(file));
            } catch (RuntimeException unreadable) {
                continue;               // counted by difference, below
            }
            readable.add(survey(file.getFileName().toString(), song));
        }

        assertEquals(size[0], files.size(), "YM files in " + corpus);
        assertEquals(size[1], readable.size(), "of them readable");
        assertEquals(rate[0], readable.size(), "readable files, as the frame-rate line has it");
        assertEquals(rate[1], files.size(), "collection size, as the frame-rate line has it");
        for (Surveyed tune : readable) {
            assertEquals(rate[2], tune.playerHz(), tune.name() + "'s frame rate");
        }

        List<Surveyed> withSamples = readable.stream().filter(t -> t.drumFrames() > 0).toList();
        assertEquals(samples[0], withSamples.size(), "tunes that play samples");
        long inBand = withSamples.stream()
                .filter(t -> t.slowestDrum() >= samples[1] && t.fastestDrum() <= samples[2])
                .count();
        assertTrue(inBand * 2 > withSamples.size(),
                "\"mostly between " + samples[1] + " and " + samples[2] + "\": only " + inBand
                        + " of " + withSamples.size() + " tunes keep every trigger there");

        assertEquals(0, readable.stream().mapToInt(Surveyed::sinus).sum(),
                "sinus-SID codes in the collection");
        assertEquals(envelope[0],
                readable.stream().filter(t -> t.voicesOnEnvelopeAtOnce() >= 2).count(),
                "tunes with two voices on the envelope at once");
        assertEquals(envelope[1], readable.size(), "readable files, as the envelope line has it");
    }

    @Test
    void whatStartingOverCostsIsWhatTheConversionDocSaysItCosts() throws IOException {
        String corpus = System.getenv("YM_CORPUS");
        assumeTrue(corpus != null && Files.isDirectory(Path.of(corpus)),
                "set YM_CORPUS to the directory holding the YM collection"
                        + " ym/CONVERSION.md counts");

        String said = String.join(" ", Files.readString(CONVERSION).split("\\s+"));
        int[] census = numbers(said,
                "([\\d,]+) of the corpus's ([\\d,]+) readable files name one other than 0",
                "the files that loop from a frame other than 0");
        int[] share = numbers(said, "is (\\d+)% of the tune on average",
                "how much of a tune such an opening is");

        List<Surveyed> readable = readable(corpus);
        List<Surveyed> late = readable.stream()
                .filter(t -> t.loopFrame() > 0 && t.loopFrame() < t.frames()).toList();
        assertEquals(census[1], readable.size(), "readable files");
        assertEquals(census[0], late.size(), "files looping from a frame other than 0");
        long mean = late.stream().mapToLong(t -> 100L * t.loopFrame() / t.frames()).sum()
                / late.size();
        assertEquals(share[0], mean, "the mean opening, as a percentage of the tune");
    }

    /** Every readable tune in the collection, surveyed. */
    private static List<Surveyed> readable(String corpus) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of(corpus))) {
            walk.filter(p -> p.toString().toLowerCase().endsWith(".ym")).sorted()
                    .forEach(files::add);
        }
        List<Surveyed> readable = new ArrayList<>();
        for (Path file : files) {
            try {
                readable.add(survey(file.getFileName().toString(),
                        Ym6Reader.read(Files.readAllBytes(file))));
            } catch (RuntimeException unreadable) {
                continue;               // counted by difference in the test above
            }
        }
        return readable;
    }

    /** One tune's contribution to the figures, in a single pass over its frames. */
    private static Surveyed survey(String name, Ym6Reader.Song song) {
        YmEffects.Extraction effects = YmEffects.extract(song);
        int drumFrames = 0;
        int together = 0;
        long slowest = Long.MAX_VALUE;
        long fastest = 0;
        for (int frame = 0; frame < song.frames(); frame++) {
            int following = 0;
            for (int voice = 0; voice < 3; voice++) {
                if ((song.registers()[8 + voice][frame] & 0x10) != 0) {
                    following++;        // bit 4: this voice follows the envelope
                }
            }
            together = Math.max(together, following);
            for (int channel = 0; channel < effects.codes().length; channel++) {
                int code = effects.codes()[channel][frame] & 0xFF;
                int count = effects.counts()[channel][frame] & 0xFF;
                if ((code & 0xC0) != Tune.KIND_PCM || (code & 0x30) == 0) {
                    continue;
                }
                int prescaler = Tune.prescaler(code & 7);
                if (prescaler == 0 || count == 0) {
                    continue;           // configures no rate; dropped at pack time
                }
                long hz = Tune.MFP_CLOCK / ((long) prescaler * count);
                drumFrames++;
                slowest = Math.min(slowest, hz);
                fastest = Math.max(fastest, hz);
            }
        }
        return new Surveyed(name, song.playerHz(), drumFrames, slowest, fastest,
                effects.sinus(), together,
                (int) Math.min(song.loopFrame(), Integer.MAX_VALUE), song.frames());
    }

    /** The numbers one documented sentence gives, or a failure naming the sentence. */
    private static int[] numbers(String document, String pattern, String what) {
        Matcher found = Pattern.compile(pattern).matcher(document);
        assertTrue(found.find(), "the sentence giving " + what + " no longer matches "
                + pattern + " - this test reads the numbers out of it");
        int[] said = new int[found.groupCount()];
        for (int i = 0; i < said.length; i++) {
            said[i] = Integer.parseInt(found.group(i + 1).replace(",", ""));
        }
        return said;
    }
}
