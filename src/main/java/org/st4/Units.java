package org.st4;

/**
 * The input as an array of k-byte units.
 *
 * <p>A unit is at most four bytes, so it fits an {@code int} and comparisons
 * are plain integer comparisons - which is what makes the optimal parser as
 * cheap at k = 4 as the byte parser is at k = 1, over a quarter as many
 * positions.
 *
 * <p>Input that is not a multiple of k is padded with zeros. The padding is
 * part of the output the decoder produces, so the packer records the padded
 * length; a caller that cares about the original size keeps it elsewhere.
 */
public final class Units {

    private Units() {}

    /** Big-endian, so a unit reads the way the 68000 would load it. */
    public static int[] split(byte[] data, int unit) {
        int count = (data.length + unit - 1) / unit;
        int[] units = new int[count];
        for (int index = 0; index < count; index++) {
            int value = 0;
            for (int byteIndex = 0; byteIndex < unit; byteIndex++) {
                int at = index * unit + byteIndex;
                value = (value << 8) | (at < data.length ? data[at] & 0xFF : 0);
            }
            units[index] = value;
        }
        return units;
    }

    /** Writes one unit's bytes, most significant first. */
    public static void write(byte[] target, int at, int value, int unit) {
        for (int byteIndex = unit - 1; byteIndex >= 0; byteIndex--) {
            target[at + byteIndex] = (byte) value;
            value >>>= 8;
        }
    }

    /** The padded length in bytes: what the decoder will produce. */
    public static int paddedLength(int length, int unit) {
        return (length + unit - 1) / unit * unit;
    }
}
