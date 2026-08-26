// Command ymx packs a YM register dump into a .ymx file.
package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/odipar/ymx/internal/ym"
	"github.com/odipar/ymx/internal/ymx"
)

const usage = "usage: ymx [-f] [-o] [-nN] [-cC] [-kK] [-drumhzH] input.ym" +
	" [output.ymx]"

func main() {
	force := false
	startsOver := true
	ring := ymx.DefaultRingSize
	chunk := ymx.DefaultChunk
	unit := 0 // 0 until chosen: -kK, or the tune's own shape
	drumHz := 0
	timerMap := ymx.DefaultTimers

	args := os.Args[1:]
	for len(args) > 0 && strings.HasPrefix(args[0], "-") {
		a := args[0]
		switch {
		case a == "-f":
			force = true
		case a == "-o":
			startsOver = false
		case strings.HasPrefix(a, "-drumhz"):
			drumHz = number(a[len("-drumhz"):])
		case strings.HasPrefix(a, "-timers"):
			timerMap = timers(a[len("-timers"):])
		case strings.HasPrefix(a, "-n"):
			ring = number(a[2:])
		case strings.HasPrefix(a, "-c"):
			chunk = number(a[2:])
		case strings.HasPrefix(a, "-k"):
			unit = number(a[2:])
		default:
			fail(usage)
		}
		args = args[1:]
	}
	if len(args) < 1 || len(args) > 2 {
		fail(usage)
	}
	inputName := args[0]
	outputName := strings.TrimSuffix(inputName, ".ym") + ".ymx"
	if len(args) == 2 {
		outputName = args[1]
	}

	input, err := os.ReadFile(inputName)
	if err != nil {
		fail("Cannot access input file " + inputName)
	}
	if !force {
		if _, err := os.Stat(outputName); err == nil {
			fail("Already existing output file " + outputName)
		}
	}

	song, err := ym.Read(input)
	if err != nil {
		fail(inputName + ": " + err.Error())
	}

	// The reader and the engine keep their own vocabularies, so the dump
	// crosses into the engine's here and nowhere else.
	crossed := &ymx.Song{
		Format: song.Format, Frames: song.Frames, PlayerHz: song.PlayerHz,
		MasterClock: song.MasterClock, LoopFrame: song.LoopFrame,
		Attributes: song.Attributes, Drums: song.Drums, Name: song.Name,
		Author: song.Author, Comment: song.Comment, Registers: song.Registers,
	}
	effects := ymx.ExtractUpTo(crossed, drumHz)
	tune, err := ymx.BuildTuneOver(crossed, effects)
	if err != nil {
		fail(inputName + ": " + err.Error())
	}

	// The unit size, where the caller named none: two where a frame near the
	// end is safe to duplicate, and one where none is. A duplicate frame is
	// safe when it neither restarts the envelope nor starts a drum.
	safe := safeToDuplicate(crossed)
	switch {
	case unit == 0 && chunk%2 == 0:
		if padded := pad(tune, 2, safe); padded != nil {
			tune, unit = padded, 2
		} else {
			unit = 1
			fmt.Println("Packing at -k1: this tune's length is not a whole" +
				" number of 2-byte units, and no frame near the end is safe" +
				" to duplicate")
		}
	case unit == 0:
		unit = 1
	case unit > 1:
		if padded := pad(tune, unit, safe); padded != nil {
			tune = padded
		}
	}

	result, err := ymx.EncodeOnTimers(tune, ring, chunk, startsOver, unit,
		timerMap)
	if err != nil {
		fail(err.Error())
	}
	if err := os.WriteFile(outputName, result.File, 0o644); err != nil {
		fail("Cannot write output file " + outputName)
	}

	raw := result.Tune.Frames * ymx.Streams
	fmt.Printf("%d frames at %d Hz, %d rings of %d bytes, %d per call\n",
		result.Tune.Frames, result.Tune.FrameRate, ymx.Streams,
		result.RingSize, result.Chunk)
	fmt.Println(result.StartingOver())
	fmt.Printf("Packed %d register bytes into %d (%.1f%%), file %d bytes\n",
		raw, result.PackedSize(),
		100.0*float64(result.PackedSize())/float64(raw), len(result.File))
}

// pad stretches the tune to a whole number of units, reporting what it
// duplicated. It gives nil where no frame near the end is safe.
func pad(tune *ymx.Tune, unit int, safe func(int) bool) *ymx.Tune {
	padded := tune.PadToUnit(unit, safe)
	if padded != nil && padded != tune {
		added := padded.Frames - tune.Frames
		plural := "s"
		if added == 1 {
			plural = ""
		}
		fmt.Printf("Padded %d frame%s (duplicates of safe frames) so the"+
			" length is whole %d-byte units\n", added, plural, unit)
	}
	return padded
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

func number(text string) int {
	value, err := strconv.Atoi(text)
	if err != nil {
		fail(usage)
	}
	return value
}

// timers reads the four-letter map naming the MFP timer each channel runs on.
func timers(text string) int {
	if len(text) == 0 || len(text) > ymx.Channels {
		fail("ymx: -timers takes one letter per channel, A B C or D")
	}
	assignments := ymx.DefaultTimers
	for channel, letter := range strings.ToUpper(text) {
		timer := strings.IndexRune("ABCD", letter)
		if timer < 0 {
			fail("ymx: -timers takes one letter per channel, A B C or D")
		}
		assignments &^= 3 << (2 * channel)
		assignments |= timer << (2 * channel)
	}
	return assignments
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
