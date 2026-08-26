package ymx

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// A runnable TOS program around an SNDH file: a prebuilt,
// position-independent stub in front of the same bytes BuildSndh writes, with
// a 28-byte PRG header before both and an empty relocation table behind.
// doc/BINARIES.md is the byte contract.

// The stub descriptor: 'YMXP' at this offset, then version, subtunes and
// flags, words, with the frame count a long between them.
const (
	StubMagic   = 4
	StubVersion = 8
	StubTunes   = 10
	StubFrames  = 12
	StubFlags   = 16
	StubRate    = 18
)

// StubFlagMarker is stub flag bit 0: drop YMXDONE.MRK on exit.
const StubFlagMarker = 1

// StubFlagVbl is stub flag bit 1: tick from the VBL, because the set claims
// Timer C for an effect channel and the host may not use it for the calls.
const StubFlagVbl = 2

// StubFlagClear is flag bit 2: clear the screen before the banner. A -perf
// build paints the background colour, and the desktop's own pixels stand in
// front of it: the bars then show in the borders alone.
const StubFlagClear = 4

// PrgOptions is what the caller asked for. An empty Title takes the output's
// own name, an empty Composer is no composer, and a nil Names is no name file.
type PrgOptions struct {
	Output    string
	Tunes     []string
	Title     string
	Composer  string
	Names     []string
	Perf      bool
	MaskBurst bool
	Marker    bool
}

// BuildPrg writes the program and gives back its path. A single .sndh
// argument is wrapped as it stands; anything else is combined into an SNDH
// file first.
func BuildPrg(options PrgOptions) (string, error) {
	output, err := filepath.Abs(options.Output)
	if err != nil {
		return "", fmt.Errorf("mkprg: cannot write %s", options.Output)
	}
	work := filepath.Join(filepath.Dir(output), ".prg_work")
	if err := os.MkdirAll(work, 0o755); err != nil {
		return "", fmt.Errorf("mkprg: cannot build in %s", work)
	}

	var sndh string
	if len(options.Tunes) == 1 &&
		strings.HasSuffix(strings.ToLower(options.Tunes[0]), ".sndh") {
		sndh = options.Tunes[0]
	} else {
		title := options.Title
		if title == "" {
			title = sndhStripSuffix(filepath.Base(output), ".prg")
		}
		sndh = filepath.Join(work, "tune.sndh")
		inner, err := SndhOptionsOf(sndh, options.Tunes, title,
			options.Composer, options.Names, options.Perf, options.MaskBurst)
		if err != nil {
			return "", err
		}
		if _, err := BuildSndh(inner); err != nil {
			return "", err
		}
	}

	file, err := os.ReadFile(sndh)
	if err != nil {
		return "", fmt.Errorf("mkprg: cannot read %s", sndh)
	}
	subtunes, err := prgSubtunes(file)
	if err != nil {
		return "", err
	}
	stubPath, err := prgResolveStub()
	if err != nil {
		return "", err
	}
	stub, err := prgReadStub(stubPath)
	if err != nil {
		return "", err
	}
	frames := 0
	if subtunes == 1 {
		frames, err = prgFrames(file)
		if err != nil {
			return "", err
		}
	}
	prg, err := prgWrap(stub, file, subtunes, frames, options.Marker)
	if err != nil {
		return "", err
	}
	if err := os.WriteFile(output, prg, 0o644); err != nil {
		return "", fmt.Errorf("mkprg: cannot write %s", output)
	}
	fmt.Printf("%s: %d bytes, %s\n", options.Output, len(prg),
		sndhPlural(subtunes, "subtune"))
	return output, nil
}

// prgWrap builds the program: the PRG header, the stub with its descriptor
// patched, the SNDH file, and the empty relocation table - a zero long.
func prgWrap(stub, sndh []byte, subtunes, frames int, marker bool) ([]byte, error) {
	var program bytes.Buffer
	text := len(stub) + len(sndh)
	program.WriteByte(0x60) // PRG magic $601A
	program.WriteByte(0x1A)
	sndhWriteLong(&program, text) // text
	sndhWriteLong(&program, 0)    // data
	sndhWriteLong(&program, 0)    // bss: every buffer is in
	sndhWriteLong(&program, 0)    // the stub's own bytes
	sndhWriteLong(&program, 0)    // reserved
	sndhWriteLong(&program, 0)    // flags
	program.WriteByte(0)          // absflag 0: the relocation
	program.WriteByte(0)          // table follows the text

	patched := append([]byte(nil), stub...)
	prgPutWord(patched, StubTunes, subtunes)
	sndhPutLong(patched, StubFrames, frames)
	rate := prgRate(sndh)
	timerC := prgClaimsTimerC(sndh)
	if timerC && rate != 50 {
		return nil, fmt.Errorf("mkprg: the set claims Timer C and plays at"+
			" %d Hz. The stub's fallback ticks from the VBL, which is a 50 Hz"+
			" clock, so this set needs a host of its own", rate)
	}
	flags := 0
	if marker {
		flags |= StubFlagMarker
	}
	if timerC {
		flags |= StubFlagVbl
	}
	if prgPaintsRaster(sndh) {
		flags |= StubFlagClear
	}
	prgPutWord(patched, StubFlags, flags)
	prgPutWord(patched, StubRate, rate)
	program.Write(patched)
	program.Write(sndh)
	sndhWriteLong(&program, 0) // no fixups
	return program.Bytes(), nil
}

// prgSubtunes reads the '##' tag's two ASCII digits.
func prgSubtunes(sndh []byte) (int, error) {
	at, err := prgFind(sndh, "##")
	if err != nil {
		return 0, err
	}
	return int(sndh[at+2]-'0')*10 + int(sndh[at+3]-'0'), nil
}

// prgRate reads the rate the timer tag gives, for the host to call play at.
// The five tags name the timer a desktop host should tick from and this tool
// reads only the rate, as the reference hosts do; which timer the stub uses is
// settled by the FLAG~ letters instead.
func prgRate(sndh []byte) int {
	for _, tag := range []string{"TA", "TB", "TC", "TD", "!V"} {
		at := prgPosition(sndh, tag)
		if at < 0 {
			continue
		}
		rate := 0
		for i := at + 2; sndh[i] >= '0' && sndh[i] <= '9'; i++ {
			rate = rate*10 + int(sndh[i]-'0')
		}
		if rate > 0 {
			return rate
		}
	}
	return 50
}

// prgPaintsRaster reports whether the core inside this SNDH file is a
// raster-monitor one, read off the core's own descriptor rather than the
// option that asked for one: the file may be a set a caller combined earlier,
// and then the option says nothing about what is inside it.
func prgPaintsRaster(sndh []byte) bool {
	for at := 0; at+4 <= len(sndh); at += 2 {
		if sndh[at] == 'Y' && sndh[at+1] == 'M' && sndh[at+2] == 'X' &&
			sndh[at+3] == 'C' {
			flags := at - CoreMagic + CoreFlags
			return flags >= 0 && flags+1 < len(sndh) &&
				sndhWord(sndh, flags)&1 != 0
		}
	}
	return false
}

// prgClaimsTimerC reports whether FLAG~ marks Timer C as one the subtunes
// claim.
func prgClaimsTimerC(sndh []byte) bool {
	at := prgPosition(sndh, "FLAG~")
	if at < 0 {
		return false
	}
	for i := at + 5; i < len(sndh) && sndh[i] != 0; i++ {
		if sndh[i] == 'c' {
			return true
		}
	}
	return false
}

// prgFrames reads FRMS's first long: subtune 1's frame count, 0 when it plays
// on.
func prgFrames(sndh []byte) (int, error) {
	found, err := prgFind(sndh, "FRMS")
	if err != nil {
		return 0, err
	}
	at := found + 4
	frames := uint32(sndh[at])<<24 | uint32(sndh[at+1])<<16 |
		uint32(sndh[at+2])<<8 | uint32(sndh[at+3])
	return int(int32(frames)), nil
}

// prgFind gives a required tag's position; a missing one stops the tool.
func prgFind(sndh []byte, tag string) (int, error) {
	at := prgPosition(sndh, tag)
	if at < 0 {
		return 0, fmt.Errorf("mkprg: the SNDH header carries no %s tag", tag)
	}
	return at, nil
}

// prgPosition gives a tag's position inside the SNDH header before 'HDNS', or
// -1.
func prgPosition(sndh []byte, tag string) int {
	wanted := []byte(tag)
	for at := 12; at+len(wanted) < len(sndh); at++ {
		if at+4 <= len(sndh) && sndh[at] == 'H' && sndh[at+1] == 'D' &&
			sndh[at+2] == 'N' && sndh[at+3] == 'S' {
			break
		}
		hit := true
		for i := 0; i < len(wanted); i++ {
			if sndh[at+i] != wanted[i] {
				hit = false
				break
			}
		}
		if hit {
			return at
		}
	}
	return -1
}

// prgResolveStub gives the stub from dist/.
func prgResolveStub() (string, error) {
	if named, ok := os.LookupEnv("YMX_STUB"); ok {
		return named, nil
	}
	repo, err := sndhRepo()
	if err != nil {
		return "", err
	}
	stub := filepath.Join(repo, "dist", "ymxprg"+sndhBinarySuffix()+".bin")
	if sndhStale(repo, stub, "YMX_player.S") {
		return "", fmt.Errorf("mkprg: %s is missing or older than the source"+
			" it is assembled from - take it from the binaries release, or"+
			" assemble it with ymx/mkcores.sh", stub)
	}
	return stub, nil
}

// prgReadStub reads the stub, its descriptor checked.
func prgReadStub(path string) ([]byte, error) {
	stub, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("mkprg: cannot read the stub %s", path)
	}
	if len(stub) < 20 || stub[StubMagic] != 'Y' || stub[StubMagic+1] != 'M' ||
		stub[StubMagic+2] != 'X' || stub[StubMagic+3] != 'P' {
		return nil, fmt.Errorf("%s is not a PRG stub", path)
	}
	if sndhWord(stub, StubVersion) != 2 {
		return nil, fmt.Errorf("%s is stub descriptor version %d, this tool"+
			" writes 2", path, sndhWord(stub, StubVersion))
	}
	if len(stub)&1 != 0 {
		return nil, fmt.Errorf("%s is odd-sized: the SNDH after it would load"+
			" misaligned", path)
	}
	return stub, nil
}

// prgPutWord writes a big-endian word over the bytes at a position.
func prgPutWord(data []byte, at, value int) {
	data[at] = byte(value >> 8)
	data[at+1] = byte(value)
}
