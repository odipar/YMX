package org.ymx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The handful of {@code .ymx} header fields the build tools need: what the
 * player must be assembled for, and what the SNDH tags declare.
 *
 * <p>The unit size is not in the YMX header - it lives in the low byte
 * of the first embedded ST4 container's signature, which is why this reads a
 * section offset first.
 */
public record YmxHeader(int ring, int chunk, int unit, int hz, int flags, int frames) {

    /** Whether the tune reads the same at any unit size: every section is
     * stored, so no ST4 signature names one. {@link #unit} is 0. */
    public boolean anyUnit() {
        return unit == 0;
    }

    /** Bit 0 of the flags: the tune starts over instead of ending. */
    public boolean loops() {
        return (flags & YmxFormat.FLAG_LOOPS) != 0;
    }

    /** What SNDH's FRMS tag requires: a tune that starts over is endless,
     * so zero. */
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
            throw new IOException(path + " is format version "
                    + YmxFormat.versionName(version) + ", this build reads "
                    + YmxFormat.versionName()
                    + " - repack the tune from its .ym or .ymr source");
        }
        // A stored section carries no signature, so the unit size comes from
        // the first section that is a container - out of either table, since
        // a file cut at its loop frame may store the frames before it and
        // pack the frames from it. A tune short enough to store every
        // section reads the same at any unit size, and its unit here is 0.
        int section = container(file, YmxFormat.OFFSET_SECTION_TABLE);
        long loopTable = longAt(file, YmxFormat.OFFSET_LOOP_TABLE);
        if (section == 0 && loopTable != 0) {
            section = container(file, (int) loopTable);
        }
        if (section + 3 >= file.length) {
            throw new IOException(path + " has no readable first section");
        }
        return new YmxHeader(word(file, YmxFormat.OFFSET_RING_SIZE),
                word(file, YmxFormat.OFFSET_CHUNK),
                section == 0 ? 0 : file[section + 3] & 0xFF,
                word(file, YmxFormat.OFFSET_PLAYER_HZ),
                word(file, YmxFormat.OFFSET_FLAGS),
                (int) longAt(file, YmxFormat.OFFSET_FRAMES));
    }

    /** The offset of one table's first section that is a container, or 0
     * where every section it locates is stored. */
    private static int container(byte[] file, int table) {
        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            long entry = longAt(file, table + 4 * stream);
            if (entry != 0 && !YmxFormat.isStored(entry)) {
                return (int) YmxFormat.sectionOffset(entry);
            }
        }
        return 0;
    }

    private static int word(byte[] file, int at) {
        return ((file[at] & 0xFF) << 8) | (file[at + 1] & 0xFF);
    }

    private static long longAt(byte[] file, int at) {
        return ((long) word(file, at) << 16) | word(file, at + 2);
    }
}
