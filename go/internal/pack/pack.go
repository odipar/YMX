// Package pack is the road from a YM dump to a .ymx file: read the dump,
// extract its effects, build the tune, choose a unit size and pad to it, and
// encode. Both the ymx command and ym-to-ymx walk it, so they cannot drift
// apart on the unit a tune is packed at.
package pack

import (
	"fmt"
	"strings"

	"github.com/odipar/ymx/internal/ym"
	"github.com/odipar/ymx/internal/ymx"
)

// Options is what a caller may choose. Unit 0 means the packer picks: two
// where a frame near the end is safe to duplicate, and one where none is.
type Options struct {
	Ring  int
	Chunk int
	Unit  int
	Loops bool
	// DrumHz is the ceiling a sample's tick rate is held to. Zero is not a
	// ceiling but a division by zero, so a caller building Options by hand
	// takes this from Defaults rather than leaving it unset.
	DrumHz    int
	TimerMap  int
	SidResume bool

	// Progress draws the parse's percentage where something is watching.
	Progress bool

	// The trim window: everything before and after is dropped, so a moment
	// deep in a long tune plays immediately. StartFrame of -1 takes the
	// window's start from StartMin and StartSec; EndFrame and FrameCount of
	// -1 leave the end where the dump put it.
	StartMin   int
	StartSec   int
	StartFrame int
	EndFrame   int
	FrameCount int

	// LoopFrame says where the tune starts over, in the frames of the tune
	// being packed, and -1 leaves the header's own.
	LoopFrame int
}

// Defaults are the shape the packer uses where the caller names none.
func Defaults() Options {
	return Options{
		Ring:     ymx.DefaultRingSize,
		Chunk:    ymx.DefaultChunk,
		Unit:     0,
		Loops:    true,
		DrumHz:   ymx.MaxTimerHz,
		TimerMap: ymx.DefaultTimers,
		Progress: true,

		StartFrame: -1,
		EndFrame:   -1,
		FrameCount: -1,
		LoopFrame:  -1,
	}
}

// Packed is the encoded file and what the road to it is worth reporting.
type Packed struct {
	Result *ymx.EncodeResult
	Song   *ymx.Song
	Notes  []string
}

// Cross carries a dump into the engine's vocabulary. The reader and the
// engine keep their own, and this is where the two meet.
func Cross(song *ym.Song) *ymx.Song {
	return &ymx.Song{
		Format: song.Format, Frames: song.Frames, PlayerHz: song.PlayerHz,
		MasterClock: song.MasterClock, LoopFrame: song.LoopFrame,
		Attributes: song.Attributes, Drums: song.Drums, Name: song.Name,
		Author: song.Author, Comment: song.Comment, Registers: song.Registers,
	}
}

// Pack packs one dump.
func Pack(input []byte, o Options) (*Packed, error) {
	song, err := ym.Read(input)
	if err != nil {
		return nil, err
	}

	var notes []string
	crossed := Cross(song)
	if err := trim(crossed, o, &notes); err != nil {
		return nil, err
	}

	effects := ymx.ExtractUpTo(crossed, o.DrumHz)
	tune, err := ymx.BuildTuneOver(crossed, effects)
	if err != nil {
		return nil, err
	}
	if o.SidResume {
		tune = tune.Under(tune.Semantics.Resuming())
	}

	// -lF says where the tune starts over, in the frames of the tune being
	// packed: a trim has already moved what F counts from. The packer
	// answers for the frame either way, whether the header gave it or the
	// caller did.
	if o.LoopFrame >= 0 {
		if o.LoopFrame >= tune.Frames {
			return nil, fmt.Errorf("-l%d is past the tune's %d frames",
				o.LoopFrame, tune.Frames)
		}
		tune = tune.StartingOverAt(o.LoopFrame)
	}

	unit := o.Unit
	unitAsked := unit != 0
	unpadded := tune
	safe := safeToDuplicate(crossed)
	switch {
	case unit == 0 && o.Chunk%2 == 0:
		if padded, note := padTo(tune, 2, safe); padded != nil {
			tune, unit = padded, 2
			notes = appendNote(notes, note)
		} else {
			unit = 1
			notes = append(notes, "Packing at -k1: this tune's length is not"+
				" a whole number of 2-byte units, and no frame near the end"+
				" is safe to duplicate")
		}
	case unit == 0:
		unit = 1
	case unit > 1:
		if padded, note := padTo(tune, unit, safe); padded != nil {
			tune = padded
			notes = appendNote(notes, note)
		}
	}

	// After the trim and the padding, which describe what was packed before
	// the tune itself is described.
	beforeSong := len(notes)
	notes = append(notes, songNotes(song, effects)...)

	result, err := ymx.EncodeOnTimers(tune, o.Ring, o.Chunk, o.Loops,
		o.Progress, unit, o.TimerMap)
	if err != nil {
		return nil, err
	}
	// A section is a whole number of units, so a cut falls on a unit boundary
	// and a loop point that is not one leaves the tune starting over from
	// frame 0. Every frame is a boundary at unit 1: where the unit was not
	// asked for, the packer packs at 1 and keeps the loop point. The pack at
	// the shape asked for has already succeeded, so a shape the encoder
	// rejects here leaves that one standing rather than failing the tune.
	if !unitAsked && unit > 1 && o.Loops && unpadded.LoopFrame > 0 &&
		result.LoopFrame != unpadded.LoopFrame {
		atOne, oneErr := ymx.EncodeOnTimers(unpadded, o.Ring, o.Chunk, o.Loops,
			o.Progress, 1, o.TimerMap)
		// Unit 1 stands only where it starts the tune over where the source
		// says. Where the frame moved for another reason it moves at unit 1
		// too, and the shape asked for is the cheaper of the two.
		if oneErr == nil && atOne.LoopFrame == unpadded.LoopFrame {
			result = atOne
			// The line sits with the padding, before the tune is described,
			// because that is where the packer chose the unit.
			said := fmt.Sprintf("Packing at -k1: frame %d, where this tune"+
				" starts over, is not a whole number of 2-byte units, and a"+
				" section is", result.LoopFrame)
			placed := make([]string, 0, len(notes)+1)
			placed = append(placed, notes[:beforeSong]...)
			placed = append(placed, said)
			placed = append(placed, notes[beforeSong:]...)
			notes = placed
		}
	}
	// The encoder's own notes stay on the result. They describe the file
	// rather than the road to it, and Report places them among the figures
	// they belong with; a command that writes no report prints them itself.
	return &Packed{Result: result, Song: crossed, Notes: notes}, nil
}

func appendNote(notes []string, note string) []string {
	if note == "" {
		return notes
	}
	return append(notes, note)
}

// trim drops everything before and after the window, in place, and says what
// it kept. The loop frame is a frame number, so it rebases on the first kept
// frame; one outside the window is no longer a frame of this tune, and the
// excerpt starts over from its own first frame.
func trim(song *ymx.Song, o Options, notes *[]string) error {
	start := o.StartFrame
	if start < 0 {
		start = (o.StartMin*60 + o.StartSec) * song.PlayerHz
	}
	end := song.Frames
	if o.EndFrame >= 0 && o.EndFrame < end {
		end = o.EndFrame
	}
	if o.FrameCount >= 0 && start+o.FrameCount < end {
		end = start + o.FrameCount
	}
	if start == 0 && end == song.Frames {
		return nil
	}
	if start < 0 || start >= end {
		return fmt.Errorf("Empty trim window: frames %d..%d of %d",
			start, end, song.Frames)
	}
	for r := range song.Registers {
		song.Registers[r] = song.Registers[r][start:end]
	}
	kept := int64(0)
	if song.LoopFrame >= int64(start) && song.LoopFrame < int64(end) {
		kept = song.LoopFrame - int64(start)
	}
	if song.LoopFrame != 0 && kept == 0 {
		*notes = append(*notes, fmt.Sprintf("Frame %d, which the header loops"+
			" from, is outside the kept window: the excerpt starts over from"+
			" its own first frame", song.LoopFrame))
	}
	song.Frames = end - start
	song.LoopFrame = kept
	*notes = append(*notes, fmt.Sprintf("Trimmed to frames %d-%d: %d frames",
		start, end-1, end-start))
	return nil
}

// padTo stretches the tune to a whole number of units, and says what it
// duplicated. It gives nil where no frame near the end is safe.
func padTo(tune *ymx.Tune, unit int, safe func(int) bool) (*ymx.Tune, string) {
	padded := tune.PadToUnit(unit, safe)
	if padded == nil || padded == tune {
		return padded, ""
	}
	added := padded.Frames - tune.Frames
	plural := "s"
	if added == 1 {
		plural = ""
	}
	return padded, fmt.Sprintf("Padded %d frame%s (duplicates of safe frames)"+
		" so the length is whole %d-byte units", added, plural, unit)
}

// safeToDuplicate says which frames may be repeated: one that neither
// restarts the envelope nor starts a drum.
func safeToDuplicate(song *ymx.Song) func(int) bool {
	r := song.Registers
	ym6 := strings.HasPrefix(song.Format, "YM6")
	return func(f int) bool {
		if r[13][f] != 0xFF {
			return false // this frame restarts the envelope
		}
		c1 := int(r[1][f]) & 0xF0
		c3 := int(r[3][f]) & 0xF0
		var drum bool
		if ym6 {
			drum = c1&0xC0 == 0x40 && c1&0x30 != 0 ||
				c3&0xC0 == 0x40 && c3&0x30 != 0
		} else {
			drum = c3&0x30 != 0
		}
		return !drum
	}
}
