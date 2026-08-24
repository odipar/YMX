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

    /** Plays a whole tune (and {@code passes} times more) and checks it. */
    static String runShape(int frames, int ring, int chunk, String label,
            boolean loops, int passes, int unit) {
        byte[][] source = GenYm.registers(frames);
        byte[] packed = Rig.pack(GenYm.ym6File(frames, source), ring, chunk,
                loops, unit);
        int played = loops ? frames * (1 + passes) : frames;
        List<GenYm.ChipState> expected = GenYm.chipStates(frames, source, loops,
                played);

        Player player = new Player(packed, Rig.workspaceSize(ring), unit);
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
            // 0 again". A tune that plays once never reports it: it reports
            // -1 on the call after its last frame instead.
            boolean wrapped = position >= frames && loops;
            if (wrapped) {
                position = 0;
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

    /** Where a retrigger stream reads the shape it restarts: the YM front
     * end out of the low nibble of the voice the channel runs on, the .ymr
     * front end out of R13, where RhYMe holds it. The two are told apart by
     * making them DISAGREE - a voice whose nibble is one value while R13
     * holds another - and reading the tick's own patched immediate. */
    static String runShapeSource() {
        // The flag-clear path, every YM tune. A buzzer on voice B with R9's
        // nibble at 11, and R13 never written: reading the shadow would
        // restart 8, the value a tune that has written no shape is taken to
        // mean.
        int frames = 16;
        byte[][] values = new byte[16][frames];
        for (int frame = 0; frame < frames; frame++) {
            values[7][frame] = 0x38;
            values[9][frame] = 0x0B;                // voice B's level, nibble 11
            values[13][frame] = (byte) GenYm.NO_ENVELOPE_CHANGE;
        }
        for (int frame = 4; frame < 12; frame++) {  // sync-buzzer, voice B
            values[1][frame] = (byte) 0xE0;
            values[6][frame] |= 6 << 5;
            values[14][frame] = (byte) 200;
        }
        Player player = new Player(Rig.pack(GenYm.ym6File(frames, values),
                960, 24, true, 1), Rig.workspaceSize(960));
        if (player.init() != 0) {
            return "shape source: YMX_init rejected the YM tune";
        }
        for (int frame = 0; frame < 6; frame++) {
            player.frame();
        }
        int got = patchedShape(player, "a");        // a YM tune's slot 1 is Timer A
        if (got != 11) {
            return "shape source: a YM buzzer restarts shape " + got
                    + ", want 11 - the nibble of the voice it runs on";
        }

        // The flag-set path. R13 is popped to $0A on the very frame the RTE
        // arms, while voice B's level is $0C: the burst writes R13 before
        // the actions run, so the arm must take the NEW shape, 10, and not
        // the 12 sitting in the volume nibble. Frame 3 then moves the shape
        // under the running buzzer, which goes through the hold path rather
        // than the arm.
        int[][] pops = new int[8][];
        Arrays.fill(pops, new int[0]);
        pops[0] = new int[] {5, 7, 10, 14, 15};
        pops[3] = new int[] {10};
        byte[] image = Rig.ymrImage(8, pops, Map.of(
                5, new byte[] {0x38},               // mixer
                7, new byte[] {0x0C},               // voice B's level: nibble 12
                10, new byte[] {0x0A, 0x04},        // the shapes: 10, then 4
                14, new byte[] {3},                 // Timer B runs an RTE
                15, new byte[] {6, (byte) 200}),    // prescaler 6, count 200
                0);
        player = new Player(Rig.packYmr(image, 960, 24), Rig.workspaceSize(960));
        if (player.init() != 0) {
            return "shape source: YMX_init rejected the .ymr tune";
        }
        player.frame();                             // frame 0: R13 := 10, RTE arms
        got = patchedShape(player, "b");            // channel 1 of a .ymr is Timer B
        if (got != 10) {
            return "shape source: a .ymr buzzer arms on shape " + got
                    + ", want 10 - R13 as this frame wrote it, not the 12 in"
                    + " the volume nibble";
        }
        for (int frame = 1; frame < 4; frame++) {   // frame 3 pops the shape to 4
            player.frame();
        }
        got = patchedShape(player, "b");
        if (got != 4) {
            return "shape source: a shape moving under a running buzzer left"
                    + " the tick on " + got + ", want 4 - the hold path reads"
                    + " R13 too";
        }

        // And an RTE that arms before the tune has written any shape: the
        // spec says to assume 8, which is what RhYMe's own player primes.
        // Voice C's level is 15 here, so the two sources cannot be confused.
        pops = new int[8][];
        Arrays.fill(pops, new int[0]);
        pops[0] = new int[] {5, 8, 17, 18};
        image = Rig.ymrImage(8, pops, Map.of(
                5, new byte[] {0x38},
                8, new byte[] {0x1F},               // voice C: nibble 15
                17, new byte[] {3},                 // Timer D runs an RTE
                18, new byte[] {6, (byte) 200}),
                0);
        player = new Player(Rig.packYmr(image, 960, 24), Rig.workspaceSize(960));
        if (player.init() != 0) {
            return "shape source: YMX_init rejected the unshaped .ymr tune";
        }
        player.frame();
        got = patchedShape(player, "d");            // channel 2 of a .ymr is Timer D
        if (got != 8) {
            return "shape source: an RTE armed before any shape restarts " + got
                    + ", want 8 - the assumed shape, not voice C's nibble";
        }
        return "";
    }

    /** A looped sample, which the player loops rather than stopping: the
     * file carries the point the sample comes back to and the tick does the
     * coming back, so the proof is the pointer - play the block out, and the
     * tick after the marker must be reading the loop start rather than a
     * stopped timer. */
    static String runSampleLoop(boolean perf) {
        int[][] pops = new int[8][];
        Arrays.fill(pops, new int[0]);
        pops[0] = new int[] {5, 7, 14, 15, 16};
        byte[] image = Rig.ymrImage(8, pops, Map.of(
                5, new byte[] {0x38},               // mixer
                7, new byte[] {0x0C},               // voice B's level
                14, new byte[] {2},                 // Timer B runs a Sample
                15, new byte[] {6, (byte) 200},     // prescaler 6, count 200
                16, new byte[] {0}),                // sample 0
                0, new Rig.SampleBlock(new byte[] {1, 2, 3, 4}, true, 1));
        Player player = new Player(Rig.packYmr(image, 960, 24),
                Rig.workspaceSize(960), 1, perf);
        if (player.init() != 0) {
            return "sample loop: YMX_init rejected the tune";
        }
        player.frame();                             // frame 0 starts the sample

        long table = drumTable(player);
        long offset = player.uc.value(player.file + table, 4);
        long loop = player.uc.value(player.file + table + 6, 2);
        if (loop != 1) {
            return "sample loop: the file stores loop point " + loop + ", want 1";
        }

        long code = Rig.CODE + player.symbol("ymx_pcm_b");
        int register = 9;                           // voice B's volume, which
        int[] levels = {1, 2, 3, 4};                // the tick selects itself
        for (int tick = 0; tick < levels.length; tick++) {
            List<Player.Pair> pairs = invokeIsr(player, code);
            if (!pairs.equals(List.of(new Player.Pair(register, levels[tick])))) {
                return "sample loop: tick " + tick + " wrote " + pairs
                        + ", want level " + levels[tick];
            }
        }

        // The marker tick. It has already written the marker as a level -
        // one sample of silence - but it must NOT stop the timer, and must
        // leave the pointer on the loop start rather than past the end.
        int at = player.mfp.size();
        List<Player.Pair> pairs = invokeIsr(player, code);
        if (!pairs.equals(List.of(new Player.Pair(register, 0x80)))) {
            return "sample loop: the marker tick wrote " + pairs
                    + ", want the marker alone";
        }
        long tbcr = 0xFFFFFA1BL;                    // every tick ends with an
        for (Player.Write write : player.mfp.subList(at, player.mfp.size())) {
            if (write.address() == tbcr) {          // EOI; only a stop touches
                return "sample loop: the marker tick programmed " + write
                        + " - a looping stream stops nothing";   // the control
            }
        }
        // The tick is reached, never returned from - this emulator build
        // cannot run an rte - so a stack left unbalanced is invisible unless
        // it is read off directly. The loop leaves the tick by a different
        // door than the stop does, and the PERF build has a colour band
        // stacked at that point.
        long left = player.uc.register(Unicorn.A7);
        if (left != Rig.STACK_TOP - 512) {
            return "sample loop: the marker tick reached its rte with the stack "
                    + (Rig.STACK_TOP - 512 - left) + " bytes off";
        }
        long position = player.uc.value(code + player.symbol("ISR_PCM_PTR"), 4);
        if (position != player.file + offset + 1) {
            return "sample loop: after the marker the tick reads "
                    + Long.toHexString(position) + ", want "
                    + Long.toHexString(player.file + offset + 1)
                    + " - the loop start";
        }

        int[] again = {2, 3, 4};                    // round it goes again
        for (int tick = 0; tick < again.length; tick++) {
            pairs = invokeIsr(player, code);
            if (!pairs.equals(List.of(new Player.Pair(register, again[tick])))) {
                return "sample loop: pass 2 tick " + tick + " wrote " + pairs
                        + ", want " + again[tick];
            }
        }
        return "";
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
        Player player = new Player(packed, Rig.workspaceSize(960));
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

    private static void word(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void longWord(ByteArrayOutputStream out, int value) {
        word(out, value >>> 16);
        word(out, value);
    }

    /** A rate pop under a running effect, done without stopping it: the opcode
     * is RETUNE addressed to voice 3, and its difference is only visible in
     * the MFP traffic - the ordinary retune stops the timer and runs it
     * again, and this must never write a zero into the timer's nibble. */
    static String runLiveRetune() {
        int[][] pops = new int[6][];
        Arrays.fill(pops, new int[0]);
        pops[0] = new int[] {5, 7, 14, 15};
        pops[3] = new int[] {15};                   // the rate alone moves
        byte[] image = Rig.ymrImage(6, pops, Map.of(
                5, new byte[] {0x38},
                7, new byte[] {0x0C},               // voice B's level, unmoved
                14, new byte[] {1},                 // Timer B runs a PWM
                15, new byte[] {6, (byte) 200, 5, (byte) 200}),  // 6 -> 5
                0);
        Player player = new Player(Rig.packYmr(image, 960, 24),
                Rig.workspaceSize(960));
        if (player.init() != 0) {
            return "live retune: YMX_init rejected the tune";
        }
        for (int frame = 0; frame < 3; frame++) {
            player.frame();
        }
        player.mfp.clear();
        player.frame();                             // frame 3: the rate pop
        long ctrl = 0xFFFFFA1BL;                    // Timer B's control, data
        long data = 0xFFFFFA21L;
        List<Player.Write> written = new ArrayList<>();
        for (Player.Write write : player.mfp) {
            if (write.address() == ctrl || write.address() == data) {
                written.add(write);
            }
        }
        if (written.size() != 2 || written.get(0).address() != ctrl
                || written.get(1).address() != data) {
            return "live retune: frame 3 touched the MFP as " + written
                    + ", want the control register then the data register,"
                    + " once each";
        }
        if ((written.get(0).value() & 7) == 0) {
            return "live retune: the control write stopped the timer, which a"
                    + " live retune never does";
        }
        if ((written.get(0).value() & 7) != 5 || written.get(1).value() != 200) {
            return "live retune: frame 3 programmed prescaler "
                    + (written.get(0).value() & 7) + " count "
                    + written.get(1).value() + ", want 5 and 200";
        }
        return "";
    }

    /** The packer's own report for one .ymr, as the documentation quotes it. */
    static String packerReport(Path tune) {
        Path out = Rig.SCRATCH.resolve(stem(tune) + ".ymx");
        return Rig.run(List.of("java", "-ea", "-cp", Rig.CLASSES.toString(),
                "org.ymr.Ymr", "-f", tune.toString(), out.toString()));
    }

    /** Opcode counts from one tune's compiled script: a RETUNE addressed to
     * voice 3 is the live retune, one to a real voice stops the timer to
     * reprogram it, and a HOLD with bit 0 reloads the count under a running
     * one. */
    static Map<String, Integer> scriptOpcodes(Path tune) {
        String script = Rig.run(List.of("java", "-ea", "-cp",
                Rig.CLASSES.toString(), "org.ymr.Ymr", "-script", tune.toString()));
        Map<String, Integer> counts = new HashMap<>(Map.of(
                "live retune", 0, "stopping retune", 0, "live reload", 0));
        Matcher action = Pattern.compile("A[0-3]=([0-9A-F]{2})").matcher(script);
        while (action.find()) {
            int value = Integer.parseInt(action.group(1), 16);
            int opcode = value >> 5;
            int voice = (value >> 3) & 3;
            if (opcode == 4) {
                String which = voice == 3 ? "live retune" : "stopping retune";
                counts.merge(which, 1, Integer::sum);
            } else if (opcode == 1 && (value & 1) != 0) {
                counts.merge("live reload", 1, Integer::sum);
            }
        }
        return counts;
    }

    /** One number under a name: what the packer measured, what the document
     * says. */
    record Measured(String what, long is, long said) {}

    /** The figures ymr/CONVERSION.md quotes, against the tunes it names.
     * Every one is a measurement, re-measured the way the document says
     * they were taken: the packer's own report for the byte counts, and the
     * compiled script for the opcode counts. */
    static String runConversionNumbers() {
        String flat;
        try {
            flat = String.join(" ", Files.readString(
                    Rig.REPO.resolve("ymr").resolve("CONVERSION.md")).split("\\s+"));
        } catch (IOException e) {
            return "conversion numbers: " + e;
        }

        List<String> tuneAndLength = said(flat,
                "`([\\w./-]+\\.ymr)` is ([\\d,]+) frames at (\\d+) Hz",
                "the tune and its length");
        List<String> packedSizes = said(flat,
                "reports ([\\d,]+) bytes of register and script data packed"
                        + " into ([\\d,]+) \\(([\\d,.]+)%\\) in a"
                        + " ([\\d,]+)-byte file", "the packed sizes");
        List<String> ringShape = said(flat,
                "([\\d,]+) rings of ([\\d,]+) bytes, decoding (\\d+) of the"
                        + " (\\d+) streams", "the ring shape");
        List<String> scriptStreams = said(flat,
                "([\\d,]+) of those ([\\d,]+) packed bytes are the eleven"
                        + " script streams, which the `\\.YMR` -"
                        + " ([\\d,]+) bytes", "the script streams");
        if (tuneAndLength == null || packedSizes == null || ringShape == null
                || scriptStreams == null) {
            return reworded;
        }
        String tune = tuneAndLength.get(0);
        List<String> opcodeCounts = said(flat,
                "on `" + Pattern.quote(tune) + "` the compiled script carries"
                        + " ([\\d,]+) live reloads and ([\\d,]+) live retunes"
                        + " against no opcode that stops", "the opcode counts");
        List<String> otherOpcodes = said(flat,
                "`([\\w./-]+\\.ymr)` has ([\\d,]+) live retunes and (\\d+)"
                        + " that stop", "the second tune's opcode counts");
        if (opcodeCounts == null || otherOpcodes == null) {
            return reworded;
        }
        String other = otherOpcodes.get(0);
        Path tunePath = Rig.REPO.resolve(tune);
        Path otherPath = Rig.REPO.resolve(other);
        if (!Files.exists(tunePath) || !Files.exists(otherPath)) {
            return "conversion numbers: a named tune is not in the tree";
        }

        String report = packerReport(tunePath);
        List<Measured> measured = new ArrayList<>();
        Matcher got = Pattern.compile("Packed (\\d+) register bytes into (\\d+)"
                + " \\([\\d,.]+%\\), file (\\d+) bytes").matcher(report);
        if (!got.find()) {
            return "conversion numbers: the packer no longer reports its"
                    + " packed sizes";
        }
        measured.add(new Measured("register bytes", Long.parseLong(got.group(1)),
                number(packedSizes.get(0))));
        measured.add(new Measured("packed bytes", Long.parseLong(got.group(2)),
                number(packedSizes.get(1))));
        measured.add(new Measured("file bytes", Long.parseLong(got.group(3)),
                number(packedSizes.get(3))));

        Matcher shape = Pattern.compile("Player needs (\\d+) bytes of ring"
                + "[\\s\\S]*? decodes (\\d+) of the (\\d+) streams").matcher(report);
        if (!shape.find()) {
            return "conversion numbers: the packer no longer reports its"
                    + " ring shape";
        }
        measured.add(new Measured("ring bytes", Long.parseLong(shape.group(1)),
                number(ringShape.get(0)) * number(ringShape.get(1))));
        measured.add(new Measured("streams decoded", Long.parseLong(shape.group(2)),
                number(ringShape.get(2))));
        measured.add(new Measured("streams stored", Long.parseLong(shape.group(3)),
                number(ringShape.get(3))));

        // the eleven script streams, summed out of the per-stream listing
        long script = 0;
        Matcher stream = Pattern.compile("(?m)^\\s+(M|X|T|A[0-3]|P[0-3])"
                + "\\s+\\d+\\s+->\\s+(\\d+) bytes").matcher(report);
        while (stream.find()) {
            script += Long.parseLong(stream.group(2));
        }
        measured.add(new Measured("script bytes", script,
                number(scriptStreams.get(0))));
        measured.add(new Measured("packed bytes, again", Long.parseLong(got.group(2)),
                number(scriptStreams.get(1))));
        try {
            measured.add(new Measured(".YMR bytes", Files.size(tunePath),
                    number(scriptStreams.get(2))));
        } catch (IOException e) {
            return "conversion numbers: " + e;
        }
        long frames = 0;
        Matcher perStream = Pattern.compile("(?m)^\\s+\\S+\\s+(\\d+)\\s+->")
                .matcher(report);
        while (perStream.find()) {
            frames = Math.max(frames, Long.parseLong(perStream.group(1)));
        }
        measured.add(new Measured("source frames", frames,
                number(tuneAndLength.get(1))));

        Map<String, Integer> opcodes = scriptOpcodes(tunePath);
        measured.add(new Measured("live reloads", opcode(opcodes, "live reload"),
                number(opcodeCounts.get(0))));
        measured.add(new Measured("live retunes", opcode(opcodes, "live retune"),
                number(opcodeCounts.get(1))));
        measured.add(new Measured("stopping retunes",
                opcode(opcodes, "stopping retune"), 0));
        Map<String, Integer> second = scriptOpcodes(otherPath);
        measured.add(new Measured(other + " live retunes",
                opcode(second, "live retune"), number(otherOpcodes.get(1))));
        measured.add(new Measured(other + " stopping retunes",
                opcode(second, "stopping retune"), number(otherOpcodes.get(2))));

        StringBuilder wrong = new StringBuilder();
        for (Measured entry : measured) {
            if (entry.is() != entry.said()) {
                wrong.append(wrong.isEmpty() ? "" : "; ").append(entry.what())
                        .append(' ').append(entry.is()).append(" not ")
                        .append(entry.said());
            }
        }
        return wrong.isEmpty() ? "" : "conversion numbers: " + wrong;
    }

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
        Files.createDirectories(Rig.SCRATCH);
        List<String> command = new ArrayList<>(List.of("sh",
                Rig.REPO.resolve("ymx").resolve("mksndh.sh").toString(),
                "-tRig", Rig.SCRATCH.resolve("sndh_test.sndh").toString()));
        for (int i = 0; i < 3; i++) {
            int frames = lengths[i];
            byte[][] values = new byte[16][frames];
            for (int f = 0; f < frames; f++) {
                values[2][f] = (byte) signatures[i][f];
                values[13][f] = (byte) GenYm.NO_ENVELOPE_CHANGE;
            }
            for (int f = 5; f < frames; f++) {      // a held SID on voice A,
                values[1][f] |= 0x10;               // so the tune claims
                values[6][f] |= 1 << 5;             // channel 0's timer
                values[14][f] = 100;
                values[8][f] = 10;
            }
            String[] extra = i == 2 ? new String[] {"-timersB"} : new String[0];
            Path tune = Rig.SCRATCH.resolve("sndh_tune" + (i + 1) + ".ymx");
            Files.write(tune, Rig.pack(GenYm.ym6File(frames, values),
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
        // frames, ring, chunk, starts over, extra passes, unit
        List<Object[]> shapes = new ArrayList<>(List.of(
                new Object[] {600, 960, 24, "default 960/24", true, 1, 1},
                new Object[] {600, 960, 24, "plays once", false, 0, 1},
                new Object[] {600, 960, 24, "two passes more", true, 2, 1},
                new Object[] {600, 240, 24, "small ring 240/24", true, 1, 1},
                new Object[] {600, 48, 24, "two-group ring 48/24", true, 1, 1},
                new Object[] {600, 960, 64, "long calls 960/64", true, 1, 1},
                new Object[] {608, 34, 17, "tightest legal 34/17", true, 1, 1},
                new Object[] {37, 960, 24, "shorter than a ring", true, 3, 1},
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
                new Object[] {600, 960, 24, "unit 4", true, 1, 4}));
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
            String problem = runShape((Integer) shape[0], (Integer) shape[1],
                    (Integer) shape[2], label, loops, (Integer) shape[5],
                    (Integer) shape[6]);
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
        failures += report(runShapeSource(),
                "the retrigger shape      (both sources, off the patched tick)");
        for (boolean perf : new boolean[] {false, true}) {
            // The PERF build stacks a colour band on the way in, so a loop
            // that leaves by a different door than the stop does has to put
            // it back.
            String build = perf ? ", PERF build" : "";
            failures += report(runSampleLoop(perf), String.format(
                    "the sample loop%-9s    (back to the loop, not stopped)", build));
        }
        failures += report(runLoopPointResolve(),
                "the loop-point resolve   (an unsigned word, $8000 and up)");
        failures += report(runLiveRetune(),
                "the live retune          (the timer is never stopped)");
        failures += report(runReadmeSizes(),
                "the README sizes         (the two byte counts, measured)");
        failures += report(runConversionNumbers(),
                "the conversion numbers   (ymr/CONVERSION.md, re-measured)");

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
