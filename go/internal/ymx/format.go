// Package ymx is the .ymx file: its header, the constants a reader and a
// writer share, and what a plain YM2149 makes of a register value. A port of
// dotnet/ymx and src/main/java/org/ymx, held to the same bytes.
package ymx

import (
	"fmt"

	"github.com/odipar/ymx/internal/st4"
)

// Magic opens every file: 'YMX!'.
const Magic = 0x594D5821

// Version is the only format version this build writes or reads: the major
// in the high byte, the minor in the low, so versions order numerically.
const Version = 0x0009

// ReleaseMajor is the binaries' own version, which moves when they change
// and stands when they do not. It is three plain numbers rather than a
// packed word because it reaches no file: the format version above is the
// compatibility gate a header carries and a player checks, and this one
// names a set of binaries. Each sits on its own line so ymx/setversion.sh
// rewrites it without disturbing what gofmt aligns.
const ReleaseMajor = 0

// ReleaseMinor is the release's minor number. See [ReleaseMajor].
const ReleaseMinor = 10

// Patch is the release's patch number. See [ReleaseMajor].
const Patch = 0

// ReleaseName is the release's version as prose, major.minor.patch.
func ReleaseName() string {
	return fmt.Sprintf("%d.%d.%d", ReleaseMajor, ReleaseMinor, Patch)
}

// VersionName reads a version word as prose: VersionName(0x0102) is "1.2".
func VersionName(word int) string {
	return fmt.Sprintf("%d.%d", word>>8, word&0xFF)
}

// FormatName is this build's format version as prose.
func FormatName() string {
	return VersionName(Version)
}

// FlagLoops is header flag bit 0: the tune starts over at frame 0.
const FlagLoops = 1

// FlagChannel is header flag bit 1+channel: the tune uses that timer
// channel.
func FlagChannel(channel int) int {
	return 2 << channel
}

// The stream counts: R0..R13 plus the script streams M, X, T and four A/P
// pairs.
const (
	Streams = 25

	// MaxStreams is the ceiling at this version and every later one: Q, the
	// required-streams mask, is one long with one bit per stream, so a
	// thirty-third stream has no bit to be required by.
	MaxStreams = 32

	// RegisterStreams is the frame streams: one per YM2149 sound register.
	RegisterStreams = 14

	StreamM  = 14
	StreamX  = 15
	StreamT  = 16
	StreamA0 = 17
)

// StreamAction is channel c's action stream; its count stream is the next.
func StreamAction(channel int) int {
	return StreamA0 + 2*channel
}

// LiveStreams is what a player must keep decoding for a tune with these
// header flags: everything up to and including the last channel it names.
func LiveStreams(flags int) int {
	live := StreamA0
	for c := 0; c < Channels; c++ {
		if flags&FlagChannel(c) != 0 {
			live = StreamAction(c) + 2
		}
	}
	return live
}

// Channels is the timer channels the format allows, numbered 0 to 3.
const Channels = 4

// T's two bits per channel: the timer a channel runs on.
const (
	TimerA = 0
	TimerB = 1
	TimerC = 2
	TimerD = 3
)

// DefaultTimers is the map a YM tune is packed with: channels 0 and 1 on
// Timers A and D, where the reference player put its first two.
const DefaultTimers = TimerA | TimerD<<2 | TimerB<<4 | TimerC<<6

// TimerOf is channel c's timer, out of a T byte.
func TimerOf(assignments, channel int) int {
	return assignments >> (2 * channel) & 3
}

// The header's fixed fields, by byte offset.
const (
	OffsetMagic       = 0
	OffsetVersion     = 4
	OffsetFlags       = 6
	OffsetFrames      = 8
	OffsetPlayerHz    = 12
	OffsetStreamCount = 14
	OffsetRingSize    = 16
	OffsetChunk       = 18
	OffsetMasterClock = 20
	OffsetSampleTable = 24
	OffsetSampleCount = 28

	// OffsetLoopFrame is L, the frame a tune that starts over goes back to.
	// It has a meaning only where FlagLoops is set, and a tune that plays
	// once through carries 0.
	OffsetLoopFrame = 30

	// OffsetLoopTable is the byte offset of the loop table: one long per
	// stream, read exactly as the section table is, locating the section
	// that covers frames [L, O). Zero where one section per stream covers
	// the whole tune, which is every file whose pass fits a ring.
	OffsetLoopTable = 34

	// OffsetRequired is Q, the required-streams mask: bit k for stream k. A
	// set bit requires the stream, and a consumer that does not implement it
	// rejects the file; a clear bit on a stream the file carries makes it
	// advisory.
	OffsetRequired = 38

	// OffsetSectionTable is one long offset per stream, in stream order.
	OffsetSectionTable = 42
)

// SectionStored is bit 31 of a section offset: the bytes there are the
// section's values, one per frame, with no container around them.
const SectionStored = 0x8000_0000

// SectionOffset is where a section's bytes begin, stored or container.
func SectionOffset(entry int64) int64 {
	return entry &^ SectionStored
}

// IsStored reports whether a section's bytes are its values rather than a
// container.
func IsStored(entry int64) bool {
	return entry&SectionStored != 0
}

// RequiredBase is the mask a file carrying no extension stream holds: the
// twenty-five streams the specification defines, and nothing above them.
const RequiredBase = 0x01FFFFFF

// SizeOfHeader is the header of a file storing this many sections.
func SizeOfHeader(streams int) int {
	return OffsetSectionTable + 4*streams
}

// HeaderSize is the header of a file storing every base stream.
const HeaderSize = OffsetSectionTable + 4*Streams

// The sample table: a long offset, a word length and a word loop.
const (
	SampleEntrySize = 8

	// SampleOneShot is a sample's loop point when it does not loop.
	SampleOneShot = 0xFFFF

	// SampleEndMark is set on the byte after a sample's last value; the PCM
	// tick reads it as negative and stops.
	SampleEndMark = 0x80

	// MaxSamples is the format's ceiling: a sample number is five bits.
	MaxSamples = 32
)

// The ring and chunk the packer defaults to, and the ring the format caps at.
const (
	DefaultRingSize = 960

	// MaxRingSize is the largest ring the format allows: the player reads
	// stream k's ring through an assembled-in displacement of k*N, and 13*N
	// must fit a signed word.
	MaxRingSize = 2520

	DefaultChunk = 24
)

// CheckShape checks a ring and chunk against what the format and the player
// require: N mod C = 0 is the wrapper's rule, C at or above the live stream
// count is the refill schedule's, N at least 2C keeps the read and write
// groups apart, and 2520 caps 13*N to a signed word displacement.
func CheckShape(ringSize, chunk, unit, live int) string {
	if !st4.IsUnitSize(unit) {
		return st4.CheckUnit(unit)
	}
	if chunk%unit != 0 {
		return fmt.Sprintf("chunk %d is not a whole number of %d-byte units",
			chunk, unit)
	}
	if chunk < live {
		return fmt.Sprintf("chunk %d is below the %d streams this tune decodes,"+
			" so the round-robin refill cannot fit in one cycle", chunk, live)
	}
	if ringSize < 2*chunk {
		return fmt.Sprintf("ring %d must hold two chunks of %d", ringSize, chunk)
	}
	if ringSize%chunk != 0 {
		return fmt.Sprintf("ring %d is not a multiple of chunk %d",
			ringSize, chunk)
	}
	if ringSize > MaxRingSize {
		return fmt.Sprintf("ring %d exceeds %d: the player reads register k's"+
			" ring through an assembled-in displacement of k*N, and 13*N must"+
			" fit a signed word", ringSize, MaxRingSize)
	}
	return ""
}
