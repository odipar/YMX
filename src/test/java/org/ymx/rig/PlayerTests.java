package org.ymx.rig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.ymx.MkCores;
import org.ymx.MkSndh;
import org.ymx.Tools;

/**
 * Differential tests for the YMX player: does the ST write the right YM
 * frames? Most checks pack a tune with the real packer - one builds its
 * file by hand - assemble YMX.S with the decoder, run the player under
 * emulation as a plain 68000, and capture every write to the sound chip.
 * The captured pairs must match, frame by frame and in order, what a
 * YM2149 should have received - which {@link GenYm} computes independently
 * of both the packer and the player.
 *
 * <p>{@code ymx/test/rig.sh [--quick]} runs the whole battery; each check
 * returns a problem line, or the empty string when it holds.
 */
final class PlayerTests {

    static final long TACR = 0xFFFFFA19L;
    static final long TADR = 0xFFFFFA1FL;
    static final long TCDCR = 0xFFFFFA1DL;
    static final long TDDR = 0xFFFFFA25L;

    private PlayerTests() {}

    /** Feeds captured writes to a model of the chip; reports R13 writes.
     * The player may skip a register whose value has not changed, so what
     * has to match is the chip's contents. Writing R13 restarts the
     * envelope, so that one write is an event in its own right. */
    static boolean applyWrites(int[] state, List<Player.Pair> writes) {
        boolean envelopeWritten = false;
        for (Player.Pair write : writes) {
            if (write.register() >= GenYm.PLAY_REGISTERS) {
                throw new IllegalStateException("wrote R" + write.register()
                        + ", which is an I/O port");
            }
            if (write.register() == 13) {
                envelopeWritten = true;
            }
            state[write.register()] = write.value();
        }
        return envelopeWritten;
    }

    /** Plays a whole tune (and {@code passes} passes more) and checks it. */
    static String runShape(int frames, int ring, int chunk, String label,
            boolean loops, int passes, int unit) {
        return runShape(frames, ring, chunk, label, loops, passes, unit, 0);
    }

    /**
     * The same, for a tune whose header sends its own player back to
     * {@code loopFrame}. A pass after the first is the frames from there on,
     * so the played length counts the whole tune once and a body each time
     * after that.
     *
     * <p>The ring the workspace is sized for is the packed file's own: a body
     * that does not fit the ring asked for is packed with a bigger one, and
     * the player reads N out of the header either way.
     */
    static String runShape(int frames, int ring, int chunk, String label,
            boolean loops, int passes, int unit, int loopFrame) {
        byte[][] source = GenYm.registers(frames);
        byte[] packed = Rig.pack(GenYm.ym6File(frames, loopFrame, source), ring,
                chunk, loops, unit);
        int carried = header(packed, YMX_LOOP_FRAME, 4);
        if (carried != (loops ? loopFrame : 0)) {
            return label + ": the file carries L=" + carried + ", not " + loopFrame;
        }
        int played = loops ? frames + passes * (frames - carried) : frames;
        List<GenYm.ChipState> expected = GenYm.chipStates(frames, source, loops,
                carried, played);

        Player player = new Player(packed, unit);
        if (player.init() != 0) {
            return label + ": YMX_init rejected the file";
        }

        int[] state = new int[GenYm.PLAY_REGISTERS];
        int position = 0;                           // where in the tune we are
        for (int index = 0; index < played; index++) {
            Player.Frame frame = player.frame();
            boolean envelope = applyWrites(state, frame.writes());
            GenYm.ChipState wanted = expected.get(index);
            if (!Arrays.equals(state, wanted.registers())) {
                StringBuilder differs = new StringBuilder();
                for (int r = 0; r < GenYm.PLAY_REGISTERS; r++) {
                    if (state[r] != wanted.registers()[r]) {
                        differs.append(differs.isEmpty() ? "" : ", ")
                                .append(String.format("R%d=%#04x want %#04x",
                                        r, state[r], wanted.registers()[r]));
                    }
                }
                return label + ": after frame " + index + " the chip has " + differs;
            }
            if (envelope != wanted.envelopeWritten()) {
                return label + ": frame " + index + " "
                        + (envelope ? "wrote" : "skipped")
                        + " R13, expected the other";
            }
            position++;
            // d0 = 1 means "that frame ended the tune, the next one is frame
            // L again". A tune that plays once never reports it: it reports
            // -1 on the call after its last frame instead.
            boolean wrapped = position >= frames && loops;
            if (wrapped) {
                position = carried;
            }
            if (frame.result() != (wrapped ? 1 : 0)) {
                return label + ": frame " + index + " returned " + frame.result()
                        + ", expected " + (wrapped ? 1 : 0);
            }
        }

        if (!loops) {
            Player.Frame past = player.frame();
            if (past.result() != -1 || !past.writes().isEmpty()) {
                return label + ": past the end it wrote " + past.writes()
                        + " and returned " + past.result();
            }
        }

        // Re-initialising is the whole reset: the second pass must be identical.
        if (player.init() != 0) {
            return label + ": re-init rejected the file";
        }
        state = new int[GenYm.PLAY_REGISTERS];
        for (int index = 0; index < Math.min(played, 3 * chunk); index++) {
            applyWrites(state, player.frame().writes());
            if (!Arrays.equals(state, expected.get(index).registers())) {
                return label + ": frame " + index + " differs after re-init";
            }
        }
        return "";
    }

    /** Header offsets the battery reads back out of a packed file. */
    static final int YMX_LOOP_FRAME = 30;

    /** One big-endian header field of a packed file. */
    static int header(byte[] packed, int at, int size) {
        int value = 0;
        for (int byteAt = 0; byteAt < size; byteAt++) {
            value = (value << 8) | (packed[at + byteAt] & 0xFF);
        }
        return value;
    }

    /** The sample table's offset, straight from the packed file's header. */
    static long drumTable(Player player) {
        return player.uc.value(player.file + 24, 4);
    }

    /** Runs one tick handler to its rte, which this emulator build cannot
     * execute - reaching it is the completed tick. Returns the chip writes. */
    static List<Player.Pair> invokeIsr(Player player, long address) {
        long stack = Rig.STACK_TOP - 512;
        player.writes.clear();
        player.uc.set(Unicorn.SR, 0x2600);
        player.uc.set(Unicorn.A7, stack);
        player.uc.start(address, Rig.MAGIC, 1_000);
        long pc = player.uc.register(Unicorn.PC);
        if (player.uc.value(pc, 2) != 0x4E73) {
            throw new IllegalStateException("the tick handler faulted at "
                    + Long.toHexString(pc));
        }
        return player.decodeWrites();
    }

    /** The byte the retrigger tick will write to R13, out of the running
     * player's own code: a self-modified immediate inside the tick block, so
     * reading the instruction is the one way to know what the buzzer will
     * restart. */
    static int patchedShape(Player player, String timer) {
        return (int) player.uc.value(
                Rig.CODE + player.symbol("ymx_retrigger_" + timer) + 4, 1);
    }



    /** The loop word is unsigned - a point of $8000 through $FFFE is legal
     * in any sample long enough to hold it - and init resolves it to an
     * absolute address. A sign-extended resolve lands 65536 bytes low, so
     * the proof is the resolved long against the arithmetic done by hand.
     * The rig's packed tunes carry short samples, so the file is built
     * here, stored sections alone. */
    static String runLoopPointResolve() {
        int loop = 0x8084;
        byte[] packed = storedYmx(4, loop + 96, loop);
        Player player = new Player(packed);
        if (player.init() != 0) {
            return "loop resolve: YMX_init rejected the tune";
        }
        long table = drumTable(player);
        long offset = player.uc.value(player.file + table, 4);
        long resolved = player.uc.value(
                Rig.CODE + player.symbol("ymx_samples") + 4, 4);
        if (resolved != player.file + offset + loop) {
            return "loop resolve: loop point $" + Integer.toHexString(loop)
                    + " resolved to $" + Long.toHexString(resolved)
                    + ", want $" + Long.toHexString(player.file + offset + loop);
        }
        return "";
    }

    /** A .ymx of stored sections, built to SPEC.md's header and table
     * layout, with one looped sample of the given length. */
    private static byte[] storedYmx(int frames, int length, int loop) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] where = new int[Rig.STREAMS];
        int at = org.ymx.YmxFormat.HEADER_SIZE;
        for (int stream = 0; stream < Rig.STREAMS; stream++) {
            while (at % 4 != 0) {
                body.write(0);
                at++;
            }
            where[stream] = at;
            for (int frame = 0; frame < frames; frame++) {
                body.write(stream == 16 ? 0xE4 : 0);    // T: 0->A 1->B 2->C 3->D
            }
            at += frames;
        }
        while (at % 4 != 0) {
            body.write(0);
            at++;
        }
        int table = at;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("YMX!".getBytes(StandardCharsets.US_ASCII));
        word(out, org.ymx.YmxFormat.VERSION);
        word(out, 1);                               // flags: starts over
        longWord(out, frames);
        word(out, 50);
        word(out, Rig.STREAMS);
        word(out, 960);                             // ring
        word(out, 24);                              // chunk
        longWord(out, 2000000);
        longWord(out, table);
        word(out, 1);                               // one sample
        longWord(out, 0);                           // L: back to the beginning
        longWord(out, 0);                           // no loop table
        longWord(out, org.ymx.YmxFormat.REQUIRED_BASE);     // Q: no extension
        for (int stream = 0; stream < Rig.STREAMS; stream++) {
            longWord(out, 0x80000000 | where[stream]);  // bit 31: stored
        }
        out.writeBytes(body.toByteArray());
        longWord(out, table + 8);                   // the sample's offset
        word(out, length);
        word(out, loop);
        out.writeBytes(new byte[length]);           // the levels, then the
        out.write(0x80);                            // end marker
        return out.toByteArray();
    }

    /**
     * A file cut in two at its loop frame, every section stored: the section
     * table locates frames {@code [0, L)} and the loop table {@code [L, O)}.
     * A short replay packs smaller stored than as a container, so this is a
     * shape a writer reaches; here it also puts the loop table's own entries
     * on the stored path. {@code L} is under one group, so the first refill
     * of every stream already runs into the second section.
     */
    static String runStoredCut() {
        int frames = 96;
        int loop = 12;
        int ring = 48;
        byte[] file = storedCutYmx(frames, loop, ring, 24);
        Player player = new Player(file, 1);
        if (player.init() != 0) {
            return "stored cut: YMX_init rejected the file";
        }
        // Two passes past the first: R0 carries the frame number, so the
        // values written to it are the frames the player played.
        for (int index = 0; index < frames + 2 * (frames - loop); index++) {
            Player.Frame played = player.frame();
            int wanted = index < frames ? index
                    : loop + (index - frames) % (frames - loop);
            Integer got = null;
            for (Player.Pair write : played.writes()) {
                if (write.register() == 0) {
                    got = write.value();
                }
            }
            if (got == null || got != wanted) {
                return "stored cut: call " + index + " wrote R0=" + got
                        + ", want frame " + wanted;
            }
        }
        return "";
    }

    /** The file {@link #runStoredCut} plays: twenty-five stored sections of
     * {@code loop} values, twenty-five more of the rest, and the two tables
     * that locate them. R0 carries the frame number and every other stream
     * holds one value, so what reaches the chip says which frame is playing. */
    private static byte[] storedCutYmx(int frames, int loop, int ring, int chunk) {
        int table = align(org.ymx.YmxFormat.HEADER_SIZE);
        int at = table + 4 * Rig.STREAMS;
        int[] first = new int[Rig.STREAMS];
        int[] second = new int[Rig.STREAMS];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int half = 0; half < 2; half++) {
            for (int stream = 0; stream < Rig.STREAMS; stream++) {
                while (at % 4 != 0) {
                    body.write(0);
                    at++;
                }
                int from = half == 0 ? 0 : loop;
                int to = half == 0 ? loop : frames;
                (half == 0 ? first : second)[stream] = at;
                for (int frame = from; frame < to; frame++) {
                    body.write(streamByte(stream, frame));
                }
                at += to - from;
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("YMX!".getBytes(StandardCharsets.US_ASCII));
        word(out, org.ymx.YmxFormat.VERSION);
        word(out, 1);                               // flags: starts over
        longWord(out, frames);
        word(out, 50);
        word(out, Rig.STREAMS);
        word(out, ring);
        word(out, chunk);
        longWord(out, 2000000);
        longWord(out, 0);                           // no samples
        word(out, 0);
        longWord(out, loop);                        // L, where it starts over
        longWord(out, table);                       // and the table for [L, O)
        longWord(out, org.ymx.YmxFormat.REQUIRED_BASE);     // Q: no extension
        for (int stream = 0; stream < Rig.STREAMS; stream++) {
            longWord(out, 0x80000000 | first[stream]);  // bit 31: stored
        }
        while (out.size() < table) {
            out.write(0);
        }
        for (int stream = 0; stream < Rig.STREAMS; stream++) {
            longWord(out, 0x80000000 | second[stream]);
        }
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /** One stream's byte on one frame of that file. */
    private static int streamByte(int stream, int frame) {
        return switch (stream) {
            case 0 -> frame & 0xFF;                 // R0: the frame number
            case 13 -> 0xFF;                        // R13: no envelope restart
            case 16 -> 0xE4;                        // T: 0->A 1->B 2->C 3->D
            default -> 0;
        };
    }

    private static int align(int at) {
        return at + ((-at) & 3);
    }

    private static void word(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void longWord(ByteArrayOutputStream out, int value) {
        word(out, value >>> 16);
        word(out, value);
    }




    /** One number under a name: what the packer measured, what the document
     * says. */
    record Measured(String what, long is, long said) {}


    // said() reports through this: the sentence a number lives in was
    // reworded away from the pattern that reads it back out.
    private static String reworded = "";

    private static @org.jspecify.annotations.Nullable List<String> said(
            String flat, String pattern, String what) {
        Matcher found = Pattern.compile(pattern).matcher(flat);
        if (!found.find()) {
            reworded = "conversion numbers: the sentence giving " + what
                    + " no longer matches its pattern - this check reads them"
                    + " out of it";
            return null;
        }
        List<String> groups = new ArrayList<>();
        for (int i = 1; i <= found.groupCount(); i++) {
            groups.add(found.group(i));
        }
        return groups;
    }

    private static long number(String text) {
        return Long.parseLong(text.replace(",", ""));
    }

    private static long opcode(Map<String, Integer> counts, String name) {
        Integer count = counts.get(name);
        return count == null ? 0 : count;
    }

    /** The README's two byte counts, against what the assembler just
     * produced. Both numbers come out of one build: YMX.S runs from the
     * start of the binary to ST4_wrap.S's first symbol, and ST4_wrap.S is
     * the rest of it. */
    static String runReadmeSizes() {
        String text;
        try {
            text = Files.readString(Rig.REPO.resolve("README.md"));
        } catch (IOException e) {
            return "README sizes: " + e;
        }
        Matcher playerSaid = Pattern.compile("is the player, ([\\d,]+) bytes at"
                + " the `ST4_UNIT` (\\d)").matcher(text);
        Matcher wrapSaid = Pattern.compile("plus the ([\\d,]+) of"
                + " \\[68k/ST4_wrap\\.S\\]").matcher(text);
        if (!playerSaid.find() || !wrapSaid.find()) {
            return "README sizes: the sentence carrying them has been"
                    + " reworded. It must still read \"is the player, N bytes"
                    + " at the `ST4_UNIT` k\" and \"plus the M of"
                    + " [68k/ST4_wrap.S]\", which is what this check reads"
                    + " them out of";
        }
        int unit = Integer.parseInt(playerSaid.group(2));
        Rig.Build build = Rig.assembleMasked(unit, false);
        int player = Rig.symbol(build.symbols(), "ST4_init");
        int wrap = build.binary().length - player;
        long saidPlayer = number(playerSaid.group(1));
        long saidWrap = number(wrapSaid.group(1));
        if (saidPlayer != player || saidWrap != wrap) {
            return "README sizes: it says " + saidPlayer + " + " + saidWrap
                    + " bytes at ST4_UNIT " + unit + "; this build is "
                    + player + " + " + wrap;
        }
        return "";
    }

    /** The SNDH container, end to end: three subtunes built by mksndh.sh,
     * the blob loaded at an arbitrary even address, every entry preserving
     * d0-a6, each subtune playing its own data, the machine state handed
     * back at exit - subtunes 1 and 2 run a SID on the default Timer A,
     * subtune 3 the same SID on Timer B, so the per-claim restore covers
     * both - and init-without-exit recovering by itself. */
    static String runSndh() throws IOException {
        // Subtune 2 is shorter than the window played below, so it
        // reaches its own wrap while the others do not: nothing a wrapped
        // subtune leaves in the workspace survives the switch to the next.
        int[][] signatures = sndhSignatures();
        Files.createDirectories(Rig.SCRATCH);
        List<String> command = new ArrayList<>(List.of("sh",
                Rig.REPO.resolve("ymx").resolve("mksndh.sh").toString(),
                "-tRig", Rig.SCRATCH.resolve("sndh_test.sndh").toString()));
        for (int i = 0; i < 3; i++) {
            String[] extra = i == 2 ? new String[] {"-timersB"} : new String[0];
            Path tune = Rig.SCRATCH.resolve("sndh_tune" + (i + 1) + ".ymx");
            Files.write(tune, Rig.pack(sndhSource(signatures[i]),
                    960, 24, true, 2, extra));
            command.add(tune.toString());
        }
        Rig.Finished build = Rig.tryRun(command);
        if (build.code() != 0) {
            return "sndh: build failed: " + build.output().strip();
        }
        byte[] blob = Files.readAllBytes(Rig.SCRATCH.resolve("sndh_test.sndh"));
        if (indexOf(blob, "SNDH", 0) != 12 || indexOf(blob, "HDNS", 0) > 256
                || indexOf(blob, "HDNS", 0) < 0) {
            return "sndh: the header is not an SNDH header";
        }
        // the subtune-name tag: word offsets from the tag start to NULs
        int sn = indexOf(blob, "!#SN", 0);
        for (int i = 0; i < 3; i++) {
            int at = sn + (((blob[sn + 4 + 2 * i] & 0xFF) << 8)
                    | (blob[sn + 5 + 2 * i] & 0xFF));
            StringBuilder name = new StringBuilder();
            while (blob[at] != 0) {
                name.append((char) blob[at++]);
            }
            if (!name.toString().equals("sndh_tune" + (i + 1))) {
                return "sndh: subtune " + (i + 1) + " is named " + name;
            }
        }

        return checkSndh(blob, signatures);
    }

    /**
     * A file that requires an extension stream, against a player that
     * implements none. §1.6's mask is the whole of 0.6's new behaviour and
     * the only thing in the format a build rejects a file for: this build
     * implements streams 0 to 24, so a file whose mask names stream 25 is
     * one it rejects rather than plays.
     *
     * <p>The control is the same file with the bit clear and the stream
     * absent: that one plays, so a rejection here is the mask and not the
     * shape of a hand-built file.
     */
    static String runRequiredExtension() {
        byte[] plain = storedYmx(4, 96, 0);
        Player accepted = new Player(plain);
        if (accepted.init() != 0) {
            return "required extension: the control file was rejected, so"
                    + " this check would pass whatever the mask did";
        }

        byte[] required = storedYmx(4, 96, 0);
        int mask = org.ymx.YmxFormat.OFFSET_REQUIRED;
        required[mask] |= 0x02;                 // bit 25 of a big-endian
                                                // long is bit 1 of byte 0
        Player refused = new Player(required);
        if (refused.init() == 0) {
            return "required extension: a file requiring stream 25 was"
                    + " accepted by a build that implements no extension"
                    + " stream, so §1.6's mask decides nothing";
        }

        byte[] wider = storedYmx(4, 96, 0);
        wider[org.ymx.YmxFormat.OFFSET_STREAM_COUNT + 1] = 33;
        Player tooMany = new Player(wider);
        if (tooMany.init() == 0) {
            return "required extension: a file claiming 33 streams was"
                    + " accepted, and thirty-two is the ceiling at every"
                    + " version";
        }
        return "";
    }

    /**
     * The conformance kit, against the player it was taken from.
     * {@code doc/conformance} holds eleven tunes and a digest of what the
     * player writes for each, and the exercise that tests SPEC.md hands a
     * reader the tunes and keeps the digests back. The kit is only worth
     * handing over while it still describes this player, so this replays
     * every tune and checks the digest.
     *
     * <p>The dumps themselves are not in the tree: they come to 1.8 MB and
     * they are derived from a player that is in the tree, so a digest is
     * what there is to keep.
     */
    static String runConformanceKit() throws IOException {
        Path kit = Rig.REPO.resolve("doc").resolve("conformance");
        Path manifest = kit.resolve("MANIFEST.txt");
        if (!Files.exists(manifest)) {
            return "conformance: doc/conformance/MANIFEST.txt is missing";
        }
        int checked = 0;
        for (String line : Files.readAllLines(manifest)) {
            String[] row = line.trim().split("\\s+");
            if (row.length != 5 || row[4].length() != 64) {
                continue;                       // a heading or a blank line
            }
            Path tune = kit.resolve("tunes").resolve(row[0] + ".ymx");
            if (!Files.exists(tune)) {
                return "conformance: " + row[0] + " is in the manifest and not"
                        + " in tunes/";
            }
            String dump = RefDump.dump(Files.readAllBytes(tune),
                    Integer.parseInt(row[1]), Integer.parseInt(row[2]));
            String got = sha256(dump.getBytes(StandardCharsets.UTF_8));
            if (!got.equals(row[4])) {
                return "conformance: " + row[0] + " no longer plays as the"
                        + " manifest records - the kit describes another"
                        + " player than this one, so re-take it before the"
                        + " next exercise";
            }
            checked++;
        }
        if (checked != 11) {
            return "conformance: the manifest has " + checked + " tunes, and"
                    + " doc/conformance/README.md accounts for eleven";
        }
        return "";
    }

    /**
     * The tick-carrying reference, against the player it was taken from.
     * {@code MANIFEST-ticks.txt} records what each tune writes with the
     * timers run, which is the record a player can be checked against and
     * {@code MANIFEST.txt}'s cannot (SPEC.md §9.4).
     *
     * <p>These dumps come to 10 MB, so a digest is what the tree keeps, as
     * it keeps one for the reader's record.
     */
    static String runTickReference() throws IOException {
        Path kit = Rig.REPO.resolve("doc").resolve("conformance");
        Path manifest = kit.resolve("MANIFEST-ticks.txt");
        if (!Files.exists(manifest)) {
            return "ticks: doc/conformance/MANIFEST-ticks.txt is missing";
        }
        int checked = 0;
        for (String line : Files.readAllLines(manifest)) {
            String[] row = line.trim().split("\\s+");
            if (row.length != 6 || row[5].length() != 64) {
                continue;                       // a heading or a blank line
            }
            Path tune = kit.resolve("tunes").resolve(row[0] + ".ymx");
            if (!Files.exists(tune)) {
                return "ticks: " + row[0] + " is in the manifest and not in"
                        + " tunes/";
            }
            String dump = TickDump.dump(Files.readAllBytes(tune),
                    Integer.parseInt(row[1]), Integer.parseInt(row[2]));
            if (!sha256(dump.getBytes(StandardCharsets.UTF_8)).equals(row[5])) {
                return "ticks: " + row[0] + " no longer plays as"
                        + " MANIFEST-ticks.txt records - a rate, a tick's"
                        + " byte or the order they land in has moved";
            }
            checked++;
        }
        if (checked != 11) {
            return "ticks: the manifest has " + checked + " tunes, and the"
                    + " kit holds eleven";
        }
        return "";
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is in every JRE", e);
        }
    }



    /**
     * The three subtunes' register-2 values, one per frame. Subtune 2 is
     * shorter than the window played below, so it reaches its own wrap
     * while the others do not: nothing a wrapped subtune leaves in the
     * workspace survives the switch to the next.
     */
    private static int[][] sndhSignatures() {
        int[] lengths = {200, 20, 200};
        int[][] signatures = new int[3][];
        for (int i = 0; i < 3; i++) {
            signatures[i] = new int[lengths[i]];
        }
        for (int f = 0; f < lengths[0]; f++) {
            signatures[0][f] = (3 * f + 1) & 0xFF;
        }
        for (int f = 0; f < lengths[1]; f++) {
            signatures[1][f] = (0x55 + 7 * f) & 0xFF;
        }
        for (int f = 0; f < lengths[2]; f++) {
            signatures[2][f] = (0xA0 + f) & 0xFF;
        }
        return signatures;
    }

    /** One subtune as a YM6 file: its own value in register 2 each frame,
     * and a held SID on voice A from frame 5, so the tune claims a timer. */
    private static byte[] sndhSource(int[] signature) {
        int frames = signature.length;
        byte[][] values = new byte[16][frames];
        for (int f = 0; f < frames; f++) {
            values[2][f] = (byte) signature[f];
            values[13][f] = (byte) GenYm.NO_ENVELOPE_CHANGE;
        }
        for (int f = 5; f < frames; f++) {
            values[1][f] |= 0x10;
            values[6][f] |= 1 << 5;
            values[14][f] = 100;
            values[8][f] = 10;
        }
        return GenYm.ym6File(frames, values);
    }

    /**
     * Every core a release publishes, each playing a tune. {@code runSndh}
     * covers the one core {@code dist/} holds for the default flags; the
     * twelve a release carries are three unit sizes by the raster monitor
     * and the frame mask, and eight of them reached the archive without a
     * tune ever running on one. The monitor paints a colour register and
     * the mask changes what the frame write sits behind, so neither reaches
     * the sound chip: every core plays the same values, and this reads them
     * back the way {@link #runSndh} does.
     */
    static String runSndhEveryCore() throws IOException {
        Path cores = Rig.SCRATCH.resolve("cores");
        Files.createDirectories(cores);
        for (boolean perf : new boolean[] {false, true}) {
            for (boolean nomask : new boolean[] {false, true}) {
                MkCores.cores(cores, perf, nomask);
                for (int unit : new int[] {1, 2, 4}) {
                    String name = "ymxsndh-k" + unit + (perf ? "-perf" : "")
                            + (nomask ? "-nomask" : "") + Tools.binarySuffix()
                            + ".bin";
                    String problem = sndhOnCore(cores.resolve(name), unit,
                            perf, !nomask);
                    if (!problem.isEmpty()) {
                        return name + ": " + problem;
                    }
                }
            }
        }
        return "";
    }

    /**
     * Every tune the tree pins, played twice: straight through the player,
     * and through the combine path a release gives a host - the tune inside
     * an SNDH file built around a real core. The two must write the same
     * values to the sound chip on every frame.
     *
     * <p>The sweeps replay a whole collection against the player and compare
     * it to a model of the source, so the packer and the player are covered
     * broadly. The combine path is not the same code: the SNDH glue claims
     * the timers, sets the replay rate and enters through its own triple,
     * and until now three synthetic subtunes were all that had gone through
     * it. These are real tunes, one of them the ring form with its raised
     * ring and one the cut.
     *
     * <p>Nothing is compared against a model here: the straight path is
     * already held to one by the sweeps, so the two paths agreeing is what
     * this adds.
     */
    static String runSndhCorpus() throws IOException {
        Path cores = Rig.SCRATCH.resolve("cores");
        MkCores.cores(cores, false, false);
        List<Path> pinned = new ArrayList<>();
        for (String where : new String[] {"ym", "ymr"}) {
            try (Stream<Path> walk = Files.list(Rig.REPO.resolve(where)
                    .resolve("test"))) {
                walk.filter(path -> path.toString().endsWith(".ymx"))
                        .sorted().forEach(pinned::add);
            }
        }
        if (pinned.isEmpty()) {
            return "sndh corpus: no pinned .ymx under ym/test";
        }
        for (Path tune : pinned) {
            String problem = sndhAgainstPlayer(tune, cores);
            if (!problem.isEmpty()) {
                return problem;
            }
        }
        return "";
    }

    /** One pinned tune down both paths, frame by frame. A core reads one
     * unit size and rejects a tune packed for another, so the tune's own
     * header selects both the core and the player it is read by. */
    private static String sndhAgainstPlayer(Path tune, Path cores)
            throws IOException {
        byte[] packed = Files.readAllBytes(tune);
        String name = tune.getFileName().toString();
        int frames = header(packed, 8, 4);
        int budget = Math.min(frames + 40, 400);

        org.ymx.YmxHeader read = org.ymx.YmxHeader.read(tune);
        int unit = read.anyUnit() ? 2 : read.unit();
        Path core = cores.resolve("ymxsndh-k" + unit + Tools.binarySuffix()
                + ".bin");
        Player straight = new Player(packed, unit);
        if (straight.init() != 0) {
            return "sndh corpus: " + name + ": YMX_init rejected the tune";
        }
        Path out = Rig.SCRATCH.resolve("corpus.sndh");
        MkSndh.build(new MkSndh.Options(out, List.of(tune), "Rig", null, null,
                false, true), core);
        Sndh combined = new Sndh(Files.readAllBytes(out));
        String problem = combined.call(0, 1);
        if (!problem.isEmpty()) {
            return "sndh corpus: " + name + ": " + problem;
        }

        for (int f = 0; f < budget; f++) {
            Player.Frame one = straight.frame();
            Sndh.Frame two = combined.frame();
            if (!two.problem().isEmpty()) {
                return "sndh corpus: " + name + " frame " + f + ": "
                        + two.problem();
            }
            if (one.result() == -1) {
                break;
            }
            Map<Integer, Integer> want = new HashMap<>();
            for (Player.Pair pair : one.writes()) {
                want.put(pair.register(), pair.value());
            }
            if (!want.equals(two.writes())) {
                return "sndh corpus: " + name + " frame " + f
                        + ": the player wrote " + want
                        + " and the SNDH core wrote " + two.writes();
            }
        }
        combined.call(4, 0xD0D0D0D0L);
        return "";
    }

    /** One core, combined with tunes packed at its own unit size and
     * played. The core is given rather than resolved from {@code dist/},
     * which is the only way to reach the variants a release carries. */
    private static String sndhOnCore(Path core, int unit, boolean perf,
            boolean maskBurst) throws IOException {
        int[][] signatures = sndhSignatures();
        List<Path> tunes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Path tune = Rig.SCRATCH.resolve("core_tune" + (i + 1) + ".ymx");
            Files.write(tune, Rig.pack(sndhSource(signatures[i]), 960, 24,
                    true, unit, i == 2 ? new String[] {"-timersB"}
                            : new String[0]));
            tunes.add(tune);
        }
        Path out = Rig.SCRATCH.resolve("core_test.sndh");
        MkSndh.build(new MkSndh.Options(out, tunes, "Rig", null, null,
                perf, maskBurst), core);
        return checkSndh(Files.readAllBytes(out), signatures);
    }

    /**
     * One built SNDH blob, driven through its three entries: the blob loaded
     * at an arbitrary even address, every entry preserving d0-a6, each
     * subtune playing its own data, the machine state handed back at exit,
     * and init-without-exit recovering by itself.
     */
    private static String checkSndh(byte[] blob, int[][] signatures) {
        Sndh player = new Sndh(blob);
        // sentinels for every timer's state: what a claim must hand back,
        // and what an unclaimed timer must never touch
        long[][] sentinels = {{0x134, 4, 0xCAFE0134L}, {0x120, 4, 0xCAFE0120L},
                {0x114, 4, 0xCAFE0114L}, {0x110, 4, 0xCAFE0110L},
                {0xFFFFFA19L, 1, 3}, {0xFFFFFA1BL, 1, 7}, {0xFFFFFA1DL, 1, 0x17},
                {0xFFFFFA1FL, 1, 99}, {0xFFFFFA21L, 1, 77},
                {0xFFFFFA23L, 1, 66}, {0xFFFFFA25L, 1, 88},
                {0xFFFFFA07L, 1, 0x21}, {0xFFFFFA13L, 1, 0x20},
                {0xFFFFFA09L, 1, 0x10}, {0xFFFFFA15L, 1, 0x11}};
        for (long[] sentinel : sentinels) {
            byte[] bytes = new byte[(int) sentinel[1]];
            for (int i = bytes.length - 1, v = (int) sentinel[2]; i >= 0; i--) {
                bytes[i] = (byte) v;
                v >>>= 8;
            }
            player.uc.write(sentinel[0], bytes);
        }

        String problem = player.call(0, 1);         // init subtune 1
        if (!problem.isEmpty()) {
            return "sndh: " + problem;
        }
        problem = playAndCheck(player, signatures, 1);
        if (!problem.isEmpty()) {
            return problem;
        }
        problem = player.call(4, 0xD0D0D0D0L);      // exit
        if (!problem.isEmpty()) {
            return "sndh: " + problem;
        }
        problem = handback(player, sentinels);      // Timer A restored, the
        if (!problem.isEmpty()) {                   // other three untouched
            return problem;
        }

        problem = player.call(0, 3);                // subtune 3: the SID on
        if (!problem.isEmpty()) {                   // Timer B
            return "sndh: " + problem;
        }
        problem = playAndCheck(player, signatures, 3);
        if (!problem.isEmpty()) {
            return problem;
        }
        problem = player.call(4, 0xD0D0D0D0L);      // exit
        if (!problem.isEmpty()) {
            return "sndh: " + problem;
        }
        problem = handback(player, sentinels);      // Timer B restored, the
        if (!problem.isEmpty()) {                   // other three untouched
            return problem;
        }

        problem = player.call(0, 2);                // subtune 2
        if (!problem.isEmpty()) {
            return "sndh: " + problem;
        }
        problem = playAndCheck(player, signatures, 2);
        if (!problem.isEmpty()) {
            return problem;
        }
        problem = player.call(0, 1);                // init WITHOUT exit
        if (!problem.isEmpty()) {
            return "sndh: " + problem;
        }
        problem = playAndCheck(player, signatures, 1);
        if (!problem.isEmpty()) {
            return problem;
        }
        problem = player.call(0, 11);               // out of range: subtune 1
        if (!problem.isEmpty()) {
            return "sndh: " + problem;
        }
        problem = playAndCheck(player, signatures, 1);
        if (!problem.isEmpty()) {
            return problem;
        }
        player.call(4, 0xD0D0D0D0L);

        return "";
    }

    private static String playAndCheck(Sndh player, int[][] signatures, int which) {
        int[] want = signatures[which - 1];
        for (int f = 0; f < 30; f++) {
            Sndh.Frame frame = player.frame();
            if (!frame.problem().isEmpty()) {
                return "sndh: " + frame.problem();
            }
            Integer got = frame.writes().get(2);
            if (got == null || got != want[f % want.length]) {
                return "sndh: subtune " + which + " frame " + f + " played "
                        + got + " want " + want[f % want.length]
                        + (f >= want.length ? " - past its wrap" : "");
            }
        }
        return "";
    }

    private static String handback(Sndh player, long[][] sentinels) {
        for (long[] sentinel : sentinels) {
            long got = player.uc.value(sentinel[0], (int) sentinel[1]);
            if (sentinel[0] == 0xFFFFFA1DL || sentinel[0] == 0xFFFFFA1BL) {
                if ((got & 0x0F) != (sentinel[2] & 0x0F)) {
                    return "sndh: exit lost the control nibble at "
                            + Long.toHexString(sentinel[0]);
                }
            } else if (got != sentinel[2]) {
                return "sndh: exit lost the state at "
                        + Long.toHexString(sentinel[0]);
            }
        }
        return "";
    }

    static int indexOf(byte[] haystack, String needle, int from) {
        byte[] wanted = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int at = from; at <= haystack.length - wanted.length; at++) {
            for (int i = 0; i < wanted.length; i++) {
                if (haystack[at + i] != wanted[i]) {
                    continue outer;
                }
            }
            return at;
        }
        return -1;
    }

    /** The whole battery, one status line per check. */
    static int battery(boolean quick) throws IOException {
        // frames, ring, chunk, label, starts over, extra passes, unit, and
        // where the header sends its player back to - left off where that is
        // frame 0, which is every shape but the four that name one
        List<Object[]> shapes = new ArrayList<>(List.of(
                new Object[] {600, 960, 24, "default 960/24", true, 1, 1},
                new Object[] {600, 960, 24, "plays once", false, 0, 1},
                new Object[] {600, 960, 24, "two passes more", true, 2, 1},
                new Object[] {600, 240, 24, "small ring 240/24", true, 1, 1},
                new Object[] {600, 48, 24, "two-group ring 48/24", true, 1, 1},
                new Object[] {600, 960, 64, "long calls 960/64", true, 1, 1},
                new Object[] {608, 34, 17, "tightest legal 34/17", true, 1, 1},
                new Object[] {37, 960, 24, "shorter than a ring", true, 3, 1},
                new Object[] {48, 48, 24, "exactly a ring", true, 2, 1},
                new Object[] {40, 960, 24, "shorter than two groups", true, 4, 1},
                new Object[] {24, 960, 24, "exactly one group", true, 2, 1},
                new Object[] {9, 960, 24, "shorter than one group", true, 3, 1},
                new Object[] {1, 960, 24, "a single frame", true, 5, 1},
                new Object[] {1, 960, 24, "a single frame, once", false, 0, 1},
                // Wider units: cheaper refills, and the packer's whole-unit
                // rules for the tune length and C must hold. The decoder is
                // a different build for each.
                new Object[] {600, 960, 24, "unit 2", true, 2, 2},
                new Object[] {600, 960, 24, "unit 2, plays once", false, 0, 2},
                new Object[] {600, 960, 24, "unit 4", true, 1, 4},
                // A header that loops from a frame other than 0: the file
                // carries L, and the player rewinds by O - L at the wrap
                // rather than starting the tune again.
                new Object[] {600, 960, 24, "loops from frame 200", true, 2, 1, 200},
                new Object[] {600, 960, 24, "loops from frame 599", true, 3, 1, 599},
                new Object[] {600, 240, 24, "a body past the ring", true, 2, 1, 100},
                new Object[] {600, 960, 24, "loops from frame 200, unit 2",
                    true, 2, 2, 200},
                // A body past the largest ring the format allows: the packer
                // cuts every stream at the loop frame and the file carries a
                // loop table, so the wrap opens the second section rather
                // than moving the cursor back.
                new Object[] {2688, 960, 24, "cut at frame 100", true, 2, 1, 100},
                // A first section shorter than a group: every stream runs out
                // of it inside its own first fill, at init, and opens the
                // loop table's before frame 0 is played.
                new Object[] {2688, 960, 24, "cut at frame 12, unit 2",
                    true, 2, 2, 12}));
        if (!quick) {
            shapes.add(new Object[] {4000, 960, 24, "four thousand frames",
                    true, 1, 1});
            shapes.add(new Object[] {4000, 2048, 32, "four thousand, 2048/32",
                    true, 1, 1});
            shapes.add(new Object[] {4000, 960, 24, "four thousand, unit 2",
                    true, 1, 2});
            shapes.add(new Object[] {4000, 2048, 32, "four thousand, unit 4",
                    true, 1, 4});
        }

        int failures = 0;
        for (Object[] shape : shapes) {
            String label = (String) shape[3];
            boolean loops = (Boolean) shape[4];
            int loopFrame = shape.length > 7 ? (Integer) shape[7] : 0;
            String problem = runShape((Integer) shape[0], (Integer) shape[1],
                    (Integer) shape[2], label, loops, (Integer) shape[5],
                    (Integer) shape[6], loopFrame);
            if (!problem.isEmpty()) {
                System.out.println("FAIL " + problem);
                failures++;
            } else {
                String where = loops ? "starts over" : "plays once";
                System.out.printf("OK   %-26s (%d frames, %d-byte rings, %s)%n",
                        label, shape[0], shape[1], where);
            }
        }

        failures += report(runSndh(),
                "the SNDH container       (subtunes, handback, re-init)");
        failures += report(runSndhEveryCore(),
                "every published core     (3 units x monitor x mask)");
        failures += report(runSndhCorpus(),
                "the pinned tunes combined (both paths, same chip writes)");
        failures += report(runConformanceKit(),
                "the conformance kit      (eleven tunes, digests of the player)");
        failures += report(runTickReference(),
                "the tick reference       (eleven tunes, every timer tick)");
        failures += report(runRequiredExtension(),
                "a required extension     (the mask rejects, the ceiling holds)");
        for (boolean perf : new boolean[] {false, true}) {
            // The PERF build stacks a colour band on the way in, so a loop
            // that leaves by a different door than the stop does has to put
            // it back.
            String build = perf ? ", PERF build" : "";
        }
        failures += report(runStoredCut(),
                "the stored cut           (both tables, values not containers)");
        failures += report(runLoopPointResolve(),
                "the loop-point resolve   (an unsigned word, $8000 and up)");
        failures += report(runReadmeSizes(),
                "the README sizes         (the two byte counts, measured)");

        for (boolean perf : new boolean[] {false, true}) {
            String problem = Effects.runEffects(perf);
            String build = perf ? "PERF build" : "";
            if (!problem.isEmpty()) {
                System.out.println("FAIL "
                        + (build.isEmpty() ? problem : build + ": " + problem));
                failures++;
            } else {
                String label = "the effect stage" + (build.isEmpty() ? "" : ", " + build);
                System.out.printf("OK   %-26s (timers, sanitize, mixer, skeleton)%n",
                        label);
            }
        }

        System.out.println(failures == 0 ? "ALL YMX PLAYER TESTS PASS"
                : failures + " FAILURES");
        return failures == 0 ? 0 : 1;
    }

    private static int report(String problem, String label) {
        if (!problem.isEmpty()) {
            System.out.println("FAIL " + problem);
            return 1;
        }
        System.out.println("OK   " + label);
        return 0;
    }

    static String stem(Path path) {
        String name = String.valueOf(path.getFileName());
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    public static void main(String[] args) throws IOException {
        boolean quick = Arrays.asList(args).contains("--quick");
        System.exit(battery(quick));
    }
}
