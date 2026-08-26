package ym

import (
	"fmt"
	"math"
	"os"
	"unicode"
)

// YmRegisters is the register count in the file: R0..R15.
const YmRegisters = 16

// ADrum4Bits is attribute bit 2: drums hold 4-bit values.
const ADrum4Bits = 4

// Song is one parsed tune, in the file's own terms: what the header said,
// the frames as read, and the samples as stored. Registers[r][frame] is Rr's
// raw value, all sixteen - the I/O ports included, where this format files
// effect counts.
type Song struct {
	Format      string
	Frames      int
	PlayerHz    int
	MasterClock int64
	LoopFrame   int64
	Interleaved bool
	Attributes  int64
	Drums       [][]byte
	Name        string
	Author      string
	Comment     string
	Registers   [][]byte
}

// Digidrums is the number of samples the file carries.
func (s *Song) Digidrums() int {
	return len(s.Drums)
}

// reader reads a YM5!/YM6! register dump, ported from Ym6.Ym6Reader and
// org.ym6.Ym6Reader. Everything here speaks the YM format's own language;
// the engine's vocabulary starts downstream, at the Tune YmEffects builds.
// LHA archives are unpacked on the way in.
type reader struct {
	data []byte
	at   int
}

// Read parses a .ym file, unpacking its LHA wrapper first where it has one.
func Read(data []byte) (*Song, error) {
	if IsArchive(data) {
		unpacked, err := Unpack(data)
		if err != nil {
			return nil, fmt.Errorf(
				"cannot unpack this .ym's LHA wrapper: %w", err)
		}
		data = unpacked
	}
	r := &reader{data: data}
	return r.run()
}

func (r *reader) run() (*Song, error) {
	format, err := r.ascii(4)
	if err != nil {
		return nil, err
	}
	if format != "YM6!" && format != "YM5!" {
		return nil, fmt.Errorf("not a YM5!/YM6! file (starts with %q);"+
			" YM2/YM3/YM4 and packed .ym files are not supported", format)
	}
	check, err := r.ascii(8)
	if err != nil {
		return nil, err
	}
	if check != "LeOnArD!" {
		return nil, fmt.Errorf(
			"missing the LeOnArD! check string after %s", format)
	}

	frames, err := r.u32()
	if err != nil {
		return nil, err
	}
	attributes, err := r.u32()
	if err != nil {
		return nil, err
	}
	digidrums, err := r.u16()
	if err != nil {
		return nil, err
	}
	masterClock, err := r.u32()
	if err != nil {
		return nil, err
	}
	playerHz, err := r.u16()
	if err != nil {
		return nil, err
	}
	loopFrame, err := r.u32()
	if err != nil {
		return nil, err
	}
	additional, err := r.u16()
	if err != nil {
		return nil, err
	}
	if err := r.skip(additional, "additional data"); err != nil {
		return nil, err
	}

	drums := make([][]byte, digidrums)
	for i := 0; i < digidrums; i++ {
		size, err := r.u32()
		if err != nil {
			return nil, err
		}
		if size > int64(len(r.data)-r.at) {
			return nil, fmt.Errorf(
				"truncated file: digidrum %d claims %d bytes", i, size)
		}
		drums[i] = make([]byte, size)
		copy(drums[i], r.data[r.at:])
		r.at += int(size)
	}
	name, err := r.readString()
	if err != nil {
		return nil, err
	}
	author, err := r.readString()
	if err != nil {
		return nil, err
	}
	comment, err := r.readString()
	if err != nil {
		return nil, err
	}

	if frames <= 0 || frames > math.MaxInt32 {
		return nil, fmt.Errorf("unusable frame count %d", frames)
	}
	if playerHz <= 0 {
		return nil, fmt.Errorf("unusable player frequency %d Hz", playerHz)
	}
	count := int(frames)
	interleaved := attributes&1 != 0
	var registers [][]byte
	if interleaved {
		registers, err = r.readInterleaved(count)
	} else {
		registers, err = r.readPerFrame(count)
	}
	if err != nil {
		return nil, err
	}

	// 'End!' closes the file. Some tools omit it; the frames are all read by
	// now, so this only reports, it does not reject.
	if r.at+4 <= len(r.data) {
		end, err := r.ascii(4)
		if err == nil && end != "End!" {
			fmt.Fprintln(os.Stderr,
				"Warning: no End! marker after the frames")
		}
	}
	return &Song{
		Format:      format,
		Frames:      count,
		PlayerHz:    playerHz,
		MasterClock: masterClock,
		LoopFrame:   loopFrame,
		Interleaved: interleaved,
		Attributes:  attributes,
		Drums:       drums,
		Name:        name,
		Author:      author,
		Comment:     comment,
		Registers:   registers,
	}, nil
}

func (r *reader) readInterleaved(frames int) ([][]byte, error) {
	if err := r.need(int64(frames)*YmRegisters,
		"interleaved frame data"); err != nil {
		return nil, err
	}
	registers := make([][]byte, YmRegisters)
	for register := 0; register < YmRegisters; register++ {
		registers[register] = make([]byte, frames)
		copy(registers[register], r.data[r.at:])
		r.at += frames
	}
	return registers, nil
}

func (r *reader) readPerFrame(frames int) ([][]byte, error) {
	if err := r.need(int64(frames)*YmRegisters, "frame data"); err != nil {
		return nil, err
	}
	registers := make([][]byte, YmRegisters)
	for register := 0; register < YmRegisters; register++ {
		registers[register] = make([]byte, frames)
	}
	for frame := 0; frame < frames; frame++ {
		for register := 0; register < YmRegisters; register++ {
			registers[register][frame] = r.data[r.at]
			r.at++
		}
	}
	return registers, nil
}

func (r *reader) need(bytes int64, what string) error {
	if bytes > int64(len(r.data)-r.at) {
		return fmt.Errorf("truncated file: %s needs %d bytes but only %d"+
			" are left", what, bytes, len(r.data)-r.at)
	}
	return nil
}

func (r *reader) skip(bytes int, what string) error {
	if bytes < 0 {
		return fmt.Errorf("negative size for %s", what)
	}
	if err := r.need(int64(bytes), what); err != nil {
		return err
	}
	r.at += bytes
	return nil
}

// ascii reads a fixed-width header field. A byte outside ASCII reads back as
// the replacement character, so a field that is not text still compares
// unequal to the strings this reader accepts.
func (r *reader) ascii(bytes int) (string, error) {
	if err := r.need(int64(bytes), "header field"); err != nil {
		return "", err
	}
	text := make([]rune, bytes)
	for i := 0; i < bytes; i++ {
		value := r.data[r.at+i]
		if value < 0x80 {
			text[i] = rune(value)
		} else {
			text[i] = unicode.ReplacementChar
		}
	}
	r.at += bytes
	return string(text), nil
}

// readString reads a zero-terminated header string. The bytes are Latin-1:
// each one is its own code point.
func (r *reader) readString() (string, error) {
	end := r.at
	for end < len(r.data) && r.data[end] != 0 {
		end++
	}
	if end == len(r.data) {
		return "", fmt.Errorf("unterminated header string")
	}
	text := make([]rune, end-r.at)
	for i := range text {
		text[i] = rune(r.data[r.at+i])
	}
	r.at = end + 1
	return string(text), nil
}

func (r *reader) u16() (int, error) {
	if err := r.need(2, "header field"); err != nil {
		return 0, err
	}
	high := int(r.data[r.at])
	low := int(r.data[r.at+1])
	r.at += 2
	return high<<8 | low, nil
}

func (r *reader) u32() (int64, error) {
	high, err := r.u16()
	if err != nil {
		return 0, err
	}
	low, err := r.u16()
	if err != nil {
		return 0, err
	}
	return int64(high)<<16 | int64(low), nil
}
