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
import org.ymx.EffectScript;
import org.ymx.Tune;
import org.ymx.YmxFormat;

/**
 * The corpus figures {@code doc/terminology.md} quotes, taken again, and the
 * packer's own numbers that {@code ym/CONVERSION.md} quotes beside them.
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

    /** The pinned tunes doc/terminology.md works through by name. The ring
     * form is asked for rather than arrived at, so its example is the
     * conformance kit's, packed with the -n1776 its row gives. */
    private static final Path RING_EXAMPLE =
            Path.of("doc", "conformance", "tunes", "ring_form.ymx");
    private static final Path CUT_EXAMPLE =
            Path.of("ym", "test", "Dragon Flight  4 - Finish 1.ymx");
    private static final Path UNIT_EXAMPLE =
            Path.of("ym", "test", "Crapman level  9.ymx");

    /** One tune, reduced to what the documents say about the collection.
     * {@code entersAtItsOwnFrame} and {@code cut} are the two the packer
     * answers for a tune that starts over: whether the frame its header gives
     * can be entered, and whether reaching it needs the streams cut in two. */
    private record Surveyed(String name, int playerHz, int drumFrames, long slowestDrum,
                            long fastestDrum, int sinus, int voicesOnEnvelopeAtOnce,
                            int loopFrame, int frames, boolean entersAtItsOwnFrame,
                            boolean cut) {}

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

    /**
     * What the reader turns away, against the collection it is pointed at.
     * {@code ym/CONVERSION.md} states the cost of taking `YM5!` and `YM6!`
     * only as a count of files, and a count in prose is a count a test
     * reads back.
     */
    @Test
    void whatTheReaderTurnsAwayIsWhatTheConversionDocSaysItIs() throws IOException {
        String corpus = System.getenv("YM_CORPUS");
        assumeTrue(corpus != null && Files.isDirectory(Path.of(corpus)),
                "set YM_CORPUS to the directory holding the YM collection");
        Path collection = Path.of(corpus);
        int total = 0;
        int refused = 0;
        int ym2 = 0;
        try (Stream<Path> listing = Files.list(collection)) {
            for (Path each : (Iterable<Path>) listing
                    .filter(p -> p.toString().endsWith(".ym")).sorted()::iterator) {
                total++;
                try {
                    Ym6Reader.read(Files.readAllBytes(each));
                } catch (Ym6Reader.FormatException e) {
                    refused++;
                    if (String.valueOf(e.getMessage()).contains("\"YM2!\"")) {
                        ym2++;
                    }
                }
            }
        }
        String said = String.join(" ", Files.readString(CONVERSION).split("\\s+"));
        assertTrue(said.contains(refused + " file of the collection's " + total),
                "ym/CONVERSION.md does not say the reader turns away " + refused
                        + " file of " + total);
        assertTrue(said.contains("the other " + (total - refused) + " read"),
                "ym/CONVERSION.md does not say the other " + (total - refused)
                        + " read");
        assertEquals(0, ym2, "the collection holds no YM2 file, as"
                + " ym/CONVERSION.md says of the sample bank one would need");
    }

    /** The one figure here that is not a measurement over the collection: how
     * far past its source's loop frame the packer looks for one the wrap can
     * enter. The document states it in seconds, the packer holds it in frames,
     * and a tune's own frame rate is what turns one into the other. */
    @Test
    void theLoopSearchBudgetIsWhatTheConversionDocSaysItIs() throws IOException {
        String said = String.join(" ", Files.readString(CONVERSION).split("\\s+"));
        int[] budget = numbers(said, "up to (\\d+) second later",
                "how far the packer looks past the header's loop frame");
        for (int rate : new int[] {25, 50, 60, 200}) {
            assertEquals(budget[0] * rate, org.ymx.LoopFrame.budget(rate),
                    "the budget at " + rate + " frames a second");
        }
    }

    /**
     * What starting over costs in each of its two forms, against the pinned
     * tunes the documents work through.
     *
     * <p>The cut costs file bytes: every {@code .ymx} in {@code ym/test} that
     * carries a loop table is measured beside the same source packed with
     * {@code -l0} - one set of sections, the file the packer wrote before the
     * cut existed - which is the figure both documents quote, as a range in
     * {@code ym/CONVERSION.md} and tune by tune in {@code doc/terminology.md}.
     * The ring form costs workspace and no file bytes, and the header of the
     * tune the vocabulary names for it says so.
     */
    @Test
    void whatStartingOverCostsIsWhatTheDocumentsSayItCosts() throws IOException {
        String said = String.join(" ", Files.readString(CONVERSION).split("\\s+"));
        String vocabulary = String.join(" ", Files.readString(DOC).split("\\s+"));
        int[] range = numbers(said,
                "the file is (\\d+) to (\\d+) per cent larger than the same tune"
                        + " packed with `-l0`", "what a cut costs");
        checkTheWorkedExamples(vocabulary);
        String[] eachGrew = groups(vocabulary,
                "the file grows by ([\\d.]+)%, ([\\d.]+)%, ([\\d.]+)%,"
                        + " ([\\d.]+)%, ([\\d.]+)% and ([\\d.]+)%",
                "what a cut costs each tune it is done to");
        List<String> grewBy = new ArrayList<>();
        List<Path> cut = new ArrayList<>();
        try (Stream<Path> listing = Files.list(Path.of("ym", "test"))) {
            listing.filter(p -> p.toString().endsWith(".ymx")).sorted()
                    .filter(CorpusNumbersTest::carriesALoopTable).forEach(cut::add);
        }
        assertTrue(!cut.isEmpty(), "no packed tune in ym/test carries a loop table,"
                + " so this test is measuring nothing");
        long smallest = Long.MAX_VALUE;
        long largest = 0;
        for (Path packed : cut) {
            String name = packed.getFileName().toString();
            Path source = packed.resolveSibling(
                    name.substring(0, name.lastIndexOf('.')) + ".ym");
            Path flat = Files.createTempFile("uncut", ".ymx");
            Path probe = Files.createTempFile("probe", ".ymx");
            boolean unitOne;
            try {
                unitOne = pack("-f", source.toString(), probe.toString())
                        .contains("Packing at -k1");
            } finally {
                Files.deleteIfExists(probe);
            }
            try {
                packStartingOverAtZero(source, flat, unitOne);
                double exactly = 100.0
                        * (Files.size(packed) - Files.size(flat)) / Files.size(flat);
                grewBy.add(String.format(java.util.Locale.ROOT, "%.1f", exactly));
                long grown = Math.round(exactly);
                smallest = Math.min(smallest, grown);
                largest = Math.max(largest, grown);
            } finally {
                Files.deleteIfExists(flat);
            }
        }
        assertEquals(range[0], smallest, "the smallest a cut file grows");
        assertEquals(range[1], largest, "the largest a cut file grows");
        assertEquals(eachGrew.length, grewBy.size(), "the vocabulary quotes "
                + eachGrew.length + " cut tunes and ym/test holds " + grewBy.size());
        grewBy.sort(java.util.Comparator.comparingDouble(Double::parseDouble));
        for (int at = 0; at < eachGrew.length; at++) {
            assertEquals(eachGrew[at], grewBy.get(at),
                    "the " + (at + 1) + "th cut file grows by " + grewBy.get(at) + "%");
        }
    }

    /**
     * The two tunes the vocabulary works through by name, and the third it
     * names for the unit boundary, against the files themselves.
     */
    private static void checkTheWorkedExamples(String vocabulary) throws IOException {
        byte[] ring = Files.readAllBytes(RING_EXAMPLE);
        int[] ringShape = numbers(vocabulary,
                "Say a tune is ([\\d,]+) frames and goes back to frame ([\\d,]+)."
                        + " One pass after the first is ([\\d,]+) frames",
                "the tune the ring form is worked through on");
        int[] ringSize = numbers(vocabulary,
                "Make `N` ([\\d,]+) and every byte the second pass needs",
                "the ring that holds the pass");
        int[] workspace = numbers(vocabulary,
                "twenty-five rings of ([\\d,]+) bytes rather than ([\\d,]+), so"
                        + " ([\\d,]+) bytes of ring rather than ([\\d,]+)",
                "what the raised rings cost");
        int[] cap = numbers(vocabulary, "up to the cap of ([\\d,]+) bytes a ring",
                "the largest ring the format allows");
        int[] ringFile = numbers(vocabulary,
                "Its file is ([\\d,]+) bytes, the same as it would be with no"
                        + " loop, and its header gives `N` = ([\\d,]+)",
                "the file the ring form costs nothing in");
        assertEquals(ringShape[0], (int) field(ring, YmxFormat.OFFSET_FRAMES, 4),
                RING_EXAMPLE + "'s frame count");
        assertEquals(ringShape[1], (int) field(ring, YmxFormat.OFFSET_LOOP_FRAME, 4),
                RING_EXAMPLE + "'s loop frame");
        assertEquals(ringShape[2], ringShape[0] - ringShape[1], "one pass");
        assertEquals(ringSize[0], (int) field(ring, YmxFormat.OFFSET_RING_SIZE, 2),
                RING_EXAMPLE + "'s ring size");
        assertEquals(0, (int) field(ring, YmxFormat.OFFSET_LOOP_TABLE, 4),
                RING_EXAMPLE + " carries a loop table, so it is not the ring form");
        assertEquals(ringSize[0], ringFile[1], "the ring the two sentences give");
        assertEquals(ringFile[0], Files.size(RING_EXAMPLE), RING_EXAMPLE + "'s size");
        assertEquals(workspace[0], ringSize[0], "the raised ring");
        assertEquals(workspace[1], YmxFormat.DEFAULT_RING_SIZE, "the default ring");
        assertEquals(workspace[2], YmxFormat.STREAMS * workspace[0], "the raised rings");
        assertEquals(workspace[3], YmxFormat.STREAMS * workspace[1], "the default rings");
        assertEquals(cap[0], YmxFormat.MAX_RING_SIZE, "the ring cap");

        byte[] streams = Files.readAllBytes(CUT_EXAMPLE);
        int[] cutShape = numbers(vocabulary,
                "is ([\\d,]+) frames and goes back to ([\\d,]+). One pass is"
                        + " ([\\d,]+) frames, and no ring may exceed ([\\d,]+) bytes",
                "the tune the cut is worked through on");
        int[] halves = numbers(vocabulary,
                "the first covering frames 0 to ([\\d,]+) and the second ([\\d,]+)"
                        + " to ([\\d,]+)", "the two sections a cut stream carries");
        int[] crossing = numbers(vocabulary,
                "which happens at frame ([\\d,]+), the same value for every stream,"
                        + " since every first section holds exactly ([\\d,]+) values",
                "where the two sections meet");
        assertEquals(cutShape[0], (int) field(streams, YmxFormat.OFFSET_FRAMES, 4),
                CUT_EXAMPLE + "'s frame count");
        assertEquals(cutShape[1], (int) field(streams, YmxFormat.OFFSET_LOOP_FRAME, 4),
                CUT_EXAMPLE + "'s loop frame");
        assertEquals(cutShape[2], cutShape[0] - cutShape[1], "one pass");
        assertEquals(cutShape[3], YmxFormat.MAX_RING_SIZE, "the ring cap");
        assertTrue(field(streams, YmxFormat.OFFSET_LOOP_TABLE, 4) != 0,
                CUT_EXAMPLE + " carries no loop table, so it is not the cut form");
        assertEquals(halves[0], cutShape[1] - 1, "the last frame of the first half");
        assertEquals(halves[1], cutShape[1], "the first frame of the second half");
        assertEquals(halves[2], cutShape[0] - 1, "the last frame of the tune");
        assertEquals(crossing[0], cutShape[1], "the frame the sections meet at");
        assertEquals(crossing[1], cutShape[1], "the values a first section holds");

        int[] moved = numbers(vocabulary,
                "gives ([\\d,]+) and its file carries ([\\d,]+)",
                "the loop frame a unit boundary moved");
        String stem = UNIT_EXAMPLE.getFileName().toString();
        Ym6Reader.Song song = Ym6Reader.read(Files.readAllBytes(UNIT_EXAMPLE
                .resolveSibling(stem.substring(0, stem.lastIndexOf('.')) + ".ym")));
        assertEquals(moved[0], song.loopFrame(), UNIT_EXAMPLE + "'s source loop frame");
        assertEquals(moved[1], (int) field(Files.readAllBytes(UNIT_EXAMPLE),
                YmxFormat.OFFSET_LOOP_FRAME, 4), UNIT_EXAMPLE + "'s loop frame");
    }

    /** One big-endian header field of a packed file. */
    private static long field(byte[] file, int at, int size) {
        long value = 0;
        for (int byteAt = 0; byteAt < size; byteAt++) {
            value = (value << 8) | (file[at + byteAt] & 0xFF);
        }
        return value;
    }

    /** The strings one documented sentence gives, or a failure naming it. */
    private static String[] groups(String document, String pattern, String what) {
        Matcher found = Pattern.compile(pattern).matcher(document);
        assertTrue(found.find(), "the sentence giving " + what + " no longer matches "
                + pattern + " - this test reads the numbers out of it");
        String[] said = new String[found.groupCount()];
        for (int i = 0; i < said.length; i++) {
            said[i] = found.group(i + 1);
        }
        return said;
    }

    /** Whether a packed file locates a second section per stream. */
    private static boolean carriesALoopTable(Path packed) {
        try {
            byte[] file = Files.readAllBytes(packed);
            long offset = 0;
            for (int at = 0; at < 4; at++) {
                offset = (offset << 8) | (file[org.ymx.YmxFormat.OFFSET_LOOP_TABLE + at]
                        & 0xFF);
            }
            return offset != 0;
        } catch (IOException e) {
            throw new IllegalStateException(packed + ": " + e);
        }
    }

    /** One tune through its own CLI, told to start over from frame 0: the
     * options the pinned file was packed with, minus the loop frame. */
    private static void packStartingOverAtZero(Path source, Path out,
                                               boolean unitOne) {
        if (unitOne) {
            pack("-f", "-l0", "-k1", source.toString(), out.toString());
        } else {
            pack("-f", "-l0", source.toString(), out.toString());
        }
    }

    /**
     * One pack, with what the packer said about it. A tune whose loop point
     * is not a whole number of 2-byte units is packed at 1, and a file
     * measured against a baseline packed at 2 would carry the difference
     * between the two unit sizes rather than what the cut costs.
     */
    private static String pack(String... args) {
        java.io.PrintStream spoken = System.out;
        java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(said, true,
                java.nio.charset.StandardCharsets.ISO_8859_1));
        try {
            Ymx.main(args);
        } finally {
            System.setOut(spoken);
        }
        return said.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /**
     * How many tunes start over from a frame of their own, and what the packer
     * answers for them: both documents count the same collection, so both are
     * read back against one survey of it. The shape resolved against is the
     * packer's default - 960-byte rings, groups of 24, two bytes a unit.
     */
    @Test
    void theLoopCensusIsWhatTheDocumentsSayItIs() throws IOException {
        String corpus = System.getenv("YM_CORPUS");
        assumeTrue(corpus != null && Files.isDirectory(Path.of(corpus)),
                "set YM_CORPUS to the directory holding the YM collection"
                        + " ym/CONVERSION.md counts");

        String said = String.join(" ", Files.readString(CONVERSION).split("\\s+"));
        String vocabulary = String.join(" ", Files.readString(DOC).split("\\s+"));
        String pattern = "([\\d,]+) of the corpus's ([\\d,]+) readable files give"
                + " one other than 0";
        int[] census = numbers(said, pattern,
                "the files that loop from a frame other than 0");
        int[] alsoSaid = numbers(vocabulary, pattern,
                "the files that loop from a frame other than 0, in the vocabulary");
        int[] share = numbers(said, "is (\\d+)% of the tune on average",
                "how much of a tune such an opening is");
        int[] entered = numbers(vocabulary,
                "([\\d,]+) of the ([\\d,]+) tunes with a loop frame can be entered"
                        + " at the frame their own header gives",
                "the tunes whose own loop frame can be entered");
        int[] needTheCut = numbers(vocabulary,
                "Of the ([\\d,]+) tunes with a loop frame, ([\\d,]+) need the cut",
                "the tunes whose streams are cut in two");

        List<Surveyed> readable = readable(corpus);
        List<Surveyed> late = readable.stream()
                .filter(t -> t.loopFrame() > 0 && t.loopFrame() < t.frames()).toList();
        assertEquals(census[1], readable.size(), "readable files");
        assertEquals(census[0], late.size(), "files looping from a frame other than 0");
        assertEquals(census[0], alsoSaid[0], "the two documents count the same files");
        assertEquals(census[1], alsoSaid[1], "the two documents read the same corpus");
        long mean = Math.round(late.stream()
                .mapToDouble(t -> 100.0 * t.loopFrame() / t.frames()).average()
                .orElseThrow());
        assertEquals(share[0], mean, "the mean opening, as a percentage of the tune");
        assertEquals(entered[1], late.size(), "tunes with a loop frame, as the"
                + " entered line has it");
        assertEquals(entered[0], late.stream().filter(Surveyed::entersAtItsOwnFrame)
                .count(), "tunes entered at the frame their own header gives");
        assertEquals(needTheCut[0], late.size(), "tunes with a loop frame, as the"
                + " cut line has it");
        assertEquals(needTheCut[1], late.stream().filter(Surveyed::cut).count(),
                "tunes whose streams are cut in two");
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
        int loopFrame = (int) Math.min(song.loopFrame(), Integer.MAX_VALUE);
        boolean enters = false;
        boolean cut = false;
        if (loopFrame > 0 && loopFrame < song.frames()) {
            Tune unpadded = YmEffects.tune(song, effects);
            EffectScript.Result script = EffectScript.compile(unpadded);
            enters = org.ymx.LoopFrame.qualifies(unpadded, script, loopFrame);
            // The packer pads to whole 2-byte units before it resolves, and a
            // duplicated frame moves the frames after it, so the census pads
            // too or it resolves against a tune the packer never sees.
            Tune tune = Ymx.padToUnit(song, unpadded, 2);
            if (tune == null) {
                tune = unpadded;
            }
            EffectScript.Result padded = EffectScript.compile(tune);
            org.ymx.LoopFrame.Plan plan = org.ymx.LoopFrame.resolve(tune, padded,
                    true, YmxFormat.DEFAULT_RING_SIZE, YmxFormat.DEFAULT_CHUNK, 2);
            // A section is a whole number of units, so a loop point that is
            // not one leaves the tune starting over from frame 0. The packer
            // packs such a tune at unit 1, where every frame is a boundary,
            // and the census counts what the packer does.
            if (plan.frame() != loopFrame) {
                org.ymx.LoopFrame.Plan atOne = org.ymx.LoopFrame.resolve(unpadded,
                        script, true, YmxFormat.DEFAULT_RING_SIZE,
                        YmxFormat.DEFAULT_CHUNK, 1);
                if (atOne.frame() == loopFrame) {
                    plan = atOne;
                }
            }
            cut = plan.cut();
        }
        return new Surveyed(name, song.playerHz(), drumFrames, slowest, fastest,
                effects.sinus(), together, loopFrame, song.frames(), enters, cut);
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
