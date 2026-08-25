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
puts in its context before the task begins. Run from this repository that
is `CLAUDE.md` and `AGENTS.md`, the index lines of a memory file, and the
git status with the subject lines of the most recent commits. None of it
states a field, an offset or an opcode, and the subject lines do name the
format and say the repository holds an implementation and this kit. An
exercise run from outside the repository carries none of it, which is how
to run one where the isolation has to be complete. The second is what an
implementer already knows: LZ77 coding and Elias gamma are general, and
recognising them is not reading an implementation.

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

Six runs have been measured. The first four ran against an earlier kit:
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
and one on which of the end-of-pass state a reader holds.

The sixth ran against the document those five were written into, with a
scratch directory for each implementer. All three passed the first two
tests: 29,406 entries each, byte for byte the reference, and no
implementer read another's work or anything else. None of the five came
back. Five entries were marked "decides output", four distinct places:
Appendix A.4's closing sentence against the formula three lines above it,
found by two of the three; a reader's skip states before its first call,
which §7 states in a preamble a reader is not given; §9.4's own pass-end
sentence, written for the fifth run, reading as an override of §7 step 1;
and the absence of any statement that a container tests a decoder only by
how much of streams A, B and C is left over. The document carries those
four now.

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

## The second reference, which carries the ticks

`MANIFEST.txt` records a call's own writes, which is the reader of §9.4.
`MANIFEST-ticks.txt` records the same ten tunes with the timers run: the
same calls, and after each one every tick that falls before the next, in
time order. The ten come to 286,452 ticks. `ymx/test/rig.sh` regenerates
both and checks both digests.

That is the record §3, §5 and §6 can be checked against. A reader's
record cannot: seven of the eight opcodes write no sound register in
their own frame, and the exercise asks for a call's own writes only, so a
decoder that runs no opcode still matches `MANIFEST.txt` on nearly every
entry. A tick record separates them - the rate a timer lands at is §5's,
the byte it carries is §6's, and neither reaches a reader.

The MFP raises no interrupt under the unit-test emulator, so the timers
are modelled and their handlers called at the addresses the vectors hold.
`ymx/test/ticks.sh` measures how far that model is from a machine: it
builds a program for each tune, runs it under Hatari, which does emulate
the MFP, traces every sound-chip write, and compares the two one register
at a time.

| tune | register | ticks compared | differ |
|---|---|---:|---:|
| `plain_packed`, `unit1`, `unit4` | R10 | 1,764 each | 0 |
| `ring_form` | R10 | 26,146 | 0 |
| `wide_ring` | R10 | 5,424 | 0 |
| `retrigger` | R9 | 20,310 | 0 |
| `resume_model` | R10 | 35,460 | 2 |
| `retrigger` | R13 | 7,292 | 86 |
| `resume_model` | R9 | 27,219 | 2,511 |

So a drum's rate, its count of ticks and every byte it carries are the
machine's, over tens of thousands of ticks and three unit sizes.

Where the two part company they part company over one thing: which side
of a frame boundary a tick falls. The values either side are the same.
On `retrigger` the machine and the model differ by one tick in 246 of
2,220 frames, and the totals per frame match to within one everywhere;
the 86 differing writes are all the envelope shape at a frame that
changes it. On `resume_model` the same one-tick shift accounts for the
larger figure, because that stream carries a different value on 27,218
of its 27,219 ticks, so any shift at all shows in every write of the
frame it moves.

Two causes were measured for that shift, and one of them is fixed. The
model fires a timer's first tick one period after the frame boundary and
the machine fires it one period after the action that started it, part
way into the frame: offsetting the model's start by 2.5% of a frame
takes `retrigger`'s 86 differences to 9. That offset is not in the model,
because no figure in the file states it. The second was a fault: a
released toggle stream keeps its timer running and has only its
interrupt disabled (§3), and the model read the timer's control register
without the interrupt registers beside it, so it landed 481 ticks in
gaps the machine leaves silent. It reads both now.

The frame period was the first candidate and is not the cause: running
the model at a PAL ST's own VBL instead of the rate the file states made
every figure above worse.

One run has been measured against this record, the first at player level.
`TASK-player.md` is its task: it asks for the ticks as well as the calls,
and fixes as its own the four things about when a tick falls that the
document leaves to the host. Three implementers worked from `SPEC.md` and
that task, with the record kept back.

All three produced every call and every tick of all ten tunes, byte for
byte the record: 29,406 calls and 286,452 ticks each, no value, no
result, no register and no tick's place differing anywhere. None read an
implementation.

The record itself was wrong when they started, and they are why it is
not now. All three landed 157,824 ticks in `resume_model` where it held
163,830, first differing at frame 2718, where that tune's 37 `RESUME`
opcodes are. A released stream keeps its timer counting and lands no tick
(§3): the record stopped the ticks and let the count stand still, so the
frame that re-enabled the interrupt discharged 287 ticks where 51 were
due. Three readings of the document against one implementation, and the
implementation was the one that was wrong.

Seventeen entries were marked "decides output" across the three. Nine
places were found by all three of them: what the end marker puts on the
volume register (§6), what `HOLD` and `RESUME` flag 1 do to the count in
flight (§3), what a toggle stream's phase does across a disabled gap
(§3.3), where the pass-end teardown falls against the last call's ticks
(§8), which voice flag 2 reads (§3.2), which timer a tick names (§2.3),
what the call reporting -1 carries (§7), two timers due at one instant,
and where a tick that coincides with a call goes. The document carries
the seven that were its own now, and `TASK-player.md` carries the two
that were the task's.

A second player run followed, against the document those nine were
written into. All three implementers again produced every call and every
tick of all ten tunes byte for byte, and the marks fell from seventeen to
eleven. All three found the same sentence: §3.3, rewritten for the first
run, said `RESUME` "delivers the next tick one period after the count
reaches its next underflow", which passes over an underflow that §9.2
does not drop. A fix from one round opening a smaller hole for the next
to find is how two of the last three rounds have gone, so a round's
output is not purely additive. The rest of the run named where a PCM
trigger's read position starts, which prescaler a flag-1 reload
multiplies by, and three more of the task's own conventions: what a tick
coinciding with a call sees, what its first period is measured from, and
the order of a call's own writes.

## What it does not cover

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
