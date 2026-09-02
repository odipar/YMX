// Command ym-to-ymx goes from a YM dump to something that plays: a .ymx, an
// SNDH file, or a runnable TOS program. The output's extension picks which.
//
// This is the command a release ships as a standalone executable, so it
// carries the SNDH cores and the PRG stub inside it rather than reading them
// out of a repository's dist/. Where a binary is absent - a build of this
// tree made before the binaries were assembled - it falls back to the
// repository, which is what the shell scripts have always used.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/odipar/ymx/internal/cores"
	"github.com/odipar/ymx/internal/pack"
	"github.com/odipar/ymx/internal/ymx"
)

const usageText = `usage: ym-to-ymx [options] output.{ymx|sndh|prg} tune.ym [more.ym ...]

  The output's extension picks what is written:
    .ymx    the packed tune, one input only
    .sndh   an SNDH v2.2 file any SNDH host plays
    .prg    a TOS program that plays it

packing
  -f              overwrite the output
  -o              play once: stop at the end instead of starting over
  -lF             start over from frame F; -l0 from the beginning
  -nN             ring size per stream, bytes (default 960)
  -cC             values decoded per call (default 24)
  -kK             ST4 unit size 1, 2 or 4 (default: the tune's own shape)
  -minM -secS     trim: drop everything before M:S
  -startframeF -endframeF -framesN
                  the same window in frames
  -drumhzH        the drum rate ceiling (default 25600)
  -timersT        which MFP timer each channel runs on (default AD)
  -sidresume      the resume gap model, for maxYMiser tunes

the SNDH file and the program
  -perf           build with the raster monitor
  -nomask         build with the frame write unmasked
  -tTitle         the SNDH TITL tag (default: the dump's own)
  -cComposer      the COMM tag - note -c is the chunk size when
                  it is followed by digits
  -Nnamesfile     subtune names, one per line
  -m              drop YMXDONE.MRK on exit, for scripted runs

  -h, --help      this text`

func main() {
	if len(os.Args) == 1 || os.Args[1] == "-h" || os.Args[1] == "--help" {
		fmt.Println(usageText)
		return
	}

	options := pack.Defaults()
	force := false
	perf := false
	maskBurst := true
	marker := false
	title := ""
	composer := ""
	var names []string

	// The first bad flag, held back until the output and the tunes are
	// settled: with too few arguments the usage text is printed instead,
	// and an output the extension rules out is reported before any flag.
	// The other trees check in that order, the flags reaching their packer
	// last.
	problem := ""
	record := func(message string) {
		if problem == "" {
			problem = message
		}
	}
	number := func(text string, zeroAllowed bool) int {
		value, message := pack.Number(text, zeroAllowed)
		if message != "" {
			record(message)
		}
		return value
	}
	timers := func(spec string) int {
		assignments, err := pack.ParseTimers(spec)
		if err != nil {
			record(err.Error())
			return options.TimerMap
		}
		return assignments
	}

	args := os.Args[1:]
	for len(args) > 0 && strings.HasPrefix(args[0], "-") {
		a := args[0]
		switch {
		case a == "-f":
			force = true
		case a == "-o":
			options.Loops = false
		case a == "-perf":
			perf = true
		case a == "-nomask":
			maskBurst = false
		case a == "-m":
			marker = true
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
		case strings.HasPrefix(a, "-t") && len(a) > 2:
			title = a[2:]
		case strings.HasPrefix(a, "-N") && len(a) > 2:
			read, err := ymx.SndhReadNames(a[2:])
			if err != nil {
				fail(err.Error())
			}
			names = read
		case a == "-copies":
			options.Copies = 0
		case strings.HasPrefix(a, "-copies"):
			options.Copies = float64(number(a[len("-copies"):], false))
		case strings.HasPrefix(a, "-c") && len(a) > 2 && !isDigit(a[2]):
			composer = a[2:] // -c with digits is the packer's chunk size
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
			// command does not have, not the name of the output.
			record("Invalid parameter " + a)
		}
		args = args[1:]
	}
	if len(args) < 2 {
		fail(usageText)
	}

	output, err := filepath.Abs(args[0])
	if err != nil {
		fail("ym-to-ymx: " + err.Error())
	}
	yms := args[1:]

	kind := strings.ToLower(filepath.Ext(output))
	if kind != ".ymx" && kind != ".sndh" && kind != ".prg" {
		fail("ym-to-ymx: the output's extension says what to write, and '" +
			kind + "' is not one of .ymx, .sndh or .prg")
	}
	if kind == ".ymx" && len(yms) > 1 {
		fail(fmt.Sprintf("ym-to-ymx: a .ymx holds one tune. Name a .sndh or a"+
			" .prg output to combine %d of them", len(yms)))
	}
	if problem != "" {
		fail("Error: " + problem)
	}
	// The trim options take one tune: a window is frames of that tune, and a
	// set is packed with the one configuration a player build holds.
	if len(yms) > 1 && (options.StartMin != 0 || options.StartSec != 0 ||
		options.StartFrame >= 0 || options.EndFrame >= 0 ||
		options.FrameCount >= 0) {
		fail("Error: the trim options take one tune, not a set")
	}
	if !force {
		if _, err := os.Stat(output); err == nil {
			fail("ym-to-ymx: already existing output file " + output)
		}
	}

	// What the set calls itself, where the caller named nothing: the tunes'
	// own names and a composer they all agree on. The dumps are read here,
	// so a dump that cannot be read is named in the message; a packing
	// failure that follows names no file.
	var set pack.TuneSet
	if kind != ".ymx" {
		read, err := pack.SetOf("ym-to-ymx", yms)
		if err != nil {
			fail(err.Error())
		}
		set = read
	}

	// The packed tunes: straight to the output where that is what was asked
	// for, and otherwise into a work directory the combiners read.
	work := filepath.Join(filepath.Dir(output), ".ym_work")
	if kind != ".ymx" {
		if err := os.MkdirAll(work, 0o755); err != nil {
			fail("ym-to-ymx: cannot make " + work)
		}
	}
	fmt.Println(pack.Banner())
	packed := make([]string, 0, len(yms))
	unit := 2
	for _, name := range yms {
		input, err := os.ReadFile(name)
		if err != nil {
			fail("ym-to-ymx: cannot read " + name)
		}
		result, err := pack.Pack(input, options)
		if err != nil {
			fail("Error: " + err.Error())
		}
		for _, note := range result.Notes {
			fmt.Println(note)
		}
		// A .ymx output is the pack itself, so its per-stream cost is what
		// was asked for. Any other output is a step on the way to a file
		// that plays, and the table is one line per stream per tune.
		if kind == ".ymx" {
			pack.Report(os.Stdout, result.Result)
		} else {
			pack.ReportQuietly(os.Stdout, result.Result)
		}
		out := output
		if kind != ".ymx" {
			stem := strings.TrimSuffix(filepath.Base(name),
				filepath.Ext(name))
			out = filepath.Join(work, stem+".ymx")
		}
		if err := os.WriteFile(out, result.Result.File, 0o644); err != nil {
			fail("ym-to-ymx: cannot write " + out)
		}
		packed = append(packed, out)
		unit = result.Result.Unit
	}
	if kind == ".ymx" {
		return
	}

	if title == "" {
		title = set.Title
	}
	if composer == "" {
		composer = set.Composer
	}
	if names == nil {
		names = set.Names
	}

	// The core this run needs, and the stub where a program is asked for.
	suffix := ""
	if perf {
		suffix += "-perf"
	}
	if !maskBurst {
		suffix += "-nomask"
	}
	core := fmt.Sprintf("ymxsndh-k%d%s-v%s.bin", unit, suffix, ymx.ReleaseName())
	stub := ""
	if kind == ".prg" {
		stub = fmt.Sprintf("ymxprg-v%s.bin", ymx.ReleaseName())
	}
	staged, err := cores.Stage(core, stub)
	if err != nil {
		fail("ym-to-ymx: " + err.Error())
	}
	defer staged.Close()

	if kind == ".sndh" {
		built, err := ymx.SndhOptionsOf(output, packed, title, composer, names,
			perf, maskBurst)
		if err != nil {
			fail(err.Error())
		}
		if _, err := ymx.BuildSndh(built); err != nil {
			fail(err.Error())
		}
		return
	}
	if _, err := ymx.BuildPrg(ymx.PrgOptions{
		Output: output, Tunes: packed, Title: title, Composer: composer,
		Names: names, Perf: perf, MaskBurst: maskBurst, Marker: marker,
	}); err != nil {
		fail(err.Error())
	}
}

func isDigit(b byte) bool {
	return b >= '0' && b <= '9'
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
