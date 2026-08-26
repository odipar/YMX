package ymx

import (
	"fmt"
	"os"

	"github.com/odipar/ymx/internal/st4"
)

// Header is the .ymx header fields the build tools read, ported from
// dotnet/ymx YmxHeader and org.ymx.YmxHeader. Two of them are not in the
// header: the unit size lives in the first embedded ST4 container's
// signature, and a tune whose sections are all stored reads at any unit and
// reports 0; the channel-to-timer map lives in the T stream, whose first
// frame this unpacks.
type Header struct {
	Ring   int
	Chunk  int
	Unit   int
	Hz     int
	Flags  int
	Frames int
	Timers int
}

// AnyUnit reports whether the tune reads at any unit size, which is a tune
// whose sections are all stored.
func (h Header) AnyUnit() bool {
	return h.Unit == 0
}

// ClaimedTimers is the MFP timers the tune claims, one bit per timer, 1
// shifted by the timer number TimerA and its neighbours give. The player
// claims one timer per timer channel the flags mark, and Timers says which
// timer that channel runs on.
func (h Header) ClaimedTimers() int {
	claimed := 0
	for channel := 0; channel < Channels; channel++ {
		if h.Flags&FlagChannel(channel) != 0 {
			claimed |= 1 << TimerOf(h.Timers, channel)
		}
	}
	return claimed
}

// Loops reports whether the tune starts over.
func (h Header) Loops() bool {
	return h.Flags&FlagLoops != 0
}

// Frms is what SNDH's FRMS tag requires: a tune that starts over is endless,
// so zero.
func (h Header) Frms() int {
	if h.Loops() {
		return 0
	}
	return h.Frames
}

// Shape is the configuration one player build serves - the string the
// mismatch messages compare.
func (h Header) Shape() string {
	return fmt.Sprintf("n%d c%d k%d", h.Ring, h.Chunk, h.Unit)
}

// ReadHeader reads the header of the .ymx file at path.
func ReadHeader(path string) (Header, error) {
	file, err := os.ReadFile(path)
	if err != nil {
		return Header{}, fmt.Errorf("cannot read %s", path)
	}
	if len(file) < HeaderSize ||
		headerWord(file, OffsetMagic) != Magic>>16 ||
		headerWord(file, OffsetMagic+2) != Magic&0xFFFF {
		return Header{}, fmt.Errorf("%s is not a .ymx file", path)
	}
	version := headerWord(file, OffsetVersion)
	if version != Version {
		return Header{}, fmt.Errorf("%s is format version %s, this build"+
			" reads %s - repack the tune from its .ym source", path,
			VersionName(version), FormatName())
	}
	// A stored section carries no signature, so the unit size comes from the
	// first section that is a container - out of either table, since a file
	// cut at its loop frame may store the frames before it and pack the
	// frames from it.
	section := headerContainer(file, OffsetSectionTable)
	loopTable := headerLong(file, OffsetLoopTable)
	if section == 0 && loopTable != 0 {
		section = headerContainer(file, int(loopTable))
	}
	if section+3 >= len(file) {
		return Header{}, fmt.Errorf("%s has no readable first section", path)
	}
	unit := 0
	if section != 0 {
		unit = int(file[section+3])
	}
	timers, err := headerTimerMap(file, path)
	if err != nil {
		return Header{}, err
	}
	return Header{
		Ring:   headerWord(file, OffsetRingSize),
		Chunk:  headerWord(file, OffsetChunk),
		Unit:   unit,
		Hz:     headerWord(file, OffsetPlayerHz),
		Flags:  headerWord(file, OffsetFlags),
		Frames: int(headerLong(file, OffsetFrames)),
		Timers: timers,
	}, nil
}

// headerTimerMap gives the T stream's first frame: the packer writes one
// channel-to-timer map over a whole tune, so frame 0 gives the map. A
// container states its own size, so what is unpacked is the rest of the file
// from the section's first byte.
func headerTimerMap(file []byte, path string) (int, error) {
	entry := headerLong(file, OffsetSectionTable+4*StreamT)
	from := int(SectionOffset(entry))
	if entry == 0 || from >= len(file) {
		return 0, fmt.Errorf("%s has no timer stream", path)
	}
	if IsStored(entry) {
		return int(file[from]), nil
	}
	section, err := st4.Read(file[from:])
	if err != nil {
		return 0, fmt.Errorf("%s: its timer stream is not readable: %w",
			path, err)
	}
	values, err := st4.Decompress(section.Control, section.Literal,
		section.ByteOffsets, section.WordOffsets, section.Unit, section.Size)
	if err != nil {
		return 0, fmt.Errorf("%s: its timer stream is not readable: %w",
			path, err)
	}
	return int(values[0]), nil
}

// headerContainer gives the offset of one table's first section that is a
// container, or 0 where every section it locates is stored.
func headerContainer(file []byte, table int) int {
	for stream := 0; stream < Streams; stream++ {
		entry := headerLong(file, table+4*stream)
		if entry != 0 && !IsStored(entry) {
			return int(SectionOffset(entry))
		}
	}
	return 0
}

// headerWord reads a big-endian word.
func headerWord(file []byte, at int) int {
	return int(file[at])<<8 | int(file[at+1])
}

// headerLong reads a big-endian long as an unsigned 32-bit value, so a
// section entry's stored bit does not read as a sign.
func headerLong(file []byte, at int) int64 {
	return int64(headerWord(file, at))<<16 | int64(headerWord(file, at+2))
}
