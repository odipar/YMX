# The conformance kit

Ten packed tunes, and what the 68000 player writes to the sound chip for
each. The kit exists to test [SPEC.md](../SPEC.md) rather than the code:
hand it to a reader who has never seen this repository, and see whether
the document alone is enough to decode the tunes.

## Running the exercise

Copy `TASK.md`, `tunes/` and `../SPEC.md` into a fresh directory and give
that directory to a reader with no access to this repository and no
access to any implementation of the format or of the compression under
it. Keep `MANIFEST.txt` and the reference dumps back: a reader who can
check an answer is not reading the document, and the exercise measures
the document.

The reader produces `decode.py`, `SOURCES.md` and `NOTES.md`, described
in `TASK.md`. Compare their output against the reference, which
`ymx/test/rig.sh` regenerates from the player.

## What clears the bar

Three tests, all three of which must hold:

1. **Output.** Every reader byte-identical to the reference on every
   tune, including the entry a run ends on.
2. **Sources.** No reader names an implementation - not a source file
   here, not one on the web.
3. **Notes.** No entry describes a guess that changed a byte a reader
   emitted.

Two runs have been measured. The first failed all three; the second
passed the second and failed the other two on one sentence and one
guess.

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
| `retrigger` | a retrigger stream and both forms of RETUNE |
| `resume_model` | a released toggle stream resuming |

## What it does not cover

Seven of the eight opcodes write no sound register in their own frame,
and the exercise asks for a call's own writes only. So no growth of this
corpus tests §3: a decoder that runs no opcode at all still matches the
reference on nearly every entry. Testing §3, §5 and §6 needs a reference
that carries what a timer stream writes between frames, which this one
does not.

`START_PCM_PREEMPT` and `RESUME` are played by no tune here, and by no
tune in the tree. Nothing exercises a channel-3 flag, a T byte that
changes, a looped sample, a file mixing stored and packed sections, or
any rule §9.1 has a player reject.
