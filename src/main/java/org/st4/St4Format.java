package org.st4;

/**
 * ST4: ZX1's three block types at a unit size of 1, 2 or 4 bytes, in four
 * streams that a 68000 reads each at its own width.
 *
 * <p>Stream A holds the bits, the flags and the interlaced Elias gamma
 * lengths, read a word at a time. Stream B holds the literal units, stream C
 * the byte offsets and stream D the word offsets. Two class bits select the
 * stream an offset comes from:
 *
 * <pre>
 *   1 0   byte offset from stream C, 1..256 units
 *   1 1   byte offset from stream C, 257..512 units
 *   0 0   word offset from stream D
 *   0 1   end of stream; one more bit repeats it
 * </pre>
 *
 * Every operation is an even number of bits, so a decoder checks its refill
 * on gamma continuation bits alone.
 *
 * <p>Lengths and offsets count units of k bytes. At k = 1 this is ZX1's
 * parse with the payload moved out; at 2 or 4 an operation covers k times as
 * much, the decoder copies k bytes at a time, and only k-aligned matches can
 * be expressed.
 *
 * <p>An offset of at most the window M, recorded in the header, is a match
 * from the output. An offset beyond M copies {@code offset - M} units from
 * behind the literal read pointer, leaves the pointer where it is, and
 * advances the offset by what it copied, so a rep after a copy resumes past
 * it. A copy is shorter than its distance. A stream packed without copies
 * has no offset beyond M.
 *
 * <p>The end code's extra bit: 0 ends the stream; 1 repeats it from a loop
 * point R, so it decodes as {@code units[0..R) units[R..O)} forever, with
 * the distance O-R as one last word in stream D, matched endlessly. A loop
 * longer than the window is replayed instead: the header gives the rewind
 * point in bytes, the caller saves the decoder's registers there and
 * restores them, all but the write pointer, at O. The packer parses the loop
 * on its own, so every pass sees the same history.
 *
 * <p>The header is twenty-eight bytes:
 *
 * <pre>
 *   0   4  signature: 'S', '4', format version, k
 *   4   4  O, the output size in bytes, a multiple of k
 *   8   4  stream B, the literals, as a byte offset from the header
 *  12   4  stream C, the byte offsets
 *  16   4  stream D, the word offsets
 *  20   4  the rewind point in bytes, or $FFFFFFFF when there is none
 *  24   4  M, the window in units
 *  28  ..  streams A, B, C and D, in that order, each on a long boundary
 * </pre>
 *
 * Stream A begins where the header ends and each stream runs to the next: no
 * length is stored, and the decoders stop on the end marker. The signature
 * holds magic, version and k in one long, so a decoder built for one k checks
 * an asset with one {@code cmp.l}; the starts are header-relative, so opening
 * a container is one {@code adda.l} per stream. A stream cut at the next
 * start can be up to three bytes of padding longer than what was written.
 */
public final class St4Format {

    /** {@code 'S4'}, the top half of every signature. */
    public static final int MAGIC = 0x53340000;

    /** The format version, the third byte of the signature. */
    public static final int VERSION = 7;

    public static final int OFFSET_SIGNATURE = 0;
    public static final int OFFSET_SIZE = 4;
    public static final int OFFSET_LITERAL = 8;
    public static final int OFFSET_BYTE_OFFSETS = 12;
    public static final int OFFSET_WORD_OFFSETS = 16;
    public static final int OFFSET_REWIND = 20;
    public static final int OFFSET_WINDOW = 24;
    public static final int HEADER_SIZE = 28;

    /** The rewind field of a stream that ends or loops by itself. */
    public static final int NO_REWIND = -1;

    /**
     * Magic, version and unit size in one long: a decoder checks all three
     * with one {@code cmp.l}.
     */
    public static int signature(int unit) {
        return MAGIC | (VERSION << 8) | unit;
    }

    /**
     * The furthest any offset reaches, in bytes. A word offset is stored as
     * {@code -offset * k} and the decoder installs it unchanged, so the limit
     * is what fits a signed word.
     */
    public static final int MAX_OFFSET = 32512;

    /** The furthest a byte offset reaches, in units: two banks of 256. */
    public static final int BYTE_OFFSET_LIMIT = 512;

    /** The longest operation the 68000 decoders can count, in units. */
    public static final int MAX_OP = 65535;

    private St4Format() {}

    public static boolean isUnitSize(int unit) {
        return unit == 1 || unit == 2 || unit == 4;
    }

    /** The reason {@code unit} cannot be used, or an empty string. */
    public static String checkUnit(int unit) {
        return isUnitSize(unit) ? "" : "unit size " + unit + " is not 1, 2 or 4";
    }

    /** How far back a match may reach at this unit size, in units. */
    public static int maxOffsetUnits(int unit) {
        return MAX_OFFSET / unit;
    }

    /**
     * What a container holds: the four streams, the unit size, the output
     * size, the rewind point in bytes or {@link #NO_REWIND}, and the window
     * in units.
     */
    public record Container(int unit, int size, byte[] control, byte[] literal,
                            byte[] byteOffsets, byte[] wordOffsets, int rewind, int window) {}

    /**
     * Reads a container, checking what a decoder trusts. The streams returned
     * may carry up to three bytes of alignment padding, since each runs to
     * the next.
     *
     * @throws IllegalArgumentException with a printable reason when the file
     *     is not an ST4 file of this version, or its streams do not lie in
     *     order inside it
     */
    public static Container read(byte[] file) {
        if (file.length < HEADER_SIZE) {
            throw new IllegalArgumentException("too short to be an ST4 file");
        }
        int signature = longAt(file, OFFSET_SIGNATURE);
        if ((signature & 0xFFFF0000) != MAGIC) {
            throw new IllegalArgumentException("not an ST4 file");
        }
        int version = (signature >> 8) & 0xFF;
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "ST4 format version " + version + ", not " + VERSION);
        }
        int unit = signature & 0xFF;
        String problem = checkUnit(unit);
        if (!problem.isEmpty()) {
            throw new IllegalArgumentException(problem);
        }
        int size = longAt(file, OFFSET_SIZE);
        if (size < 0 || size % unit != 0) {
            throw new IllegalArgumentException(
                    "output size " + size + " is not a whole number of " + unit + "-byte units");
        }
        int rewind = longAt(file, OFFSET_REWIND);
        if (rewind != NO_REWIND && (rewind < 0 || rewind >= size || rewind % unit != 0)) {
            throw new IllegalArgumentException(
                    "rewind point " + rewind + " is not a unit of the output");
        }
        int window = longAt(file, OFFSET_WINDOW);
        if (window < 1 || window > maxOffsetUnits(unit)) {
            throw new IllegalArgumentException(
                    "window " + window + " is not 1.." + maxOffsetUnits(unit) + " units");
        }

        // The streams lie in the file as A, B, C, D.
        int[] edge = {HEADER_SIZE, longAt(file, OFFSET_LITERAL),
                      longAt(file, OFFSET_BYTE_OFFSETS), longAt(file, OFFSET_WORD_OFFSETS),
                      file.length};
        for (int i = 1; i < edge.length - 1; i++) {
            if (edge[i] % 4 != 0) {
                throw new IllegalArgumentException(
                        "stream " + "ABCD".charAt(i) + " does not start on a long boundary");
            }
            if (edge[i] < edge[i - 1] || edge[i] > file.length) {
                throw new IllegalArgumentException(
                        "stream " + "ABCD".charAt(i) + " lies outside the file");
            }
        }
        return new Container(unit, size,
                java.util.Arrays.copyOfRange(file, edge[0], edge[1]),
                java.util.Arrays.copyOfRange(file, edge[1], edge[2]),
                java.util.Arrays.copyOfRange(file, edge[2], edge[3]),
                java.util.Arrays.copyOfRange(file, edge[3], edge[4]), rewind, window);
    }

    private static int longAt(byte[] file, int at) {
        return (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
                | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
    }
}
