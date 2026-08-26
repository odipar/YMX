// Command dst4 unpacks an ST4 container. The output is padded to a whole
// number of units, as the format stores it.
package main

import (
	"fmt"
	"os"

	"github.com/odipar/ymx/internal/st4"
)

const usage = "usage: dst4 input.st4 output"

func main() {
	args := os.Args[1:]
	if len(args) != 2 {
		fail(usage)
	}
	file, err := os.ReadFile(args[0])
	if err != nil {
		fail("dst4: cannot read " + args[0])
	}
	container, err := st4.Read(file)
	if err != nil {
		fail("dst4: " + err.Error() + ": " + args[0])
	}
	// A malformed stream trips a descriptive check; the decoder does not
	// validate its input, so report rather than continue on corrupt data.
	output, err := st4.Decompress(container.Control, container.Literal,
		container.ByteOffsets, container.WordOffsets, container.Unit,
		container.Size)
	if err != nil {
		fail("dst4: corrupted or truncated ST4 data in " + args[0] + ": " +
			err.Error())
	}
	if err := os.WriteFile(args[1], output, 0o644); err != nil {
		fail("dst4: cannot write " + args[1])
	}
	note := ""
	if container.Unit != 1 {
		note = " (a whole number of units)"
	}
	fmt.Printf("File decompressed from %d to %d bytes, k=%d%s!\n", len(file),
		len(output), container.Unit, note)
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
