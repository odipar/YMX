// Package st4 is the ST4 container: the compressor, the decompressor and
// the format they share. It is a port of dotnet/st4 and src/main/java/org/st4,
// held to the same bar as they hold each other - the same input gives the
// same bytes out of all three.
//
// Stream A holds the bits, the flags and the interlaced Elias gamma lengths,
// read a word at a time. Stream B holds the literal units, stream C the byte
// offsets and stream D the word offsets. An offset of at most the window M,
// recorded in the header, is a match from the output. An offset beyond M
// copies offset - M units from behind the literal read pointer, leaves the
// pointer where it is, and advances the offset by what it copied, so a rep
// after a copy resumes past it. A copy is shorter than its distance.
//
// The end code's extra bit: 0 ends the stream; 1 repeats it from a loop
// point R, so it decodes as units[0..R) units[R..O) forever, with the
// distance O-R as one last word in stream D, matched endlessly. A loop
// longer than the window is replayed instead: the header gives the rewind
// point in bytes, the caller saves the decoder's registers there and
// restores them, all but the write pointer, at O.
package st4

import "fmt"

// The container's twenty-eight-byte header and the limits a decoder reads:
//
//	 0   4  signature: 'S', '4', format version, k
//	 4   4  O, the output size in bytes, a multiple of k
//	 8   4  stream B, the literals, as a byte offset from the header
//	12   4  stream C, the byte offsets
//	16   4  stream D, the word offsets
//	20   4  the rewind point in bytes, or $FFFFFFFF when there is none
//	24   4  M, the window in units
//	28  ..  streams A, B, C and D, in that order, each on a long boundary
const (
	Magic   = 0x53340000
	Version = 7

	OffsetSignature   = 0
	OffsetSize        = 4
	OffsetLiteral     = 8
	OffsetByteOffsets = 12
	OffsetWordOffsets = 16
	OffsetRewind      = 20
	OffsetWindow      = 24
	HeaderSize        = 28

	// NoRewind is the rewind field of a stream that ends or loops by itself.
	NoRewind = -1

	// MaxOffset is the furthest any offset reaches, in bytes: what fits a
	// signed word as -offset*k.
	MaxOffset = 32512

	// ByteOffsetLimit is the furthest a byte offset reaches, in units.
	ByteOffsetLimit = 512

	// MaxOp is the longest operation the 68000 decoders count, in units.
	MaxOp = 65535
)

// Signature packs magic, version and unit size into one long, so a decoder
// built for one k checks an asset with a single cmp.l.
func Signature(unit int) uint32 {
	return uint32(Magic) | uint32(Version)<<8 | uint32(unit)
}

// IsUnitSize reports whether unit is one the format carries.
func IsUnitSize(unit int) bool {
	return unit == 1 || unit == 2 || unit == 4
}

// CheckUnit gives the reason unit cannot be used, or an empty string.
func CheckUnit(unit int) string {
	if IsUnitSize(unit) {
		return ""
	}
	return fmt.Sprintf("unit size %d is not 1, 2 or 4", unit)
}

// MaxOffsetUnits is how far back a match may reach at this unit size, in
// units.
func MaxOffsetUnits(unit int) int {
	return MaxOffset / unit
}

// Split reads the input as units of k bytes, big-endian, zero-padded to a
// whole number of them.
func Split(data []byte, unit int) []uint32 {
	count := (len(data) + unit - 1) / unit
	units := make([]uint32, count)
	for index := 0; index < count; index++ {
		var value uint32
		for byteIndex := 0; byteIndex < unit; byteIndex++ {
			at := index*unit + byteIndex
			var b uint32
			if at < len(data) {
				b = uint32(data[at])
			}
			value = value<<8 | b
		}
		units[index] = value
	}
	return units
}

// WriteUnit writes one unit's bytes, most significant first.
func WriteUnit(target []byte, at int, value uint32, unit int) {
	for byteIndex := unit - 1; byteIndex >= 0; byteIndex-- {
		target[at+byteIndex] = byte(value)
		value >>= 8
	}
}

// PaddedLength is the padded length in bytes: what the decoder produces.
func PaddedLength(length, unit int) int {
	return (length + unit - 1) / unit * unit
}

// Container is one ST4 file, taken apart: the four streams, the unit size,
// the output size, the rewind point in bytes or NoRewind, and the window in
// units.
type Container struct {
	Unit        int
	Size        int
	Control     []byte
	Literal     []byte
	ByteOffsets []byte
	WordOffsets []byte
	Rewind      int
	Window      int
}

// Read takes a container apart, checking everything a decoder would
// otherwise accept. The streams may carry up to three bytes of alignment
// padding.
func Read(file []byte) (Container, error) {
	if len(file) < HeaderSize {
		return Container{}, fmt.Errorf("too short to be an ST4 file")
	}
	signature := longAt(file, OffsetSignature)
	if signature&0xFFFF0000 != uint32(Magic) {
		return Container{}, fmt.Errorf("not an ST4 file")
	}
	version := int(signature>>8) & 0xFF
	if version != Version {
		return Container{}, fmt.Errorf("ST4 format version %d, not %d",
			version, Version)
	}
	unit := int(signature) & 0xFF
	if problem := CheckUnit(unit); problem != "" {
		return Container{}, fmt.Errorf("%s", problem)
	}
	size := int(int32(longAt(file, OffsetSize)))
	if size < 0 || size%unit != 0 {
		return Container{}, fmt.Errorf(
			"output size %d is not a whole number of %d-byte units", size, unit)
	}
	rewind := int(int32(longAt(file, OffsetRewind)))
	if rewind != NoRewind && (rewind < 0 || rewind >= size || rewind%unit != 0) {
		return Container{}, fmt.Errorf(
			"rewind point %d is not a unit of the output", rewind)
	}
	window := int(int32(longAt(file, OffsetWindow)))
	if window < 1 || window > MaxOffsetUnits(unit) {
		return Container{}, fmt.Errorf("window %d is not 1..%d units",
			window, MaxOffsetUnits(unit))
	}

	// The streams lie in the file as A, B, C, D.
	edge := []int{HeaderSize,
		int(int32(longAt(file, OffsetLiteral))),
		int(int32(longAt(file, OffsetByteOffsets))),
		int(int32(longAt(file, OffsetWordOffsets))),
		len(file)}
	for i := 1; i < len(edge)-1; i++ {
		if edge[i]%4 != 0 {
			return Container{}, fmt.Errorf(
				"stream %c does not start on a long boundary", "ABCD"[i])
		}
		if edge[i] < edge[i-1] || edge[i] > len(file) {
			return Container{}, fmt.Errorf(
				"stream %c lies outside the file", "ABCD"[i])
		}
	}
	return Container{
		Unit:        unit,
		Size:        size,
		Control:     file[edge[0]:edge[1]],
		Literal:     file[edge[1]:edge[2]],
		ByteOffsets: file[edge[2]:edge[3]],
		WordOffsets: file[edge[3]:edge[4]],
		Rewind:      rewind,
		Window:      window,
	}, nil
}

func longAt(file []byte, at int) uint32 {
	return uint32(file[at])<<24 | uint32(file[at+1])<<16 |
		uint32(file[at+2])<<8 | uint32(file[at+3])
}
