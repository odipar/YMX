package ymx

import "fmt"

// Which frame a packed tune starts over from, and what keeping it costs.
//
// A source gives the frame its own player went back to. The file's L is that
// frame where two rules allow it: what the wrap leaves at that frame, and how
// the player reaches it again.
//
// The first is the state the wrap leaves behind. At the end of a pass every
// claimed timer is stopped, its vector parked and every skip bit cleared, so
// frame L is entered with nothing carried in, exactly as frame 0 is entered on
// the first pass. LoopFrameQualifies holds where a frame reads no state an
// earlier frame set, on three conditions: every timer stream running there
// starts there, every skip bit set there is set by that frame's own M, and no
// voice follows the envelope generator before the first frame at or after it
// that writes R13.
//
// The second is how the player reaches the frame again, in one of two ways. A
// wrap that moves the read position in every ring back O - L bytes needs O - L
// at or under N; raising N to hold the body costs workspace and no file bytes,
// so that is what the packer does, up to the format's cap. Past the cap the
// file carries two sections per stream instead - frames [0, L) in the section
// table's, [L, O) in the loop table's - which the player opens in turn
// (SPEC.md 1.4, 8), and that one costs file bytes. Where the state rule holds
// for no frame within the budget, and where a cut has no frame it can start
// at, L is 0 and the packer reports it.

// LoopBudgetSeconds is how far past the frame its source gives the packer
// looks for one it can enter, in seconds. The advance moves the repeat that
// much later, which bounds it; past the bound the file carries 0 and the tune
// starts over from its first frame.
const LoopBudgetSeconds = 1

// LoopBudget is the budget in frames, for a tune at frameRate frames a second.
func LoopBudget(frameRate int) int {
	return LoopBudgetSeconds * frameRate
}

// LoopPlan is what the packer settled on: the Frame the file carries, the
// RingSize it needs to reach it, whether the streams are Cut in two at that
// frame, and the Notes saying what moved and what it cost.
type LoopPlan struct {
	Frame    int
	RingSize int
	Cut      bool
	Notes    []string
}

// ResolveLoopFrame resolves the frame a file starts over from. loops is what
// the file's flag bit 0 will say; ringSize and chunk are the shape the caller
// asked for, and the plan's ring size is that one or a larger multiple of the
// chunk. unit is the size the sections are packed at, which a cut has to fall
// on: each of the two sections is a whole number of units.
func ResolveLoopFrame(tune *Tune, script *ScriptResult, loops bool,
	ringSize, chunk, unit int) LoopPlan {
	var notes []string
	if !loops || tune.LoopFrame == 0 {
		return LoopPlan{Frame: 0, RingSize: ringSize, Cut: false, Notes: notes}
	}
	given := tune.LoopFrame
	budget := LoopBudget(tune.FrameRate)
	last := given + budget
	if tune.Frames-1 < last {
		last = tune.Frames - 1
	}
	// Three answers out of one walk: the first frame that can be entered, the
	// first a ring can reach back over - the body shrinks as the frame moves
	// later, so once one fits every later one does - and the first a cut can
	// start at.
	entered := -1
	ringFrame := -1
	ring := ringSize
	cutFrame := -1
	for candidate := given; candidate <= last; candidate++ {
		if !LoopFrameQualifies(tune, script, candidate) {
			continue
		}
		if entered < 0 {
			entered = candidate
		}
		size := tune.Frames - candidate
		// The chunk divides it and two chunks fit, since a body past the ring
		// is already past a ring of at least two; the cap is what is left to
		// check.
		needed := ringSize
		if size > ringSize {
			needed = (size + chunk - 1) / chunk * chunk
		}
		if needed <= MaxRingSize {
			ringFrame = candidate
			ring = needed
			break
		}
		if cutFrame < 0 && candidate%unit == 0 {
			cutFrame = candidate
		}
	}
	if entered < 0 {
		notes = append(notes, fmt.Sprintf("The source starts over at frame %d,"+
			" and no frame from there to %d can be entered with the timers"+
			" stopped, the skips cleared and the envelope generator not"+
			" restarted: the tune starts over from frame 0 instead, so its"+
			" first %d frames are heard on every pass", given, last, given))
		return LoopPlan{Frame: 0, RingSize: ringSize, Cut: false, Notes: notes}
	}
	if ringFrame < 0 && cutFrame < 0 {
		notes = append(notes, fmt.Sprintf("The source starts over at frame %d,"+
			" leaving %d frames to replay, more than the %d bytes the largest"+
			" ring holds, and no frame from there to %d that can be entered"+
			" falls on a %d-byte unit: the tune starts over from frame 0"+
			" instead, so its first %d frames are heard on every pass",
			given, tune.Frames-entered, MaxRingSize, last, unit, given))
		return LoopPlan{Frame: 0, RingSize: ringSize, Cut: false, Notes: notes}
	}

	frame := cutFrame
	if ringFrame >= 0 {
		frame = ringFrame
	}
	if entered != given {
		notes = append(notes, fmt.Sprintf("The source starts over at frame %d,"+
			" which cannot be entered with the timers stopped, the skips"+
			" cleared and the envelope generator not restarted: the tune"+
			" starts over from frame %d instead, %d frame%s later",
			given, entered, entered-given, plural(entered-given)))
	}
	if ringFrame >= 0 {
		if ring != ringSize {
			notes = append(notes, fmt.Sprintf("Rings raised from %d to %d bytes"+
				" so the %d frames from the loop frame fit one: %d bytes of"+
				" rings rather than %d, and no file bytes. The header carries"+
				" the raised size, and a host sizes its workspace from it",
				ringSize, ring, tune.Frames-frame, Streams*ring,
				Streams*ringSize))
		}
		if frame != entered {
			notes = append(notes, fmt.Sprintf("Frame %d leaves more frames to"+
				" replay than the largest ring holds, so the tune starts over"+
				" from frame %d instead, the first one a ring reaches back"+
				" over", entered, frame))
		}
		return LoopPlan{Frame: frame, RingSize: ring, Cut: false, Notes: notes}
	}
	if frame != entered {
		notes = append(notes, fmt.Sprintf("Frame %d is not a whole number of"+
			" %d-byte units, which a section is: the tune starts over from"+
			" frame %d instead, %d frame%s later", entered, unit, frame,
			frame-entered, plural(frame-entered)))
	}
	replayed := tune.Frames - frame
	notes = append(notes, fmt.Sprintf("The %d frames from frame %d are past the"+
		" %d bytes the largest ring holds, so every stream is packed as two"+
		" sections - one of the %d frames before it, one of the %d from it -"+
		" and the file carries a loop table locating the second: file bytes"+
		" rather than workspace", replayed, frame, MaxRingSize, frame,
		replayed))
	return LoopPlan{Frame: frame, RingSize: ringSize, Cut: true, Notes: notes}
}

// plural is the "s" a count of one leaves off.
func plural(count int) string {
	if count == 1 {
		return ""
	}
	return "s"
}

// LoopFrameQualifies reports whether frame at can be entered with nothing
// carried in.
//
// The walk up to at collects what an earlier frame leaves behind. The skip
// bits hold the last value M gave them, and a channel is armed from the frame
// a toggle or retrigger stream programs its timer until a release stops it: a
// release that only masks the interrupt leaves the counter running towards a
// RESUME, so it leaves the channel armed. A PCM stream is the one that stops
// its own timer, at the marker its sample ends with, and the voice it owns
// carries a skip bit for exactly as long as it plays - so the skip bits mark a
// sample in flight, and a PCM start leaves the channel unarmed.
func LoopFrameQualifies(tune *Tune, script *ScriptResult, at int) bool {
	armed := make([]bool, len(script.Actions))
	skips := 0
	for p := 0; p < at; p++ {
		master := int(script.M[p])
		if master&MSkips != 0 {
			skips = master >> MSkipShift & 7
		}
		for c := 0; c < len(armed); c++ {
			if master&(MChannel0<<c) == 0 {
				continue
			}
			action := int(script.Actions[c][p])
			opcode := action & 0xE0
			if opcode == OpcodeStartToggle || opcode == OpcodeStartRetrigger {
				armed[c] = true
			} else if opcode == OpcodeStartPcm ||
				opcode == OpcodeStartPcmPreempt ||
				opcode == OpcodeRelease && action&ReleaseMask == 0 {
				armed[c] = false
			}
		}
	}

	here := int(script.M[at])
	for c := 0; c < len(armed); c++ {
		startsHere := here&(MChannel0<<c) != 0 && starts(int(script.Actions[c][at]))
		if armed[c] && !startsHere {
			return false
		}
	}
	if skips != 0 && here&MSkips == 0 {
		return false
	}
	return envelopeIsSetBeforeAVoiceHearsIt(tune, at)
}

// starts reports whether this action byte programs a timer from nothing: the
// four start opcodes, and not the RETUNE that sits among them.
func starts(action int) bool {
	opcode := action & 0xE0
	return opcode >= OpcodeStartToggle && opcode != OpcodeRetune
}

// envelopeIsSetBeforeAVoiceHearsIt reports whether the envelope generator is
// restarted before any voice is put on it, counting from frame at. R13's write
// is the restart, and the frame write puts it after R8, R9 and R10, so a frame
// that both writes R13 and puts a voice on the envelope ends with the phase
// set. A frame before that one with a voice on the envelope is driven at a
// phase that depends on the frames played earlier, and those differ between
// the first pass and the rest.
func envelopeIsSetBeforeAVoiceHearsIt(tune *Tune, at int) bool {
	registers := tune.Registers
	for p := at; p < tune.Frames; p++ {
		if int(registers[EnvelopeShape][p]) != NoEnvelopeChange {
			return true
		}
		for voice := 0; voice < 3; voice++ {
			if int(registers[VolumeA+voice][p])&EnvelopeMode != 0 {
				return false
			}
		}
	}
	return true
}
