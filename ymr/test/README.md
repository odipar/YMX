# The pinned .YMR tunes

Three tunes and the `.ymx` each one packs to, checked the same way the YM
tunes in [../../ym/test](../../ym/test) are: `PinnedCorpusTest` packs them
again and compares.

They carry what no YM dump in that collection does. `dd.ymr` plays once,
4,044 frames, with samples and squares across all three voices - the only
source in either collection whose header says the tune stops. `deeper.ymr`
starts over, and its script holds 321 retunes that leave the timer running.
`signals.ymr` runs 9,792 frames with 336 sync-buzzer starts.

Each is packed by `org.ymr.Ymr` at the default options, the entry point the
tools call.

What a `.YMR` conversion costs is [../CONVERSION.md](../CONVERSION.md).
