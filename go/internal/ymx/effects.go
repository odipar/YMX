package ymx

import (
	"fmt"
	"math"
	"sort"
	"strings"
)

// The YM front end's far side: a Song in, a Tune out, every dialect and every
// unplayable code normalized away. A YM6 frame carries up to two effect slots
// smeared across spare register bits; both dialects come out as the same code
// and count byte pairs per frame. Codes the reference player would not start
// are dropped and counted; a too-fast drum is rescued by resampling.

// Song is one parsed YM tune in the file's own terms: what the header said,
// the frames as read, and the samples as stored. Registers[r][frame] is Rr's
// raw value, all sixteen - the I/O ports included, where that format files
// effect counts. The reader fills it; the fields here are the ones the
// extraction reads, so the two packages stay apart.
type Song struct {
	Format      string
	Frames      int
	PlayerHz    int
	MasterClock int64
	LoopFrame   int64
	Attributes  int64
	Drums       [][]byte
	Name        string
	Author      string
	Comment     string
	Registers   [][]byte
}

// YmRegisters is the register count in a YM file: R0..R15.
const YmRegisters = 16

// AttributeDrum4Bits is YM attribute bit 2: drums hold 4-bit values.
const AttributeDrum4Bits = 4

// Digidrums is how many samples the file carries.
func (s *Song) Digidrums() int {
	return len(s.Drums)
}

// MaxTimerHz is the fastest tick rate a real player programs.
const MaxTimerHz = 25600

// Extraction is what the reader's frames become, and what this extraction has
// to say about the file: codes and counts per timer channel per frame, the
// converted samples, the drop counters, and one note per resampled sample.
type Extraction struct {
	Codes       [][]byte
	Counts      [][]byte
	Samples     [][]byte
	Inert       int
	TooFast     int
	Sinus       int
	MissingDrum int
	Notes       []string
}

// Dropped is how many effect codes this extraction threw away.
func (e *Extraction) Dropped() int {
	return e.Inert + e.TooFast + e.Sinus + e.MissingDrum
}

// ymEffects is the extraction's own state for one pass over one song.
type ymEffects struct {
	song     *Song
	samples  [][]byte
	num      []int // per-sample divisor scale num/den;
	den      []int // 1/1 = the sample plays as dumped
	divisors []map[int]bool
	drumHz   int
	notes    []string

	inert       int
	tooFast     int
	sinus       int
	missingDrum int
}

func newYmEffects(song *Song, drumHz int) *ymEffects {
	e := &ymEffects{song: song, drumHz: drumHz}
	e.samples = convertSamples(song)
	e.num = make([]int, len(e.samples))
	e.den = make([]int, len(e.samples))
	e.divisors = make([]map[int]bool, len(e.samples))
	for i := 0; i < len(e.samples); i++ {
		e.num[i] = 1
		e.den[i] = 1
		e.divisors[i] = map[int]bool{}
	}
	return e
}

// BuildTune is a dump as the engine has it, at the standard ceiling.
func BuildTune(song *Song) (*Tune, error) {
	return BuildTuneOver(song, Extract(song))
}

// BuildTuneOver is a dump as the engine has it, over an extraction already
// made. Only the fourteen sound registers cross, UNMASKED: the script still
// reads a PCM stream's sample number and a toggle stream's volume out of a
// voice's volume register, and the encoder masks the frame streams itself.
func BuildTuneOver(song *Song, fx *Extraction) (*Tune, error) {
	// A YM header always gives a loop frame, and its players always went
	// round. The frame crosses as the source gives it, and the packer answers
	// for it: a frame at or past the end is no frame, so it comes across as 0.
	notes := fx.Notes
	given := song.LoopFrame
	loopFrame := 0
	if given > 0 && given < int64(song.Frames) {
		loopFrame = int(given)
	}
	if given >= int64(song.Frames) {
		noted := append([]string(nil), notes...)
		noted = append(noted, fmt.Sprintf("The YM header loops from frame %d,"+
			" which is past the %d the file holds; the tune starts over from"+
			" frame 0", given, song.Frames))
		notes = noted
	}
	registers := make([][]byte, RegisterStreams)
	copy(registers, song.Registers)
	return NewTune(Tune{
		Frames:      song.Frames,
		FrameRate:   song.PlayerHz,
		MasterClock: song.MasterClock,
		Loops:       true,
		LoopFrame:   loopFrame,
		Registers:   registers,
		Codes:       fx.Codes,
		Counts:      fx.Counts,
		Shapes:      shapesOf(song, fx),
		Samples:     fx.Samples,
		SampleLoops: oneShot(len(fx.Samples)),
		Semantics:   YmSemantics(),
		Name:        song.Name,
		Author:      song.Author,
		Comment:     song.Comment,
		Notes:       notes,
	})
}

// oneShot marks every sample a hit: YM has no way to say a sample loops, and
// the reference player's own drum tick stops at the end.
func oneShot(samples int) []int {
	loops := make([]int, samples)
	for i := range loops {
		loops[i] = SampleOneShot
	}
	return loops
}

// shapesOf is the envelope shape a retrigger stream would restart, frame by
// frame, as ST-Sound arrives at it: R13's write first, then each slot's buzzer
// nibble, the second slot winning by arriving last.
func shapesOf(song *Song, fx *Extraction) []byte {
	shapes := make([]byte, song.Frames)
	shape := 0 // ST-Sound's reset leaves it here
	for frame := 0; frame < song.Frames; frame++ {
		written := int(song.Registers[EnvelopeShape][frame])
		if written != NoEnvelopeChange {
			shape = written & 15
		}
		for slot := 0; slot < len(fx.Codes); slot++ {
			code := int(fx.Codes[slot][frame])
			if code != 0 && code&0xC0 == KindRetrigger {
				voice := (code>>4)&3 - 1
				shape = int(song.Registers[8+voice][frame]) & 15
			}
		}
		shapes[frame] = byte(shape)
	}
	return shapes
}

// Extract reads the effect bits at the standard ceiling.
func Extract(song *Song) *Extraction {
	return ExtractUpTo(song, MaxTimerHz)
}

// ExtractUpTo reads the effect bits with drumHz as the ceiling a sample's tick
// rate is held to.
func ExtractUpTo(song *Song, drumHz int) *Extraction {
	effects := newYmEffects(song, drumHz)
	effects.downsample()
	frames := song.Frames
	// A YM frame carries two effect slots and no more, so only the first two
	// channels are ever written here.
	codes := make([][]byte, Channels)
	counts := make([][]byte, Channels)
	for c := 0; c < Channels; c++ {
		codes[c] = make([]byte, frames)
		counts[c] = make([]byte, frames)
	}
	ym6 := strings.HasPrefix(song.Format, "YM6")
	for frame := 0; frame < frames; frame++ {
		var slot1, slot2 int
		if ym6 {
			slot1 = effects.validate(effects.register(1, frame)&0xF0,
				effects.register(6, frame)>>5,
				effects.register(14, frame), frame)
			slot2 = effects.validate(effects.register(3, frame)&0xF0,
				effects.register(8, frame)>>5,
				effects.register(15, frame), frame)
		} else {
			// YM5: R1 bits 5-4 are a SID voice, R3 bits 5-4 a drum voice, and
			// a YM5 drum's prescaler always sits in R8.
			slot1 = effects.validate(
				KindToggle|(effects.register(1, frame)&0x30),
				effects.register(6, frame)>>5,
				effects.register(14, frame), frame)
			slot2 = effects.validate(
				KindPcm|(effects.register(3, frame)&0x30),
				effects.register(8, frame)>>5,
				effects.register(15, frame), frame)
		}
		codes[0][frame] = byte(slot1 >> 8)
		counts[0][frame] = byte(slot1)
		codes[1][frame] = byte(slot2 >> 8)
		counts[1][frame] = byte(slot2)
	}
	return &Extraction{
		Codes:       codes,
		Counts:      counts,
		Samples:     effects.samples,
		Inert:       effects.inert,
		TooFast:     effects.tooFast,
		Sinus:       effects.sinus,
		MissingDrum: effects.missingDrum,
		Notes:       append([]string(nil), effects.notes...),
	}
}

// downsample surveys every drum trigger and rescues the samples whose rate
// exceeds the ceiling: each is resampled to the highest MFP-representable rate
// under it, every trigger's divisor scaled by the same exact ratio, with the
// power-of-two factor as the fallback.
func (e *ymEffects) downsample() {
	ym6 := strings.HasPrefix(e.song.Format, "YM6")
	for frame := 0; frame < e.song.Frames; frame++ {
		first := 0
		if ym6 {
			first = e.register(1, frame) & 0xF0
		}
		e.surveyDrum(first, e.register(6, frame)>>5,
			e.register(14, frame), frame)
		second := 0
		if ym6 {
			second = e.register(3, frame) & 0xF0
		} else if e.register(3, frame)&0x30 != 0 {
			second = KindPcm | (e.register(3, frame) & 0x30)
		}
		e.surveyDrum(second, e.register(8, frame)>>5,
			e.register(15, frame), frame)
	}
	for i := 0; i < len(e.samples); i++ {
		if len(e.divisors[i]) == 0 {
			continue
		}
		seen := sortedDivisors(e.divisors[i])
		fastest := seen[0]
		if int64(e.drumHz)*int64(fastest) >= MfpClock {
			continue // the fastest trigger fits already
		}
		target := e.ceilingDivisor()
		g := gcd(target, fastest)
		n := target / g
		d := fastest / g
		exact := true
		for _, divisor := range seen {
			scaled := int64(divisor) * int64(n)
			if scaled%int64(d) != 0 ||
				!representable(int(int32(scaled/int64(d)))) {
				exact = false
				break
			}
		}
		if !exact { // the old rescue: a power of two
			n = 1
			d = 1
			for int64(e.drumHz)*int64(fastest)*int64(n) < MfpClock && n < 64 {
				n *= 2
			}
		}
		e.num[i] = n
		e.den[i] = d
		source := e.samples[i]
		outLength := int(int32(int64(len(source)) * int64(d) / int64(n)))
		if outLength < 1 {
			outLength = 1
		}
		e.samples[i] = resample(source, outLength)
		e.notes = append(e.notes, fmt.Sprintf(
			"drum %d resampled %d -> %d Hz to fit %d Hz (-drumhz to change)",
			i, MfpClock/fastest,
			int64(MfpClock)*int64(d)/(int64(fastest)*int64(n)), e.drumHz))
	}
}

func (e *ymEffects) surveyDrum(code, prescaler, count, frame int) {
	if code&0xC0 != KindPcm || code&0x30 == 0 {
		return
	}
	prescaler &= 7
	count &= 0xFF
	if Prescaler(prescaler) == 0 || count == 0 {
		return
	}
	number := e.register(8+((code&0x30)>>4)-1, frame) & 31
	if number >= len(e.samples) {
		return
	}
	e.divisors[number][Prescaler(prescaler)*count] = true
}

// sortedDivisors is the divisors a sample was triggered at, fastest first.
func sortedDivisors(set map[int]bool) []int {
	seen := make([]int, 0, len(set))
	for divisor := range set {
		seen = append(seen, divisor)
	}
	sort.Ints(seen)
	return seen
}

// ceilingDivisor is the smallest MFP-representable divisor whose rate is at or
// under the ceiling.
func (e *ymEffects) ceilingDivisor() int {
	needed := (MfpClock + e.drumHz - 1) / e.drumHz
	best := math.MaxInt32
	for p := 1; p < Prescalers; p++ {
		count := (needed + Prescaler(p) - 1) / Prescaler(p)
		if count <= 255 && Prescaler(p)*count < best {
			best = Prescaler(p) * count
		}
	}
	return best
}

func representable(divisor int) bool {
	for p := 1; p < Prescalers; p++ {
		if divisor%Prescaler(p) == 0 {
			count := divisor / Prescaler(p)
			if count >= 1 && count <= 255 {
				return true
			}
		}
	}
	return false
}

func gcd(a, b int) int {
	for b != 0 {
		t := a % b
		a = b
		b = t
	}
	return a
}

// curve is the chip's volume curve, per the reference player.
var curve = [16]int{62, 161, 265, 377, 580, 774, 1155, 1575, 2260, 3088, 4570,
	6233, 9330, 13187, 21220, 32767}

// resample is a windowed-sinc resample of a volume-index sample: indices
// become amplitudes through the chip curve, a Hann-windowed sinc low-passes at
// the target band, and the result quantizes back to the nearest index.
func resample(source []byte, outLength int) []byte {
	const taps = 12
	step := float64(len(source)) / float64(outLength)
	cutoff := math.Min(1.0, 1.0/step)
	resampled := make([]byte, outLength)
	for j := 0; j < outLength; j++ {
		center := (float64(j)+0.5)*step - 0.5
		baseAt := int(math.Floor(center))
		acc := 0.0
		weight := 0.0
		for m := baseAt - taps + 1; m <= baseAt+taps; m++ {
			t := (float64(m) - center) * cutoff
			x := (float64(m) - center) / taps
			sinc := 1.0
			if t != 0 {
				sinc = math.Sin(math.Pi*t) / (math.Pi * t)
			}
			w := (0.5 + 0.5*math.Cos(math.Pi*x)) * sinc
			at := m
			if at < 0 {
				at = 0
			}
			if at > len(source)-1 {
				at = len(source) - 1
			}
			acc += w * float64(curve[source[at]&15])
			weight += w
		}
		amplitude := acc / weight
		nearest := 0
		for i := 1; i < 16; i++ {
			if math.Abs(float64(curve[i])-amplitude) <
				math.Abs(float64(curve[nearest])-amplitude) {
				nearest = i
			}
		}
		resampled[j] = byte(nearest)
	}
	return resampled
}

// fit fits a timer divisor into the MFP's prescaler table: the smallest
// prescaler whose count divides exactly and fits a byte, or 0 when none does.
func fit(code, divisor int) int {
	for p := 1; p < Prescalers; p++ {
		if divisor%Prescaler(p) == 0 {
			count := divisor / Prescaler(p)
			if count >= 1 && count <= 255 {
				return ((code&0xF0)|p)<<8 | count
			}
		}
	}
	return 0
}

func (e *ymEffects) register(register, frame int) int {
	return int(e.song.Registers[register][frame])
}

// validate is one slot's E and T bytes packed as (E << 8) | T; zero when the
// slot is idle or the code cannot be played.
func (e *ymEffects) validate(code, prescaler, count, frame int) int {
	voiceBits := code & 0x30
	if voiceBits == 0 {
		return 0 // the slot is idle this frame
	}
	kind := code & 0xC0
	if kind == KindCurve {
		e.sinus++
		return 0
	}
	prescaler &= 7
	count &= 0xFF
	if Prescaler(prescaler) == 0 || count == 0 {
		e.inert++ // the reference player's no-op
		return 0
	}
	if kind == KindPcm {
		voice := (voiceBits >> 4) - 1
		number := e.register(8+voice, frame) & 31
		if number >= len(e.samples) {
			e.missingDrum++
			return 0
		}
		// The drum's sample may have been resampled: every trigger scales its
		// divisor by the same exact ratio.
		if e.num[number] > e.den[number] {
			scaled := int64(Prescaler(prescaler)) * int64(count) *
				int64(e.num[number]) / int64(e.den[number])
			fitted := fit(code, int(int32(scaled)))
			if fitted == 0 {
				e.tooFast++
			}
			return fitted
		}
	}
	hz := MfpClock / (Prescaler(prescaler) * count)
	ceiling := MaxTimerHz
	if kind == KindPcm {
		ceiling = e.drumHz
	}
	if hz > ceiling {
		e.tooFast++ // samples use their own ceiling, so
		return 0    // -drumhz above 25600 works too
	}
	return ((code&0xF0)|prescaler)<<8 | count
}

// convertSamples is the drum samples as PSG volume values 0..15, one per byte,
// without the end markers.
func convertSamples(song *Song) [][]byte {
	count := song.Digidrums()
	if count > MaxSamples {
		count = MaxSamples
	}
	converted := make([][]byte, count)
	fourBit := song.Attributes&AttributeDrum4Bits != 0
	for i := 0; i < count; i++ {
		source := song.Drums[i]
		drum := make([]byte, len(source))
		for j := 0; j < len(source); j++ {
			if fourBit {
				drum[j] = source[j] & 15
			} else {
				drum[j] = source[j] >> 4
			}
		}
		converted[i] = drum
	}
	return converted
}
