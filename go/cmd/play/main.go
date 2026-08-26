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

const usageText = `usage: play.sh [-perf] [-nomask] [-nRING] [-cCHUNK]
               [-kUNIT] [-o] song.ym...

Packs the tunes, builds a program around them and runs it under Hatari.
Press SPACE in its window to stop. Everything it builds lands in a work
directory next to the first tune. HATARI= and TOS= point at your own
install.`

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
			fmt.Println(usageText)
			return
		case a == "-sidresume":
			options.SidResume = true
		case strings.HasPrefix(a, "-drumhz"):
			options.DrumHz = number(a[len("-drumhz"):])
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
		name += fmt.Sprintf("-%d", options.Unit)
	}
	work := filepath.Join(filepath.Dir(first), name)
	if err := os.MkdirAll(work, 0o755); err != nil {
		fail("play.sh: cannot make " + work)
	}

	set, err := pack.SetOf(rest)
	if err != nil {
		fail(err.Error())
	}
	fmt.Println("play.sh: packing " + strings.Join(rest, " "))
	packed := make([]string, 0, len(rest))
	for _, tune := range rest {
		input, err := os.ReadFile(tune)
		if err != nil {
			fail("play.sh: cannot read " + tune)
		}
		result, err := pack.Pack(input, options)
		if err != nil {
			fail(tune + ": " + err.Error())
		}
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
