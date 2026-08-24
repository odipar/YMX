package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}
