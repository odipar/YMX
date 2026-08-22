package org.ymx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an SNDH v2.2 container around one or more packed tunes - the
 * canonical form of this player, which {@link MkPrg} then wraps in a runnable
 * program around the very same bytes.
 *
 * <p>The tunes become subtunes 1..N and must share one configuration: one
 * player build serves one unit size, one ring size and one chunk, so a set
 * packed in separate calls with different shapes is refused here rather than
 * crashing on an ST. The result is a raw SNDH file - position independent,
 * loadable anywhere, playable by any SNDH host.
 *
 * <p>The generated tag and tune tables are substituted into a build copy of
 * {@code YMX_sndh.S} rather than left as include files: rmac crashes on that
 * source's include shape, and on long include paths, so the assembler runs
 * inside the work directory on short names.
 */
public final class MkSndh {

    /** SNDH's '##' tag is two ASCII digits. */
    public static final int MAX_SUBTUNES = 99;

    private MkSndh() {}

    /** What the caller asked for; every field but the tunes has a default. */
    public record Options(Path output, List<Path> tunes, String title,
                          @Nullable String composer, @Nullable List<String> names,
                          boolean perf, boolean maskBurst) {

        public Options {
            if (tunes.isEmpty()) {
                throw Tools.fail("mksndh: no tunes");
            }
            if (tunes.size() > MAX_SUBTUNES) {
                throw Tools.fail("mksndh: SNDH's '##' tag caps a file at "
                        + MAX_SUBTUNES + " subtunes");
            }
        }
    }

    /** What it built, for the caller that wraps it. */
    public record Result(Path output, int subtunes, YmxHeader shape) {}

    public static Result build(Options options) {
        Path output = options.output().toAbsolutePath();
        Path work = Tools.directoryOf(output).resolve(".sndh_work");
        try {
            Files.createDirectories(work);
        } catch (IOException e) {
            throw Tools.fail("mksndh: cannot make " + work);
        }

        @Nullable YmxHeader set = null;
        List<Integer> frms = new ArrayList<>();
        List<String> names = new ArrayList<>();
        StringBuilder tunesInc = new StringBuilder();
        StringBuilder bodies = new StringBuilder();
        int n = 0;
        for (Path tune : options.tunes()) {
            if (!Files.isRegularFile(tune)) {
                throw Tools.fail("mksndh: no such file: " + tune);
            }
            YmxHeader header;
            try {
                header = YmxHeader.read(tune);
            } catch (IOException e) {
                throw Tools.fail("mksndh: " + e.getMessage());
            }
            if (set == null) {
                set = header;
            } else if (header.hz() != set.hz()) {
                throw Tools.fail("mksndh: " + tune + " plays at " + header.hz()
                        + " Hz, the set at " + set.hz()
                        + " - one SNDH declares one rate");
            } else if (header.ring() != set.ring() || header.chunk() != set.chunk()
                    || header.unit() != set.unit()) {
                throw Tools.fail("mksndh: " + tune + " is packed " + header.shape()
                        + ", the set started " + set.shape() + " - one player build"
                        + " needs one configuration (pack the set in one ymx call)");
            }
            n++;
            frms.add(header.frms());
            names.add(subtuneName(options, n, tune));
            try {
                Files.copy(tune, work.resolve("tune" + n + ".ymx"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw Tools.fail("mksndh: cannot stage " + tune);
            }
            tunesInc.append("        dc.l    sndh_tune").append(n)
                    .append("-sndh_start\n");
            bodies.append("sndh_tune").append(n).append(":\n")
                    .append("        incbin  \"tune").append(n).append(".ymx\"\n")
                    .append("        even\n");
        }
        tunesInc.append(bodies);

        if (set == null) {
            throw Tools.fail("mksndh: no tunes");
        }
        String tagsInc = tags(options, set, n, frms, names);
        Path build = work.resolve("sndh_build.S");
        substitute(Tools.ymxDir().resolve("YMX_sndh.S"), build, tagsInc, tunesInc.toString());
        Tools.assemble(work, "sndh_build.S", output,
                List.of("-fr", "-i" + Tools.ymxDir(), "-i" + Tools.repo().resolve("68k")));

        System.out.println(options.output() + ": " + Tools.size(output) + " bytes, "
                + Tools.plural(n, "subtune") + ", " + set.shape());
        return new Result(output, n, set);
    }

    /** The name file's nth line, or the tune's own stem. */
    private static String subtuneName(Options options, int n, Path tune) {
        @Nullable List<String> given = options.names();
        if (given != null && given.size() >= n) {
            return given.get(n - 1);
        }
        return tune.getFileName().toString().replaceAll("(?i)\\.ymx$", "");
    }

    /**
     * The tag block: the player's build-time equates, then the SNDH tags in
     * the order the spec asks for them - the '##' subtune count before any
     * per-subtune table, and the names last.
     */
    private static String tags(Options options, YmxHeader set, int n,
                               List<Integer> frms, List<String> names) {
        StringBuilder out = new StringBuilder();
        out.append("ST4_UNIT    equ     ").append(set.unit()).append('\n');
        out.append("RING_SIZE   equ     ").append(set.ring()).append('\n');
        out.append("YMX_TUNES   equ     ").append(n).append('\n');
        out.append("YMX_PERF    equ     ").append(options.perf() ? 1 : 0).append('\n');
        out.append("YMX_MASK_BURST equ  ").append(options.maskBurst() ? 1 : 0)
           .append('\n');
        out.append("        dc.b    'TITL',\"").append(clean(options.title())).append("\",0\n");
        if (options.composer() != null && !options.composer().isEmpty()) {
            out.append("        dc.b    'COMM',\"").append(clean(options.composer()))
               .append("\",0\n");
        }
        out.append("        dc.b    'CONV','Converted from YM by YMX (ZX1 through ST4)',0\n");
        out.append(String.format("        dc.b    '##%02d',0%n", n));
        out.append("        dc.b    'TC").append(set.hz()).append("',0\n");
        out.append("        dc.b    'FLAG','~','ady',0\n");
        out.append("        even\n");
        out.append("        dc.b    'FRMS'\n");
        StringBuilder list = new StringBuilder();
        for (int frames : frms) {
            list.append(list.isEmpty() ? "" : ",").append(frames);
        }
        out.append("        dc.l    ").append(list).append('\n');
        // The subtune names: SNDH's own track list. The offsets are words
        // relative to the tag start, and the reference parsers agree on the
        // '!#SN' spelling (the spec's own text wavers between the two).
        out.append("        even\n");
        out.append("sndh_sn:\n");
        out.append("        dc.b    '!#SN'\n");
        for (int i = 1; i <= n; i++) {
            out.append("        dc.w    sndh_sn").append(i).append("-sndh_sn\n");
        }
        for (int i = 1; i <= n; i++) {
            out.append("sndh_sn").append(i).append(":\n")
               .append("        dc.b    \"").append(clean(names.get(i - 1))).append("\",0\n");
        }
        return out.toString();
    }

    /**
     * What rmac can hold inside a double-quoted string: printable ASCII with
     * the quote itself dropped. Titles come out of YM headers, which carry
     * anything at all - one of them broke the assembler with an apostrophe.
     */
    static String clean(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c < 0x7F && c != '"') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Copies the SNDH source, replacing its two include lines with the
     * generated blocks - see the class comment for why. */
    private static void substitute(Path source, Path target, String tags, String tunes) {
        try {
            List<String> lines = Files.readAllLines(source, StandardCharsets.ISO_8859_1);
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                if (line.contains("include \"sndh_tags.inc\"")) {
                    out.append(tags);
                } else if (line.contains("include \"sndh_tunes.inc\"")) {
                    out.append(tunes);
                } else {
                    out.append(line).append('\n');
                }
            }
            Files.writeString(target, out.toString(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw Tools.fail("mksndh: cannot build from " + source + ": " + e.getMessage());
        }
    }

    private static final String USAGE =
            "usage: mksndh.sh [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile]"
            + " output.sndh tune1.ymx [tune2.ymx ...]";

    public static void main(String[] args) {
        @Nullable String title = null;
        @Nullable String composer = null;
        @Nullable List<String> names = null;
        boolean perf = false;
        boolean maskBurst = true;
        int i = 0;
        for (; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-perf")) {
                perf = true;
            } else if (a.equals("-nomask")) {
                maskBurst = false;
            } else if (a.startsWith("-t")) {
                title = a.substring(2);
            } else if (a.startsWith("-c")) {
                composer = a.substring(2);
            } else if (a.startsWith("-N")) {
                names = readNames(Path.of(a.substring(2)));
            } else {
                break;
            }
        }
        if (args.length - i < 2) {
            throw Tools.fail(USAGE);
        }
        Path output = Path.of(args[i++]);
        List<Path> tunes = new ArrayList<>();
        for (; i < args.length; i++) {
            tunes.add(Path.of(args[i]));
        }
        if (title == null || title.isEmpty()) {
            title = output.getFileName().toString().replaceAll("(?i)\\.sndh$", "");
        }
        build(new Options(output, tunes, title, composer, names, perf, maskBurst));
    }

    static List<String> readNames(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw Tools.fail("mksndh: cannot read names from " + file);
        }
    }
}
