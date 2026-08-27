package org.ymx.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ymx.YmxFormat;

/**
 * {@link Check} against the ten conformance tunes and against a file built
 * to break one rule.
 *
 * <p>The kit is what the packer writes, so a report on it holds the packer
 * to SPEC.md §9.3 as well as the reader to the kit. A checker that finds
 * nothing anywhere would pass that on its own, which is what the built file
 * is for: eight frames, a toggle stream started and released, and the
 * voice's skip left standing over the release.
 */
final class CheckTest {

    private static final Path KIT = Path.of("doc", "conformance", "tunes");

    private static final int FRAMES = 8;
    private static final int RELEASED = 4;

    @Test
    void everyConformanceTuneIsWithinTheRules() throws IOException {
        try (var tunes = Files.list(KIT)) {
            for (Path tune : tunes.filter(p -> p.toString().endsWith(".ymx")).toList()) {
                List<Check.Fault> faults = Check.check(Files.readAllBytes(tune));
                assertTrue(faults.isEmpty(), () -> tune + " reports:\n  "
                        + String.join("\n  ", faults.stream().map(Object::toString).toList()));
            }
        }
    }

    @Test
    void aSkipLeftStandingOverAReleaseIsReported() {
        List<Check.Fault> faults = Check.check(tune(false));
        assertEquals(1, faults.size(), faults::toString);
        assertEquals(RELEASED, faults.get(0).frame());
        assertTrue(faults.get(0).detail().contains("no timer stream owns"),
                faults.get(0)::toString);
    }

    @Test
    void theSameTuneWithTheSkipLiftedIsWithinTheRules() {
        assertEquals(List.of(), Check.check(tune(true)));
    }

    /**
     * A voice a sample owns, unskipped before that sample could have ended,
     * is reported. §6 bounds the earliest frame a one-shot finishes on, and
     * before it the file is claiming an end that cannot have happened.
     *
     * <p>Without the bound an unskipped PCM voice read as a sample that had
     * finished, so the one rule §10.1 states for a sample went unchecked -
     * the same shape as the toggle case above, for the stream kind that ends
     * itself.</p>
     */
    @Test
    void aSampleVoiceUnskippedTooEarlyIsReported() {
        List<Check.Fault> faults = Check.check(sampled(false));
        assertEquals(1, faults.size(), faults::toString);
        assertEquals(RELEASED, faults.get(0).frame());
        assertTrue(faults.get(0).detail().contains("cannot have finished"),
                faults.get(0)::toString);
    }

    /** The same tune with the skip standing is within the rules. */
    @Test
    void theSameTuneWithTheSkipStandingIsWithinTheRules() {
        assertEquals(List.of(), Check.check(sampled(true)));
    }

    /**
     * Eight frames: a one-shot sample of 4,000 bytes triggered on voice A at
     * frame 0, at prescaler 7 and count 200, so §6 puts its rejoin far past
     * frame {@value #RELEASED}. With {@code held} the skip stands; without
     * it frame {@value #RELEASED} clears it.
     */
    private static byte[] sampled(boolean held) {
        byte[][] streams = new byte[YmxFormat.STREAMS][FRAMES];
        byte[] master = streams[YmxFormat.STREAM_M];
        byte[] action = streams[YmxFormat.streamAction(0)];
        byte[] count = streams[YmxFormat.streamAction(0) + 1];

        master[0] = (byte) (1 | 0x10 | (1 << 5));   // channel 0 acts, voice A skipped
        action[0] = (byte) ((6 << 5) | 7);          // START_PCM, voice A, prescaler 7
        count[0] = (byte) 200;
        streams[8][0] = 0;                          // R8: the sample number
        if (!held) {
            master[RELEASED] = (byte) (1 | 0x10);   // the skip cleared, and nothing
            action[RELEASED] = (byte) (1 << 5);     // a HOLD, so the channel acts
        }
        return file(streams, 4000, YmxFormat.SAMPLE_ONE_SHOT);
    }

    /**
     * A trigger repeated on one channel reports nothing. §9.3 asks what a
     * trigger silences, which is the channels holding a toggle stream on the
     * voice it takes; a channel meeting its own running timer is
     * reprogrammed, not stopped, and START_PCM stays the encoding.
     *
     * <p>Reading the rule as any running timer reported this on 36 of the
     * 543 tunes in the collection, 4,888 times, and the conformance kit
     * carries no repeated trigger to catch it.</p>
     */
    @Test
    void aTriggerRepeatedOnItsOwnChannelIsWithinTheRules() {
        assertEquals(List.of(), Check.check(retriggered()));
    }

    /**
     * The same eight frames, with a PCM stream triggered on voice A at frame
     * 0 and again at frame {@value #RELEASED} while its own timer runs.
     */
    private static byte[] retriggered() {
        byte[][] streams = new byte[YmxFormat.STREAMS][FRAMES];
        byte[] master = streams[YmxFormat.STREAM_M];
        byte[] action = streams[YmxFormat.streamAction(0)];
        byte[] count = streams[YmxFormat.streamAction(0) + 1];

        master[0] = (byte) (1 | 0x10 | (1 << 5));   // channel 0 acts, voice A skipped
        action[0] = (byte) ((6 << 5) | 1);          // START_PCM, voice A, prescaler 1
        count[0] = 100;
        master[RELEASED] = 1;                       // acts, and the skip stands
        action[RELEASED] = (byte) ((6 << 5) | 1);   // the same trigger again
        count[RELEASED] = 100;
        return file(streams);
    }

    /**
     * Eight frames on timer channel 0: a toggle stream takes voice A at
     * frame 0 and is released at frame {@value #RELEASED}. With
     * {@code lifted} the release frame's M clears voice A's skip, which is
     * §9.3's rule; without it the skip stands and the frame write omits R8
     * for the rest of the tune.
     *
     * <p>Every section is stored (§1.4), so the values are the file's own
     * bytes and no packing stands between this and what {@link Check}
     * reads.
     */
    private static byte[] tune(boolean lifted) {
        byte[][] streams = new byte[YmxFormat.STREAMS][FRAMES];
        byte[] master = streams[YmxFormat.STREAM_M];
        byte[] action = streams[YmxFormat.streamAction(0)];
        byte[] count = streams[YmxFormat.streamAction(0) + 1];

        master[0] = (byte) (1 | 0x10 | (1 << 5));   // channel 0 acts, voice A skipped
        action[0] = (byte) ((3 << 5) | 1);          // START_TOGGLE, voice A, prescaler 1
        count[0] = 100;
        master[RELEASED] = (byte) (lifted ? 1 | 0x10 : 1);
        action[RELEASED] = (byte) (2 << 5);         // RELEASE, stopping

        return file(streams);
    }

    /** The streams written into a file with every section stored. */
    private static byte[] file(byte[][] streams) {
        return file(streams, -1, 0);
    }

    /** The streams written into a file, with a one-entry sample table where
     * {@code length} is not negative. */
    private static byte[] file(byte[][] streams, int length, int loop) {
        int table = length < 0 ? 0 : YmxFormat.HEADER_SIZE + 2;
        int body = (length < 0 ? YmxFormat.HEADER_SIZE + 2 : table + 8);
        byte[] file = new byte[body + YmxFormat.STREAMS * FRAMES];
        if (table != 0) {
            putLong(file, YmxFormat.OFFSET_SAMPLE_TABLE, table);
            putWord(file, YmxFormat.OFFSET_SAMPLE_COUNT, 1);
            putLong(file, table, body);             // the sample's own bytes
            putWord(file, table + 4, length);
            putWord(file, table + 6, loop);
        }
        putLong(file, YmxFormat.OFFSET_MAGIC, YmxFormat.MAGIC);
        putWord(file, YmxFormat.OFFSET_VERSION, YmxFormat.VERSION);
        putWord(file, YmxFormat.OFFSET_FLAGS, YmxFormat.flagChannel(0));
        putLong(file, YmxFormat.OFFSET_FRAMES, FRAMES);
        putWord(file, YmxFormat.OFFSET_PLAYER_HZ, 50);
        putWord(file, YmxFormat.OFFSET_STREAM_COUNT, YmxFormat.STREAMS);
        putWord(file, YmxFormat.OFFSET_RING_SIZE, YmxFormat.DEFAULT_RING_SIZE);
        putWord(file, YmxFormat.OFFSET_CHUNK, YmxFormat.DEFAULT_CHUNK);
        putLong(file, YmxFormat.OFFSET_MASTER_CLOCK, 2000000);
        putLong(file, YmxFormat.OFFSET_REQUIRED, YmxFormat.REQUIRED_BASE);
        for (int stream = 0; stream < YmxFormat.STREAMS; stream++) {
            int at = body + stream * FRAMES;
            putLong(file, YmxFormat.OFFSET_SECTION_TABLE + 4 * stream,
                    (int) (YmxFormat.SECTION_STORED | at));
            System.arraycopy(streams[stream], 0, file, at, FRAMES);
        }
        return file;
    }

    private static void putLong(byte[] file, int at, int value) {
        file[at] = (byte) (value >> 24);
        file[at + 1] = (byte) (value >> 16);
        file[at + 2] = (byte) (value >> 8);
        file[at + 3] = (byte) value;
    }

    private static void putWord(byte[] file, int at, int value) {
        file[at] = (byte) (value >> 8);
        file[at + 1] = (byte) value;
    }
}
