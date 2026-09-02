package st4

import (
	"fmt"
	"math"
	"math/bits"
	"os"
	"time"
)

// The optimizer for streams with copies from the literal stream: a search
// over which units are literal, each step scored by an exact parse for that
// choice and by what the compressor then writes, for as long as it is given.
//
// A dictionary is a set of forced literals: they stay literal, a copy comes
// only from them, the parse decides the rest. The opening passes, what
// st4 -c alone writes, take the literals of a full-window parse, fill holes
// of a few units, and shrink the dictionary to what gets copied from. Given
// time, a sweep frees or trims every literal run, keeping what packs
// smaller; then random moves free, seed, extend or trim runs, accepted when
// they pack smaller and by annealing when they do not, and the search
// returns to the best and sweeps again when it stalls. The parse is the fast
// optimizer's DP with copies added: sources found through two-unit chains
// over the dictionary, the rep of a copy as a ring rep at the same output
// distance with literal shadows at the source, the literal channel a
// min-tree keyed by match end, chains rebuilt from a node pool, and every
// parse restarted from a checkpoint before the first changed unit. A copy is
// costed with the dictionary's own literal count, a lower bound, so every
// copy is valid; the compressor's bits are the score.

const (
	searchNone = math.MinInt32

	ckLiterals byte = 0
	ckRep      byte = 1 // a ring match reusing the last offset
	ckNew      byte = 2 // a ring match at a new offset
	ckCopy     byte = 3 // a copy from the literal stream
	ckCopyRep  byte = 4 // a rep of the last copy, after literals

	// searchHole is the widest hole between dictionary runs the opening
	// passes fill, in units.
	searchHole = 3

	// searchPasses is the opening passes, at most.
	searchPasses = 4

	// The annealing temperature, in bits, at the start and at the end.
	searchHot  = 10.0
	searchCold = 0.3

	// searchPatience is the steps without a new best before the search
	// returns to the best.
	searchPatience = 2000
)

func gammaBits(value int) int {
	return 2*(31-bits.LeadingZeros32(uint32(value))) + 1
}

// OptimizeCopies searches for seconds, zero for the opening passes alone,
// and returns the best parse found: copies from the literal stream as
// negative offsets, matches within window as positive ones. maxOpLength is
// the compressor's operation limit, which the score counts; progress reports
// on stdout: the opening passes as the Meter does, then each improvement.
func OptimizeCopies(units []uint32, unit, window, maxOpLength int,
	seconds float64, progress bool) *Block {
	deadline := time.Now().Add(time.Duration(seconds * 1e9))
	return newCopySearch(units, unit, window, maxOpLength, 1, progress).run(
		deadline, math.MaxInt64)
}

// ---------------------------------------------------------------- search

type copySearch struct {
	units       []uint32
	unit        int
	window      int
	maxOpLength int
	count       int
	random      *javaRandom
	parser      *copyParser

	// The incumbent: its dictionary is exactly its literals.
	forced []bool
	chain  *Block
	bits   int
	runs   [][3]int // literal runs {start, end, referenced}
	copies [][3]int // copies and matches {start, end, isCopy}

	best     *Block
	bestBits int

	// The budget: a deadline, a step count, and the steps taken.
	deadline     time.Time
	stepsAllowed int64
	started      time.Time
	step         int64
	accepted     int64
	lastBest     int64
	progress     bool
}

func newCopySearch(units []uint32, unit, window, maxOpLength int,
	seed int64, progress bool) *copySearch {
	s := &copySearch{
		units:       units,
		unit:        unit,
		window:      window,
		maxOpLength: maxOpLength,
		count:       len(units),
		random:      newJavaRandom(seed),
		progress:    progress,
		parser:      newCopyParser(units, unit, window),
		started:     time.Now(),
	}
	// The opening passes: the full-window parse's literals, holes filled,
	// shrunk to what gets copied from.
	reach := MaxOffsetUnits(unit)
	dictionary := filled(literalMask(OptimizeEvents(units, unit, reach, progress),
		s.count))
	first := s.parser.parseReporting(dictionary, progress)
	s.chain = first
	s.forced = literalMask(first, s.count)
	s.adopt(first)
	s.best = s.chain
	s.bestBits = s.bits
	s.reportPass(1)
	for pass := 1; pass < searchPasses; pass++ {
		next := filled(s.referenced())
		if equalBools(next, dictionary) {
			break
		}
		dictionary = next
		s.adopt(s.parser.parseReporting(dictionary, progress))
		if s.bits < s.bestBits {
			s.best = s.chain
			s.bestBits = s.bits
		}
		s.reportPass(pass + 1)
	}
	s.returnToBest()
	return s
}

// reportPass prints a pass's bits and bytes. The line carries the time, so
// it draws only where the meter draws: at a terminal, not into a redirected
// log.
func (s *copySearch) reportPass(pass int) {
	if s.progress && isTerminal(os.Stdout) {
		fmt.Printf("%7.1fs pass %d: %d bits, %d bytes\n",
			time.Since(s.started).Seconds(), pass, s.bits, (s.bits+7)/8)
	}
}

// returnToBest parses the best dictionary again, so the parser's base is
// the best.
func (s *copySearch) returnToBest() {
	s.adopt(s.parser.parse(literalMask(s.best, s.count)))
}

// adopt makes parsed, the parse of the dictionary just made, the incumbent,
// its own literals the dictionary from here on.
func (s *copySearch) adopt(parsed *Block) {
	s.parser.accept()
	s.chain = parsed
	s.bits = CompressRepeating(parsed, s.units, s.unit, s.maxOpLength, -1,
		s.window).Bits()
	s.forced = literalMask(parsed, s.count)
	s.runs = nil
	s.copies = nil
	referenced := s.referenced()
	previous := -1
	for _, block := range blocksOf(parsed) {
		start := previous + 1
		if block.Offset == 0 {
			used := 0
			for p := start; p <= block.Index; p++ {
				if referenced[p] {
					used = 1
				}
			}
			s.runs = append(s.runs, [3]int{start, block.Index, used})
		} else {
			isCopy := 0
			if block.Offset < 0 {
				isCopy = 1
			}
			s.copies = append(s.copies, [3]int{start, block.Index, isCopy})
		}
		previous = block.Index
	}
}

// referenced is the positions the incumbent's copies read from.
func (s *copySearch) referenced() []bool {
	referenced := make([]bool, s.count)
	previous := -1
	for _, block := range blocksOf(s.chain) {
		if block.Offset < 0 {
			distance := -block.Offset
			for p := previous + 1; p <= block.Index; p++ {
				referenced[p-distance] = true
			}
		}
		previous = block.Index
	}
	return referenced
}

func (s *copySearch) run(deadline time.Time, steps int64) *Block {
	s.deadline = deadline
	s.stepsAllowed = steps
	// Descend first: most of what the opening passes force packs smaller
	// free, and a sweep finds that run by run.
	s.sweep()
	for !s.exhausted() {
		var fraction float64
		if steps == math.MaxInt64 {
			fraction = float64(time.Since(s.started)) /
				float64(max(time.Duration(1), deadline.Sub(s.started)))
		} else {
			fraction = float64(s.step) / float64(steps)
		}
		temperature := searchHot * math.Pow(searchCold/searchHot,
			math.Min(1.0, fraction))
		proposal := append([]bool(nil), s.forced...)
		move := s.propose(proposal)
		parsed := s.parser.parse(proposal)
		score := s.evaluate(parsed)
		delta := score - s.bits
		if delta <= 0 || s.random.nextDouble() < math.Exp(-float64(delta)/temperature) {
			s.adopt(parsed)
			s.accepted++
			s.noteBest(move)
		}
		if s.step-s.lastBest > searchPatience {
			// Stuck: back to the best, and descend from there again.
			s.returnToBest()
			s.sweep()
			s.lastBest = s.step
		}
	}
	if s.progress && s.step > 0 {
		fmt.Printf("%d steps, %d accepted: %d bits, %d bytes\n", s.step,
			s.accepted, s.bestBits, (s.bestBits+7)/8)
	}
	return s.best
}

func (s *copySearch) exhausted() bool {
	return s.step >= s.stepsAllowed ||
		(s.step%8 == 0 && !time.Now().Before(s.deadline))
}

// evaluate scores a parse: the compressor's bits. A step of the budget.
func (s *copySearch) evaluate(parsed *Block) int {
	s.step++
	return CompressRepeating(parsed, s.units, s.unit, s.maxOpLength, -1,
		s.window).Bits()
}

func (s *copySearch) noteBest(move string) {
	if s.bits < s.bestBits {
		s.best = s.chain
		s.bestBits = s.bits
		s.lastBest = s.step
		if s.progress {
			s.report(move)
		}
	}
}

// sweep is the greedy descent: every literal run of the incumbent, in a
// random order, freed whole and trimmed at either end, keeping each change
// that packs smaller.
func (s *copySearch) sweep() {
	order := append([][3]int(nil), s.runs...)
	for i := len(order) - 1; i > 0; i-- {
		j := s.random.nextInt(i + 1)
		order[i], order[j] = order[j], order[i]
	}
	for _, run := range order {
		if s.exhausted() {
			return
		}
		start := run[0]
		end := run[1]
		if !s.forced[start] && !s.forced[end] {
			continue // gone already
		}
		if s.improve(start, end+1, "sweep free") {
			continue
		}
		if end > start {
			if !s.improve(start, start+1, "sweep trim") {
				s.improve(end, end+1, "sweep trim")
			}
		}
	}
}

// improve frees [from, to) when that packs smaller.
func (s *copySearch) improve(from, to int, move string) bool {
	proposal := append([]bool(nil), s.forced...)
	fill(proposal, from, to, false)
	parsed := s.parser.parse(proposal)
	score := s.evaluate(parsed)
	if score < s.bits {
		s.adopt(parsed)
		s.accepted++
		s.noteBest(move)
		return true
	}
	return false
}

func (s *copySearch) report(move string) {
	fmt.Printf("%7.1fs %8d steps: %d bits, %d bytes  (%s)\n",
		time.Since(s.started).Seconds(), s.step, s.bestBits,
		(s.bestBits+7)/8, move)
}

// propose changes the dictionary in place, and says how.
func (s *copySearch) propose(dictionary []bool) string {
	kind := s.random.nextInt(20)
	switch {
	case kind < 6:
		s.free(dictionary)
		return "free"
	case kind < 12:
		s.seed(dictionary)
		return "seed"
	case kind < 15:
		s.extend(dictionary)
		return "extend"
	case kind < 18:
		s.trim(dictionary)
		return "trim"
	default:
		s.free(dictionary)
		s.seed(dictionary)
		return "free+seed"
	}
}

// pickRun is a literal run, unreferenced ones four times as likely, or none.
func (s *copySearch) pickRun() ([3]int, bool) {
	if len(s.runs) == 0 {
		return [3]int{}, false
	}
	for attempt := 0; attempt < 4; attempt++ {
		run := s.runs[s.random.nextInt(len(s.runs))]
		if run[2] == 0 || s.random.nextInt(4) == 0 {
			return run, true
		}
	}
	return s.runs[s.random.nextInt(len(s.runs))], true
}

// free frees a literal run, or part of one, for the parse to match.
func (s *copySearch) free(dictionary []bool) {
	run, ok := s.pickRun()
	if !ok {
		return
	}
	length := run[1] - run[0] + 1
	if s.random.nextBoolean() {
		fill(dictionary, run[0], run[1]+1, false)
	} else {
		size := 1 + s.random.nextInt(min(length, 8))
		start := run[0] + s.random.nextInt(length-size+1)
		fill(dictionary, start, start+size, false)
	}
}

// seed forces literals where a copy or match sits, so later copies can come
// from there.
func (s *copySearch) seed(dictionary []bool) {
	if len(s.copies) == 0 {
		return
	}
	op := s.copies[s.random.nextInt(len(s.copies))]
	for attempt := 0; attempt < 3 && op[2] == 0 && s.random.nextInt(4) != 0; attempt++ {
		op = s.copies[s.random.nextInt(len(s.copies))] // prefer copies
	}
	length := op[1] - op[0] + 1
	var size int
	if s.random.nextBoolean() {
		size = min(length, 32)
	} else {
		size = 1 + s.random.nextInt(min(length, 12))
	}
	start := op[0]
	if !s.random.nextBoolean() {
		start += s.random.nextInt(length - size + 1)
	}
	fill(dictionary, start, start+size, true)
}

// extend grows a literal run past its end by a few units.
func (s *copySearch) extend(dictionary []bool) {
	run, ok := s.pickRun()
	if !ok {
		return
	}
	size := 1 + s.random.nextInt(8)
	if s.random.nextBoolean() {
		fill(dictionary, run[1]+1, min(s.count, run[1]+1+size), true)
	} else {
		fill(dictionary, max(0, run[0]-size), run[0], true)
	}
}

// trim shortens a literal run at either end by a unit or a few.
func (s *copySearch) trim(dictionary []bool) {
	run, ok := s.pickRun()
	if !ok {
		return
	}
	length := run[1] - run[0] + 1
	size := 1 + s.random.nextInt(min(length, 3))
	if s.random.nextBoolean() {
		fill(dictionary, run[1]+1-size, run[1]+1, false)
	} else {
		fill(dictionary, run[0], run[0]+size, false)
	}
}

// blocksOf is the blocks of a chain, first block first.
func blocksOf(chain *Block) []*Block {
	var list []*Block
	for block := chain; block != nil && block.Index >= 0; block = block.Chain {
		list = append(list, block)
	}
	for i, j := 0, len(list)-1; i < j; i, j = i+1, j-1 {
		list[i], list[j] = list[j], list[i]
	}
	return list
}

func literalMask(chain *Block, count int) []bool {
	literal := make([]bool, count)
	previous := -1
	for _, block := range blocksOf(chain) {
		if block.Offset == 0 {
			for p := previous + 1; p <= block.Index; p++ {
				literal[p] = true
			}
		}
		previous = block.Index
	}
	return literal
}

func filled(dictionary []bool) []bool {
	result := append([]bool(nil), dictionary...)
	run := 0
	for p := 0; p < len(result); p++ {
		if result[p] {
			if run > 0 && run <= searchHole {
				fill(result, p-run, p, true)
			}
			run = 0
		} else {
			run++
		}
	}
	return result
}

func fill(flags []bool, from, to int, value bool) {
	for p := from; p < to; p++ {
		flags[p] = value
	}
}

func equalBools(a, b []bool) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func clampIndex(index, low, high int) int {
	if index < low {
		return low
	}
	if index > high {
		return high
	}
	return index
}

func offsetCost(offset int) int {
	if offset > ByteOffsetLimit {
		return 16
	}
	return 8
}

// ---------------------------------------------------------------- parser

// copyParser is the exact parse for one dictionary, on arrays reused across
// calls. Ring offsets 1..window and copy distances window+1..count-1 share
// one state index space and never meet.
type copyParser struct {
	units       []uint32
	count       int
	literalBits int
	window      int
	reach       int

	// Per state index: the best chain ending in a match or copy there, its
	// cost, end and how to rebuild it, and its literal extension.
	stateBits   []int
	stateEnd    []int
	stateKind   []byte
	stateAux    []int
	statePred   []int
	stateNode   []int
	litBits     []int
	litEnd      []int
	litNode     []int
	matchLength []int
	stamp       []int

	// Per position: the winner, and the best match or copy ending there.
	optimalBits   []int
	winNode       []int
	matchNodeSlot []int // by end + 1, so -1 has a slot
	bestLength    []int

	// The dictionary as prefix counts, and the input's two-unit chains: the
	// previous position with the same two units, the same for every
	// dictionary.
	forcedBefore []int
	prevSame2    []int
	forced       []bool

	// Distances visited at the previous position, whose runs may end at this
	// one, and distances whose last copy could still be repped.
	activePrev      []int
	activeCur       []int
	activePrevCount int
	activeCurCount  int
	repable         []int
	inRepable       []bool
	repableCount    int

	// The position being parsed, and its best match or copy so far.
	bestMatch      int
	bestMatchIdx   int
	bestLengthSize int

	// The node pool.
	nodeKind   []byte
	nodeEnd    []int
	nodeOffset []int
	nodeAux    []int
	nodePred   []int
	nodeBits   []int
	nodes      int

	// The literal channel: a min-tree by match end + 1 over bits -
	// end*literalBits.
	half int
	tree []int64

	// Checkpoints: the state before position k*checkpoint, for the base
	// dictionary, the last parse accepted, and for the parse under way. A
	// parse restarts from the last checkpoint before its dictionary first
	// differs from the base's, since nothing before depends on what comes
	// after. Nodes are appended past the base's, so a rejected parse leaves
	// the base's intact.
	checkpoint int
	base       []*copySnapshot
	proposal   []*copySnapshot
	baseForced []bool
	hasBase    bool
	poolTop    int
	sharedUpTo int  // checkpoints the parse under way shares
	fresh      bool // the parse under way started from scratch
	fullNodes  int  // the nodes a parse from scratch takes
}

func newCopyParser(units []uint32, unit, window int) *copyParser {
	count := len(units)
	size := max(count, window) + 1
	p := &copyParser{
		units:         units,
		count:         count,
		literalBits:   8 * unit,
		window:        window,
		reach:         MaxOffsetUnits(unit),
		stateBits:     make([]int, size),
		stateEnd:      make([]int, size),
		stateKind:     make([]byte, size),
		stateAux:      make([]int, size),
		statePred:     make([]int, size),
		stateNode:     make([]int, size),
		litBits:       make([]int, size),
		litEnd:        make([]int, size),
		litNode:       make([]int, size),
		matchLength:   make([]int, size),
		stamp:         make([]int, size),
		optimalBits:   make([]int, count),
		winNode:       make([]int, count),
		matchNodeSlot: make([]int, count+1),
		bestLength:    make([]int, max(count, 3)),
		forcedBefore:  make([]int, count+1),
		prevSame2:     make([]int, count),
		activePrev:    make([]int, size),
		activeCur:     make([]int, size),
		repable:       make([]int, size),
		inRepable:     make([]bool, size),
		nodeKind:      make([]byte, 0, 1024),
		nodeEnd:       make([]int, 0, 1024),
		nodeOffset:    make([]int, 0, 1024),
		nodeAux:       make([]int, 0, 1024),
		nodePred:      make([]int, 0, 1024),
		nodeBits:      make([]int, 0, 1024),
	}
	last := map[uint64]int{}
	for q := 0; q+1 < count; q++ {
		key := uint64(units[q])<<32 | uint64(units[q+1])
		if previous, ok := last[key]; ok {
			p.prevSame2[q] = previous
		} else {
			p.prevSame2[q] = -1
		}
		last[key] = q
	}
	if count > 0 {
		p.prevSame2[count-1] = -1
	}
	h := 1
	for h < count+1 {
		h <<= 1
	}
	p.half = h
	p.tree = make([]int64, 2*h)
	p.checkpoint = max(1024, (count+7)/8)
	slots := (count + p.checkpoint - 1) / p.checkpoint
	p.base = make([]*copySnapshot, slots)
	p.proposal = make([]*copySnapshot, slots)
	for k := 0; k < slots; k++ {
		p.base[k] = newCopySnapshot(size, count)
		p.proposal[k] = newCopySnapshot(size, count)
	}
	return p
}

// copySnapshot is a parse's whole state before a checkpoint position.
type copySnapshot struct {
	stateBits       []int
	stateEnd        []int
	stateKind       []byte
	stateAux        []int
	statePred       []int
	stateNode       []int
	litBits         []int
	litEnd          []int
	litNode         []int
	matchLength     []int
	stamp           []int
	optimalBits     []int
	winNode         []int
	matchNodeSlot   []int
	leaves          []int64
	activePrev      []int
	activeCur       []int
	repable         []int
	activePrevCount int
	activeCurCount  int
	repableCount    int
	nodes           int
	valid           bool
}

func newCopySnapshot(size, count int) *copySnapshot {
	return &copySnapshot{
		stateBits:     make([]int, size),
		stateEnd:      make([]int, size),
		stateKind:     make([]byte, size),
		stateAux:      make([]int, size),
		statePred:     make([]int, size),
		stateNode:     make([]int, size),
		litBits:       make([]int, size),
		litEnd:        make([]int, size),
		litNode:       make([]int, size),
		matchLength:   make([]int, size),
		stamp:         make([]int, size),
		optimalBits:   make([]int, count),
		winNode:       make([]int, count),
		matchNodeSlot: make([]int, count+1),
		leaves:        make([]int64, count+1),
		activePrev:    make([]int, size),
		activeCur:     make([]int, size),
		repable:       make([]int, size),
	}
}

func (p *copyParser) parse(dictionary []bool) *Block {
	return p.parseReporting(dictionary, false)
}

// parseReporting is parse, reporting on stdout as the Meter does.
func (p *copyParser) parseReporting(dictionary []bool, progress bool) *Block {
	from := 0
	if p.hasBase {
		from = p.count - 1
		for q := 0; q < p.count; q++ {
			if dictionary[q] != p.baseForced[q] {
				from = q
				break
			}
		}
	}
	slot := from / p.checkpoint
	for slot > 0 && !p.base[slot].valid {
		slot--
	}
	p.sharedUpTo = slot
	p.fresh = slot == 0
	p.forced = dictionary
	start := slot * p.checkpoint
	if slot == 0 {
		p.prepare()
	} else {
		p.restore(p.base[slot])
	}
	for q := start; q < p.count; q++ {
		p.forcedBefore[q+1] = p.forcedBefore[q]
		if p.forced[q] {
			p.forcedBefore[q+1]++
		}
	}
	meter := NewMeter(TotalSteps(p.count, start, p.window), progress)
	for index := start; index < p.count; index++ {
		if index > 0 && index%p.checkpoint == 0 {
			p.snapshot(p.proposal[index/p.checkpoint], index)
		}
		literalOnly := p.forced[index]
		value := p.units[index]
		p.bestLengthSize = 2

		// The literal channel: the best match or copy end, per gamma class
		// of the run length that reaches here from it.
		litCand := math.MaxInt32
		litE := 0
		for k := 0; ; k++ {
			slotHi := index - (1 << k) + 1
			if slotHi < 0 {
				break
			}
			slotLo := max(0, index-(2<<k)+2)
			found := p.query(slotLo, slotHi)
			if found != math.MaxInt64 {
				candidate := int(found>>32) + index*p.literalBits + 2 + 2*k
				if candidate < litCand {
					litCand = candidate
					litE = int(int32(found)) - 1
				}
			}
		}

		p.bestMatch = math.MaxInt32
		p.bestMatchIdx = -1

		// Ring offsets: the reference DP.
		maxOffset := clampIndex(index, InitialOffset, p.window)
		for offset := 1; offset <= maxOffset; offset++ {
			if !literalOnly && index != 0 && value == p.units[index-offset] {
				if p.litEnd[offset] != searchNone {
					if p.matchLength[offset] == 0 {
						p.litNode[offset] = p.newNode(ckLiterals,
							p.litEnd[offset], 0, p.stateEnd[offset],
							p.node(offset), p.litBits[offset])
					}
					bits := p.litBits[offset] + 1 +
						gammaBits(index-p.litEnd[offset])
					p.setState(offset, bits, index, ckRep, 0, p.litNode[offset])
					if bits < p.bestMatch {
						p.bestMatch = bits
						p.bestMatchIdx = offset
					}
				}
				p.matchLength[offset]++
				if p.matchLength[offset] > 1 {
					p.bestLengthSize = p.extendBestLength(p.bestLengthSize,
						p.matchLength[offset], index)
					length := p.bestLength[p.matchLength[offset]]
					bits := p.optimalBits[index-length] + 3 + offsetCost(offset) +
						gammaBits(length-1)
					if p.stateEnd[offset] != index || p.stateBits[offset] > bits {
						p.setState(offset, bits, index, ckNew, length,
							p.winNode[index-length])
						if bits < p.bestMatch {
							p.bestMatch = bits
							p.bestMatchIdx = offset
						}
					}
				}
			} else {
				p.matchLength[offset] = 0
				if p.stateEnd[offset] != searchNone {
					length := index - p.stateEnd[offset]
					p.litBits[offset] = p.stateBits[offset] + 1 + gammaBits(length) +
						length*p.literalBits
					p.litEnd[offset] = index
				}
			}
		}

		// Copies. A copy needs two units, so the two-unit chain finds the
		// runs, restricted to dictionary pairs beyond the window; a run in
		// progress the chain no longer lists ends here and is visited for
		// its last unit; a distance whose last copy could still be repped is
		// visited wherever its unit matches, since a rep may be one unit.
		p.activePrev, p.activeCur = p.activeCur, p.activePrev
		p.activePrevCount = p.activeCurCount
		p.activeCurCount = 0
		if !literalOnly {
			if index+1 < p.count {
				for q := p.prevSame2[index]; q >= 0; q = p.prevSame2[q] {
					if p.forced[q] && p.forced[q+1] && index-q > p.window {
						p.visit(index, index-q)
					}
				}
			}
			for a := 0; a < p.activePrevCount; a++ {
				distance := p.activePrev[a]
				if p.stamp[distance] == index-1 && value == p.units[index-distance] &&
					p.forced[index-distance] {
					p.visit(index, distance)
				}
			}
			for r := 0; r < p.repableCount; r++ {
				distance := p.repable[r]
				if p.stamp[distance] != index && value == p.units[index-distance] &&
					p.forced[index-distance] {
					p.visit(index, distance)
				}
			}
		}
		// A distance stays reppable while the literals since its copy have
		// literal shadows at the source.
		for r := p.repableCount - 1; r >= 0; r-- {
			distance := p.repable[r]
			if p.stateEnd[distance] < index && p.stamp[distance] != index &&
				!p.forced[index-distance] {
				p.inRepable[distance] = false
				p.repableCount--
				p.repable[r] = p.repable[p.repableCount]
			}
		}

		// The winner, and the literal channel's next entry.
		if p.bestMatch < litCand {
			p.optimalBits[index] = p.bestMatch
			p.winNode[index] = p.node(p.bestMatchIdx)
		} else {
			if litCand == math.MaxInt32 {
				panic("a literal run always reaches")
			}
			p.optimalBits[index] = litCand
			p.winNode[index] = p.newNode(ckLiterals, index, 0, litE,
				p.matchNodeSlot[litE+1], litCand)
		}
		if p.bestMatch != math.MaxInt32 {
			p.matchNodeSlot[index+1] = p.node(p.bestMatchIdx)
			p.update(index+1, int64(p.bestMatch-index*p.literalBits)<<32|
				int64(index+1))
		}
		meter.Advance(int64(clampIndex(index, InitialOffset, p.window)))
	}
	meter.Finish()
	return p.rebuild(p.winNode[p.count-1])
}

// visit takes a copy distance whose unit matches at index with the source
// in the dictionary: continues or starts its run, and enters the rep of the
// last copy at that distance and the copy ending here.
func (p *copyParser) visit(index, distance int) {
	q := index - distance
	if p.stamp[distance] != index-1 {
		// A run starts. Its rep continues the last copy at this distance
		// when the literals since have literal shadows at the source.
		p.matchLength[distance] = 1
		p.litNode[distance] = -1
		if p.stateEnd[distance] != searchNone {
			end := p.stateEnd[distance]
			between := index - 1 - end
			if p.forcedBefore[q]-p.forcedBefore[end-distance+1] == between {
				bits := p.stateBits[distance] + 1 + gammaBits(between) +
					between*p.literalBits
				p.litBits[distance] = bits
				p.litEnd[distance] = index - 1
				p.litNode[distance] = p.newNode(ckLiterals, index-1, 0, end,
					p.node(distance), bits)
			}
		}
	} else {
		p.matchLength[distance]++
	}
	p.stamp[distance] = index
	p.activeCur[p.activeCurCount] = distance
	p.activeCurCount++
	run := p.matchLength[distance]
	if p.litNode[distance] >= 0 {
		bits := p.litBits[distance] + 1 + gammaBits(run)
		p.setState(distance, bits, index, ckCopyRep, 0, p.litNode[distance])
		if bits < p.bestMatch {
			p.bestMatch = bits
			p.bestMatchIdx = distance
		}
	}
	if run > 1 {
		// Literals from the source's last unit to here: a copy of n units
		// reads back n - 1 more and leaves at least one literal between.
		between := p.forcedBefore[index] - p.forcedBefore[q]
		if between < 2 {
			return
		}
		longest := min(run, p.reach-p.window-between+1)
		if longest < 2 {
			return
		}
		p.bestLengthSize = p.extendBestLength(p.bestLengthSize, longest, index)
		length := p.bestLength[longest]
		bits := p.optimalBits[index-length] + 3 +
			offsetCost(p.window+between+length-1) + gammaBits(length-1)
		byteLongest := min(longest, ByteOffsetLimit+1-p.window-between)
		if byteLongest >= 2 && byteLongest < longest {
			shorter := p.bestLength[byteLongest]
			shorterBits := p.optimalBits[index-shorter] + 3 + 8 +
				gammaBits(shorter-1)
			if shorterBits < bits {
				bits = shorterBits
				length = shorter
			}
		}
		if p.stateEnd[distance] != index || p.stateBits[distance] > bits {
			p.setState(distance, bits, index, ckCopy, length,
				p.winNode[index-length])
			if bits < p.bestMatch {
				p.bestMatch = bits
				p.bestMatchIdx = distance
			}
		}
	}
}

// accept makes the parse just made the base for the ones to come: its
// checkpoints stand, its nodes are kept, and the next parse is compared
// against its dictionary.
func (p *copyParser) accept() {
	p.settle()
	if p.poolTop > 4*p.fullNodes+65536 {
		// The pool holds the tails of every parse since the last full one;
		// one full parse of the base compacts it. The limit is a multiple of
		// what a full parse takes, so the compaction does not find the pool
		// too big again.
		p.hasBase = false
		p.poolTop = 0
		p.parse(p.baseForced)
		p.settle()
	}
}

// settle makes the parse just made the base.
func (p *copyParser) settle() {
	for m := p.sharedUpTo + 1; m < len(p.base); m++ {
		kept := p.base[m]
		p.base[m] = p.proposal[m]
		p.proposal[m] = kept
		p.base[m].valid = true
	}
	p.baseForced = append([]bool(nil), p.forced...)
	p.hasBase = true
	if p.fresh {
		p.fullNodes = p.nodes - p.poolTop
	}
	p.poolTop = p.nodes
}

func (p *copyParser) snapshot(into *copySnapshot, position int) {
	copy(into.stateBits, p.stateBits)
	copy(into.stateEnd, p.stateEnd)
	copy(into.stateKind, p.stateKind)
	copy(into.stateAux, p.stateAux)
	copy(into.statePred, p.statePred)
	copy(into.stateNode, p.stateNode)
	copy(into.litBits, p.litBits)
	copy(into.litEnd, p.litEnd)
	copy(into.litNode, p.litNode)
	copy(into.matchLength, p.matchLength)
	copy(into.stamp, p.stamp)
	copy(into.optimalBits, p.optimalBits[:position])
	copy(into.winNode, p.winNode[:position])
	copy(into.matchNodeSlot, p.matchNodeSlot[:position+1])
	copy(into.leaves, p.tree[p.half:p.half+position+1])
	copy(into.activePrev, p.activePrev[:p.activePrevCount])
	copy(into.activeCur, p.activeCur[:p.activeCurCount])
	copy(into.repable, p.repable[:p.repableCount])
	into.activePrevCount = p.activePrevCount
	into.activeCurCount = p.activeCurCount
	into.repableCount = p.repableCount
	into.nodes = p.nodes
	into.valid = true
}

func (p *copyParser) restore(from *copySnapshot) {
	p.nodes = p.poolTop
	copy(p.stateBits, from.stateBits)
	copy(p.stateEnd, from.stateEnd)
	copy(p.stateKind, from.stateKind)
	copy(p.stateAux, from.stateAux)
	copy(p.statePred, from.statePred)
	copy(p.stateNode, from.stateNode)
	copy(p.litBits, from.litBits)
	copy(p.litEnd, from.litEnd)
	copy(p.litNode, from.litNode)
	copy(p.matchLength, from.matchLength)
	copy(p.stamp, from.stamp)
	position := p.sharedUpTo * p.checkpoint
	copy(p.optimalBits, from.optimalBits[:position])
	copy(p.winNode, from.winNode[:position])
	copy(p.matchNodeSlot, from.matchNodeSlot[:position+1])
	for i := range p.tree {
		p.tree[i] = math.MaxInt64
	}
	copy(p.tree[p.half:], from.leaves[:position+1])
	for i := p.half - 1; i >= 1; i-- {
		p.tree[i] = min(p.tree[2*i], p.tree[2*i+1])
	}
	copy(p.activePrev, from.activePrev[:from.activePrevCount])
	copy(p.activeCur, from.activeCur[:from.activeCurCount])
	p.activePrevCount = from.activePrevCount
	p.activeCurCount = from.activeCurCount
	for r := 0; r < p.repableCount; r++ {
		p.inRepable[p.repable[r]] = false
	}
	copy(p.repable, from.repable[:from.repableCount])
	p.repableCount = from.repableCount
	for r := 0; r < p.repableCount; r++ {
		p.inRepable[p.repable[r]] = true
	}
	p.bestLength[2] = 2
}

func (p *copyParser) prepare() {
	for i := range p.stateEnd {
		p.stateEnd[i] = searchNone
		p.litEnd[i] = searchNone
		p.litNode[i] = -1
		p.stateNode[i] = -1
		p.matchLength[i] = 0
		p.stamp[i] = -2
	}
	for i := range p.tree {
		p.tree[i] = math.MaxInt64
	}
	p.bestLength[2] = 2
	p.nodes = p.poolTop
	// The fake block every chain hangs from: one unit back, ending before
	// the stream, costing -1 so the first flag is free.
	root := p.newNode(ckNew, -1, InitialOffset, 0, -1, -1)
	p.stateBits[InitialOffset] = -1
	p.stateEnd[InitialOffset] = -1
	p.stateKind[InitialOffset] = ckNew
	p.stateNode[InitialOffset] = root
	p.matchNodeSlot[0] = root
	p.update(0, int64(p.literalBits-1)<<32)
	p.activePrevCount = 0
	p.activeCurCount = 0
	for r := 0; r < p.repableCount; r++ {
		p.inRepable[p.repable[r]] = false
	}
	p.repableCount = 0
}

func (p *copyParser) extendBestLength(size, target, index int) int {
	if size < target {
		bits := p.optimalBits[index-p.bestLength[size]] +
			gammaBits(p.bestLength[size]-1)
		for {
			size++
			shorterBits := p.optimalBits[index-size] + gammaBits(size-1)
			if shorterBits <= bits {
				p.bestLength[size] = size
				bits = shorterBits
			} else {
				p.bestLength[size] = p.bestLength[size-1]
			}
			if size >= target {
				break
			}
		}
	}
	return size
}

func (p *copyParser) setState(idx, bits, end int, kind byte, aux, pred int) {
	p.stateBits[idx] = bits
	p.stateEnd[idx] = end
	p.stateKind[idx] = kind
	p.stateAux[idx] = aux
	p.statePred[idx] = pred
	p.stateNode[idx] = -1
	if idx > p.window && !p.inRepable[idx] {
		p.inRepable[idx] = true
		p.repable[p.repableCount] = idx
		p.repableCount++
	}
}

// node is the state's node, made when first needed.
func (p *copyParser) node(idx int) int {
	if p.stateNode[idx] < 0 {
		offset := idx
		if idx > p.window {
			offset = -idx
		}
		p.stateNode[idx] = p.newNode(p.stateKind[idx], p.stateEnd[idx], offset,
			p.stateAux[idx], p.statePred[idx], p.stateBits[idx])
	}
	return p.stateNode[idx]
}

func (p *copyParser) newNode(kind byte, end, offset, aux, pred, bits int) int {
	if p.nodes == len(p.nodeKind) {
		p.nodeKind = append(p.nodeKind, 0)
		p.nodeEnd = append(p.nodeEnd, 0)
		p.nodeOffset = append(p.nodeOffset, 0)
		p.nodeAux = append(p.nodeAux, 0)
		p.nodePred = append(p.nodePred, 0)
		p.nodeBits = append(p.nodeBits, 0)
	}
	p.nodeKind[p.nodes] = kind
	p.nodeEnd[p.nodes] = end
	p.nodeOffset[p.nodes] = offset
	p.nodeAux[p.nodes] = aux
	p.nodePred[p.nodes] = pred
	p.nodeBits[p.nodes] = bits
	p.nodes++
	return p.nodes - 1
}

func (p *copyParser) rebuild(last int) *Block {
	var order []int
	for node := last; node >= 0; node = p.nodePred[node] {
		order = append(order, node)
	}
	chain := &Block{Bits: -1, Index: -1, Offset: InitialOffset}
	for i := len(order) - 2; i >= 0; i-- {
		node := order[i]
		offset := p.nodeOffset[node]
		if p.nodeKind[node] == ckLiterals {
			offset = 0
		}
		chain = &Block{Bits: p.nodeBits[node], Index: p.nodeEnd[node],
			Offset: offset, Chain: chain}
	}
	return chain
}

func (p *copyParser) update(slot int, value int64) {
	i := p.half + slot
	p.tree[i] = value
	for i >>= 1; i >= 1; i >>= 1 {
		p.tree[i] = min(p.tree[2*i], p.tree[2*i+1])
	}
}

func (p *copyParser) query(lo, hi int) int64 {
	result := int64(math.MaxInt64)
	l := p.half + lo
	r := p.half + hi + 1
	for l < r {
		if l&1 == 1 {
			result = min(result, p.tree[l])
			l++
		}
		if r&1 == 1 {
			r--
			result = min(result, p.tree[r])
		}
		l >>= 1
		r >>= 1
	}
	return result
}
