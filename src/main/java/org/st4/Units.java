package org.st4;

/**
 * The input as k-byte units, one per {@code int}, big-endian as the 68000
 * loads them. Input that is not a multiple of k is padded with zeros, and
 * the padding is part of the decoder's output.
 */
public final class Units {

    private Units() {}

    /** The units, big-endian. */
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

    /** The padded length in bytes: what the decoder produces. */
    public static int paddedLength(int length, int unit) {
        return (length + unit - 1) / unit * unit;
    }
}
