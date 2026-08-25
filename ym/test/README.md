# The pinned YM tunes

Thirty-six tunes from a 544-file collection and two built by hand, and the
`.ymx` each one packs to.
`PinnedCorpusTest` packs them again and compares, so a change anywhere in the
packer, the script compiler or ST4 fails here rather than in a shipped
file. The test says how to repin after an intended change.

They were taken by covering features rather than by sampling: both dialects,
every effect class the collection holds - digidrums, SID voices, and tunes
with no effect - each distinct drum rate, the tunes that put two and
three voices on the envelope at once, seven whose header gives a loop frame
other than 0, and the extremes of length (4 to 58,716 frames) and size (162
bytes to 55 KB).

All eight of the compiled script's opcodes are covered here. A recorded
dump reaches `START_PCM`, `START_TOGGLE`, `RELEASE`, and, through
`Synergy Credits`, `HOLD` and the voice-addressed `RETUNE`; `RESUME` is
reached under `-sidresume`. No YM file in the 544 starts a retrigger
stream or preempts a running timer, measured by compiling every one of
them, so two dumps carry those:

* `Sync buzzer, built.ym` runs a retrigger stream on voice C in bursts -
  a YM6 file files that as a sync-buzzer - and a toggle stream on voice B
  whose rate moves while its volume holds.
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
