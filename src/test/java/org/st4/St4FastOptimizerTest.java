package org.st4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Random;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link St4FastOptimizer} against {@link St4Optimizer}: the whole point of the
 * fast one is that it finds the SAME parse, so all four streams must match byte
 * for byte on every input shape, unit size and window - including the shapes
 * that once broke it: lone matches before an offset's first state, degenerate
 * runs, and inputs of a byte or two.
 */
final class St4FastOptimizerTest {

    private static List<byte[]> inputs() {
        byte[] random = new byte[4096];
        new Random(7).nextBytes(random);
        byte[] sparse = new byte[4096];
        var r = new Random(11);
        for (int i = 0; i < sparse.length; i++) {
            sparse[i] = (byte) (r.nextInt(4) * 17 + i % 3);
        }
        byte[] allSame = new byte[3000];
        Arrays.fill(allSame, (byte) 'A');
        byte[] period = new byte[4096];
        for (int i = 0; i < period.length; i++) {
            period[i] = (byte) (i % 3);
        }
        // A lone early match pair far apart, then dense matches: exercises
        // offsets whose first state appears long after their first match.
        byte[] lone = new byte[2048];
        r = new Random(3);
        for (int i = 0; i < lone.length; i++) {
            lone[i] = (byte) r.nextInt(256);
        }
        System.arraycopy(lone, 0, lone, 1500, 300);
        return List.of(new byte[] {42}, new byte[] {1, 2, 3}, new byte[] {7, 7},
                random, sparse, allSame, period, lone,
                "abracadabra hocus pocus ".repeat(40).getBytes(
                        java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void findsTheExactSameParse() {
        for (byte[] input : inputs()) {
            for (int unit : new int[] {1, 2, 4}) {
                for (int window : new int[] {16, 64, 1024, St4Format.maxOffsetUnits(unit)}) {
                    int[] units = Units.split(input, unit);
                    St4Compressor.Result reference = St4Compressor.compress(
                            St4Optimizer.optimize(units, unit, window, false),
                            units, unit, St4Format.MAX_OP);
                    St4Compressor.Result fast = St4Compressor.compress(
                            St4FastOptimizer.optimize(units, unit, window, false),
                            units, unit, St4Format.MAX_OP);
                    String shape = input.length + " bytes, k=" + unit + ", m=" + window;
                    assertArrayEquals(reference.control(), fast.control(), shape);
                    assertArrayEquals(reference.literal(), fast.literal(), shape);
                    assertArrayEquals(reference.byteOffsets(), fast.byteOffsets(), shape);
                    assertArrayEquals(reference.wordOffsets(), fast.wordOffsets(), shape);
                    assertEquals(reference.paddedSize(), fast.paddedSize(), shape);
                }
            }
        }
    }
}
