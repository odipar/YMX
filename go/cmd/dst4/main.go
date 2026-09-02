// Command dst4 unpacks an ST4 container, and is the reference the 68000
// decoders are checked against. The output is the padded data, a whole
// number of k-byte units, as the format stores it: at -k1 the input, at -k2
// or -k4 up to k-1 bytes longer. For a stream that loops, -rN writes the
// pass and then N-1 repeats of its loop section.
package main

import (
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/odipar/ymx/internal/pack"
	"github.com/odipar/ymx/internal/st4"
)

const banner = "DST4: aligned split-stream unpacker v7.0 by Robbert van" +
	" Dalen, based on ZX1 v1.5 by Einar Saukas"

const usageText = "Usage: dst4 [-f] [-rN] input.st4 [output]\n" +
	"  -f      Force overwrite of output file\n" +
	"  -rN     Play a looping stream's loop N times: the whole pass, then\n" +
	"          N-1 repeats of its loop section (default 1, the pass)\n" +
	"The output is padded to a whole number of units, as the format" +
	" stores it."

func main() {
	fmt.Println(banner)

	force := false
	times := 1
	args := os.Args[1:]
	for len(args) > 0 && strings.HasPrefix(args[0], "-") {
		a := args[0]
		switch {
		case a == "-f":
			force = true
		case strings.HasPrefix(a, "-r"):
			times = number(a[2:])
		default:
			fail("Invalid parameter " + a)
		}
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
	// One whole pass; -r asks for more of it. A malformed stream trips a
	// descriptive check; the decoder does not validate its input, so report
	// rather than continue on corrupt data.
	decoded, err := st4.Decode(container.Control, container.Literal,
		container.ByteOffsets, container.WordOffsets, container.Unit,
		container.Size, container.Window, container.Rewind)
	if err != nil {
		fail("Corrupted or truncated ST4 data in " + inputName + ": " +
			err.Error())
	}
	output, err := played(container, decoded, times)
	var corrupt corruptError
	if errors.As(err, &corrupt) {
		fail("Corrupted or truncated ST4 data in " + inputName + ": " +
			corrupt.Error())
	} else if err != nil {
		fail(err.Error() + ": " + inputName)
	}
	if err := os.WriteFile(outputName, output, 0o644); err != nil {
		fail("Cannot write output file " + outputName)
	}

	note := ""
	if container.Unit != 1 {
		note = " (a whole number of units)"
	}
	loop := ""
	if decoded.RepeatIndex >= 0 {
		loop = fmt.Sprintf(", looping from unit %d", decoded.RepeatIndex)
	} else if container.Rewind >= 0 {
		loop = fmt.Sprintf(", looping from unit %d by rewind",
			container.Rewind/container.Unit)
	}
	repeated := ""
	if times != 1 {
		repeated = fmt.Sprintf(", played %d times", times)
	}
	fmt.Printf("File decompressed from %d to %d bytes, k=%d%s%s%s!\n",
		len(file), len(output), container.Unit, note, loop, repeated)
}

// corruptError is a decode that failed on the second pass through a
// repeating stream.
type corruptError struct {
	err error
}

func (c corruptError) Error() string {
	return c.err.Error()
}

// played is the pass and then times - 1 repeats of its loop section, as a
// decoder driven past the end produces. A stream that loops by itself is
// decoded again to that length; a stream that loops by rewind repeats the
// pass's loop section, since every pass sees the same history. A stream
// that does not loop has nothing to repeat, and says so.
func played(container st4.Container, pass st4.Decoded, times int) ([]byte, error) {
	output := pass.Output
	if times == 1 {
		return output, nil
	}
	unit := container.Unit
	if pass.RepeatIndex >= 0 {
		loop := len(output) - pass.RepeatIndex*unit
		again, err := st4.Decode(container.Control, container.Literal,
			container.ByteOffsets, container.WordOffsets, unit,
			len(output)+(times-1)*loop, container.Window, container.Rewind)
		if err != nil {
			return nil, corruptError{err}
		}
		return again.Output, nil
	}
	if container.Rewind < 0 {
		return nil, fmt.Errorf("The stream does not loop, so -r%d has nothing"+
			" to repeat", times)
	}
	loop := len(output) - container.Rewind
	result := make([]byte, len(output)+(times-1)*loop)
	copy(result, output)
	for at := len(output); at < len(result); at += loop {
		copy(result[at:], output[container.Rewind:])
	}
	return result, nil
}

// number reads a numeric flag value, stopping with the reason it cannot be
// used: a pass count counts from one.
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
