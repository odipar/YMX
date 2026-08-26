package pack

import "strconv"

// Numeric flag values, checked where the flag is read.
//
// A bare conversion took whatever it was given and let the value travel: -k0
// and -drumhz0 reached a packer that had no use for them, were ignored, and a
// finished file came out that a caller had every reason to think carried what
// they asked for. The other two trees refuse the value at the flag.

// Number reads a numeric flag value, refusing a negative one and, unless
// zeroAllowed, a zero. The second result is the message to fail with.
//
// Zero is a real answer for every part of the trim window - -min0 -sec13 is
// how a caller says thirteen seconds in, and -startframe0 says the same thing
// again. It is nonsense for a ring, a chunk, a unit or a rate ceiling, which
// is why the default stands for those. A window that comes out empty is
// caught where the window is worked out, and says so in those words rather
// than as a bad parameter.
// The width is 32 bits because the other two trees read these values with
// Integer.parseInt and int.TryParse, which stop at 2147483647. Go's int is
// 64 bits here, so a bare conversion took 3000000000 and packed a file where
// the other two refused the value.
func Number(text string, zeroAllowed bool) (int, string) {
	value, err := strconv.ParseInt(text, 10, 32)
	if err != nil || value < 0 || (value == 0 && !zeroAllowed) {
		return 0, "Invalid parameter value " + text
	}
	return int(value), ""
}
