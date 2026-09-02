package org.ymx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Assembles the prebuilt player binaries: the SNDH cores, one per ST4 unit
 * size and flag combination, and the PRG stub. The combiners' assembler
 * step: {@link MkSndh} and {@link MkPrg} combine without one, and call in
 * here when a binary under {@code dist/} is missing or stale.
 */
public final class MkCores {

    private MkCores() {}

    /** The three cores for one flag combination, named for it. */
    public static void cores(Path out, boolean perf, boolean nomask) {
        cores(out, perf, nomask, 0);
    }

    /**
     * As above, {@code copies} building for the default ring as the
     * window: a core that decodes copies from the literal stream, and takes
     * no ring wider than that window.
     */
    public static void cores(Path out, boolean perf, boolean nomask, boolean copies) {
        cores(out, perf, nomask, copies ? YmxFormat.DEFAULT_RING_SIZE : 0);
    }

    /**
     * As above, built for {@code ring} bytes as the window, 0 for none. A
     * tune with copies plays only on a core whose window is its ring, so a
     * ring other than the default gets a core of its own, named for it; the
     * default ring's is the release's {@code -copies} core. The window
     * counts units, so a unit the ring is not a whole number of gets no
     * core, and the run says so.
     */
    public static void cores(Path out, boolean perf, boolean nomask, int ring) {
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw Tools.fail("mkcores: cannot make " + out);
        }
        String suffix = windowSuffix(ring) + (perf ? "-perf" : "")
                + (nomask ? "-nomask" : "");
        Path work = out.resolve(".cores_work");
        try {
            Files.createDirectories(work);
            for (int unit : new int[] {1, 2, 4}) {
                if (ring % unit != 0) {
                    System.out.println("ymxsndh-k" + unit + suffix + ": a ring of " + ring
                            + " bytes is not a whole number of " + unit
                            + "-byte units, so there is no core");
                    continue;
                }
                Files.writeString(work.resolve("core.S"), """
                        ST4_UNIT        equ     %d
                        ST4_WINDOW      equ     %d
                        YMX_PERF        equ     %d
                        YMX_MASK_BURST  equ     %d
                                include "YMX_sndh.S"
                        """.formatted(unit, ring / unit, perf ? 1 : 0, nomask ? 0 : 1),
                        StandardCharsets.ISO_8859_1);
                Path core = out.resolve("ymxsndh-k" + unit + suffix
                        + Tools.binarySuffix() + ".bin");
                Tools.run(work, List.of("rmac", "-m68000", "-fr", "+o3",
                        "-i" + Tools.repo().resolve("68k"),
                        "-o", core.toString(), "core.S"));
                System.out.println(core + ": " + Tools.size(core) + " bytes");
            }
            Files.deleteIfExists(work.resolve("core.S"));
            Files.deleteIfExists(work);
        } catch (IOException e) {
            throw Tools.fail("mkcores: cannot write under " + work);
        }
    }

    /**
     * The part of a core's name that says its window: nothing for none,
     * {@code -copies} for the default ring, {@code -copies-n<ring>} for
     * another. The combiners resolve a core by this name.
     */
    static String windowSuffix(int ring) {
        if (ring == 0) {
            return "";
        }
        return "-copies" + (ring == YmxFormat.DEFAULT_RING_SIZE ? "" : "-n" + ring);
    }

    /** The PRG stub. */
    public static void stub(Path out) {
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw Tools.fail("mkcores: cannot make " + out);
        }
        Path stub = out.resolve("ymxprg" + Tools.binarySuffix() + ".bin");
        Tools.run(Tools.repo().resolve("68k"), List.of("rmac", "-m68000",
                "-fr", "+o3", "-o", stub.toString(), "YMX_player.S"));
        System.out.println(stub + ": " + Tools.size(stub) + " bytes");
    }

    private static final String USAGE =
            "usage: mkcores.sh [-perf] [-nomask] [-copies [-nN]] [outdir]";

    public static void main(String[] args) {
        boolean perf = false;
        boolean nomask = false;
        boolean copies = false;
        int ring = YmxFormat.DEFAULT_RING_SIZE;
        int i = 0;
        for (; i < args.length; i++) {
            if (args[i].equals("-perf")) {
                perf = true;
            } else if (args[i].equals("-nomask")) {
                nomask = true;
            } else if (args[i].equals("-copies")) {
                copies = true;
            } else if (args[i].startsWith("-n")) {
                // The ring the copies core is built for as its window: a
                // tune with copies at that ring plays on no other core.
                try {
                    ring = Integer.parseInt(args[i].substring(2));
                } catch (NumberFormatException e) {
                    throw Tools.fail("mkcores: not a number: " + args[i].substring(2));
                }
                if (ring <= 0) {
                    throw Tools.fail("mkcores: a ring is at least one byte: " + args[i]);
                }
            } else if (args[i].startsWith("-")) {
                throw Tools.fail(USAGE);
            } else {
                break;
            }
        }
        if (args.length - i > 1 || (!copies && ring != YmxFormat.DEFAULT_RING_SIZE)) {
            throw Tools.fail(USAGE);
        }
        Path out = i < args.length ? Path.of(args[i])
                : Tools.repo().resolve("dist");
        cores(out, perf, nomask, copies ? ring : 0);
        if (!perf && !nomask && !copies) {
            stub(out);
        }
    }
}
