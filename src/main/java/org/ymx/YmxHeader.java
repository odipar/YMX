package org.ymx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The handful of {@code .ymx} header fields the build tools need: what the
 * player must be assembled for, and what the SNDH tags declare.
 *
 * <p>The unit size is not in the ymx header at all - it lives in the low byte
 * of the first embedded ST4 container's signature, which is why this reads a
 * section offset first. A tune that loops from the start has no intro
 * sections, so the loop table answers when the intro table is empty.
 */
public record YmxHeader(int ring, int chunk, int unit, int hz, int flags, int frames) {

    /** Bit 0 of the flags: the tune loops instead of ending. */
    public boolean loops() {
        return (flags & YmxFormat.FLAG_LOOPS) != 0;
    }

    /** What SNDH's FRMS tag wants: a looping tune is endless, so zero. */
    public int frms() {
        return loops() ? 0 : frames;
    }

    /** {@code n<ring> c<chunk> k<unit>}, the configuration one player build
     * serves - the string the mismatch messages compare. */
    public String shape() {
        return "n" + ring + " c" + chunk + " k" + unit;
    }

    public static YmxHeader read(Path path) throws IOException {
        byte[] file = Files.readAllBytes(path);
        if (file.length < YmxFormat.HEADER_SIZE
                || word(file, YmxFormat.OFFSET_MAGIC) != (YmxFormat.MAGIC >>> 16)
                || word(file, YmxFormat.OFFSET_MAGIC + 2) != (YmxFormat.MAGIC & 0xFFFF)) {
            throw new IOException(path + " is not a .ymx file");
        }
        int version = word(file, YmxFormat.OFFSET_VERSION);
        if (version != YmxFormat.VERSION) {
            throw new IOException(path + " is format version " + version
                    + ", this build reads " + YmxFormat.VERSION
                    + " - repack the tune from its .ym source");
        }
        int section = (int) longAt(file, YmxFormat.OFFSET_INTRO_TABLE);
        if (section == 0) {
            section = (int) longAt(file, YmxFormat.OFFSET_LOOP_TABLE);
        }
        if (section + 3 >= file.length) {
            throw new IOException(path + " has no readable first section");
        }
        return new YmxHeader(word(file, YmxFormat.OFFSET_RING_SIZE),
                word(file, YmxFormat.OFFSET_CHUNK),
                file[section + 3] & 0xFF,       // the ST4 signature's low byte
                word(file, YmxFormat.OFFSET_PLAYER_HZ),
                word(file, YmxFormat.OFFSET_FLAGS),
                (int) longAt(file, YmxFormat.OFFSET_FRAMES));
    }

    private static int word(byte[] file, int at) {
        return ((file[at] & 0xFF) << 8) | (file[at + 1] & 0xFF);
    }

    private static long longAt(byte[] file, int at) {
        return ((long) word(file, at) << 16) | word(file, at + 2);
    }
}
