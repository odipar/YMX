// Command ymsndh packs every YM dump given with one configuration and
// combines the results into an SNDH file.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
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

	rest := os.Args[1:]
flags:
	for len(rest) > 0 && strings.HasPrefix(rest[0], "-") {
		a := rest[0]
		switch {
		case a == "-perf":
			perf = true
		case a == "-o":
			options.Loops = false
		case a == "-sidresume":
			options.SidResume = true
		case strings.HasPrefix(a, "-drumhz"):
			options.DrumHz = number(a[len("-drumhz"):])
		case strings.HasPrefix(a, "-t"):
			title = a[2:]
		case strings.HasPrefix(a, "-n"):
			options.Ring = number(a[2:])
		case strings.HasPrefix(a, "-c"):
			options.Chunk = number(a[2:])
		case strings.HasPrefix(a, "-k"):
			options.Unit = number(a[2:])
		default:
			break flags
		}
		rest = rest[1:]
	}
	if len(rest) < 2 {
		fail(usageText)
	}
	output, yms := rest[0], rest[1:]

	// A fresh work directory each run: yesterday's leftovers are not this
	// set's subtunes.
	work := filepath.Join(directoryOf(output), ".ym_work")
	if err := os.RemoveAll(work); err != nil {
		fail("ymsndh: cannot clear " + work)
	}
	if err := os.MkdirAll(work, 0o755); err != nil {
		fail("ymsndh: cannot make " + work)
	}

	set, err := pack.SetOf(yms)
	if err != nil {
		fail(err.Error())
	}
	packed := make([]string, 0, len(yms))
	for _, name := range yms {
		input, err := os.ReadFile(name)
		if err != nil {
			fail("ymsndh: cannot read " + name)
		}
		result, err := pack.Pack(input, options)
		if err != nil {
			fail(name + ": " + err.Error())
		}
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

func number(text string) int {
	value, err := strconv.Atoi(text)
	if err != nil {
		fail(usageText)
	}
	return value
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
