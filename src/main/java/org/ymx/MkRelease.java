package org.ymx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stages a release of the prebuilt player binaries: every core variant -
 * three unit sizes by the perf and mask flags - plus the PRG stub, each
 * assembled by {@code ymx/mkcores.sh}, verified against the descriptors
 * {@link MkSndh} and {@link MkPrg} read, and listed in a manifest with its
 * SHA-256. {@code ymx/mkrelease.sh} wraps this and publishes the staged
 * directory as a GitHub release.
 */
public final class MkRelease {

    /** One core build: a unit size and the two assembly flags. */
    record Variant(int unit, boolean perf, boolean nomask) {

        String name() {
            return "ymxsndh-k" + unit + (perf ? "-perf" : "")
                    + (nomask ? "-nomask" : "") + ".bin";
        }

        int flags() {
            return (perf ? MkSndh.CORE_FLAG_PERF : 0)
                    | (nomask ? MkSndh.CORE_FLAG_NOMASK : 0);
        }
    }

    /** Every core the release carries. */
    static List<Variant> matrix() {
        List<Variant> variants = new ArrayList<>();
        for (int unit : new int[] {1, 2, 4}) {
            for (int flags = 0; flags < 4; flags++) {
                variants.add(new Variant(unit, (flags & 1) != 0, (flags & 2) != 0));
            }
        }
        return variants;
    }

    private MkRelease() {}

    /** usage: MkRelease stagedir [source-commit] */
    public static void main(String[] args) {
        if (args.length < 1) {
            throw Tools.fail("usage: MkRelease stagedir [source-commit]");
        }
        Path dir = Path.of(args[0]).toAbsolutePath();
        String commit = args.length > 1 ? args[1] : "unrecorded";
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw Tools.fail("mkrelease: cannot make " + dir);
        }

        String script = Tools.repo().resolve("ymx").resolve("mkcores.sh").toString();
        for (int flags = 0; flags < 4; flags++) {
            List<String> command = new ArrayList<>(List.of("sh", script));
            if ((flags & 1) != 0) {
                command.add("-perf");
            }
            if ((flags & 2) != 0) {
                command.add("-nomask");
            }
            command.add(dir.toString());
            Tools.run(Tools.repo(), command);
        }

        StringBuilder manifest = new StringBuilder();
        manifest.append("YMX player binaries - format version ")
                .append(YmxFormat.VERSION).append(", descriptor version 1\n");
        manifest.append("source commit ").append(commit).append('\n');
        manifest.append("doc/BINARIES.md is the combine contract\n\n");
        manifest.append("name  bytes  sha256  unit  flags\n");
        try {
            for (Variant variant : matrix()) {
                byte[] core = Files.readAllBytes(dir.resolve(variant.name()));
                verifyCore(core, variant);
                manifest.append(entry(variant.name(), core,
                        variant.unit() + "  " + variant.flags()));
            }
            byte[] stub = MkPrg.readStub(dir.resolve("ymxprg.bin"));
            manifest.append(entry("ymxprg.bin", stub, "-  -"));
            Files.writeString(dir.resolve("MANIFEST.txt"), manifest.toString(),
                    StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw Tools.fail("mkrelease: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw Tools.fail("mkrelease: " + e.getMessage());
        }
        System.out.println(dir + ": " + (matrix().size() + 1)
                + " binaries and MANIFEST.txt, format version " + YmxFormat.VERSION);
    }

    /** One manifest line: name, size, digest, and the given tail columns. */
    private static String entry(String name, byte[] bytes, String tail) {
        return name + "  " + bytes.length + "  " + sha256(bytes) + "  " + tail + '\n';
    }

    /** The descriptor checks {@link MkSndh} performs at combine time, run
     * once at release time against the variant each file is named for. */
    static void verifyCore(byte[] core, Variant variant) {
        if (core.length < 34 || core[MkSndh.CORE_MAGIC] != 'Y'
                || core[MkSndh.CORE_MAGIC + 3] != 'C') {
            throw new IllegalArgumentException(variant.name() + " is not an SNDH core");
        }
        if (MkSndh.word(core, MkSndh.CORE_VERSION) != 1) {
            throw new IllegalArgumentException(variant.name()
                    + " carries descriptor version "
                    + MkSndh.word(core, MkSndh.CORE_VERSION) + ", not 1");
        }
        if (MkSndh.word(core, MkSndh.CORE_FORMAT) != YmxFormat.VERSION) {
            throw new IllegalArgumentException(variant.name() + " reads format version "
                    + MkSndh.word(core, MkSndh.CORE_FORMAT) + ", the release is "
                    + YmxFormat.VERSION);
        }
        if (MkSndh.word(core, MkSndh.CORE_UNIT) != variant.unit()) {
            throw new IllegalArgumentException(variant.name() + " serves unit "
                    + MkSndh.word(core, MkSndh.CORE_UNIT) + ", its name says "
                    + variant.unit());
        }
        if (MkSndh.word(core, MkSndh.CORE_FLAGS) != variant.flags()) {
            throw new IllegalArgumentException(variant.name() + " carries flags "
                    + MkSndh.word(core, MkSndh.CORE_FLAGS) + ", its name says "
                    + variant.flags());
        }
        if ((core.length & 1) != 0) {
            throw new IllegalArgumentException(variant.name() + " is odd-sized");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is in every JRE", e);
        }
    }
}
