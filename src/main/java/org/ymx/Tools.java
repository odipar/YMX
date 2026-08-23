package org.ymx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What the build tools need from the world outside the JVM: where the repo
 * is, and how to run a command.
 *
 * <p>The shell wrappers pass the repository root in, since a class file can
 * locate only its own jar or directory; the fallback covers running the
 * tools straight from {@code target/classes} without a wrapper.
 */
public final class Tools {

    private Tools() {}

    public static Path repo() {
        String named = System.getProperty("ymx.repo");
        if (named != null) {
            return Path.of(named);
        }
        try {
            // target/classes/org/ymx/Tools.class -> target/classes -> target -> repo
            Path classes = Path.of(Tools.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return directoryOf(directoryOf(classes));
        } catch (Exception e) {
            return Path.of("").toAbsolutePath();
        }
    }

    /** Runs a command, returning its trimmed stdout, failing loudly. */
    public static String output(Path directory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0) {
                throw fail(command.get(0) + " failed: " + out);
            }
            return out;
        } catch (IOException e) {
            throw fail("cannot run " + command.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail("interrupted while running " + command.get(0));
        }
    }

    /** Runs a command quietly, returning its exit status. */
    public static int status(Path directory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Runs a command with its output on ours, failing loudly. */
    public static void run(Path directory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .inheritIO()
                    .start();
            int status = process.waitFor();
            if (status != 0) {
                throw fail(command.get(0) + " failed (" + status + ")");
            }
        } catch (IOException e) {
            throw fail("cannot run " + command.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail("interrupted while running " + command.get(0));
        }
    }

    /**
     * The directory a path sits in. Every output here is a file inside some
     * directory, so a path with no parent is a caller's mistake, not a case
     * to carry a null through.
     */
    public static Path directoryOf(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            throw fail(path + " has no directory to build in");
        }
        return parent;
    }

    /** The size line every builder ends with. */
    public static String plural(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    public static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Prints the message and leaves, the way the shell scripts did. */
    public static RuntimeException fail(String message) {
        System.err.println(message);
        System.exit(1);
        throw new AssertionError("unreachable");
    }
}
