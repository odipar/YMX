package org.ymx;

/**
 * The {@code .ymx} container: a fixed header followed by one embedded ST4
 * container per stream section - fourteen frame streams carrying the
 * YM2149's sound registers, and eleven carrying the compiled effect script.
 *
 * <p>Every field is big-endian, which is what the 68000 player reads directly
 * out of the loaded file. The header is a fixed size so the player can index
 * the stream table without parsing anything.
 *
 * <pre>
 *   0   4  'YMX!'
 *   4   2  format version, the major byte then the minor (VERSION)
 *   6   2  flags: bit 0 set when the tune starts over at the end, bits 1-4
 *           one per timer channel, set when the tune uses it
 *   8   4  O, the number of frames
 *  12   2  frame rate in Hz: how often the player is called (50 usually)
 *  14   2  S, the stream count (25: R0..R13, then M X T and four A/P pairs)
 *  16   2  N, the ring size in bytes each stream decodes through
 *  18   2  C, the chunk size one ST4_resume call produces
 *  20   4  YM master clock in Hz, informational
 *  24   4  byte offset of the sample table; zero when there are none
 *  28   2  sample count
 *  30   4  L, the frame a tune that starts over goes back to
 *  34   4  byte offset of the loop table; zero when there is none
 *  38   4*S  byte offset of each stream's section, covering frames [0, O)
 * 138   ...  the body: the packed sections, then the sample table
 * </pre>
 *
 * <p>Streams 14-24 carry the compiled effect script, one byte per frame
 * like the registers, but they are script data rather than frame streams:
 * their bytes never reach a register. The packer replays the reference
 * player's decisions over the whole timeline and emits prepared actions -
 * M says what acts this frame (zero on the vast majority), X carries the
 * operands an action byte has no room for - the envelope shape a retrigger
 * stream restarts, and the timer channels a preempting sample stops - and
 * each channel's A and P name its action and its timer count. The channels come last so that a
 * tune using two of them leaves the others' pairs at the end of the file,
 * where the player can stop decoding. {@link EffectScript} owns the byte
 * semantics; see doc/SPEC.md for the design.
 *
 * <p>The sample table is {@code count} entries of {byte offset (long),
 * sample length (word), loop point (word)}, each offset pointing at
 * PSG-ready volume bytes 0..15 followed by the end marker {@code $80}.
 * A PCM stream plays one of these out, and its tick handler stops on the
 * marker rather than counting - or, where the loop point is not
 * {@link #SAMPLE_ONE_SHOT}, goes back to it and plays on. YM calls them
 * digidrums, and their numbering is the YM file's.
 *
 * <p>Each section is a complete, standard ST4 container - twenty-byte header,
 * then its four streams - or, where the values are shorter than a container,
 * stored plain with bit 31 of its offset set ({@link #SECTION_STORED}). Every
 * container in a file is packed at one unit size, 1, 2 or 4 bytes, recorded
 * in its ST4 signature; each section begins on a long boundary. The player
 * opens a container with the eight-instruction sequence ST4.S documents, and
 * {@code dst4} can unpack any container section straight out of the file for
 * debugging.
 *
 * <p>Each stream is one section covering every frame. A tune that starts over
 * reaches the end of that section and the player opens it again from the top,
 * which is the only way to restart a decoder.
 *
 * <p>The player needs {@code O}, {@code N}, {@code C} and the offsets; the
 * packed sizes are implied by the next offset and never needed, because
 * ST4_wrap counts output units rather than input bytes.
 */
public final class YmxFormat {

    /** {@code 'YMX!'}, the first four bytes of every file. */
    public static final int MAGIC = 0x594D5821;

    /** The only version this release writes or reads: the major in the
     * high byte, the minor in the low, so versions order numerically -
     * $0005, version 0.5, sorts before $0100, version 1.0. There is no
     * version history here to be compatible with; doc/SPEC.md defines
     * the layout. */
    public static final int VERSION = 0x0005;

    /** The released binaries' patch number: it moves when the binaries
     * change and the format does not - an optimized player, a fixed
     * stub. The format version above is the compatibility gate; this
     * number never reaches the format word. */
    public static final int PATCH = 0;

    /** The release's version as prose: the format version plus the
     * patch, "0.5.0". */
    public static String releaseName() {
        return versionName() + "." + PATCH;
    }

    /** A version word as prose: {@code versionName(0x0102)} reads "1.2". */
    public static String versionName(int word) {
        return (word >> 8) + "." + (word & 0xFF);
    }

    /** This release's version as prose. */
    public static String versionName() {
        return versionName(VERSION);
    }

    /** Flag bit 0: the tune starts over at frame 0 instead of ending. */
    public static final int FLAG_LOOPS = 1;

    /** Flag bit {@code 1 + channel}: the tune uses that timer channel, so
     * the player claims a timer for it. Every channel says so the same
     * way; a channel left clear costs the host nothing. */
    public static int flagChannel(int channel) {
        return 2 << channel;
    }


    /** R0..R13 plus the script streams M, X, T and four A/P pairs. */
    public static final int STREAMS = 25;

    /** The frame streams: one per YM2149 sound register. */
    public static final int REGISTER_STREAMS = 14;

    /** Stream indices of the script data: the master byte, then
     * each timer channel's action and timer-count bytes. The byte semantics -
     * the opcode vocabulary, the master bits, the skip bits - are
     * {@link EffectScript}'s ABI, which packer, player and rigs all cite. */
    public static final int STREAM_M = 14;
    public static final int STREAM_X = 15;
    public static final int STREAM_T = 16;
    public static final int STREAM_A0 = 17;

    /** Channel {@code c}'s action stream; its count stream is the next one.
     * The channels sit last and two apart, so a tune that uses fewer of
     * them leaves a tail of streams the player never has to decode. */
    public static int streamAction(int channel) {
        return STREAM_A0 + 2 * channel;
    }

    /** The streams a player must keep decoding for a tune with these header
     * flags: everything up to and including the last channel it names. The
     * rest are in the file, hold nothing anyone reads, and cost no time. */
    public static int liveStreams(int flags) {
        int live = STREAM_A0;
        for (int c = 0; c < CHANNELS; c++) {
            if ((flags & flagChannel(c)) != 0) {
                live = streamAction(c) + 2;
            }
        }
        return live;
    }

    /** Timer channels the format allows, numbered 0 to 3. Each is a pair
     * of streams that pack to nothing while the tune leaves the channel
     * idle. Which of the MFP's timers runs each one is the T stream's to
     * say, not the player's. */
    public static final int CHANNELS = 4;

    /** T's two bits per channel: the timer it runs on. */
    public static final int TIMER_A = 0;
    public static final int TIMER_B = 1;
    public static final int TIMER_C = 2;
    public static final int TIMER_D = 3;

    /** The map a YM tune is packed with. Channels 0 and 1 land on Timers A
     * and D, which is where the reference player put its first two, so a YM
     * tune sounds exactly as it did; the rest fill in the unused timers. */
    public static final int DEFAULT_TIMERS =
            TIMER_A | (TIMER_D << 2) | (TIMER_B << 4) | (TIMER_C << 6);

    /** Channel {@code c}'s timer, out of a T byte. */
    public static int timerOf(int assignments, int channel) {
        return (assignments >> (2 * channel)) & 3;
    }

    public static final int OFFSET_MAGIC = 0;
    public static final int OFFSET_VERSION = 4;
    public static final int OFFSET_FLAGS = 6;
    public static final int OFFSET_FRAMES = 8;
    public static final int OFFSET_PLAYER_HZ = 12;
    public static final int OFFSET_STREAM_COUNT = 14;
    public static final int OFFSET_RING_SIZE = 16;
    public static final int OFFSET_CHUNK = 18;
    public static final int OFFSET_MASTER_CLOCK = 20;
    public static final int OFFSET_SAMPLE_TABLE = 24;
    public static final int OFFSET_SAMPLE_COUNT = 28;

    /** {@code L}, the frame a tune that starts over goes back to. It has a
     * meaning only where {@link #FLAG_LOOPS} is set, and a tune that plays
     * once through carries 0. */
    public static final int OFFSET_LOOP_FRAME = 30;

    /** Byte offset of the loop table; zero where the file carries no such
     * table, which is every file this release writes. */
    public static final int OFFSET_LOOP_TABLE = 34;

    /**
     * Bit 31 of a section offset: the bytes at that offset are the section's
     * values, one per frame, and there is no container around them.
     *
     * <p>Twenty of a container's bytes are header, so a section shorter than
     * that costs more packed than plain - a one-frame tune carries one value.
     * The offset's top bit says which a section is, and a file is far too
     * small for the bit to be an offset.
     */
    public static final long SECTION_STORED = 0x8000_0000L;

    /** Where a section's bytes begin, whether it is stored or a container. */
    public static long sectionOffset(long entry) {
        return entry & ~SECTION_STORED;
    }

    /** Whether a section's bytes are its values rather than a container. */
    public static boolean isStored(long entry) {
        return (entry & SECTION_STORED) != 0;
    }

    /** One long offset per stream, in stream order: where its section is. */
    public static final int OFFSET_SECTION_TABLE = 38;

    public static final int HEADER_SIZE = OFFSET_SECTION_TABLE + 4 * STREAMS;

    /** A sample table entry: a long offset and a word length. */
    public static final int SAMPLE_ENTRY_SIZE = 8;

    /**
     * A sample's {@code loopStart} when it does not loop, most of
     * them: a digidrum is a hit and stops.
     *
     * <p>A loop point is an offset into the sample and a sample cannot reach
     * 64 KB, so a word holds any real one and leaves {@code $FFFF} spare to
     * mean none. That keeps the entry eight bytes and long-aligned, which is
     * what the player's table of resolved pairs requires.
     */
    public static final int SAMPLE_ONE_SHOT = 0xFFFF;

    /** The byte after a sample's last value has this bit set; the PCM tick
     * interrupt routine's own move.b reads it as negative and stops. */
    public static final int SAMPLE_END_MARK = 0x80;

    /** The format's ceiling: a sample number is five bits in the YM file. */
    public static final int MAX_SAMPLES = 32;

    /** Default ring size: the size the timings in the README are quoted for. */
    public static final int DEFAULT_RING_SIZE = 960;

    /** The largest ring the format allows: the player reads stream {@code k}'s
     * ring through an assembled-in displacement of {@code k*N}, and 13*N must
     * fit a signed word. */
    public static final int MAX_RING_SIZE = 2520;

    /**
     * Default chunk size, and the group size the round-robin player is built
     * around: one refill per VBL covers the 21 streams a YM tune decodes
     * within a 24-VBL cycle, with three VBLs to spare. A tune that uses all
     * four timer channels decodes {@value #STREAMS} and needs a bigger
     * chunk - and a ring that divides by it, so 960/24 becomes 1000/25.
     */
    public static final int DEFAULT_CHUNK = 24;

    private YmxFormat() {}

    /**
     * Checks a ring/chunk pair against what both the format and the player
     * require, and returns the reason it is unusable, or null when it is fine.
     *
     * <p>{@code N mod C = 0} is ST4_wrap's own rule. {@code C >= S} is the
     * player's: the refill schedule gives each register one VBL of its own
     * inside a group. {@code N >= 2C} keeps the group being read and the group
     * being written from sharing ring space.
     */
    public static String checkShape(int ringSize, int chunk) {
        return checkShape(ringSize, chunk, 1);
    }

    /**
     * As above, for sections packed at {@code unit} bytes per ST4 unit. The
     * chunk must be whole units, since one refill call's budget is C/unit.
     */
    public static String checkShape(int ringSize, int chunk, int unit) {
        return checkShape(ringSize, chunk, unit, STREAMS);
    }

    /**
     * As above, for a tune whose live stream count is known. The round-robin
     * has to refill every stream the player decodes once per C frames, and a
     * player decodes only as far as the last channel the tune names - so a
     * tune that leaves channels idle may use a smaller C than the format's
     * full stream count would allow.
     */
    public static String checkShape(int ringSize, int chunk, int unit, int live) {
        if (!org.st4.St4Format.isUnitSize(unit)) {
            return org.st4.St4Format.checkUnit(unit);
        }
        if (chunk % unit != 0) {
            return "chunk " + chunk + " is not a whole number of " + unit + "-byte units";
        }
        if (chunk < live) {
            return "chunk " + chunk + " is below the " + live
                    + " streams this tune decodes, so the round-robin refill"
                    + " cannot fit in one cycle";
        }
        if (ringSize < 2 * chunk) {
            return "ring " + ringSize + " must hold two chunks of " + chunk;
        }
        if (ringSize % chunk != 0) {
            return "ring " + ringSize + " is not a multiple of chunk " + chunk;
        }
        if (ringSize > MAX_RING_SIZE) {
            return "ring " + ringSize + " exceeds " + MAX_RING_SIZE
                    + ": the player reads register"
                    + " k's ring through an assembled-in displacement of k*N,"
                    + " and 13*N must fit a signed word";
        }
        return "";
    }
}
