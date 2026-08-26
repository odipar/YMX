// Command dst4 unpacks an ST4 container. The output is padded to a whole
// number of units, as the format stores it.
package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/odipar/ymx/internal/st4"
)

const banner = "DST4: aligned split-stream unpacker v4.0 by Robbert van" +
	" Dalen, based on ZX1 v1.5 by Einar Saukas"

const usageText = "Usage: dst4 [-f] input.st4 [output]\n" +
	"  -f      Force overwrite of output file\n" +
	"The output is padded to a whole number of units, as the format" +
	" stores it."

func main() {
	fmt.Println(banner)

	force := false
	args := os.Args[1:]
	for len(args) > 0 && strings.HasPrefix(args[0], "-") {
		if args[0] != "-f" {
			fail("Invalid parameter " + args[0])
		}
		force = true
		args = args[1:]
	}
	if len(args) < 1 || len(args) > 2 {
		usage()
	}
	inputName := args[0]
	var outputName string
	if len(args) == 2 {
		outputName = args[1]
	} else if len(inputName) > 4 && strings.HasSuffix(inputName, ".st4") {
		outputName = strings.TrimSuffix(inputName, ".st4")
	} else {
		fail("Cannot infer output filename")
	}

	file, err := os.ReadFile(inputName)
	if err != nil {
		fail("Cannot access input file " + inputName)
	}
	if !force {
		if _, err := os.Stat(outputName); err == nil {
			fail("Already existing output file " + outputName)
		}
	}

	container, err := st4.Read(file)
	if err != nil {
		fail(err.Error() + ": " + inputName)
	}
	// A malformed stream trips a descriptive check; the decoder does not
	// validate its input, so report rather than continue on corrupt data.
	output, err := st4.Decompress(container.Control, container.Literal,
		container.ByteOffsets, container.WordOffsets, container.Unit,
		container.Size)
	if err != nil {
		fail("Corrupted or truncated ST4 data in " + inputName + ": " +
			err.Error())
	}
	if err := os.WriteFile(outputName, output, 0o644); err != nil {
		fail("Cannot write output file " + outputName)
	}

	note := ""
	if container.Unit != 1 {
		note = " (a whole number of units)"
	}
	fmt.Printf("File decompressed from %d to %d bytes, k=%d%s!\n", len(file),
		len(output), container.Unit, note)
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, "Error: "+message)
	os.Exit(1)
}

func usage() {
	fmt.Fprintln(os.Stderr, usageText)
	os.Exit(1)
}
