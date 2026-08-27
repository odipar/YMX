// Package check reads a packed tune back against SPEC.md §9.3 - the rules a
// player does not check.
//
// A file that breaks one of those rules is undefined behaviour (§9.1): the
// player reads it, drives the chip from it and reports nothing, so a writer
// that breaks one hears the result rather than reading it. This decodes the
// streams and reads the rules back off them.
//
// What it reads is listed in doc/tools.md. Two rules are outside it: the
// sample table's own bounds, which this reads without checking, and R13's
// $FF on every frame that must not restart the envelope - a marker whose
// absence is a value the file is free to carry.
package check

import (
	"fmt"
	"math"
	"math/bits"
	"sort"

	"github.com/odipar/ymx/internal/st4"
	"github.com/odipar/ymx/internal/ymx"
)

// mask is the bits §2's table leaves in each register's value.
var mask = [ymx.RegisterStreams]int{0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F,
	0x1F, 0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F}

var opcodeName = [8]string{"RESUME", "HOLD", "RELEASE", "START_TOGGLE",
	"RETUNE", "START_RETRIGGER", "START_PCM", "START_PCM_PREEMPT"}

const (
	opResume          = 0
	opHold            = 1
	opRelease         = 2
	opStartToggle     = 3
	opRetune          = 4
	opStartRetrigger  = 5
	opStartPCM        = 6
	opStartPCMPreempt = 7
)

// mSkips is M bit 4: bits 7-5 are read this frame.
const mSkips = 0x10

// noVoice is the value a two-bit voice field carries for RETUNE's live form,
// and this package's mark for a channel driving no voice.
const noVoice = 3

// never is the frame a stream that ends at no frame rejoins on.
const never = math.MaxInt32

// The kinds a channel's stream can be, and the absence of one.
type kind int

const (
	kindNone kind = iota
	kindToggle
	kindRetrigger
	kindPCM
)

// Fault is one place the file leaves the rules, and where.
type Fault struct {
	Frame  int
	Rule   string
	Detail string
}

func (f Fault) String() string {
	where := "header"
	if f.Frame >= 0 {
		where = fmt.Sprintf("frame %d", f.Frame)
	}
	return where + ": " + f.Rule + " - " + f.Detail
}

// channel is what one channel's timer is carrying between two action bytes.
type channel struct {
	kind      kind
	voice     int
	prescaler int
	// rejoin is the frame a one-shot could first have finished on.
	rejoin int
	// running says the timer counts.
	running bool
	// disabled says the channel is released, its interrupt down.
	disabled bool
}

func newChannels() []channel {
	channels := make([]channel, ymx.Channels)
	for c := range channels {
		channels[c].voice = noVoice
	}
	return channels
}

// Check is every rule this reads that the file breaks, in frame order.
func Check(file []byte) []Fault {
	var faults []Fault
	add := func(frame int, rule, detail string) {
		faults = append(faults, Fault{frame, rule, detail})
	}
	if len(file) < ymx.HeaderSize ||
		longAt(file, ymx.OffsetMagic) != ymx.Magic {
		add(-1, "§1.1 magic", "the file does not open with 'YMX!'")
		return faults
	}
	version := wordAt(file, ymx.OffsetVersion)
	if version != ymx.Version {
		add(-1, "§1.1 version", "format "+ymx.VersionName(version)+
			", not "+ymx.FormatName())
		return faults
	}
	frames := longAt(file, ymx.OffsetFrames)
	streams := wordAt(file, ymx.OffsetStreamCount)
	ring := wordAt(file, ymx.OffsetRingSize)
	flags := wordAt(file, ymx.OffsetFlags)
	loopFrame := longAt(file, ymx.OffsetLoopFrame)
	loopTable := longAt(file, ymx.OffsetLoopTable)
	faults = shape(file, faults, frames, streams, ring, loopFrame, loopTable)
	if len(faults) > 0 {
		return faults
	}

	// §6's table, which the rejoin bound below reads: one entry of eight
	// bytes per sample, its length at 4 and its loop point at 6.
	sampleTable := longAt(file, ymx.OffsetSampleTable)
	sampleCount := 0
	if sampleTable != 0 {
		sampleCount = wordAt(file, ymx.OffsetSampleCount)
	}
	length := make([]int, sampleCount)
	loop := make([]int, sampleCount)
	for sample := 0; sample < sampleCount; sample++ {
		at := sampleTable + ymx.SampleEntrySize*sample
		if at < 0 || at+ymx.SampleEntrySize > len(file) {
			add(-1, "§6 sample table",
				fmt.Sprintf("entry %d lies outside the file", sample))
			return faults
		}
		length[sample] = wordAt(file, at+4)
		loop[sample] = wordAt(file, at+6)
	}
	rate := wordAt(file, ymx.OffsetPlayerHz)

	value := make([][]byte, ymx.Streams)
	for stream := 0; stream < ymx.Streams; stream++ {
		decoded, err := streamOf(file, stream, frames, loopFrame, loopTable)
		if err != nil {
			add(-1, "§1.4 section", fmt.Sprintf(
				"stream %d does not decode: %s", stream, err))
			continue
		}
		value[stream] = decoded
	}
	if len(faults) > 0 {
		return faults
	}
	faults = registers(faults, value, frames)
	faults = script(faults, value, frames, flags, length, loop, rate)
	return faults
}

// -----------------------------------------------------------------
// The shape
// -----------------------------------------------------------------

func shape(file []byte, faults []Fault, frames, streams, ring, loopFrame,
	loopTable int) []Fault {
	add := func(detail string) {
		faults = append(faults, Fault{-1, "§9.3 shape", detail})
	}
	if frames < 1 {
		add(fmt.Sprintf("O is %d, not at least 1", frames))
	}
	if streams < ymx.Streams || streams > ymx.MaxStreams {
		faults = append(faults, Fault{-1, "§1.5 S", fmt.Sprintf(
			"the stream count is %d, outside %d to %d",
			streams, ymx.Streams, ymx.MaxStreams)})
	}
	if ring < 1 || ring > ymx.MaxRingSize {
		faults = append(faults, Fault{-1, "§1.3 N", fmt.Sprintf(
			"the ring size is %d, outside 1 to %d", ring, ymx.MaxRingSize)})
	}
	for stream := 0; stream < ymx.Streams; stream++ {
		if entry(file, ymx.OffsetSectionTable, stream) == 0 {
			add(fmt.Sprintf("section-table entry %d is 0", stream))
		}
	}
	if loopFrame < 0 {
		add(fmt.Sprintf("L is %d, not a frame index", loopFrame))
		return faults // O - L is read below
	}
	if loopFrame != 0 && loopFrame >= frames {
		add(fmt.Sprintf("L is %d, not below O at %d", loopFrame, frames))
		return faults // O - L is read below
	}
	if loopTable == 0 {
		if loopFrame != 0 && frames-loopFrame > ring {
			add(fmt.Sprintf("one section per stream and O - L is %d, past"+
				" the ring at %d: a wrap reaches back further than a pass",
				frames-loopFrame, ring))
		}
		return faults
	}
	if loopTable%4 != 0 {
		add(fmt.Sprintf("the loop table is at %d, off a long boundary",
			loopTable))
	}
	if loopFrame == 0 {
		add("the file carries a loop table and L is 0")
	}
	if frames-loopFrame <= ring {
		add(fmt.Sprintf("the file carries a loop table and O - L is %d,"+
			" within the ring at %d: one section per stream is the form",
			frames-loopFrame, ring))
	}
	// The table's own extent, which the entries below are read from: a
	// header naming a table past the file's end has no entries to read.
	if loopTable < 0 || loopTable+4*ymx.Streams > len(file) {
		add(fmt.Sprintf("the loop table is at %d, outside the file at %d"+
			" bytes", loopTable, len(file)))
		return faults
	}
	for stream := 0; stream < ymx.Streams; stream++ {
		if entry(file, loopTable, stream) == 0 {
			add(fmt.Sprintf("loop-table entry %d is 0", stream))
		}
	}
	return faults
}

// -----------------------------------------------------------------
// The register values
// -----------------------------------------------------------------

func registers(faults []Fault, value [][]byte, frames int) []Fault {
	for register := 0; register < ymx.RegisterStreams; register++ {
		for frame := 0; frame < frames; frame++ {
			byteValue := int(value[register][frame])
			if register == 13 && byteValue == 0xFF {
				continue // the marker: R13 is not written
			}
			if byteValue&^mask[register] != 0 {
				faults = append(faults, Fault{frame, "§2 register mask",
					fmt.Sprintf("R%d carries %s, outside the mask %s",
						register, hex(byteValue), hex(mask[register]))})
			}
		}
	}
	return faults
}

// -----------------------------------------------------------------
// The script: M, T and the action bytes
// -----------------------------------------------------------------

func script(faults []Fault, value [][]byte, frames, flags int,
	length, loop []int, rate int) []Fault {
	master := value[ymx.StreamM]
	spare := value[ymx.StreamX]
	timers := value[ymx.StreamT]
	live := 0
	for c := 0; c < ymx.Channels; c++ {
		if flags&ymx.FlagChannel(c) != 0 {
			live |= 1 << c
		}
	}
	channels := newChannels()
	var claimed int
	faults, claimed = timerMap(faults, timers, live)
	skips := 0 // a player begins with all three clear
	previousMap := int(timers[0])
	var reported [3]bool

	for frame := 0; frame < frames; frame++ {
		m := int(master[frame])
		if m&^(0x0F|mSkips|0xE0) != 0 {
			faults = append(faults, Fault{frame, "§2.1 M",
				"the master byte is " + hex(m)})
		}
		if m&0x0F&^live != 0 {
			faults = append(faults, Fault{frame, "§9.3 values", fmt.Sprintf(
				"M marks channel %d, which §1.2's flags do not",
				bits.TrailingZeros32(uint32(m&0x0F&^live)))})
		}
		if m&mSkips != 0 {
			skips = (m >> 5) & 7
		}
		faults = mapTimers(faults, frame, timers, previousMap, live, claimed,
			channels)
		previousMap = int(timers[frame])
		for c := 0; c < ymx.Channels; c++ {
			if m&(1<<c) != 0 {
				faults = act(faults, frame, c, channels, value,
					int(spare[frame]), length, loop, rate)
			}
		}
		faults = ownership(faults, frame, skips, channels, &reported)
	}
	return faults
}

// timerMap reads frame 0's byte, which claims a timer per flagged channel,
// all distinct.
func timerMap(faults []Fault, timers []byte, live int) ([]Fault, int) {
	byteValue := int(timers[0])
	claimed := 0
	for c := 0; c < ymx.Channels; c++ {
		if live&(1<<c) == 0 {
			continue
		}
		index := ymx.TimerOf(byteValue, c)
		timer := 1 << index
		if claimed&timer != 0 {
			faults = append(faults, Fault{0, "§9.3 actions",
				"two flagged channels name Timer " + letter("ABCD", index) +
					" at frame 0"})
		}
		claimed |= timer
	}
	return faults, claimed
}

// mapTimers reads a changed T entry, which moves a channel with nothing
// running, to a timer frame 0 claimed.
func mapTimers(faults []Fault, frame int, timers []byte, previous, live,
	claimed int, channels []channel) []Fault {
	byteValue := int(timers[frame])
	if byteValue == previous {
		return faults
	}
	for c := 0; c < ymx.Channels; c++ {
		if live&(1<<c) == 0 ||
			ymx.TimerOf(byteValue, c) == ymx.TimerOf(previous, c) {
			continue
		}
		timer := ymx.TimerOf(byteValue, c)
		if channels[c].running {
			faults = append(faults, Fault{frame, "§2.3 T", fmt.Sprintf(
				"channel %d moves to Timer %s with a timer still running",
				c, letter("ABCD", timer))})
		}
		if claimed&(1<<timer) == 0 {
			faults = append(faults, Fault{frame, "§9.3 actions", fmt.Sprintf(
				"channel %d moves to Timer %s, which frame 0 did not claim",
				c, letter("ABCD", timer))})
		}
	}
	return faults
}

// act reads one channel's action byte, and what it leaves the channel
// carrying.
func act(faults []Fault, frame, c int, channels []channel, value [][]byte,
	spare int, length, loop []int, rate int) []Fault {
	action := int(value[ymx.StreamAction(c)][frame])
	opcode := action >> 5
	voice := (action >> 3) & 3
	low := action & 7
	state := &channels[c]
	name := fmt.Sprintf("%s on channel %d", opcodeName[opcode], c)
	add := func(rule, detail string) {
		faults = append(faults, Fault{frame, rule, detail})
	}

	if opcode == opRelease && voice != 0 {
		add("§2.4 A", fmt.Sprintf(
			"%s names voice %d; the field is written as 0", name, voice))
	}
	if opcode != opRetune && opcode != opRelease && voice == noVoice {
		add("§2.4 A", name+" names voice 3")
	}
	if programs(opcode) && (low < 1 || low > 7) {
		add("§9.3 actions", fmt.Sprintf(
			"%s carries prescaler index %d, outside 1 to 7", name, low))
	}
	switch opcode {
	case opStartToggle:
		faults = claim(faults, frame, channels, c, kindToggle, voice, low)
	case opStartRetrigger:
		faults = claim(faults, frame, channels, c, kindRetrigger, voice, low)
	case opStartPCM:
		triggered(state, value, frame, c, voice, low, length, loop, rate)
		if silenced(channels, c, voice) != 0 {
			add("§9.3 actions", name+" leaves a running timer standing;"+
				" START_PCM_PREEMPT is the encoding where one is stopped")
		}
		faults = claim(faults, frame, channels, c, kindPCM, voice, low)
	case opStartPCMPreempt:
		triggered(state, value, frame, c, voice, low, length, loop, rate)
		nibble := spare & 0x0F
		stops := silenced(channels, c, voice)
		if nibble != stops {
			add("§9.3 actions", fmt.Sprintf(
				"%s marks channels %s in X where the silenced ones are %s",
				name, hex(nibble), hex(stops)))
		}
		for other := 0; other < ymx.Channels; other++ {
			if nibble&(1<<other) != 0 {
				stop(&channels[other])
			}
		}
		faults = claim(faults, frame, channels, c, kindPCM, voice, low)
	case opRelease:
		if state.kind == kindNone {
			add("§3", name+" stops a channel with no stream")
		}
		if low&1 != 0 {
			state.disabled = true // the timer counts on
		} else {
			stop(state)
		}
	case opRetune:
		if state.kind == kindNone {
			add("§3.1", name+" retunes no running stream")
		} else if voice != noVoice && voice != state.voice {
			add("§9.3 actions", fmt.Sprintf(
				"%s moves voice %s to %s; a changed voice re-enters through"+
					" a start opcode", name, letter("ABC", state.voice),
				letter("ABC", voice)))
		}
		state.prescaler = low
		state.running = true
		state.disabled = false
	case opResume:
		if !state.disabled {
			add("§9.3 actions", name+" follows no disabling release")
		}
		if state.kind != kindToggle {
			add("§3.3",
				name+" resumes a stream that is not a toggle stream")
		}
		state.disabled = false
	case opHold:
		if state.kind == kindNone {
			add("§3", name+" updates no running stream")
		}
		if low&2 != 0 && low&4 != 0 {
			add("§9.3 actions", name+" sets both flag 2 and flag 4;"+
				" a channel runs one stream kind")
		}
	}
	return faults
}

// claim takes the channel for a start opcode, and the voice it names.
func claim(faults []Fault, frame int, channels []channel, c int, k kind,
	voice, prescaler int) []Fault {
	if k != kindRetrigger {
		for other := 0; other < ymx.Channels; other++ {
			if other != c && channels[other].voice == voice &&
				channels[other].kind != kindNone &&
				channels[other].kind != kindRetrigger {
				faults = append(faults, Fault{frame, "§9.3 actions",
					fmt.Sprintf("channel %d starts a second timer stream on"+
						" voice %s, which channel %d already runs",
						c, letter("ABC", voice), other)})
			}
		}
	}
	state := &channels[c]
	state.kind = k
	state.voice = voice
	if k == kindRetrigger {
		state.voice = noVoice
	}
	state.prescaler = prescaler
	state.running = true
	state.disabled = false
	return faults
}

func stop(state *channel) {
	state.kind = kindNone
	state.voice = noVoice
	state.running = false
	state.disabled = false
}

func programs(opcode int) bool {
	return opcode == opRetune || opcode == opStartToggle ||
		opcode == opStartRetrigger || opcode == opStartPCM ||
		opcode == opStartPCMPreempt
}

// triggered keeps a trigger's sample and its rate, so the rejoin below can be
// read off them. The sample number is the voice's register byte on this
// frame, which the skip keeps off the chip (§3.2), and the count is the
// trigger's own P.
func triggered(state *channel, value [][]byte, frame, c, voice,
	prescaler int, length, loop []int, rate int) {
	sample := int(value[8+voice][frame])
	count := int(value[ymx.StreamAction(c)+1][frame])
	state.rejoin = rejoinOf(length, loop, sample, prescaler, count, rate,
		frame)
}

// rejoinOf is the frame a one-shot sample started on frame could first have
// ended on, which is §6's rejoin bound:
//
//	frames = ceil(((length + 1) · prescaler[index] · count · rate
//	               + 2457600/16) / 2457600)
//
// A looping sample ends of itself at no frame, so it gives never: a voice it
// owns rejoins the frame write only where something stops it.
func rejoinOf(length, loop []int, sample, prescaler, count, rate,
	frame int) int {
	if sample < 0 || sample >= len(length) {
		return never // no such sample: §6 has it
	}
	if loop[sample] != ymx.SampleOneShot {
		return never // it loops, and ends at no frame
	}
	if count == 0 {
		count = 256
	}
	ticks := int64(length[sample]+1) * int64(ymx.Prescaler(prescaler)) *
		int64(count)
	const clock = int64(2457600)
	frames := (ticks*int64(rate) + clock/16 + clock - 1) / clock
	return frame + int(frames)
}

// silenced is the channels a trigger silences: §9.3's rule is what the
// trigger stops, not what happens to be running. A trigger takes one voice,
// so it silences the channels holding a toggle stream on that voice, and no
// others. Its own channel is reprogrammed rather than stopped, and a stream
// on another voice is untouched.
//
// Counting every running channel instead reported 4,888 faults over 36 of the
// 543 tunes in the collection, all of them a repeated trigger meeting its own
// channel's timer.
func silenced(channels []channel, trigger, voice int) int {
	stops := 0
	for c := 0; c < ymx.Channels; c++ {
		other := channels[c]
		if c != trigger && other.kind == kindToggle && other.running &&
			other.voice == voice {
			stops |= 1 << c
		}
	}
	return stops
}

// ownership reads the skip field against what the streams own.
//
// A voice is skipped while a timer stream writes its volume register (§2.1),
// so a skip set on a voice no stream owns locks that voice out of the frame
// write for as long as it stands, and a skip clear on a voice a toggle stream
// owns has the frame write and the ticks both writing it. A channel released
// under the resume model lands no tick while its interrupt is down (§3.3), so
// it owns nothing across the gap and the voice rejoins the frame write there.
// A PCM stream ends at its sample's marker rather than at an opcode (§6), so
// a cleared skip over one is the rejoin the file is entitled to and ends this
// reader's ownership of the voice.
//
// One fault a run: the frames after an edge carry the same mismatch as the
// edge, and the edge is where the writer put it.
func ownership(faults []Fault, frame, skips int, channels []channel,
	reported *[3]bool) []Fault {
	for voice := 0; voice < 3; voice++ {
		owner := kindNone
		for _, state := range channels {
			if state.voice == voice && state.kind != kindNone &&
				!state.disabled {
				owner = state.kind
			}
		}
		skipped := skips&(1<<voice) != 0
		detail := ""
		switch {
		case skipped && owner == kindNone:
			detail = fmt.Sprintf(" is skipped and no timer stream owns its"+
				" volume register: the frame write omits R%d and no tick"+
				" writes it", 8+voice)
		case !skipped && owner == kindToggle:
			detail = fmt.Sprintf(" is not skipped and a toggle stream owns"+
				" its volume register: the frame write and the ticks both"+
				" write R%d", 8+voice)
		case !skipped && owner == kindPCM:
			// A sample ends at its own marker and the file says nothing of
			// it, so an unskipped voice reads as one that finished. §6
			// bounds when it could have: before that frame it cannot have,
			// and the skip is one the writer did not set.
			earliest := never
			for _, state := range channels {
				if state.voice == voice && state.kind == kindPCM &&
					state.rejoin < earliest {
					earliest = state.rejoin
				}
			}
			if frame < earliest {
				since := fmt.Sprintf(" before frame %d", earliest)
				if earliest == never {
					since = ", since it loops"
				}
				detail = " is not skipped and a PCM stream owns its volume" +
					" register: the sample cannot have finished" + since
			} else {
				for c := range channels {
					if channels[c].voice == voice &&
						channels[c].kind == kindPCM {
						stop(&channels[c]) // the sample reached its marker
					}
				}
			}
		}
		if detail == "" {
			reported[voice] = false
		} else if !reported[voice] {
			faults = append(faults, Fault{frame, "§9.3 values",
				"voice " + letter("ABC", voice) + detail})
			reported[voice] = true
		}
	}
	return faults
}

// -----------------------------------------------------------------
// Reading the container
// -----------------------------------------------------------------

// streamOf is one stream's O values, out of its section and its loop section.
func streamOf(file []byte, index, frames, loopFrame,
	loopTable int) ([]byte, error) {
	if loopTable == 0 {
		return section(file, ymx.OffsetSectionTable, index, frames)
	}
	head, err := section(file, ymx.OffsetSectionTable, index, loopFrame)
	if err != nil {
		return nil, err
	}
	tail, err := section(file, loopTable, index, frames-loopFrame)
	if err != nil {
		return nil, err
	}
	whole := make([]byte, frames)
	copy(whole, head)
	copy(whole[loopFrame:], tail[:frames-loopFrame])
	return whole, nil
}

// section is one section, decoded to at least count values.
func section(file []byte, table, index, count int) ([]byte, error) {
	item := entry(file, table, index)
	start := int(ymx.SectionOffset(item))
	if start < 0 || start > len(file) {
		return nil, fmt.Errorf("the section is at %d, outside the file",
			start)
	}
	if ymx.IsStored(item) {
		return rangeOf(file, start, start+count), nil
	}
	container, err := st4.Read(rangeOf(file, start, next(file, start)))
	if err != nil {
		return nil, err
	}
	out, err := st4.Decompress(container.Control, container.Literal,
		container.ByteOffsets, container.WordOffsets, container.Unit,
		container.Size)
	if err != nil {
		return nil, err
	}
	if len(out) < count {
		return nil, fmt.Errorf("%d values, not %d", len(out), count)
	}
	return out, nil
}

// rangeOf is file[from:to] with a short file read as zeros past its end,
// which is how the other trees read one.
func rangeOf(file []byte, from, to int) []byte {
	out := make([]byte, to-from)
	if from < len(file) {
		copy(out, file[from:min(to, len(file))])
	}
	return out
}

// next is where the body item at start ends: the next offset any table names,
// or the file's end. Content in the body is located by offset alone (§1.1),
// so a section's extent is the distance to its neighbour.
func next(file []byte, start int) int {
	offsets := []int{len(file)}
	if sampleTable := longAt(file, ymx.OffsetSampleTable); sampleTable != 0 {
		offsets = append(offsets, sampleTable)
	}
	loopTable := longAt(file, ymx.OffsetLoopTable)
	if loopTable != 0 {
		offsets = append(offsets, loopTable)
	}
	for index := 0; index < ymx.Streams; index++ {
		offsets = append(offsets, int(ymx.SectionOffset(
			entry(file, ymx.OffsetSectionTable, index))))
		if loopTable != 0 {
			offsets = append(offsets, int(ymx.SectionOffset(
				entry(file, loopTable, index))))
		}
	}
	sort.Ints(offsets)
	for _, offset := range offsets {
		if offset > start {
			return offset
		}
	}
	return len(file)
}

func entry(file []byte, table, index int) int64 {
	return int64(uint32(longAt(file, table+4*index)))
}

func longAt(file []byte, at int) int {
	if at < 0 || at+4 > len(file) {
		return 0
	}
	return int(int32(uint32(file[at])<<24 | uint32(file[at+1])<<16 |
		uint32(file[at+2])<<8 | uint32(file[at+3])))
}

func wordAt(file []byte, at int) int {
	if at < 0 || at+2 > len(file) {
		return 0
	}
	return int(file[at])<<8 | int(file[at+1])
}

func hex(value int) string {
	return fmt.Sprintf("$%02X", value)
}

// letter is one character of a name table, as a string: "ABCD" reads Timer A
// to Timer D, "ABC" reads voice A to voice C.
func letter(names string, index int) string {
	return names[index : index+1]
}
