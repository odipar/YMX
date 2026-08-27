// Command ymsndh packs every YM dump given with one configuration and
// combines the results into an SNDH file.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/odipar/ymx/internal/pack"
	"github.com/odipar/ymx/internal/ymx"
)

const usageText = "usage: ym_sndh.sh [-perf] [-tTitle] [packer flags]" +
	" output.sndh tunes.ym..."

func main() {
	title := ""
	perf := false
	options := pack.Defaults()

	// The first bad flag, held back until an output and a tune are there to
	// name: with too few arguments the usage text is printed instead. The
	// other trees check in that order, the flags reaching their packer
	// last.
	problem := ""
	record := func(message string) {
		if problem == "" {
			problem = message
		}
	}
	timers := func(spec string) int {
		assignments, err := pack.ParseTimers(spec)
		if err != nil {
			record(err.Error())
			return options.TimerMap
		}
		return assignments
	}
	number := func(text string, zeroAllowed bool) int {
		value, message := pack.Number(text, zeroAllowed)
		if message != "" {
			record(message)
		}
		return value
	}

	rest := os.Args[1:]
	for len(rest) > 0 && strings.HasPrefix(rest[0], "-") {
		a := rest[0]
		switch {
		case a == "-perf":
			perf = true
		case a == "-o":
			options.Loops = false
		case a == "-f":
			// The other trees hand every flag to the packer, which is
			// always run with -f: the work directory is this command's
			// own, and a caller who passes it changes nothing.
		case a == "-sidresume":
			options.SidResume = true
		case strings.HasPrefix(a, "-timers"):
			options.TimerMap = timers(a[len("-timers"):])
		case strings.HasPrefix(a, "-drumhz"):
			options.DrumHz = number(a[len("-drumhz"):], false)
		case strings.HasPrefix(a, "-startframe"):
			options.StartFrame = number(a[len("-startframe"):], true)
		case strings.HasPrefix(a, "-endframe"):
			options.EndFrame = number(a[len("-endframe"):], true)
		case strings.HasPrefix(a, "-frames"):
			options.FrameCount = number(a[len("-frames"):], true)
		case strings.HasPrefix(a, "-min"):
			options.StartMin = number(a[len("-min"):], true)
		case strings.HasPrefix(a, "-sec"):
			options.StartSec = number(a[len("-sec"):], true)
		case strings.HasPrefix(a, "-t"):
			title = a[2:]
		case strings.HasPrefix(a, "-n"):
			options.Ring = number(a[2:], false)
		case strings.HasPrefix(a, "-c"):
			options.Chunk = number(a[2:], false)
		case strings.HasPrefix(a, "-k"):
			options.Unit = number(a[2:], false)
		case strings.HasPrefix(a, "-l"):
			options.LoopFrame = number(a[2:], true)
		default:
			// A leading dash that matches no case is a flag this
			// command does not have, not the name of the output. The
			// other trees reach the same message by handing the flag
			// to the packer, which does not have it either.
			record("Invalid parameter " + a)
		}
		rest = rest[1:]
	}
	if len(rest) < 2 {
		fail(usageText)
	}
	if problem != "" {
		fail("Error: " + problem)
	}
	output, yms := rest[0], rest[1:]

	// The dumps are read first: a tune that cannot be read leaves no work
	// directory behind, which is what the other two trees leave.
	set, err := pack.SetOf("ymsndh", yms)
	if err != nil {
		fail(err.Error())
	}

	// A fresh work directory each run: yesterday's leftovers are not this
	// set's subtunes.
	work := filepath.Join(directoryOf(output), ".ym_work")
	if err := os.RemoveAll(work); err != nil {
		fail("ymsndh: cannot clear " + work)
	}
	if err := os.MkdirAll(work, 0o755); err != nil {
		fail("ymsndh: cannot make " + work)
	}
	fmt.Println(pack.Banner())
	packed := make([]string, 0, len(yms))
	for _, name := range yms {
		input, err := os.ReadFile(name)
		if err != nil {
			fail("ymsndh: cannot read " + name)
		}
		result, err := pack.Pack(input, options)
		if err != nil {
			fail("Error: " + err.Error())
		}
		for _, note := range result.Notes {
			fmt.Println(note)
		}
		pack.ReportQuietly(os.Stdout, result.Result)
		out := filepath.Join(work, pack.Stem(name)+".ymx")
		if err := os.WriteFile(out, result.Result.File, 0o644); err != nil {
			fail("ymsndh: cannot write " + out)
		}
		packed = append(packed, out)
	}

	if title == "" {
		title = set.Title
	}
	built, err := ymx.SndhOptionsOf(output, packed, title, set.Composer,
		set.Names, perf, true)
	if err != nil {
		fail(err.Error())
	}
	if _, err := ymx.BuildSndh(built); err != nil {
		fail(err.Error())
	}
}

func directoryOf(path string) string {
	absolute, err := filepath.Abs(path)
	if err != nil {
		fail("ymsndh: " + err.Error())
	}
	return filepath.Dir(absolute)
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
