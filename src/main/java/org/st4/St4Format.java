package org.st4;

/**
 * ST4: ZX1's three block types at a chosen unit granularity, split across three
 * streams so a 68000 can read each of them the fastest way that exists for it.
 *
 * <p>A ZX1 stream interleaves everything: flag bits, gamma lengths, offset
 * bytes and literal payload all share one byte sequence. Two things follow from
 * that, and both cost real cycles. The literal payload lands at whatever parity
 * the preceding control bytes leave it at, so a 68000 can only ever copy it a
 * byte at a time - a {@code move.w} or {@code move.l} needs both source and
 * destination even. And the bit reservoir sits in the same sequence as the
 * offset bytes, so it can only ever be refilled a byte at a time: a
 * {@code move.w (a0)+,d0} would desynchronise the moment an offset byte moved
 * the pointer by one. ST4 splits all three apart:
 *
 * <ul>
 *   <li><b>stream A</b> - nothing but bits: the block-type flags and the
 *       interlaced Elias gamma lengths. Because no byte-sized read ever comes
 *       out of it, the reservoir refills a word at a time, halving the
 *       refills.</li>
 *   <li><b>stream B</b> - the literal payload, nothing else. Its alignment is
 *       therefore a property of the format rather than luck.</li>
 *   <li><b>stream C</b> - the byte offsets, one byte each.</li>
 *   <li><b>stream D</b> - the word offsets, one word each, so it is
 *       word-aligned by construction and a match source is one
 *       {@code move.w} away.</li>
 * </ul>
 *
 * <p>Which of the two an offset came from used to be encoded in the low bit of
 * the offset byte itself, ZX1-style; it is now a control code, so neither
 * stream carries a selector and each one holds values of a single width. That
 * costs <em>two</em> control bits per new-offset match rather than one, and the
 * second is not a filler. Stream A's decoder skips the refill check on every
 * bit but a gamma continuation, which is sound only because each operation
 * contributes an even number of bits - a gamma is odd, its flag makes it even.
 * One extra bit would make it odd again and cost a check on every data bit, far
 * more than the offsets save. So a new-offset match spends two: the first says
 * byte or word, and the second picks which 256-unit bank a byte offset names,
 * which keeps the split from costing ratio.
 *
 * <pre>
 *   1 0   byte offset from stream C, 1..256 units
 *   1 1   byte offset from stream C, 257..512 units
 *   0 0   word offset from stream D
 *   0 1   end of stream
 * </pre>
 *
 * <p>On top of that, lengths and offsets are counted in <em>units</em> of
 * {@code k} bytes, where k is 1, 2 or 4. At k = 1 that is ZX1's parse with the
 * payload moved out. At k = 2 or 4 every operation covers k times as much
 * output, so the decoder runs k times fewer operations and can copy k bytes at
 * a time - and, for free, a one-byte offset reaches 128 units instead of 128
 * bytes.
 *
 * <p>The cost is quantisation: an offset or length that is not a multiple of k
 * cannot be expressed, so the packer only finds matches that line up with the
 * unit grid. That is a bargain on data whose structure is k-aligned and a
 * disaster on data that is not, which is why the mode is chosen per asset and
 * recorded in the header.
 *
 * <p>The header is twenty bytes and holds only what cannot be worked out:
 *
 * <pre>
 *   0   4  signature: 'S', '4', format version, k
 *   4   4  O, the output size in bytes; always a multiple of k
 *   8   4  stream B, as a byte offset from the start of the header
 *  12   4  stream C
 *  16   4  stream D
 *  20  ..  stream A, then B, then C, then D
 * </pre>
 *
 * <p>Everything else follows from those. Stream A begins where the header ends,
 * so it needs no field. No length is stored: the streams are laid out in order,
 * so each one runs to the next, and the last runs to the end of the file. None
 * of the four decoders reads a length anyway - it stops on the end marker and
 * the other streams run out with it.
 *
 * <p>The shape is chosen for the 68000 that has to load it. The signature packs
 * the magic, the version AND the unit size into one long, so a decoder built
 * for a particular k proves an asset matches it with a single {@code cmp.l}
 * rather than three compares. Each stream starts on a long boundary, so a wide
 * move is safe at every unit size. And the offsets are relative to the header
 * rather than absolute, so a loader that has the asset's address in a register
 * needs one {@code adda.l} per stream and no relocation:
 *
 * <pre>
 *         lea     asset(pc),a3
 *         cmp.l   #ST4_SIGNATURE,(a3)     ; magic, version and k in one compare
 *         bne.s   wrong_asset
 *         lea     20(a3),a0               ; stream A, where the header ends
 *         movea.l a3,a2
 *         adda.l  8(a3),a2                ; stream B
 *         movea.l a3,a4
 *         adda.l  12(a3),a4               ; stream C
 *         movea.l a3,a5
 *         adda.l  16(a3),a5               ; stream D
 * </pre>
 *
 * <p>A derived length can be up to three bytes longer than what the packer
 * wrote, because a stream is padded to the next long boundary. Nothing reads
 * the padding.
 */
public final class St4Format {

    /** {@code 'S4'}, the top half of every signature. */
    public static final int MAGIC = 0x53340000;

    /** Version 4 cut the header to what cannot be derived. */
    public static final int VERSION = 4;

    public static final int OFFSET_SIGNATURE = 0;
    public static final int OFFSET_SIZE = 4;
    public static final int OFFSET_LITERAL = 8;
    public static final int OFFSET_BYTE_OFFSETS = 12;
    public static final int OFFSET_WORD_OFFSETS = 16;
    public static final int HEADER_SIZE = 20;

    /**
     * Magic, version and unit size in one long, so a decoder built for one k
     * checks an asset against itself with a single {@code cmp.l}.
     */
    public static int signature(int unit) {
        return MAGIC | (VERSION << 8) | unit;
    }

    /**
     * The furthest any offset reaches, in BYTES. A word offset is stored as
     * {@code -offset * k}, which the decoder installs unchanged, so the limit
     * is what fits a signed word rather than anything about the format.
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

    /** What a container holds: the four streams, the unit size and the output size. */
    public record Container(int unit, int size, byte[] control, byte[] literal,
                            byte[] byteOffsets, byte[] wordOffsets) {}

    /**
     * Reads a container, checking everything a decoder would otherwise accept.
     * The streams it returns may carry up to three bytes of alignment padding,
     * since no length is stored and each stream runs to the next.
     *
     * @throws IllegalArgumentException with a printable reason if it is not an
     *     ST4 file this build reads, or if the offsets do not describe
     *     four streams laid out in order inside it
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
                java.util.Arrays.copyOfRange(file, edge[3], edge[4]));
    }

    private static int longAt(byte[] file, int at) {
        return (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
                | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
    }
}
