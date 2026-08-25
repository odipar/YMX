# The conformance kit

Ten packed tunes, and what the 68000 player writes to the sound chip for
each. The kit exists to test [SPEC.md](../SPEC.md) rather than the code:
hand it to someone who has never seen this repository, and see whether the
document alone is enough to decode the tunes.

It tests the **reader** of SPEC.md §9.4 - something that produces the
values a frame writes and drives no chip. A reader is one of the three
roles §1 names, and the narrowest: it needs neither §5's rates nor §6's
sample table, because both describe what a timer writes between frames
and a reader produces one set of values a frame. The claim this kit
measures is therefore "a converter or an analyser can be written from the
document", not "a player can be ported from it".

## Running the exercise

Copy `TASK.md`, `tunes/` and `../SPEC.md` into a fresh directory and give
that directory to an implementer with no access to this repository and no
access to any implementation of the format or of the compression under
it. Keep `MANIFEST.txt` and the reference dumps back: an implementer who
can check an answer is not reading the document, and the exercise
measures the document.

Where several implementers run at once, each needs scratch space of its
own as well as its own copy of the directory. The fifth run gave all
three one scratch root; two of them wrote a first decoder to the same
obvious filename under it, and one read the other's. Give each a
directory nothing else writes to.

Two things reach an implementer that the copied directory does not
carry, and both belong in its `SOURCES.md`. The first is what the harness
puts in its context before the task begins - in this repository
that is `CLAUDE.md` and `AGENTS.md`, which state prose rules and no
field, offset or opcode. The second is what it already knows: LZ77
coding and Elias gamma are general, and an implementer who recognises
them has not read an implementation.

The implementer produces `decode.py`, `SOURCES.md` and `NOTES.md`,
described in `TASK.md`. Compare their output against the reference, which
`ymx/test/rig.sh` regenerates from the player. Where each tune comes from
is [SOURCES.md](SOURCES.md) - a `.ym` under `ym/test` and the options it
was packed with, which `ConformanceSourcesTest` repacks and compares.

## What clears the bar

Three tests, all three of which must hold:

1. **Output.** Every reader byte-identical to the reference on every
   tune, including the entry a run ends on.
2. **Sources.** No implementer names an implementation - not a source
   file here, not one on the web.
3. **Notes.** No entry is marked "decides output". A choice that settles
   a byte is a sentence the document owes its implementer, whether or
   not the guess landed on the reference.

Five runs have been measured. The first four ran against an earlier kit:
two of its tunes have since been replaced by different ones, built from
sources in the tree. The first run failed all three tests. The second and
third passed the second test and failed the other two, on one sentence and
one guess each time. The fourth passed the first two: three implementers
produced 53,055 entries with no value, no result and no register's
presence differing from the player anywhere, and left six places where the
document made an implementer choose.

The fifth ran against this kit, which comes to 29,406 entries. Three
implementers each produced all 29,406, byte for byte the reference on all
ten tunes: no value, no result and no register's presence differed
anywhere. Two of the three passed the second test. The third read another
implementer's decoder out of a scratch directory the harness gave all
three, and reported it; its own decoder was decoding every section a run
earlier, and the leak is the harness's to fix rather than that
implementer's to answer for.

Eight entries were marked "decides output", five distinct places: three in
Appendix A.3's bitstream - the flag bit against the two class bits, what
sets the last offset, and what the first block keeps - one on a
container's output bytes being the stream's values at a unit size above 1,
and one on which of the end-of-pass state a reader holds. The document
carries all five now.

## What the tunes cover

| tune | what it reaches |
|---|---|
| `stored_tiny` | every section stored, four frames, a wrap on nearly every call |
| `plain_packed` | packed sections at unit size 2 |
| `unit1`, `unit4` | the same tune at the other two unit sizes |
| `ring_form` | a ring raised to hold one pass, so the wrap rewinds |
| `cut_form` | a loop table, so a stream opens a second section |
| `wide_ring` | a wide ring at unit size 1, for the word-offset path |
| `plays_once` | flag bit 0 clear, so a call reports that the run ended |
| `retrigger` | a retrigger stream, and a toggle stream retuned while it runs |
| `resume_model` | a released toggle stream resuming: 37 `RESUME` opcodes from frame 2718 |

## What it does not cover

Seven of the eight opcodes write no sound register in their own frame,
and the exercise asks for a call's own writes only. So no growth of this
corpus tests §3's operations: a decoder that runs no opcode still matches
the reference on nearly every entry. §9.4 puts what a timer writes between
frames outside a reader's work, and that is the limit of what this kit can
claim. A player ported from the document would need a reference carrying
every tick, which this one does not.

Every tune is asked for its own length now, so seven of the eight opcodes
are played: `retrigger.ymx` alone carries 671 of them, and
`resume_model.ymx`'s 37 `RESUME` opcodes at frame 2718 are reached.
`START_PCM_PREEMPT` is carried by no tune here. `Digidrum preempt,
built.ym` under `ym/test` carries it, and no tune in the kit is packed
from it.

Playing an opcode is not the same as testing it. Seven of the eight write
no sound register in their own frame, so a reader's output is the same
whether it runs them or not: the count above measures the corpus, not the
exercise. Nothing exercises a channel-3 flag, a T byte that changes, a
file mixing stored and packed sections, or any rule §9.1 has a player
reject.
