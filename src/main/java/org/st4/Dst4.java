package org.st4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line ST4 unpacker: the counterpart to {@link St4}, and the readable
 * reference the 68000 decoders are checked against.
 *
 * <p>What comes out is the <em>padded</em> data - a whole number of k-byte
 * units - because that is what the format stores and what the 68000 decoders
 * write. At {@code -k1} that is the input exactly; at {@code -k2} or
 * {@code -k4} it can be up to k-1 bytes longer, and this tool says so.
 */
public final class Dst4 {

    private Dst4() {}

    public static void main(String[] args) {
        System.out.println("DST4: aligned split-stream unpacker v4.0 by Robbert van Dalen, "
                + "based on ZX1 v1.5 by Einar Saukas");

        boolean forcedMode = false;
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            if (args[i].equals("-f")) {
                forcedMode = true;
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
                    Usage: dst4 [-f] input.st4 [output]
                      -f      Force overwrite of output file
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

        byte[] output;
        try {
            output = St4Decompressor.decompress(container.control(), container.literal(),
                    container.byteOffsets(), container.wordOffsets(), container.unit(),
                    container.size());
        } catch (AssertionError | IllegalStateException | IndexOutOfBoundsException e) {
            // The decoder reports a stream that reaches past the offset
            // limit, runs out of literals or writes past the output; with -ea
            // the rest of the malformed streams trip a descriptive assertion.
            // Report rather than continue on corrupt data.
            throw error("Corrupted or truncated ST4 data in " + inputName
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }

        try {
            Files.write(outputPath, output);
        } catch (IOException e) {
            throw error("Cannot write output file " + outputName);
        }

        System.out.printf("File decompressed from %d to %d bytes, k=%d%s!%n",
                file.length, output.length, container.unit(),
                container.unit() == 1 ? "" : " (a whole number of units)");
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
