package ymx

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// The combiner: a prebuilt SNDH core plus packed tunes make an SNDH v2.2
// file, the canonical form of this player, which BuildPrg then wraps in a
// runnable program around the same bytes. No assembler runs here:
// doc/BINARIES.md is the byte contract.

// SndhMaxSubtunes caps a file: SNDH's '##' tag is two ASCII digits.
const SndhMaxSubtunes = 99

// The core descriptor: 'YMXC' at this offset, then version, unit, flags,
// format version and the workspace's fixed size, words; then the two offsets
// this tool patches, longs.
const (
	CoreMagic     = 12
	CoreVersion   = 16
	CoreUnit      = 18
	CoreFlags     = 20
	CoreFormat    = 22
	CoreWorkFixed = 24
	CoreTableOff  = 26
	CoreWorkOff   = 30
)

// Core flag bits, matching YMX_sndh.S.
const (
	CoreFlagPerf   = 1
	CoreFlagNomask = 2
)

// SndhOptions is what the caller asked for; every field but the tunes has a
// default. An empty Composer is no composer, and a nil Names is no name file.
type SndhOptions struct {
	Output    string
	Tunes     []string
	Title     string
	Composer  string
	Names     []string
	Perf      bool
	MaskBurst bool
}

// SndhOptionsOf checks the tune list the two ways a combine cannot recover
// from, and gives back the options to build with.
func SndhOptionsOf(output string, tunes []string, title, composer string,
	names []string, perf, maskBurst bool) (SndhOptions, error) {
	if len(tunes) == 0 {
		return SndhOptions{}, errors.New("mksndh: no tunes")
	}
	if len(tunes) > SndhMaxSubtunes {
		return SndhOptions{}, fmt.Errorf("mksndh: SNDH's '##' tag caps a file"+
			" at %d subtunes", SndhMaxSubtunes)
	}
	return SndhOptions{
		Output:    output,
		Tunes:     tunes,
		Title:     title,
		Composer:  composer,
		Names:     names,
		Perf:      perf,
		MaskBurst: maskBurst,
	}, nil
}

// SndhResult is what the combine built, for the caller that wraps it.
type SndhResult struct {
	Output   string
	Subtunes int
	Shape    Header
}

// BuildSndh combines the tunes with the core these options resolve to.
func BuildSndh(options SndhOptions) (SndhResult, error) {
	core, err := sndhResolveCore(options)
	if err != nil {
		return SndhResult{}, err
	}
	return BuildSndhWithCore(options, core)
}

// BuildSndhWithCore combines with the core given rather than resolved from
// dist/.
func BuildSndhWithCore(options SndhOptions, corePath string) (SndhResult, error) {
	core, err := sndhReadCore(corePath, options)
	if err != nil {
		return SndhResult{}, err
	}

	var tunes [][]byte
	var frms []int
	var names []string
	var first Header
	haveFirst := false
	rate := 0
	maxRing := 0
	claimed := 0
	n := 0
	for _, tune := range options.Tunes {
		header, err := ReadHeader(tune)
		if err != nil {
			return SndhResult{}, fmt.Errorf("mksndh: %w", err)
		}
		if !header.AnyUnit() && header.Unit != sndhWord(core, CoreUnit) {
			return SndhResult{}, fmt.Errorf("%s is packed at unit %d, the core"+
				" serves unit %d", tune, header.Unit, sndhWord(core, CoreUnit))
		}
		unit := header.Unit
		if header.AnyUnit() {
			unit = 1
		}
		shape := CheckShape(header.Ring, header.Chunk, unit,
			LiveStreams(header.Flags))
		if shape != "" {
			return SndhResult{}, fmt.Errorf("%s: %s", tune, shape)
		}
		if !haveFirst {
			first = header
			haveFirst = true
			rate = header.Hz
		} else if header.Hz != rate {
			return SndhResult{}, fmt.Errorf("%s plays at %d Hz, the set at %d"+
				" - one SNDH declares one rate", tune, header.Hz, rate)
		}
		n++
		if header.Ring > maxRing {
			maxRing = header.Ring
		}
		claimed |= header.ClaimedTimers()
		frms = append(frms, header.Frms())
		names = append(names, sndhSubtuneName(options, n, tune))
		packed, err := os.ReadFile(tune)
		if err != nil {
			return SndhResult{}, fmt.Errorf("mksndh: cannot read %s", tune)
		}
		tunes = append(tunes, packed)
	}
	if !haveFirst {
		return SndhResult{}, errors.New("mksndh: no tunes")
	}

	file := sndhCombine(core, tunes,
		sndhTags(options, rate, n, frms, names, claimed), maxRing)
	output, err := filepath.Abs(options.Output)
	if err != nil {
		return SndhResult{}, fmt.Errorf("mksndh: cannot write %s",
			options.Output)
	}
	if err := os.WriteFile(output, file, 0o644); err != nil {
		return SndhResult{}, fmt.Errorf("mksndh: cannot write %s", output)
	}
	fmt.Printf("%s: %d bytes, %s, unit %d, workspace for rings of %d\n",
		options.Output, len(file), sndhPlural(n, "subtune"),
		sndhWord(core, CoreUnit), maxRing)
	return SndhResult{Output: output, Subtunes: n, Shape: first}, nil
}

// sndhCombine builds the whole file: the twelve-byte entry triple, the tag
// block, the core with its two offsets patched, the subtune table, the tunes
// and the workspace, every piece even-aligned. Each outer entry is bra.w to
// the same entry of the core's own triple, so all three displacements are the
// header's size minus 2.
func sndhCombine(core []byte, tunes [][]byte, tags []byte, maxRing int) []byte {
	var file bytes.Buffer
	header := 12 + len(tags)
	header += header & 1 // the core starts even
	for entry := 0; entry < 3; entry++ {
		file.WriteByte(0x60) // bra.w
		file.WriteByte(0x00)
		file.WriteByte(byte((header - 2) >> 8))
		file.WriteByte(byte((header - 2) & 0xFF))
	}
	file.Write(tags)
	if file.Len()&1 != 0 {
		file.WriteByte(0)
	}

	patched := append([]byte(nil), core...)
	tableOff := len(core) + (len(core) & 1)
	tableSize := 2 + 4*len(tunes)
	at := tableOff + tableSize + (tableSize & 1)
	offsets := make([]int, len(tunes))
	for i := range tunes {
		offsets[i] = at
		at += len(tunes[i])
		at += at & 1
	}
	workOff := at
	sndhPutLong(patched, CoreTableOff, tableOff)
	sndhPutLong(patched, CoreWorkOff, workOff)
	file.Write(patched)

	sndhPad(&file, header+tableOff)
	file.WriteByte(byte(len(tunes) >> 8))
	file.WriteByte(byte(len(tunes) & 0xFF))
	for _, offset := range offsets {
		sndhWriteLong(&file, offset)
	}
	for i := range tunes {
		sndhPad(&file, header+offsets[i])
		file.Write(tunes[i])
	}
	sndhPad(&file, header+workOff)
	workspace := sndhWord(core, CoreWorkFixed) + Streams*maxRing
	file.Write(make([]byte, workspace))
	return file.Bytes()
}

// sndhPad writes zero bytes up to a file position, one at most under these
// layouts.
func sndhPad(file *bytes.Buffer, to int) {
	for file.Len() < to {
		file.WriteByte(0)
	}
}

// sndhTags builds the tag block, 'SNDH' through 'HDNS'. The SNDH
// specification fixes no tag order: its tag table is a list, no sentence in it
// states an ordering rule, and its own example header writes an order this
// combiner does not. The order below fixes one thing of its own: the '##'
// subtune count comes before FRMS and before the subtune names, because a
// reader sizes both of those tables by the count it has read. The two pads put
// FRMS's longs on an even address and 'HDNS' on the even boundary the
// specification requires. claimed is the set's MFP timers, one bit per timer,
// which the FLAG tag spells out.
func sndhTags(options SndhOptions, rate, n int, frms []int, names []string,
	claimed int) []byte {
	var block bytes.Buffer
	block.WriteString("SNDH")
	sndhTag(&block, "TITL", sndhClean(options.Title))
	if options.Composer != "" {
		sndhTag(&block, "COMM", sndhClean(options.Composer))
	}
	sndhTag(&block, "CONV", "Converted from YM by YMX (ZX1 through ST4)")
	sndhTag(&block, fmt.Sprintf("##%02d", n), "")
	sndhTag(&block, "TC"+strconv.Itoa(rate), "")
	sndhTag(&block, "FLAG", sndhFlag(claimed))
	if block.Len()&1 != 0 {
		block.WriteByte(0)
	}
	block.WriteString("FRMS")
	for _, frames := range frms {
		sndhWriteLong(&block, frames)
	}
	// The subtune names: SNDH's own track list. The specification prints this
	// tag '#!SN' in every place it appears, and two parsers that read v2.2
	// files, PSG Play and the AtariAudio library, both match '!#SN'; a file
	// spelled the way the specification prints it loses its names in both. The
	// offsets are words counted from the tag's first byte, one per subtune,
	// which is what PSG Play adds them to - the specification's example writes
	// a base offset and then deltas instead, which lands on the wrong
	// addresses there. doc/BINARIES.md section 2 states both departures.
	block.WriteString("!#SN")
	next := 4 + 2*n // the first name sits after the tag and its offsets
	at := make([]int, n)
	for i := 0; i < n; i++ {
		at[i] = next
		next += len(sndhClean(names[i])) + 1
	}
	for i := 0; i < n; i++ {
		block.WriteByte(byte(at[i] >> 8))
		block.WriteByte(byte(at[i] & 0xFF))
	}
	for i := 0; i < n; i++ {
		block.WriteString(sndhClean(names[i]))
		block.WriteByte(0)
	}
	if block.Len()&1 != 0 {
		block.WriteByte(0)
	}
	block.WriteString("HDNS")
	return block.Bytes()
}

// sndhFlag gives the FLAG tag's value for a set claiming these MFP timers:
// '~', then 'a' to 'd' for each timer some tune in the set claims, then 'y'
// for the YM2149 the player drives. The specification's letters run in that
// order. A claimed timer is one the player takes over at init and hands back
// at exit.
func sndhFlag(claimed int) string {
	const letters = "abcd" // the MFP's four timers
	var value strings.Builder
	value.WriteByte('~')
	for timer := 0; timer < len(letters); timer++ {
		if claimed&(1<<timer) != 0 {
			value.WriteByte(letters[timer])
		}
	}
	value.WriteByte('y')
	return value.String()
}

// sndhTag writes one text tag: the four tag bytes, the value, a closing NUL.
func sndhTag(block *bytes.Buffer, name, value string) {
	block.WriteString(name)
	block.WriteString(value)
	block.WriteByte(0)
}

// sndhSubtuneName gives the name file's nth line, or the tune's own stem.
func sndhSubtuneName(options SndhOptions, n int, tune string) string {
	if len(options.Names) >= n {
		return options.Names[n-1]
	}
	return sndhStripSuffix(filepath.Base(tune), ".ymx")
}

// sndhStripSuffix drops a suffix, whatever case it is written in.
func sndhStripSuffix(name, suffix string) string {
	if len(name) >= len(suffix) &&
		strings.EqualFold(name[len(name)-len(suffix):], suffix) {
		return name[:len(name)-len(suffix)]
	}
	return name
}

// sndhClean keeps printable ASCII and drops the NUL-adjacent risks: titles
// come out of YM headers, which carry any text. The bytes it keeps are the
// bytes a Latin-1 writer emits for them, so the tag block holds what this
// returns.
func sndhClean(text string) string {
	var kept strings.Builder
	for i := 0; i < len(text); i++ {
		if text[i] >= 0x20 && text[i] < 0x7F {
			kept.WriteByte(text[i])
		}
	}
	return kept.String()
}

// sndhResolveCore gives the core for these options, from dist/ beside the
// repository.
func sndhResolveCore(options SndhOptions) (string, error) {
	if named, ok := os.LookupEnv("YMX_CORE"); ok {
		return named, nil
	}
	unit, err := sndhUnitOf(options.Tunes)
	if err != nil {
		return "", err
	}
	repo, err := sndhRepo()
	if err != nil {
		return "", err
	}
	suffix := ""
	if options.Perf {
		suffix += "-perf"
	}
	if !options.MaskBurst {
		suffix += "-nomask"
	}
	name := "ymxsndh-k" + strconv.Itoa(unit) + suffix +
		sndhBinarySuffix() + ".bin"
	core, err := sndhPrebuilt(repo, name,
		"YMX_sndh.S", "YMX.S", "ST4_wrap.S")
	if err != nil {
		return "", fmt.Errorf("mksndh: %w", err)
	}
	return core, nil
}

// sndhPrebuilt gives a binary of this name from dist/, or from the staged
// release beside it.
//
// This tree assembles nothing: rmac is what the other two reach for when a
// core is missing, and not needing it is the point of a standalone build. So
// dist/release is worth reading before giving up - ymx/mkrelease.sh stages
// every core there under the release's own name, and a checkout that has
// staged a release already holds what this needs. Both are held to the same
// rule: a binary older than a source it was assembled from is stale wherever
// it sits.
func sndhPrebuilt(repo, name string, sources ...string) (string, error) {
	beside := filepath.Join(repo, "dist", name)
	if !sndhStale(repo, beside, sources...) {
		return beside, nil
	}
	staged := filepath.Join(repo, "dist", "release", name)
	if !sndhStale(repo, staged, sources...) {
		return staged, nil
	}
	return "", fmt.Errorf("%s is missing or older than the sources it is"+
		" assembled from, and so is %s - take it from the binaries release,"+
		" or assemble it with ymx/mkcores.sh", beside, staged)
}

// sndhStale reports whether a prebuilt binary is missing or older than a
// source it was assembled from, so the resolvers stop rather than combine
// against the repository's past. A source that is not there does not date the
// binary, which is what the C# tool's clock reading of a missing file amounts
// to.
func sndhStale(repo, binary string, sources ...string) bool {
	built, err := os.Stat(binary)
	if err != nil {
		return true
	}
	for _, source := range sources {
		at, err := os.Stat(filepath.Join(repo, "68k", source))
		if err != nil {
			continue
		}
		if at.ModTime().After(built.ModTime()) {
			return true
		}
	}
	return false
}

// sndhUnitOf gives the unit the set is packed at: the first tune that names
// one, and 2 for a set of tunes that read at any unit.
func sndhUnitOf(tunes []string) (int, error) {
	for _, tune := range tunes {
		header, err := ReadHeader(tune)
		if err != nil {
			return 0, fmt.Errorf("mksndh: %w", err)
		}
		if !header.AnyUnit() {
			return header.Unit, nil
		}
	}
	return 2, nil
}

// sndhReadCore reads the core, its descriptor checked against what the caller
// asked for.
func sndhReadCore(path string, options SndhOptions) ([]byte, error) {
	core, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("mksndh: cannot read the core %s", path)
	}
	if len(core) < 34 || core[CoreMagic] != 'Y' || core[CoreMagic+1] != 'M' ||
		core[CoreMagic+2] != 'X' || core[CoreMagic+3] != 'C' {
		return nil, fmt.Errorf("%s is not an SNDH core", path)
	}
	if sndhWord(core, CoreVersion) != 1 {
		return nil, fmt.Errorf("%s is core descriptor version %d, this tool"+
			" writes 1", path, sndhWord(core, CoreVersion))
	}
	if sndhWord(core, CoreFormat) != Version {
		return nil, fmt.Errorf("%s reads format version %s and the tunes carry"+
			" %s - take the core for %s from the binaries release, or"+
			" reassemble it with ymx/mkcores.sh", path,
			VersionName(sndhWord(core, CoreFormat)), FormatName(), FormatName())
	}
	flags := 0
	if options.Perf {
		flags |= CoreFlagPerf
	}
	if !options.MaskBurst {
		flags |= CoreFlagNomask
	}
	if sndhWord(core, CoreFlags) != flags {
		return nil, fmt.Errorf("%s is built with flags %d, the options ask"+
			" for %d", path, sndhWord(core, CoreFlags), flags)
	}
	return core, nil
}

// sndhWord reads a big-endian word.
func sndhWord(data []byte, at int) int {
	return int(data[at])<<8 | int(data[at+1])
}

// sndhPutLong writes a big-endian long over the bytes at a position.
func sndhPutLong(data []byte, at, value int) {
	word := uint32(value)
	data[at] = byte(word >> 24)
	data[at+1] = byte(word >> 16)
	data[at+2] = byte(word >> 8)
	data[at+3] = byte(word)
}

// sndhWriteLong appends a big-endian long.
func sndhWriteLong(file *bytes.Buffer, value int) {
	word := uint32(value)
	file.WriteByte(byte(word >> 24))
	file.WriteByte(byte(word >> 16))
	file.WriteByte(byte(word >> 8))
	file.WriteByte(byte(word))
}

// SndhReadNames reads a name file: one subtune name per line, Latin-1, the
// encoding the tag block is written in.
func SndhReadNames(file string) ([]string, error) {
	text, err := os.ReadFile(file)
	if err != nil {
		return nil, fmt.Errorf("mksndh: cannot read names from %s", file)
	}
	if len(text) == 0 {
		return nil, nil
	}
	body := strings.ReplaceAll(strings.ReplaceAll(string(text), "\r\n", "\n"),
		"\r", "\n")
	lines := strings.Split(body, "\n")
	if lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1] // a final newline ends the last name
	}
	return lines, nil
}

// sndhPlural is the count every builder's size line ends with.
func sndhPlural(n int, noun string) string {
	if n == 1 {
		return fmt.Sprintf("%d %s", n, noun)
	}
	return fmt.Sprintf("%d %ss", n, noun)
}

// sndhBinarySuffix is the prebuilt binaries' name suffix - the release
// version, so files from different releases tell apart on sight.
func sndhBinarySuffix() string {
	return "-v" + ReleaseName()
}

// sndhRepo is the repository the prebuilt binaries sit under: YMX_REPO where
// the caller names it, and otherwise the first directory at or above the
// executable's own that holds 68k/YMX_sndh.S. The C# tool counts four
// directories up from its assembly, a depth its build layout fixes; a Go
// executable sits at no fixed depth, so the walk looks for the source the
// cores are assembled from.
func sndhRepo() (string, error) {
	if named, ok := os.LookupEnv("YMX_REPO"); ok {
		return named, nil
	}
	self, err := os.Executable()
	if err == nil {
		at := filepath.Dir(self)
		for {
			if _, err := os.Stat(filepath.Join(at, "68k", "YMX_sndh.S")); err == nil {
				return at, nil
			}
			up := filepath.Dir(at)
			if up == at {
				break
			}
			at = up
		}
	}
	return "", errors.New("mksndh: cannot find the repository the prebuilt" +
		" binaries sit under - name it in YMX_REPO, or name the core itself" +
		" in YMX_CORE")
}
