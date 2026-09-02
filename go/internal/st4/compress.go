package st4

import "math/bits"

// InitialOffset is the offset a stream is decoded as having last used, so a
// first match can repeat it without naming it.
const InitialOffset = 1

// Block is one block of a parse, chained to the block before it: the last
// block of a parse is the parse. Bits is the cost of the chain through this
// block, Index the last unit it covers, and Offset its kind: zero a literal
// run, positive a match from that many units back in the output, negative a
// copy from the literal stream whose source starts that many units back in
// the output and is literal there. The compressor writes a copy as an offset
// beyond the window.
type Block struct {
	Bits   int
	Index  int
	Offset int
	Chain  *Block
}

// Result is the four streams and their figures. RewindIndex is the loop
// point of a stream the caller loops by rewind, in units, or -1; Window is
// what the header records; Copies counts the blocks copied from the literal
// stream.
type Result struct {
	Control     []byte
	Literal     []byte
	ByteOffsets []byte
	WordOffsets []byte
	Unit        int
	PaddedSize  int
	LongestOp   int
	Operations  int
	RewindIndex int
	Window      int
	Copies      int
	ControlBits int
	RepeatWord  bool
}

// PackedSize is the bytes all four streams take together.
func (r Result) PackedSize() int {
	return len(r.Control) + len(r.Literal) + len(r.ByteOffsets) +
		len(r.WordOffsets)
}

// Bits is what the parse cost, what a chain counts: everything written but
// the end code, its repeat bit, the repeat's word and stream A's padding.
func (r Result) Bits() int {
	repeat := 0
	if r.RepeatWord {
		repeat = 16
	}
	return r.ControlBits - 4 + 8*(len(r.Literal)+len(r.ByteOffsets)+
		len(r.WordOffsets)) - repeat
}

// compressor writes a parse as the four streams: bits and gamma lengths in
// A, literal units in B, byte offsets in C, word offsets in D. A word offset
// is written as -offset*unit and a byte offset as bank*256 + 256 - offset,
// the values the 68000 decoders keep in a register; stream A is padded to an
// even length for their word-wide refill.
//
// A match longer than maxOpLength units is split, since the decoders count
// an operation in a word; a literal run cannot be, and Result.LongestOp
// reports it. A copy from the literal stream is written as the window plus
// the literal units between its source and itself, and is shorter than that
// count: the one copy that would not be gives its last unit to a literal. The
// intro and the loop of a rewind stream are two parses written back to back;
// two literal runs that meet at the seam merge, and a one-unit rep the intro
// left no offset for is written as a literal.
type compressor struct {
	units        []uint32
	unit         int
	window       int
	control      []byte
	literal      []byte
	literalIndex int
	byteOffsets  []byte
	wordOffsets  []byte
	bitMask      int
	bitIndex     int
	bitsWritten  int
	longestOp    int
	operations   int
	copies       int

	// The walk: where the next unit comes from, the literal run gathered but
	// not yet written, the offset the stream holds, whether the first block,
	// which has no flag, is still to come, and how many literal units precede
	// each position written so far.
	readIndex       int
	pendingLiterals int
	lastOffset      int
	first           bool
	literalsBefore  []int
}

func newCompressor(units []uint32, unit, window int) *compressor {
	size := len(units) * unit
	if size < unit {
		size = unit
	}
	return &compressor{
		units:          units,
		unit:           unit,
		window:         window,
		control:        make([]byte, 0, 256),
		literal:        make([]byte, size),
		lastOffset:     InitialOffset,
		first:          true,
		literalsBefore: make([]int, len(units)+1),
	}
}

// Compress writes the parse out as the four streams, ending the stream.
func Compress(optimal *Block, units []uint32, unit, maxOpLength int) Result {
	return CompressRepeating(optimal, units, unit, maxOpLength, -1,
		MaxOffsetUnits(unit))
}

// CompressRepeating is Compress repeating from unit repeatIndex: the stream
// decodes as units[0..R) units[R..O) forever, the distance O-R written as one
// last word offset, and -1 ends the stream. The parse was made at window
// units: its matches keep within it, and its copies are written as offsets
// beyond it.
func CompressRepeating(optimal *Block, units []uint32, unit, maxOpLength,
	repeatIndex, window int) Result {
	if repeatIndex < -1 || repeatIndex >= len(units) {
		panic("the loop point must be a unit of the stream itself")
	}
	return newCompressor(units, unit, window).run([]*Block{optimal},
		maxOpLength, repeatIndex, -1)
}

// CompressRewinding writes a stream that loops by rewind: the intro
// units[0..R), nil when R is 0, and the loop units[R..O) come from separate
// parses, so no match in the loop reaches before unit rewindIndex, where the
// caller saves the decoder's state. The stream ends plainly. The parses were
// made at window units.
func CompressRewinding(intro, loop *Block, units []uint32, unit, maxOpLength,
	rewindIndex, window int) Result {
	if rewindIndex < 0 || rewindIndex >= len(units) {
		panic("the rewind point must be a unit of the stream itself")
	}
	if (intro == nil) != (rewindIndex == 0) {
		panic("an intro exactly when there is one")
	}
	chains := []*Block{loop}
	if intro != nil {
		chains = []*Block{intro, loop}
	}
	return newCompressor(units, unit, window).run(chains, maxOpLength, -1,
		rewindIndex)
}

func (c *compressor) run(chains []*Block, maxOpLength, repeatIndex,
	rewindIndex int) Result {
	for _, chain := range chains {
		// Un-reverse the chain; its head is the parser's fake block.
		var blocks []*Block
		for block := chain; block != nil; block = block.Chain {
			blocks = append(blocks, block)
		}
		// blocks is now newest-first; walk it backwards, dropping the head.
		previous := blocks[len(blocks)-1]

		for at := len(blocks) - 2; at >= 0; at-- {
			block := blocks[at]
			length := block.Index - previous.Index
			previous = block

			if block.Offset == 0 {
				c.pendingLiterals += length // runs merge across a seam
				continue
			}
			if block.Offset < 0 {
				c.copy(-block.Offset, length, maxOpLength)
				continue
			}
			offset := block.Offset
			if offset > c.window {
				panic("a match reaches past the window")
			}
			// Split evenly rather than greedily: every piece after the first
			// has to be a new-offset match, and those cannot be shorter than
			// two units, so a greedy remainder of one would be unwritable.
			pieces := 1
			if maxOpLength >= 3 {
				pieces = (length-1)/maxOpLength + 1
			}
			base := length / pieces
			wider := length % pieces
			for piece := 0; piece < pieces; piece++ {
				size := base
				if piece < wider {
					size++
				}
				rep := c.pendingLiterals > 0 && offset == c.lastOffset
				if size == 1 && !rep {
					c.pendingLiterals++ // the seam's one-unit rep
					continue
				}
				c.flushLiterals()
				c.emitMatch(offset, size, rep)
			}
		}
	}
	c.flushLiterals()
	if c.readIndex != len(c.units) {
		panic("the parses did not cover the input")
	}

	// The end marker, then the repeat bit: end, or one last word offset in
	// stream D, the distance back to the loop point, matched forever.
	c.writeBit(true)
	c.writeBit(false)
	c.writeBit(true)
	c.writeBit(repeatIndex >= 0)
	if repeatIndex >= 0 {
		scaled := (len(c.units) - repeatIndex) * c.unit
		if scaled > 32768 {
			panic("the loop distance must fit -(O-R)*k in a signed word")
		}
		if len(c.units)-repeatIndex > c.window {
			panic("the loop must fit the window")
		}
		c.wordOffsets = append(c.wordOffsets, byte(uint32(-scaled)>>8),
			byte(-scaled))
	}

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
		RewindIndex: rewindIndex,
		Window:      c.window,
		Copies:      c.copies,
		ControlBits: c.bitsWritten,
		RepeatWord:  repeatIndex >= 0,
	}
}

// copy writes a copy from the literal stream, distance units back in the
// output for length units, in pieces the counters hold. A piece is written
// as a match at the window plus the literals between its source and itself;
// a piece as long as that count gives its last unit to a literal, so the
// decoder's offset, advanced by what it copies, never reaches zero.
func (c *compressor) copy(distance, length, maxOpLength int) {
	pieces := 1
	if maxOpLength >= 3 {
		pieces = (length-1)/maxOpLength + 1
	}
	base := length / pieces
	wider := length % pieces
	for piece := 0; piece < pieces; piece++ {
		size := base
		if piece < wider {
			size++
		}
		start := c.readIndex + c.pendingLiterals
		source := start - distance
		if c.literalsAt(source+size)-c.literalsAt(source) != size {
			panic("a copy's source must be literal")
		}
		back := c.literalsAt(start) - c.literalsAt(source)
		if back < size {
			panic("a copy's source lies behind its own literals")
		}
		given := 0
		if back == size {
			if size-1 < 2 {
				c.pendingLiterals += size // too short to write at all
				continue
			}
			given = 1
			size--
		}
		wire := c.window + back
		if wire > MaxOffsetUnits(c.unit) {
			panic("a copy reaches past the offsets")
		}
		rep := c.pendingLiterals > 0 && wire == c.lastOffset
		c.flushLiterals()
		c.emitMatch(wire, size, rep)
		c.lastOffset = wire - size // where the decoder leaves it
		c.copies++
		c.pendingLiterals += given
	}
}

// literalsAt is the literal units before position: recorded for what is
// written, counted for the run still pending.
func (c *compressor) literalsAt(position int) int {
	if position <= c.readIndex {
		return c.literalsBefore[position]
	}
	return c.literalsBefore[c.readIndex] + (position - c.readIndex)
}

func (c *compressor) emitMatch(offset, size int, rep bool) {
	if rep {
		c.writeBit(false)
		c.writeInterlacedEliasGamma(size)
	} else {
		c.writeBit(true)
		c.writeOffsetOf(offset)
		c.writeInterlacedEliasGamma(size - 1)
		c.lastOffset = offset
	}
	for i := 0; i < size; i++ {
		c.literalsBefore[c.readIndex+i+1] = c.literalsBefore[c.readIndex]
	}
	c.operations++
	c.readIndex += size
	if size > c.longestOp {
		c.longestOp = size
	}
}

// flushLiterals writes the literal run gathered so far, if any: its flag,
// unless it opens the stream, its length, and its units into stream B.
func (c *compressor) flushLiterals() {
	if c.pendingLiterals == 0 {
		return
	}
	if c.first {
		c.first = false // a stream opens with literals
	} else {
		c.writeBit(false)
	}
	c.writeInterlacedEliasGamma(c.pendingLiterals)
	for i := 0; i < c.pendingLiterals; i++ {
		WriteUnit(c.literal, c.literalIndex, c.units[c.readIndex], c.unit)
		c.literalIndex += c.unit
		c.literalsBefore[c.readIndex+1] = c.literalsBefore[c.readIndex] + 1
		c.readIndex++
	}
	c.operations++
	if c.pendingLiterals > c.longestOp {
		c.longestOp = c.pendingLiterals
	}
	c.pendingLiterals = 0
}

// writeOffsetOf writes the two class bits, then the offset itself into
// whichever stream it belongs to. Two class bits keep every operation an
// even number of bits, so a decoder checks its refill on gamma continuation
// bits alone.
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
	c.bitsWritten++
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
