package st4

// Container assembles a file: twenty-eight bytes of header, then A, B, C and
// D in order, each starting on a long boundary. No length is stored: a
// stream runs to the next, and the last to whatever the caller loads after
// the container. A container is also how other formats embed an ST4 stream.
func (r Result) Container() []byte {
	controlAt := HeaderSize // already a multiple of 4
	literalAt := align(controlAt + len(r.Control))
	byteAt := align(literalAt + len(r.Literal))
	wordAt := align(byteAt + len(r.ByteOffsets))
	file := make([]byte, wordAt+len(r.WordOffsets))

	rewind := ^uint32(0) // NoRewind
	if r.RewindIndex >= 0 {
		rewind = uint32(r.RewindIndex * r.Unit)
	}
	putLong(file, OffsetSignature, Signature(r.Unit))
	putLong(file, OffsetSize, uint32(r.PaddedSize))
	putLong(file, OffsetLiteral, uint32(literalAt))
	putLong(file, OffsetByteOffsets, uint32(byteAt))
	putLong(file, OffsetWordOffsets, uint32(wordAt))
	putLong(file, OffsetRewind, rewind)
	putLong(file, OffsetWindow, uint32(r.Window))
	copy(file[controlAt:], r.Control)
	copy(file[literalAt:], r.Literal)
	copy(file[byteAt:], r.ByteOffsets)
	copy(file[wordAt:], r.WordOffsets)
	return file
}

func align(at int) int {
	return at + ((-at) & 3)
}

func putLong(file []byte, at int, value uint32) {
	file[at] = byte(value >> 24)
	file[at+1] = byte(value >> 16)
	file[at+2] = byte(value >> 8)
	file[at+3] = byte(value)
}
