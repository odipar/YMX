package ymx

import (
	"fmt"

	"github.com/odipar/ymx/internal/st4"
)

// The encoder turns a Tune into a .ymx file: fourteen register vectors
// masked down to what a plain YM2149 receives, the compiled script streams,
// and the sample table, each vector packed as its own embedded ST4 container
// - or stored plain where the values are smaller than a container.
//
// A tune that repeats reaches its loop frame again in one of two ways: the
// player moves the read position in every ring back one pass, or every
// stream's container carries the loop frame as its rewind point, the frames
// from it packed on their own, and the player replays them from the state it
// saved there. Which frame the file carries, and which of the two reaches
// it, is the loop plan's answer; the ring size the plan comes back with is
// the one the file carries.

// StreamCost is what packing one stream's vector produced.
type StreamCost struct {
	Register   int
	Frames     int
	PackedSize int
	LongestOp  int
}

// EncodeResult is the finished file plus the per-stream numbers the commands
// report. Tune is the one packed, RingSize the one the file carries, and
// LoopFrame the L it holds. Notes is what the loop frame moved or cost.
type EncodeResult struct {
	File      []byte
	Streams   []StreamCost
	RingSize  int
	Chunk     int
	Loops     bool
	Unit      int
	LoopFrame int
	Tune      *Tune
	Script    *ScriptResult
	Notes     []string
}

// StartingOver says what the end of the tune does, and the frame it goes back
// to - one sentence, so every command reports it in the same words.
func (r *EncodeResult) StartingOver() string {
	if !r.Loops {
		return "Plays once, then stops"
	}
	if r.LoopFrame == 0 {
		return fmt.Sprintf("Plays through, then starts over from frame 0,"+
			" replaying all of its %d frames", r.Tune.Frames)
	}
	return fmt.Sprintf("Plays through, then starts over from frame %d,"+
		" replaying %d of its %d frames", r.LoopFrame,
		r.Tune.Frames-r.LoopFrame, r.Tune.Frames)
}

// PackedSize is the bytes every stream takes together.
func (r *EncodeResult) PackedSize() int {
	total := 0
	for _, s := range r.Streams {
		total += s.PackedSize
	}
	return total
}

// LongestOp is the longest operation in any stream; over 65535 the file is
// unsafe for the 68000 decoders' word counters.
func (r *EncodeResult) LongestOp() int {
	longest := 0
	for _, s := range r.Streams {
		if s.LongestOp > longest {
			longest = s.LongestOp
		}
	}
	return longest
}

// Encode writes a tune out with the default channel-to-timer map.
func Encode(tune *Tune, ringSize, chunk int, loops, progress bool,
	unit int) (*EncodeResult, error) {
	return EncodeOnTimers(tune, ringSize, chunk, loops, progress, unit,
		DefaultTimers)
}

// EncodeOnTimers is the encoder with the channel-to-timer map the T stream
// carries, and no copy from the literal stream.
func EncodeOnTimers(tune *Tune, ringSize, chunk int, loops, progress bool,
	unit, timerMap int) (*EncodeResult, error) {
	return EncodeCopying(tune, ringSize, chunk, loops, progress, unit,
		timerMap, -1)
}

// EncodeCopying is the whole encoder, with copies from the literal stream
// (SPEC.md Appendix A.5): copies below 0 packs none, 0 packs the search's
// opening passes, and above 0 searches for that many seconds a stream. A
// file with a copy in it sets flag bit 5 and plays only on a player built
// for its ring as a window. From this line down nothing depends on which
// format the tune was read out of.
func EncodeCopying(tune *Tune, ringSize, chunk int, loops, progress bool,
	unit, timerMap int, copies float64) (*EncodeResult, error) {
	// The floor first, on what every tune decodes; the exact check waits for
	// the script, since a tune that leaves channels idle decodes fewer
	// streams and may use a smaller chunk.
	if problem := CheckShape(ringSize, chunk, unit, StreamA0); problem != "" {
		return nil, fmt.Errorf("%s", problem)
	}
	if tune.Frames%unit != 0 {
		return nil, fmt.Errorf("a tune of %d frames cannot be packed in %d-byte"+
			" units: its length must be a multiple of %d",
			tune.Frames, unit, unit)
	}

	// The source's own loop frame is compiled as an entry, so a stream
	// running into it starts there and LoopFrame can keep it.
	entering := 0
	if loops {
		entering = tune.LoopFrame
	}
	script := CompileEntering(tune, timerMap, entering)
	channels := channelsUsed(script)
	if problem := CheckShape(ringSize, chunk, unit,
		LiveStreams(channels)); problem != "" {
		return nil, fmt.Errorf("%s", problem)
	}

	// The loop frame comes before the packing: a plan that rewinds packs the
	// frames from it on their own, so every pass reads one history.
	plan := ResolveLoopFrame(tune, script, loops, ringSize, chunk, unit)
	ringSize = plan.RingSize

	// A back-reference may never reach out of the ring the player decodes
	// through, and the format's own ceiling applies above.
	offsetLimit := ringSize / unit
	if limit := st4.MaxOffsetUnits(unit); limit < offsetLimit {
		offsetLimit = limit
	}
	frames := script.Frames
	vectors := make([][]byte, Streams)
	for register := 0; register < RegisterStreams; register++ {
		values := MaskAll(register, tune.Registers[register])
		if register == 7 {
			values = append([]byte(nil), values...)
			for p := 0; p < frames; p++ {
				values[p] |= script.R7Force[p]
			}
		}
		vectors[register] = values
	}
	vectors[StreamM] = script.M
	vectors[StreamX] = script.X
	vectors[StreamT] = script.Timers
	for c := 0; c < Channels; c++ {
		acts := MChannel0 << c
		action := script.Actions[c]
		vectors[StreamAction(c)] = carry(action, script.M, acts, nil)
		vectors[StreamAction(c)+1] = carry(script.Counts[c], script.M, acts, action)
	}

	// One section per stream. Where the plan rewinds, the container's rewind
	// point is the loop frame and the frames from it are parsed on their
	// own, so a pass after the first reads the history the first one did
	// (SPEC.md §8).
	streams := make([]StreamCost, 0, Streams)
	sections := make([]section, Streams)
	rewindAt := -1
	if plan.Rewinds {
		rewindAt = plan.Frame
	}
	copied := false
	for stream := 0; stream < Streams; stream++ {
		values := vectors[stream]
		sections[stream] = packSection(values, offsetLimit, unit, rewindAt,
			copies, progress)
		copied = copied || sections[stream].Copies > 0
		streams = append(streams, StreamCost{Register: stream,
			Frames: len(values), PackedSize: len(sections[stream].Bytes),
			LongestOp: sections[stream].LongestOp})
	}
	// The twenty-five containers of one file take one loop form. A stream's
	// section holds one byte a frame, so every container's rewind point is
	// the loop frame in bytes, or none.
	if err := loopsAlign(sections, rewindAt, offsetLimit); err != nil {
		return nil, err
	}

	flags := channels
	if copied {
		flags |= FlagCopies
	}
	file := buildFile(tune, ringSize, chunk, frames, loops, plan.Frame,
		sections, tune.Samples, flags)
	return &EncodeResult{
		File: file, Streams: streams, RingSize: ringSize, Chunk: chunk,
		Loops: loops, Unit: unit, LoopFrame: plan.Frame, Tune: tune,
		Script: script, Notes: plan.Notes,
	}, nil
}

// carry repeats a stream byte where it is not meaningful. A byte counts only
// on frames its master bit marks - and for a count stream, only when the
// action reads the count. Everywhere else the previous byte repeats, which
// costs nothing packed.
func carry(values, master []byte, bit int, actions []byte) []byte {
	carried := append([]byte(nil), values...)
	var last byte
	for p := 0; p < len(carried); p++ {
		read := master[p]&byte(bit) != 0
		if read && actions != nil {
			opcode := int(actions[p]) & 0xE0
			// RESUME at voice 3 programs the timer, so its low bits are a
			// prescaler and not the flags ResumeReload sits among: it
			// always reads a count (SPEC.md 3.5).
			resumeRetuned := opcode == OpcodeResume &&
				(int(actions[p])>>3)&3 == Voiceless
			read = opcode >= OpcodeStartToggle || resumeRetuned ||
				opcode == OpcodeHold && int(actions[p])&HoldReload != 0 ||
				opcode == OpcodeResume && int(actions[p])&ResumeReload != 0
		}
		if read {
			last = carried[p]
		} else {
			carried[p] = last
		}
	}
	return carried
}

// section is one section as it goes into the file: packed, or the values
// themselves; Copies counts the blocks copied from the literal stream.
type section struct {
	Bytes     []byte
	Stored    bool
	LongestOp int
	Copies    int
}

// packSection packs one section of one stream; an empty section produces
// nothing. rewindAt is the frame the container carries as its rewind point,
// or -1: the frames from it are parsed apart from the frames before it, so
// no match in the loop reaches before it.
//
// A short section costs more as a container than as itself: twenty-eight of
// the bytes are header before a value is written down, and a one-frame tune
// carries one value. Where the values are the smaller of the two, they are
// what the file gets, and the section's offset says so.
func packSection(values []byte, offsetLimit, unit, rewindAt int,
	copies float64, progress bool) section {
	if len(values) == 0 {
		return section{Bytes: nil}
	}
	units := st4.Split(values, unit)
	var result st4.Result
	if rewindAt < 0 {
		result = st4.CompressRepeating(
			parseUnits(units, unit, offsetLimit, copies, progress),
			units, unit, st4.MaxOp, -1, offsetLimit)
	} else {
		// A value is one byte, so the rewind point in units is the frame
		// over the unit size; the plan holds the frame to a unit boundary.
		rewindIndex := rewindAt / unit
		intro := units[:rewindIndex]
		loop := units[rewindIndex:]
		var introParse *st4.Block
		if len(intro) > 0 {
			introParse = parseUnits(intro, unit, offsetLimit, copies, progress)
		}
		result = st4.CompressRewinding(introParse,
			parseUnits(loop, unit, offsetLimit, copies, progress),
			units, unit, st4.MaxOp, rewindIndex, offsetLimit)
	}
	container := result.Container()
	if len(values) < len(container) {
		return section{Bytes: values, Stored: true, LongestOp: result.LongestOp}
	}
	return section{Bytes: container, Stored: false, LongestOp: result.LongestOp,
		Copies: result.Copies}
}

// parseUnits is the parse: the event-driven optimizer, or with copies the
// search that copies from the literal stream, for copies seconds. Both
// report as they go where progress asks: the search its opening passes, then
// each improvement.
func parseUnits(units []uint32, unit, window int, copies float64,
	progress bool) *st4.Block {
	if copies < 0 {
		return st4.OptimizeEvents(units, unit, window, progress)
	}
	return st4.OptimizeCopies(units, unit, window, st4.MaxOp, copies, progress)
}

// loopsAlign checks that every container of the file carries the one loop
// form the plan chose: a rewind point of rewindAt bytes, or none, and the
// window the ring gives. A stored section carries no header and takes its
// form from the file's. A writer that let one stream differ would hand the
// player a file it cannot detect and cannot play, so the check is here.
func loopsAlign(sections []section, rewindAt, window int) error {
	want := st4.NoRewind
	if rewindAt >= 0 {
		want = rewindAt
	}
	for stream, s := range sections {
		if s.Stored || len(s.Bytes) == 0 {
			continue
		}
		container, err := st4.Read(s.Bytes)
		if err != nil {
			return fmt.Errorf("stream %d: %w", stream, err)
		}
		if container.Rewind != want {
			return fmt.Errorf("stream %d carries rewind %d where the file's"+
				" loop form is %d", stream, container.Rewind, want)
		}
		if container.Window != window {
			return fmt.Errorf("stream %d carries window %d where the ring"+
				" gives %d", stream, container.Window, window)
		}
	}
	return nil
}

func buildFile(tune *Tune, ringSize, chunk, frames int, loops bool,
	loopFrame int, sections []section, samples [][]byte, flags int) []byte {
	// Each section is placed on a long boundary: containers carry alignment
	// rules of their own, and a stored section takes the same boundary - one
	// placement rule.
	total := HeaderSize
	for _, s := range sections {
		total = alignUp(total) + len(s.Bytes)
	}
	sampleTable := 0
	if len(samples) > 0 {
		sampleTable = alignUp(total)
		total = sampleTable + SampleEntrySize*len(samples)
		for _, sample := range samples {
			total += len(sample) + 1 // the end marker byte
		}
	}

	file := make([]byte, alignUp(total))
	putLong(file, OffsetMagic, Magic)
	putWord(file, OffsetVersion, Version)
	// One flag per timer channel: the player claims a timer for each channel
	// named here and leaves the rest to the host.
	if loops {
		flags |= FlagLoops
	}
	putWord(file, OffsetFlags, flags)
	putLong(file, OffsetFrames, int64(frames))
	putWord(file, OffsetPlayerHz, tune.FrameRate)
	putWord(file, OffsetStreamCount, Streams)
	putWord(file, OffsetRingSize, ringSize)
	putWord(file, OffsetChunk, chunk)
	putLong(file, OffsetMasterClock, tune.MasterClock)
	putLong(file, OffsetSampleTable, int64(sampleTable))
	putWord(file, OffsetSampleCount, len(samples))
	// L, the frame the tune starts over from: 0 where it plays once through,
	// and 0 where the packer could not keep the source's own.
	putLong(file, OffsetLoopFrame, int64(loopFrame))
	// Q: this version carries no extension stream, so the mask names the
	// twenty-five the specification defines and nothing above them.
	putLong(file, OffsetRequired, RequiredBase)

	place(file, OffsetSectionTable, sections, HeaderSize)

	// The sample table: entries first, then the samples, each closed by the
	// end marker the PCM tick handler stops on.
	if len(samples) > 0 {
		sample := sampleTable + SampleEntrySize*len(samples)
		for i := range samples {
			putLong(file, sampleTable+SampleEntrySize*i, int64(sample))
			putWord(file, sampleTable+SampleEntrySize*i+4, len(samples[i]))
			putWord(file, sampleTable+SampleEntrySize*i+6, tune.SampleLoops[i])
			copy(file[sample:], samples[i])
			sample += len(samples[i])
			file[sample] = byte(SampleEndMark)
			sample++
		}
	}
	return file
}

// place copies one table's sections into the file and fills in its offsets,
// and reports where the next part may begin.
func place(file []byte, table int, sections []section, at int) int {
	for register := 0; register < Streams; register++ {
		bytes := sections[register].Bytes
		if len(bytes) == 0 {
			continue // no such section: the offset stays 0
		}
		at = alignUp(at)
		entry := int64(at)
		if sections[register].Stored {
			entry |= SectionStored
		}
		putLong(file, table+4*register, entry)
		copy(file[at:], bytes)
		at += len(bytes)
	}
	return at
}

// channelsUsed is the header's channel flags: a bit per timer channel the
// script ever gives something to do.
func channelsUsed(script *ScriptResult) int {
	acting := 0
	for _, b := range script.M {
		acting |= int(b)
	}
	flags := 0
	for c := 0; c < Channels; c++ {
		if acting&(MChannel0<<c) != 0 {
			flags |= FlagChannel(c)
		}
	}
	return flags
}

func alignUp(at int) int {
	return at + ((-at) & 3)
}

func putWord(file []byte, at, value int) {
	file[at] = byte(uint32(value) >> 8)
	file[at+1] = byte(value)
}

func putLong(file []byte, at int, value int64) {
	putWord(file, at, int(uint64(value)>>16))
	putWord(file, at+2, int(value))
}
