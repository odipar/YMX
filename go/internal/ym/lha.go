// Package ym reads a YM5!/YM6! register dump: the frames as the file holds
// them, the digidrum samples, and the header text. The LHA archive most .ym
// files come wrapped in is unpacked on the way in. A port of dotnet/ym6 and
// src/main/java/org/ym6, held to the same bytes.
package ym

import "fmt"

// The depacker's fixed sizes. DicBit is 13 because that is what -lh5- uses.
const (
	bufSize    = 4096
	bitBufSize = 16
	dicBit     = 13 // -lh5-
	dicSize    = 1 << dicBit
	maxMatch   = 256
	threshold  = 3
	nc         = 255 + maxMatch + 2 - threshold
	cBit       = 9
	np         = dicBit + 1
	nt         = 16 + 3
	pBit       = 4
	tBit       = 5
	npt        = nt // nt is the larger of nt and np
)

// lha unpacks the LHA archives distributed .ym files come wrapped in,
// entirely in memory - ported from Ym6.Lha and org.ym6.Lha, themselves from
// the ST-Sound library's LZH depacker by Arnaud Carre, based on LZH code by
// Haruhiko Okumura (1991) and Kerwin F. Medina (1996). Level-0 headers,
// -lh5- inflated and -lh0- copied out; the first member is the answer.
type lha struct {
	source     []byte
	sourceAt   int
	sourceLeft int

	bitbuf    int
	subbitbuf int
	bitcount  int
	pending   int
	pendingAt int
	window    [bufSize]byte

	left      [2*nc - 1]int
	right     [2*nc - 1]int
	cLen      [nc]byte
	ptLen     [npt]byte
	blocksize int
	cTable    [4096]int
	ptTable   [256]int

	matchLeft int
	matchAt   int
}

// IsArchive reports whether this is an LHA archive: any -lh?- method at
// offset 2.
func IsArchive(data []byte) bool {
	return len(data) >= 22 && data[0] != 0 &&
		data[2] == '-' && data[3] == 'l' && data[4] == 'h' &&
		data[6] == '-'
}

// Unpack returns the archive's first member.
func Unpack(archive []byte) (data []byte, err error) {
	// A corrupt member indexes past a Huffman table or a code length array.
	// The depacker carries no bounds test of its own, so the index panic is
	// what reports a member the sizes in the header do not describe.
	defer func() {
		if problem := recover(); problem != nil {
			data = nil
			err = fmt.Errorf("corrupt LHA member: %v", problem)
		}
	}()

	if !IsArchive(archive) {
		return nil, fmt.Errorf("not an LHA archive")
	}
	headerSize := int(archive[0])
	dataAt := headerSize + 2
	if dataAt > len(archive) {
		return nil, fmt.Errorf("LHA header extends beyond the file")
	}
	level := archive[20]
	if level != 0 {
		return nil, fmt.Errorf("LHA header level %d; YM archives use level 0",
			level)
	}
	sum := 0
	for i := 2; i < dataAt; i++ {
		sum += int(archive[i])
	}
	if byte(sum) != archive[1] {
		return nil, fmt.Errorf("LHA header checksum mismatch")
	}

	compressedSize := little32(archive, 7)
	originalSize := little32(archive, 11)
	if compressedSize < 0 || int(compressedSize) > len(archive)-dataAt {
		return nil, fmt.Errorf("LHA member is truncated")
	}
	if originalSize < 0 {
		return nil, fmt.Errorf("LHA member claims a negative size")
	}

	method := string(archive[2:7])
	if method == "-lh0-" { // stored, not compressed
		if int(originalSize) > len(archive)-dataAt {
			return nil, fmt.Errorf("LHA member is truncated")
		}
		stored := make([]byte, originalSize)
		copy(stored, archive[dataAt:])
		return stored, nil
	}
	if method != "-lh5-" {
		return nil, fmt.Errorf("unsupported LHA method %s", method)
	}
	depacker := &lha{
		source:     archive,
		sourceAt:   dataAt,
		sourceLeft: int(compressedSize),
	}
	return depacker.inflate(int(originalSize)), nil
}

// little32 reads four bytes low end first. The result is signed in 32 bits,
// so a size with the top bit set reads back negative and the caller rejects
// it.
func little32(data []byte, at int) int32 {
	return int32(data[at]) | int32(data[at+1])<<8 |
		int32(data[at+2])<<16 | int32(data[at+3])<<24
}

// ---------------------------------------------------------------- inflate

func (l *lha) inflate(originalSize int) []byte {
	result := make([]byte, originalSize)
	// One slice for the whole run: a match reaching back past the start of
	// this slice reads what the last one left there.
	slice := make([]byte, dicSize)
	l.pending = 0
	l.initGetbits()
	l.blocksize = 0
	l.matchLeft = 0

	at := 0
	for at < originalSize {
		n := originalSize - at
		if n > dicSize {
			n = dicSize
		}
		l.decode(n, slice)
		copy(result[at:at+n], slice[:n])
		at += n
	}
	return result
}

// ---------------------------------------------------------------- bit I/O

func (l *lha) fillbuf(n int) {
	l.bitbuf = (l.bitbuf << n) & 0xFFFF
	for n > l.bitcount {
		n -= l.bitcount
		l.bitbuf |= (l.subbitbuf << n) & 0xFFFF
		if l.pending == 0 {
			l.pendingAt = 0
			l.pending = bufSize - 32
			if l.sourceLeft < l.pending {
				l.pending = l.sourceLeft
			}
			if l.pending > 0 {
				copy(l.window[:l.pending], l.source[l.sourceAt:])
				l.sourceAt += l.pending
				l.sourceLeft -= l.pending
			}
		}
		if l.pending > 0 {
			l.pending--
			l.subbitbuf = int(l.window[l.pendingAt])
			l.pendingAt++
		} else {
			l.subbitbuf = 0 // ran dry: the sizes bound the read
		}
		l.bitcount = 8
	}
	l.bitcount -= n
	l.bitbuf |= (l.subbitbuf >> l.bitcount) & 0xFFFF
	l.bitbuf &= 0xFFFF
}

func (l *lha) getbits(n int) int {
	bits := (l.bitbuf >> (bitBufSize - n)) & 0xFFFF
	l.fillbuf(n)
	return bits
}

func (l *lha) initGetbits() {
	l.bitbuf = 0
	l.subbitbuf = 0
	l.bitcount = 0
	l.fillbuf(bitBufSize)
}

// ----------------------------------------------------- Huffman table build

func (l *lha) makeTable(nchar int, bitlen []byte, tablebits int, table []int) {
	var count [17]int
	var weight [17]int
	var start [18]int

	for i := 0; i < nchar; i++ {
		count[bitlen[i]]++
	}
	start[1] = 0
	for i := 1; i <= 16; i++ {
		start[i+1] = start[i] + (count[i] << (16 - i))
	}

	jutbits := 16 - tablebits
	for i := 1; i <= tablebits; i++ {
		start[i] >>= jutbits
		weight[i] = 1 << (tablebits - i)
	}
	for i := tablebits + 1; i <= 16; i++ {
		weight[i] = 1 << (16 - i)
	}

	at := (start[tablebits+1] >> jutbits) & 0xFFFF
	end := 1 << tablebits
	for at < end {
		table[at] = 0
		at++
	}

	avail := nchar
	mask := 1 << (15 - tablebits)
	for ch := 0; ch < nchar; ch++ {
		length := int(bitlen[ch])
		if length == 0 {
			continue
		}
		nextcode := start[length] + weight[length]
		if length <= tablebits {
			for i := start[length]; i < nextcode; i++ {
				table[i] = ch
			}
		} else {
			// The code is longer than the table indexes: the tail bits walk
			// a tree spliced into left/right. Which array a node lives in is
			// part of the walk.
			code := int32(start[length])
			array := 0 // 0 table, 1 left, 2 right
			index := int(code >> jutbits)
			for bit := length - tablebits; bit != 0; bit-- {
				var node int
				switch array {
				case 0:
					node = table[index]
				case 1:
					node = l.left[index]
				default:
					node = l.right[index]
				}
				if node == 0 {
					l.left[avail] = 0
					l.right[avail] = 0
					node = avail
					avail++
					switch array {
					case 0:
						table[index] = node
					case 1:
						l.left[index] = node
					default:
						l.right[index] = node
					}
				}
				if code&int32(mask) != 0 {
					array = 2
				} else {
					array = 1
				}
				index = node
				code <<= 1
			}
			switch array {
			case 0:
				table[index] = ch
			case 1:
				l.left[index] = ch
			default:
				l.right[index] = ch
			}
		}
		start[length] = nextcode
	}
}

// ------------------------------------------------------ Huffman decoding

func (l *lha) readPtLen(nn, nbit, special int) {
	n := l.getbits(nbit)
	if n == 0 {
		c := l.getbits(nbit)
		for i := 0; i < nn; i++ {
			l.ptLen[i] = 0
		}
		for i := 0; i < 256; i++ {
			l.ptTable[i] = c
		}
		return
	}
	i := 0
	for i < n {
		c := l.bitbuf >> (bitBufSize - 3)
		if c == 7 {
			mask := 1 << (bitBufSize - 4)
			for mask&l.bitbuf != 0 {
				mask >>= 1
				c++
			}
		}
		if c < 7 {
			l.fillbuf(3)
		} else {
			l.fillbuf(c - 3)
		}
		l.ptLen[i] = byte(c)
		i++
		if i == special {
			skip := l.getbits(2)
			for {
				skip--
				if skip < 0 {
					break
				}
				l.ptLen[i] = 0
				i++
			}
		}
	}
	for i < nn {
		l.ptLen[i] = 0
		i++
	}
	l.makeTable(nn, l.ptLen[:], 8, l.ptTable[:])
}

func (l *lha) readCLen() {
	n := l.getbits(cBit)
	if n == 0 {
		c := l.getbits(cBit)
		for i := 0; i < nc; i++ {
			l.cLen[i] = 0
		}
		for i := 0; i < 4096; i++ {
			l.cTable[i] = c
		}
		return
	}
	i := 0
	for i < n {
		c := l.ptTable[(l.bitbuf>>(bitBufSize-8))&0xFF]
		if c >= nt {
			mask := 1 << (bitBufSize - 9)
			for {
				if l.bitbuf&mask != 0 {
					c = l.right[c]
				} else {
					c = l.left[c]
				}
				mask >>= 1
				if c < nt {
					break
				}
			}
		}
		l.fillbuf(int(l.ptLen[c]))
		if c <= 2 {
			switch c {
			case 0:
				c = 1
			case 1:
				c = l.getbits(4) + 3
			default:
				c = l.getbits(cBit) + 20
			}
			for {
				c--
				if c < 0 {
					break
				}
				l.cLen[i] = 0
				i++
			}
		} else {
			l.cLen[i] = byte(c - 2)
			i++
		}
	}
	for i < nc {
		l.cLen[i] = 0
		i++
	}
	l.makeTable(nc, l.cLen[:], 12, l.cTable[:])
}

func (l *lha) decodeC() int {
	if l.blocksize == 0 {
		l.blocksize = l.getbits(16)
		l.readPtLen(nt, tBit, 3)
		l.readCLen()
		l.readPtLen(np, pBit, -1)
	}
	l.blocksize--
	j := l.cTable[(l.bitbuf>>(bitBufSize-12))&0xFFF]
	if j >= nc {
		mask := 1 << (bitBufSize - 13)
		for {
			if l.bitbuf&mask != 0 {
				j = l.right[j]
			} else {
				j = l.left[j]
			}
			mask >>= 1
			if j < nc {
				break
			}
		}
	}
	l.fillbuf(int(l.cLen[j]))
	return j
}

func (l *lha) decodeP() int {
	j := l.ptTable[(l.bitbuf>>(bitBufSize-8))&0xFF]
	if j >= np {
		mask := 1 << (bitBufSize - 9)
		for {
			if l.bitbuf&mask != 0 {
				j = l.right[j]
			} else {
				j = l.left[j]
			}
			mask >>= 1
			if j < np {
				break
			}
		}
	}
	l.fillbuf(int(l.ptLen[j]))
	if j != 0 {
		j = (1 << (j - 1)) + l.getbits(j-1)
	}
	return j
}

// decode fills one dictionary-sized slice of output; matches may carry over
// into the next slice.
func (l *lha) decode(count int, buffer []byte) {
	at := 0
	for {
		l.matchLeft--
		if l.matchLeft < 0 {
			break
		}
		buffer[at] = buffer[l.matchAt]
		l.matchAt = (l.matchAt + 1) & (dicSize - 1)
		at++
		if at == count {
			return
		}
	}
	for {
		c := l.decodeC()
		if c <= 255 {
			buffer[at] = byte(c)
			at++
			if at == count {
				return
			}
		} else {
			l.matchLeft = c - (255 + 1 - threshold)
			l.matchAt = (at - l.decodeP() - 1) & (dicSize - 1)
			for {
				l.matchLeft--
				if l.matchLeft < 0 {
					break
				}
				buffer[at] = buffer[l.matchAt]
				l.matchAt = (l.matchAt + 1) & (dicSize - 1)
				at++
				if at == count {
					return
				}
			}
		}
	}
}
