package st4

import "fmt"

// Where the decoder stands between operations: what it emitted last selects
// what the next control bit means.
type decodeState int

const (
	stateStart decodeState = iota
	stateLiterals
	stateMatch
	stateDone
)

// decompressor is ZX1's state machine with the pieces read from the format's
// own streams, every length and offset counted in units.
type decompressor struct {
	offsetLimit     int
	control         []byte
	literal         []byte
	byteOffsets     []byte
	wordOffsets     []byte
	output          []byte
	unit            int
	controlIndex    int
	literalIndex    int
	byteOffsetIndex int
	wordOffsetIndex int
	outputIndex     int
	bitMask         int
	bitValue        int
	lastOffset      int
	state           decodeState
}

// Decompress rebuilds size bytes from the four streams, reaching as far back
// as the unit size carries.
func Decompress(control, literal, byteOffsets, wordOffsets []byte,
	unit, size int) ([]byte, error) {
	return DecompressLimit(control, literal, byteOffsets, wordOffsets, unit,
		size, MaxOffsetUnits(unit))
}

// DecompressLimit is Decompress refusing any back-reference further than
// offsetLimit units. A stream that decodes under a limit is safe for a ring of
// that many units.
func DecompressLimit(control, literal, byteOffsets, wordOffsets []byte,
	unit, size, offsetLimit int) ([]byte, error) {
	if !IsUnitSize(unit) {
		return nil, fmt.Errorf("unit size must be 1, 2 or 4")
	}
	if size < 0 || size%unit != 0 {
		return nil, fmt.Errorf("output size must be a whole number of units")
	}
	d := &decompressor{
		offsetLimit: offsetLimit,
		control:     control,
		literal:     literal,
		byteOffsets: byteOffsets,
		wordOffsets: wordOffsets,
		output:      make([]byte, size),
		unit:        unit,
		lastOffset:  InitialOffset,
		state:       stateStart,
	}
	if err := d.run(); err != nil {
		return nil, err
	}
	return d.output, nil
}

func (d *decompressor) run() error {
	for d.state != stateDone {
		var err error
		switch d.state {
		case stateStart:
			err = d.beginLiterals()
		case stateLiterals:
			var newOffset bool
			if newOffset, err = d.readBit(); err == nil {
				if newOffset {
					err = d.beginMatchFromNewOffset()
				} else {
					err = d.beginMatchFromLastOffset()
				}
			}
		case stateMatch:
			var newOffset bool
			if newOffset, err = d.readBit(); err == nil {
				if newOffset {
					err = d.beginMatchFromNewOffset()
				} else {
					err = d.beginLiterals()
				}
			}
		default:
			panic("unreachable")
		}
		if err != nil {
			return err
		}
	}
	if d.outputIndex != len(d.output) {
		return fmt.Errorf("the streams did not fill the output")
	}
	return nil
}

func (d *decompressor) beginLiterals() error {
	length, err := d.readInterlacedEliasGamma()
	if err != nil {
		return err
	}
	if length <= 0 {
		return fmt.Errorf("invalid literal length")
	}
	for i := 0; i < int(length)*d.unit; i++ {
		if d.literalIndex >= len(d.literal) {
			return fmt.Errorf("truncated literal stream")
		}
		if d.outputIndex >= len(d.output) {
			return fmt.Errorf("the streams overran the output")
		}
		d.output[d.outputIndex] = d.literal[d.literalIndex]
		d.outputIndex++
		d.literalIndex++
	}
	d.state = stateLiterals
	return nil
}

func (d *decompressor) beginMatchFromLastOffset() error {
	length, err := d.readInterlacedEliasGamma()
	if err != nil {
		return err
	}
	if err := d.copyUnits(length); err != nil {
		return err
	}
	d.state = stateMatch
	return nil
}

func (d *decompressor) beginMatchFromNewOffset() error {
	// Two class bits: byte or word, then which bank - or, for a word, the one
	// code that means the stream is over.
	isByte, err := d.readBit()
	if err != nil {
		return err
	}
	if isByte {
		high, err := d.readBit()
		if err != nil {
			return err
		}
		bank := 0
		if high {
			bank = 1
		}
		if d.byteOffsetIndex >= len(d.byteOffsets) {
			return fmt.Errorf("truncated byte offsets")
		}
		d.lastOffset = bank*256 + 256 - int(d.byteOffsets[d.byteOffsetIndex])
		d.byteOffsetIndex++
	} else {
		end, err := d.readBit()
		if err != nil {
			return err
		}
		if end {
			d.state = stateDone
			return nil
		}
		if d.wordOffsetIndex+2 > len(d.wordOffsets) {
			return fmt.Errorf("truncated word offsets")
		}
		scaled := int(d.wordOffsets[d.wordOffsetIndex])<<8 |
			int(d.wordOffsets[d.wordOffsetIndex+1])
		d.wordOffsetIndex += 2
		d.lastOffset = ((1 << 16) - scaled) / d.unit // stored as -offset*unit
	}
	if d.lastOffset <= 0 {
		return fmt.Errorf("an offset must reach back at least one unit")
	}
	if d.lastOffset > d.offsetLimit {
		return fmt.Errorf("offset %d units reaches past the %d-unit limit",
			d.lastOffset, d.offsetLimit)
	}
	length, err := d.readInterlacedEliasGamma()
	if err != nil {
		return err
	}
	if err := d.copyUnits(length + 1); err != nil {
		return err
	}
	d.state = stateMatch
	return nil
}

// copyUnits copies length units from lastOffset units back.
func (d *decompressor) copyUnits(length int32) error {
	if length <= 0 {
		return fmt.Errorf("invalid match length")
	}
	distance := d.lastOffset * d.unit
	if distance > d.outputIndex {
		return fmt.Errorf("match reaches before the output")
	}
	for i := 0; i < int(length)*d.unit; i++ {
		if d.outputIndex >= len(d.output) {
			return fmt.Errorf("the streams overran the output")
		}
		d.output[d.outputIndex] = d.output[d.outputIndex-distance]
		d.outputIndex++
	}
	return nil
}

func (d *decompressor) readControl() (int, error) {
	if d.controlIndex >= len(d.control) {
		return 0, fmt.Errorf("truncated control stream")
	}
	value := int(d.control[d.controlIndex])
	d.controlIndex++
	return value, nil
}

func (d *decompressor) readBit() (bool, error) {
	d.bitMask >>= 1
	if d.bitMask == 0 {
		d.bitMask = 128
		value, err := d.readControl()
		if err != nil {
			return false, err
		}
		d.bitValue = value
	}
	return d.bitValue&d.bitMask != 0, nil
}

// readInterlacedEliasGamma reads one value, a bit of payload after every bit
// that says the value goes on. The count is held in 32 bits, as the other two
// trees hold it, so a stream of set bits wraps to a length the caller refuses
// rather than growing without end.
func (d *decompressor) readInterlacedEliasGamma() (int32, error) {
	value := int32(1)
	for {
		more, err := d.readBit()
		if err != nil {
			return 0, err
		}
		if !more {
			return value, nil
		}
		bit, err := d.readBit()
		if err != nil {
			return 0, err
		}
		payload := int32(0)
		if bit {
			payload = 1
		}
		value = value<<1 | payload
	}
}
