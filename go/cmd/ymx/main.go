// Command ymx packs a YM register dump into a .ymx file.
package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/odipar/ymx/internal/pack"
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

	o := pack.Defaults()
	o.Ring, o.Chunk, o.Unit = ring, chunk, unit
	o.Loops, o.DrumHz, o.TimerMap = startsOver, drumHz, timerMap
	packed, err := pack.Pack(input, o)
	if err != nil {
		fail(inputName + ": " + err.Error())
	}
	for _, note := range packed.Notes {
		fmt.Println(note)
	}
	result := packed.Result
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
