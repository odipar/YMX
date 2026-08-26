package ymx

// What a plain YM2149 receives: every YM6 effect bit masked away at packing
// time. R13 is the exception - $FF means "leave the envelope alone" and
// survives packing.

const (
	// NoEnvelopeChange is the envelope shape value meaning "do not write
	// R13".
	NoEnvelopeChange = 0xFF

	// EnvelopeShape is register 13.
	EnvelopeShape = 13

	// VolumeA is register 8, voice A's volume; voices B and C follow it.
	VolumeA = 8

	// EnvelopeMode is bit 4 of a volume register: the voice takes its level
	// from the envelope generator.
	EnvelopeMode = 0x10
)

var registerMasks = [RegisterStreams]byte{
	0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F, 0x1F,
	0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F,
}

// MaskValue is the value the chip would use, effect bits removed.
func MaskValue(register, value int) int {
	if register == EnvelopeShape && value&0xFF == NoEnvelopeChange {
		return NoEnvelopeChange
	}
	return value & int(registerMasks[register])
}

// MaskAll masks a whole register vector, leaving the input untouched.
func MaskAll(register int, values []byte) []byte {
	masked := make([]byte, len(values))
	for i, v := range values {
		masked[i] = byte(MaskValue(register, int(v)))
	}
	return masked
}
