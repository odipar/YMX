package org.ym6;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.ymx.Tools;

/**
 * The packing step both the SNDH and the Hatari front ends need: every .ym
 * through the packer with one configuration, into one directory.
 *
 * <p>A lone tune goes through the single-file form, where the trim options
 * still mean something. A set goes through the packer's trailing-directory
 * form, which pins them all to one unit size and one workspace - the
 * shape a single player build can hold as subtunes.
 */
public final class Packing {

    private Packing() {}

    public static List<Path> pack(List<Path> yms, Path work, List<String> flags) {
        return pack(yms, work, flags, false);
    }

    public static List<Path> pack(List<Path> yms, Path work, List<String> flags,
                                  boolean fresh) {
        try {
            if (fresh && Files.exists(work)) {
                try (var walk = Files.walk(work)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            Files.createDirectories(work);
        } catch (IOException e) {
            throw Tools.fail("cannot make " + work + ": " + e.getMessage());
        }

        List<String> argv = new ArrayList<>();
        argv.add("-f");
        argv.addAll(flags);
        List<Path> packed = new ArrayList<>();
        for (Path ym : yms) {
            packed.add(work.resolve(TuneSet.stem(ym) + ".ymx"));
        }
        if (yms.size() == 1) {
            argv.add(yms.get(0).toString());
            argv.add(packed.get(0).toString());
        } else {
            for (Path ym : yms) {
                argv.add(ym.toString());
            }
            argv.add(work.toString());          // the trailing directory: a set
        }
        quietly(() -> Ymx.main(argv.toArray(new String[0])));
        return packed;
    }

    /**
     * Runs the packer with its per-stream table swallowed: eighteen lines of
     * ratios per tune is what you want when packing one tune on purpose, and
     * noise when a build script is on its way somewhere else.
     */
    private static void quietly(Runnable packer) {
        PrintStream original = System.out;
        System.setOut(new PrintStream(new LineFilter(original), true,
                StandardCharsets.ISO_8859_1));
        try {
            packer.run();
        } finally {
            System.out.flush();
            System.setOut(original);
        }
    }

    /** Drops the packer's per-stream lines - "  R0", "  E2", "  T1" - and
     * passes everything else straight through, a line at a time. */
    private static final class LineFilter extends OutputStream {

        private final PrintStream out;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineFilter(PrintStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) {
            line.write(b);
            if (b == '\n') {
                String text = line.toString(StandardCharsets.ISO_8859_1);
                line.reset();
                if (!text.matches("^ {2}[RTE]\\d.*\\R?")) {
                    out.print(text);
                }
            }
        }

        @Override
        public void flush() {
            if (line.size() > 0) {
                out.print(line.toString(StandardCharsets.ISO_8859_1));
                line.reset();
            }
            out.flush();
        }
    }

    /** Wraps the checked-exception noise of deleting a work tree. */
    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
