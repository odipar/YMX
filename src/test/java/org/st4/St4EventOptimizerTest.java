package org.st4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link St4EventOptimizer} against {@link St4FastOptimizer}: the optimum is
 * unique, so the cost arrays are equal element for element, the strongest
 * check on an optimizer that breaks ties differently. The rebuilt chain
 * decompresses back to the input and packs to the same size, give or take
 * stream padding.
 */
final class St4EventOptimizerTest {

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
    void computesTheExactSameCosts() {
        for (byte[] input : inputs()) {
            for (int unit : new int[] {1, 2, 4}) {
                for (int window : new int[] {16, 64, 1024, St4Format.maxOffsetUnits(unit)}) {
                    int[] units = Units.split(input, unit);
                    assertArrayEquals(St4FastOptimizer.costs(units, unit, window),
                            St4EventOptimizer.costs(units, unit, window),
                            input.length + " bytes, k=" + unit + ", m=" + window);
                }
            }
        }
    }

    @Test
    void itsChainsRoundTripAtTheSameSize() {
        for (byte[] input : inputs()) {
            for (int unit : new int[] {1, 2, 4}) {
                for (int window : new int[] {64, St4Format.maxOffsetUnits(unit)}) {
                    int[] units = Units.split(input, unit);
                    St4Compressor.Result packed = St4Compressor.compress(
                            St4EventOptimizer.optimize(units, unit, window, false),
                            units, unit, St4Format.MAX_OP);
                    String shape = input.length + " bytes, k=" + unit + ", m=" + window;
                    assertArrayEquals(Arrays.copyOf(input, packed.paddedSize()),
                            St4Decompressor.decompress(packed.control(), packed.literal(),
                                    packed.byteOffsets(), packed.wordOffsets(), unit,
                                    packed.paddedSize()),
                            shape);
                    St4Compressor.Result reference = St4Compressor.compress(
                            St4FastOptimizer.optimize(units, unit, window, false),
                            units, unit, St4Format.MAX_OP);
                    assertTrue(Math.abs(packed.packedSize() - reference.packedSize()) <= 4,
                            shape + ": " + packed.packedSize()
                                    + " vs " + reference.packedSize());
                }
            }
        }
    }
}
