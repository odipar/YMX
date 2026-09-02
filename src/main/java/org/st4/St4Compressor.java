package org.st4;

import java.util.ArrayDeque;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Writes a parse as the four streams: bits and gamma lengths in A, literal
 * units in B, byte offsets in C, word offsets in D. A word offset is written
 * as {@code -offset * unit} and a byte offset as
 * {@code bank * 256 + 256 - offset}, the values the 68000 decoders keep in a
 * register; stream A is padded to an even length for their word-wide refill.
 *
 * <p>A match longer than {@code maxOpLength} units is split, since the
 * decoders count an operation in a word; a literal run cannot be, and
 * {@link Result#longestOp()} reports it. A copy from the literal stream is
 * written as the window plus the literal units between its source and
 * itself, and is shorter than that count: the one copy that would not be
 * gives its last unit to a literal. The intro and the loop of a rewind
 * stream are two parses written back to back; two literal runs that meet at
 * the seam merge, and a one-unit rep the intro left no offset for is written
 * as a literal.
 */
public final class St4Compressor {

    /**
     * The four streams and their figures. {@code rewindIndex} is the loop
     * point of a stream the caller loops by rewind, in units, or -1;
     * {@code window} is what the header records; {@code copies} counts the
     * blocks copied from the literal stream.
     */
    public record Result(byte[] control, byte[] literal, byte[] byteOffsets,
                         byte[] wordOffsets, int unit, int paddedSize, int longestOp,
                         int operations, int rewindIndex, int window, int copies,
                         int controlBits, boolean repeatWord) {

        /** Bytes all four streams take together. */
        public int packedSize() {
            return control.length + literal.length + byteOffsets.length
                    + wordOffsets.length;
        }

        /**
         * Bits the parse cost, what a chain counts: everything written but
         * the end code, its repeat bit, the repeat's word and stream A's
         * padding.
         */
        public int bits() {
            return controlBits - 4 + 8 * (literal.length + byteOffsets.length
                    + wordOffsets.length) - (repeatWord ? 16 : 0);
        }
    }

    private final int[] units;
    private final int unit;
    private final int window;
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
    private int bitsWritten;
    private int longestOp;
    private int operations;
    private int copies;

    // The walk: where the next unit comes from, the literal run gathered but
    // not yet written, the offset the stream holds, whether the first block,
    // which has no flag, is still to come, and how many literal units precede
    // each position written so far.
    private int readIndex;
    private int pendingLiterals;
    private int lastOffset = St4Optimizer.INITIAL_OFFSET;
    private boolean first = true;
    private final int[] literalsBefore;

    private St4Compressor(int[] units, int unit, int window) {
        this.units = units;
        this.unit = unit;
        this.window = window;
        this.literal = new byte[Math.max(unit, units.length * unit)];
        this.literalsBefore = new int[units.length + 1];
    }

    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength) {
        return compress(optimal, units, unit, maxOpLength, -1);
    }

    /**
     * As above, repeating from unit {@code repeatIndex}: the stream decodes as
     * {@code units[0..R) units[R..O)} forever, the distance O-R written as
     * one last word offset. -1 ends the stream.
     */
    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength,
                                  int repeatIndex) {
        return compress(optimal, units, unit, maxOpLength, repeatIndex,
                St4Format.maxOffsetUnits(unit));
    }

    /**
     * As above, for a parse made at {@code window} units: the parse's matches
     * keep within it, and its copies are written as offsets beyond it.
     */
    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength,
                                  int repeatIndex, int window) {
        assert -1 <= repeatIndex && repeatIndex < units.length
                : "the loop point must be a unit of the stream itself";
        return new St4Compressor(units, unit, window).run(new St4Block[] {optimal},
                maxOpLength, repeatIndex, -1);
    }

    /**
     * A stream that loops by rewind: the intro {@code units[0..R)}, null when
     * R is 0, and the loop {@code units[R..O)} come from separate parses, so
     * no match in the loop reaches before unit {@code rewindIndex}, where the
     * caller saves the decoder's state. The stream ends plainly.
     */
    public static Result compressRewinding(@Nullable St4Block intro, St4Block loop,
                                           int[] units, int unit, int maxOpLength,
                                           int rewindIndex) {
        return compressRewinding(intro, loop, units, unit, maxOpLength, rewindIndex,
                St4Format.maxOffsetUnits(unit));
    }

    /** As above, for parses made at {@code window} units. */
    public static Result compressRewinding(@Nullable St4Block intro, St4Block loop,
                                           int[] units, int unit, int maxOpLength,
                                           int rewindIndex, int window) {
        assert 0 <= rewindIndex && rewindIndex < units.length
                : "the rewind point must be a unit of the stream itself";
        assert (intro == null) == (rewindIndex == 0) : "an intro exactly when there is one";
        St4Block[] chains = intro == null ? new St4Block[] {loop} : new St4Block[] {intro, loop};
        return new St4Compressor(units, unit, window).run(chains, maxOpLength, -1,
                rewindIndex);
    }

    private Result run(St4Block[] chains, int maxOpLength, int repeatIndex, int rewindIndex) {
        for (St4Block chain : chains) {
            // Un-reverse the chain; its head is the parser's fake block.
            var blocks = new ArrayDeque<St4Block>();
            for (St4Block block = chain; block != null; block = block.chain()) {
                blocks.push(block);
            }
            St4Block previous = blocks.pop();

            for (St4Block block : blocks) {
                int length = block.index() - previous.index();
                previous = block;

                if (block.offset() == 0) {
                    pendingLiterals += length;      // runs merge across a seam
                    continue;
                }
                if (block.offset() < 0) {
                    copy(-block.offset(), length, maxOpLength);
                    continue;
                }
                int offset = block.offset();
                assert offset <= window : "a match reaches past the window";
                // Split evenly rather than greedily: every piece after the first
                // has to be a new-offset match, and those cannot be shorter than
                // two units, so a greedy remainder of one would be unwritable.
                int pieces = maxOpLength < 3 ? 1 : (length - 1) / maxOpLength + 1;
                int base = length / pieces;
                int wider = length % pieces;
                for (int piece = 0; piece < pieces; piece++) {
                    int size = base + (piece < wider ? 1 : 0);
                    boolean rep = pendingLiterals > 0 && offset == lastOffset;
                    if (size == 1 && !rep) {
                        pendingLiterals++;          // the seam's one-unit rep
                        continue;
                    }
                    flushLiterals();
                    emitMatch(offset, size, rep);
                }
            }
        }
        flushLiterals();
        assert readIndex == units.length : "the parses did not cover the input";

        // The end marker, then the repeat bit: end, or one last word offset
        // in stream D, the distance back to the loop point, matched forever.
        writeBit(true);
        writeBit(false);
        writeBit(true);
        writeBit(repeatIndex >= 0);
        if (repeatIndex >= 0) {
            int scaled = (units.length - repeatIndex) * unit;
            assert scaled <= 32768 : "the loop distance must fit -(O-R)*k in a signed word";
            assert units.length - repeatIndex <= window : "the loop must fit the window";
            if (wordOffsetIndex + 2 > wordOffsets.length) {
                wordOffsets = Arrays.copyOf(wordOffsets, wordOffsets.length * 2);
            }
            wordOffsets[wordOffsetIndex++] = (byte) (-scaled >> 8);
            wordOffsets[wordOffsetIndex++] = (byte) -scaled;
        }

        return new Result(Arrays.copyOf(control, controlIndex + (controlIndex & 1)),
                Arrays.copyOf(literal, literalIndex),
                Arrays.copyOf(byteOffsets, byteOffsetIndex),
                Arrays.copyOf(wordOffsets, wordOffsetIndex), unit,
                units.length * unit, longestOp, operations, rewindIndex, window, copies,
                bitsWritten, repeatIndex >= 0);
    }

    /**
     * A copy from the literal stream, {@code distance} units back in the
     * output for {@code length} units, in pieces the counters hold. A piece
     * is written as a match at the window plus the literals between its
     * source and itself; a piece as long as that count gives its last unit
     * to a literal, so the decoder's offset, advanced by what it copies,
     * never reaches zero.
     */
    private void copy(int distance, int length, int maxOpLength) {
        int pieces = maxOpLength < 3 ? 1 : (length - 1) / maxOpLength + 1;
        int base = length / pieces;
        int wider = length % pieces;
        for (int piece = 0; piece < pieces; piece++) {
            int size = base + (piece < wider ? 1 : 0);
            int start = readIndex + pendingLiterals;
            int source = start - distance;
            assert literalsAt(source + size) - literalsAt(source) == size
                    : "a copy's source must be literal";
            int back = literalsAt(start) - literalsAt(source);
            assert back >= size : "a copy's source lies behind its own literals";
            int given = 0;
            if (back == size) {
                if (size - 1 < 2) {
                    pendingLiterals += size;        // too short to write at all
                    continue;
                }
                given = 1;
                size--;
            }
            int wire = window + back;
            assert wire <= St4Format.maxOffsetUnits(unit) : "a copy reaches past the offsets";
            boolean rep = pendingLiterals > 0 && wire == lastOffset;
            flushLiterals();
            emitMatch(wire, size, rep);
            lastOffset = wire - size;               // where the decoder leaves it
            copies++;
            pendingLiterals += given;
        }
    }

    /**
     * Literal units before {@code position}: recorded for what is written,
     * counted for the run still pending.
     */
    private int literalsAt(int position) {
        return position <= readIndex ? literalsBefore[position]
                : literalsBefore[readIndex] + (position - readIndex);
    }

    private void emitMatch(int offset, int size, boolean rep) {
        if (rep) {
            writeBit(false);
            writeInterlacedEliasGamma(size);
        } else {
            writeBit(true);
            writeOffsetOf(offset);
            writeInterlacedEliasGamma(size - 1);
            lastOffset = offset;
        }
        for (int i = 0; i < size; i++) {
            literalsBefore[readIndex + i + 1] = literalsBefore[readIndex];
        }
        operations++;
        readIndex += size;
        longestOp = Math.max(longestOp, size);
    }

    /**
     * Writes the literal run gathered so far, if any: its flag, unless it
     * opens the stream, its length, and its units into stream B.
     */
    private void flushLiterals() {
        if (pendingLiterals == 0) {
            return;
        }
        if (first) {
            first = false;                          // a stream opens with literals
        } else {
            writeBit(false);
        }
        writeInterlacedEliasGamma(pendingLiterals);
        for (int i = 0; i < pendingLiterals; i++) {
            Units.write(literal, literalIndex, units[readIndex], unit);
            literalIndex += unit;
            literalsBefore[readIndex + 1] = literalsBefore[readIndex] + 1;
            readIndex++;
        }
        operations++;
        longestOp = Math.max(longestOp, pendingLiterals);
        pendingLiterals = 0;
    }

    /**
     * The two class bits, then the offset into its stream. Two class bits
     * keep every operation an even number of bits, so a decoder checks its
     * refill on gamma continuation bits alone.
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
     * A bit goes into the byte reserved when the last one filled; a set bit
     * patches it in place.
     */
    private void writeBit(boolean value) {
        bitsWritten++;
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
