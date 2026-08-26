package st4

import "sort"

// maxInt64 stands for "no entry", as long.MaxValue does in the other trees.
const maxInt64 = int64(^uint64(0) >> 1)

// minTree is an iterative min segment tree over int64.
type minTree struct {
	nodes []int64
	size  int
}

func newMinTree(width int) *minTree {
	power := width - 1
	if power < 2 {
		power = 2
	}
	size := 1
	for size < power {
		size <<= 1
	}
	// The C# takes the highest power of two at or below power and doubles
	// it, which is this rounded up and then doubled only when power is not
	// itself a power of two. Match it exactly.
	size = highestPowerOfTwo(power) * 2
	t := &minTree{nodes: make([]int64, 2*size), size: size}
	for i := range t.nodes {
		t.nodes[i] = maxInt64
	}
	return t
}

func highestPowerOfTwo(value int) int {
	power := 1
	for power*2 <= value {
		power *= 2
	}
	return power
}

func (t *minTree) set(at int, value int64) {
	node := at + t.size
	t.nodes[node] = value
	for node >>= 1; node > 0; node >>= 1 {
		left, right := t.nodes[2*node], t.nodes[2*node+1]
		if right < left {
			left = right
		}
		t.nodes[node] = left
	}
}

// min is the minimum over the inclusive range, maxInt64 when empty.
func (t *minTree) min(from, to int) int64 {
	if from < 0 {
		from = 0
	}
	if to >= t.size {
		to = t.size - 1
	}
	best := maxInt64
	lo := from + t.size
	hi := to + t.size + 1
	for lo < hi {
		if lo&1 != 0 {
			if t.nodes[lo] < best {
				best = t.nodes[lo]
			}
			lo++
		}
		if hi&1 != 0 {
			hi--
			if t.nodes[hi] < best {
				best = t.nodes[hi]
			}
		}
		lo >>= 1
		hi >>= 1
	}
	return best
}

// slotTree is a min tree whose slots hold sets, so entries retire exactly.
// The C# uses a SortedSet, which drops duplicates; a sorted slice with the
// same rule keeps the two trees agreeing.
type slotTree struct {
	*minTree
	slots map[int][]int64
}

func newSlotTree(width int) *slotTree {
	return &slotTree{minTree: newMinTree(width), slots: map[int][]int64{}}
}

func (t *slotTree) insert(slot int, entry int64) {
	set := t.slots[slot]
	at := sort.Search(len(set), func(i int) bool { return set[i] >= entry })
	if at < len(set) && set[at] == entry {
		return // a set, not a multiset: already there
	}
	set = append(set, 0)
	copy(set[at+1:], set[at:])
	set[at] = entry
	t.slots[slot] = set
	t.set(slot, set[0])
}

func (t *slotTree) remove(slot int, entry int64) {
	set, ok := t.slots[slot]
	if !ok {
		return
	}
	at := sort.Search(len(set), func(i int) bool { return set[i] >= entry })
	if at < len(set) && set[at] == entry {
		set = append(set[:at], set[at+1:]...)
		t.slots[slot] = set
	}
	if len(set) == 0 {
		t.set(slot, maxInt64)
	} else {
		t.set(slot, set[0])
	}
}

// runHeap is a min-heap of (start, offset) entries, smallest first, matching
// the priority queue the other trees use. Stale entries pop lazily.
type runHeap struct {
	items []int64
}

func (h *runHeap) push(entry int64) {
	h.items = append(h.items, entry)
	at := len(h.items) - 1
	for at > 0 {
		parent := (at - 1) / 2
		if h.items[parent] <= h.items[at] {
			break
		}
		h.items[parent], h.items[at] = h.items[at], h.items[parent]
		at = parent
	}
}

func (h *runHeap) peek() (int64, bool) {
	if len(h.items) == 0 {
		return 0, false
	}
	return h.items[0], true
}

func (h *runHeap) pop() {
	last := len(h.items) - 1
	h.items[0] = h.items[last]
	h.items = h.items[:last]
	at := 0
	for {
		left, right := 2*at+1, 2*at+2
		smallest := at
		if left < len(h.items) && h.items[left] < h.items[smallest] {
			smallest = left
		}
		if right < len(h.items) && h.items[right] < h.items[smallest] {
			smallest = right
		}
		if smallest == at {
			return
		}
		h.items[at], h.items[smallest] = h.items[smallest], h.items[at]
		at = smallest
	}
}
