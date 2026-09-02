// Command st4 compresses a file into an ST4 container.
package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/odipar/ymx/internal/pack"
	"github.com/odipar/ymx/internal/st4"
)

const banner = "ST4: aligned split-stream packer v7.0 by Robbert van Dalen," +
	" based on ZX1 v1.5 by Einar Saukas"

const usageText = "Usage: st4 [-f] [-c[S]] [-kK] [-mN] [-lN] [-rR] input [output.st4]\n" +
	"  -f      Force overwrite of output file\n" +
	"  -c      Let a match beyond the -m window copy from the\n" +
	"          literal stream; needs a decoder built with copies\n" +
	"  -cS     The same, searching for S seconds for a better parse\n" +
	"  -kK     Unit size: 1, 2 or 4 bytes (default 1). Lengths and\n" +
	"          offsets count units, so the output is padded to a\n" +
	"          whole number of them\n" +
	"  -mN     Limit back-references to N units\n" +
	"  -lN     Split matches so no operation exceeds N units\n" +
	"  -rR     Loop: after the last unit, the output continues\n" +
	"          from unit R, forever"

func main() {
	fmt.Println(banner)

	unit := 1
	offsetLimit := st4.MaxOffset
	maxOp := st4.MaxOp
	repeatIndex := -1
	copies := false
	search := 0.0
	force := false
	args := os.Args[1:]
	for len(args) > 0 && strings.HasPrefix(args[0], "-") {
		a := args[0]
		switch {
		case a == "-f":
			force = true
		case a == "-c":
			copies = true
		case strings.HasPrefix(a, "-c"):
			copies = true
			search = float64(number(a[2:], false))
		case strings.HasPrefix(a, "-k"):
			unit = number(a[2:], false)
		case strings.HasPrefix(a, "-m"):
			offsetLimit = number(a[2:], false)
		case strings.HasPrefix(a, "-l"):
			maxOp = number(a[2:], false)
		case strings.HasPrefix(a, "-r"):
			repeatIndex = number(a[2:], true) // -r0 loops it all
		default:
			fail("Invalid parameter " + a)
		}
		args = args[1:]
	}
	if len(args) < 1 || len(args) > 2 {
		usage()
	}
	inputName := args[0]
	outputName := inputName + ".st4"
	if len(args) == 2 {
		outputName = args[1]
	}

	if problem := st4.CheckUnit(unit); problem != "" {
		fail(problem)
	}
	// A word offset is stored scaled to bytes, so the window is a byte
	// figure: 32512 units at k=4 would not fit the word.
	if limit := st4.MaxOffsetUnits(unit); offsetLimit > limit {
		offsetLimit = limit
	}

	input, err := os.ReadFile(inputName)
	if err != nil {
		fail("Cannot access input file " + inputName)
	}
	if len(input) == 0 {
		fail("Empty input file " + inputName)
	}
	if !force {
		if _, err := os.Stat(outputName); err == nil {
			fail("Already existing output file " + outputName)
		}
	}

	units := st4.Split(input, unit)
	if repeatIndex >= len(units) {
		fail(fmt.Sprintf("-r%d is not a unit of the input, which is %d units",
			repeatIndex, len(units)))
	}
	var result st4.Result
	window := offsetLimit
	if repeatIndex >= 0 && len(units)-repeatIndex > offsetLimit {
		// The loop is longer than the window, so no match reaches across it
		// and the caller replays the stream from the state it saved at the
		// loop point. The loop is parsed on its own, so every pass sees the
		// same history.
		intro := units[:repeatIndex]
		loop := units[repeatIndex:]
		var introParse *st4.Block
		if len(intro) > 0 {
			introParse = parse(intro, unit, offsetLimit, maxOp, copies, search)
		}
		result = st4.CompressRewinding(introParse,
			parse(loop, unit, offsetLimit, maxOp, copies, search),
			units, unit, maxOp, repeatIndex, window)
	} else {
		// The loop fits the window: the end is an endless match back to the
		// loop point.
		result = st4.CompressRepeating(
			parse(units, unit, offsetLimit, maxOp, copies, search), units,
			unit, maxOp, repeatIndex, window)
	}

	if err := os.WriteFile(outputName, result.Container(), 0o644); err != nil {
		fail("Cannot write output file " + outputName)
	}

	padded := st4.PaddedLength(len(input), unit)
	note := ""
	if padded != len(input) {
		note = fmt.Sprintf(" padded to %d", padded)
	}
	tail := ""
	if result.Copies != 0 {
		tail += fmt.Sprintf(", %d copies from the literal stream", result.Copies)
	}
	if repeatIndex >= 0 {
		tail += fmt.Sprintf(", loops from unit %d", repeatIndex)
		if result.RewindIndex >= 0 {
			tail += " by rewind"
		}
	}
	fmt.Printf("Packed %d bytes%s into %d (%.1f%%): A %d, B %d, C %d, D %d,"+
		" %d operations%s\n", len(input), note, result.PackedSize(),
		100.0*float64(result.PackedSize())/float64(len(input)),
		len(result.Control), len(result.Literal), len(result.ByteOffsets),
		len(result.WordOffsets), result.Operations, tail)
	if result.RewindIndex >= 0 {
		fmt.Printf("The loop is longer than the -m%d window, so the decoder"+
			" cannot loop it alone: save its state at unit %d and restore it"+
			" at unit %d, every pass\n", offsetLimit, repeatIndex, len(units))
	}
	if result.LongestOp > maxOp {
		fmt.Printf("Warning: longest operation is %d units, over the -l%d"+
			" limit: a literal run, which the format cannot split\n",
			result.LongestOp, maxOp)
	}
}

// parse is the parse: the event-driven optimizer, or with -c the opening
// passes of the search that copies from the literal stream, and with
// seconds the search from there.
func parse(units []uint32, unit, window, maxOp int, copies bool,
	seconds float64) *st4.Block {
	if !copies {
		return st4.OptimizeEvents(units, unit, window, true)
	}
	return st4.OptimizeCopies(units, unit, window, maxOp, seconds, true)
}

// number reads a numeric flag value, stopping with the reason it cannot be
// used. A unit size, an offset window, an operation length and a search
// time each count from one, so zero fails there as a negative does; a loop
// point may be zero.
func number(text string, zeroAllowed bool) int {
	value, problem := pack.Number(text, zeroAllowed)
	if problem != "" {
		fail(problem)
	}
	return value
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, "Error: "+message)
	os.Exit(1)
}

func usage() {
	fmt.Fprintln(os.Stderr, usageText)
	os.Exit(1)
}
