using System;
using System.Text;

namespace Ym6
{
    /// <summary>
    /// Unpacks the LHA archives distributed .ym files come wrapped in,
    /// entirely in memory - ported from org.ym6.Lha, itself from the ST-Sound
    /// library's LZH depacker by Arnaud Carré, based on LZH code by Haruhiko
    /// Okumura (1991) and Kerwin F. Medina (1996). Level-0 headers, -lh5-
    /// inflated and -lh0- copied out; the first member is the answer.
    /// </summary>
    internal sealed class Lha
    {
        private const int BufSize = 4096;
        private const int BitBufSize = 16;
        private const int DicBit = 13;              // -lh5-
        private const int DicSize = 1 << DicBit;
        private const int MaxMatch = 256;
        private const int Threshold = 3;
        private const int Nc = 255 + MaxMatch + 2 - Threshold;
        private const int CBit = 9;
        private const int Np = DicBit + 1;
        private const int Nt = 16 + 3;
        private const int PBit = 4;
        private const int TBit = 5;
        private const int Npt = Nt > Np ? Nt : Np;

        private readonly byte[] source;
        private int sourceAt;
        private int sourceLeft;

        private int bitbuf;
        private int subbitbuf;
        private int bitcount;
        private int pending;
        private int pendingAt;
        private readonly byte[] window = new byte[BufSize];

        private readonly int[] left = new int[2 * Nc - 1];
        private readonly int[] right = new int[2 * Nc - 1];
        private readonly byte[] cLen = new byte[Nc];
        private readonly byte[] ptLen = new byte[Npt];
        private int blocksize;
        private readonly int[] cTable = new int[4096];
        private readonly int[] ptTable = new int[256];

        private int matchLeft;
        private int matchAt;

        private Lha(byte[] source, int from, int size)
        {
            this.source = source;
            sourceAt = from;
            sourceLeft = size;
        }

        /// <summary>Whether this is an LHA archive: any -lh?- method at
        /// offset 2.</summary>
        internal static bool IsArchive(byte[] data)
        {
            return data.Length >= 22 && data[0] != 0
                    && data[2] == '-' && data[3] == 'l' && data[4] == 'h'
                    && data[6] == '-';
        }

        /// <summary>Unpacks the archive's first member.</summary>
        internal static byte[] Unpack(byte[] archive)
        {
            if (!IsArchive(archive))
            {
                throw new ArgumentException("not an LHA archive");
            }
            int headerSize = archive[0];
            int dataAt = headerSize + 2;
            if (dataAt > archive.Length)
            {
                throw new ArgumentException("LHA header extends beyond the file");
            }
            int level = archive[20];
            if (level != 0)
            {
                throw new ArgumentException("LHA header level " + level
                        + "; YM archives use level 0");
            }
            int sum = 0;
            for (int i = 2; i < dataAt; i++)
            {
                sum += archive[i];
            }
            if ((sum & 0xFF) != archive[1])
            {
                throw new ArgumentException("LHA header checksum mismatch");
            }

            int compressedSize = Little32(archive, 7);
            int originalSize = Little32(archive, 11);
            if (compressedSize < 0 || compressedSize > archive.Length - dataAt)
            {
                throw new ArgumentException("LHA member is truncated");
            }
            if (originalSize < 0)
            {
                throw new ArgumentException("LHA member claims a negative size");
            }

            string method = Encoding.ASCII.GetString(archive, 2, 5);
            if (method == "-lh0-")
            {                                       // stored, not compressed
                byte[] data = new byte[originalSize];
                Array.Copy(archive, dataAt, data, 0, originalSize);
                return data;
            }
            if (method != "-lh5-")
            {
                throw new ArgumentException("unsupported LHA method " + method);
            }
            return new Lha(archive, dataAt, compressedSize).Inflate(originalSize);
        }

        private static int Little32(byte[] data, int at)
        {
            return data[at] | (data[at + 1] << 8) | (data[at + 2] << 16)
                    | (data[at + 3] << 24);
        }

        // ---------------------------------------------------------- inflate

        private byte[] Inflate(int originalSize)
        {
            byte[] result = new byte[originalSize];
            byte[] slice = new byte[DicSize];
            pending = 0;
            InitGetbits();
            blocksize = 0;
            matchLeft = 0;

            int at = 0;
            while (at < originalSize)
            {
                int n = Math.Min(originalSize - at, DicSize);
                Decode(n, slice);
                Array.Copy(slice, 0, result, at, n);
                at += n;
            }
            return result;
        }

        // ---------------------------------------------------------- bit I/O

        private void Fillbuf(int n)
        {
            bitbuf = (bitbuf << n) & 0xFFFF;
            while (n > bitcount)
            {
                bitbuf |= (subbitbuf << (n -= bitcount)) & 0xFFFF;
                if (pending == 0)
                {
                    pendingAt = 0;
                    pending = Math.Min(BufSize - 32, sourceLeft);
                    if (pending > 0)
                    {
                        Array.Copy(source, sourceAt, window, 0, pending);
                        sourceAt += pending;
                        sourceLeft -= pending;
                    }
                }
                if (pending > 0)
                {
                    pending--;
                    subbitbuf = window[pendingAt++];
                }
                else
                {
                    subbitbuf = 0;      // ran dry: the sizes bound the read
                }
                bitcount = 8;
            }
            bitbuf |= (subbitbuf >> (bitcount -= n)) & 0xFFFF;
            bitbuf &= 0xFFFF;
        }

        private int Getbits(int n)
        {
            int bits = (bitbuf >> (BitBufSize - n)) & 0xFFFF;
            Fillbuf(n);
            return bits;
        }

        private void InitGetbits()
        {
            bitbuf = 0;
            subbitbuf = 0;
            bitcount = 0;
            Fillbuf(BitBufSize);
        }

        // ---------------------------------------------- Huffman table build

        private void MakeTable(int nchar, byte[] bitlen, int tablebits, int[] table)
        {
            int[] count = new int[17];
            int[] weight = new int[17];
            int[] start = new int[18];

            for (int i = 0; i < nchar; i++)
            {
                count[bitlen[i]]++;
            }
            start[1] = 0;
            for (int i = 1; i <= 16; i++)
            {
                start[i + 1] = start[i] + (count[i] << (16 - i));
            }

            int jutbits = 16 - tablebits;
            for (int i = 1; i <= tablebits; i++)
            {
                start[i] >>= jutbits;
                weight[i] = 1 << (tablebits - i);
            }
            for (int i = tablebits + 1; i <= 16; i++)
            {
                weight[i] = 1 << (16 - i);
            }

            int at = (start[tablebits + 1] >> jutbits) & 0xFFFF;
            int end = 1 << tablebits;
            while (at < end)
            {
                table[at++] = 0;
            }

            int avail = nchar;
            int mask = 1 << (15 - tablebits);
            for (int ch = 0; ch < nchar; ch++)
            {
                int len = bitlen[ch];
                if (len == 0)
                {
                    continue;
                }
                int nextcode = start[len] + weight[len];
                if (len <= tablebits)
                {
                    for (int i = start[len]; i < nextcode; i++)
                    {
                        table[i] = ch;
                    }
                }
                else
                {
                    // The code is longer than the table indexes: the tail
                    // bits walk a tree spliced into left[]/right[]. Which
                    // array a node lives in is part of the walk.
                    int code = start[len];
                    int array = 0;              // 0 table, 1 left, 2 right
                    int index = code >> jutbits;
                    for (int bits = len - tablebits; bits != 0; bits--)
                    {
                        int node = array == 0 ? table[index]
                                : array == 1 ? left[index] : right[index];
                        if (node == 0)
                        {
                            left[avail] = 0;
                            right[avail] = 0;
                            node = avail++;
                            if (array == 0)
                            {
                                table[index] = node;
                            }
                            else if (array == 1)
                            {
                                left[index] = node;
                            }
                            else
                            {
                                right[index] = node;
                            }
                        }
                        array = (code & mask) != 0 ? 2 : 1;
                        index = node;
                        code <<= 1;
                    }
                    if (array == 0)
                    {
                        table[index] = ch;
                    }
                    else if (array == 1)
                    {
                        left[index] = ch;
                    }
                    else
                    {
                        right[index] = ch;
                    }
                }
                start[len] = nextcode;
            }
        }

        // ------------------------------------------------ Huffman decoding

        private void ReadPtLen(int nn, int nbit, int special)
        {
            int n = Getbits(nbit);
            if (n == 0)
            {
                int c = Getbits(nbit);
                for (int i = 0; i < nn; i++)
                {
                    ptLen[i] = 0;
                }
                for (int i = 0; i < 256; i++)
                {
                    ptTable[i] = c;
                }
            }
            else
            {
                int i = 0;
                while (i < n)
                {
                    int c = bitbuf >> (BitBufSize - 3);
                    if (c == 7)
                    {
                        int mask = 1 << (BitBufSize - 4);
                        while ((mask & bitbuf) != 0)
                        {
                            mask >>= 1;
                            c++;
                        }
                    }
                    Fillbuf(c < 7 ? 3 : c - 3);
                    ptLen[i++] = (byte) c;
                    if (i == special)
                    {
                        int skip = Getbits(2);
                        while (--skip >= 0)
                        {
                            ptLen[i++] = 0;
                        }
                    }
                }
                while (i < nn)
                {
                    ptLen[i++] = 0;
                }
                MakeTable(nn, ptLen, 8, ptTable);
            }
        }

        private void ReadCLen()
        {
            int n = Getbits(CBit);
            if (n == 0)
            {
                int c = Getbits(CBit);
                for (int i = 0; i < Nc; i++)
                {
                    cLen[i] = 0;
                }
                for (int i = 0; i < 4096; i++)
                {
                    cTable[i] = c;
                }
            }
            else
            {
                int i = 0;
                while (i < n)
                {
                    int c = ptTable[(bitbuf >> (BitBufSize - 8)) & 0xFF];
                    if (c >= Nt)
                    {
                        int mask = 1 << (BitBufSize - 9);
                        do
                        {
                            c = (bitbuf & mask) != 0 ? right[c] : left[c];
                            mask >>= 1;
                        } while (c >= Nt);
                    }
                    Fillbuf(ptLen[c]);
                    if (c <= 2)
                    {
                        if (c == 0)
                        {
                            c = 1;
                        }
                        else if (c == 1)
                        {
                            c = Getbits(4) + 3;
                        }
                        else
                        {
                            c = Getbits(CBit) + 20;
                        }
                        while (--c >= 0)
                        {
                            cLen[i++] = 0;
                        }
                    }
                    else
                    {
                        cLen[i++] = (byte) (c - 2);
                    }
                }
                while (i < Nc)
                {
                    cLen[i++] = 0;
                }
                MakeTable(Nc, cLen, 12, cTable);
            }
        }

        private int DecodeC()
        {
            if (blocksize == 0)
            {
                blocksize = Getbits(16);
                ReadPtLen(Nt, TBit, 3);
                ReadCLen();
                ReadPtLen(Np, PBit, -1);
            }
            blocksize--;
            int j = cTable[(bitbuf >> (BitBufSize - 12)) & 0xFFF];
            if (j >= Nc)
            {
                int mask = 1 << (BitBufSize - 13);
                do
                {
                    j = (bitbuf & mask) != 0 ? right[j] : left[j];
                    mask >>= 1;
                } while (j >= Nc);
            }
            Fillbuf(cLen[j]);
            return j;
        }

        private int DecodeP()
        {
            int j = ptTable[(bitbuf >> (BitBufSize - 8)) & 0xFF];
            if (j >= Np)
            {
                int mask = 1 << (BitBufSize - 9);
                do
                {
                    j = (bitbuf & mask) != 0 ? right[j] : left[j];
                    mask >>= 1;
                } while (j >= Np);
            }
            Fillbuf(ptLen[j]);
            if (j != 0)
            {
                j = (1 << (j - 1)) + Getbits(j - 1);
            }
            return j;
        }

        /// <summary>One dictionary-sized slice of output; matches may carry
        /// over.</summary>
        private void Decode(int count, byte[] buffer)
        {
            int at = 0;
            while (--matchLeft >= 0)
            {
                buffer[at] = buffer[matchAt];
                matchAt = (matchAt + 1) & (DicSize - 1);
                if (++at == count)
                {
                    return;
                }
            }
            for (;;)
            {
                int c = DecodeC();
                if (c <= 255)
                {
                    buffer[at] = (byte) c;
                    if (++at == count)
                    {
                        return;
                    }
                }
                else
                {
                    matchLeft = c - (255 + 1 - Threshold);
                    matchAt = (at - DecodeP() - 1) & (DicSize - 1);
                    while (--matchLeft >= 0)
                    {
                        buffer[at] = buffer[matchAt];
                        matchAt = (matchAt + 1) & (DicSize - 1);
                        if (++at == count)
                        {
                            return;
                        }
                    }
                }
            }
        }
    }
}
