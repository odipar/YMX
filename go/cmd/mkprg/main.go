// Command mkprg wraps packed tunes, or a ready SNDH file, in a runnable TOS
// program.
package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/odipar/ymx/internal/ymx"
)

const usageText = "usage: mkprg.sh [-m] [-perf] [-nomask] [-tTitle]" +
	" [-cComposer] [-Nnamesfile] output.prg tunes.ymx...|set.sndh"

// The prefix on every error this command prints, as the other trees' is.
// sndhCombine is the prefix the SNDH combine puts on its own messages, and a
// message that carries either one prints unchanged.
const (
	prefix      = "mkprg: "
	sndhCombine = "mksndh: "
)

func main() {
	title, composer := "", ""
	var names []string
	marker, perf, maskBurst := false, false, true

	rest := os.Args[1:]
flags:
	for len(rest) > 0 {
		a := rest[0]
		switch {
		case a == "-m":
			marker = true
		case a == "-perf":
			perf = true
		case a == "-nomask":
			maskBurst = false
		case strings.HasPrefix(a, "-t"):
			title = a[2:]
		case strings.HasPrefix(a, "-c"):
			composer = a[2:]
		case strings.HasPrefix(a, "-N"):
			read, err := ymx.SndhReadNames(a[2:])
			if err != nil {
				failBuild(err)
			}
			names = read
		default:
			break flags
		}
		rest = rest[1:]
	}

	// Both argument orders: the .prg names the output wherever it stands, so
	// `mkprg.sh song.ymx SONG.PRG` keeps working.
	var output string
	var tunes []string
	switch {
	case len(rest) == 0:
		fail(usageText)
	case strings.EqualFold(suffixOf(rest[0]), ".prg"):
		output, tunes = rest[0], rest[1:]
	case len(rest) == 2 && strings.EqualFold(suffixOf(rest[1]), ".prg"):
		output, tunes = rest[1], rest[:1]
	default:
		fail(usageText)
	}
	if len(tunes) == 0 {
		fail(usageText)
	}

	if _, err := ymx.BuildPrg(ymx.PrgOptions{
		Output: output, Tunes: tunes, Title: title, Composer: composer,
		Names: names, Perf: perf, MaskBurst: maskBurst, Marker: marker,
	}); err != nil {
		failBuild(err)
	}
}

// failBuild prints an error from the build, with this command's prefix in
// front of a message that carries none. The usage text goes through fail.
func failBuild(err error) {
	message := err.Error()
	if !strings.HasPrefix(message, prefix) &&
		!strings.HasPrefix(message, sndhCombine) {
		message = prefix + message
	}
	fail(message)
}

func suffixOf(name string) string {
	if at := strings.LastIndex(name, "."); at >= 0 {
		return name[at:]
	}
	return ""
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
