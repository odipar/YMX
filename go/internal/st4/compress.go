package st4

import "math/bits"

// InitialOffset is the offset a stream is decoded as having last used, so a
// first match can repeat it without naming it.
const InitialOffset = 1

// Block is one block of an ST4 parse chain: a literal run when Offset is 0,
// otherwise a match. Index is the last unit the block covers, and Bits the
// cost of the whole chain up to and including it.
type Block struct {
	Bits   int
	Index  int
	Offset int
	Chain  *Block
}

// Result is the four streams, and what the caller needs to know about them.
type Result struct {
	Control     []byte
	Literal     []byte
	ByteOffsets []byte
	WordOffsets []byte
	Unit        int
	PaddedSize  int
	LongestOp   int
	Operations  int
}

// PackedSize is the bytes all four streams take together.
func (r Result) PackedSize() int {
	return len(r.Control) + len(r.Literal) + len(r.ByteOffsets) +
		len(r.WordOffsets)
}

// compressor writes a parse out as the format's streams: bits in stream A,
// literal payload in B, byte offsets in C, word offsets in D. Matches longer
// than maxOpLength units are split evenly, and stream A is padded to an even
// length for the 68000's word-wide refill.
type compressor struct {
	units        []uint32
	unit         int
	control      []byte
	literal      []byte
	literalIndex int
	byteOffsets  []byte
	wordOffsets  []byte
	bitMask      int
	bitIndex     int
	longestOp    int
	operations   int
}

// Compress writes the parse out as the four streams.
func Compress(optimal *Block, units []uint32, unit, maxOpLength int) Result {
	size := len(units) * unit
	if size < unit {
		size = unit
	}
	c := &compressor{
		units:   units,
		unit:    unit,
		control: make([]byte, 0, 256),
		literal: make([]byte, size),
	}
	return c.run(optimal, maxOpLength)
}

func (c *compressor) run(optimal *Block, maxOpLength int) Result {
	// Un-reverse the chain; its head is the parser's fake block.
	var blocks []*Block
	for block := optimal; block != nil; block = block.Chain {
		blocks = append(blocks, block)
	}
	// blocks is now newest-first; walk it backwards, dropping the head.
	previous := blocks[len(blocks)-1]

	readIndex := 0
	lastOffset := InitialOffset
	first := true
	afterLiterals := false

	for at := len(blocks) - 2; at >= 0; at-- {
		block := blocks[at]
		length := block.Index - previous.Index
		previous = block

		if block.Offset == 0 {
			if first {
				first = false // a stream opens with literals
			} else {
				c.writeBit(false)
			}
			c.writeInterlacedEliasGamma(length)
			for i := 0; i < length; i++ {
				WriteUnit(c.literal, c.literalIndex, c.units[readIndex], c.unit)
				readIndex++
				c.literalIndex += c.unit
			}
			afterLiterals = true
			c.operations++
			if length > c.longestOp {
				c.longestOp = length
			}
			continue
		}

		offset := block.Offset
		// Split evenly rather than greedily: every piece after the first has
		// to be a new-offset match, and those cannot be shorter than two
		// units, so a greedy remainder of one would be unwritable.
		pieces := 1
		if maxOpLength >= 3 {
			pieces = (length-1)/maxOpLength + 1
		}
		baseSize := length / pieces
		wider := length % pieces
		for piece := 0; piece < pieces; piece++ {
			size := baseSize
			if piece < wider {
				size++
			}
			if afterLiterals && offset == lastOffset {
				c.writeBit(false)
				c.writeInterlacedEliasGamma(size)
			} else {
				c.writeBit(true)
				c.writeOffsetOf(offset)
				c.writeInterlacedEliasGamma(size - 1)
				lastOffset = offset
			}
			afterLiterals = false
			c.operations++
			readIndex += size
			if size > c.longestOp {
				c.longestOp = size
			}
		}
	}

	// End marker: the one control code that names no stream.
	c.writeBit(true)
	c.writeBit(false)
	c.writeBit(true)

	control := c.control
	if len(control)&1 != 0 {
		control = append(control, 0)
	}
	return Result{
		Control:     control,
		Literal:     c.literal[:c.literalIndex],
		ByteOffsets: c.byteOffsets,
		WordOffsets: c.wordOffsets,
		Unit:        c.unit,
		PaddedSize:  len(c.units) * c.unit,
		LongestOp:   c.longestOp,
		Operations:  c.operations,
	}
}

// writeOffsetOf writes the two class bits, then the offset itself into
// whichever stream it belongs to.
func (c *compressor) writeOffsetOf(offset int) {
	if offset <= ByteOffsetLimit {
		bank := (offset - 1) / 256 // 0 for 1..256, 1 for 257..512
		c.writeBit(true)
		c.writeBit(bank != 0)
		c.byteOffsets = append(c.byteOffsets, byte(bank*256+256-offset))
		return
	}
	scaled := offset * c.unit
	if scaled > 32768 {
		panic("a word offset must fit -offset*k in a signed word")
	}
	c.writeBit(false)
	c.writeBit(false)
	c.wordOffsets = append(c.wordOffsets, byte(uint32(-scaled)>>8), byte(-scaled))
}

// writeBit puts a bit in stream A, in the byte reserved when the reservoir
// ran dry - so a set bit patches that byte where it sits.
func (c *compressor) writeBit(value bool) {
	if c.bitMask == 0 {
		c.bitMask = 128
		c.bitIndex = len(c.control)
		c.control = append(c.control, 0)
	}
	if value {
		c.control[c.bitIndex] |= byte(c.bitMask)
	}
	c.bitMask >>= 1
}

func (c *compressor) writeInterlacedEliasGamma(value int) {
	for bit := highestOneBit(value) >> 1; bit != 0; bit >>= 1 {
		c.writeBit(true)
		c.writeBit(value&bit != 0)
	}
	c.writeBit(false)
}

func highestOneBit(value int) int {
	if value == 0 {
		return 0
	}
	return 1 << (31 - bits.LeadingZeros32(uint32(value)))
}
