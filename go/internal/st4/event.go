package st4

import "sort"

// The event-driven optimizer: the same costs as the plain DP without visiting
// every (position, offset) pair, doing per-offset work only where a match run
// starts or ends. Every minimum is taken over encoded (key, offset) longs, so
// the result does not depend on the order the occurrence chains are walked -
// which matters here, because Go randomises map iteration and the chains are
// walked in sorted key order to keep one run the same as the next.
//
// It falls back to the plain DP when a cheap event count says the runs are
// too short to pay.

// churn is the ratio of events to positions above which the plain DP wins.
const churn = 8

// absent is the inner key a position without a predecessor groups under.
const absent = -1 << 63

// OptimizeEvents is the parser the tools use. Where the event count is low it
// runs the engine, and otherwise the plain DP, which is what the other trees
// do and what keeps the bytes the same.
func OptimizeEvents(units []uint32, unit, offsetLimit int,
	progress bool) *Block {
	e := newEngine(units, unit, offsetLimit)
	if e.countEvents() > int64(churn)*int64(len(units)) {
		return Optimize(units, unit, offsetLimit, progress)
	}
	e.run(progress)
	r := &rebuilder{
		units:       units,
		literalBits: e.literalBits,
		optimalBits: e.optimalBits,
		winKind:     e.winKind,
		winOffset:   e.winOffset,
		winAux:      e.winAux,
	}
	return r.rebuild()
}

type engine struct {
	units       []uint32
	literalBits int
	offsetLimit int

	optimalBits []int
	winKind     []byte
	winOffset   []int
	winAux      []int

	// Per offset: the state (best chain ending in its last match), the
	// current run's start and frozen literal key.
	stateS     []int
	stateE     []int
	runStartOf []int
	litKeyOf   []int

	// Channel structures: min-trees with per-slot sets where entries retire.
	literalTree *slotTree // by state end e (slot e+1)
	repTree     *slotTree // by run start s
	costTree    *minTree  // recorded costs, argmin position
	byteRuns    runHeap
	wordRuns    runHeap

	// Occurrence chains: positions by (value, predecessor) and (value,
	// successor), newest first, for exact run enumeration.
	byPred   map[uint32]map[int64]int
	bySucc   map[uint32]map[int64]int
	predNext []int
	succNext []int
}

func newEngine(units []uint32, unit, offsetLimit int) *engine {
	count := len(units)
	width := clamp(int64(count)-1, 1, offsetLimit)
	e := &engine{
		units:       units,
		literalBits: 8 * unit,
		offsetLimit: offsetLimit,
		optimalBits: make([]int, count),
		winKind:     make([]byte, count),
		winOffset:   make([]int, count),
		winAux:      make([]int, count),
		stateS:      make([]int, width+1),
		stateE:      make([]int, width+1),
		runStartOf:  make([]int, width+1),
		litKeyOf:    make([]int, width+1),
		literalTree: newSlotTree(count + 1),
		repTree:     newSlotTree(count + 1),
		costTree:    newMinTree(count),
		byPred:      map[uint32]map[int64]int{},
		bySucc:      map[uint32]map[int64]int{},
		predNext:    make([]int, count),
		succNext:    make([]int, count),
	}
	for i := range e.stateE {
		e.stateE[i] = none
		e.runStartOf[i] = -1
	}
	return e
}

// sortedKeys gives a group's keys in a fixed order. The result does not
// depend on the order, but a run of this tool must not depend on Go's map
// randomisation either, or two runs of the same input could differ.
func sortedKeys(groups map[int64]int) []int64 {
	keys := make([]int64, 0, len(groups))
	for k := range groups {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(a, b int) bool { return keys[a] < keys[b] })
	return keys
}

// forEachRunStart visits run starts at j: in-window occurrences of units[j]
// whose predecessor differs from units[j-1], or that have none.
func (e *engine) forEachRunStart(j int, even func(int)) {
	groups, ok := e.byPred[e.units[j]]
	if !ok {
		return
	}
	predecessor := int64(e.units[j-1])
	lowest := int64(j) - int64(e.offsetLimit)
	if lowest < 0 {
		lowest = 0
	}
	for _, key := range sortedKeys(groups) {
		if key == predecessor {
			continue // those continue a run
		}
		for p := groups[key]; int64(p) >= lowest; p = e.predNext[p] {
			even(j - p)
		}
	}
}

// forEachRunEnd visits run ends at e = j-1: matches there whose successor
// differs at j.
func (e *engine) forEachRunEnd(j int, even func(int)) {
	groups, ok := e.bySucc[e.units[j-1]]
	if !ok {
		return
	}
	successor := int64(e.units[j])
	lowest := int64(j-1) - int64(e.offsetLimit)
	if lowest < 0 {
		lowest = 0
	}
	for _, key := range sortedKeys(groups) {
		if key == successor {
			continue // those keep matching
		}
		for p := groups[key]; int64(p) >= lowest; p = e.succNext[p] {
			even(j - 1 - p)
		}
	}
}

// chain records position j for future starts, and j-1 for future ends.
func (e *engine) chain(j int) {
	predecessor := int64(absent)
	if j > 0 {
		predecessor = int64(e.units[j-1])
	}
	groups, ok := e.byPred[e.units[j]]
	if !ok {
		groups = map[int64]int{}
		e.byPred[e.units[j]] = groups
	}
	if old, seen := groups[predecessor]; seen {
		e.predNext[j] = old
	} else {
		e.predNext[j] = none
	}
	groups[predecessor] = j
	if j > 0 {
		after, seen := e.bySucc[e.units[j-1]]
		if !seen {
			after = map[int64]int{}
			e.bySucc[e.units[j-1]] = after
		}
		if previous, had := after[int64(e.units[j])]; had {
			e.succNext[j-1] = previous
		} else {
			e.succNext[j-1] = none
		}
		after[int64(e.units[j])] = j - 1
	}
}

// countEvents is one cheap pass counting run events, to decide engine or
// plain DP.
func (e *engine) countEvents() int64 {
	var events int64
	for j := 0; j < len(e.units); j++ {
		if j > 0 {
			e.forEachRunStart(j, func(int) { events++ })
			e.forEachRunEnd(j, func(int) { events++ })
		}
		e.chain(j)
	}
	// The pass consumed the chains; rebuild them empty for the real run.
	e.byPred = map[uint32]map[int64]int{}
	e.bySucc = map[uint32]map[int64]int{}
	return events
}

func (e *engine) run(progress bool) {
	count := len(e.units)
	meter := NewMeter(TotalSteps(count, 0, e.offsetLimit), progress)

	// The fake state every chain hangs from: offset one, just before the
	// stream, as the reference DP seeds it.
	e.stateS[1] = -1
	e.stateE[1] = -1
	e.literalTree.insert(0, encode(-1-(-1)*e.literalBits, 1))

	for j := 0; j < count; j++ {
		if j > 0 {
			at := j
			e.forEachRunEnd(j, func(offset int) { e.endRun(offset, at-1) })
			e.forEachRunStart(j, func(offset int) { e.startRun(offset, at) })
		}

		best := 1<<31 - 1
		var kind byte
		bestOffset := 0
		aux := 0

		// Literal channel: one range-min per gamma class of the age j-e.
		for t := 0; int64(1)<<t <= int64(j)+1; t++ {
			lowest := j - (1 << (t + 1)) + 1 // e range
			highest := j - (1 << t)
			from := lowest + 1
			if from < 0 {
				from = 0
			}
			enc := e.literalTree.min(from, highest+1)
			if enc == maxInt64 {
				continue
			}
			candidate := key(enc) + j*e.literalBits + 1 + (2*t + 1)
			if candidate < best {
				best = candidate
				kind = kindLiterals
				bestOffset = offsetOf(enc)
				aux = e.stateE[bestOffset]
			}
		}

		// Rep channel: the same, keyed by run start.
		for t := 0; int64(1)<<t <= int64(j)+1; t++ {
			lowest := j - (1 << (t + 1)) + 2 // s range
			highest := j - (1 << t) + 1
			if highest < 1 {
				continue
			}
			from := lowest
			if from < 1 {
				from = 1
			}
			enc := e.repTree.min(from, highest)
			if enc == maxInt64 {
				continue
			}
			candidate := key(enc) + 1 + (2*t + 1)
			if candidate < best {
				best = candidate
				kind = kindRep
				bestOffset = offsetOf(enc)
				aux = e.runStartOf[bestOffset] - 1
			}
		}

		// New-offset channel: range-mins over recorded costs, cut to the
		// longest active run of each offset class.
		byteTop := e.top(&e.byteRuns)
		wordTop := e.top(&e.wordRuns)
		maxByte := 0
		if byteTop != maxInt64 {
			maxByte = j - int(uint64(byteTop)>>16) + 1
		}
		maxWord := 0
		if wordTop != maxInt64 {
			maxWord = j - int(uint64(wordTop)>>16) + 1
		}
		for t := 0; ; t++ {
			lenLo := (1 << t) + 1
			if lenLo > maxWord {
				break
			}
			lenHi := 1 << (t + 1)
			gammaBits := 2*t + 1
			for half := 0; half < 2; half++ {
				reach := lenHi
				if half == 0 {
					if maxByte < reach {
						reach = maxByte
					}
				} else if maxWord < reach {
					reach = maxWord
				}
				if reach < lenLo {
					continue
				}
				enc := e.costTree.min(j-reach, j-lenLo)
				if enc == maxInt64 {
					continue
				}
				offsetBits := 8
				if half != 0 {
					offsetBits = 16
				}
				candidate := int(uint64(enc)>>22) + gammaBits + 3 + offsetBits
				if candidate < best {
					best = candidate
					kind = kindNew
					runTop := byteTop
					if half != 0 {
						runTop = wordTop
					}
					bestOffset = int(runTop & 0xFFFF)
					aux = j - int(enc&0x3FFFFF) // the split
				}
			}
		}

		if best == 1<<31-1 {
			panic("every position has a winner")
		}
		e.optimalBits[j] = best
		e.winKind[j] = kind
		e.winOffset[j] = bestOffset
		e.winAux[j] = aux
		e.costTree.set(j, int64(best)<<22|int64(uint32(j)))

		e.chain(j)
		meter.Advance(int64(clamp(int64(j), 1, e.offsetLimit)))
	}
	meter.Finish()
}

func (e *engine) startRun(offset, start int) {
	e.runStartOf[offset] = start
	if e.stateE[offset] != none {
		length := (start - 1) - e.stateE[offset]
		litKey := e.stateS[offset] + 1 + eliasGammaBits(length) +
			length*e.literalBits
		e.litKeyOf[offset] = litKey
		e.repTree.insert(start, encode(litKey, offset))
	} else {
		e.litKeyOf[offset] = none
	}
	entry := int64(start)<<16 | int64(uint32(offset))
	e.wordRuns.push(entry)
	if offset <= ByteOffsetLimit {
		e.byteRuns.push(entry)
	}
}

func (e *engine) endRun(offset, end int) {
	start := e.runStartOf[offset]
	if start < 0 {
		panic("a run can only end after it started")
	}
	run := end - start + 1
	state := 1<<31 - 1
	if e.litKeyOf[offset] != none {
		e.repTree.remove(start, encode(e.litKeyOf[offset], offset))
		state = e.litKeyOf[offset] + 1 + eliasGammaBits(run)
	}
	if run >= 2 {
		core := e.bestSplit(end, run)
		if core != 1<<31-1 {
			offsetBits := 8
			if offset > ByteOffsetLimit {
				offsetBits = 16
			}
			if core+3+offsetBits < state {
				state = core + 3 + offsetBits
			}
		}
	}
	if state != 1<<31-1 {
		if e.stateE[offset] != none {
			// The reference DP overwrites an offset's state at its next match
			// run regardless of cost; replicate that.
			e.literalTree.remove(e.stateE[offset]+1,
				encode(e.stateS[offset]-e.stateE[offset]*e.literalBits, offset))
		}
		e.literalTree.insert(end+1, encode(state-end*e.literalBits, offset))
		e.stateS[offset] = state
		e.stateE[offset] = end
	}
	e.runStartOf[offset] = -1
}

// bestSplit is the min over lengths 2..reach of cost[end-length] +
// gamma(length-1).
func (e *engine) bestSplit(end, reach int) int {
	best := 1<<31 - 1
	for t := 0; ; t++ {
		lenLo := (1 << t) + 1
		if lenLo > reach {
			break
		}
		lenHi := 1 << (t + 1)
		if reach < lenHi {
			lenHi = reach
		}
		enc := e.costTree.min(end-lenHi, end-lenLo)
		if enc != maxInt64 {
			if candidate := int(uint64(enc)>>22) + 2*t + 1; candidate < best {
				best = candidate
			}
		}
	}
	return best
}

func encode(keyValue, offset int) int64 {
	return int64(keyValue)<<16 | int64(uint32(offset))&0xFFFF
}

func key(encoded int64) int {
	return int(encoded >> 16)
}

func offsetOf(encoded int64) int {
	return int(encoded & 0xFFFF)
}

// top is the smallest valid (start, offset) entry; stale runs pop lazily.
func (e *engine) top(runs *runHeap) int64 {
	for {
		entry, ok := runs.peek()
		if !ok {
			return maxInt64
		}
		if e.runStartOf[int(entry&0xFFFF)] == int(uint64(entry)>>16) {
			return entry
		}
		runs.pop()
	}
}
