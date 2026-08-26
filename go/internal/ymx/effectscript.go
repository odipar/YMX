package ymx

import "math"

// The compiled effect script: the reference player's per-frame decision logic
// replayed over the whole timeline at pack time, emitting prepared actions the
// player executes without comparing anything against remembered state. The
// stream ABI and the opcode table are the other trees', byte for byte; the
// Java tree carries the full story.

// The action ABI. Opcode 0 is the SID resume - the maxYMiser model.
const (
	OpcodeResume = 0

	// ResumeReload is RESUME flag bit 0: load the count again.
	ResumeReload = 1

	// ResumeVolume is RESUME flag bit 1: patch the toggle volume.
	ResumeVolume = 2

	OpcodeHold    = 1 << 5
	OpcodeRelease = 2 << 5

	// ReleaseMask is RELEASE flag bit 0: mask instead of stopping.
	ReleaseMask = 1

	OpcodeStartToggle = 3 << 5
	OpcodeRetune      = 4 << 5

	// Voiceless is the action byte's voice field addressing no voice; RETUNE
	// addressed to it is the live rate change.
	Voiceless = 3

	OpcodeStartRetrigger  = 5 << 5
	OpcodeStartPcm        = 6 << 5
	OpcodeStartPcmPreempt = 7 << 5

	// The HOLD flags: which of a running effect's values moved this frame.
	HoldReload = 1
	HoldVolume = 2
	HoldShape  = 4
)

// The master byte: which channels act this frame, and the skip bits.
const (
	MChannel0  = 1
	MChannel1  = 2
	MChannel2  = 4
	MChannel3  = 8
	MSkips     = 16
	MSkipShift = 5
)

// Action packs an action byte: the opcode, the voice it addresses, and the
// opcode's own low bits.
func Action(opcode, voice, low int) int {
	return opcode | voice<<3 | low
}

// Semantics is the decisions the codes cannot make for themselves, because
// they follow from how the source format triggers, mixes, stops and retunes
// rather than from anything in the bytes.
type Semantics struct {
	PcmHoldRetriggers bool
	ForceMixerOnPcm   bool
	ChannelEndsPcm    bool
	SidResume         bool
	RetunesLive       bool
}

// YmSemantics is the YM dialect: a held PCM code retriggers its sample every
// frame, a voice a sample owns is forced off the mixer, nothing ends a sample
// but its own marker tick, and a released toggle stream comes back at phase
// zero.
func YmSemantics() Semantics {
	return Semantics{PcmHoldRetriggers: true, ForceMixerOnPcm: true}
}

// Resuming is the same semantics with the maxYMiser gap model.
func (s Semantics) Resuming() Semantics {
	s.SidResume = true
	return s
}

// ScriptResult is the compiled script: the script streams, the mixer bits to
// OR into R7, the sample end edges, and the packer's notes.
type ScriptResult struct {
	Frames  int
	M       []byte
	Actions [][]byte
	Counts  [][]byte
	X       []byte
	Timers  []byte
	R7Force []byte

	// Reopens is one {frame, voice} pair per sample end: where a voice
	// rejoins the frame write.
	Reopens [][2]int

	Notes []string
}

// Streams is M, X, T, then each channel's action and count.
func (r *ScriptResult) Streams() [][]byte {
	streams := make([][]byte, 3+2*len(r.Actions))
	streams[0] = r.M
	streams[1] = r.X
	streams[2] = r.Timers
	for c := 0; c < len(r.Actions); c++ {
		streams[3+2*c] = r.Actions[c]
		streams[4+2*c] = r.Counts[c]
	}
	return streams
}

// stuck marks a voice that never rejoins the frame write: its sample was cut
// mid-play - the reference player's stuck flag. The value is the C# and Java
// trees' int maximum, so the comparisons against it agree.
const stuck = math.MaxInt32

// channelState is one channel's remembered state - the reference player's
// descriptor, field for field, minus the machine addresses.
type channelState struct {
	elast     int
	tlast     int
	vec       int // what the timer vector holds
	vecVoice  int
	sel       int // the ISR's patched select voice
	vol       int // the ISR's patched toggle volume
	shape     int // the ISR's patched retrigger shape
	masked    bool
	prescaler int
}

// effectScript is the compiler's own state for one pass over one tune.
type effectScript struct {
	tune      *Tune
	channels  [Channels]*channelState
	drumEnd   [3]int
	drumOwner [3]int
	skips     int
	reopens   [][2]int
	notes     []string
	semantics Semantics

	m          []byte
	actions    [][]byte
	counts     [][]byte
	x          []byte
	timers     []byte
	r7         []byte
	frames     int
	stuckNoted bool
}

// Compile compiles the script with the timer map a YM tune is packed with.
func Compile(tune *Tune) *ScriptResult {
	return CompileOnTimers(tune, DefaultTimers)
}

// CompileOnTimers compiles the script: one pass over the tune's frames, from
// the state a tune starts in, with the channel-to-timer map the T stream will
// carry.
func CompileOnTimers(tune *Tune, timerMap int) *ScriptResult {
	script := newEffectScript(tune, timerMap)
	return script.run()
}

func newEffectScript(tune *Tune, timerMap int) *effectScript {
	s := &effectScript{
		tune:      tune,
		semantics: tune.Semantics,
		frames:    tune.Frames,
	}
	s.m = make([]byte, s.frames)
	s.x = make([]byte, s.frames)
	s.timers = make([]byte, s.frames)
	for p := range s.timers {
		s.timers[p] = byte(timerMap)
	}
	s.actions = make([][]byte, Channels)
	s.counts = make([][]byte, Channels)
	for c := 0; c < Channels; c++ {
		s.channels[c] = &channelState{vec: -1, vecVoice: -1, sel: -1, vol: -1,
			shape: -1, prescaler: -1}
		s.actions[c] = make([]byte, s.frames)
		s.counts[c] = make([]byte, s.frames)
	}
	s.r7 = make([]byte, s.frames)
	for v := 0; v < 3; v++ {
		s.drumEnd[v] = -1
		s.drumOwner[v] = -1
	}
	return s
}

func (s *effectScript) run() *ScriptResult {
	for p := 0; p < s.frames; p++ {
		s.frame(p)
	}
	return &ScriptResult{
		Frames:  s.frames,
		M:       append([]byte(nil), s.m...),
		Actions: copyStreams(s.actions),
		Counts:  copyStreams(s.counts),
		X:       append([]byte(nil), s.x...),
		Timers:  append([]byte(nil), s.timers...),
		R7Force: append([]byte(nil), s.r7...),
		Reopens: append([][2]int(nil), s.reopens...),
		Notes:   append([]string(nil), s.notes...),
	}
}

// frame does one frame: expire sample windows, then every timer channel in
// turn, lowest first - the order the reference player discovers the same
// events in, and the order arbitration is decided by.
func (s *effectScript) frame(p int) {
	skipsBefore := s.skips
	// X's high nibble is this frame's shape, resolved at pack time.
	s.x[p] = byte(s.shape(p) << 4)

	for v := 0; v < 3; v++ {
		if s.drumOwner[v] >= 0 && s.drumEnd[v] == p {
			s.drumOwner[v] = -1 // the marker has run by now: the
			s.drumEnd[v] = -1   // skip lifts, the mixer frees
			s.skips &^= 1 << v
			s.reopens = append(s.reopens, [2]int{p, v})
		}
	}

	for c := 0; c < len(s.channels); c++ {
		s.doChannel(p, c)
	}

	if s.semantics.ForceMixerOnPcm {
		for v := 0; v < 3; v++ {
			if s.drumOwner[v] >= 0 {
				s.r7[p] |= byte(0x09 << v)
			}
		}
	}
	if s.skips != skipsBefore {
		s.m[p] |= byte(MSkips | s.skips<<MSkipShift)
	}
}

// doChannel is ymx_slot, transcribed: the labels in the comments are the
// reference player's.
func (s *effectScript) doChannel(p, index int) {
	channel := s.channels[index]
	code := int(s.tune.Codes[index][p])
	count := int(s.tune.Counts[index][p])

	if code == channel.elast {
		if code == 0 {
			return // the idle frame
		}
		s.hold(p, index, channel, code, count)
		return
	}
	old := channel.elast
	channel.elast = code
	if code == 0 { // .released
		s.released(p, index, channel, old)
		return
	}
	voice := (code>>4)&3 - 1
	kind := code & 0xC0
	if s.retunesLive(old, code) && s.parameterHeld(p, channel, kind, voice) {
		s.liveRetune(p, index, channel, code, count)
	} else if kind == KindToggle { // .toggle
		s.toggle(p, index, channel, code, count, voice, old)
	} else if kind == KindPcm && s.retunesPcm(old, code) {
		s.pcmRetune(p, index, channel, code, count, voice)
	} else if kind == KindPcm { // .pcm
		s.pcm(p, index, channel, code, count, voice, old)
	} else { // the retrigger arm
		s.retrigger(p, index, channel, code, count, voice)
	}
}

// retunesLive reports whether a rate pop can move the prescaler with the timer
// left running - a source whose own player reprograms live renders a rate
// change as a bend, not a restart.
func (s *effectScript) retunesLive(old, code int) bool {
	return s.semantics.RetunesLive &&
		old != 0 && (old^code)&0xF8 == 0 // only bits 2-0
}

// parameterHeld reports whether the effect's parameter byte held across the
// pop; the live retune carries no voice, so it cannot repatch a volume or a
// shape on the way through.
func (s *effectScript) parameterHeld(p int, channel *channelState,
	kind, voice int) bool {
	if kind == KindToggle {
		return s.parameter(p, voice) == channel.vol
	}
	return kind == KindPcm || s.shape(p) == channel.shape
}

// liveRetune is a rate under a running effect, with nothing stopped: RETUNE
// addressed to voice 3.
func (s *effectScript) liveRetune(p, index int, channel *channelState,
	code, count int) {
	channel.tlast = count
	channel.prescaler = code & 7
	s.emit(p, index, Action(OpcodeRetune, Voiceless, code&7), count)
}

// retunesPcm reports whether a changed PCM code is a rate moving under a
// running sample rather than a fresh trigger.
func (s *effectScript) retunesPcm(old, code int) bool {
	return !s.semantics.PcmHoldRetriggers &&
		old != 0 && old&0xC0 == KindPcm &&
		(old^code)&0xF8 == 0 // only bits 2-0 moved
}

// pcmRetune is a rate under a running sample: RETUNE leaves the vector alone,
// so the PCM tick and its pointer survive.
func (s *effectScript) pcmRetune(p, index int, channel *channelState,
	code, count, voice int) {
	channel.tlast = count
	channel.prescaler = code & 7
	s.emit(p, index, Action(OpcodeRetune, voice, code&7), count)
}

// hold is .held: a running effect's count reload and parameter tracking -
// emitted only on frames where a value changed.
func (s *effectScript) hold(p, index int, channel *channelState,
	code, count int) {
	kind := code & 0xC0
	voice := (code>>4)&3 - 1
	// A source with no trigger but the code itself fires the sample again on
	// every frame that repeats the code.
	if kind == KindPcm && s.semantics.PcmHoldRetriggers {
		s.pcm(p, index, channel, code, count, voice, code)
		return
	}
	flags := 0
	if count != channel.tlast {
		channel.tlast = count
		flags |= HoldReload
	}
	// A PCM stream tracks no register, so a held one carries the reload and
	// nothing else.
	if kind == KindToggle { // .track
		value := s.parameter(p, voice)
		if value != channel.vol {
			channel.vol = value
			flags |= HoldVolume
		}
	} else if kind != KindPcm {
		value := s.shape(p)
		if value != channel.shape {
			channel.shape = value
			flags |= HoldShape
		}
	}
	if flags != 0 {
		s.emit(p, index, Action(OpcodeHold, voice, flags), count)
	}
}

// released is .released: a retrigger stream ending stops its timer; a toggle
// stream forks on the gap model; a PCM stream finishes itself, unless the
// source can say stop.
func (s *effectScript) released(p, index int, channel *channelState, old int) {
	kind := old & 0xC0
	if kind == KindPcm {
		if !s.semantics.ChannelEndsPcm {
			return // timer left running: the marker ends it
		}
		// A stop is applied on the frame it is said: RELEASE with bit 0 clear
		// stops the timer outright and the voice rejoins this frame's own
		// write.
		if s.endOwnPcm(p, index, -1) {
			s.emit(p, index, Action(OpcodeRelease, 0, 0), 0)
		}
		return
	}
	s.cut(p, index, -1)
	if kind == KindToggle {
		s.openOld(old)
		if s.semantics.SidResume {
			channel.masked = true
			s.emit(p, index, Action(OpcodeRelease, 0, ReleaseMask), 0)
			return
		}
	}
	s.emit(p, index, Action(OpcodeRelease, 0, 0), 0)
}

func (s *effectScript) toggle(p, index int, channel *channelState,
	code, count, voice, old int) {
	// A sample this same channel is playing is not a rival for the voice: one
	// timer runs both, so arming the square necessarily ends the sample. The
	// arbitration below is for a sample another channel owns.
	if s.semantics.ChannelEndsPcm {
		s.endOwnPcm(p, index, voice)
	}
	if s.drumOwner[voice] >= 0 { // a PCM stream owns the register:
		channel.elast = 0 // retry next frame
		s.openOld(old)
		return // nothing armed, nothing emitted
	}
	value := s.parameter(p, voice)
	// The gap models fork on masked: a re-arrival on a channel whose masked
	// timer still runs this stream's square at the same prescaler RESUMES; a
	// prescaler change across a masked gap needs the hardware's
	// stop/load/start (RETUNE, half kept); everything else is a full START at
	// phase zero.
	sameSid := channel.vec == KindToggle && channel.vecVoice == voice &&
		channel.sel == voice
	resume := channel.masked && sameSid && channel.prescaler == code&7
	retune := old != 0 && (code^old)&0xF0 == 0 ||
		channel.masked && sameSid && channel.prescaler != code&7
	s.cut(p, index, -1)
	s.openOld(old)
	s.skips |= 1 << voice
	channel.masked = false
	if resume {
		low := 0
		if count != channel.tlast {
			channel.tlast = count
			low |= ResumeReload
		}
		if value != channel.vol {
			channel.vol = value
			low |= ResumeVolume
		}
		s.emit(p, index, Action(OpcodeResume, voice, low), count)
		return
	}
	channel.tlast = count
	channel.vol = value
	channel.prescaler = code & 7
	if retune {
		s.emit(p, index, Action(OpcodeRetune, voice, code&7), count)
		return
	}
	channel.sel = voice
	channel.vec = KindToggle
	channel.vecVoice = voice
	s.emit(p, index, Action(OpcodeStartToggle, voice, code&7), count)
}

func (s *effectScript) pcm(p, index int, channel *channelState,
	code, count, voice, old int) {
	if old != code { // the old-effect cleanup
		if old&0xC0 == KindToggle && old != 0 {
			s.openOld(old)
		} else if old&0xC0 == KindPcm && old != 0 && (old^code)&0x30 != 0 {
			orphan := (old>>4)&3 - 1
			if s.drumOwner[orphan] == index {
				s.drumOwner[orphan] = -1 // cut mid-sample: its
				s.drumEnd[orphan] = -1   // marker never runs, so
				s.skips &^= 1 << orphan  // the start cleans up
			}
		}
	}
	// Preemption: another channel holds a toggle stream on this voice. Its
	// timer stops FIRST, and it retries; X names what to stop.
	stops := 0
	for c := 0; c < len(s.channels); c++ {
		other := s.channels[c]
		if c != index && other.elast&0xC0 == KindToggle && other.elast != 0 &&
			(other.elast>>4)&3-1 == voice {
			other.elast = 0
			stops |= MChannel0 << c
		}
	}
	opcode := OpcodeStartPcm
	if stops != 0 {
		opcode = OpcodeStartPcmPreempt
	}
	s.x[p] |= byte(stops)  // a union: one frame may name several
	s.cut(p, index, voice) // the retrigger's own voice gets a
	channel.tlast = count  // fresh window, not a stuck flag
	channel.masked = false
	channel.prescaler = code & 7
	channel.vec = KindPcm
	channel.vecVoice = voice
	s.skips |= 1 << voice
	s.drumOwner[voice] = index
	if s.looped(p, voice) {
		s.drumEnd[voice] = stuck
	} else {
		s.drumEnd[voice] = p + s.duration(p, code, count, voice)
	}
	s.emit(p, index, Action(opcode, voice, code&7), count)
}

func (s *effectScript) retrigger(p, index int, channel *channelState,
	code, count, voice int) {
	// The same takeover the toggle arm does, with the opposite skip: a
	// retrigger stream writes R13 and never a volume register.
	if s.semantics.ChannelEndsPcm {
		s.endOwnPcm(p, index, -1)
	}
	s.cut(p, index, -1)
	channel.tlast = count
	channel.masked = false
	channel.prescaler = code & 7
	channel.shape = s.shape(p)
	channel.vec = KindRetrigger
	channel.vecVoice = voice
	s.emit(p, index, Action(OpcodeStartRetrigger, voice, code&7), count)
}

// openOld reopens an old toggle stream's voice; only that one rejoins the
// frame write.
func (s *effectScript) openOld(old int) {
	if old != 0 && old&0xC0 == KindToggle {
		s.skips &^= 1 << ((old>>4)&3 - 1)
	}
}

// cut ends a sample this channel still owes ticks to: any action that programs
// or stops the channel's timer means the sample's marker will never run, so
// its voice stays skipped and forced.
func (s *effectScript) cut(p, index, keep int) {
	for v := 0; v < 3; v++ {
		if v == keep {
			continue
		}
		if s.drumOwner[v] == index && s.drumEnd[v] > p && s.drumEnd[v] != stuck {
			s.drumEnd[v] = stuck
			if !s.stuckNoted {
				s.stuckNoted = true
				s.notes = append(s.notes,
					"an effect armed over its own channel's running drum: voice "+
						string(rune('A'+v))+
						" stays skipped, as the reference player left it")
			}
		}
	}
}

// endOwnPcm ends the samples this channel still owns, because the channel was
// told to do something else - the ChannelEndsPcm rule. Reports whether
// anything was taken away.
func (s *effectScript) endOwnPcm(p, index, taken int) bool {
	ended := false
	for v := 0; v < 3; v++ {
		if s.drumOwner[v] != index {
			continue
		}
		s.drumOwner[v] = -1
		s.drumEnd[v] = -1
		ended = true
		if v != taken {
			s.skips &^= 1 << v
			s.reopens = append(s.reopens, [2]int{p, v})
		}
	}
	return ended
}

// parameter is the voice's parameter register byte, as the player reads it:
// YM6's filing convention, and the byte every front end must fill whatever its
// own format does.
func (s *effectScript) parameter(p, voice int) int {
	return int(s.tune.Registers[8+voice][p]) & 15
}

// shape is the shape a retrigger stream would restart on this frame.
func (s *effectScript) shape(p int) int {
	return int(s.tune.Shapes[p]) & 15
}

// looped reports whether the sample this frame triggers on voice loops. A
// looped sample runs until something else takes the voice, so the skip that
// covers it does not lift on the schedule duration gives a one-shot: the frame
// write would reach a register the loop is still writing.
func (s *effectScript) looped(p, voice int) bool {
	number := int(s.tune.Registers[8+voice][p]) & 31
	return number < len(s.tune.SampleLoops) &&
		s.tune.SampleLoops[number] != SampleOneShot
}

// duration is a sample's length in frames, rounded so the reopen is never
// early: the sample plus its marker tick at the timer rate, plus a sixteenth
// of a frame for the arming phase.
func (s *effectScript) duration(p, code, count, voice int) int {
	number := int(s.tune.Registers[8+voice][p]) & 31
	ticks := int64(len(s.tune.Samples[number])) + 1
	divisor := int64(Prescaler(code&7)) * int64(count)
	scaled := ticks*divisor*int64(s.tune.FrameRate) + MfpClock/16
	return int(int32((scaled + MfpClock - 1) / MfpClock))
}

func (s *effectScript) emit(p, index, action, count int) {
	s.m[p] |= byte(MChannel0 << index)
	s.actions[index][p] = byte(action)
	s.counts[index][p] = byte(count)
}

func copyStreams(streams [][]byte) [][]byte {
	copies := make([][]byte, len(streams))
	for c := 0; c < len(streams); c++ {
		copies[c] = append([]byte(nil), streams[c]...)
	}
	return copies
}
