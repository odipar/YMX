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

    /** Packs for a caller who reads the per-stream table. */
    public static List<Path> pack(List<Path> yms, Path work, List<String> flags) {
        return pack(yms, work, flags, false, false);
    }

    /** Packs on the way to something else, with the table dropped. */
    public static List<Path> pack(List<Path> yms, Path work, List<String> flags,
                                  boolean fresh) {
        return pack(yms, work, flags, fresh, true);
    }

    public static List<Path> pack(List<Path> yms, Path work, List<String> flags,
                                  boolean fresh, boolean quiet) {
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
        String[] packerArgs = argv.toArray(new String[0]);
        if (quiet) {
            quietly(() -> Ymx.main(packerArgs));
        } else {
            Ymx.main(packerArgs);
        }
        return packed;
    }

    /**
     * Runs the packer with its per-stream table dropped. The table is one line
     * of ratios per stream per tune, which a build script on its way to an
     * SNDH file does not need in its log.
     */
    static void quietly(Runnable packer) {
        PrintStream original = System.out;
        LineFilter filter = new LineFilter(original);
        // Autoflush off. With it on, a PrintStream flushes its sink after
        // every byte array it writes and tests for no newline first, and
        // printf writes one piece of the format at a time - so a table line
        // reached the filter as fourteen fragments, each of them out before a
        // whole line was there to match. The finally drains the filter before
        // the restore, and the progress meter flushes for itself.
        System.setOut(new PrintStream(filter, false,
                StandardCharsets.ISO_8859_1));
        try {
            packer.run();
        } finally {
            System.out.flush();
            filter.drain();
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
                if (!text.matches("^ {2}(?:[REAP]\\d+|[MXT]) +.*\\R?")) {
                    out.print(text);
                }
            }
        }

        /**
         * A flush can land mid-line: the progress meter draws with carriage
         * returns and flushes for itself. Text that cannot grow into a table
         * line goes straight out; the rest is held until its newline, so no
         * flush carries half a table line past the match.
         */
        @Override
        public void flush() {
            String text = line.toString(StandardCharsets.ISO_8859_1);
            if (!text.isEmpty() && !opensTableLine(text)) {
                out.print(text);
                line.reset();
            }
            out.flush();
        }

        /** Whether text could still grow into a line this drops. */
        private static boolean opensTableLine(String text) {
            return text.length() < 2 ? "  ".startsWith(text)
                    : text.startsWith("  ");
        }

        /** Writes what is left, newline or not: the last of the output. */
        void drain() {
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
