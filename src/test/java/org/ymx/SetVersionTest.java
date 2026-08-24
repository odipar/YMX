package org.ymx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * argument gate refuses what the parse could not read.
 */
final class SetVersionTest {

    /** The files the tool rewrites, copied so the repo stays as it is. */
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
        SetVersion.set(scratch, "1.12");
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
        assertTrue(spec.contains("the version is $010C — 1.12;"),
                "SPEC §9.1's bullet");
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
                () -> SetVersion.set(scratch, "0.5"));
        assertTrue(String.valueOf(refused.getMessage())
                .contains("the site has moved"), String.valueOf(refused));
        assertArrayEquals(player, Files.readAllBytes(scratch.resolve(FILES.get(2))),
                "a refused bump writes nothing, and the 68k file moved");
    }

    @Test
    void theArgumentGateRefusesWhatTheParseCannotRead(@TempDir Path scratch) {
        for (String bad : new String[] {"1", "1.2.3", "0.4\n", "256.0",
                "1234.0", "١.٢", "1. 2", ".4"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SetVersion.set(scratch, bad), "\"" + bad + "\"");
        }
    }
}
