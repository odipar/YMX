// Command mksndh combines packed tunes into an SNDH v2.2 file.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/odipar/ymx/internal/ymx"
)

const usageText = "usage: mksndh.sh [-perf] [-nomask] [-tTitle] [-cComposer]" +
	" [-Nnamesfile] [-Pcorefile] output.sndh tune1.ymx [tune2.ymx ...]"

// The prefix on every error this command prints, as the other trees' is. A
// message that carries it already prints unchanged.
const prefix = "mksndh: "

func main() {
	title, composer, core := "", "", ""
	var names []string
	perf, maskBurst := false, true

	// The flags come first and the loop stops on the first argument that is
	// not one, as the other trees' does: an unknown flag is read as the
	// output name rather than refused.
	rest := os.Args[1:]
flags:
	for len(rest) > 0 {
		a := rest[0]
		switch {
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
		case strings.HasPrefix(a, "-P"):
			core = a[2:]
		default:
			break flags
		}
		rest = rest[1:]
	}
	if len(rest) < 2 {
		fail(usageText)
	}
	output, tunes := rest[0], rest[1:]
	if title == "" {
		title = strings.TrimSuffix(filepath.Base(output),
			filepath.Ext(output))
	}

	options, err := ymx.SndhOptionsOf(output, tunes, title, composer, names,
		perf, maskBurst)
	if err != nil {
		failBuild(err)
	}
	if core != "" {
		_, err = ymx.BuildSndhWithCore(options, core)
	} else {
		_, err = ymx.BuildSndh(options)
	}
	if err != nil {
		failBuild(err)
	}
}

// failBuild prints an error from the combine, with this command's prefix in
// front of a message that carries none. The usage text goes through fail.
func failBuild(err error) {
	message := err.Error()
	if !strings.HasPrefix(message, prefix) {
		message = prefix + message
	}
	fail(message)
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
