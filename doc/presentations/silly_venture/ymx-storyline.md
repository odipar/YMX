# YMX - streaming chiptunes on the Atari ST

I designed YMX around a player that streams prepared register data
to the YM2149. A tracker can compute a complex instrument's behavior
offline and store the register values as table rows. The player can
use the same frame and tick procedures for different instruments.

To limit memory use, I wanted to decode the packed data a part at a
time during playback. My target was a compression ratio close to
ZX0's, with a decoder optimized for the 68000.

I also wanted a common model for timer effects. To play a SID voice
or a digidrum, the player writes a sequence of values to a YM2149
volume register, one value per timer tick.

## YM5 and YM6

Arnaud Carré's YM5 and YM6 formats for ST-Sound start with the sound
chip settings recorded a frame at a time. They also carry information
for effects that run between frames. In YM6, spare bits in the frame
identify the effect and its timing. I kept the idea of one row per
frame and made the timer operations explicit.
[Arnaud Carré's YM format notes][ym] · [YMXS import rules][import]

## Tat's minymiser

Steven Tattersall's minymiser was the playback inspiration. Tat groups
a register dump by register, compresses the streams, and decodes them
during playback through small rolling buffers. The ST can read a new
set of values each frame while keeping only a portion of the decoded
tune in memory. I wanted to use that approach for tunes with timer
effects as well. [Tat's minymiser notes][minymiser]

## From ZX0 to ST4

I tried Einar Saukas's ZX0 first. The decoder I tried unpacked the
data in one pass. For playback I needed to decode part of a stream,
pause, then resume on a later frame. I tried that with ST1, which
added resumable decoding on the 68000 for ZX1, Saukas's successor
to ZX0. A rolling buffer kept only the recent output.
[ZX0][zx0] · [ZX1][zx1] · [ST1][st1]

ST1 still read ZX1, a byte-oriented format designed for 8-bit machines.
ST4 split the compressed data into four streams and added units of one,
two or four bytes. The 68000 could then refill its bit buffer a word at
a time and copy aligned literal data in words or longwords. The first
YMX player came from the `.yx6` player developed alongside ST4.
[ST4][st4] · [YMX history][history]

## A row per frame, a row per tick

The tune table has a row for each frame. The player first performs any
timer operations in the row. These start, retune, stop or remap an
effect. The player then writes the YM2149 sound registers the row sets,
such as a voice's volume. The tune sets the frame rate. An effect's
prescaler and count select its tick rate from a large, fixed set of
rates derived from the MFP clock. [YMXS specification][ymxs]

At each tick the player reads the next row of the effect's source.
The mapping passes each value through unchanged or uses it as a key
in a lookup table. The target writes the resulting values to the
YM2149 registers it selects.

```text
source row -> mapping (optional lookups) -> target register writes
```

A square-wave SID source has one column with a volume level followed
by zero. The player repeats those two rows. For a digidrum, the
player reads one column of sample levels once. In the digidrum test
tune, both effects target voice A's volume register. The player
stops the SID effect on the frame that starts the drum.
[YMXS digidrum test tune][drum]

## Instrument data

A tracker can combine an arpeggio, vibrato and a volume curve in
one instrument. It can calculate the tone period and volume for
each tick offline. The source table has a column for the low part
of the period, one for the high part and one for the volume.

Changing the arpeggio or vibrato changes the values in the source
table. The player uses the same voice target to write the period
and volume. The timer rate sets how often the player reads a row,
applies its mapping and writes the registers.

A lookup on the volume column can change the level while the period
values pass through unchanged. A later frame can select a different
volume lookup without restarting the source.
[YMXS transformation example][fade]

## How the four projects fit

**YMXS** defines the tune data and what the player does on a frame
or a tick. A tracker can write the tune as JSON or CSV. **YMXR**
encodes that tune for the Atari ST and plays it on the 68000. Its tools
can package a tune as an SNDH file or a runnable TOS program.
[YMXS][ymxs] · [YMXR][ymxr]

**DTX** defines how YMXR's tables are stored and read row by row.
**ST4** compresses the changing columns of the frame table so the
DTX reader can decode them as playback advances. YMXR stores the
effect sources in separate, unpacked DTX tables for the timer ticks.
[DTX][dtx] · [ST4][st4] · [YMXR tune file layout][ymxr-format]

I can start with a YM dump or write a tune from scratch. In both
cases, YMXS defines the frame rows and timer sources. Another player
can use those same rows and sources with a different file layout.

## Development timeline

- **Before ST1.** I tried ZX0 and looked for a way to pause decoding
  between frames.
- **12 August 2026.** ST1 added a resumable ZX1 decoder for the 68000.
  [ST1 commit][st1-resume]
- **16 August 2026.** YX6 played YM register dumps through ST1.
  [First YX6 commit][yx6-first]
- **18 August 2026.** ST4 split from ST1 with a format for the 68000.
  [First ST4 commit][st4-first] · [ST4 v0.2][st4-release]
- **19 August 2026.** YX6 added timer playback for SID voices,
  digidrums and the sync buzzer. [YX6 timer playback][yx6-effects]
- **22 August 2026.** The first YMX format adopted `.yx6`. Its
  converter prepared the timer operations during packing.
  [First YMX commit][ymx-first] · [YMX binaries v0.4.1][ymx-release]
- **29 August 2026.** The design split the tune file into two layers.
  YMXR defined what each column did and DTX defined its storage.
  [YMXR and DTX design][ymxr-dtx]
- **5 September 2026.** The first DTX release packaged tables with
  68000 readers. [DTX v0.1.0][dtx-first]
- **10 September 2026.** YMXS defined the common tune model and its
  text encodings. YMXR conversion began to pass through YMXS.
  [YMXS v0.1.0][ymxs-first] · [YMXR converter commit][ymxr-converter]
- **15 September 2026.** YMX became the design document for the four
  repositories. [YMX repository split][ymx-split]
- **17 September 2026.** YMXS added sources with several columns.
  YMXR could then write several registers at a tick.
  [YMXS commit][ymxs-targets] · [YMXR v0.4.1][ymxr-targets]
- **21 October 2026.** DTX3 stored each constant column as one value.
  YMXR adopted DTX3 for the frame table.
  [DTX v0.12.0][dtx-constant] · [YMXR v0.5.0][ymxr-dtx3]
- **29 October 2026.** A deferred start let the current source finish
  before the next one began. [YMXR v0.6.0][ymxr-deferred]
- **1 November 2026.** YMXS added optional transformations and remaps.
  [YMXS v0.7.0][ymxs-mapping]
- **3 November 2026.** YMXR added playback with lookup tables.
  [YMXR v0.8.0][ymxr-mapping]
- **5 November 2026.** YMXR began including only the player code
  needed by the tunes it packages. [YMXR v0.11.0][ymxr-link]

### GitHub activity

Counts as of 8 November 2026. Each commit count covers the
history reachable from `main`. The release counts cover published
GitHub releases.

| Repository | Commits on main | Published releases |
|---|---:|---:|
| [ST4][stats-st4] | [280][stats-st4-commits] | [25][stats-st4-releases] |
| [YMX][stats-ymx] | [185][stats-ymx-commits] | [9][stats-ymx-releases] |
| [YMXR][stats-ymxr] | [389][stats-ymxr-commits] | [67][stats-ymxr-releases] |
| [YMXS][stats-ymxs] | [110][stats-ymxs-commits] | [24][stats-ymxs-releases] |
| [DTX][stats-dtx] | [143][stats-dtx-commits] | [25][stats-dtx-releases] |

Claude Code wrote most of the suite under my direction. I used
Claude Opus 5 and 5.5 for most of that work.
[YMX attribution][credits] · [Claude Opus][claude-opus]

[ym]: https://www.lynn3686.com/ym3456_tidy.html
[import]: https://github.com/odipar/YMXS/blob/main/doc/ym.md
[minymiser]: https://clarets.org/steve/projects/minymiser.html
[zx0]: https://github.com/einar-saukas/ZX0
[zx1]: https://github.com/einar-saukas/ZX1
[st1]: https://github.com/odipar/ST1
[st4]: https://github.com/odipar/ST4
[history]: https://github.com/odipar/YMX#where-the-code-went
[ymxs]: https://github.com/odipar/YMXS/blob/main/doc/SPEC.md
[drum]: https://github.com/odipar/YMXS/blob/main/doc/tunes/digidrum.json
[fade]: https://github.com/odipar/YMXS/blob/main/doc/tunes/fade.json
[ymxr]: https://github.com/odipar/YMXR
[dtx]: https://github.com/odipar/DTX
[ymxr-format]: https://github.com/odipar/YMXR/blob/main/doc/SPEC.md#33-the-tune-file
[st1-resume]: https://github.com/odipar/ST1/commit/d2ecb8f
[yx6-first]: https://github.com/odipar/ST1/commit/f03dad8
[st4-first]: https://github.com/odipar/ST4/commit/c8816ae
[st4-release]: https://github.com/odipar/ST4/releases/tag/v0.2
[yx6-effects]: https://github.com/odipar/ST4/commit/dc93647
[ymx-first]: https://github.com/odipar/YMX/commit/f2c2f74
[ymx-release]: https://github.com/odipar/YMX/releases/tag/binaries-v0.4.1
[ymxr-dtx]: https://github.com/odipar/YMXR/commit/2d0c914
[dtx-first]: https://github.com/odipar/DTX/releases/tag/v0.1.0
[ymxs-first]: https://github.com/odipar/YMXS/releases/tag/v0.1.0
[ymxr-converter]: https://github.com/odipar/YMXR/commit/4e97902
[ymx-split]: https://github.com/odipar/YMX/commit/f008877
[ymxs-targets]: https://github.com/odipar/YMXS/commit/51f9546
[ymxr-targets]: https://github.com/odipar/YMXR/releases/tag/v0.4.1
[dtx-constant]: https://github.com/odipar/DTX/releases/tag/v0.12.0
[ymxr-dtx3]: https://github.com/odipar/YMXR/releases/tag/v0.5.0
[ymxr-deferred]: https://github.com/odipar/YMXR/releases/tag/v0.6.0
[ymxs-mapping]: https://github.com/odipar/YMXS/releases/tag/v0.7.0
[ymxr-mapping]: https://github.com/odipar/YMXR/releases/tag/v0.8.0
[ymxr-link]: https://github.com/odipar/YMXR/blob/e89556f/doc/RELEASES.md#0110-2026-11-05
[credits]: https://github.com/odipar/YMX#license-and-attribution

[stats-st4]: https://github.com/odipar/ST4
[stats-ymx]: https://github.com/odipar/YMX
[stats-ymxr]: https://github.com/odipar/YMXR
[stats-ymxs]: https://github.com/odipar/YMXS
[stats-dtx]: https://github.com/odipar/DTX
[stats-st4-commits]: https://github.com/odipar/ST4/commits/fbf323480cef55126d55673f75b58e9e4d03138c/
[stats-st4-releases]: https://github.com/odipar/ST4/releases
[stats-ymx-commits]: https://github.com/odipar/YMX/commits/f6c149451c30eeb5b72923fc3ab23710e3de7af3/
[stats-ymx-releases]: https://github.com/odipar/YMX/releases
[stats-ymxr-commits]: https://github.com/odipar/YMXR/commits/67b7b455c4ada737f74f852e01c1fa3efa3e9e31/
[stats-ymxr-releases]: https://github.com/odipar/YMXR/releases
[stats-ymxs-commits]: https://github.com/odipar/YMXS/commits/aa54377ad0bfa392f6f685e43510fda2106f02ab/
[stats-ymxs-releases]: https://github.com/odipar/YMXS/releases
[stats-dtx-commits]: https://github.com/odipar/DTX/commits/5decf29407aee02b79b66b7375af03aa7580b787/
[stats-dtx-releases]: https://github.com/odipar/DTX/releases
[claude-opus]: https://www.anthropic.com/claude/opus
