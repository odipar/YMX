# The pinned YM tunes

Thirty-six tunes from a 544-file collection, and the `.ymx` each one packs to.
`PinnedCorpusTest` packs them again and compares, so a change anywhere in the
packer, the script compiler or ST4 fails here rather than in a shipped
file. The test says how to repin after an intended change.

They were taken by covering features rather than by sampling: both dialects,
every effect class the collection holds - digidrums, SID voices, and tunes
with no effect at all - each distinct drum rate, the tunes that put two and
three voices on the envelope at once, seven whose header gives a loop frame
other than 0, and the extremes of length (4 to 58,716 frames) and size (162
bytes to 55 KB).

The compiled script's opcodes are covered between the two collections. A YM
dump reaches `START_PCM`, `START_TOGGLE`, `RELEASE`, and, through
`Synergy Credits`, `HOLD` and the voice-addressed `RETUNE`. No YM file in
the 544 starts a retrigger stream or preempts a running timer, measured by
compiling every one of them, so `START_RETRIGGER` and `START_PCM_PREEMPT`
are reached by no tune here and are built by hand in the rig's effect
stage. `OpcodeCoverageTest` holds that list: it names the two, fails if a
third joins them, and fails again if a tune starts reaching one, so the
list only shrinks. `RESUME` is reached under `-sidresume`, and the live
`RETUNE` addressed to voice 3 by no YM source.

Each is packed by `org.ym6.Ymx` at the default options, the entry point the
tools call, so what is pinned is what a user gets. Packing is deterministic:
the same source and options give the same bytes.

What a YM conversion costs is [../CONVERSION.md](../CONVERSION.md).
