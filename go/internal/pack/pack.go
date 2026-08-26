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
	Ring      int
	Chunk     int
	Unit      int
	Loops     bool
	DrumHz    int
	TimerMap  int
	SidResume bool

	// Progress draws the parse's percentage where something is watching.
	Progress bool
}

// Defaults are the shape the packer uses where the caller names none.
func Defaults() Options {
	return Options{
		Ring:     ymx.DefaultRingSize,
		Chunk:    ymx.DefaultChunk,
		Unit:     0,
		Loops:    true,
		TimerMap: ymx.DefaultTimers,
		Progress: true,
	}
}

// Packed is the encoded file and what the road to it is worth reporting.
type Packed struct {
	Result *ymx.EncodeResult
	Song   *ymx.Song
	Notes  []string
}

// Pack packs one dump.
func Pack(input []byte, o Options) (*Packed, error) {
	song, err := ym.Read(input)
	if err != nil {
		return nil, err
	}

	// The reader and the engine keep their own vocabularies; the dump
	// crosses into the engine's here and nowhere else.
	crossed := &ymx.Song{
		Format: song.Format, Frames: song.Frames, PlayerHz: song.PlayerHz,
		MasterClock: song.MasterClock, LoopFrame: song.LoopFrame,
		Attributes: song.Attributes, Drums: song.Drums, Name: song.Name,
		Author: song.Author, Comment: song.Comment, Registers: song.Registers,
	}
	effects := ymx.ExtractUpTo(crossed, o.DrumHz)
	tune, err := ymx.BuildTuneOver(crossed, effects)
	if err != nil {
		return nil, err
	}
	if o.SidResume {
		tune = tune.Under(tune.Semantics.Resuming())
	}

	var notes []string
	unit := o.Unit
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

	result, err := ymx.EncodeOnTimers(tune, o.Ring, o.Chunk, o.Loops,
		o.Progress, unit, o.TimerMap)
	if err != nil {
		return nil, err
	}
	return &Packed{Result: result, Song: crossed,
		Notes: append(notes, result.Notes...)}, nil
}

func appendNote(notes []string, note string) []string {
	if note == "" {
		return notes
	}
	return append(notes, note)
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
