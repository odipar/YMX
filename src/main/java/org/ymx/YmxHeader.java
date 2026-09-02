package org.ymx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.st4.St4Decompressor;
import org.st4.St4Format;

/**
 * The handful of {@code .ymx} header fields the build tools need: what the
 * player must be assembled for, and what the SNDH tags declare.
 *
 * <p>Two of them are not in the YMX header. The unit size lives in the low
 * byte of the first embedded ST4 container's signature, which is why this
 * reads a section offset first; the channel-to-timer map lives in the T
 * stream, whose first frame this unpacks.
 */
public record YmxHeader(int ring, int chunk, int unit, int hz, int flags, int frames,
                        int timers) {

    /** Whether the tune reads the same at any unit size: every section is
     * stored, so no ST4 signature names one. {@link #unit} is 0. */
    public boolean anyUnit() {
        return unit == 0;
    }

    /**
     * The MFP timers the tune claims, one bit per timer, {@code 1 << } the
     * timer number {@link YmxFormat#TIMER_A} and its neighbours give. The
     * player claims one timer per timer channel the flags mark, and
     * {@link #timers} says which timer that channel runs on.
     */
    public int claimedTimers() {
        int claimed = 0;
        for (int channel = 0; channel < YmxFormat.CHANNELS; channel++) {
            if ((flags & YmxFormat.flagChannel(channel)) != 0) {
                claimed |= 1 << YmxFormat.timerOf(timers, channel);
            }
        }
        return claimed;
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
                    + " - repack the tune from its .ym source");
        }
        // A stored section carries no signature, so the unit size comes from
        // the first section that is a container. A tune short enough to store
        // every section reads the same at any unit size, and its unit here
        // is 0.
        int section = container(file, YmxFormat.OFFSET_SECTION_TABLE);
        if (section + 3 >= file.length) {
            throw new IOException(path + " has no readable first section");
        }
        return new YmxHeader(word(file, YmxFormat.OFFSET_RING_SIZE),
                word(file, YmxFormat.OFFSET_CHUNK),
                section == 0 ? 0 : file[section + 3] & 0xFF,
                word(file, YmxFormat.OFFSET_PLAYER_HZ),
                word(file, YmxFormat.OFFSET_FLAGS),
                (int) longAt(file, YmxFormat.OFFSET_FRAMES),
                timerMap(file, path));
    }

    /**
     * The T stream's first frame: the packer writes one channel-to-timer map
     * over a whole tune, so frame 0 gives the map. A container says its own
     * size, so what is unpacked is the rest of the file from the section's
     * first byte.
     */
    private static int timerMap(byte[] file, Path path) throws IOException {
        long entry = longAt(file,
                YmxFormat.OFFSET_SECTION_TABLE + 4 * YmxFormat.STREAM_T);
        int from = (int) YmxFormat.sectionOffset(entry);
        if (entry == 0 || from >= file.length) {
            throw new IOException(path + " has no timer stream");
        }
        if (YmxFormat.isStored(entry)) {
            return file[from] & 0xFF;
        }
        St4Format.Container section;
        try {
            section = St4Format.read(Arrays.copyOfRange(file, from, file.length));
        } catch (IllegalArgumentException e) {
            throw new IOException(path + ": its timer stream is not readable: "
                    + e.getMessage());
        }
        return St4Decompressor.decompress(section.control(), section.literal(),
                section.byteOffsets(), section.wordOffsets(), section.unit(),
                section.size())[0] & 0xFF;
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
