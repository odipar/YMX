package org.ymx.rig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.ymx.Tools;

/**
 * What every rig shares: the repository's paths, the assembled player builds
 * with their symbol tables, the packers run as the tools a user runs, and a
 * hand-built .YMR image. The scratch directory caches packs on the tune
 * bytes and options, NOT on the packer's code - after changing the packer,
 * {@code rm -rf ymx/test/.work}.
 */
final class Rig {

    static final Path REPO = Tools.repo();
    static final Path CLASSES = REPO.resolve("target").resolve("classes");
    static final Path SCRATCH = REPO.resolve("ymx").resolve("test")
            .resolve(".work");

    static final long CODE = 0x001000;
    static final long FILE = 0x010000;
    static final long WORK = 0x040000;
    static final long STACK_TOP = 0x090000;
    static final long MAGIC = 0x0A0000;
    static final long PSG = 0xFFFF8800L;
    static final long PSG_PAGE = 0xFFFF8000L;
    static final long MFP_PAGE = 0xFFFFF000L;   // $FFFFFAxx: the timers
    static final long VECTORS = 0x000000;       // the timer vectors

    static final int STREAMS = 25;              // fourteen register, eleven script
    static final int YMX_DEFAULT_MAP = 0x9C;    // the packer's: 0->A 1->D 2->B 3->C
    static final int YMX_FIXED = 46 + STREAMS * 64; // the workspace before the rings

    private Rig() {}

    static int workspaceSize(int ring) {
        return YMX_FIXED + STREAMS * ring;
    }

    /** One assembled player build and where its labels sit. */
    record Build(byte[] binary, Map<String, Integer> symbols) {}

    private static final Map<String, Build> ASSEMBLED = new HashMap<>();

    /**
     * YMX.S plus the decoder, built for one unit size, as one flat blob.
     * perf builds the raster monitor in. YMX_NOMASK in the environment
     * runs the rig - the size check aside - against the variant whose
     * frame write is unmasked, the tools' -nomask.
     */
    static Build assemble(int unit, boolean perf) {
        return assemble(unit, perf, System.getenv("YMX_NOMASK") == null);
    }

    /** The masked build regardless of YMX_NOMASK: the README's byte
     * counts quote it, so the size check measures it. */
    static Build assembleMasked(int unit, boolean perf) {
        return assemble(unit, perf, true);
    }

    private static Build assemble(int unit, boolean perf, boolean masked) {
        String tag = unit + (perf ? "p" : "") + (masked ? "" : "n");
        Build built = ASSEMBLED.get(tag);
        if (built != null) {
            return built;
        }
        try {
            Files.createDirectories(SCRATCH);
            Path source = SCRATCH.resolve("link" + tag + ".S");
            Files.writeString(source, "ST4_UNIT    equ     " + unit + "\n"
                    + (perf ? "YMX_PERF    equ     1\n" : "")
                    + (masked ? "" : "YMX_MASK_BURST equ  0\n")
                    + "        include \"YMX.S\"\n"
                    + "        include \"ST4_wrap.S\"\n",
                    StandardCharsets.ISO_8859_1);
            Path binary = SCRATCH.resolve("link" + tag + ".bin");
            Path listing = SCRATCH.resolve("link" + tag + ".lst");
            run(List.of("rmac", "-m68000", "-fr", "+o3",
                    "-i" + REPO.resolve("68k"), "-l*" + listing,
                    "-o", binary.toString(), source.toString()));
            built = new Build(Files.readAllBytes(binary), symbolTable(listing));
            ASSEMBLED.put(tag, built);
            return built;
        } catch (IOException e) {
            throw new IllegalStateException("assembling " + tag + ": " + e);
        }
    }

    /** Every label in an rmac listing, from its symbol table - two symbols
     * per line, which is why the pattern matches within a line. */
    static Map<String, Integer> symbolTable(Path listing) throws IOException {
        Pattern pattern = Pattern.compile("(\\S+)\\s+([0-9A-F]{16})\\s+[atdb]\\b");
        Map<String, Integer> symbols = new HashMap<>();
        for (String line : Files.readAllLines(listing, StandardCharsets.ISO_8859_1)) {
            Matcher symbol = pattern.matcher(line);
            while (symbol.find()) {
                symbols.put(symbol.group(1),
                        (int) Long.parseLong(symbol.group(2), 16));
            }
        }
        for (String wanted : new String[] {"YMX_init", "YMX_play", "YMX_stop"}) {
            if (!symbols.containsKey(wanted)) {
                throw new IllegalStateException(wanted + " missing from the listing");
            }
        }
        return symbols;
    }

    /** One label's value, checked present. */
    static int symbol(Map<String, Integer> symbols, String name) {
        Integer value = symbols.get(name);
        if (value == null) {
            throw new IllegalStateException(name + " missing from the listing");
        }
        return value;
    }

    /** Runs the real YM packer, cached on the tune and the packing options.
     * loops says the tune starts over at the end; false packs one that
     * stops. */
    static byte[] pack(byte[] tune, int ring, int chunk, boolean loops, int unit,
            String... extra) {
        if (!Files.exists(CLASSES)) {
            throw new IllegalStateException(
                    "target/classes is missing; run `mvn compile` first");
        }
        String tag = String.join("", extra);
        Path cached = SCRATCH.resolve(sha1(tune) + "-n" + ring + "-c" + chunk
                + "-k" + unit + (loops ? "loops" : "once") + tag + ".ymx");
        if (!Files.exists(cached)) {
            packWith("org.ym6.Ymx", tune, ".ym", cached, join(
                    List.of("-f", "-n" + ring, "-c" + chunk, "-k" + unit),
                    loops ? List.of() : List.of("-o"), Arrays.asList(extra)));
        }
        return readBytes(cached);
    }

    /** The real .ymr packer, so the header flags are the ones it writes. */
    static byte[] packYmr(byte[] image, int ring, int chunk) {
        Path cached = SCRATCH.resolve("ymr-" + sha1(image) + "-n" + ring
                + "-c" + chunk + ".ymx");
        if (!Files.exists(cached)) {
            packWith("org.ymr.Ymr", image, ".ymr", cached,
                    List.of("-f", "-n" + ring, "-c" + chunk, "-k1"));
        }
        return readBytes(cached);
    }

    private static void packWith(String packer, byte[] source, String suffix,
            Path out, List<String> options) {
        try {
            Files.createDirectories(SCRATCH);
            Path tune = Files.createTempFile(SCRATCH, "tune", suffix);
            try {
                Files.write(tune, source);
                List<String> command = new ArrayList<>(List.of("java", "-ea",
                        "-cp", CLASSES.toString(), packer));
                command.addAll(options);
                command.add(tune.toString());
                command.add(out.toString());
                run(command);
            } finally {
                Files.deleteIfExists(tune);
            }
        } catch (IOException e) {
            throw new IllegalStateException(packer + ": " + e);
        }
    }

    /** A command that must succeed, its output captured either way. */
    static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(SCRATCH.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.ISO_8859_1);
            if (process.waitFor() != 0) {
                throw new IllegalStateException(command.get(0) + " failed:\n" + output);
            }
            return output;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(command.get(0) + ": " + e);
        }
    }

    /** The same command, allowed to fail: exit code and combined output. */
    record Finished(int code, String output) {}

    static Finished tryRun(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(SCRATCH.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.ISO_8859_1);
            return new Finished(process.waitFor(), output);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(command.get(0) + ": " + e);
        }
    }

    /** One sample block of a hand-built .YMR: pre-converted 4-bit levels,
     * whether it loops, and where it comes back to. */
    record SampleBlock(byte[] levels, boolean looped, int start) {}

    /**
     * A .YMR v1.3 register dump with every stream stored uncompressed.
     * pops[frame] lists the stream indices that frame pops, ascending, and
     * streams maps a stream index to its entries laid end to end. A ring
     * size of 0 is the format's own "stored uncompressed", so no ZX1 packer
     * runs here.
     */
    static byte[] ymrImage(int frames, int[][] pops, Map<Integer, byte[]> streams,
            int loop, SampleBlock... samples) {
        ByteArrayOutputStream command = new ByteArrayOutputStream();
        for (int frame = 0; frame < frames; frame++) {
            int[] popped = pops[frame].clone();
            Arrays.sort(popped);
            for (int stream : popped) {
                command.write(stream);
            }
            command.write(0);
        }
        Map<Integer, byte[]> present = new HashMap<>(streams);
        present.put(0, command.toByteArray());

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.writeBytes("YMR!".getBytes(StandardCharsets.US_ASCII));
        word(header, 0x0103);
        longWord(header, frames);
        longWord(header, loop);
        word(header, 50);
        word(header, samples.length);
        longWord(header, 2000000);
        word(header, 20);
        longWord(header, 0);

        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        for (SampleBlock sample : samples) {
            longWord(blocks, sample.levels().length);
            blocks.writeBytes(sample.levels());
            blocks.write(sample.looped() ? 1 : 0);
            word(blocks, sample.start());
            blocks.write(0);
        }

        int at = 268 + blocks.size();               // the map, then the blocks
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int stream = 0; stream < 20; stream++) {
            byte[] data = present.get(stream);
            if (data == null || data.length == 0) {
                header.writeBytes(new byte[12]);    // offset 0: not in the file
                continue;
            }
            longWord(header, at);
            longWord(header, 0);
            word(header, 0);
            word(header, 0);
            body.writeBytes(data);
            at += data.length;
        }
        if (header.size() != 268) {
            throw new IllegalStateException("the header is " + header.size() + " bytes");
        }
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        image.writeBytes(header.toByteArray());
        image.writeBytes(blocks.toByteArray());
        image.writeBytes(body.toByteArray());
        return image.toByteArray();
    }

    private static void word(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void longWord(ByteArrayOutputStream out, int value) {
        word(out, value >>> 16);
        word(out, value);
    }

    static String sha1(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-1 is in every JRE", e);
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException(path + ": " + e);
        }
    }

    private static List<String> join(List<String> first, List<String> second,
            List<String> third) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        all.addAll(third);
        return all;
    }
}
