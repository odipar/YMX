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
 *   4   2  format version (1)
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
 *  30   4*S  byte offset of each stream's section, covering frames [0, O)
 * 130   ...  the body: the packed sections, then the sample table
 * </pre>
 *
 * <p>Streams 14-24 carry the compiled effect script, one byte per frame
 * like the registers, but they are script data rather than frame streams:
 * their bytes never reach a register. The packer replays the reference
 * player's decisions over the whole timeline and emits prepared actions -
 * M says what acts this frame (zero on the vast majority), X is the operand
 * a verb reads when its action byte has no room for one - today, which
 * timer channels a preempting sample stops - and each channel's A and P
 * name its action and its timer count. The channels come last so that a
 * tune using two of them leaves the others' pairs at the end of the file,
 * where the player can stop decoding. {@link EffectScript} owns the byte
 * semantics; see doc/SPEC.md for the design.
 *
 * <p>The sample table is {@code count} entries of {byte offset (long),
 * sample length (word), loop point (word)}, each offset pointing at
 * PSG-ready volume bytes 0..15 followed by one end marker with bit 7 set.
 * A PCM stream plays one of these out, and its tick handler stops on the
 * marker rather than counting - or, where the loop point is not
 * {@link #SAMPLE_ONE_SHOT}, goes back to it and plays on. YM calls them
 * digidrums, and their numbering is the YM file's.
 *
 * <p>Each section is a complete, standard ST4 container - twenty-byte header,
 * then its four streams - packed at unit size 1, placed on a long boundary so
 * the container's own alignment guarantees hold. The player opens each with the
 * eight-instruction sequence ST4.S documents, and {@code dst4} can unpack any
 * section straight out of the file for debugging.
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

    /** The only version this release writes or reads. YMX starts at 1: the
     * layout it describes is the one the .yx6 container reached over ten
     * revisions inside the ST4 repository, adopted whole and renumbered,
     * so there is no version history here to be compatible with. */
    public static final int VERSION = 1;

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
     * the verb vocabulary, the master bits, the skip bits - are
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
    public static final int OFFSET_SECTION_TABLE = 30;

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
        if (ringSize > 2520) {
            return "ring " + ringSize + " exceeds 2520: the player reads register"
                    + " k's ring through an assembled-in displacement of k*N,"
                    + " and 13*N must fit a signed word";
        }
        return "";
    }
}
