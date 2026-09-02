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

// decompressor is the reference decoder, which the 68000 decoders have to
// agree with: ZX1's state machine with four changes. Literals come from
// stream B and offsets from stream C or D by width; lengths and offsets
// count units; the end marker's extra bit turns the end into an endless
// match, the repeat; and an offset beyond the window copies offset - window
// units from behind the literal read pointer, which stays where it is, and
// advances the offset by what was copied. A copy that would not stay behind
// the pointer is rejected.
type decompressor struct {
	window          int
	rewindAt        int
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
	repeatIndex     int
	state           decodeState
}

// Decoded is the output and how the stream ended: the loop point R of a
// repeating stream, which decodes as units[0..R) units[R..O) forever, or -1.
// A repeating stream decodes to any size from one pass up.
type Decoded struct {
	Output      []byte
	RepeatIndex int
}

// Decompress rebuilds size bytes from the four streams, reaching as far back
// as the unit size carries.
func Decompress(control, literal, byteOffsets, wordOffsets []byte,
	unit, size int) ([]byte, error) {
	return DecompressWindow(control, literal, byteOffsets, wordOffsets, unit,
		size, MaxOffsetUnits(unit))
}

// DecompressWindow is Decompress at the window the stream was packed for: a
// match reaches at most window units back, so a stream that decodes is safe
// for a ring of that many units, and an offset beyond it copies from the
// literal stream.
func DecompressWindow(control, literal, byteOffsets, wordOffsets []byte,
	unit, size, window int) ([]byte, error) {
	decoded, err := Decode(control, literal, byteOffsets, wordOffsets, unit,
		size, window, NoRewind)
	if err != nil {
		return nil, err
	}
	return decoded.Output, nil
}

// Decode is DecompressWindow also reporting whether the stream repeats, and
// holding the stream to its rewind point: from rewindAt bytes on, no match
// reaches before it, so the loop replays from the state saved there and
// every pass sees the same history. A stream that reaches before it would
// loop wrongly on the 68000, and is rejected here; NoRewind holds nothing.
func Decode(control, literal, byteOffsets, wordOffsets []byte,
	unit, size, window, rewindAt int) (Decoded, error) {
	if !IsUnitSize(unit) {
		return Decoded{}, fmt.Errorf("unit size must be 1, 2 or 4")
	}
	if size < 0 || size%unit != 0 {
		return Decoded{}, fmt.Errorf("output size must be a whole number of units")
	}
	d := &decompressor{
		window:      window,
		rewindAt:    rewindAt,
		control:     control,
		literal:     literal,
		byteOffsets: byteOffsets,
		wordOffsets: wordOffsets,
		output:      make([]byte, size),
		unit:        unit,
		lastOffset:  InitialOffset,
		repeatIndex: -1,
		state:       stateStart,
	}
	if err := d.run(); err != nil {
		return Decoded{}, err
	}
	return Decoded{Output: d.output, RepeatIndex: d.repeatIndex}, nil
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
	if err := d.copyLength(length); err != nil {
		return err
	}
	d.state = stateMatch
	return nil
}

func (d *decompressor) beginMatchFromNewOffset() error {
	// Two class bits: byte or word, then the bank, or for a word the end of
	// the stream.
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
			return d.endOrRepeat()
		}
		d.lastOffset, err = d.readWordOffset()
		if err != nil {
			return err
		}
	}
	if d.lastOffset <= 0 {
		return fmt.Errorf("an offset must reach back at least one unit")
	}
	length, err := d.readInterlacedEliasGamma()
	if err != nil {
		return err
	}
	if err := d.copyLength(length + 1); err != nil {
		return err
	}
	d.state = stateMatch
	return nil
}

// copyLength copies length units from the offset the stream holds: from the
// output within the window, from the literal stream beyond it.
func (d *decompressor) copyLength(length int32) error {
	if d.lastOffset > d.window {
		return d.copyFromLiterals(length)
	}
	return d.copyUnits(length)
}

// copyFromLiterals copies length units from the literal stream, lastOffset -
// window units behind the read pointer, which stays where it is, and
// advances the offset by what it copied.
func (d *decompressor) copyFromLiterals(length int32) error {
	back := d.lastOffset - d.window
	if back <= int(length) {
		return fmt.Errorf("a copy of %d units from %d units back does not stay"+
			" behind the literal read pointer", length, back)
	}
	source := d.literalIndex - back*d.unit
	if source < 0 {
		return fmt.Errorf("a copy reaches before the literal stream")
	}
	for i := 0; i < int(length)*d.unit; i++ {
		if d.outputIndex >= len(d.output) {
			return fmt.Errorf("the streams overran the output")
		}
		d.output[d.outputIndex] = d.literal[source+i]
		d.outputIndex++
	}
	d.lastOffset -= int(length)
	return nil
}

// endOrRepeat reads the end code's extra bit: a plain end, or the repeat,
// one last word offset from stream D matched until the output is full. The
// 68000 decoders run the same match 65535 units at a time, re-armed forever.
func (d *decompressor) endOrRepeat() error {
	repeat, err := d.readBit()
	if err != nil {
		return err
	}
	if repeat {
		// Stream D holds the distance back to the loop point.
		distance, err := d.readWordOffset()
		if err != nil {
			return err
		}
		if distance <= 0 {
			return fmt.Errorf("a repeat must reach back at least one unit")
		}
		if distance > d.window {
			return fmt.Errorf("the loop distance %d units reaches past the"+
				" %d-unit window", distance, d.window)
		}
		d.repeatIndex = d.outputIndex/d.unit - distance
		if d.repeatIndex < 0 {
			return fmt.Errorf("the loop point must be a unit of the stream")
		}
		d.lastOffset = distance
		remaining := (len(d.output) - d.outputIndex) / d.unit
		if remaining > 0 {
			if err := d.copyUnits(int32(remaining)); err != nil {
				return err
			}
		}
	}
	d.state = stateDone
	return nil
}

func (d *decompressor) readWordOffset() (int, error) {
	if d.wordOffsetIndex+2 > len(d.wordOffsets) {
		return 0, fmt.Errorf("truncated word offsets")
	}
	scaled := int(d.wordOffsets[d.wordOffsetIndex])<<8 |
		int(d.wordOffsets[d.wordOffsetIndex+1])
	d.wordOffsetIndex += 2
	return ((1 << 16) - scaled) / d.unit, nil // stored as -offset*unit
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
		// With no rewind point this never fires: -1 is below every source.
		if d.outputIndex >= d.rewindAt && d.outputIndex-distance < d.rewindAt {
			return fmt.Errorf("the loop reaches before the rewind point %d at"+
				" byte %d", d.rewindAt, d.outputIndex)
		}
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
