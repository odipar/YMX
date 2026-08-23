package org.ymx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Assembles the prebuilt player binaries: the SNDH cores, one per ST4 unit
 * size and flag combination, and the PRG stub. The one tool that runs rmac;
 * {@link MkSndh} and {@link MkPrg} combine the results without it, and call
 * in here when a binary under {@code dist/} is missing or stale.
 */
public final class MkCores {

    private MkCores() {}

    /** The three cores for one flag combination, named for it. */
    public static void cores(Path out, boolean perf, boolean nomask) {
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw Tools.fail("mkcores: cannot make " + out);
        }
        String suffix = (perf ? "-perf" : "") + (nomask ? "-nomask" : "");
        Path work = out.resolve(".cores_work");
        try {
            Files.createDirectories(work);
            for (int unit : new int[] {1, 2, 4}) {
                Files.writeString(work.resolve("core.S"), """
                        ST4_UNIT        equ     %d
                        YMX_PERF        equ     %d
                        YMX_MASK_BURST  equ     %d
                                include "YMX_sndh.S"
                        """.formatted(unit, perf ? 1 : 0, nomask ? 0 : 1),
                        StandardCharsets.ISO_8859_1);
                Path core = out.resolve("ymxsndh-k" + unit + suffix + ".bin");
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

    /** The PRG stub. */
    public static void stub(Path out) {
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw Tools.fail("mkcores: cannot make " + out);
        }
        Path stub = out.resolve("ymxprg.bin");
        Tools.run(Tools.repo().resolve("68k"), List.of("rmac", "-m68000",
                "-fr", "+o3", "-o", stub.toString(), "YMX_player.S"));
        System.out.println(stub + ": " + Tools.size(stub) + " bytes");
    }

    private static final String USAGE =
            "usage: mkcores.sh [-perf] [-nomask] [outdir]";

    public static void main(String[] args) {
        boolean perf = false;
        boolean nomask = false;
        int i = 0;
        for (; i < args.length; i++) {
            if (args[i].equals("-perf")) {
                perf = true;
            } else if (args[i].equals("-nomask")) {
                nomask = true;
            } else if (args[i].startsWith("-")) {
                throw Tools.fail(USAGE);
            } else {
                break;
            }
        }
        if (args.length - i > 1) {
            throw Tools.fail(USAGE);
        }
        Path out = i < args.length ? Path.of(args[i])
                : Tools.repo().resolve("dist");
        cores(out, perf, nomask);
        if (!perf && !nomask) {
            stub(out);
        }
    }
}
