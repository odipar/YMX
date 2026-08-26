package ymx

import "fmt"

// Tune is a tune as the engine has one: the streams to play, the sources they
// play from, and the few numbers that say how fast it runs. This is the
// handover point - a front end reads its own format and stops here, at a
// record with no format in it.
//
// Registers[r][frame] is the frame stream targeting register r, R0 to R13.
// Codes[c][frame] and Counts[c][frame] are timer channel c's timer stream: a
// code carries the kind in bits 7-6, the voice plus one in bits 5-4, the
// prescaler index in bits 2-0; bit 3 is a front end's trigger bit. Shapes is
// the envelope shape a retrigger stream would restart on each frame; Samples
// and SampleLoops are the PCM streams' sources and where each goes back to.
// Loops is what the source says the end of the tune does and LoopFrame the
// frame it starts over from, 0 where the source gives none; whether the frame
// survives into the file is the packer's answer - see ResolveLoopFrame.
type Tune struct {
	Frames      int
	FrameRate   int
	MasterClock int64
	Loops       bool
	LoopFrame   int
	Registers   [][]byte
	Codes       [][]byte
	Counts      [][]byte
	Shapes      []byte
	Samples     [][]byte
	SampleLoops []int
	Semantics   Semantics
	Name        string
	Author      string
	Comment     string
	Notes       []string
}

// Under is the same tune under other semantics: how a caller says what no file
// records, without every layer carrying a flag.
func (t *Tune) Under(semantics Semantics) *Tune {
	under := *t
	under.Semantics = semantics
	return &under
}

// StartingOverAt is the same tune starting over from another frame: how a CLI
// replaces the frame the source gives.
func (t *Tune) StartingOverAt(frame int) *Tune {
	moved := *t
	moved.LoopFrame = frame
	return &moved
}

// The kind a code names in bits 7-6.
const (
	KindToggle    = 0x00
	KindPcm       = 0x40
	KindCurve     = 0x80
	KindRetrigger = 0xC0
)

// MfpClock is the MFP's own clock in Hz; a tune's MasterClock is the YM2149's.
const MfpClock = 2457600

// Prescalers is how many prescaler indices a code byte's bits 2-0 name.
const Prescalers = 8

// Index 0 is the MFP's stopped state, so a code selecting it starts nothing.
var prescalerTable = [Prescalers]int{0, 4, 10, 16, 50, 64, 100, 200}

// Prescaler is the divisor a prescaler index selects.
func Prescaler(index int) int {
	return prescalerTable[index]
}

// padSearch is how far a safe frame is looked for either side of a boundary
// that needs padding.
const padSearch = 64

// NewTune builds a tune, widening the timer streams to the format's four
// channels and validating what the encoder relies on. The fields arrive in a
// Tune value rather than a row of positional arguments; the widening and the
// checks are the ones the other trees make, in the same order.
func NewTune(t Tune) (*Tune, error) {
	if len(t.Registers) != RegisterStreams {
		return nil, fmt.Errorf("a tune carries %d frame streams, R0 to R13, not"+
			" %d: the I/O ports are not chip state",
			RegisterStreams, len(t.Registers))
	}
	codes, err := widen(t.Codes, t.Frames)
	if err != nil {
		return nil, err
	}
	counts, err := widen(t.Counts, t.Frames)
	if err != nil {
		return nil, err
	}
	if len(t.Shapes) != t.Frames {
		return nil, fmt.Errorf("a tune carries one envelope shape a frame, not"+
			" %d for %d", len(t.Shapes), t.Frames)
	}
	if t.LoopFrame < 0 || t.LoopFrame >= t.Frames {
		return nil, fmt.Errorf("a tune of %d frames cannot start over at frame"+
			" %d; a source that gives no frame gives 0", t.Frames, t.LoopFrame)
	}
	// A code names a kind in bits 7-6 and a voice PLUS ONE in bits 5-4, so
	// zero voice bits mean the channel is idle and the whole byte must be 0; a
	// voiceless kind would compile to a negative voice that floods the opcode
	// above it.
	for channel := 0; channel < len(codes); channel++ {
		for frame := 0; frame < t.Frames; frame++ {
			code := int(codes[channel][frame])
			if code != 0 && code&0x30 == 0 {
				return nil, fmt.Errorf("channel %d carries the code $%02X on"+
					" frame %d, which names a kind but no voice; an idle"+
					" channel's code is 0", channel, code, frame)
			}
		}
	}
	if len(t.SampleLoops) != len(t.Samples) {
		return nil, fmt.Errorf("a tune carries one loop point per sample, not"+
			" %d for %d", len(t.SampleLoops), len(t.Samples))
	}
	for sample := 0; sample < len(t.Samples); sample++ {
		loop := t.SampleLoops[sample]
		if loop != SampleOneShot && loop >= len(t.Samples[sample]) {
			return nil, fmt.Errorf("sample %d loops from %d, which is past its"+
				" %d bytes; a sample that does not loop says %d",
				sample, loop, len(t.Samples[sample]), SampleOneShot)
		}
	}
	built := t
	built.Codes = codes
	built.Counts = counts
	built.Notes = append([]string(nil), t.Notes...)
	return &built, nil
}

// PadToUnit pads the tune so its length is a whole number of units, by
// duplicating a frame the front end says is safe to duplicate - every stream
// stretched at the same frame, since a frame is a column across all of them.
// The loop frame moves with the frame it points at: the duplicates go in after
// atEnd, so a loop frame past that one sits endPad frames further along.
// Returns the tune itself when the length fits, or nil when no safe frame
// exists in the window.
func (t *Tune) PadToUnit(unit int, safeToDuplicate func(int) bool) *Tune {
	endPad := (unit - t.Frames%unit) % unit
	if endPad == 0 {
		return t
	}
	atEnd := safeFrame(safeToDuplicate, t.Frames-1, t.Frames-padSearch)
	if atEnd < 0 {
		return nil
	}
	pad := padding{frames: t.Frames, atEnd: atEnd, endPad: endPad,
		total: t.Frames + endPad}
	padded := *t
	padded.Frames = pad.total
	padded.LoopFrame = pad.rebase(t.LoopFrame)
	padded.Registers = pad.stretchAll(t.Registers)
	padded.Codes = pad.stretchAll(t.Codes)
	padded.Counts = pad.stretchAll(t.Counts)
	padded.Shapes = pad.stretch(t.Shapes)
	return &padded
}

func safeFrame(safe func(int) bool, from, floor int) int {
	stop := floor
	if from-(padSearch-1) > stop {
		stop = from - (padSearch - 1)
	}
	for frame := from; frame >= stop; frame-- {
		if safe(frame) {
			return frame
		}
	}
	return -1
}

// padding is which frame is duplicated and how often - one plan, applied to
// every stream.
type padding struct {
	frames int
	atEnd  int
	endPad int
	total  int
}

// rebase is where a frame of the unpadded tune sits in the padded one.
func (p padding) rebase(frame int) int {
	if frame <= p.atEnd {
		return frame
	}
	return frame + p.endPad
}

func (p padding) stretch(values []byte) []byte {
	return p.stretchAll([][]byte{values})[0]
}

func (p padding) stretchAll(streams [][]byte) [][]byte {
	out := make([][]byte, len(streams))
	for stream := 0; stream < len(streams); stream++ {
		values := streams[stream]
		padded := make([]byte, p.total)
		at := 0
		for frame := 0; frame < p.frames; frame++ {
			padded[at] = values[frame]
			at++
			if frame == p.atEnd {
				for copies := 0; copies < p.endPad; copies++ {
					padded[at] = values[frame]
					at++
				}
			}
		}
		out[stream] = padded
	}
	return out
}

func widen(streams [][]byte, frames int) ([][]byte, error) {
	if len(streams) > Channels {
		return nil, fmt.Errorf("a tune offers %d timer channels and the format"+
			" carries %d; widening cannot drop the rest quietly, so a front end"+
			" with more to say has to say it to a format that has room",
			len(streams), Channels)
	}
	if len(streams) == Channels {
		return streams, nil
	}
	widened := make([][]byte, Channels)
	copy(widened, streams)
	for channel := len(streams); channel < Channels; channel++ {
		widened[channel] = make([]byte, frames)
	}
	return widened, nil
}
