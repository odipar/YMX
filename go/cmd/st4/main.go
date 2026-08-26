// Command st4 compresses a file into an ST4 container.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/odipar/ymx/internal/st4"
)

const usage = "usage: st4 [-kK] [-lN] input output"

func main() {
	unit := 1
	maxOp := st4.MaxOp
	args := os.Args[1:]
	for len(args) > 0 && len(args[0]) > 1 && args[0][0] == '-' {
		flag, rest := args[0][1], args[0][2:]
		value, err := strconv.Atoi(rest)
		if err != nil {
			fail(usage)
		}
		switch flag {
		case 'k':
			unit = value
		case 'l':
			maxOp = value
		default:
			fail(usage)
		}
		args = args[1:]
	}
	if len(args) != 2 {
		fail(usage)
	}
	if problem := st4.CheckUnit(unit); problem != "" {
		fail("st4: " + problem)
	}
	input, err := os.ReadFile(args[0])
	if err != nil {
		fail("st4: cannot read " + args[0])
	}
	units := st4.Split(input, unit)
	optimal := st4.OptimizeEvents(units, unit, st4.MaxOffsetUnits(unit), true)
	result := st4.Compress(optimal, units, unit, maxOp)
	if err := os.WriteFile(args[1], result.Container(), 0o644); err != nil {
		fail("st4: cannot write " + args[1])
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
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
