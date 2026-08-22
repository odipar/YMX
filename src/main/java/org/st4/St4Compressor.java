package org.st4;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Writes an ST4 parse out as three streams.
 *
 * <p>Stream A carries nothing but bits - the block-type flags and the gamma
 * lengths - so it holds no byte-sized value that a word-wide refill could trip
 * over. Stream B carries nothing but literal units, so its first byte is as
 * aligned as the caller places it and every literal run is a whole number of
 * units. Streams C and D carry the offsets, split by width: bytes in C, words
 * in D, so each is uniform and D is word-aligned by construction.
 *
 * <p>A word offset is written as {@code -offset * unit}, which is exactly what
 * the 68000 decoders keep in a register, so they install it with one move and
 * no arithmetic. A byte offset is written as {@code bank * 256 + 256 - offset},
 * which those decoders read into a register whose high byte is already $FF, so
 * the value arrives pre-negated too.
 *
 * <p>Stream A is padded to an even length. The 68000 decoders refill their bit
 * queue with a {@code move.w}, so the last refill of a stream must find a whole
 * word even when the bits themselves stopped mid-byte.
 *
 * <p>Matches longer than {@code maxOpLength} units are split, as in jx1: the
 * 68000 decoders count an operation's remaining length in a word, so nothing
 * may exceed 65535 units. A literal run cannot be split - after a literal run
 * a 0 bit means a match, so the format has no way to say "more literals" - and
 * {@link Result#longestOp()} reports what actually came out.
 */
public final class St4Compressor {

    /** The three streams, and what the caller needs to know about them. */
    public record Result(byte[] control, byte[] literal, byte[] byteOffsets,
                         byte[] wordOffsets, int unit, int paddedSize, int longestOp,
                         int operations) {

        /** Bytes all four streams take together, which is what a comparison wants. */
        public int packedSize() {
            return control.length + literal.length + byteOffsets.length
                    + wordOffsets.length;
        }
    }

    private final int[] units;
    private final int unit;
    private byte[] control = new byte[256];
    private int controlIndex;
    private byte[] literal;
    private int literalIndex;
    private byte[] byteOffsets = new byte[64];
    private int byteOffsetIndex;
    private byte[] wordOffsets = new byte[64];
    private int wordOffsetIndex;
    private int bitMask;
    private int bitIndex;
    private int longestOp;
    private int operations;

    private St4Compressor(int[] units, int unit) {
        this.units = units;
        this.unit = unit;
        this.literal = new byte[Math.max(unit, units.length * unit)];
    }

    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength) {
        return new St4Compressor(units, unit).run(optimal, maxOpLength);
    }

    private Result run(St4Block optimal, int maxOpLength) {
        // Un-reverse the chain; its head is the parser's fake block.
        var blocks = new ArrayDeque<St4Block>();
        for (St4Block block = optimal; block != null; block = block.chain()) {
            blocks.push(block);
        }
        St4Block previous = blocks.pop();

        int readIndex = 0;
        int lastOffset = St4Optimizer.INITIAL_OFFSET;
        boolean first = true;
        boolean afterLiterals = false;

        for (St4Block block : blocks) {
            int length = block.index() - previous.index();
            previous = block;

            if (block.offset() == 0) {
                if (first) {
                    first = false;                  // a stream opens with literals
                } else {
                    writeBit(false);
                }
                writeInterlacedEliasGamma(length);
                for (int i = 0; i < length; i++) {
                    Units.write(literal, literalIndex, units[readIndex++], unit);
                    literalIndex += unit;
                }
                afterLiterals = true;
                operations++;
                longestOp = Math.max(longestOp, length);
            } else {
                int offset = block.offset();
                // Split evenly rather than greedily: every piece after the first
                // has to be a new-offset match, and those cannot be shorter than
                // two units, so a greedy remainder of one would be unwritable.
                int pieces = maxOpLength < 3 ? 1 : (length - 1) / maxOpLength + 1;
                int base = length / pieces;
                int wider = length % pieces;
                for (int piece = 0; piece < pieces; piece++) {
                    int size = base + (piece < wider ? 1 : 0);
                    if (afterLiterals && offset == lastOffset) {
                        writeBit(false);
                        writeInterlacedEliasGamma(size);
                    } else {
                        writeBit(true);
                        writeOffsetOf(offset);
                        writeInterlacedEliasGamma(size - 1);
                        lastOffset = offset;
                    }
                    afterLiterals = false;
                    operations++;
                    readIndex += size;
                    longestOp = Math.max(longestOp, size);
                }
            }
        }

        // End marker: the one control code that names no stream at all.
        writeBit(true);
        writeBit(false);
        writeBit(true);

        return new Result(Arrays.copyOf(control, controlIndex + (controlIndex & 1)),
                Arrays.copyOf(literal, literalIndex),
                Arrays.copyOf(byteOffsets, byteOffsetIndex),
                Arrays.copyOf(wordOffsets, wordOffsetIndex), unit,
                units.length * unit, longestOp, operations);
    }

    /**
     * The two class bits, then the offset itself into whichever stream it
     * belongs to. The class bits are also what keeps the operation an even
     * number of bits long, which is what lets the decoder skip refill checks.
     */
    private void writeOffsetOf(int offset) {
        if (offset <= St4Format.BYTE_OFFSET_LIMIT) {
            int bank = (offset - 1) / 256;              // 0 for 1..256, 1 for 257..512
            writeBit(true);
            writeBit(bank != 0);
            if (byteOffsetIndex == byteOffsets.length) {
                byteOffsets = Arrays.copyOf(byteOffsets, byteOffsets.length * 2);
            }
            byteOffsets[byteOffsetIndex++] = (byte) (bank * 256 + 256 - offset);
        } else {
            int scaled = offset * unit;
            assert scaled <= 32768 : "a word offset must fit -offset*k in a signed word";
            writeBit(false);
            writeBit(false);
            if (wordOffsetIndex + 2 > wordOffsets.length) {
                wordOffsets = Arrays.copyOf(wordOffsets, wordOffsets.length * 2);
            }
            wordOffsets[wordOffsetIndex++] = (byte) (-scaled >> 8);
            wordOffsets[wordOffsetIndex++] = (byte) -scaled;
        }
    }

    private void writeControl(int value) {
        if (controlIndex == control.length) {
            control = Arrays.copyOf(control, control.length * 2);
        }
        control[controlIndex++] = (byte) value;
    }

    /**
     * Bits live in stream A, in the byte reserved when the reservoir ran dry -
     * so a set bit patches that byte where it already sits.
     */
    private void writeBit(boolean value) {
        if (bitMask == 0) {
            bitMask = 128;
            bitIndex = controlIndex;
            writeControl(0);
        }
        if (value) {
            control[bitIndex] |= (byte) bitMask;
        }
        bitMask >>= 1;
    }

    private void writeInterlacedEliasGamma(int value) {
        for (int bit = Integer.highestOneBit(value) >> 1; bit != 0; bit >>= 1) {
            writeBit(true);
            writeBit((value & bit) != 0);
        }
        writeBit(false);
    }
}
