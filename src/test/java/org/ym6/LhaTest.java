package org.ym6;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The LHA unwrapping in front of the reader. The {@code -lh5-} inflater
 * itself is a straight port of ST-Sound's depacker, verified byte-identical
 * against the reference implementation across a 514-archive library; what
 * these tests pin down is the header handling and the stored path, which a
 * test can build for itself.
 */
final class LhaTest {

    /** A level-0 {@code -lh0-} (stored) archive around the given data. */
    private static byte[] stored(byte[] data, String method) {
        String name = "TUNE.YM";
        var out = new ByteArrayOutputStream();
        int headerSize = 20 + name.length() + 2;
        out.write(headerSize);
        out.write(0);                               // checksum, patched below
        out.writeBytes(method.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (int shift = 0; shift < 32; shift += 8) {
            out.write((data.length >> shift) & 0xFF);   // compressed size
        }
        for (int shift = 0; shift < 32; shift += 8) {
            out.write((data.length >> shift) & 0xFF);   // original size
        }
        out.writeBytes(new byte[4]);                // time and date
        out.write(0x20);                            // attribute
        out.write(0);                               // header level 0
        out.write(name.length());
        out.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(0);                               // crc16 of the data,
        out.write(0);                               // not checked
        out.writeBytes(data);
        byte[] archive = out.toByteArray();
        int sum = 0;
        for (int i = 2; i < 2 + headerSize; i++) {
            sum += archive[i] & 0xFF;
        }
        archive[1] = (byte) sum;
        return archive;
    }

    @Test
    void anArchivedTuneReadsLikeItsContents() {
        int frames = 200;
        byte[][] registers = Ym6TestData.registers(frames);
        byte[] raw = Ym6TestData.file(registers, frames, true);
        Ym6Reader.Song direct = Ym6Reader.read(raw);
        Ym6Reader.Song wrapped = Ym6Reader.read(stored(raw, "-lh0-"));

        assertEquals(direct.format(), wrapped.format());
        assertEquals(direct.frames(), wrapped.frames());
        for (int register = 0; register < Ym6Reader.Song.YM_REGISTERS; register++) {
            assertArrayEquals(direct.registers()[register], wrapped.registers()[register],
                    "register " + register);
        }
    }

    @Test
    void aRawFileIsNotMistakenForAnArchive() {
        byte[] raw = Ym6TestData.file(Ym6TestData.registers(50), 50, true);
        assertTrue(!Lha.isArchive(raw));
    }

    @Test
    void aDamagedArchiveSaysWhatIsWrongWithIt() {
        byte[] raw = Ym6TestData.file(Ym6TestData.registers(50), 50, true);

        byte[] badSum = stored(raw, "-lh0-");
        badSum[1] ^= 0x55;
        Ym6Reader.FormatException checksum = assertThrows(Ym6Reader.FormatException.class,
                () -> Ym6Reader.read(badSum));
        assertTrue(String.valueOf(checksum.getMessage()).contains("checksum"));

        byte[] wrongLevel = stored(raw, "-lh0-");
        wrongLevel[20] = 1;
        wrongLevel[1] += 1;                         // keep the checksum valid
        Ym6Reader.FormatException level = assertThrows(Ym6Reader.FormatException.class,
                () -> Ym6Reader.read(wrongLevel));
        assertTrue(String.valueOf(level.getMessage()).contains("level"));

        byte[] method = stored(raw, "-lh6-");
        Ym6Reader.FormatException unsupported = assertThrows(Ym6Reader.FormatException.class,
                () -> Ym6Reader.read(method));
        assertTrue(String.valueOf(unsupported.getMessage()).contains("-lh6-"));
    }
}
