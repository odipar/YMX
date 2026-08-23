# The pinned .YMR tunes

Three tunes and the `.ymx` each one packs to, checked the same way the YM
tunes in [../../ym/test](../../ym/test) are: `PinnedCorpusTest` packs them
again and compares.

They carry what no YM dump in that collection does. `dd.ymr` plays once,
4,044 frames, with samples and squares across all three voices. `deeper.ymr`
loops, and its script holds 325 retunes that leave the timer running.
`signals.ymr` runs 9,792 frames with 672 sync-buzzer starts, and is the
largest `.ymx` here for a reason worth knowing: its effect state at the wrap
first agrees with its state at the loop frame 9,606 frames later, so the
encoder rotates the split there and the file carries nearly every frame
twice. Packed to play once it is 9,908 bytes against 19,216 looping.

Each is packed by `org.ymr.Ymr` at the default options, the entry point the
tools call.

What a `.YMR` conversion costs is [../CONVERSION.md](../CONVERSION.md).
