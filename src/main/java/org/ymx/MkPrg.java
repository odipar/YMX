package org.ymx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A runnable TOS program around an SNDH file: a prebuilt, position-independent
 * stub in front of the same bytes {@link MkSndh} writes, with a 28-byte PRG
 * header before both and an empty relocation table behind.
 *
 * <p>No assembler runs here either - {@code ymx/mkcores.sh} assembled the
 * stub once, and this tool patches its descriptor (subtunes, the frame count
 * a play-once tune ends on, the marker flag) and concatenates. The input is
 * packed tunes, which go through {@link MkSndh} first, or a ready SNDH file.
 * {@code doc/BINARIES.md} is the byte contract.
 */
public final class MkPrg {

    /** The stub descriptor: 'YMXP' at this offset, then version, subtunes
     * and flags, words, with the frame count a long between them. */
    public static final int STUB_MAGIC = 4;
    public static final int STUB_VERSION = 8;
    public static final int STUB_TUNES = 10;
    public static final int STUB_FRAMES = 12;
    public static final int STUB_FLAGS = 16;

    /** Stub flag bit 0: drop YMXDONE.MRK on exit, for scripted runs. */
    public static final int STUB_FLAG_MARKER = 1;

    private MkPrg() {}

    public record Options(Path output, List<Path> tunes, @Nullable String title,
                          @Nullable String composer, @Nullable List<String> names,
                          boolean perf, boolean maskBurst, boolean marker) {}

    public static Path build(Options options) {
        Path output = options.output().toAbsolutePath();
        Path work = Tools.directoryOf(output).resolve(".prg_work");
        try {
            Files.createDirectories(work);
        } catch (IOException e) {
            throw Tools.fail("mkprg: cannot make " + work);
        }

        Path sndh;
        if (options.tunes().size() == 1 && options.tunes().get(0).toString()
                .toLowerCase(java.util.Locale.ROOT).endsWith(".sndh")) {
            sndh = options.tunes().get(0);
        } else {
            @Nullable String title = options.title();
            if (title == null || title.isEmpty()) {
                title = output.getFileName().toString().replaceAll("(?i)\\.prg$", "");
            }
            sndh = work.resolve("tune.sndh");
            MkSndh.build(new MkSndh.Options(sndh, options.tunes(), title,
                    options.composer(), options.names(), options.perf(),
                    options.maskBurst()));
        }

        byte[] file;
        try {
            file = Files.readAllBytes(sndh);
        } catch (IOException e) {
            throw Tools.fail("mkprg: cannot read " + sndh);
        }
        int subtunes = subtunes(file);
        byte[] prg = wrap(readStub(resolveStub()), file, subtunes,
                subtunes == 1 ? frames(file) : 0, options.marker());
        try {
            Files.write(output, prg);
        } catch (IOException e) {
            throw Tools.fail("mkprg: cannot write " + output);
        }
        System.out.println(options.output() + ": " + prg.length + " bytes, "
                + Tools.plural(subtunes, "subtune"));
        return output;
    }

    /**
     * The program: the PRG header, the stub with its descriptor patched, the
     * SNDH file, and the empty relocation table - a zero long, since nothing
     * in the stub or a position-independent SNDH needs relocating.
     */
    static byte[] wrap(byte[] stub, byte[] sndh, int subtunes, int frames,
                       boolean marker) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int text = stub.length + sndh.length;
        out.write(0x60);                          // PRG magic $601A
        out.write(0x1A);
        putLong(out, text);                       // text
        putLong(out, 0);                          // data
        putLong(out, 0);                          // bss: every buffer is in
        putLong(out, 0);                          // the stub's own bytes
        putLong(out, 0);                          // reserved
        putLong(out, 0);                          // flags
        out.write(0);                             // absflag 0: the relocation
        out.write(0);                             // table follows the text

        byte[] patched = stub.clone();
        putWord(patched, STUB_TUNES, subtunes);
        putLongIn(patched, STUB_FRAMES, frames);
        putWord(patched, STUB_FLAGS, marker ? STUB_FLAG_MARKER : 0);
        out.writeBytes(patched);
        out.writeBytes(sndh);
        putLong(out, 0);                          // no fixups
        return out.toByteArray();
    }

    /** The '##' tag's two ASCII digits. */
    static int subtunes(byte[] sndh) {
        int at = find(sndh, "##");
        return (sndh[at + 2] - '0') * 10 + (sndh[at + 3] - '0');
    }

    /** FRMS's first long: subtune 1's frame count, 0 when it plays on. */
    static int frames(byte[] sndh) {
        int at = find(sndh, "FRMS") + 4;
        return ((sndh[at] & 0xFF) << 24) | ((sndh[at + 1] & 0xFF) << 16)
                | ((sndh[at + 2] & 0xFF) << 8) | (sndh[at + 3] & 0xFF);
    }

    /** A tag's position inside the SNDH header, before 'HDNS'. */
    private static int find(byte[] sndh, String tag) {
        byte[] wanted = tag.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int at = 12; at + wanted.length < sndh.length; at++) {
            if (sndh[at] == 'H' && sndh[at + 1] == 'D' && sndh[at + 2] == 'N'
                    && sndh[at + 3] == 'S') {
                break;
            }
            boolean hit = true;
            for (int i = 0; i < wanted.length; i++) {
                if (sndh[at + i] != wanted[i]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return at;
            }
        }
        throw Tools.fail("mkprg: the SNDH header carries no " + tag + " tag");
    }

    /** The stub from dist/ - assembled on the spot, once, when missing. */
    static Path resolveStub() {
        String named = System.getProperty("ymx.stub");
        if (named != null) {
            return Path.of(named);
        }
        Path stub = Tools.repo().resolve("dist")
                .resolve("ymxprg" + Tools.binarySuffix() + ".bin");
        if (MkSndh.stale(stub, "YMX_player.S")) {
            MkCores.stub(Tools.repo().resolve("dist"));
        }
        return stub;
    }

    /** The stub, its descriptor checked. */
    static byte[] readStub(Path path) {
        byte[] stub;
        try {
            stub = Files.readAllBytes(path);
        } catch (IOException e) {
            throw Tools.fail("mkprg: cannot read the stub " + path);
        }
        if (stub.length < 18 || stub[STUB_MAGIC] != 'Y' || stub[STUB_MAGIC + 1] != 'M'
                || stub[STUB_MAGIC + 2] != 'X' || stub[STUB_MAGIC + 3] != 'P') {
            throw new IllegalArgumentException(path + " is not a PRG stub");
        }
        if (MkSndh.word(stub, STUB_VERSION) != 1) {
            throw new IllegalArgumentException(path + " is stub descriptor version "
                    + MkSndh.word(stub, STUB_VERSION) + ", this tool writes 1");
        }
        if ((stub.length & 1) != 0) {
            throw new IllegalArgumentException(path + " is odd-sized: the SNDH"
                    + " after it would load misaligned");
        }
        return stub;
    }

    private static void putWord(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >> 8);
        bytes[at + 1] = (byte) value;
    }

    private static void putLongIn(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }

    private static void putLong(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static final String USAGE =
            "usage: mkprg.sh [-m] [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile]"
            + " output.prg tunes.ymx...|set.sndh";

    public static void main(String[] args) {
        boolean marker = false;
        boolean perf = false;
        boolean maskBurst = true;
        @Nullable String title = null;
        @Nullable String composer = null;
        @Nullable List<String> names = null;
        int i = 0;
        for (; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-m")) {
                marker = true;
            } else if (a.equals("-perf")) {
                perf = true;
            } else if (a.equals("-nomask")) {
                maskBurst = false;
            } else if (a.startsWith("-t")) {
                title = a.substring(2);
            } else if (a.startsWith("-c")) {
                composer = a.substring(2);
            } else if (a.startsWith("-N")) {
                names = MkSndh.readNames(Path.of(a.substring(2)));
            } else {
                break;
            }
        }
        if (i >= args.length) {
            throw Tools.fail(USAGE);
        }

        // Both argument orders: the .prg names the output wherever it stands,
        // so `mkprg.sh song.ymx SONG.PRG` keeps working.
        Path output;
        List<Path> tunes = new ArrayList<>();
        if (args[i].toLowerCase(java.util.Locale.ROOT).endsWith(".prg")) {
            output = Path.of(args[i++]);
            for (; i < args.length; i++) {
                tunes.add(Path.of(args[i]));
            }
        } else if (args.length - i == 2) {
            tunes.add(Path.of(args[i]));
            output = Path.of(args[i + 1]);
        } else {
            throw Tools.fail(USAGE);
        }
        if (tunes.isEmpty()) {
            throw Tools.fail(USAGE);
        }
        try {
            build(new Options(output, tunes, title, composer, names, perf,
                    maskBurst, marker));
        } catch (IllegalArgumentException e) {
            throw Tools.fail("mkprg: " + e.getMessage());
        }
    }
}
