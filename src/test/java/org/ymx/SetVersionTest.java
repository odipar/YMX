package org.ymx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SetVersion} against a copy of the real sites: a bump lands at
 * every site, a reworded site is refused with nothing written, and the
 * argument gate rejects what the parse could not read.
 */
final class SetVersionTest {

    /** The files the tool rewrites, copied so the repo stays as it is.
     * The two YmxFormat files carry a patch site each as well. */
    private static final List<String> FILES = List.of(
            "src/main/java/org/ymx/YmxFormat.java",
            "dotnet/ymx/YmxFormat.cs",
            "68k/YMX.S",
            "doc/SPEC.md");

    private static void copySites(Path scratch) throws IOException {
        for (String file : FILES) {
            Path to = scratch.resolve(file);
            Files.createDirectories(to.getParent());
            Files.copy(Path.of(file), to);
        }
    }

    @Test
    void aBumpRewritesEverySite(@TempDir Path scratch) throws IOException {
        copySites(scratch);
        SetVersion.set(scratch, "1.12", true);
        assertTrue(Files.readString(scratch.resolve(FILES.get(0)))
                .contains("VERSION = 0x010C;"), "the Java constant");
        assertTrue(Files.readString(scratch.resolve(FILES.get(1)))
                .contains("Version = 0x010C;"), "the C# constant");
        assertTrue(Files.readString(scratch.resolve(FILES.get(2)))
                .contains("equ     $010C"), "the 68k equate");
        String spec = Files.readString(scratch.resolve(FILES.get(3)));
        assertTrue(spec.contains("Version 1.12. Big-endian throughout."),
                "SPEC's opening line");
        assertTrue(spec.contains("**$010C**, version 1.12"), "SPEC §1.1's row");
        assertTrue(spec.contains("the version is $010C - 1.12;"),
                "SPEC §9.1's bullet");
        assertTrue(Files.readString(scratch.resolve(FILES.get(0)))
                .contains("PATCH = " + YmxFormat.PATCH + ";"),
                "-format leaves the release's patch alone");
    }

    /**
     * The release moves on its own, and the format word stays where it
     * was. The two were one number until 0.8, and a tune packed at a
     * format version has to keep playing across every release of it.
     */
    @Test
    void aReleaseMovesWithoutTouchingTheFormat(@TempDir Path scratch)
            throws IOException {
        copySites(scratch);
        String before = Files.readString(scratch.resolve(FILES.get(2)));
        SetVersion.set(scratch, "0.9.3", false);
        String java = Files.readString(scratch.resolve(FILES.get(0)));
        assertTrue(java.contains("RELEASE_MAJOR = 0;"), "the Java major");
        assertTrue(java.contains("RELEASE_MINOR = 9;"), "the Java minor");
        assertTrue(java.contains("PATCH = 3;"), "the Java patch");
        String cs = Files.readString(scratch.resolve(FILES.get(1)));
        assertTrue(cs.contains("ReleaseMajor = 0;"), "the C# major");
        assertTrue(cs.contains("ReleaseMinor = 9;"), "the C# minor");
        assertTrue(cs.contains("Patch = 3;"), "the C# patch");
        assertEquals(before, Files.readString(scratch.resolve(FILES.get(2))),
                "a release bump wrote to the player's format equate");
        assertTrue(java.contains("VERSION = 0x%04X;".formatted(YmxFormat.VERSION)),
                "a release bump moved the format constant");
    }

    /** A patch is the release's, so -format refuses one. */
    @Test
    void theFormatTakesNoPatch(@TempDir Path scratch) throws IOException {
        copySites(scratch);
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> SetVersion.set(scratch, "0.9.3", true));
        assertTrue(String.valueOf(refused.getMessage()).contains("-release"),
                String.valueOf(refused.getMessage()));
    }

    @Test
    void aRewordedSiteIsRefusedWithNothingWritten(@TempDir Path scratch)
            throws IOException {
        copySites(scratch);
        Path spec = scratch.resolve(FILES.get(3));
        Files.writeString(spec, Files.readString(spec)
                .replace("Big-endian throughout", "big-endian, throughout"));
        byte[] player = Files.readAllBytes(scratch.resolve(FILES.get(2)));
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> SetVersion.set(scratch, "0.5", true));
        assertTrue(String.valueOf(refused.getMessage())
                .contains("the site has moved"), String.valueOf(refused));
        assertArrayEquals(player, Files.readAllBytes(scratch.resolve(FILES.get(2))),
                "a refused bump writes nothing, and the 68k file moved");
    }

    @Test
    void theArgumentGateRefusesWhatTheParseCannotRead(@TempDir Path scratch) {
        for (String bad : new String[] {"1", "1.2.3.4", "0.4\n", "1234.0",
                "١.٢", "1. 2", ".4", "1.2."}) {
            IllegalArgumentException refused = assertThrows(
                    IllegalArgumentException.class,
                    () -> SetVersion.set(scratch, bad, true), "\"" + bad + "\"");
            assertTrue(String.valueOf(refused.getMessage()).startsWith("usage:"),
                    "\"" + bad + "\" refused with: " + refused.getMessage());
        }
        IllegalArgumentException outOfRange = assertThrows(
                IllegalArgumentException.class,
                () -> SetVersion.set(scratch, "256.0", true));
        assertTrue(String.valueOf(outOfRange.getMessage()).contains("0 to 255"),
                String.valueOf(outOfRange.getMessage()));
    }
}
