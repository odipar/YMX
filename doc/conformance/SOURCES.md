# Where the kit's tunes come from

Each tune here is a `.ym` under `ym/test` packed with the options in its
row. `ConformanceSourcesTest` repacks every one and compares it to the
file in `tunes/`, so a tune and the account of how it was made stay one
thing.

Two of them once had no row. They came from a converter the tree no
longer carries, so a format change that moved a body byte would have
stranded them: version 0.6 added four header bytes and left the body
alone, so they came through it. Both are replaced here by different
tunes, packed from sources in the tree and covering what those two
covered.

| tune | source under `ym/test` | packer options |
|---|---|---|
| `stored_tiny` | `Circus Attractions  2.ym` | `-frames4` |
| `plain_packed` | `Turrican 2 - world completed 1.ym` | none |
| `unit1` | `Turrican 2 - world completed 1.ym` | `-k1` |
| `unit4` | `Turrican 2 - world completed 1.ym` | `-k4` |
| `ring_form` | `Turrican - world 4-3.ym` | `-n1776` |
| `cut_form` | `Dragon Flight  4 - Finish 1.ym` | none |
| `wide_ring` | `Turrican - world 4-1.ym` | `-k1 -n2048 -c32` |
| `plays_once` | `Knucklebusters.ym` | `-o -frames4000` |
| `repeat_trigger` | `Chambers of Shaolin - Chinese revolution.ym` | `-frames120` |
| `retrigger` | `Sync buzzer, built.ym` | none |
| `resume_model` | `Synergy Credits.ym` | `-sidresume` |

`Sync buzzer, built.ym` is built rather than recorded: no file in the
collection carries a sync-buzzer. `org.ymx.rig.BuiltTunes` holds the
source it comes out of and `BuiltTunesTest` rebuilds it.
