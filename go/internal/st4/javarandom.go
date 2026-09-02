package st4

// javaRandom is java.util.Random, the 48-bit LCG, so the search steps as the
// Java one does from the same seed and packs the same bytes. math/rand
// produces a different sequence.
type javaRandom struct {
	seed int64
}

const (
	randMultiplier = 0x5DEECE66D
	randAddend     = 0xB
	randMask       = (1 << 48) - 1
)

func newJavaRandom(seed int64) *javaRandom {
	return &javaRandom{seed: (seed ^ randMultiplier) & randMask}
}

func (r *javaRandom) next(bits uint) int32 {
	r.seed = (r.seed*randMultiplier + randAddend) & randMask
	return int32(uint64(r.seed) >> (48 - bits))
}

// nextInt is a value from 0 up to bound, exclusive.
func (r *javaRandom) nextInt(bound int) int {
	if bound <= 0 {
		panic("bound must be positive")
	}
	if bound&-bound == bound {
		return int((int64(bound) * int64(r.next(31))) >> 31)
	}
	for {
		bits := r.next(31)
		value := bits % int32(bound)
		if bits-value+(int32(bound)-1) >= 0 {
			return int(value)
		}
	}
}

func (r *javaRandom) nextBoolean() bool {
	return r.next(1) != 0
}

func (r *javaRandom) nextDouble() float64 {
	return float64(int64(r.next(26))<<27+int64(r.next(27))) * (1.0 / (1 << 53))
}
