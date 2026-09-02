package org.st4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Command-line ST4 unpacker, and the reference the 68000 decoders are checked
 * against. The output is the padded data, a whole number of k-byte units, as
 * the format stores it: at {@code -k1} the input, at {@code -k2} or
 * {@code -k4} up to k-1 bytes longer. For a stream that loops, {@code -rN}
 * writes the pass and then N-1 repeats of its loop section.
 */
public final class Dst4 {

    private Dst4() {}

    public static void main(String[] args) {
        System.out.println("DST4: aligned split-stream unpacker v7.0 by Robbert van Dalen, "
                + "based on ZX1 v1.5 by Einar Saukas");

        boolean forcedMode = false;
        int times = 1;
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            if (args[i].equals("-f")) {
                forcedMode = true;
            } else if (args[i].startsWith("-r")) {
                times = parseNumber(args[i].substring(2));
            } else {
                throw error("Invalid parameter " + args[i]);
            }
        }

        String inputName;
        String outputName;
        if (args.length == i + 1) {
            inputName = args[i];
            if (inputName.length() > 4 && inputName.endsWith(".st4")) {
                outputName = inputName.substring(0, inputName.length() - 4);
            } else {
                throw error("Cannot infer output filename");
            }
        } else if (args.length == i + 2) {
            inputName = args[i];
            outputName = args[i + 1];
        } else {
            usage("""
                    Usage: dst4 [-f] [-rN] input.st4 [output]
                      -f      Force overwrite of output file
                      -rN     Play a looping stream's loop N times: the whole pass, then
                              N-1 repeats of its loop section (default 1, the pass)
                    The output is padded to a whole number of units, as the format stores it.""");
            return;
        }

        byte[] file;
        try {
            file = Files.readAllBytes(Path.of(inputName));
        } catch (IOException e) {
            throw error("Cannot access input file " + inputName);
        }

        Path outputPath = Path.of(outputName);
        if (!forcedMode && Files.exists(outputPath)) {
            throw error("Already existing output file " + outputName);
        }

        St4Format.Container container;
        try {
            container = St4Format.read(file);
        } catch (IllegalArgumentException e) {
            throw error(e.getMessage() + ": " + inputName);
        }

        St4Decompressor.Decoded decoded;
        byte[] output;
        try {
            // One whole pass; -r asks for more of it.
            decoded = St4Decompressor.decode(container.control(), container.literal(),
                    container.byteOffsets(), container.wordOffsets(), container.unit(),
                    container.size(), container.window(), container.rewind());
            output = played(container, decoded, times);
        } catch (AssertionError | IndexOutOfBoundsException | IllegalStateException e) {
            // A malformed stream trips an assertion under -ea; report it.
            throw error("Corrupted or truncated ST4 data in " + inputName
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            throw error(e.getMessage() + ": " + inputName);
        }

        try {
            Files.write(outputPath, output);
        } catch (IOException e) {
            throw error("Cannot write output file " + outputName);
        }

        System.out.printf(Locale.ROOT, "File decompressed from %d to %d bytes, k=%d%s%s%s!%n",
                file.length, output.length, container.unit(),
                container.unit() == 1 ? "" : " (a whole number of units)",
                decoded.repeatIndex() >= 0 ? ", looping from unit " + decoded.repeatIndex()
                        : container.rewind() < 0 ? ""
                        : ", looping from unit " + container.rewind() / container.unit()
                                + " by rewind",
                times == 1 ? "" : ", played " + times + " times");
    }

    /**
     * The pass and then {@code times - 1} repeats of its loop section, as a
     * decoder driven past the end produces. A stream that loops by itself is
     * decoded again to that length; a stream that loops by rewind repeats
     * the pass's loop section, since every pass sees the same history.
     *
     * @throws IllegalArgumentException when the stream does not loop and more
     *     than one pass is asked for
     */
    static byte[] played(St4Format.Container container, St4Decompressor.Decoded pass,
                         int times) {
        byte[] output = pass.output();
        if (times == 1) {
            return output;
        }
        int unit = container.unit();
        if (pass.repeatIndex() >= 0) {
            int loop = output.length - pass.repeatIndex() * unit;
            return St4Decompressor.decode(container.control(), container.literal(),
                    container.byteOffsets(), container.wordOffsets(), unit,
                    output.length + (times - 1) * loop, container.window(), container.rewind())
                    .output();
        }
        if (container.rewind() < 0) {
            throw new IllegalArgumentException("The stream does not loop, so -r" + times
                    + " has nothing to repeat");
        }
        int loop = output.length - container.rewind();
        byte[] result = java.util.Arrays.copyOf(output, output.length + (times - 1) * loop);
        for (int at = output.length; at < result.length; at += loop) {
            System.arraycopy(output, container.rewind(), result, at, loop);
        }
        return result;
    }

    private static int parseNumber(String argument) {
        try {
            int value = Integer.parseInt(argument);
            if (value <= 0) {
                throw error("Invalid parameter value " + argument);
            }
            return value;
        } catch (NumberFormatException e) {
            throw error("Invalid parameter value " + argument);
        }
    }

    private static RuntimeException error(String message) {
        System.err.println("Error: " + message);
        System.exit(1);
        throw new AssertionError("unreachable");
    }

    private static void usage(String text) {
        System.err.println(text);
        System.exit(1);
    }
}
