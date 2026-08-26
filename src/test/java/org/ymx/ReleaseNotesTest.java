package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.ymx.rig.GenData;

/**
 * The figures {@code doc/RELEASES.md}'s newest section states, against the
 * tree that section describes.
 *
 * <p>{@code ymx/mkrelease.sh -publish} posts that section verbatim as the
 * release notes, so a number in it is a number on the release page, read by
 * people who cannot run a test. The section is read here through
 * {@link MkRelease#releaseNotes()}, the same text the stager posts, and
 * every figure in it is looked up in {@link YmxFormat}, {@link LoopFrame}
 * or {@code 68k/YMX.S}'s own equates. The player's size is the one figure
 * no constant holds: the rig measures it into {@code README.md} out of an
 * assembled build ({@code ymx/test/rig.sh}), and the two sentences are tied
 * together here, so the page carries the measured size rather than a
 * remembered one.
 *
 * <p>Only the newest section is read. The older sections state what an
 * earlier release carried and are history: the newest section's quote of
 * the release before it is checked against that release's own section, and
 * nothing else in an older section is measured again.
 *
 * <p>A reworded sentence fails here, on purpose: these checks locate their
 * numbers by the shape of the sentence carrying them, so a figure moved
 * without being re-measured is caught rather than posted.
 */
final class ReleaseNotesTest {

    private static final Path PLAYER = Path.of("68k", "YMX.S");
    private static final Path README = Path.of("README.md");

    /** The numbers the notes spell out rather than write in digits. */
    private static final Map<String, Integer> SPELLED =
            Map.of("eight", 8, "twelve", 12, "twenty-five", 25,
                    "thirty-two", 32);

    /**
     * The size the section leads with, against the README's, which the rig
     * measures out of an assembled player - and the size it says the
     * release before it carried, against that release's own section.
     */
    @Test
    void theNewestSectionLeadsWithTheMeasuredPlayerSize() throws IOException {
        String notes = newest();
        Matcher said = Pattern.compile("The player is ([\\d,]+) bytes at unit size"
                + " (\\d), where ([\\d.]+) carried ([\\d,]+)").matcher(notes);
        assertTrue(said.find(), "doc/RELEASES.md's section for "
                + YmxFormat.releaseName() + " states no player size. Every"
                + " section leads with one: \"The player is N bytes at unit"
                + " size k, where <release> carried M\", which is what this"
                + " check reads it out of");

        String measured = Files.readString(README);
        Matcher rig = Pattern.compile("is the player, ([\\d,]+) bytes at the"
                + " `ST4_UNIT` (\\d)").matcher(measured);
        assertTrue(rig.find(), "README.md's measured sizes have been reworded;"
                + " the rig reads them out of that sentence and this check"
                + " reads the release notes against it");
        assertEquals(number(rig.group(2)), number(said.group(2)),
                "the release notes quote the player at another unit size than"
                        + " the README the rig measures");
        assertEquals(number(rig.group(1)), number(said.group(1)),
                "the release notes state a player size the rig did not measure");

        String before = MkRelease.notesFor(said.group(3));
        Matcher carried = Pattern.compile("([\\d,]+) bytes at unit size (\\d)")
                .matcher(before);
        assertTrue(carried.find(), "doc/RELEASES.md's section for "
                + said.group(3) + " states no player size of its own");
        assertEquals(number(carried.group(1)), number(said.group(4)),
                "the newest section misquotes " + said.group(3) + "'s size");
        assertEquals(number(carried.group(2)), number(said.group(2)),
                "the two sizes are quoted at different unit sizes");
    }

    /**
     * The format version the section names, against the one this build
     * reads. {@code ymx/setversion.sh} rewrites twelve sites and this page
     * is not one of them - an older section states the version its release
     * carried and stands - so the newest section's own version is held
     * here.
     */
    @Test
    void theSectionNamesTheFormatVersionThisBuildReads() {
        assertEquals(YmxFormat.versionName(), group(
                whicheverStates("Format version [\\d.]+: a tune packed at"),
                "Format version ([\\d.]+): a tune packed at",
                "the format version its tunes carry"));
    }

    /**
     * The header the notes describe, field by field. Each figure is read
     * from the section that states it: the header's size moved at 0.6 and
     * the loop frame's offset did not, so the two live in different
     * sections and each check finds its own.
     */
    @Test
    void theHeaderFiguresAreTheHeaderBothTreesWrite() {
        assertEquals(YmxFormat.HEADER_SIZE, number(
                whicheverStates("The header is \\d+ bytes"),
                "The header is (\\d+) bytes", "the header size"));
        assertEquals(YmxFormat.OFFSET_SECTION_TABLE - YmxFormat.OFFSET_REQUIRED,
                number(whicheverStates("The section table follows at \\d+"),
                        "The section table follows at (\\d+)",
                        "the section table's offset")
                        - YmxFormat.OFFSET_REQUIRED,
                "the section table sits one long past the mask");
        assertEquals(YmxFormat.OFFSET_REQUIRED, number(
                whicheverStates("The long at offset \\d+ is `Q`"),
                "The long at offset (\\d+) is `Q`", "`Q`'s offset"));
        assertEquals(YmxFormat.OFFSET_LOOP_FRAME, number(
                whicheverStates("The long at offset \\d+ is `L`"),
                "The long at offset (\\d+) is `L`", "`L`'s offset"));
        assertEquals(YmxFormat.OFFSET_LOOP_TABLE, number(
                whicheverStates("the long at \\d+ is the offset of a loop table"),
                "the long at (\\d+) is the offset of a loop table",
                "the loop table's offset"));
        assertEquals(YmxFormat.STREAMS, spelled(
                whicheverStates("the loop table, [\\w-]+ entries"),
                "the loop table, ([\\w-]+) entries"),
                "the loop table's entries");
        assertEquals(YmxFormat.MAX_STREAMS, spelled(
                whicheverStates("[\\w-]+ is the stream ceiling"),
                "([\\w-]+) is the stream ceiling"),
                "the stream ceiling");
    }

    /** The cap a ring is raised to, and the workspace the rings sit past. */
    @Test
    void theRingCapAndTheWorkspaceFloorAreThePlayersOwn() throws IOException {
        String notes = whicheverStates("up to the cap of [\\d,]+ bytes a ring");
        assertEquals(YmxFormat.MAX_RING_SIZE, number(notes,
                "up to the cap of ([\\d,]+) bytes a ring", "the ring cap"));

        Map<String, Integer> equates = equates();
        int fixed = equate(equates, "YMX_STATE")
                + equate(equates, "YMX_STREAMS") * equate(equates, "YMX_STATE_SIZE");
        assertEquals(fixed, number(notes,
                "The workspace before the rings is [\\w-]+ bytes larger: ([\\d,]+)"
                        + " bytes", "the workspace before the rings"));
        assertEquals(YmxFormat.STREAMS,
                number(notes, "plus (\\d+) `N` for the rings", "the rings"));
        // The three longs the same bullet lists are what the workspace grew
        // by: they sit between the fields 0.4.1 had and the decoder states.
        assertEquals(equate(equates, "YMX_STATE") - equate(equates, "YMX_BODY"),
                spelled(notes, "The workspace before the rings is ([\\w-]+) bytes"
                        + " larger"), "what the three longs added");
    }

    /**
     * The conditions the notes give for a frame the wrap can enter, against
     * the packer's own report of them. The page states them for a reader,
     * the packer states them to whoever runs a conversion, and both carry
     * the one wording, so a change to either fails here.
     */
    @Test
    void theConditionsTheNotesStateAreThePackersOwn() {
        String notes = whicheverStates("the timers stopped");
        String conditions = group(notes,
                "the frame has to be one the wrap can enter (with the timers[^.]*)\\.",
                "the conditions a frame is kept under");
        assertEquals(1, LoopFrame.BUDGET_SECONDS,
                "the notes call the budget \"a second\"");
        assertTrue(notes.contains("takes the next one that can, up to a second"
                + " later"), "doc/RELEASES.md's section for "
                + YmxFormat.releaseName() + " no longer states how far past"
                + " its frame the packer looks");

        Tune tune = fallsBack();
        LoopFrame.Plan plan = LoopFrame.resolve(tune, EffectScript.compile(tune),
                true, 960, 24, 1);
        assertEquals(0, plan.frame(), "the tune built here has to fall back");
        assertTrue(plan.notes().stream().anyMatch(note -> note.contains(conditions)),
                "the packer reports the fallback as " + plan.notes()
                        + ", and the release page states it as \"" + conditions
                        + "\": the page and the packer state the conditions"
                        + " in one wording");
    }

    /** A tune whose loop frame no frame within the budget can be entered
     * at: voice A follows the envelope throughout and R13 is never written,
     * so the phase a second pass would start at is the first pass's. */
    /**
     * The cycle sentence names two frame counts and what each resolves to.
     * Hatari is not run here, so the tick counts stand as measured; the
     * frames and the resolutions they imply are arithmetic, and they are
     * what a reader would use to repeat the measurement. One tick of the
     * harness's 200 Hz clock covers 40,000 cycles at 8 MHz.
     */
    @Test
    void theCycleSentenceResolvesWhatItSaysItDoes() {
        String notes = whicheverStates("played [\\d,]+ frames of one tune");
        int longRun = number(notes, "played ([\\d,]+) frames of one tune",
                "the frames the cycle measurement played");
        int shortRun = number(notes, "the harness's own ([\\d,]+) frames",
                "the frames the harness plays by default");

        assertEquals(GenData.DEFAULT_PLAY, shortRun,
                "doc/RELEASES.md gives the harness's default as " + shortRun
                        + " frames, where org.ymx.rig.GenData plays "
                        + GenData.DEFAULT_PLAY);

        int perTick = 40_000;
        assertEquals(number(notes, "resolves to within about (\\d+) cycles a frame",
                        "what the long run resolves"),
                Math.round((float) perTick / longRun),
                "doc/RELEASES.md says what " + longRun + " frames resolve;"
                        + " a tick is " + perTick + " cycles at 8 MHz");
    }

    private static Tune fallsBack() {
        int frames = 200;
        byte[][] registers = new byte[YmxFormat.REGISTER_STREAMS][frames];
        for (int frame = 0; frame < frames; frame++) {
            registers[13][frame] = (byte) 0xFF;
            registers[8][frame] = 0x10;
        }
        return new Tune(frames, 50, 2000000L, true, 100, registers,
                new byte[YmxFormat.CHANNELS][frames],
                new byte[YmxFormat.CHANNELS][frames], new byte[frames],
                new byte[0][], new int[0], EffectScript.Semantics.YM,
                "", "", "", List.of());
    }

    // ------------------------------------------------------------- the sources

    /** The section the stager posts, whitespace collapsed, so a rewrapped
     * paragraph reads the same as the one it replaced. */
    private static String newest() {
        return String.join(" ", MkRelease.releaseNotes().split("\\s+"));
    }

    /**
     * The section that introduced this format version, which is the newest
     * one where the release is that format's first and an older one where a
     * patch has followed it. The format's own figures - the header, the ring
     * cap, the workspace, the conditions - are stated once, in that section,
     * and a patch section repeats none of them.
     */
    /**
     * The newest section stating something. A figure is stated once, in the
     * release that set it: a patch that leaves the player alone restates
     * none of the player's figures, and a format version that moves the
     * header restates the header and not the loop frame. So a check walks
     * the sections newest first and reads the one that carries its figure.
     */
    private static String whicheverStates(String pattern) {
        Pattern wanted = Pattern.compile(pattern);
        for (String section : sections()) {
            if (wanted.matcher(section).find()) {
                return section;
            }
        }
        return newest();                // fails in the caller, by name
    }

    /** Every section of doc/RELEASES.md, newest first, whitespace flat. */
    private static List<String> sections() {
        String all;
        try {
            all = Files.readString(Path.of("doc", "RELEASES.md"));
        } catch (IOException e) {
            throw new IllegalStateException("doc/RELEASES.md: " + e);
        }
        List<String> found = new ArrayList<>();
        for (String part : all.split("(?m)^## ")) {
            if (!part.isBlank() && Character.isDigit(part.charAt(0))) {
                found.add(String.join(" ", part.split("\\s+")));
            }
        }
        return found;
    }

    private static String theFormat() {
        return String.join(" ",
                MkRelease.notesFor(YmxFormat.versionName() + ".0").split("\\s+"));
    }

    /** Every {@code NAME equ NUMBER} in the 68000 player. */
    private static Map<String, Integer> equates() throws IOException {
        Map<String, Integer> equates = new LinkedHashMap<>();
        Matcher equ = Pattern.compile("^(\\w+)\\s+equ\\s+(\\d+)\\s*(?:;.*)?$",
                Pattern.MULTILINE).matcher(Files.readString(PLAYER));
        while (equ.find()) {
            equates.put(equ.group(1), Integer.parseInt(equ.group(2)));
        }
        return equates;
    }

    private static int equate(Map<String, Integer> equates, String name) {
        Integer value = equates.get(name);
        assertTrue(value != null, PLAYER + " no longer defines " + name);
        return value == null ? 0 : value;
    }

    private static int number(String text) {
        return Integer.parseInt(text.replace(",", ""));
    }

    private static int number(String notes, String pattern, String what) {
        return number(group(notes, pattern, what));
    }

    /** One figure the notes spell out as a word. */
    private static int spelled(String notes, String pattern) {
        String said = group(notes, pattern, "a figure spelled out");
        Integer value = SPELLED.get(said);
        assertTrue(value != null, "doc/RELEASES.md spells a figure \"" + said
                + "\", which this check has no number for");
        return value == null ? -1 : value;
    }

    private static String group(String notes, String pattern, String what) {
        Matcher said = Pattern.compile(pattern).matcher(notes);
        assertTrue(said.find(), "doc/RELEASES.md's section for "
                + YmxFormat.releaseName() + " no longer states " + what
                + " as \"" + pattern + "\", which is what this check reads"
                + " it out of");
        return said.group(1);
    }
}
