// Command play is a test drive: pack a tune, build a player around it, and
// run it under Hatari. The exit marker tells this command the program
// stopped, so closing the window or pressing SPACE ends the run.
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/odipar/ymx/internal/pack"
	"github.com/odipar/ymx/internal/ymx"
)

// The help text and the one-line failure the other two trees print. A
// caller reading one tree's examples runs them against another.
const helpText = `play.sh - test drive a YM tune: pack it, build a player, run it under Hatari.

  ym/play.sh song.ym                  # 960-byte rings, 24 values per call
  ym/play.sh -n256 song.ym            # smaller rings: less RAM, worse ratio
  ym/play.sh -n2048 -c32 song.ym      # longer calls: cheaper on average
  ym/play.sh -copies song.ym          # copies from the literal stream: the
                                      # player is built for the ring as its
                                      # window; -copies5 searches five
                                      # seconds a stream for a better parse
  ym/play.sh -o song.ym               # play once and stop, instead of
                                      # starting over at the end
  ym/play.sh -min13 -sec52 song.ym    # trim: start deep in a long tune
  ym/play.sh -startframe41403 -frames1729 song.ym
  ym/play.sh one.ym two.ym            # a set: subtunes, number keys pick
  ym/play.sh -perf song.ym            # the raster monitor: the frame step
                                      # works in red, timer ticks in green
                                      # (A) and blue (D), and a yellow bar
                                      # estimates the ticks' scanlines
  ym/play.sh -nomask song.ym          # drop the interrupt mask around the
                                      # frame write, which the writes do
                                      # not need: ticks then interleave
                                      # with it instead of waiting ~500
                                      # cycles behind it

Press SPACE in the Hatari window to stop. Everything it builds lands in a
work directory next to the first tune. The trim flags take one tune.

  HATARI=/path/to/hatari TOS=/path/to/tos.img ym/play.sh song.ym`

const usageText = "usage: play.sh [-perf] [-nomask] [-nRING] [-cCHUNK] [-kUNIT] [-copies[S]] [-o] song.ym..."

// The first value the packer has no use for, held until the TOS image has
// been looked for: that is the order the other trees report them in.
var problem string

func record(message string) {
	if problem == "" {
		problem = message
	}
}

func main() {
	options := pack.Defaults()
	perf, maskBurst := false, true

	rest := os.Args[1:]
flags:
	for len(rest) > 0 && strings.HasPrefix(rest[0], "-") {
		a := rest[0]
		switch {
		case a == "-perf":
			perf = true
		case a == "-nomask":
			maskBurst = false
		case a == "-o":
			options.Loops = false
		case a == "-h", a == "--help":
			fmt.Println(helpText)
			return
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
		case a == "-copies":
			options.Copies = 0
		case strings.HasPrefix(a, "-copies"):
			options.Copies = float64(number(a[len("-copies"):], false))
		case strings.HasPrefix(a, "-n"):
			options.Ring = number(a[2:], false)
		case strings.HasPrefix(a, "-c"):
			options.Chunk = number(a[2:], false)
		case strings.HasPrefix(a, "-k"):
			options.Unit = number(a[2:], false)
		case strings.HasPrefix(a, "-l"):
			options.LoopFrame = number(a[2:], true)
		default:
			break flags
		}
		rest = rest[1:]
	}
	if len(rest) == 0 {
		fail(usageText)
	}
	for _, name := range rest {
		if info, err := os.Stat(name); err != nil || info.IsDir() {
			fail("play.sh: no such file: " + name)
		}
	}

	hatari := env("HATARI", "hatari")
	tos := env("TOS", filepath.Join(home(), "hatari-2.6.1_macos",
		"tos-2.06.rom"))
	// Before the pack, not after: a missing image otherwise costs a whole
	// build and leaves a work directory behind, which the other trees do not.
	if info, err := os.Stat(tos); err != nil || info.IsDir() {
		fail("play.sh: no TOS image at " + tos +
			" - set TOS=/path/to/tos.img")
	}
	if problem != "" {
		fail("Error: " + problem)
	}

	// One directory per run, named after the first tune and the shape, so a
	// second run with a different ring size does not overwrite the first.
	first, err := filepath.Abs(rest[0])
	if err != nil {
		fail("play.sh: " + err.Error())
	}
	name := pack.Stem(first)
	if len(rest) > 1 {
		name += fmt.Sprintf("+%d", len(rest)-1)
	}
	name += fmt.Sprintf("-n%d-c%d", options.Ring, options.Chunk)
	if options.Unit != 0 {
		name += fmt.Sprintf("-k%d", options.Unit)
	}
	work := filepath.Join(filepath.Dir(first), name)
	if err := os.MkdirAll(work, 0o755); err != nil {
		fail("play.sh: cannot make " + work)
	}

	set, err := pack.SetOf("play.sh", rest)
	if err != nil {
		fail(err.Error())
	}
	fmt.Println("play.sh: packing " + strings.Join(rest, " "))
	fmt.Println(pack.Banner())
	packed := make([]string, 0, len(rest))
	for _, tune := range rest {
		input, err := os.ReadFile(tune)
		if err != nil {
			fail("play.sh: cannot read " + tune)
		}
		result, err := pack.Pack(input, options)
		if err != nil {
			fail("Error: " + err.Error())
		}
		for _, note := range result.Notes {
			fmt.Println(note)
		}
		pack.Report(os.Stdout, result.Result)
		out := filepath.Join(work, pack.Stem(tune)+".ymx")
		if err := os.WriteFile(out, result.Result.File, 0o644); err != nil {
			fail("play.sh: cannot write " + out)
		}
		packed = append(packed, out)
	}

	program := filepath.Join(work, "PLAY.PRG")
	if _, err := ymx.BuildPrg(ymx.PrgOptions{
		Output: program, Tunes: packed, Title: set.Title,
		Composer: set.Composer, Names: set.Names, Perf: perf,
		MaskBurst: maskBurst, Marker: true,
	}); err != nil {
		fail(err.Error())
	}

	marker := filepath.Join(work, "YMXDONE.MRK")
	os.Remove(marker)
	fmt.Println("play.sh: starting Hatari - press SPACE in its window to stop")
	run(hatari, tos, program, marker)
	os.Remove(marker)
	fmt.Println("play.sh: stopped. The tune and the program are in " + work)
}

// run starts the emulator and waits, ending the session when the program
// drops its marker. Sound on, real speed, a window: this is a listening
// test, not a measurement.
func run(hatari, tos, program, marker string) {
	emulator := exec.Command(hatari,
		"--tos", tos, "--machine", "st", "--cpuclock", "8", "--memsize", "4",
		"--sound", "44100", "--ym-mixing", "model", "--window", "--zoom", "2",
		"--confirm-quit", "off", "--log-level", "fatal", program)
	emulator.Stdout, emulator.Stderr = os.Stdout, os.Stderr
	if err := emulator.Start(); err != nil {
		fail("play.sh: cannot start " + hatari + ": " + err.Error())
	}
	done := make(chan error, 1)
	go func() { done <- emulator.Wait() }()
	for {
		select {
		case <-done:
			return
		case <-time.After(200 * time.Millisecond):
			if _, err := os.Stat(marker); err == nil {
				_ = emulator.Process.Kill()
				<-done
				return
			}
		}
	}
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func home() string {
	if dir, err := os.UserHomeDir(); err == nil {
		return dir
	}
	return ""
}

// number reads a flag's value. This command reads the digits itself, because
// the work directory is named after the ring, the chunk and the unit, and
// says so in its own words when they are not digits. A value that is digits
// but no use to the packer - a zero unit, a negative count - is held back:
// the other trees hand the flag to the packer, which turns it down after the
// TOS image has been looked for, and the order is what a caller sees.
func number(text string, zeroAllowed bool) int {
	value, message := pack.Number(text, zeroAllowed)
	if message == "" {
		return value
	}
	if _, digits := strconv.Atoi(text); digits != nil {
		fail("play.sh: not a number: " + text)
	}
	record(message)
	return value
}

// timers reads the timer map, failing the way this command fails.
func timers(spec string) int {
	assignments, err := pack.ParseTimers(spec)
	if err != nil {
		fail("play.sh: " + err.Error())
	}
	return assignments
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
