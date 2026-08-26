// Command st4 compresses a file into an ST4 container.
package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/odipar/ymx/internal/pack"
	"github.com/odipar/ymx/internal/st4"
)

const banner = "ST4: aligned split-stream packer v4.0 by Robbert van Dalen," +
	" based on ZX1 v1.5 by Einar Saukas"

const usageText = "Usage: st4 [-f] [-kK] [-mN] [-lN] input [output.st4]\n" +
	"  -f      Force overwrite of output file\n" +
	"  -kK     Unit size: 1, 2 or 4 bytes (default 1). Lengths and\n" +
	"          offsets count units, so the output is padded to a\n" +
	"          whole number of them\n" +
	"  -mN     Limit back-references to N units\n" +
	"  -lN     Split matches so no operation exceeds N units"

func main() {
	fmt.Println(banner)

	unit := 1
	offsetLimit := st4.MaxOffset
	maxOp := st4.MaxOp
	force := false
	args := os.Args[1:]
	for len(args) > 0 && strings.HasPrefix(args[0], "-") {
		a := args[0]
		switch {
		case a == "-f":
			force = true
		case strings.HasPrefix(a, "-k"):
			unit = number(a[2:])
		case strings.HasPrefix(a, "-m"):
			offsetLimit = number(a[2:])
		case strings.HasPrefix(a, "-l"):
			maxOp = number(a[2:])
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
	// A word offset is stored pre-scaled, so the window is a byte figure:
	// reaching 32512 units at k=4 would not fit the word it is kept in.
	if limit := st4.MaxOffsetUnits(unit); offsetLimit > limit {
		offsetLimit = limit
	}

	input, err := os.ReadFile(inputName)
	if err != nil {
		fail("Cannot access input file " + inputName)
	}
	if len(input) == 0 {
		// The optimizer reads back from the last unit, and an empty input
		// has none.
		fail("Empty input file " + inputName)
	}
	if !force {
		if _, err := os.Stat(outputName); err == nil {
			fail("Already existing output file " + outputName)
		}
	}

	units := st4.Split(input, unit)
	optimal := st4.OptimizeEvents(units, unit, offsetLimit, true)
	result := st4.Compress(optimal, units, unit, maxOp)
	if err := os.WriteFile(outputName, result.Container(), 0o644); err != nil {
		fail("Cannot write output file " + outputName)
	}

	padded := st4.PaddedLength(len(input), unit)
	note := ""
	if padded != len(input) {
		note = fmt.Sprintf(" padded to %d", padded)
	}
	fmt.Printf("Packed %d bytes%s into %d (%.1f%%): A %d, B %d, C %d, D %d,"+
		" %d operations\n", len(input), note, result.PackedSize(),
		100.0*float64(result.PackedSize())/float64(len(input)),
		len(result.Control), len(result.Literal), len(result.ByteOffsets),
		len(result.WordOffsets), result.Operations)
	if result.LongestOp > maxOp {
		fmt.Printf("Warning: longest operation is %d units, over the -l%d"+
			" limit: a literal run, which the format cannot split\n",
			result.LongestOp, maxOp)
	}
}

// number reads a numeric flag value, stopping with the reason it cannot be
// used. A unit size, an offset window and an operation length each count from
// one, so zero fails here as a negative does.
func number(text string) int {
	value, problem := pack.Number(text, false)
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
