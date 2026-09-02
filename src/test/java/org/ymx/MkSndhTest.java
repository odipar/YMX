package org.ymx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ym6.Ym6Reader;
import org.ym6.Ym6TestData;
import org.ym6.YmEffects;

/**
 * The SNDH combiner against {@code doc/BINARIES.md}: the file it writes from
 * a core and packed tunes, byte position by byte position.
 *
 * <p>The core here is synthetic - the descriptor and two entry markers, no
 * player code - because the combiner reads only the descriptor and the tests
 * are about the bytes around the core, not inside it. The rig's SNDH test
 * plays a real combined file end to end.
 */
final class MkSndhTest {

    /** A core-shaped byte block: entry triple, 'YMXC', version 1, the given
     * unit, flags 0, the current format version, a workspace fixed size of
     * 100. */
    static byte[] core(int unit) {
        byte[] core = new byte[64];
        core[0] = 0x60;                             // bra.w, unfollowed here
        core[12] = 'Y';
        core[13] = 'M';
        core[14] = 'X';
        core[15] = 'C';
        core[17] = (byte) MkSndh.CORE_DESCRIPTOR_VERSION;   // descriptor version
        core[19] = (byte) unit;
        core[22] = (byte) (YmxFormat.VERSION >> 8); // format version, a word
        core[23] = (byte) YmxFormat.VERSION;
        core[25] = 100;                             // workspace fixed size
        return core;
    }

    private static Path write(Path dir, String name, byte[] bytes) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    /** A real packed tune, so the header the combiner reads is genuine. */
    private static byte[] tune(int frames, boolean loops) {
        return tune(frames, loops, YmxFormat.DEFAULT_TIMERS);
    }

    /** As above, on a given channel-to-timer map. */
    private static byte[] tune(int frames, boolean loops, int timerMap) {
        byte[][] registers = Ym6TestData.registers(frames);
        Tune source = YmEffects.tune(Ym6Reader.read(
                Ym6TestData.file(registers, frames, true)));
        return YmxEncoder.encode(source, 960, 24, loops, false, 1, timerMap).file();
    }

    @Test
    void theCombinedFileFollowsTheContract(@TempDir Path dir) throws IOException {
        byte[] one = tune(240, true);
        byte[] two = tune(120, false);
        Path core = write(dir, "core.bin", core(1));
        Path out = dir.resolve("set.sndh");
        MkSndh.Result result = MkSndh.build(new MkSndh.Options(out,
                List.of(write(dir, "one.ymx", one), write(dir, "two.ymx", two)),
                "Set", "Composer", null, false, true), core);
        assertEquals(2, result.subtunes());
        byte[] file = Files.readAllBytes(out);

        // The entry triple: three bra.w, all with the same displacement,
        // landing on the core's own triple.
        int header = 2 + word(file, 2) + 0;
        for (int entry = 0; entry < 3; entry++) {
            assertEquals(0x6000, word(file, 4 * entry), "entry " + entry);
            assertEquals(header - 2, word(file, 4 * entry + 2), "entry " + entry);
        }
        assertEquals(0, header & 1, "the core starts even");
        assertEquals('Y', file[header + 12], "the core sits at the branch target");

        // The tags, in the combiner's own order, ending in HDNS just before
        // the core: the '##' count first of the three tables, since a reader
        // sizes FRMS and the names by it.
        String tags = new String(file, 12, header - 12, StandardCharsets.ISO_8859_1);
        assertTrue(tags.startsWith("SNDH"));
        assertTrue(tags.indexOf("TITLSet\0") < tags.indexOf("COMMComposer\0"));
        assertTrue(tags.contains("##02\0"));
        assertTrue(tags.indexOf("##02\0") < tags.indexOf("FRMS"));
        assertTrue(tags.indexOf("##02\0") < tags.indexOf("!#SN"));
        assertTrue(tags.contains("TC50\0"));
        assertTrue(tags.contains("FLAG~ay\0"));
        assertTrue(tags.indexOf("FRMS") < tags.indexOf("!#SN"));
        assertTrue(tags.replaceAll("\0+$", "").endsWith("HDNS"));

        // FRMS: one long per subtune - 0 for the tune that starts over, the
        // frame count for the one that plays once.
        int frms = 12 + tags.indexOf("FRMS") + 4;
        assertEquals(0, longAt(file, frms), "subtune 1 starts over");
        assertEquals(120, longAt(file, frms + 4), "subtune 2 plays once");

        // The subtune names, from the tunes' stems.
        int sn = 12 + tags.indexOf("!#SN");
        int name1 = sn + word(file, sn + 4);
        assertEquals("one", new String(file, name1, 3, StandardCharsets.ISO_8859_1));

        // The table the core's patched offset reaches: a count word, then a
        // long offset per tune, from the core's start.
        int tableOff = (int) longAt(file, header + MkSndh.CORE_TABLE_OFF);
        int table = header + tableOff;
        assertEquals(0, tableOff & 1);
        assertEquals(2, word(file, table));
        int at1 = header + (int) longAt(file, table + 2);
        int at2 = header + (int) longAt(file, table + 6);
        assertArrayEquals(one, java.util.Arrays.copyOfRange(file, at1, at1 + one.length));
        assertArrayEquals(two, java.util.Arrays.copyOfRange(file, at2, at2 + two.length));

        // The workspace: after everything, even, fixed + 25 rings of the
        // set's largest N, all zero.
        int workOff = (int) longAt(file, header + MkSndh.CORE_WORK_OFF);
        assertEquals(0, workOff & 1);
        assertEquals(file.length, header + workOff + 100 + YmxFormat.STREAMS * 960,
                "the workspace is the last thing in the file");
        for (int i = header + workOff; i < file.length; i++) {
            assertEquals(0, file[i], "workspace byte " + i);
        }
    }

    /** The map {@code -timersBC} compiles to: channels 0 and 1 on Timers B
     * and C, and the channels the flag leaves out taking the timers it did
     * not, in order. */
    private static final int TIMERS_BC = YmxFormat.TIMER_B
            | (YmxFormat.TIMER_C << 2) | (YmxFormat.TIMER_A << 4)
            | (YmxFormat.TIMER_D << 6);

    /** The map {@code -timersD} compiles to, the same way. */
    private static final int TIMERS_D = YmxFormat.TIMER_D
            | (YmxFormat.TIMER_A << 2) | (YmxFormat.TIMER_B << 4)
            | (YmxFormat.TIMER_C << 6);

    /**
     * The FLAG tag names the MFP timers the tunes claim, so the packer's
     * {@code -timers} map moves it: the tune the default map puts on Timer A
     * declares 'b' when it is packed on Timer B. A set declares every timer
     * any of its subtunes takes, since a host reads one tag for the file.
     */
    @Test
    void theFlagTagNamesTheTimersTheTunesClaim(@TempDir Path dir) throws IOException {
        assertEquals("~ay", flagOf(dir, "one", tune(240, true)));
        assertEquals("~by", flagOf(dir, "two", tune(240, true, TIMERS_BC)));
        assertEquals("~ady", flagOf(dir, "set",
                tune(240, true), tune(240, true, TIMERS_D)));
    }

    /** The FLAG tag's value out of the file the combiner writes from these
     * tunes: the bytes between 'FLAG' and its closing NUL. */
    private static String flagOf(Path dir, String name, byte[]... tunes)
            throws IOException {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < tunes.length; i++) {
            paths.add(write(dir, name + i + ".ymx", tunes[i]));
        }
        Path out = dir.resolve(name + ".sndh");
        MkSndh.build(new MkSndh.Options(out, paths, "Set", null, null,
                false, true), write(dir, name + "core.bin", core(1)));
        String file = new String(Files.readAllBytes(out),
                StandardCharsets.ISO_8859_1);
        int at = file.indexOf("FLAG") + 4;
        return file.substring(at, file.indexOf(0, at));
    }

    @Test
    void aCoreServingAnotherUnitIsRefused(@TempDir Path dir) throws IOException {
        Path tune = write(dir, "one.ymx", tune(240, true));
        Path core = write(dir, "core.bin", core(2));
        MkSndh.Options options = new MkSndh.Options(dir.resolve("out.sndh"),
                List.of(tune), "T", null, null, false, true);
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> MkSndh.build(options, core));
        String reason = String.valueOf(refused.getMessage());
        assertTrue(reason.contains("unit"), reason);
    }

    @Test
    void aTuneWithABrokenShapeIsRefused(@TempDir Path dir) throws IOException {
        byte[] bytes = tune(240, true);
        bytes[YmxFormat.OFFSET_RING_SIZE] = (byte) (2544 >> 8);   // over the cap,
        bytes[YmxFormat.OFFSET_RING_SIZE + 1] = (byte) 2544;      // divisible by 24
        Path tune = write(dir, "one.ymx", bytes);
        Path core = write(dir, "core.bin", core(1));
        MkSndh.Options options = new MkSndh.Options(dir.resolve("out.sndh"),
                List.of(tune), "T", null, null, false, true);
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> MkSndh.build(options, core));
        String reason = String.valueOf(refused.getMessage());
        assertTrue(reason.contains("2520"), reason);
    }

    @Test
    void aForeignCoreIsRefused(@TempDir Path dir) throws IOException {
        Path tune = write(dir, "one.ymx", tune(240, true));
        Path core = write(dir, "core.bin", new byte[64]);
        MkSndh.Options options = new MkSndh.Options(dir.resolve("out.sndh"),
                List.of(tune), "T", null, null, false, true);
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> MkSndh.build(options, core));
        String reason = String.valueOf(refused.getMessage());
        assertTrue(reason.contains("not an SNDH core"), reason);
    }

    @Test
    void coreFlagsMustMatchTheOptions(@TempDir Path dir) throws IOException {
        Path tune = write(dir, "one.ymx", tune(240, true));
        Path core = write(dir, "core.bin", core(1));
        MkSndh.Options options = new MkSndh.Options(dir.resolve("out.sndh"),
                List.of(tune), "T", null, null, true, true);
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> MkSndh.build(options, core));
        String reason = String.valueOf(refused.getMessage());
        assertTrue(reason.contains("flags"), reason);
    }

    private static int word(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    private static long longAt(byte[] bytes, int at) {
        return ((long) word(bytes, at) << 16) | word(bytes, at + 2);
    }
}
