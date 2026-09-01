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
// player moves the read position in every ring back one pass, or every stream
// is packed as two sections - the frames before the loop frame, then the
// frames from it - and the player opens the second one at the wrap. Which
// frame the file carries, and which of the two reaches it, is the loop plan's
// answer; the ring size the plan comes back with is the one the file carries.

// StreamCost is what packing one stream's vector produced.
type StreamCost struct {
	Register   int
	Frames     int
	PackedSize int
	LongestOp  int
	LoopSize   int
}

// FirstSize is the bytes of the section covering the frames before the loop
// frame: the whole of a stream that is not cut.
func (s StreamCost) FirstSize() int {
	return s.PackedSize - s.LoopSize
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

// EncodeOnTimers is the whole encoder, with the channel-to-timer map the T
// stream carries. From this line down nothing depends on which format the
// tune was read out of.
func EncodeOnTimers(tune *Tune, ringSize, chunk int, loops, progress bool,
	unit, timerMap int) (*EncodeResult, error) {
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

	// The loop frame comes before the packing rather than after it: a body
	// that needs a bigger ring gets one, and a bigger ring lets a
	// back-reference reach further, so the sections are packed against the
	// ring the file ends up carrying. A plan that cuts the streams doubles
	// the sections there are to pack.
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

	// One section per stream, or two where the plan cuts them at the loop
	// frame: the first covers the frames before it, the second the frames
	// from it, and the pair is what the stream reports as its cost.
	streams := make([]StreamCost, 0, Streams)
	sections := make([]section, Streams)
	var loopSections []section
	if plan.Cut {
		loopSections = make([]section, Streams)
	}
	for stream := 0; stream < Streams; stream++ {
		values := vectors[stream]
		if loopSections == nil {
			sections[stream] = packSection(values, offsetLimit, unit, progress)
		} else {
			sections[stream] = packSection(values[:plan.Frame], offsetLimit,
				unit, progress)
			loopSections[stream] = packSection(values[plan.Frame:], offsetLimit,
				unit, progress)
		}
		var second *section
		if loopSections != nil {
			second = &loopSections[stream]
		}
		streams = append(streams,
			measure(stream, len(values), sections[stream], second))
	}

	file := buildFile(tune, ringSize, chunk, frames, loops, plan.Frame,
		sections, loopSections, tune.Samples, channels)
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
// themselves.
type section struct {
	Bytes     []byte
	Stored    bool
	LongestOp int
}

// packSection packs one section. A short section costs more as a container
// than as itself, and then the values are what the file gets, with the
// section's offset saying so.
func packSection(values []byte, offsetLimit, unit int, progress bool) section {
	if len(values) == 0 {
		return section{Bytes: nil}
	}
	units := st4.Split(values, unit)
	result := st4.Compress(
		st4.OptimizeEvents(units, unit, offsetLimit, progress),
		units, unit, st4.MaxOp)
	container := result.Container()
	if len(values) < len(container) {
		return section{Bytes: values, Stored: true, LongestOp: result.LongestOp}
	}
	return section{Bytes: container, Stored: false, LongestOp: result.LongestOp}
}

// measure is what one stream costs the file: its frames, and the bytes of the
// one section covering them or of the two that share them.
func measure(stream, frames int, first section, second *section) StreamCost {
	if second == nil {
		return StreamCost{Register: stream, Frames: frames,
			PackedSize: len(first.Bytes), LongestOp: first.LongestOp}
	}
	longest := first.LongestOp
	if second.LongestOp > longest {
		longest = second.LongestOp
	}
	return StreamCost{Register: stream, Frames: frames,
		PackedSize: len(first.Bytes) + len(second.Bytes),
		LongestOp:  longest, LoopSize: len(second.Bytes)}
}

func buildFile(tune *Tune, ringSize, chunk, frames int, loops bool,
	loopFrame int, sections, loopSections []section, samples [][]byte,
	channels int) []byte {
	// Each section is placed on a long boundary: containers carry alignment
	// rules of their own, and a stored section takes the same boundary - one
	// placement rule.
	//
	// The loop table, where there is one, sits between the header and the
	// sections: one more table of the same shape, on a long boundary like
	// everything else in the body.
	total := HeaderSize
	loopTable := 0
	if loopSections != nil {
		loopTable = alignUp(total)
		total = loopTable + 4*Streams
	}
	for _, s := range sections {
		total = alignUp(total) + len(s.Bytes)
	}
	if loopSections != nil {
		for _, s := range loopSections {
			total = alignUp(total) + len(s.Bytes)
		}
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
	flags := channels
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
	// and 0 where the packer could not keep the source's own. The loop table
	// offset is 0 where the sections cover the whole tune, and otherwise
	// where the second set of them is located from.
	putLong(file, OffsetLoopFrame, int64(loopFrame))
	putLong(file, OffsetLoopTable, int64(loopTable))
	// Q: this version carries no extension stream, so the mask names the
	// twenty-five the specification defines and nothing above them.
	putLong(file, OffsetRequired, RequiredBase)

	from := HeaderSize
	if loopTable != 0 {
		from = loopTable + 4*Streams
	}
	at := place(file, OffsetSectionTable, sections, from)
	if loopSections != nil {
		place(file, loopTable, loopSections, at)
	}

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
