package org.ymx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PRG wrapper against {@code doc/BINARIES.md}: header, patched stub,
 * SNDH, empty relocation table. The stub is synthetic - the wrapper reads
 * only the descriptor - and the SNDH comes from {@link MkSndh} over the
 * same synthetic core {@link MkSndhTest} uses.
 */
final class MkPrgTest {

    /** A stub-shaped byte block: 'YMXP' at 4, version 2, even-sized. */
    static byte[] stub() {
        byte[] stub = new byte[40];
        stub[0] = 0x60;
        stub[4] = 'Y';
        stub[5] = 'M';
        stub[6] = 'X';
        stub[7] = 'P';
        stub[9] = 2;
        return stub;
    }

    @Test
    void theProgramFollowsTheContract() {
        byte[] stub = stub();
        byte[] sndh = {'X', 'Y'};
        byte[] prg = MkPrg.wrap(stub, sndh, 3, 4500, true);

        assertEquals(0x601A, word(prg, 0));
        assertEquals(stub.length + sndh.length, (int) longAt(prg, 2), "text size");
        for (int zero = 6; zero < 26; zero += 4) {
            assertEquals(0, longAt(prg, zero), "data, bss, symbols, reserved, flags");
        }
        assertEquals(0, word(prg, 26), "absflag 0: a relocation table follows");

        int text = 28;
        assertEquals(3, word(prg, text + MkPrg.STUB_TUNES), "subtunes patched");
        assertEquals(4500, longAt(prg, text + MkPrg.STUB_FRAMES), "frames patched");
        assertEquals(MkPrg.STUB_FLAG_MARKER, word(prg, text + MkPrg.STUB_FLAGS));
        assertEquals(50, word(prg, text + MkPrg.STUB_RATE),
                "a set with no timer tag is patched to the 50 Hz default");
        assertArrayEquals(sndh, Arrays.copyOfRange(prg,
                text + stub.length, text + stub.length + sndh.length));
        assertEquals(0, longAt(prg, prg.length - 4), "the empty relocation table");
        assertEquals(28 + stub.length + sndh.length + 4, prg.length);
    }

    @Test
    void anOddStubIsRefused(@TempDir Path dir) throws IOException {
        byte[] odd = Arrays.copyOf(stub(), 41);
        Path path = dir.resolve("stub.bin");
        Files.write(path, odd);
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> MkPrg.readStub(path));
        String reason = String.valueOf(refused.getMessage());
        assertTrue(reason.contains("odd"), reason);
    }

    @Test
    void subtunesAndFramesComeOutOfTheSndhTags(@TempDir Path dir) throws IOException {
        byte[] one = MkSndhTest.core(1);
        Path core = dir.resolve("core.bin");
        Files.write(core, one);
        byte[][] registers = org.ym6.Ym6TestData.registers(120);
        Tune source = org.ym6.YmEffects.tune(org.ym6.Ym6Reader.read(
                org.ym6.Ym6TestData.file(registers, 120, true)));
        Path tune = dir.resolve("one.ymx");
        Files.write(tune, YmxEncoder.encode(source, 960, 24, false, false).file());
        Path out = dir.resolve("one.sndh");
        MkSndh.build(new MkSndh.Options(out, List.of(tune), "T", null, null,
                false, true), core);

        byte[] sndh = Files.readAllBytes(out);
        assertEquals(1, MkPrg.subtunes(sndh));
        assertEquals(120, MkPrg.frames(sndh));
    }

    private static int word(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    private static long longAt(byte[] bytes, int at) {
        return ((long) word(bytes, at) << 16) | word(bytes, at + 2);
    }
}
