package org.st4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Command-line ST4 packer. {@code -k1} is ZX1's unit size and packs to within
 * a few percent of jx1, which a test holds; {@code -k2} and {@code -k4} trade
 * ratio for a decoder that runs half or a quarter as many operations.
 */
public final class St4 {

    private St4() {}

    public static void main(String[] args) {
        System.out.println("ST4: aligned split-stream packer v7.0 by Robbert van Dalen, "
                + "based on ZX1 v1.5 by Einar Saukas");

        int unit = 1;
        int offsetLimit = St4Format.MAX_OFFSET;
        int maxOpLength = St4Format.MAX_OP;
        int repeatIndex = -1;
        boolean copies = false;
        double search = 0;
        boolean forcedMode = false;
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            switch (args[i]) {
                case "-f" -> forcedMode = true;
                case "-c" -> copies = true;
                default -> {
                    if (args[i].startsWith("-c")) {
                        copies = true;
                        search = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-k")) {
                        unit = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-m")) {
                        offsetLimit = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-l")) {
                        maxOpLength = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-r")) {
                        repeatIndex = parseIndex(args[i].substring(2));
                    } else {
                        throw error("Invalid parameter " + args[i]);
                    }
                }
            }
        }

        String outputName;
        if (args.length == i + 1) {
            outputName = args[i] + ".st4";
        } else if (args.length == i + 2) {
            outputName = args[i + 1];
        } else {
            usage("""
                    Usage: st4 [-f] [-c[S]] [-kK] [-mN] [-lN] [-rR] input [output.st4]
                      -f      Force overwrite of output file
                      -c      Let a match beyond the -m window copy from the
                              literal stream; needs a decoder built with copies
                      -cS     The same, searching for S seconds for a better parse
                      -kK     Unit size: 1, 2 or 4 bytes (default 1). Lengths and
                              offsets count units, so the output is padded to a
                              whole number of them
                      -mN     Limit back-references to N units
                      -lN     Split matches so no operation exceeds N units
                      -rR     Loop: after the last unit, the output continues
                              from unit R, forever""");
            return;
        }

        String problem = St4Format.checkUnit(unit);
        if (!problem.isEmpty()) {
            throw error(problem);
        }
        // A word offset is stored scaled to bytes, so the window is a byte
        // figure: 32512 units at k=4 would not fit the word.
        if (offsetLimit > St4Format.maxOffsetUnits(unit)) {
            offsetLimit = St4Format.maxOffsetUnits(unit);
        }

        byte[] input;
        try {
            input = Files.readAllBytes(Path.of(args[i]));
        } catch (IOException e) {
            throw error("Cannot access input file " + args[i]);
        }
        if (input.length == 0) {
            throw error("Empty input file " + args[i]);
        }

        Path outputPath = Path.of(outputName);
        if (!forcedMode && Files.exists(outputPath)) {
            throw error("Already existing output file " + outputName);
        }

        int[] units = Units.split(input, unit);
        if (repeatIndex >= units.length) {
            throw error("-r" + repeatIndex + " is not a unit of the input, which is "
                    + units.length + " units");
        }
        St4Compressor.Result result;
        int window = offsetLimit;
        if (repeatIndex >= 0 && units.length - repeatIndex > offsetLimit) {
            // The loop is longer than the window, so no match reaches across
            // it and the caller replays the stream from the state it saved at
            // the loop point. The loop is parsed on its own, so every pass
            // sees the same history.
            int[] intro = Arrays.copyOfRange(units, 0, repeatIndex);
            int[] loop = Arrays.copyOfRange(units, repeatIndex, units.length);
            result = St4Compressor.compressRewinding(
                    intro.length == 0 ? null
                            : parse(intro, unit, offsetLimit, maxOpLength, copies, search),
                    parse(loop, unit, offsetLimit, maxOpLength, copies, search),
                    units, unit, maxOpLength, repeatIndex, window);
        } else {
            // The loop fits the window: the end is an endless match back to
            // the loop point.
            result = St4Compressor.compress(
                    parse(units, unit, offsetLimit, maxOpLength, copies, search), units,
                    unit, maxOpLength, repeatIndex, window);
        }

        try {
            Files.write(outputPath, container(result));
        } catch (IOException e) {
            throw error("Cannot write output file " + outputName);
        }

        int padded = Units.paddedLength(input.length, unit);
        System.out.printf(Locale.ROOT, "Packed %d bytes%s into %d (%.1f%%): A %d, B %d, C %d, D %d, "
                + "%d operations%s%n",
                input.length, padded == input.length ? "" : " padded to " + padded,
                result.packedSize(), 100.0 * result.packedSize() / input.length,
                result.control().length, result.literal().length,
                result.byteOffsets().length, result.wordOffsets().length,
                result.operations(),
                (result.copies() == 0 ? "" : ", " + result.copies()
                        + " copies from the literal stream")
                        + (repeatIndex < 0 ? "" : ", loops from unit " + repeatIndex
                        + (result.rewindIndex() < 0 ? "" : " by rewind")));
        if (result.rewindIndex() >= 0) {
            System.out.printf(Locale.ROOT, "The loop is longer than the -m%d window, so the decoder cannot "
                    + "loop it alone: save its state at unit %d and restore it at unit %d, "
                    + "every pass%n", offsetLimit, repeatIndex, units.length);
        }
        if (result.longestOp() > maxOpLength) {
            System.out.printf(Locale.ROOT, "Warning: longest operation is %d units, over the -l%d limit: "
                    + "a literal run, which the format cannot split%n",
                    result.longestOp(), maxOpLength);
        }
    }

    /**
     * The parse: the event-driven optimizer, or with {@code -c} the opening
     * passes of the search that copies from the literal stream, and with
     * seconds the search from there.
     */
    private static St4Block parse(int[] units, int unit, int window, int maxOpLength,
                                  boolean copies, double seconds) {
        if (!copies) {
            return St4EventOptimizer.optimize(units, unit, window);
        }
        return St4LiteralCopySearch.optimize(units, unit, window, maxOpLength, seconds,
                seconds > 0);
    }

    /**
     * Twenty-eight bytes of header, then A, B, C and D, each on a long
     * boundary. No length is stored: a stream runs to the next, and the last
     * to whatever the caller loads after the container. Public because other
     * formats embed containers, many at once.
     */
    public static byte[] container(St4Compressor.Result result) {
        int controlAt = St4Format.HEADER_SIZE;                  // already a multiple of 4
        int literalAt = align(controlAt + result.control().length);
        int byteAt = align(literalAt + result.literal().length);
        int wordAt = align(byteAt + result.byteOffsets().length);
        byte[] file = new byte[wordAt + result.wordOffsets().length];

        putLong(file, St4Format.OFFSET_SIGNATURE, St4Format.signature(result.unit()));
        putLong(file, St4Format.OFFSET_SIZE, result.paddedSize());
        putLong(file, St4Format.OFFSET_LITERAL, literalAt);
        putLong(file, St4Format.OFFSET_BYTE_OFFSETS, byteAt);
        putLong(file, St4Format.OFFSET_WORD_OFFSETS, wordAt);
        putLong(file, St4Format.OFFSET_REWIND, result.rewindIndex() < 0
                ? St4Format.NO_REWIND : result.rewindIndex() * result.unit());
        putLong(file, St4Format.OFFSET_WINDOW, result.window());
        System.arraycopy(result.control(), 0, file, controlAt, result.control().length);
        System.arraycopy(result.literal(), 0, file, literalAt, result.literal().length);
        System.arraycopy(result.byteOffsets(), 0, file, byteAt,
                result.byteOffsets().length);
        System.arraycopy(result.wordOffsets(), 0, file, wordAt,
                result.wordOffsets().length);
        return file;
    }

    private static int align(int at) {
        return at + ((-at) & 3);
    }

    private static void putWord(byte[] file, int at, int value) {
        file[at] = (byte) (value >>> 8);
        file[at + 1] = (byte) value;
    }

    private static void putLong(byte[] file, int at, int value) {
        putWord(file, at, value >>> 16);
        putWord(file, at + 2, value);
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

    /** As {@link #parseNumber}, but an index may be zero: -r0 loops it all. */
    private static int parseIndex(String argument) {
        try {
            int value = Integer.parseInt(argument);
            if (value < 0) {
                throw error("Invalid parameter value " + argument);
            }
            return value;
        } catch (NumberFormatException e) {
            throw error("Invalid parameter value " + argument);
        }
    }
}
