package org.ymx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Stages a release of the prebuilt player binaries: every core variant -
 * three unit sizes by the perf and mask flags - plus the PRG stub, each
 * assembled by {@link MkCores}, verified against the descriptors
 * {@link MkSndh} and {@link MkPrg} read, and listed in a manifest with its
 * SHA-256. {@code -publish} creates or updates the GitHub release tagged
 * {@code binaries-v<release version>}, replacing its assets and posting
 * this release's section of {@code doc/RELEASES.md} as the notes: a new
 * format version is a new release, so is a patch of one, and an unchanged
 * release updates in place.
 */
public final class MkRelease {

    /** One core build: a unit size, the two assembly flags, and whether
     * it carries the copy code. */
    record Variant(int unit, boolean perf, boolean nomask, boolean copies) {

        String name() {
            return "ymxsndh-k" + unit + (copies ? "-copies" : "")
                    + (perf ? "-perf" : "") + (nomask ? "-nomask" : "")
                    + Tools.binarySuffix() + ".bin";
        }

        int flags() {
            return (perf ? MkSndh.CORE_FLAG_PERF : 0)
                    | (nomask ? MkSndh.CORE_FLAG_NOMASK : 0)
                    | (copies ? MkSndh.CORE_FLAG_COPIES : 0);
        }
    }

    /** Every core the release carries. */
    static List<Variant> matrix() {
        List<Variant> variants = new ArrayList<>();
        for (int unit : new int[] {1, 2, 4}) {
            for (int flags = 0; flags < 8; flags++) {
                variants.add(new Variant(unit, (flags & 1) != 0, (flags & 2) != 0,
                        (flags & 4) != 0));
            }
        }
        return variants;
    }

    private MkRelease() {}

    private static final String USAGE =
            "usage: mkrelease.sh [-publish] [stagedir]\n"
            + "       mkrelease.sh -notes [release]";

    public static void main(String[] args) {
        boolean publish = false;
        boolean notesOnly = false;
        int i = 0;
        for (; i < args.length; i++) {
            if (args[i].equals("-publish")) {
                publish = true;
            } else if (args[i].equals("-notes")) {
                notesOnly = true;
            } else if (args[i].startsWith("-")) {
                throw Tools.fail(USAGE);
            } else {
                break;
            }
        }
        if (args.length - i > 1) {
            throw Tools.fail(USAGE);
        }
        if (notesOnly) {
            if (publish) {
                throw Tools.fail(USAGE);
            }
            // A release names itself, so an older page can be rewritten from
            // its own section without staging anything.
            repostNotes(i < args.length ? args[i] : YmxFormat.releaseName());
            return;
        }
        Path dir = (i < args.length ? Path.of(args[i])
                : Tools.repo().resolve("dist").resolve("release")).toAbsolutePath();
        String commit = Tools.output(Tools.repo(),
                List.of("git", "rev-parse", "--short", "HEAD"));
        try {
            if (Files.isDirectory(dir)) {
                try (var stale = Files.list(dir)) {
                    for (Path old : stale.toList()) {
                        Files.delete(old);
                    }
                }
            }
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw Tools.fail("mkrelease: cannot clear " + dir);
        }

        for (int flags = 0; flags < 8; flags++) {
            MkCores.cores(dir, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
        }
        MkCores.stub(dir);
        carryStandalone(dir);

        StringBuilder manifest = new StringBuilder();
        manifest.append("YMX player binaries - release ")
                .append(YmxFormat.releaseName()).append(", format version ")
                .append(YmxFormat.versionName()).append(", descriptor version ")
                .append(MkSndh.CORE_DESCRIPTOR_VERSION).append('\n');
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
            byte[] stub = MkPrg.readStub(
                    dir.resolve("ymxprg" + Tools.binarySuffix() + ".bin"));
            verifyStub(stub);
            manifest.append(entry("ymxprg" + Tools.binarySuffix() + ".bin",
                    stub, "-  -"));
            for (Path zip : standalone(dir)) {
                manifest.append(entry(zip.getFileName().toString(),
                        Files.readAllBytes(zip), "-  -"));
            }
            Files.writeString(dir.resolve("MANIFEST.txt"), manifest.toString(),
                    StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw Tools.fail("mkrelease: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw Tools.fail("mkrelease: " + e.getMessage());
        }
        Path zip = binariesZip(dir);
        int tools = standalone(dir).size();
        System.out.println(dir + ": " + (matrix().size() + 1) + " binaries in "
                + zip.getFileName() + ", " + tools
                + (tools == 1 ? " standalone tool" : " standalone tools")
                + " and MANIFEST.txt, release " + YmxFormat.releaseName());

        if (publish) {
            try {
                publish(dir, commit);
            } catch (IllegalArgumentException e) {
                String message = e.getMessage();
                throw Tools.fail(message == null ? e.toString() : message);
            }
        }
    }

    /** The GitHub release tagged by the release version, its assets
     * replaced and its notes rewritten, so the commit they name is the
     * one the assets were assembled at and the account of what changed
     * is this release's section of doc/RELEASES.md. A release created
     * here is tagged at the staged commit; a tag that exists stays where
     * it is, so a run whose HEAD has moved past it stops instead of
     * posting notes naming a commit the tag does not reach. */
    private static void publish(Path dir, String commit) {
        String tag = tag();
        String head = Tools.output(Tools.repo(),
                List.of("git", "rev-parse", "HEAD"));
        String notes = notes(commit);
        if (Tools.status(Tools.repo(),
                List.of("gh", "release", "view", tag)) != 0) {
            Tools.run(Tools.repo(), createCommand(tag, head, notes));
        } else {
            String tagged = Tools.output(Tools.repo(), List.of("gh", "api",
                    "repos/{owner}/{repo}/commits/" + tag, "--jq", ".sha"));
            if (!tagged.equals(head)) {
                throw new IllegalArgumentException("mkrelease: " + tag
                        + " is tagged at " + shortSha(tagged) + " and this run"
                        + " staged " + commit + " - stage from "
                        + shortSha(tagged) + ", or delete the release and its"
                        + " tag and publish again");
            }
            Tools.run(Tools.repo(), editCommand(tag, notes));
        }
        Tools.run(Tools.repo(), uploadCommand(dir, tag));
        System.out.println("published " + tag + " at " + commit);
    }

    /**
     * The page's text: this release's section of doc/RELEASES.md, then the
     * line naming the commit the binaries were assembled at. Both the
     * publish and the notes-only path read it here, so the page reads the
     * same however it was written.
     */
    static String notes(String commit) {
        return notes(YmxFormat.releaseName(), commit, true);
    }

    /**
     * A release's page, out of its own section: the notes reflowed, and one
     * line saying where the binaries came from.
     *
     * <p>{@code carriesZip} is a fact about the release rather than about
     * this build. Releases before 0.8.2 published their binaries as loose
     * files, so naming a zip on one of those pages would point a reader at
     * an asset that is not there.</p>
     */
    static String notes(String release, String commit, boolean carriesZip) {
        return reflow(notesFor(release))
                + "\n\nPrebuilt SNDH cores and the PRG stub, assembled at "
                + commit
                + (carriesZip ? ", in ymx-binaries-v" + release + ".zip" : "")
                + ". doc/BINARIES.md is the combine contract; MANIFEST.txt"
                + " lists sizes and SHA-256 digests.";
    }

    /**
     * The section as a release page wants it: one line to a paragraph, one
     * to a list item. doc/RELEASES.md wraps at the width the repository
     * holds to, and the page renders every one of those breaks, so a wrap
     * that reads straight in the file reads ragged there. Table rows and
     * fenced blocks keep the lines they were written with.
     */
    static String reflow(String section) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();
        boolean fenced = false;
        for (String raw : section.split("\n", -1)) {
            String text = raw.strip();
            boolean fence = text.startsWith("```");
            if (fenced || fence || text.startsWith("|")) {
                if (line.length() > 0) {
                    out.append(line).append('\n');
                    line.setLength(0);
                }
                out.append(raw).append('\n');
                if (fence) {
                    fenced = !fenced;
                }
                continue;
            }
            if (text.isEmpty()) {
                if (line.length() > 0) {
                    out.append(line).append('\n');
                    line.setLength(0);
                }
                out.append('\n');
                continue;
            }
            if (text.startsWith("- ") || text.startsWith("#")) {
                if (line.length() > 0) {
                    out.append(line).append('\n');
                    line.setLength(0);
                }
            } else if (line.length() > 0) {
                line.append(' ');
            }
            line.append(text);
        }
        if (line.length() > 0) {
            out.append(line);
        }
        return out.toString().strip();
    }

    /**
     * Rewrite a published release's notes, and nothing else: no asset is
     * replaced and the tag stays where it is. The commit the notes name is
     * the tag's own rather than HEAD, so the page keeps saying where its
     * binaries came from however far main has moved since - a reworded
     * section is the page changing, not the release.
     */
    private static void repostNotes(String release) {
        String tag = "binaries-v" + release;
        if (Tools.status(Tools.repo(),
                List.of("gh", "release", "view", tag)) != 0) {
            throw Tools.fail("mkrelease: there is no release " + tag
                    + " to write notes for - publish it first");
        }
        String tagged = Tools.output(Tools.repo(), List.of("gh", "api",
                "repos/{owner}/{repo}/commits/" + tag, "--jq", ".sha"));
        boolean carriesZip = !Tools.output(Tools.repo(), List.of("gh",
                "release", "view", tag, "--json", "assets", "--jq",
                ".assets[].name | select(. == \"ymx-binaries-v" + release
                        + ".zip\")")).isBlank();
        Tools.run(Tools.repo(), editCommand(tag,
                notes(release, shortSha(tagged), carriesZip)));
        System.out.println("notes rewritten for " + tag + " at "
                + shortSha(tagged));
    }

    /** The command that creates the release: {@code --target} tags the
     * commit whose bytes are staged, rather than the default branch's
     * head. The whole SHA goes in, not the short form the notes carry. */
    static List<String> createCommand(String tag, String head, String notes) {
        return List.of("gh", "release", "create", tag, "--target", head,
                "--title", "YMX player binaries " + YmxFormat.releaseName()
                        + ", format " + YmxFormat.versionName(),
                "--notes", notes);
    }

    /** The command that rewrites an existing release's notes. It moves no
     * tag, which is why the caller reads the tag's commit first. */
    static List<String> editCommand(String tag, String notes) {
        return List.of("gh", "release", "edit", tag, "--notes", notes);
    }

    /** The command that replaces every asset: the binaries zip, each
     * standalone zip and the manifest, out of the staging directory. The
     * manifest goes up on its own as well as inside the zip, so its digests
     * can be read off the release page without downloading anything. */
    static List<String> uploadCommand(Path dir, String tag) {
        List<String> upload = new ArrayList<>(List.of("gh", "release", "upload",
                tag, "--clobber"));
        upload.add(dir.resolve(binariesZipName()).toString());
        for (Path zip : standalone(dir)) {
            upload.add(zip.toString());
        }
        upload.add(dir.resolve("MANIFEST.txt").toString());
        return upload;
    }

    /**
     * The standalone {@code ym-to-ymx} zips this release carries, one per
     * platform: the executable and its launcher, for a machine with no SDK.
     * {@code ymx/publish.sh} builds them into {@code dist/standalone}, and
     * staging copies in each one whose name carries this release's suffix.
     * A release staged without them carries none and publishes anyway - a
     * combiner reads the cores, not the tool.
     */
    private static void carryStandalone(Path dir) {
        Path built = Tools.repo().resolve("dist").resolve("standalone");
        if (!Files.isDirectory(built)) {
            return;
        }
        try (java.util.stream.Stream<Path> listing = Files.list(built)) {
            for (Path zip : listing.toList()) {
                if (matches(zip)) {
                    Files.copy(zip, dir.resolve(zip.getFileName()));
                }
            }
        } catch (IOException e) {
            throw Tools.fail("mkrelease: cannot carry " + built + ": "
                    + e.getMessage());
        }
    }

    /**
     * The timestamp every entry in the binaries zip carries: the earliest a
     * zip can record. A zip stamped with the clock differs on every run, and
     * two releases built from one commit would not compare.
     */
    private static final LocalDateTime ZIP_TIME =
            LocalDateTime.of(1980, 1, 1, 0, 0, 0);

    /** The binaries zip's name, which carries the release suffix. */
    static String binariesZipName() {
        return "ymx-binaries" + Tools.binarySuffix() + ".zip";
    }

    /**
     * Every file a combiner needs, in one zip: the twelve cores, the stub and
     * MANIFEST.txt. A combiner fetches one file and has the whole set, and
     * the manifest travels beside the binaries it lists rather than being
     * fetched separately to check them.
     *
     * <p>Entries are stored rather than deflated and stamped
     * {@link #ZIP_TIME}, so staging one commit twice writes the same zip
     * both times. The two trees frame their containers differently, so the
     * zips they write are not the same file; a stored entry carries the
     * bytes it was given, so what a combiner unpacks is the same from
     * either, and MANIFEST.txt lists those bytes rather than the zip.</p>
     *
     * @return the zip, in the staging directory
     */
    static Path binariesZip(Path dir) {
        List<Path> carried = new ArrayList<>();
        for (Variant variant : matrix()) {
            carried.add(dir.resolve(variant.name()));
        }
        carried.add(dir.resolve("ymxprg" + Tools.binarySuffix() + ".bin"));
        carried.add(dir.resolve("MANIFEST.txt"));

        Path zip = dir.resolve(binariesZipName());
        try (ZipOutputStream out =
                new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path file : carried) {
                byte[] bytes = Files.readAllBytes(file);
                CRC32 crc = new CRC32();
                crc.update(bytes);
                ZipEntry entry = new ZipEntry(file.getFileName().toString());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
                entry.setTimeLocal(ZIP_TIME);
                out.putNextEntry(entry);
                out.write(bytes);
                out.closeEntry();
            }
        } catch (IOException e) {
            throw Tools.fail("mkrelease: cannot write " + zip + ": "
                    + e.getMessage());
        }
        return zip;
    }

    /** The zips staging left in the directory, in name order. */
    static List<Path> standalone(Path dir) {
        List<Path> zips = new ArrayList<>();
        try (java.util.stream.Stream<Path> listing = Files.list(dir)) {
            listing.filter(MkRelease::matches).sorted().forEach(zips::add);
        } catch (IOException e) {
            return List.of();
        }
        return zips;
    }

    /** Whether a file is a standalone tool zip for this release. */
    private static boolean matches(Path zip) {
        String name = zip.getFileName().toString();
        return name.startsWith("ym-to-ymx-")
                && name.endsWith(Tools.binarySuffix() + ".zip");
    }

    /** A commit as the notes spell it: the first seven characters of
     * the SHA, or all of a shorter string. */
    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    /** The tag this release publishes under: the release version, the
     * format version and the patch together. */
    static String tag() {
        return "binaries-v" + YmxFormat.releaseName();
    }

    /** This release's section of {@code doc/RELEASES.md}, from its
     * heading to the next: the account the release page carries. A
     * release with no section of its own is not published - this throws
     * rather than leaving, so a test can call it. */
    static String releaseNotes() {
        return notesFor(YmxFormat.releaseName());
    }

    /** One release's section, by name. */
    static String notesFor(String release) {
        String heading = "## " + release;
        String document;
        try {
            document = Files.readString(Tools.repo().resolve("doc")
                    .resolve("RELEASES.md"));
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "mkrelease: doc/RELEASES.md: " + e.getMessage());
        }
        int start = document.indexOf(heading + "\n");
        if (start < 0) {
            throw new IllegalArgumentException(
                    "mkrelease: doc/RELEASES.md carries no \"" + heading
                    + "\" section - write what this release changes before"
                    + " publishing it");
        }
        int next = document.indexOf("\n## ", start + heading.length());
        return document.substring(start + heading.length() + 1,
                next < 0 ? document.length() : next).strip();
    }

    /** One manifest line: name, size, digest, and the given tail columns. */
    private static String entry(String name, byte[] bytes, String tail) {
        return name + "  " + bytes.length + "  " + sha256(bytes) + "  " + tail + '\n';
    }

    /** The descriptor checks {@link MkSndh} performs at combine time, run
     * once at release time against the variant each file is named for. */
    static void verifyCore(byte[] core, Variant variant) {
        if (core.length < 34 || core[MkSndh.CORE_MAGIC] != 'Y'
                || core[MkSndh.CORE_MAGIC + 1] != 'M'
                || core[MkSndh.CORE_MAGIC + 2] != 'X'
                || core[MkSndh.CORE_MAGIC + 3] != 'C') {
            throw new IllegalArgumentException(variant.name() + " is not an SNDH core");
        }
        if (MkSndh.word(core, MkSndh.CORE_VERSION) != MkSndh.CORE_DESCRIPTOR_VERSION) {
            throw new IllegalArgumentException(variant.name()
                    + " carries descriptor version "
                    + MkSndh.word(core, MkSndh.CORE_VERSION) + ", not "
                    + MkSndh.CORE_DESCRIPTOR_VERSION);
        }
        if (MkSndh.word(core, MkSndh.CORE_FORMAT) != YmxFormat.VERSION) {
            throw new IllegalArgumentException(variant.name() + " reads format version "
                    + YmxFormat.versionName(MkSndh.word(core, MkSndh.CORE_FORMAT))
                    + ", this release reads " + YmxFormat.versionName());
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
        int fixed = MkSndh.word(core, MkSndh.CORE_WORK_FIXED);
        if (fixed == 0 || (fixed & 1) != 0) {
            throw new IllegalArgumentException(variant.name() + " gives F = "
                    + fixed + ", the workspace bytes before the rings:"
                    + " nonzero and even");
        }
        if (!zeroLong(core, MkSndh.CORE_TABLE_OFF)) {
            throw new IllegalArgumentException(variant.name() + " carries a"
                    + " table offset; a combiner patches that long, so a"
                    + " released core carries 0");
        }
        if (!zeroLong(core, MkSndh.CORE_WORK_OFF)) {
            throw new IllegalArgumentException(variant.name() + " carries a"
                    + " workspace offset; a combiner patches that long, so a"
                    + " released core carries 0");
        }
        if ((core.length & 1) != 0) {
            throw new IllegalArgumentException(variant.name() + " is odd-sized");
        }
    }

    /** The descriptor checks {@link MkPrg} performs at wrap time, run once
     * at release time, with two of the fields a combiner patches read
     * back: a released stub carries the frame count and the flags as the
     * assembler left them. */
    static void verifyStub(byte[] stub) {
        String name = "ymxprg" + Tools.binarySuffix() + ".bin";
        if (stub.length < 18 || stub[MkPrg.STUB_MAGIC] != 'Y'
                || stub[MkPrg.STUB_MAGIC + 1] != 'M'
                || stub[MkPrg.STUB_MAGIC + 2] != 'X'
                || stub[MkPrg.STUB_MAGIC + 3] != 'P') {
            throw new IllegalArgumentException(name + " is not a PRG stub");
        }
        if (MkSndh.word(stub, MkPrg.STUB_VERSION) != 2) {
            throw new IllegalArgumentException(name
                    + " carries stub descriptor version "
                    + MkSndh.word(stub, MkPrg.STUB_VERSION) + ", not 2");
        }
        if (!zeroLong(stub, MkPrg.STUB_FRAMES)) {
            throw new IllegalArgumentException(name + " carries a frame count;"
                    + " a combiner patches that long, so a released stub"
                    + " carries 0");
        }
        if (MkSndh.word(stub, MkPrg.STUB_FLAGS) != 0) {
            throw new IllegalArgumentException(name + " carries flags "
                    + MkSndh.word(stub, MkPrg.STUB_FLAGS) + "; a combiner"
                    + " patches that word, so a released stub carries 0");
        }
        if ((stub.length & 1) != 0) {
            throw new IllegalArgumentException(name + " is odd-sized");
        }
    }

    /** Whether the long at an offset is zero - the value the assembler
     * writes where a combiner patches. */
    private static boolean zeroLong(byte[] bytes, int at) {
        return MkSndh.word(bytes, at) == 0 && MkSndh.word(bytes, at + 2) == 0;
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
