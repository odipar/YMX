package pack

import (
	"fmt"
	"strings"

	"github.com/odipar/ymx/internal/ymx"
)

// ParseTimers reads the map naming the MFP timer each channel runs on, one
// letter per channel from the first. The channels the spec leaves out take
// the timers it did not, in order, so every channel ends on a timer of its
// own: two channels on one timer is a map no player can honour, and it is
// refused rather than written.
func ParseTimers(spec string) (int, error) {
	letters := []rune(spec)
	if len(letters) == 0 || len(letters) > ymx.Channels {
		return 0, fmt.Errorf("-timers takes one letter per channel, up to %d:"+
			" -timersBC, say", ymx.Channels)
	}
	var taken [4]bool
	timers := [ymx.Channels]int{-1, -1, -1, -1}
	for channel := 0; channel < len(letters); channel++ {
		timer := strings.IndexRune("ABCD", upperRune(letters[channel]))
		if timer < 0 {
			return 0, fmt.Errorf("-timers: '%c' is not one of the MFP's timers"+
				" A, B, C or D", letters[channel])
		}
		if taken[timer] {
			return 0, fmt.Errorf("-timers: two channels cannot both run on"+
				" Timer %c", "ABCD"[timer])
		}
		taken[timer] = true
		timers[channel] = timer
	}
	// The channels the spec left out take the timers it did not, in order.
	spare := 0
	map_ := 0
	for channel := 0; channel < ymx.Channels; channel++ {
		if timers[channel] < 0 {
			for taken[spare] {
				spare++
			}
			taken[spare] = true
			timers[channel] = spare
		}
		map_ |= timers[channel] << (2 * channel)
	}
	return map_, nil
}

// upperRune folds one letter, whatever its width in bytes: the other two
// trees read the spec a character at a time, so a non-ASCII letter is quoted
// whole rather than as the first byte of it.
func upperRune(r rune) rune {
	if r >= 'a' && r <= 'z' {
		return r - 'a' + 'A'
	}
	return r
}

func upper(b byte) byte {
	if b >= 'a' && b <= 'z' {
		return b - 'a' + 'A'
	}
	return b
}
