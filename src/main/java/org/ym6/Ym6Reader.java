package org.ym6;

import java.nio.charset.StandardCharsets;
import org.ymx.Tune;

/**
 * Reads a YM5!/YM6! register dump, as described by
 * <a href="http://leonard.oxg.free.fr/ymformat.html">the YM format page</a>.
 *
 * <p>This class is the boundary. Everything here speaks the YM format's own
 * language - digidrums, SID voices, effect slots, TP and TC - because those
 * are the names of the bytes it is reading, and calling them anything else
 * would misdescribe the file. The engine's vocabulary starts downstream, at
 * the {@link Tune} {@link YmEffects} builds out of this;
 * {@code doc/terminology.md} maps the two.
 *
 * <p>The layout is a fixed header, optional extra data, the digidrum samples,
 * three NUL-terminated strings, and then the frames: either 16 vectors of one
 * register each (the interleaved option) or one 16-byte record per frame. Both
 * are accepted here and both come out as 16 register vectors, because that is
 * the shape YMX packs.
 *
 * <p>Distributed {@code .ym} files are usually LHA archives holding this data;
 * the reader unpacks them itself, through {@link Lha}.
 */
public final class Ym6Reader {

    /** One parsed tune, in the file's own terms and nothing else's: what the
     *  header said, the frames as read, and the samples as stored.
     *
     *  <p>{@code registers[r][frame]} is R{@code r}'s raw value, all sixteen
     *  of them - the two I/O ports included, because in this format they are
     *  where an effect's timer count is filed and {@link YmEffects} has to
     *  read them. Only fourteen go on to the engine. */
    public record Song(String format, int frames, int playerHz, long masterClock, long loopFrame,
                       boolean interleaved, long attributes, byte[][] drums, String name,
                       String author, String comment, byte[][] registers) {

        /** Register count in the file: R0..R15, the last two being the I/O ports. */
        public static final int YM_REGISTERS = 16;

        /** Attribute bit 2: drum samples hold 4-bit values, one per byte. */
        public static final int A_DRUM4BITS = 4;

        /** The digidrum samples exactly as stored: 8-bit unsigned by default,
         * 4-bit values in bytes when {@link #A_DRUM4BITS} is set. */
        public byte[][] drums() {
            return drums;
        }

        public int digidrums() {
            return drums.length;
        }
    }

    /** Thrown for anything this reader will not accept, with a usable message. */
    public static final class FormatException extends RuntimeException {
        public FormatException(String message) {
            super(message);
        }
    }

    private final byte[] data;
    private int at;

    private Ym6Reader(byte[] data) {
        this.data = data;
    }

    public static Song read(byte[] data) {
        if (Lha.isArchive(data)) {
            try {
                data = Lha.unpack(data);
            } catch (IllegalArgumentException e) {
                throw new FormatException("cannot unpack this .ym's LHA wrapper: "
                        + e.getMessage());
            }
        }
        return new Ym6Reader(data).run();
    }

    private Song run() {
        String format = ascii(4);
        if (!format.equals("YM6!") && !format.equals("YM5!")) {
            throw new FormatException("not a YM5!/YM6! file (starts with \"" + format
                    + "\"); YM2/YM3 and packed .ym files are not supported");
        }
        String check = ascii(8);
        if (!check.equals("LeOnArD!")) {
            throw new FormatException("missing the LeOnArD! check string after " + format);
        }

        long frames = u32();
        long attributes = u32();
        int digidrums = u16();
        long masterClock = u32();
        int playerHz = u16();
        long loopFrame = u32();
        int additional = u16();
        skip(additional, "additional data");

        byte[][] drums = new byte[digidrums][];
        for (int i = 0; i < digidrums; i++) {
            long size = u32();
            if (size < 0 || size > data.length - at) {
                throw new FormatException("truncated file: digidrum " + i + " claims "
                        + size + " bytes");
            }
            drums[i] = new byte[(int) size];
            System.arraycopy(data, at, drums[i], 0, (int) size);
            at += size;
        }
        String name = string();
        String author = string();
        String comment = string();

        if (frames <= 0 || frames > Integer.MAX_VALUE) {
            throw new FormatException("unusable frame count " + frames);
        }
        if (playerHz <= 0) {
            throw new FormatException("unusable player frequency " + playerHz + " Hz");
        }
        int count = (int) frames;
        boolean interleaved = (attributes & 1) != 0;
        byte[][] registers = interleaved ? readInterleaved(count) : readPerFrame(count);

        // 'End!' closes the file. Some tools omit it; the frames are all read
        // by now, so this only reports, it does not reject.
        if (at + 4 <= data.length && !ascii(4).equals("End!")) {
            System.err.println("Warning: no End! marker after the frames");
        }
        return new Song(format, count, playerHz, masterClock, loopFrame, interleaved,
                attributes, drums, name, author, comment, registers);
    }

    private byte[][] readInterleaved(int frames) {
        need((long) frames * Song.YM_REGISTERS, "interleaved frame data");
        byte[][] registers = new byte[Song.YM_REGISTERS][];
        for (int r = 0; r < Song.YM_REGISTERS; r++) {
            registers[r] = new byte[frames];
            System.arraycopy(data, at, registers[r], 0, frames);
            at += frames;
        }
        return registers;
    }

    private byte[][] readPerFrame(int frames) {
        need((long) frames * Song.YM_REGISTERS, "frame data");
        byte[][] registers = new byte[Song.YM_REGISTERS][frames];
        for (int frame = 0; frame < frames; frame++) {
            for (int r = 0; r < Song.YM_REGISTERS; r++) {
                registers[r][frame] = data[at++];
            }
        }
        return registers;
    }

    private void need(long bytes, String what) {
        if (bytes > data.length - at) {
            throw new FormatException("truncated file: " + what + " needs " + bytes
                    + " bytes but only " + (data.length - at) + " are left");
        }
    }

    private void skip(int bytes, String what) {
        if (bytes < 0) {
            throw new FormatException("negative size for " + what);
        }
        need(bytes, what);
        at += bytes;
    }

    private String ascii(int bytes) {
        need(bytes, "header field");
        String text = new String(data, at, bytes, StandardCharsets.US_ASCII);
        at += bytes;
        return text;
    }

    private String string() {
        int end = at;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        if (end == data.length) {
            throw new FormatException("unterminated header string");
        }
        String text = new String(data, at, end - at, StandardCharsets.ISO_8859_1);
        at = end + 1;
        return text;
    }

    private int u16() {
        need(2, "header field");
        return ((data[at++] & 0xFF) << 8) | (data[at++] & 0xFF);
    }

    private long u32() {
        return ((long) u16() << 16) | u16();
    }
}
