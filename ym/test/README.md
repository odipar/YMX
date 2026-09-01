# The pinned YM tunes

Thirty-nine tunes from a 544-file collection and two built by hand, and
the `.ymx` each one packs to.
`PinnedCorpusTest` packs them again and compares, so a change anywhere in the
packer, the script compiler or ST4 fails here rather than in a shipped
file. The test says how to repin after an intended change.

They were chosen to cover features rather than to sample: both dialects,
every effect class the collection holds - digidrums, SID voices, and tunes
with no effect - each distinct drum rate, the tunes that put two and
three voices on the envelope at once, eight whose header gives a loop frame
other than 0, and the extremes of length (4 to 58,716 frames) and size (162
bytes to 55 KB).

Three carry the extremes of what a player is asked to keep up with, which
the rest of the set leaves at the middle:

* `Synergy Wicked Polygons 2` loops from frame 41,403 of 43,132, the
  furthest into a tune the collection loops from, where the next pinned
  tune loops from 2,019. It acts on 16,403 frames, more than any other
  file in the collection.
* `Chambers of Shaolin - Mega Pock Olipse` runs a timer at 512 ticks a
  frame, which the collection reaches nowhere else but in the four
  Chambers tunes.
* `Sid Music #1` acts on 13,558 of its 17,153 frames, a SID voice moving
  on four frames in five.

All eight of the compiled script's opcodes are covered here. A recorded
dump reaches `START_PCM`, `START_TOGGLE`, `RELEASE`, and, through
`Synergy Credits`, `HOLD` and the voice-addressed `RETUNE`; `RESUME` is
reached under `-sidresume`. No YM file in the 544 starts a retrigger
stream or preempts a running timer, measured by compiling every one of
them, so two dumps carry those:

* `Sync buzzer, built.ym` runs a retrigger stream on voice C in bursts -
  a YM6 file files that as a sync-buzzer - and a toggle stream on voice B
  whose rate moves while its volume holds.
* `Retrigger retune, built.ym` runs one unbroken retrigger stream on
  voice C whose shape and rate step on the same frame, which the buzzer
  above deliberately does not: its shape steps only where a burst starts.
  It is the tune that reaches `START_RETRIGGER` at voice 3.
* `Digidrum preempt, built.ym` puts both of a frame's effect slots on
  voice A, a drum arriving every twentieth frame on the voice a toggle
  stream is running on, so the drum's stream stops that timer first.

`org.ymx.rig.BuiltTunes` is the source both come out of and
`BuiltTunesTest` rebuilds them, so each stays reproducible from that
source. `OpcodeCoverageTest` holds the coverage: it fails if
an opcode a tune reaches stops being reached, and its list of opcodes no
tune reaches is empty. The live `RETUNE` addressed to voice 3 is reached
by no YM source and by no tune here: a YM file records a code sitting in
a register, not the moment a player reprogrammed a running timer.

Each is packed by `org.ym6.Ymx` at the default options, the entry point the
tools call, so what is pinned is what a user gets. Packing is deterministic:
the same source and options give the same bytes.

[../CONVERSION.md](../CONVERSION.md) states what a YM conversion costs.
