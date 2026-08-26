package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The release stager's own checks, on synthetic cores. */
final class MkReleaseTest {

    @Test
    void theMatrixCoversEveryVariantOnce() {
        Set<String> names = new HashSet<>();
        for (MkRelease.Variant variant : MkRelease.matrix()) {
            assertTrue(names.add(variant.name()), variant.name() + " twice");
        }
        assertEquals(12, names.size(), "three units by four flag combinations");
        assertTrue(names.contains(
                "ymxsndh-k2" + Tools.binarySuffix() + ".bin"));
        assertTrue(names.contains(
                "ymxsndh-k4-perf-nomask" + Tools.binarySuffix() + ".bin"));
    }

    /** The names, the tag and the manifest carry the release version -
     * the format version and the patch. Spelled out of the constants
     * rather than through the helpers, so a helper that drops the patch
     * fails here. */
    @Test
    void everyPublishedNameCarriesTheReleaseVersion() {
        String release = YmxFormat.versionName() + "." + YmxFormat.PATCH;
        assertEquals("ymxsndh-k2-v" + release + ".bin",
                new MkRelease.Variant(2, false, false).name());
        assertEquals("binaries-v" + release, MkRelease.tag());
        assertTrue(Tools.binarySuffix().endsWith("." + YmxFormat.PATCH),
                "the binaries' suffix drops the patch: " + Tools.binarySuffix());
    }

    /** {@code doc/RELEASES.md} is newest first, so its first section is
     * the release being staged: a section for a release that does not
     * exist, or a missing one for the release that does, fails here. */
    @Test
    void theDocumentsNewestSectionIsThisRelease() throws IOException {
        String first = Files.readAllLines(Path.of("doc", "RELEASES.md")).stream()
                .filter(line -> line.startsWith("## "))
                .findFirst().orElse("");
        assertEquals("## " + YmxFormat.releaseName(), first,
                "doc/RELEASES.md is newest first: its first section heading"
                + " is this release's");
    }

    /** A release with no section of its own is refused, and the refusal
     * is an exception a test can catch rather than an exit. */
    @Test
    void aReleaseWithoutASectionIsRefused() {
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> MkRelease.notesFor("99.99.99"));
        assertTrue(String.valueOf(refused.getMessage()).contains("carries no"),
                String.valueOf(refused.getMessage()));
    }

    /** The release being staged has its own account in
     * {@code doc/RELEASES.md}: the notes the release page carries. */
    @Test
    void thisReleaseHasItsSection() {
        String notes = MkRelease.releaseNotes();
        assertTrue(!notes.isEmpty(), "doc/RELEASES.md's section for "
                + YmxFormat.releaseName() + " is empty");
        assertTrue(!notes.startsWith("#"), "the section for "
                + YmxFormat.releaseName() + " reads as another heading: "
                + notes.lines().findFirst().orElse(""));
    }

    /** The create path tags the commit whose bytes are staged: without
     * {@code --target} the tag lands on the default branch's head, and
     * the notes then name a commit the tag does not reach. No gh command
     * runs here; the argument list is what is read back. */
    @Test
    void theCreateCommandTagsTheStagedCommit() {
        String head = "0123456789abcdef0123456789abcdef01234567";
        assertEquals(List.of("gh", "release", "create",
                        "binaries-v" + YmxFormat.releaseName(),
                        "--target", head,
                        "--title", "YMX player binaries "
                                + YmxFormat.releaseName() + ", format "
                                + YmxFormat.versionName(),
                        "--notes", "the notes"),
                MkRelease.createCommand(MkRelease.tag(), head, "the notes"));
    }

    /** The edit path rewrites the notes and nothing else: gh moves no
     * tag, so the caller reads the tag's commit back before this runs. */
    @Test
    void theEditCommandRewritesTheNotesAlone() {
        assertEquals(List.of("gh", "release", "edit",
                        "binaries-v" + YmxFormat.releaseName(),
                        "--notes", "the notes"),
                MkRelease.editCommand(MkRelease.tag(), "the notes"));
    }

    @Test
    void theUploadCommandCarriesEveryStagedFile() {
        Path dir = Path.of("dist", "release");
        List<String> upload = MkRelease.uploadCommand(dir, MkRelease.tag());
        assertEquals(List.of("gh", "release", "upload",
                        "binaries-v" + YmxFormat.releaseName(), "--clobber"),
                upload.subList(0, 5));
        assertEquals(MkRelease.matrix().size() + 2
                        + MkRelease.standalone(dir).size(), upload.size() - 5,
                "every core, the stub, each standalone tool and the manifest");
        assertTrue(upload.get(upload.size() - 1).endsWith("MANIFEST.txt"),
                upload.get(upload.size() - 1));
    }

    /**
     * A staged standalone tool is uploaded, and one named for another
     * release is not: the release suffix keeps an older zip out.
     */
    @Test
    void theUploadCommandCarriesThisReleasesToolsAlone(@TempDir Path dir)
            throws IOException {
        String suffix = Tools.binarySuffix();
        Files.writeString(dir.resolve("ym-to-ymx-osx-arm64" + suffix + ".zip"), "");
        Files.writeString(dir.resolve("ym-to-ymx-win-x64" + suffix + ".zip"), "");
        Files.writeString(dir.resolve("ym-to-ymx-win-x64-v0.0.1.zip"), "");
        Files.writeString(dir.resolve("ymxsndh-k2" + suffix + ".bin"), "");

        List<String> staged = MkRelease.standalone(dir).stream()
                .map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("ym-to-ymx-osx-arm64" + suffix + ".zip",
                        "ym-to-ymx-win-x64" + suffix + ".zip"), staged,
                "this release's zips, in name order");

        List<String> upload = MkRelease.uploadCommand(dir, MkRelease.tag());
        for (String zip : staged) {
            assertTrue(upload.stream().anyMatch(a -> a.endsWith(zip)),
                    zip + " is not uploaded");
        }
        assertTrue(upload.stream().noneMatch(a -> a.endsWith("v0.0.1.zip")),
                "an older release's zip is uploaded");
    }

    /**
     * The notes carry the section and the commit its binaries came from,
     * and both the publish and the -notes path read them from here. A
     * -notes run names the tag's own commit rather than HEAD, which is
     * what lets a page be reworded after main has moved on.
     */
    @Test
    void theNotesCarryTheSectionAndTheCommitTheyName() {
        String notes = MkRelease.notes("abc1234");
        assertTrue(notes.startsWith("The player is "),
                "the notes do not open with this release's section: "
                        + notes.substring(0, Math.min(60, notes.length())));
        assertTrue(notes.contains("assembled at abc1234."),
                "the notes name no commit");
        assertTrue(notes.contains("MANIFEST.txt"),
                "the notes point at no digest list");
        assertTrue(MkRelease.notes("deadbee").contains("assembled at deadbee."),
                "the commit is not the one the caller passed");
    }

    /**
     * A page renders every newline the notes carry, so the section reaches
     * it one line to a paragraph and one to a list item, however
     * doc/RELEASES.md wrapped it.
     */
    @Test
    void theSectionReachesThePageUnwrapped() {
        for (String line : MkRelease.reflow(MkRelease.releaseNotes()).split("\n")) {
            if (line.isEmpty() || line.startsWith("|") || line.startsWith("```")) {
                continue;
            }
            assertTrue(line.length() > 74 || !line.startsWith("  "),
                    "a wrapped continuation reached the page: \"" + line + '"');
        }
        assertTrue(MkRelease.reflow("one\ntwo\n\n- a\n  b\n- c")
                        .equals("one two\n\n- a b\n- c"),
                "reflow joined the wrong lines: "
                        + MkRelease.reflow("one\ntwo\n\n- a\n  b\n- c"));
        assertTrue(MkRelease.reflow("| a | b |\n| - | - |")
                        .equals("| a | b |\n| - | - |"),
                "reflow ran a table's rows together");
    }

    /** The C# tree builds the same commands: the string literals of each
     * method, in order, against the Java ones. */
    @Test
    void bothTreesBuildTheSameCommands() throws IOException {
        String java = Files.readString(
                Path.of("src", "main", "java", "org", "ymx", "MkRelease.java"));
        String cs = Files.readString(Path.of("dotnet", "ymx", "MkRelease.cs"));
        String[][] methods = {
                {"List<String> createCommand(", "List<string> CreateCommand("},
                {"List<String> editCommand(", "List<string> EditCommand("},
                {"List<String> uploadCommand(", "List<string> UploadCommand("},
                {"String notes(String commit)", "string Notes(string commit)"},
                {"String reflow(String section)", "string Reflow(string section)"}};
        for (String[] method : methods) {
            assertEquals(literals(java, method[0]), literals(cs, method[1]),
                    method[0] + " and " + method[1]
                            + " build different argument lists");
        }
    }

    /** The string literals one method's body carries, in order. */
    private static List<String> literals(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, signature + " has moved");
        String body = source.substring(at < 0 ? 0 : at);
        int depth = 0;
        int end = body.length();
        for (int i = body.indexOf('{'); i >= 0 && i < body.length(); i++) {
            if (body.charAt(i) == '{') {
                depth++;
            } else if (body.charAt(i) == '}' && --depth == 0) {
                end = i;
                break;
            }
        }
        List<String> found = new ArrayList<>();
        Matcher literal = Pattern.compile("\"([^\"]*)\"")
                .matcher(body.substring(0, end));
        while (literal.find()) {
            found.add(literal.group(1));
        }
        return found;
    }

    @Test
    void aCoreIsVerifiedAgainstTheVariantItIsNamedFor() {
        MkRelease.Variant plain = new MkRelease.Variant(1, false, false);
        byte[] core = MkSndhTest.core(1);
        MkRelease.verifyCore(core, plain);

        RuntimeException wrongUnit = assertThrows(RuntimeException.class,
                () -> MkRelease.verifyCore(core, new MkRelease.Variant(2, false, false)));
        assertTrue(String.valueOf(wrongUnit.getMessage()).contains("unit"));

        RuntimeException wrongFlags = assertThrows(RuntimeException.class,
                () -> MkRelease.verifyCore(core, new MkRelease.Variant(1, true, false)));
        assertTrue(String.valueOf(wrongFlags.getMessage()).contains("flags"));

        byte[] wrongFormat = core.clone();
        wrongFormat[MkSndh.CORE_FORMAT + 1] = 99;
        RuntimeException stale = assertThrows(RuntimeException.class,
                () -> MkRelease.verifyCore(wrongFormat, plain));
        assertTrue(String.valueOf(stale.getMessage()).contains("format version"));
    }

    /** The rest of the descriptor, which the variant does not name: all
     * four magic bytes, F, and the two longs a combiner patches. */
    @Test
    void aCoreIsVerifiedAgainstTheDescriptorItCarries() {
        MkRelease.Variant plain = new MkRelease.Variant(1, false, false);
        for (int letter = 0; letter < 4; letter++) {
            byte[] core = MkSndhTest.core(1);
            core[MkSndh.CORE_MAGIC + letter] = '?';
            refused(core, plain, "is not an SNDH core");
        }
        byte[] noFixed = MkSndhTest.core(1);
        noFixed[MkSndh.CORE_WORK_FIXED + 1] = 0;
        refused(noFixed, plain, "F = 0");
        byte[] oddFixed = MkSndhTest.core(1);
        oddFixed[MkSndh.CORE_WORK_FIXED + 1] = 101;
        refused(oddFixed, plain, "F = 101");
        byte[] table = MkSndhTest.core(1);
        table[MkSndh.CORE_TABLE_OFF + 3] = 4;
        refused(table, plain, "table offset");
        byte[] workspace = MkSndhTest.core(1);
        workspace[MkSndh.CORE_WORK_OFF + 3] = 4;
        refused(workspace, plain, "workspace offset");
    }

    private static void refused(byte[] core, MkRelease.Variant variant,
            String says) {
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> MkRelease.verifyCore(core, variant));
        assertTrue(String.valueOf(refused.getMessage()).contains(says),
                String.valueOf(refused.getMessage()));
    }

    /** The stub read the same way: 'YMXP' whole, and the fields a
     * combiner patches as the assembler left them. */
    @Test
    void theStubIsVerifiedAgainstItsDescriptor() {
        MkRelease.verifyStub(MkPrgTest.stub());
        for (int letter = 0; letter < 4; letter++) {
            byte[] stub = MkPrgTest.stub();
            stub[MkPrg.STUB_MAGIC + letter] = '?';
            refusedStub(stub, "is not a PRG stub");
        }
        byte[] version = MkPrgTest.stub();
        version[MkPrg.STUB_VERSION + 1] = 1;
        refusedStub(version, "stub descriptor version 1");
        byte[] frames = MkPrgTest.stub();
        frames[MkPrg.STUB_FRAMES + 3] = 9;
        refusedStub(frames, "frame count");
        byte[] flags = MkPrgTest.stub();
        flags[MkPrg.STUB_FLAGS + 1] = 1;
        refusedStub(flags, "flags 1");
    }

    private static void refusedStub(byte[] stub, String says) {
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> MkRelease.verifyStub(stub));
        assertTrue(String.valueOf(refused.getMessage()).contains(says),
                String.valueOf(refused.getMessage()));
    }
}
