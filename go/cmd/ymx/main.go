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

const usage = "usage: ymx [-f] [-o] [-lF] [-nN] [-cC] [-kK]" +
	" [-minM] [-secS] [-startframeF] [-endframeF] [-framesN]" +
	" [-drumhzH] [-timersT] [-sidresume] input.ym [output.ymx]"

func main() {
	force := false
	o := pack.Defaults()

	args := os.Args[1:]
	for len(args) > 0 && strings.HasPrefix(args[0], "-") {
		a := args[0]
		switch {
		case a == "-f":
			force = true
		case a == "-o":
			o.Loops = false
		case a == "-sidresume":
			o.SidResume = true
		case strings.HasPrefix(a, "-timers"):
			o.TimerMap = parseTimers(a[len("-timers"):])
		case strings.HasPrefix(a, "-drumhz"):
			o.DrumHz = number(a[len("-drumhz"):])
		case strings.HasPrefix(a, "-startframe"):
			o.StartFrame = number(a[len("-startframe"):])
		case strings.HasPrefix(a, "-endframe"):
			o.EndFrame = number(a[len("-endframe"):])
		case strings.HasPrefix(a, "-frames"):
			o.FrameCount = number(a[len("-frames"):])
		case strings.HasPrefix(a, "-min"):
			o.StartMin = number(a[len("-min"):])
		case strings.HasPrefix(a, "-sec"):
			o.StartSec = number(a[len("-sec"):])
		case strings.HasPrefix(a, "-n"):
			o.Ring = number(a[2:])
		case strings.HasPrefix(a, "-c"):
			o.Chunk = number(a[2:])
		case strings.HasPrefix(a, "-k"):
			o.Unit = number(a[2:])
		case strings.HasPrefix(a, "-l"):
			o.LoopFrame = number(a[2:])
		default:
			fail("Invalid parameter " + a)
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

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}

// parseTimers reads the timer map, failing the way this command fails.
func parseTimers(spec string) int {
	assignments, err := pack.ParseTimers(spec)
	if err != nil {
		fail(err.Error())
	}
	return assignments
}
