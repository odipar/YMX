// Command ymx packs a YM register dump into a .ymx file.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/odipar/ymx/internal/pack"
	"github.com/odipar/ymx/internal/ym"
	"github.com/odipar/ymx/internal/ymx"
)

// The usage text, held to the other two trees word for word. A flag
// named in one tree's help and missing from another's is a flag somebody
// reads about and then cannot use.
const usage = `Usage: YMX [-f] [-o] [-lF] [-nN] [-cC] [-kK] input.ym [output.ymx]
       ymx [options] one.ym two.ym more.ym output-dir/
  -f      Force overwrite of output file
  -o      Play once: stop at the end instead of starting over
  -lF     Start over from frame F rather than from the frame
          the header gives; -l0 starts over from the
          beginning. Where the wrap cannot enter F the packer
          takes the next frame it can and says so
  -nN     Ring size per stream, in bytes (default 960)
  -cC     Values decoded per call, and the round-robin group
          size (default 24; N mod C = 0, and C at
          least the streams the tune decodes: 17 with
          no timer channel, 21 for a YM tune, 25 for
          one that uses all four)
  -kK     ST4 unit size: 1, 2 or 4 (default 2). An odd
          tune length is padded with safe duplicate frames
          - inaudible - to fit the unit. The player must be
          built with the same ST4_UNIT
  -copies Let a match beyond the ring copy from the literal
          stream; the player must then be built with
          ST4_WINDOW = N/K, and mksndh takes the -copies core
  -copiesS   The same, searching S seconds a stream for a
          better parse
  -minM -secS   Trim: drop everything before M:S, so a
          moment deep in a long tune plays immediately
  -drumhzH   The drum rate ceiling (default 25600): a drum
          asking for a faster timer is downsampled to fit,
          with a warning
  -timersT   Which MFP timer each channel runs on, one
          letter per channel from 0 up: -timersBC puts
          channel 0 on Timer B and channel 1 on Timer C.
          The default is AD, where a YM tune has always
          played. Timer C is the system's 200 Hz clock,
          so a tune that takes it stops that clock and
          cannot be hosted from a Timer C interrupt
  -sidresume   The maxYMiser SID gap model: a released
          SID's timer keeps counting and a re-arrival
          resumes its phase. Default: the ym2149-rs
          model, phase-zero restarts
  -startframeF -endframeF -framesN   The same window in
          frames: start, end, or a length cap
  -script Dump the compiled effect script instead of
          packing: one line per frame anything acts on
  -meta   Print the header's title, author and frame rate,
          one per line, and pack nothing - what the build
          scripts read for the SNDH tags

The input is a YM5!/YM6! dump, LHA-archived or already
unpacked - the reader tells them apart by itself. With a
trailing DIRECTORY, every argument before it is an input,
packed with the same configuration - the set one player
build can hold as subtunes.`

func main() {
	force := false
	o := pack.Defaults()

	args := os.Args[1:]
	// -meta: the YM header's strings and rate, one per line, for the build
	// scripts to carry into SNDH tags. Nothing else runs.
	if len(args) == 2 && args[0] == "-meta" {
		song := readSong(args[1])
		fmt.Println(strings.TrimSpace(song.Name))
		fmt.Println(strings.TrimSpace(song.Author))
		fmt.Println(song.PlayerHz)
		return
	}
	// -script: the compiled effect script, one line per acting frame.
	if len(args) == 2 && args[0] == "-script" {
		tune, err := ymx.BuildTune(pack.Cross(readSong(args[1])))
		if err != nil {
			fail(args[1] + ": " + err.Error())
		}
		script := ymx.CompileOnTimers(tune, ymx.DefaultTimers)
		fmt.Printf("%d frames\n", script.Frames)
		for frame := 0; frame < script.Frames; frame++ {
			if script.M[frame] == 0 && script.R7Force[frame] == 0 {
				continue
			}
			line := fmt.Sprintf("%6d  M=%02X X=%02X T=%02X", frame,
				script.M[frame], script.X[frame], script.Timers[frame])
			for voice := range script.Actions {
				line += fmt.Sprintf(" A%d=%02X P%d=%3d", voice,
					script.Actions[voice][frame], voice,
					script.Counts[voice][frame])
			}
			fmt.Printf("%s R7|=%02X\n", line, script.R7Force[frame])
		}
		for _, note := range script.Notes {
			fmt.Println("note: " + note)
		}
		return
	}
	fmt.Println(pack.Banner())

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
			o.DrumHz = number(a[len("-drumhz"):], false)
		case strings.HasPrefix(a, "-startframe"):
			o.StartFrame = number(a[len("-startframe"):], true)
		case strings.HasPrefix(a, "-endframe"):
			o.EndFrame = number(a[len("-endframe"):], true)
		case strings.HasPrefix(a, "-frames"):
			o.FrameCount = number(a[len("-frames"):], true)
		case strings.HasPrefix(a, "-min"):
			o.StartMin = number(a[len("-min"):], true)
		case strings.HasPrefix(a, "-sec"):
			o.StartSec = number(a[len("-sec"):], true)
		case strings.HasPrefix(a, "-n"):
			o.Ring = number(a[2:], false)
		case a == "-copies":
			o.Copies = 0
		case strings.HasPrefix(a, "-copies"):
			o.Copies = float64(number(a[len("-copies"):], false))
		case strings.HasPrefix(a, "-c"):
			o.Chunk = number(a[2:], false)
		case strings.HasPrefix(a, "-k"):
			o.Unit = number(a[2:], false)
		case strings.HasPrefix(a, "-l"):
			o.LoopFrame = number(a[2:], true)
		default:
			fail("Invalid parameter " + a)
		}
		args = args[1:]
	}

	// A trailing DIRECTORY collects a whole set: every argument before it is
	// an input, each packed with the identical configuration into
	// <dir>/<stem>.ymx - the shape a multi-tune player needs, since one
	// player build serves one unit size and one workspace.
	if len(args) >= 2 && isDirectory(args[len(args)-1]) {
		if o.StartMin != 0 || o.StartSec != 0 || o.StartFrame >= 0 ||
			o.EndFrame >= 0 || o.FrameCount >= 0 {
			fail("the trim options take one tune, not a set")
		}
		// One unit size for the whole set: padding fits a tune to it, or
		// the pack stops at the tune it cannot fit.
		if o.Unit == 0 {
			o.Unit = 2
		}
		dir := args[len(args)-1]
		for _, input := range args[:len(args)-1] {
			packOne(input, filepath.Join(dir, stem(input)+".ymx"), o, force)
		}
		return
	}

	if len(args) < 1 || len(args) > 2 {
		failUsage()
	}
	inputName := args[0]
	// With no output named, the .ymx goes beside the dump under the dump's
	// own name: tune.ym packs to tune.ym.ymx.
	outputName := inputName + ".ymx"
	if len(args) == 2 {
		outputName = args[1]
	}
	packOne(inputName, outputName, o, force)
}

// packOne is the whole road for one tune: read, pack, write, report.
func packOne(inputName, outputName string, o pack.Options, force bool) {
	// The floor only, and before the input is opened: how many streams a
	// tune decodes depends on the channels it names, which the encoder
	// derives and checks again. A command line wrong in the ring, the chunk
	// or the unit is answered for that, whatever the input turns out to be.
	unit := o.Unit
	if unit < 1 {
		unit = 1
	}
	if problem := ymx.CheckShape(o.Ring, o.Chunk, unit,
		ymx.StreamA0); problem != "" {
		fail(problem)
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
		// The reader and the encoder fail through this one return. A
		// reader's failure names the input path, the way the other two
		// trees write it; an encoder's failure names the option and no
		// path. Reading the dump once more, on the way out, tells the two
		// apart.
		if _, again := ym.Read(input); again != nil {
			fail(inputName + ": " + err.Error())
		}
		fail(err.Error())
	}
	for _, note := range packed.Notes {
		fmt.Println(note)
	}
	result := packed.Result
	if err := os.WriteFile(outputName, result.File, 0o644); err != nil {
		fail("Cannot write output file " + outputName)
	}

	pack.Report(os.Stdout, result)
}

// stem is the input's file name with a trailing ".ym" dropped, upper or
// lower case: the name its packed file takes in a set's directory.
func stem(path string) string {
	name := filepath.Base(path)
	if len(name) >= 3 && strings.EqualFold(name[len(name)-3:], ".ym") {
		name = name[:len(name)-3]
	}
	return name
}

// isDirectory says whether the path names a directory that already exists,
// the test that separates a set from a single tune.
func isDirectory(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

// readSong reads a dump, or stops with the reason it could not.
func readSong(name string) *ym.Song {
	input, err := os.ReadFile(name)
	if err != nil {
		fail("Cannot access input file " + name)
	}
	song, err := ym.Read(input)
	if err != nil {
		fail(name + ": " + err.Error())
	}
	return song
}

// number reads a numeric flag value, failing the way this command fails.
func number(text string, zeroAllowed bool) int {
	value, problem := pack.Number(text, zeroAllowed)
	if problem != "" {
		fail(problem)
	}
	return value
}

// fail writes why the command stops, under the "Error: " the other two
// trees write, and exits 1.
func fail(message string) {
	fmt.Fprintln(os.Stderr, "Error: "+message)
	os.Exit(1)
}

// failUsage writes the forms of the command line and exits 1. A positional
// argument out of place is a question about the form, not about one value
// in it.
func failUsage() {
	fmt.Fprintln(os.Stderr, usage)
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
