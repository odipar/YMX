package st4

import "math/bits"

// none marks a slot no position has reached.
const none = -1 << 31

// The kinds a forward pass records for the winner at a position.
const (
	kindLiterals = 1
	kindRep      = 2
	kindNew      = 3
)

func eliasGammaBits(value int) int {
	return 2*(31-bits.LeadingZeros32(uint32(value))) + 1
}

func clamp(value int64, low, high int) int {
	if value < int64(low) {
		return low
	}
	if value > int64(high) {
		return high
	}
	return int(value)
}

// Optimize runs the forward cost pass and rebuilds the winning chain. The
// candidates are weighed in the same order with the same strictly-better
// replacement rule as the other two trees, so ties fall the same way and the
// bytes match.
func Optimize(units []uint32, unit, offsetLimit int) *Block {
	literalBits := 8 * unit
	count := len(units)
	optimalBits := make([]int, count)
	winKind := make([]byte, count)
	winOffset := make([]int, count)
	winAux := make([]int, count)
	forward(units, literalBits, offsetLimit, optimalBits, winKind, winOffset,
		winAux)
	r := &rebuilder{
		units:       units,
		literalBits: literalBits,
		optimalBits: optimalBits,
		winKind:     winKind,
		winOffset:   winOffset,
		winAux:      winAux,
	}
	return r.rebuild()
}

func forward(units []uint32, literalBits, offsetLimit int, optimalBits []int,
	winKind []byte, winOffset, winAux []int) {
	count := len(units)
	width := clamp(int64(count)-1, InitialOffset, offsetLimit)
	stateBits := make([]int, width+1)
	stateEnd := make([]int, width+1)
	litBits := make([]int, width+1)
	litEnd := make([]int, width+1)
	matchLength := make([]int, width+1)
	for i := range stateEnd {
		stateEnd[i] = none
		litEnd[i] = none
	}
	bestLengthSlots := count
	if bestLengthSlots < 3 {
		bestLengthSlots = 3
	}
	bestLength := make([]int, bestLengthSlots)
	bestLength[2] = 2

	// The fake block every chain hangs from: one unit back, ending just
	// before the stream.
	stateBits[InitialOffset] = -1
	stateEnd[InitialOffset] = -1

	for index := 0; index < count; index++ {
		maxOffset := clamp(int64(index), InitialOffset, offsetLimit)
		bestLengthSize := 2
		unitValue := units[index]
		best := 1<<31 - 1
		for offset := 1; offset <= maxOffset; offset++ {
			if index != 0 && unitValue == units[index-offset] {
				// Match reusing the last offset, after a literal run.
				if litEnd[offset] != none {
					b := litBits[offset] + 1 +
						eliasGammaBits(index-litEnd[offset])
					stateBits[offset] = b
					stateEnd[offset] = index
					if b < best {
						best = b
						winKind[index] = kindRep
						winOffset[index] = offset
						winAux[index] = litEnd[offset]
					}
				}
				// Match with a new offset, at the best split length.
				matchLength[offset]++
				if matchLength[offset] > 1 {
					if bestLengthSize < matchLength[offset] {
						b := optimalBits[index-bestLength[bestLengthSize]] +
							eliasGammaBits(bestLength[bestLengthSize]-1)
						for {
							bestLengthSize++
							shorterBits := optimalBits[index-bestLengthSize] +
								eliasGammaBits(bestLengthSize-1)
							if shorterBits <= b {
								bestLength[bestLengthSize] = bestLengthSize
								b = shorterBits
							} else {
								bestLength[bestLengthSize] =
									bestLength[bestLengthSize-1]
							}
							if bestLengthSize >= matchLength[offset] {
								break
							}
						}
					}
					length := bestLength[matchLength[offset]]
					offsetBits := 8
					if offset > ByteOffsetLimit {
						offsetBits = 16
					}
					newBits := optimalBits[index-length] + 3 + offsetBits +
						eliasGammaBits(length-1)
					if stateEnd[offset] != index || stateBits[offset] > newBits {
						stateBits[offset] = newBits
						stateEnd[offset] = index
						if newBits < best {
							best = newBits
							winKind[index] = kindNew
							winOffset[index] = offset
							winAux[index] = length
						}
					}
				}
			} else {
				// Literals, continuing from the offset's last match.
				matchLength[offset] = 0
				if stateEnd[offset] != none {
					length := index - stateEnd[offset]
					b := stateBits[offset] + 1 + eliasGammaBits(length) +
						length*literalBits
					litBits[offset] = b
					litEnd[offset] = index
					if b < best {
						best = b
						winKind[index] = kindLiterals
						winOffset[index] = offset
						winAux[index] = stateEnd[offset]
					}
				}
			}
		}
		if best == 1<<31-1 {
			panic("every position has a winner")
		}
		optimalBits[index] = best
	}
}

// rebuilder rebuilds an optimal parse chain from what the forward pass
// recorded. Only the blocks the winning chain contains are ever built.
type rebuilder struct {
	units       []uint32
	literalBits int
	optimalBits []int
	winKind     []byte
	winOffset   []int
	winAux      []int
}

func (r *rebuilder) matches(index, offset int) bool {
	return index >= offset && r.units[index] == r.units[index-offset]
}

// frame is a pending resolution: the winner chain at an index, or the state
// an offset held when it last matched there. Frames form a chain of single
// dependencies, resolved with an explicit stack.
type frame struct {
	isState   bool
	offset    int
	index     int
	scanned   bool
	runStart  int
	prevEnd   int // the state before this run
	newLength int // best new-offset split, 0 = none
	newBits   int
}

func stateKey(offset, index int) int64 {
	return int64(offset)<<32 | int64(uint32(index))
}

func (r *rebuilder) rebuild() *Block {
	last := len(r.units) - 1
	winner := make([]*Block, len(r.units))
	states := map[int64]*Block{
		stateKey(InitialOffset, -1): {Bits: -1, Index: -1, Offset: InitialOffset},
	}

	stack := []*frame{{isState: false, offset: 0, index: last, prevEnd: none}}
	for len(stack) > 0 {
		f := stack[len(stack)-1]
		var done bool
		if f.isState {
			done, stack = r.resolveState(f, states, winner, stack)
		} else {
			done, stack = r.resolveWinner(f, states, winner, stack)
		}
		if done {
			stack = stack[:len(stack)-1]
		}
	}
	block := winner[last]
	if block == nil {
		panic("reconstruction did not reach the last position")
	}
	return block
}

func (r *rebuilder) resolveWinner(f *frame, states map[int64]*Block,
	winner []*Block, stack []*frame) (bool, []*frame) {
	index := f.index
	if winner[index] != nil {
		return true, stack
	}
	offset := r.winOffset[index]
	switch r.winKind[index] {
	case kindLiterals:
		state, ok := states[stateKey(offset, r.winAux[index])]
		if !ok {
			return false, append(stack,
				&frame{isState: true, offset: offset, index: r.winAux[index],
					prevEnd: none})
		}
		winner[index] = &Block{Bits: r.optimalBits[index], Index: index,
			Offset: 0, Chain: state}
	case kindRep:
		litAt := r.winAux[index]
		prevEnd := r.previousStateEnd(offset, litAt)
		state, ok := states[stateKey(offset, prevEnd)]
		if !ok {
			return false, append(stack,
				&frame{isState: true, offset: offset, index: prevEnd,
					prevEnd: none})
		}
		winner[index] = &Block{Bits: r.optimalBits[index], Index: index,
			Offset: offset, Chain: r.literalRun(state, litAt)}
	case kindNew:
		previous := winner[index-r.winAux[index]]
		if previous == nil {
			return false, append(stack,
				&frame{isState: false, offset: 0, index: index - r.winAux[index],
					prevEnd: none})
		}
		winner[index] = &Block{Bits: r.optimalBits[index], Index: index,
			Offset: offset, Chain: previous}
	default:
		panic("a position has no winner")
	}
	return true, stack
}

// resolveState finds the state an offset held after matching at f.index: the
// cheaper of reusing the offset across the literal run before this match run,
// and a new-offset match at the best split - the same two candidates the
// forward pass weighed, with the same tie rule.
func (r *rebuilder) resolveState(f *frame, states map[int64]*Block,
	winner []*Block, stack []*frame) (bool, []*frame) {
	offset := f.offset
	end := f.index
	if !f.scanned {
		f.scanned = true
		if !r.matches(end, offset) {
			panic("a state can only end on a match")
		}
		start := end
		for start-1 >= offset && r.matches(start-1, offset) {
			start--
		}
		f.runStart = start
		f.prevEnd = r.previousStateEnd(offset, start-1)
		run := end - start + 1
		if run >= 2 {
			bestCore := 1<<31 - 1
			for length := 2; length <= run; length++ {
				core := r.optimalBits[end-length] + eliasGammaBits(length-1)
				if core <= bestCore { // ties go to the longer split
					bestCore = core
					f.newLength = length
				}
			}
			offsetBits := 8
			if offset > ByteOffsetLimit {
				offsetBits = 16
			}
			f.newBits = bestCore + 3 + offsetBits
		}
	}

	if f.prevEnd != none { // the rep candidate exists
		previous, ok := states[stateKey(offset, f.prevEnd)]
		if !ok {
			return false, append(stack,
				&frame{isState: true, offset: offset, index: f.prevEnd,
					prevEnd: none})
		}
		literal := r.literalRun(previous, f.runStart-1)
		repBits := literal.Bits + 1 + eliasGammaBits(end-f.runStart+1)
		if f.newLength == 0 || repBits <= f.newBits {
			states[stateKey(offset, end)] = &Block{Bits: repBits, Index: end,
				Offset: offset, Chain: literal}
			return true, stack
		}
	}
	if f.newLength == 0 {
		panic("a state is a rep match or a new-offset match")
	}
	before := winner[end-f.newLength]
	if before == nil {
		return false, append(stack,
			&frame{isState: false, offset: 0, index: end - f.newLength,
				prevEnd: none})
	}
	states[stateKey(offset, end)] = &Block{Bits: f.newBits, Index: end,
		Offset: offset, Chain: before}
	return true, stack
}

// literalRun is the literal run from just after state through litEnd.
func (r *rebuilder) literalRun(state *Block, litEnd int) *Block {
	length := litEnd - state.Index
	b := state.Bits + 1 + eliasGammaBits(length) + length*r.literalBits
	return &Block{Bits: b, Index: litEnd, Offset: 0, Chain: state}
}

// previousStateEnd is where this offset's state ended at or before from, or
// none: the last match, provided some adjacent-pair match sits at or below
// it. Offset one counts every match, and with none the fake block is the
// state.
func (r *rebuilder) previousStateEnd(offset, from int) int {
	lastMatch := none
	for index := from; index >= offset; index-- {
		if r.units[index] == r.units[index-offset] {
			if lastMatch == none {
				lastMatch = index
			}
			if offset == InitialOffset ||
				(index-1 >= offset &&
					r.units[index-1] == r.units[index-1-offset]) {
				return lastMatch
			}
		}
	}
	if offset == InitialOffset {
		return -1
	}
	return none
}
