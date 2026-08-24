package org.ymx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Combines a prebuilt SNDH core with packed tunes into an SNDH v2.2 file -
 * the canonical form of this player, which {@link MkPrg} then wraps in a
 * runnable program around the very same bytes.
 *
 * <p>No assembler runs here: the core is a position-independent binary
 * {@code ymx/mkcores.sh} assembled once per unit size and flag combination,
 * and this tool writes
 * the SNDH header itself and appends the core, the subtune table, the tunes
 * and an exactly sized workspace - {@code doc/BINARIES.md} is the byte
 * contract, and any system that follows it produces the same files. The
 * tunes become subtunes 1..N and must share the core's unit size and one
 * frame rate; ring and chunk may differ per tune, since the workspace is
 * sized to the largest.
 */
public final class MkSndh {

    /** SNDH's '##' tag is two ASCII digits. */
    public static final int MAX_SUBTUNES = 99;

    /** The core descriptor: 'YMXC' at this offset, then version, unit,
     * flags, the format version the core reads and the workspace's fixed
     * size, words; then the two offsets this tool patches, longs. */
    public static final int CORE_MAGIC = 12;
    public static final int CORE_VERSION = 16;
    public static final int CORE_UNIT = 18;
    public static final int CORE_FLAGS = 20;
    public static final int CORE_FORMAT = 22;
    public static final int CORE_WORK_FIXED = 24;
    public static final int CORE_TABLE_OFF = 26;
    public static final int CORE_WORK_OFF = 30;

    /** Core flag bits, matching {@code YMX_sndh.S}. */
    public static final int CORE_FLAG_PERF = 1;
    public static final int CORE_FLAG_NOMASK = 2;

    private MkSndh() {}

    /** What the caller requested; every field but the tunes has a default. */
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
        return build(options, resolveCore(options));
    }

    /** As above, with the core given rather than resolved from dist/. */
    public static Result build(Options options, Path corePath) {
        byte[] core = readCore(corePath, options);

        List<byte[]> tunes = new ArrayList<>();
        List<Integer> frms = new ArrayList<>();
        List<String> names = new ArrayList<>();
        @Nullable YmxHeader first = null;
        int rate = 0;
        int maxRing = 0;
        int n = 0;
        for (Path tune : options.tunes()) {
            YmxHeader header;
            try {
                header = YmxHeader.read(tune);
            } catch (IOException e) {
                throw Tools.fail("mksndh: " + e.getMessage());
            }
            if (!header.anyUnit() && header.unit() != word(core, CORE_UNIT)) {
                throw new IllegalArgumentException(tune + " is packed at unit "
                        + header.unit() + ", the core serves unit "
                        + word(core, CORE_UNIT));
            }
            String shape = YmxFormat.checkShape(header.ring(), header.chunk(),
                    header.anyUnit() ? 1 : header.unit(),
                    YmxFormat.liveStreams(header.flags()));
            if (!shape.isEmpty()) {
                throw new IllegalArgumentException(tune + ": " + shape);
            }
            if (first == null) {
                first = header;
                rate = header.hz();
            } else if (header.hz() != rate) {
                throw new IllegalArgumentException(tune + " plays at "
                        + header.hz() + " Hz, the set at " + rate
                        + " - one SNDH declares one rate");
            }
            n++;
            maxRing = Math.max(maxRing, header.ring());
            frms.add(header.frms());
            names.add(subtuneName(options, n, tune));
            try {
                tunes.add(Files.readAllBytes(tune));
            } catch (IOException e) {
                throw Tools.fail("mksndh: cannot read " + tune);
            }
        }
        if (first == null) {
            throw Tools.fail("mksndh: no tunes");
        }

        byte[] file = combine(core, tunes, tags(options, rate, n, frms, names),
                maxRing);
        Path output = options.output().toAbsolutePath();
        try {
            Files.write(output, file);
        } catch (IOException e) {
            throw Tools.fail("mksndh: cannot write " + output);
        }
        System.out.println(options.output() + ": " + file.length + " bytes, "
                + Tools.plural(n, "subtune") + ", unit " + word(core, CORE_UNIT)
                + ", workspace for rings of " + maxRing);
        return new Result(output, n, first);
    }

    /**
     * The whole file: the twelve-byte entry triple, the tag block, the core
     * with its two offsets patched, the subtune table, the tunes and the
     * workspace, every piece even-aligned.
     *
     * <p>Each outer entry is {@code bra.w} to the same entry of the core's
     * own triple, so all three displacements are the header's size minus 2.
     */
    static byte[] combine(byte[] core, List<byte[]> tunes, byte[] tags,
                          int maxRing) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int header = 12 + tags.length;
        header += header & 1;                     // the core starts even
        for (int entry = 0; entry < 3; entry++) {
            out.write(0x60);                      // bra.w
            out.write(0x00);
            out.write((header - 2) >> 8);
            out.write((header - 2) & 0xFF);
        }
        out.writeBytes(tags);
        if ((out.size() & 1) != 0) {
            out.write(0);
        }

        byte[] patched = core.clone();
        int tableOff = core.length + (core.length & 1);
        int tableSize = 2 + 4 * tunes.size();
        int at = tableOff + tableSize + (tableSize & 1);
        int[] offsets = new int[tunes.size()];
        for (int i = 0; i < tunes.size(); i++) {
            offsets[i] = at;
            at += tunes.get(i).length;
            at += at & 1;
        }
        int workOff = at;
        putLong(patched, CORE_TABLE_OFF, tableOff);
        putLong(patched, CORE_WORK_OFF, workOff);
        out.writeBytes(patched);

        pad(out, header + tableOff);
        out.write(tunes.size() >> 8);
        out.write(tunes.size() & 0xFF);
        for (int offset : offsets) {
            out.write(offset >>> 24);
            out.write(offset >>> 16);
            out.write(offset >>> 8);
            out.write(offset);
        }
        for (int i = 0; i < tunes.size(); i++) {
            pad(out, header + offsets[i]);
            out.writeBytes(tunes.get(i));
        }
        pad(out, header + workOff);
        int workspace = word(core, CORE_WORK_FIXED) + YmxFormat.STREAMS * maxRing;
        out.writeBytes(new byte[workspace]);
        return out.toByteArray();
    }

    /** Zero bytes up to a file position, one at most under these layouts. */
    private static void pad(ByteArrayOutputStream out, int to) {
        while (out.size() < to) {
            out.write(0);
        }
    }

    /**
     * The tag block, 'SNDH' through 'HDNS': the tags in the order the spec
     * requires - the '##' subtune count before any per-subtune table, and
     * the names last.
     */
    static byte[] tags(Options options, int rate, int n,
                       List<Integer> frms, List<String> names) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        text(out, "SNDH");
        tag(out, "TITL", clean(options.title()));
        if (options.composer() != null && !options.composer().isEmpty()) {
            tag(out, "COMM", clean(options.composer()));
        }
        tag(out, "CONV", "Converted from YM by YMX (ZX1 through ST4)");
        tag(out, String.format("##%02d", n), "");
        tag(out, "TC" + rate, "");
        tag(out, "FLAG", "~ady");
        if ((out.size() & 1) != 0) {
            out.write(0);
        }
        text(out, "FRMS");
        for (int frames : frms) {
            out.write(frames >>> 24);
            out.write(frames >>> 16);
            out.write(frames >>> 8);
            out.write(frames);
        }
        // The subtune names: SNDH's own track list. The offsets are words
        // relative to the tag start, and the reference parsers agree on the
        // '!#SN' spelling (the spec's own text wavers between the two).
        text(out, "!#SN");
        int strings = 4 + 2 * n;
        int[] at = new int[n];
        for (int i = 0; i < n; i++) {
            at[i] = strings;
            strings += clean(names.get(i)).length() + 1;
        }
        for (int i = 0; i < n; i++) {
            out.write(at[i] >> 8);
            out.write(at[i] & 0xFF);
        }
        for (int i = 0; i < n; i++) {
            text(out, clean(names.get(i)));
            out.write(0);
        }
        if ((out.size() & 1) != 0) {
            out.write(0);
        }
        text(out, "HDNS");
        return out.toByteArray();
    }

    /** One text tag: the four tag bytes, the value, a closing NUL. */
    private static void tag(ByteArrayOutputStream out, String name, String value) {
        text(out, name);
        text(out, value);
        out.write(0);
    }

    private static void text(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** The name file's nth line, or the tune's own stem. */
    private static String subtuneName(Options options, int n, Path tune) {
        @Nullable List<String> given = options.names();
        if (given != null && given.size() >= n) {
            return given.get(n - 1);
        }
        return tune.getFileName().toString().replaceAll("(?i)\\.ymx$", "");
    }

    /** Printable ASCII with the NUL-adjacent risks dropped: titles come out
     * of YM headers, which carry any text. */
    static String clean(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c < 0x7F) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The core for these options, from {@code dist/} beside the repo -
     * assembled on the spot, once, when it is not there yet.
     */
    static Path resolveCore(Options options) {
        String named = System.getProperty("ymx.core");
        if (named != null) {
            return Path.of(named);
        }
        int unit = unitOf(options.tunes());
        String suffix = (options.perf() ? "-perf" : "")
                + (options.maskBurst() ? "" : "-nomask");
        Path core = Tools.repo().resolve("dist").resolve("ymxsndh-k"
                + unit + suffix + Tools.binarySuffix() + ".bin");
        if (stale(core, "YMX_sndh.S", "YMX.S", "ST4_wrap.S")) {
            MkCores.cores(Tools.repo().resolve("dist"), options.perf(),
                    !options.maskBurst());
        }
        return core;
    }

    /** The unit size the set's core must serve: the first tune's, or the
     * packer's default of 2 where every tune reads the same at any unit. */
    /** Whether a prebuilt binary is missing or older than a source it was
     * assembled from, so the resolvers reassemble rather than combine
     * against the repository's past. */
    static boolean stale(Path binary, String... sources) {
        try {
            if (!Files.isRegularFile(binary)) {
                return true;
            }
            java.nio.file.attribute.FileTime built = Files.getLastModifiedTime(binary);
            for (String source : sources) {
                Path path = Tools.repo().resolve("68k").resolve(source);
                if (Files.getLastModifiedTime(path).compareTo(built) > 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static int unitOf(List<Path> tunes) {
        for (Path tune : tunes) {
            try {
                YmxHeader header = YmxHeader.read(tune);
                if (!header.anyUnit()) {
                    return header.unit();
                }
            } catch (IOException e) {
                throw Tools.fail("mksndh: " + e.getMessage());
            }
        }
        return 2;
    }

    /** The core, its descriptor checked against what the caller asked for. */
    static byte[] readCore(Path path, Options options) {
        byte[] core;
        try {
            core = Files.readAllBytes(path);
        } catch (IOException e) {
            throw Tools.fail("mksndh: cannot read the core " + path);
        }
        if (core.length < 34 || core[CORE_MAGIC] != 'Y' || core[CORE_MAGIC + 1] != 'M'
                || core[CORE_MAGIC + 2] != 'X' || core[CORE_MAGIC + 3] != 'C') {
            throw new IllegalArgumentException(path + " is not an SNDH core");
        }
        if (word(core, CORE_VERSION) != 1) {
            throw new IllegalArgumentException(path + " is core descriptor version "
                    + word(core, CORE_VERSION) + ", this tool writes 1");
        }
        if (word(core, CORE_FORMAT) != YmxFormat.VERSION) {
            throw new IllegalArgumentException(path + " reads format version "
                    + YmxFormat.versionName(word(core, CORE_FORMAT))
                    + " and the tunes carry " + YmxFormat.versionName()
                    + " - reassemble it with ymx/mkcores.sh");
        }
        int flags = (options.perf() ? CORE_FLAG_PERF : 0)
                | (options.maskBurst() ? 0 : CORE_FLAG_NOMASK);
        if (word(core, CORE_FLAGS) != flags) {
            throw new IllegalArgumentException(path + " is built with flags "
                    + word(core, CORE_FLAGS) + ", the options ask for " + flags);
        }
        return core;
    }

    static int word(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    private static void putLong(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }

    private static final String USAGE =
            "usage: mksndh.sh [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile]"
            + " [-Pcorefile] output.sndh tune1.ymx [tune2.ymx ...]";

    public static void main(String[] args) {
        @Nullable String title = null;
        @Nullable String composer = null;
        @Nullable List<String> names = null;
        @Nullable Path core = null;
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
            } else if (a.startsWith("-P")) {
                core = Path.of(a.substring(2));
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
        Options options = new Options(output, tunes, title, composer, names,
                perf, maskBurst);
        try {
            if (core != null) {
                build(options, core);
            } else {
                build(options);
            }
        } catch (IllegalArgumentException e) {
            throw Tools.fail("mksndh: " + e.getMessage());
        }
    }

    static List<String> readNames(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw Tools.fail("mksndh: cannot read names from " + file);
        }
    }
}
