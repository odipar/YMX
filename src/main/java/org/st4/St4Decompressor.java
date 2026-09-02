package org.st4;

/**
 * The reference decoder, which the 68000 decoders have to agree with. ZX1's
 * state machine with four changes: literals come from stream B and offsets
 * from stream C or D by width; lengths and offsets count units; the end
 * marker's extra bit turns the end into an endless match, the repeat; and an
 * offset beyond the window copies {@code offset - window} units from behind
 * the literal read pointer, which stays where it is, and advances the offset
 * by what was copied. A copy that would not stay behind the pointer is
 * rejected.
 */
public final class St4Decompressor {

    private enum State { START, LITERALS, MATCH, DONE }

    private final int window;
    private final int rewindAt;
    private final byte[] control;
    private final byte[] literal;
    private final byte[] byteOffsets;
    private final byte[] wordOffsets;
    private final byte[] output;
    private final int unit;
    private int controlIndex;
    private int literalIndex;
    private int byteOffsetIndex;
    private int wordOffsetIndex;
    private int outputIndex;
    private int bitMask;
    private int bitValue;
    private int lastOffset = St4Optimizer.INITIAL_OFFSET;
    private int repeatIndex = -1;
    private State state = State.START;

    private St4Decompressor(byte[] control, byte[] literal, byte[] byteOffsets,
                            byte[] wordOffsets, byte[] output, int unit,
                            int window, int rewindAt) {
        this.window = window;
        this.rewindAt = rewindAt;
        this.control = control;
        this.literal = literal;
        this.byteOffsets = byteOffsets;
        this.wordOffsets = wordOffsets;
        this.output = output;
        this.unit = unit;
    }

    /** Decodes the streams into {@code size} bytes, which must be a multiple of k. */
    public static byte[] decompress(byte[] control, byte[] literal, byte[] byteOffsets,
                                    byte[] wordOffsets, int unit, int size) {
        return decompress(control, literal, byteOffsets, wordOffsets, unit, size,
                St4Format.maxOffsetUnits(unit));
    }

    /**
     * The output and how the stream ended: the loop point R of a repeating
     * stream, which decodes as {@code units[0..R) units[R..O)} forever, or
     * -1. A repeating stream decodes to any {@code size} from one pass up.
     */
    public record Decoded(byte[] output, int repeatIndex) {}

    /**
     * As above, at the window the stream was packed for: a match reaches at
     * most {@code window} units back, so a stream that decodes is safe for a
     * ring of that many units, and an offset beyond it copies from the
     * literal stream. Tests hold a {@code -mN} stream to its ring this way.
     *
     * @throws IllegalStateException when a copy does not stay behind the
     *     literal read pointer, or a loop reaches past the window
     */
    public static byte[] decompress(byte[] control, byte[] literal, byte[] byteOffsets,
                                    byte[] wordOffsets, int unit, int size,
                                    int window) {
        return decode(control, literal, byteOffsets, wordOffsets, unit, size,
                window).output();
    }

    /** As {@link #decompress}, also reporting whether the stream repeats. */
    public static Decoded decode(byte[] control, byte[] literal, byte[] byteOffsets,
                                 byte[] wordOffsets, int unit, int size,
                                 int window) {
        return decode(control, literal, byteOffsets, wordOffsets, unit, size, window,
                St4Format.NO_REWIND);
    }

    /**
     * As above, holding a stream to its rewind point: from {@code rewindAt}
     * bytes on, no match reaches before it, so the loop replays from the
     * state saved there and every pass sees the same history. A stream that
     * reaches before it would loop wrongly on the 68000, and is rejected
     * here.
     *
     * @throws IllegalStateException when the loop reaches before its rewind
     *     point, a copy does not stay behind the literal read pointer, or a
     *     loop reaches past the window
     */
    public static Decoded decode(byte[] control, byte[] literal, byte[] byteOffsets,
                                 byte[] wordOffsets, int unit, int size,
                                 int window, int rewindAt) {
        assert St4Format.isUnitSize(unit) : "unit size must be 1, 2 or 4";
        assert size % unit == 0 : "output size must be a whole number of units";
        var decoder = new St4Decompressor(control, literal, byteOffsets, wordOffsets,
                new byte[size], unit, window, rewindAt);
        decoder.run();
        return new Decoded(decoder.output, decoder.repeatIndex);
    }

    private void run() {
        while (state != State.DONE) {
            switch (state) {
                case START -> beginLiterals();
                case LITERALS -> {
                    if (readBit()) {
                        beginMatchFromNewOffset();
                    } else {
                        beginMatchFromLastOffset();
                    }
                }
                case MATCH -> {
                    if (readBit()) {
                        beginMatchFromNewOffset();
                    } else {
                        beginLiterals();
                    }
                }
                case DONE -> throw new AssertionError("unreachable");
            }
        }
        assert outputIndex == output.length : "the streams did not fill the output";
    }

    private void beginLiterals() {
        int length = readInterlacedEliasGamma();
        assert length > 0 : "invalid literal length";
        for (int i = 0; i < length * unit; i++) {
            if (literalIndex >= literal.length) {
                throw new IllegalStateException("truncated literal stream");
            }
            if (outputIndex >= output.length) {
                throw new IllegalStateException("the streams overran the output");
            }
            output[outputIndex++] = literal[literalIndex++];
        }
        state = State.LITERALS;
    }

    private void beginMatchFromLastOffset() {
        int length = readInterlacedEliasGamma();
        if (lastOffset > window) {
            copyFromLiterals(length);
        } else {
            copy(length);
        }
        state = State.MATCH;
    }

    private void beginMatchFromNewOffset() {
        // Two class bits: byte or word, then the bank, or for a word the end
        // of the stream.
        if (readBit()) {
            int bank = readBit() ? 1 : 0;
            assert byteOffsetIndex < byteOffsets.length : "truncated byte offsets";
            lastOffset = bank * 256 + 256 - (byteOffsets[byteOffsetIndex++] & 0xFF);
        } else {
            if (readBit()) {
                endOrRepeat();
                return;
            }
            lastOffset = readWordOffset();
        }
        assert lastOffset > 0 : "an offset must reach back at least one unit";
        int length = readInterlacedEliasGamma() + 1;
        if (lastOffset > window) {
            copyFromLiterals(length);
        } else {
            copy(length);
        }
        state = State.MATCH;
    }

    /**
     * Copies {@code length} units from the literal stream, {@code lastOffset -
     * window} units behind the read pointer, which stays where it is, and
     * advances the offset by what it copied.
     */
    private void copyFromLiterals(int length) {
        int back = lastOffset - window;
        if (back <= length) {
            throw new IllegalStateException("a copy of " + length + " units from " + back
                    + " units back does not stay behind the literal read pointer");
        }
        int source = literalIndex - back * unit;
        if (source < 0) {
            throw new IllegalStateException("a copy reaches before the literal stream");
        }
        for (int i = 0; i < length * unit; i++) {
            output[outputIndex++] = literal[source + i];
        }
        lastOffset -= length;
    }

    /**
     * The end code's extra bit: a plain end, or the repeat, one last word
     * offset from stream D matched until the output is full. The 68000
     * decoders run the same match 65535 units at a time, re-armed forever.
     */
    private void endOrRepeat() {
        if (readBit()) {
            // Stream D holds the distance back to the loop point.
            int distance = readWordOffset();
            assert distance > 0 : "a repeat must reach back at least one unit";
            if (distance > window) {
                throw new IllegalStateException("the loop distance " + distance
                        + " units reaches past the " + window + "-unit window");
            }
            repeatIndex = outputIndex / unit - distance;
            assert repeatIndex >= 0 : "the loop point must be a unit of the stream";
            lastOffset = distance;
            int remaining = (output.length - outputIndex) / unit;
            if (remaining > 0) {
                copy(remaining);
            }
        }
        state = State.DONE;
    }

    private int readWordOffset() {
        assert wordOffsetIndex + 2 <= wordOffsets.length : "truncated word offsets";
        int scaled = (wordOffsets[wordOffsetIndex] & 0xFF) << 8
                | (wordOffsets[wordOffsetIndex + 1] & 0xFF);
        wordOffsetIndex += 2;
        return ((1 << 16) - scaled) / unit;   // stored as -offset * unit
    }

    /** Copies {@code length} units from {@code lastOffset} units back. */
    private void copy(int length) {
        assert length > 0 : "invalid match length";
        int distance = lastOffset * unit;
        assert distance <= outputIndex : "match reaches before the output";
        for (int i = 0; i < length * unit; i++) {
            // With no rewind point this never fires: -1 is below every source.
            if (outputIndex >= rewindAt && outputIndex - distance < rewindAt) {
                throw new IllegalStateException("the loop reaches before the rewind point "
                        + rewindAt + " at byte " + outputIndex);
            }
            if (outputIndex >= output.length) {
                throw new IllegalStateException("the streams overran the output");
            }
            output[outputIndex] = output[outputIndex - distance];
            outputIndex++;
        }
    }

    private int readControl() {
        assert controlIndex < control.length : "truncated control stream";
        return control[controlIndex++] & 0xFF;
    }

    private boolean readBit() {
        bitMask >>= 1;
        if (bitMask == 0) {
            bitMask = 128;
            bitValue = readControl();
        }
        return (bitValue & bitMask) != 0;
    }

    private int readInterlacedEliasGamma() {
        int value = 1;
        while (readBit()) {
            value = value << 1 | (readBit() ? 1 : 0);
        }
        return value;
    }
}
