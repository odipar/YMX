package org.ym6;

/**
 * Unpacks the LHA archives that distributed {@code .ym} files come wrapped
 * in, entirely in memory.
 *
 * <p>Ported from the ST-Sound library's LZH depacker by Arnaud Carré, which
 * is based on original LZH code by Haruhiko Okumura (1991) and Kerwin F.
 * Medina (1996). It reads a level-0 header - the kind every YM archive in
 * the wild carries - verifies its checksum, and inflates the {@code -lh5-}
 * method; {@code -lh0-} members are stored uncompressed and copied out.
 *
 * <p>YM archives hold exactly one member, so this returns the first member's
 * data and ignores anything after it.
 */
final class Lha {

    private static final int BUFSIZE = 4096;
    private static final int BITBUFSIZ = 16;
    private static final int DICBIT = 13;               // -lh5-
    private static final int DICSIZ = 1 << DICBIT;
    private static final int MAXMATCH = 256;
    private static final int THRESHOLD = 3;
    private static final int NC = 255 + MAXMATCH + 2 - THRESHOLD;
    private static final int CBIT = 9;
    private static final int NP = DICBIT + 1;
    private static final int NT = 16 + 3;
    private static final int PBIT = 4;
    private static final int TBIT = 5;
    private static final int NPT = Math.max(NT, NP);

    private final byte[] source;
    private int sourceAt;
    private int sourceLeft;

    private int bitbuf;
    private int subbitbuf;
    private int bitcount;
    private int pending;
    private int pendingAt;
    private final byte[] window = new byte[BUFSIZE];

    private final int[] left = new int[2 * NC - 1];
    private final int[] right = new int[2 * NC - 1];
    private final byte[] cLen = new byte[NC];
    private final byte[] ptLen = new byte[NPT];
    private int blocksize;
    private final int[] cTable = new int[4096];
    private final int[] ptTable = new int[256];

    private int matchLeft;
    private int matchAt;

    private Lha(byte[] source, int from, int size) {
        this.source = source;
        this.sourceAt = from;
        this.sourceLeft = size;
    }

    /** Whether this is an LHA archive: any {@code -lh?-} method at offset 2. */
    static boolean isArchive(byte[] data) {
        return data.length >= 22 && (data[0] & 0xFF) != 0
                && data[2] == '-' && data[3] == 'l' && data[4] == 'h'
                && data[6] == '-';
    }

    /**
     * Unpacks the archive's first member.
     *
     * @throws IllegalArgumentException with a printable reason when the
     *     header is damaged or the method is one no YM archive uses
     */
    static byte[] unpack(byte[] archive) {
        if (!isArchive(archive)) {
            throw new IllegalArgumentException("not an LHA archive");
        }
        int headerSize = archive[0] & 0xFF;
        int dataAt = headerSize + 2;
        if (dataAt > archive.length) {
            throw new IllegalArgumentException("LHA header extends beyond the file");
        }
        int level = archive[20] & 0xFF;
        if (level != 0) {
            throw new IllegalArgumentException("LHA header level " + level
                    + "; YM archives use level 0");
        }
        int sum = 0;
        for (int i = 2; i < dataAt; i++) {
            sum += archive[i] & 0xFF;
        }
        if ((sum & 0xFF) != (archive[1] & 0xFF)) {
            throw new IllegalArgumentException("LHA header checksum mismatch");
        }

        int compressedSize = little32(archive, 7);
        int originalSize = little32(archive, 11);
        if (compressedSize < 0 || compressedSize > archive.length - dataAt) {
            throw new IllegalArgumentException("LHA member is truncated");
        }
        if (originalSize < 0) {
            throw new IllegalArgumentException("LHA member claims a negative size");
        }

        String method = new String(archive, 2, 5,
                java.nio.charset.StandardCharsets.US_ASCII);
        if (method.equals("-lh0-")) {               // stored, not compressed
            byte[] data = new byte[originalSize];
            System.arraycopy(archive, dataAt, data, 0, originalSize);
            return data;
        }
        if (!method.equals("-lh5-")) {
            throw new IllegalArgumentException("unsupported LHA method " + method);
        }
        return new Lha(archive, dataAt, compressedSize).inflate(originalSize);
    }

    private static int little32(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
    }

    // ------------------------------------------------------------- inflate

    private byte[] inflate(int originalSize) {
        byte[] result = new byte[originalSize];
        byte[] slice = new byte[DICSIZ];
        pending = 0;
        initGetbits();
        blocksize = 0;
        matchLeft = 0;

        int at = 0;
        while (at < originalSize) {
            int n = Math.min(originalSize - at, DICSIZ);
            decode(n, slice);
            System.arraycopy(slice, 0, result, at, n);
            at += n;
        }
        return result;
    }

    // ------------------------------------------------------------- bit I/O

    private void fillbuf(int n) {
        bitbuf = (bitbuf << n) & 0xFFFF;
        while (n > bitcount) {
            bitbuf |= (subbitbuf << (n -= bitcount)) & 0xFFFF;
            if (pending == 0) {
                pendingAt = 0;
                pending = Math.min(BUFSIZE - 32, sourceLeft);
                if (pending > 0) {
                    System.arraycopy(source, sourceAt, window, 0, pending);
                    sourceAt += pending;
                    sourceLeft -= pending;
                }
            }
            if (pending > 0) {
                pending--;
                subbitbuf = window[pendingAt++] & 0xFF;
            } else {
                subbitbuf = 0;              // ran dry: the sizes bound the read
            }
            bitcount = 8;
        }
        bitbuf |= (subbitbuf >> (bitcount -= n)) & 0xFFFF;
        bitbuf &= 0xFFFF;
    }

    private int getbits(int n) {
        int bits = (bitbuf >> (BITBUFSIZ - n)) & 0xFFFF;
        fillbuf(n);
        return bits;
    }

    private void initGetbits() {
        bitbuf = 0;
        subbitbuf = 0;
        bitcount = 0;
        fillbuf(BITBUFSIZ);
    }

    // ------------------------------------------------- Huffman table build

    private void makeTable(int nchar, byte[] bitlen, int tablebits, int[] table) {
        int[] count = new int[17];
        int[] weight = new int[17];
        int[] start = new int[18];

        for (int i = 0; i < nchar; i++) {
            count[bitlen[i] & 0xFF]++;
        }
        start[1] = 0;
        for (int i = 1; i <= 16; i++) {
            start[i + 1] = start[i] + (count[i] << (16 - i));
        }

        int jutbits = 16 - tablebits;
        for (int i = 1; i <= tablebits; i++) {
            start[i] >>= jutbits;
            weight[i] = 1 << (tablebits - i);
        }
        for (int i = tablebits + 1; i <= 16; i++) {
            weight[i] = 1 << (16 - i);
        }

        int at = (start[tablebits + 1] >> jutbits) & 0xFFFF;
        int end = 1 << tablebits;
        while (at < end) {
            table[at++] = 0;
        }

        int avail = nchar;
        int mask = 1 << (15 - tablebits);
        for (int ch = 0; ch < nchar; ch++) {
            int len = bitlen[ch] & 0xFF;
            if (len == 0) {
                continue;
            }
            int nextcode = start[len] + weight[len];
            if (len <= tablebits) {
                for (int i = start[len]; i < nextcode; i++) {
                    table[i] = ch;
                }
            } else {
                // The code is longer than the table indexes: the tail bits
                // walk a tree spliced into left[]/right[]. Which array a node
                // lives in is part of the walk, so track it explicitly.
                int code = start[len];
                int array = 0;                      // 0 table, 1 left, 2 right
                int index = code >> jutbits;
                for (int bits = len - tablebits; bits != 0; bits--) {
                    int node = array == 0 ? table[index]
                            : array == 1 ? left[index] : right[index];
                    if (node == 0) {
                        left[avail] = 0;
                        right[avail] = 0;
                        node = avail++;
                        if (array == 0) {
                            table[index] = node;
                        } else if (array == 1) {
                            left[index] = node;
                        } else {
                            right[index] = node;
                        }
                    }
                    array = (code & mask) != 0 ? 2 : 1;
                    index = node;
                    code <<= 1;
                }
                if (array == 0) {
                    table[index] = ch;
                } else if (array == 1) {
                    left[index] = ch;
                } else {
                    right[index] = ch;
                }
            }
            start[len] = nextcode;
        }
    }

    // --------------------------------------------------- Huffman decoding

    private void readPtLen(int nn, int nbit, int special) {
        int n = getbits(nbit);
        if (n == 0) {
            int c = getbits(nbit);
            for (int i = 0; i < nn; i++) {
                ptLen[i] = 0;
            }
            for (int i = 0; i < 256; i++) {
                ptTable[i] = c;
            }
        } else {
            int i = 0;
            while (i < n) {
                int c = bitbuf >> (BITBUFSIZ - 3);
                if (c == 7) {
                    int mask = 1 << (BITBUFSIZ - 4);
                    while ((mask & bitbuf) != 0) {
                        mask >>= 1;
                        c++;
                    }
                }
                fillbuf(c < 7 ? 3 : c - 3);
                ptLen[i++] = (byte) c;
                if (i == special) {
                    int skip = getbits(2);
                    while (--skip >= 0) {
                        ptLen[i++] = 0;
                    }
                }
            }
            while (i < nn) {
                ptLen[i++] = 0;
            }
            makeTable(nn, ptLen, 8, ptTable);
        }
    }

    private void readCLen() {
        int n = getbits(CBIT);
        if (n == 0) {
            int c = getbits(CBIT);
            for (int i = 0; i < NC; i++) {
                cLen[i] = 0;
            }
            for (int i = 0; i < 4096; i++) {
                cTable[i] = c;
            }
        } else {
            int i = 0;
            while (i < n) {
                int c = ptTable[(bitbuf >> (BITBUFSIZ - 8)) & 0xFF];
                if (c >= NT) {
                    int mask = 1 << (BITBUFSIZ - 9);
                    do {
                        c = (bitbuf & mask) != 0 ? right[c] : left[c];
                        mask >>= 1;
                    } while (c >= NT);
                }
                fillbuf(ptLen[c] & 0xFF);
                if (c <= 2) {
                    if (c == 0) {
                        c = 1;
                    } else if (c == 1) {
                        c = getbits(4) + 3;
                    } else {
                        c = getbits(CBIT) + 20;
                    }
                    while (--c >= 0) {
                        cLen[i++] = 0;
                    }
                } else {
                    cLen[i++] = (byte) (c - 2);
                }
            }
            while (i < NC) {
                cLen[i++] = 0;
            }
            makeTable(NC, cLen, 12, cTable);
        }
    }

    private int decodeC() {
        if (blocksize == 0) {
            blocksize = getbits(16);
            readPtLen(NT, TBIT, 3);
            readCLen();
            readPtLen(NP, PBIT, -1);
        }
        blocksize--;
        int j = cTable[(bitbuf >> (BITBUFSIZ - 12)) & 0xFFF];
        if (j >= NC) {
            int mask = 1 << (BITBUFSIZ - 13);
            do {
                j = (bitbuf & mask) != 0 ? right[j] : left[j];
                mask >>= 1;
            } while (j >= NC);
        }
        fillbuf(cLen[j] & 0xFF);
        return j;
    }

    private int decodeP() {
        int j = ptTable[(bitbuf >> (BITBUFSIZ - 8)) & 0xFF];
        if (j >= NP) {
            int mask = 1 << (BITBUFSIZ - 9);
            do {
                j = (bitbuf & mask) != 0 ? right[j] : left[j];
                mask >>= 1;
            } while (j >= NP);
        }
        fillbuf(ptLen[j] & 0xFF);
        if (j != 0) {
            j = (1 << (j - 1)) + getbits(j - 1);
        }
        return j;
    }

    /** One dictionary-sized slice of output; matches may carry over. */
    private void decode(int count, byte[] buffer) {
        int at = 0;
        while (--matchLeft >= 0) {
            buffer[at] = buffer[matchAt];
            matchAt = (matchAt + 1) & (DICSIZ - 1);
            if (++at == count) {
                return;
            }
        }
        for (;;) {
            int c = decodeC();
            if (c <= 255) {
                buffer[at] = (byte) c;
                if (++at == count) {
                    return;
                }
            } else {
                matchLeft = c - (255 + 1 - THRESHOLD);
                matchAt = (at - decodeP() - 1) & (DICSIZ - 1);
                while (--matchLeft >= 0) {
                    buffer[at] = buffer[matchAt];
                    matchAt = (matchAt + 1) & (DICSIZ - 1);
                    if (++at == count) {
                        return;
                    }
                }
            }
        }
    }
}
