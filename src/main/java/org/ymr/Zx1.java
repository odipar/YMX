package org.ymr;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.jx1.Decompressor;

/**
 * Decodes one ZX1 stream out of a .YMR image.
 *
 * <p>ZX1 is Einar Saukas's LZ format, chosen for .YMR because its decoder fits
 * in a handful of 68000 instructions and keeps its whole state in registers, so
 * a second concurrent stream costs a second set of registers and nothing else.
 * A .YMR needs that: every stream in the file is compressed on its own, and the
 * Atari player has seventeen or more of them running at once, each decoded a
 * byte or two per frame.
 *
 * <p>The bitstream work is not done here. {@link Decompressor} is the ZX1
 * implementation this family already has, vendored from jx1 - see
 * {@code org/jx1/README.md} - and reading .YMR with a second port written to
 * match it would only give the two something to disagree about. What is here is
 * the part that is .YMR's rather than ZX1's: where a stream sits in the image,
 * how far it is allowed to reach back, and what to say when it will not decode.
 *
 * <h2>The ring is the point</h2>
 *
 * <p>The player decodes a stream through a ring of {@code ring_size} bytes that
 * is at once the decoder's sliding window and its output queue: it runs a
 * little ahead of where the frame tick reads, and the tick reads the bytes out
 * before the write pointer laps them. That ring is all the memory the stream
 * ever gets on a half-megabyte machine, and it is the same number the packer
 * was handed as its offset limit - the whole of the format's memory
 * claim. Ring size and compression ratio are one decision, and the exporter
 * shares a fixed budget out between the streams to pay for it.
 *
 * <p>{@link Decompressor} takes that ring as an argument, so a decode through
 * it is the decode the Atari does, byte for byte. A stream whose packer
 * overreached its ring is the one case where that matters: the ring has already
 * overwritten what the match points at, so the Atari copies out whatever the
 * ring holds now. Rather than pass that on, a stream long enough to lap its
 * ring is decoded twice - once through a window nothing can outrun,
 * the decode the packer meant, and once through the ring the map declares, which
 * is the decode the Atari performs - and the stream is refused unless the two
 * agree. They agree for every well-formed file; when they do not, the file is
 * broken and says so here rather than three layers down.
 *
 * <p>The array handed back is the one from the window nothing can outrun. Which
 * of the two is returned does not matter, because the only streams that get as
 * far as being returned are the ones whose two decodes were compared and found
 * equal, or the ones too short to lap their ring, where the ring never wraps
 * and the two decodes are the same bytes by construction. It is returned
 * because it is the one that does not depend on how far a match reached.
 *
 * <h2>Assertions are a build flag, not a diagnosis</h2>
 *
 * <p>Upstream reports malformed data by tripping a Java assertion, the
 * right stance for a decoder whose caller packed the data it is handing over.
 * Here the data is a file somebody else wrote, and two things follow from that.
 * The rejection cannot be worded by the assertion, which fires inside a decoder
 * that has never heard of .YMR and cannot name which of the twenty streams went
 * wrong. And, far worse, an assertion is a flag on the command line: the same
 * stream that stops a {@code java -ea} run reads on quietly under a plain
 * {@code java}, where a back-reference past the ring returns whatever the ring
 * holds and the song comes out wrong. A converter that turns a broken file into
 * a wrong song because somebody forgot a JVM flag is worse than one that is
 * merely terse.
 *
 * <p>So nothing here is diagnosed by catching an assertion. Every condition
 * this class rejects, it establishes for itself from outside the decoder, out
 * of what the decoder does rather than what it says: the stream is decoded
 * several times over, with the window, the ring fill and the stored length
 * varied one at a time, and the results are compared. A throw from the decoder
 * is read as no more than "that did not run to the end", which is true whether
 * it arrived as an assertion or as the array index the assertion was guarding,
 * and its message is dropped. Each of the questions below costs one more decode
 * of a stream that is a few hundred bytes long.
 *
 * <p><b>Does the stream end where the map says it ends?</b> ZX1's end marker is
 * always two bytes - the one-byte offset form cannot encode a distance small
 * enough to mean "end" - so a stream whose marker lands on its last stored byte
 * cannot survive losing that byte. Decoding the stream without its last byte
 * therefore has to fail, and if it succeeds instead, the marker came earlier
 * and the map's length and the stream disagree. That probe is the only way to
 * see the leftover bytes at all without {@code -ea}, where upstream's own check
 * for them is compiled out.
 *
 * <p><b>Does it ever reach back past its own first byte?</b> A window nothing
 * can outrun is still full of bytes the stream did not put there, and a match
 * that reaches into them is invisible from outside - the decoder copies
 * them to the output. Filling the window twice with different bytes makes it
 * visible: a stream that reaches back too far decodes to two different things,
 * and a well-formed one cannot tell the two fills apart.
 *
 * <p><b>Does it stay inside its ring?</b> The comparison described above.
 *
 * <p>The first two questions share one rejection rather than getting one each,
 * and so does the stream that stops mid-operation. That is a limit, not
 * an economy. Under {@code -ea} the decoder stops at the first thing it does
 * not like and nothing after that point can be observed, so all three faults
 * look identical from out here - a run that did not finish - and telling them
 * apart would mean reading the assertion's text, the very coupling this
 * arrangement exists to remove. Without {@code -ea} the decoder runs on and the
 * probes could separate them, but a message that is sharper on one build than
 * on the other is the bug this class was fixed for, not a feature. So one
 * sentence names all three, and both builds print it for all three.
 *
 * <p>The ring comparison is the one distinction that survives that argument,
 * because it is not a fault the decoder is ever in a position to judge:
 * the reference decode has already run to the end before the ring decode is
 * started, so whichever way the ring decode ends - a different string of bytes
 * without {@code -ea}, a stop with it - the answer is the same one and the
 * message is its own.
 *
 * <p>A {@code ring_size} of 0 is not a small ring but a different thing
 * entirely: the stream is stored uncompressed and its bytes are the data. The
 * exporter stores a stream that way whenever packing it would not make it
 * smaller, routine for the short ones - five bytes pack to six.
 *
 * <p>Everything this class rejects is a {@link YmrReader.FormatException},
 * because a stream that will not decode is a malformed .YMR image like any
 * other and a caller has one thing to catch.
 */
public final class Zx1 {

    /**
     * The largest distance ZX1's two-byte offset form can name. A ring at least
     * this big can never be lapped by a back-reference, which makes the
     * ring comparison below skippable rather than merely usually equal.
     */
    private static final int MAX_OFFSET = 32512;

    /**
     * The window the reference decode runs through: the one the C original
     * uses, which is past {@link #MAX_OFFSET} and so cannot be outrun. That
     * makes a decode through it worth calling the decode the packer meant,
     * and it also leaves the input the only array a malformed stream can
     * still index out of range - so a throw from that decode means the stream
     * ran out, no matter which build is running.
     */
    private static final int REFERENCE_WINDOW = Decompressor.DEFAULT_BUFFER_SIZE;

    private Zx1() {}

    /** Decodes a whole stream held on its own, for callers that have nothing else. */
    public static byte[] decode(byte[] stream, int ringSize) {
        return decode(stream, 0, stream.length, ringSize, "a ZX1 stream");
    }

    /**
     * Decodes the stream stored at {@code from} for {@code length} bytes.
     *
     * @param ringSize the ring the map gives this stream, and therefore the
     *                 furthest back any of its matches may reach; 0 means the
     *                 stream is stored uncompressed and is returned as it lies
     * @param what     how to name this stream in a rejection message
     */
    public static byte[] decode(byte[] image, int from, int length, int ringSize, String what) {
        if (from < 0 || length < 0 || from > image.length || length > image.length - from) {
            throw new YmrReader.FormatException("truncated file: " + what + " claims " + length
                    + " bytes at offset " + from + ", past the " + image.length
                    + " bytes in the file");
        }
        if (ringSize == 0) {
            return Arrays.copyOfRange(image, from, from + length);
        }
        if (ringSize < 0) {
            throw new YmrReader.FormatException(what + ": a ring of " + ringSize + " bytes");
        }
        // The exact slice: a stream's stored length is the distance to the next
        // one in the map, and the end marker has to land on the last byte of it.
        byte[] stream = Arrays.copyOfRange(image, from, from + length);

        // The questions the class javadoc lists, one decode each, in an order
        // that lets each rest on the one before it: nothing is shortened until
        // the stream has decoded whole, so the shortening is never asked of a
        // stream of no bytes, and no fill is compared against a decode that
        // never happened.
        byte[] decoded = attempt(stream, REFERENCE_WINDOW, 0x00);
        if (decoded == null
                || attempt(Arrays.copyOf(stream, length - 1), REFERENCE_WINDOW, 0x00) != null
                || !Arrays.equals(decoded, attempt(stream, REFERENCE_WINDOW, 0xFF))) {
            throw new YmrReader.FormatException(what + ": the " + length + " bytes the map gives"
                    + " this stream are not one whole ZX1 stream - it ends mid-operation, reaches"
                    + " its end marker before its last byte, or reaches back for bytes it never"
                    + " wrote");
        }
        // A match can reach no further back than the stream has already
        // written, which the fill above has just established, so a stream that
        // never grows past its ring cannot overreach it - and nothing
        // can overreach MAX_OFFSET. Most streams skip the ring decode on one of
        // those two counts.
        if (decoded.length > ringSize && ringSize < MAX_OFFSET
                && !Arrays.equals(decoded, attempt(stream, ringSize, 0x00))) {
            throw new YmrReader.FormatException(what + ": a match reaches back further than the "
                    + ringSize + "-byte ring the map gives this stream, so the Atari would decode"
                    + " this stream to something else again");
        }
        return decoded;
    }

    /**
     * One pass of the vendored decoder: what it wrote, or {@code null} if it did
     * not run to the end.
     *
     * <p>A failure comes back as nothing but its absence because that is all it
     * is worth. With {@code -ea} it arrives as an {@link AssertionError} naming
     * a condition in a decoder that cannot name the stream it was given, and
     * without {@code -ea} the same stream arrives as the array index that
     * assertion was guarding, or does not arrive; the caller above has
     * the context, and calls in a way that does not need the difference.
     *
     * @param window how many bytes of ring to decode through
     * @param fill   what the ring holds before the decode starts, so that a
     *               match reaching into bytes the stream never wrote can be
     *               caught by running the same decode twice
     */
    private static byte @Nullable [] attempt(byte[] stream, int window, int fill) {
        var output = new ByteArrayOutputStream(Math.max(64, stream.length * 2));
        byte[] ring = new byte[window];
        Arrays.fill(ring, (byte) fill);
        try {
            new Decompressor(stream, ring) {
                @Override
                protected void flip(byte[] flushed, int flushedLength) {
                    output.write(flushed, 0, flushedLength);
                }
            }.decompress();
        } catch (AssertionError | ArrayIndexOutOfBoundsException e) {
            return null;
        }
        return output.toByteArray();
    }
}
