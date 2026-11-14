# YMX - streaming chiptunes on the Atari ST

A short talk for Atari ST and chiptune enthusiasts who know the
basics. Follow a RhYMe song from the tracker to the YM2149.
The audience should leave knowing how the same tune model connects
YMXS, YMXR, DTX and ST4.

[Full development timeline][timeline]

## 1. RhYMe and YMX on the Atari ST

**On screen**

Tracker pattern

| Row | Note | Instrument |
| --- | --- | --- |
| 00 | C-4 | 01 |
| 04 | E-4 | 01 |
| 08 | G-4 | 01 |

Prepared register rows

| Frame | R0 | R1 | R8 |
| --- | --- | --- | --- |
| 0 | DE | 01 | 0F |
| 24 | 7B |  |  |
| 48 | 3F |  | 0C |

A blank marks an omitted write.

**Visual**

Place the violet tracker pattern above its green register table.
Use an arrow between them to mark the offline conversion.

## 2. YM formats and YMX

**On screen**

YM1, YM2, YM3, YM4, YM5, YM6, YM7, … YMX

YM1 (release unverified)

YM2 through YM6 (public references)

YM7 (no public source found)

X stands for any number.

YMX is a family of technologies.

| Technology | Role |
|---|---|
| YMXS | Tune abstraction |
| ST4 | 68000 compression |
| DTX | Table abstraction |
| YMXR | Tune file and player |

YM format family by Leonard / Oxygene (Arnaud Carré)

**Visual**

Show YM1 through YM7 before the ellipsis leading to YMX.
Group YM2 through YM6 and mark the status of YM1 and YM7.
Below, highlight the sound registers and effects in one of
three YM6 frames. List the four YMX technologies.

## 3. Tat's minymiser

**On screen**

A compressed stream for each group of register values

Small rolling buffers during playback

LZSA-style compression with a 68000 decoder

minymiser by Tat / Avena (Steven Tattersall)

**Visual**

Show a short grid of register values. Mark one row as a frame and
one column as a stream. Beside the column, show the small part of
the decoded stream that is in the rolling buffer.

## 4. Batching column refills

**On screen**

One numbered row per playback frame.

| Frame | Read A | Read B | Read C | Read D | Refill column | Write for frames 5 to 8 |
|---|---|---|---|---|---|---|
| 1 | A1 | B1 | C1 | D1 | A | A5, A6, A7, A8 |
| 2 | A2 | B2 | C2 | D2 | B | B5, B6, B7, B8 |
| 3 | A3 | B3 | C3 | D3 | C | C5, C6, C7, C8 |
| 4 | A4 | B4 | C4 | D4 | D | D5, D6, D7, D8 |

**Visual**

Use one frame-number column for both actions. Show the four
current bytes on the left. On the right, use the refill column's
color for the four bytes it writes. Compare four one-byte decoder
calls with one four-byte call.

## 5. Compression for the 68000

**On screen**

| Stage | Role in the project |
|---|---|
| ZX0 | Compression target |
| ZX1 | Format used by ST1 |
| ST1 | Resumable decoding on the 68000 |
| ST4 | Aligned streams for the 68000 |

ZX1 interleaved stream for `ABCABCABC`

| Hex bytes | Use |
|---|---|
| `DB` | Bit byte |
| `41 42 43` | Literals |
| `FA` | Match offset |
| `40` | Bit byte |
| `FF FF` | End marker |

ST4 streams are bits, literals, byte offsets and word offsets.

| Decoder | Copy instruction | Bytes per move |
|---|---|---:|
| ST1, reading ZX1 | `move.b (a2)+,(a1)+` | 1 |
| ST4, two-byte unit | `move.w (a3)+,(a1)+` | 2 |

ZX0 and ZX1 by Einar Saukas

**Visual**

Show the eight encoded bytes between the encoder and decoder.
Use color to group the bit bytes, literals and offsets. Place
ST4's four streams below it, followed by the copy instructions.

## 6. Frame rows and timer ticks

**On screen**

One frame, enlarged

20 ms at 50 Hz

Wavetable after retune (2.4 kHz)

48 timer ticks per frame

Host frames 100 to 160. One line per frame. Labels every 10 frames.

| Frame | Timer | Operation | Effect |
|---:|---|---|---|
| 100 | A | Start | SID |
| 110 | B | Start | Sync buzzer |
| 120 | A | Stop | SID |
| 120 | D | Start | Digidrum |
| 130 | A | Start | Wavetable |
| 140 | A | Retune | Wavetable |
| 140 | D | Stop | Digidrum |
| 150 | A | Remap | Wavetable |
| 150 | B | Stop | Sync buzzer |
| 160 | A | Stop | Wavetable |

Digidrum · 20 frames

Wavetable · 30 frames

**Visual**

Enlarge frame 140 above the overview and show 48 timer ticks
between its boundaries. Highlight that interval on the frame
ruler below. Use a short mark for each frame and a longer,
numbered mark every tenth frame. Use a circle for a start, a
line for an active effect and a square for a stop. Mark the
retune with a diamond and the remap with a triangle on the
continuous wavetable line.

## 7. YMXS and YMXR

**On screen**

**YMXS**

Tune abstraction with frame rows and timer sources

JSON or CSV

**YMXR**

Atari ST binary format

DTX tables

68000 player

YM2149 playback

**Another YMXS player**

A layout suited to the host

**Visual**

Show one YMXS tune with JSON and CSV as text encodings. Branch
from the tune to YMXR's Atari ST binary format and its 68000
player, and to another YMXS player with a layout suited to its
host.

## 8. YMXR frame table columns

**On screen**

| Columns | Registers | Use |
|---|---|---|
| 0 to 1 | R0, R1 | Voice A tone |
| 2 to 3 | R2, R3 | Voice B tone |
| 4 to 5 | R4, R5 | Voice C tone |
| 6 | R6 | Noise |
| 7 | R7 | Mixer |
| 8 to 10 | R8, R9, R10 | Volume |
| 11 to 13 | R11, R12, R13 | Envelope |

| Timer | Count (reload value) | Control (divider + flags) | Source index | Mapping index | Target index |
|---|---:|---:|---:|---:|---:|
| A | 17 | 16 | 2 | 0 | 8 |
| D | 21 | 20 | 1 | 0 | 9 |
| B | 25 | 24 | 3 | 1 | 14 |
| C | 29 | 28 | 4 | 2 | 19 |

30 columns in v7 to v9. v10 adds four mapping columns.

Count and Control show column positions.

Mapping 0 uses identity. 1 and 2 select lookups.

**Visual**

Use a teal register strip and a table with one row per timer.
Give each timer column a distinct color. Match the Source index
and Target index colors to the source and target tables.

## 9. Timer sources and targets

**On screen**

| Effect | Source values | Playback | Target |
|---|---|---|---|
| Square-wave SID | 13, 0 | Two levels on repeat | R8 |
| Digidrum | 8, 9, 10, 11, … | Sample plays once | R8 |
| Wavetable | 0, 4, 8, 12, 15, 12, 8, 4 | One cycle on repeat | R8 |
| Sync buzzer | 0 | One value on repeat | R13 |

**Visual**

Connect the SID square wave, digidrum levels and wavetable cycle
to R8. On a separate row, connect a single value to R13.

## 10. Source, mapping and target indices

**On screen**

Timer B example

| Source 3 | Mapping 1 | Target 14 |
|---|---|---|
| 63, 1 | 1 → 2 | 63, 2 |

Example source index

| Source | DTX1 table | Columns |
|---:|---|---:|
| 1 | SID loop 1 | 1 |
| 2 | Digidrum sample 1 | 1 |
| 3 | Tone period | 2 |
| 4 | Pitch and volume | 3 |
| 5 | SID loop 2 | 1 |
| 6 | Digidrum sample 2 | 1 |

Source 0 stops the timer.

Fixed target codes

| Target | Registers | Use |
|---:|---|---|
| 8 | R8 | Voice A volume |
| 9 | R9 | Voice B volume |
| 10 | R10 | Voice C volume |
| 13 | R13 | Envelope shape |
| 14 | R0, R1 | Voice A tone |
| 17 | R0, R1, R8 | Voice A tone and volume |
| 21 | R11, R12, R13 | Envelope period and shape |
| 22 | R6, R8 | Noise and voice A volume |

Each tick reads a row, maps its last value and writes the target registers.

**Visual**

Place Mapping 1 between Source 3 and Target 14 above the index
tables. Show the input row, the lookup and the output values.
Highlight Source 3 and Target 14 in their tables. Draw a shared
branch from the two SID loops and two samples to targets 8, 9 and
10. Draw separate arrows from source 3 to target 14 and source 4
to target 17. Give the three voice volume targets distinct colors.

## 11. YMX architecture

**On screen**

RhYMe export and YMXR playback

**Visual**

Group RhYMe export, tune file data and Atari ST playback. Use a
narrow lane for the export and separate frame and timer tick blocks
within playback. Trace the DTX3 tune table into the frame block
and the DTX1 sources into the timer tick block. Both paths end at
the YM2149.

## 12. YM6 and DTX sizes

**On screen**

Wicked Polygons

Synergy Megademo · 3D screen

| Representation | Bytes |
|---|---:|
| Uncompressed YM6 | 1,278,499 |
| DTX image | 102,080 |

92.0% smaller

SNDH with player · 123,752 bytes

| SNDH component | Bytes |
|---|---:|
| Player core | 3,402 |
| Bound tune data | 408 |
| Workspace and other bytes | 17,862 |
| DTX image | 102,080 |

Packing options `-k1 -m780 -copies`

SNDH option `-link`

Standalone YMXR tune · 101,036 bytes

Music by Scavenger / Synergy

**Visual**

Show the uncompressed YM6 and the DTX image on one scale. Below
the chart, show the SNDH total with its player and a proportional
bar the same height as the table header. Show the byte counts in
the table and the packing options and tune size at the bottom.

## 13. YMXR frame cost

**On screen**

Average 68000 cycles per frame

| Tune | Playback | DTX advance | Other frame work | Effect timers | Total cycles | % of budget |
|---|---|---:|---:|---:|---:|---:|
| Chambers of Shaolin | Register writes only | 575 | 556 | 0 | 1,131 | 0.71% |
| DBA 5 | SID voice | 740 | 811 | 2,747 | 4,298 | 2.69% |
| Synergy Credits | Two SID timers | 903 | 1,092 | 4,228 | 6,223 | 3.89% |
| Deeper | SID, samples, sync buzzer | 847 | 1,303 | 11,304 | 13,454 | 8.41% |

160,000 cycles per frame at 8 MHz and 50 Hz.

**Visual**

Compare four real tunes on one scale. Stack DTX advance, other
frame work and effect timer interrupts. Label the frame call,
timer interrupts, total cycles and share of the frame budget.

## 14. Development timeline

**On screen**

AI-assisted development

Mostly Claude Opus 5 and 5.5

| 2026 | Milestone |
|---|---|
| 16 August | YX6 streams YM dumps with ST1 |
| 25 August | RhYMe adopts YMX for Atari ST exports |
| 10 September | YMXS defines the tune model |
| 11 September | RhYMe switches to the YMXR tools |
| 21 October | YMXR adopts DTX3 |
| 3 November | YMXR plays sources through lookup tables |

GitHub activity as of 8 November 2026

| Repository | Commits on main | Published releases |
|---|---:|---:|
| ST4 | 280 | 25 |
| YMX | 185 | 9 |
| YMXR | 389 | 67 |
| YMXS | 110 | 24 |
| DTX | 143 | 25 |

**Visual**

Place a vector chip icon beside the AI assistance credit. Show
the milestones above the GitHub table. Use the full timeline
for individual releases.

## 15. A RhYMe song on the Atari ST

**On screen**

Imperium

YM remake by iSo

Original by Madeon

RhYMe export

Atari ST playback

`IMPERIUM.PRG`

**Visual**

Open `Imperium.rhs` from the RhYMe demo songs. Show the pattern
in RhYMe, then export it as an Atari ST Program named
`IMPERIUM.PRG`. Play a short excerpt on an ST or in Hatari.
[RhYMe Imperium demo][rhyme-imperium]

**Credit**

Claude Code wrote most of the suite under Robbert van Dalen's
direction. [YMX attribution][credits]

[ym]: https://www.lynn3686.com/ym3456_tidy.html
[ym1-reference]: https://www.worldofsam.org/products/ym-player
[ym7]: https://github.com/arnaud-carre/sndh-player#why
[import]: https://github.com/odipar/YMXS/blob/main/doc/ym.md
[minymiser]: https://clarets.org/steve/projects/minymiser.html
[zx0]: https://github.com/einar-saukas/ZX0
[zx1]: https://github.com/einar-saukas/ZX1
[zx1-encoder]: https://github.com/einar-saukas/ZX1/blob/main/src/compress.c#L45-L71
[zx1-decoder]: https://github.com/einar-saukas/ZX1/blob/main/src/dzx1.c#L32-L60
[st1]: https://github.com/odipar/ST1
[st4]: https://github.com/odipar/ST4
[ymxs]: https://github.com/odipar/YMXS/blob/main/doc/SPEC.md
[ymxs-json]: https://github.com/odipar/YMXS/blob/aa54377/doc/json.md
[ymxs-csv]: https://github.com/odipar/YMXS/blob/aa54377/doc/csv.md
[drum]: https://github.com/odipar/YMXS/blob/main/doc/tunes/digidrum.json
[dtx]: https://github.com/odipar/DTX
[ymx-family]: https://github.com/odipar/YMX#the-four-repositories
[players]: https://github.com/odipar/YMX#building-another-player
[credits]: https://github.com/odipar/YMX#license-and-attribution
[timeline]: ymx-storyline.md#development-timeline
[rhyme]: https://github.com/sandord/rhyme
[rhyme-effects]: https://github.com/sandord/rhyme/blob/main/spec/file-formats/ymxs_export_spec.md#the-effects
[rhyme-export]: https://github.com/sandord/rhyme/blob/main/spec/file-formats/ymxs_export_spec.md
[rhyme-ymx]: https://github.com/sandord/rhyme/commit/1c6990c5
[rhyme-ymxr]: https://github.com/sandord/rhyme/commit/129ecb87
[rhyme-demo]: https://github.com/sandord/rhyme/blob/main/examples/songs/Doclands/Deeper%20-%20DDrums.rhs
[st1-streaming]: https://github.com/odipar/ST1#use-case-streaming-ym6
[dtx-refill]: https://github.com/odipar/DTX/blob/main/doc/abi.md#4-dtx2-and-dtx3-the-turn
[st4-copy]: https://github.com/odipar/ST4/blob/fbf3234/doc/decoders.md#the-copy-ladders
[ymxr-playback]: https://github.com/odipar/YMXR/blob/e89556f/README.md#reading-and-playback
[ymxr-performance]: https://github.com/odipar/YMXR/blob/e89556f/doc/performance.md#the-play-call
[ymxr-ticks]: https://github.com/odipar/YMXR/blob/4a801e0/doc/performance.md#a-tick
[ymxr-cycle-rig]: https://github.com/odipar/YMXR/blob/4a801e0/68k/test/emu/test_ymxr.py
[ymxr-fixtures]: https://github.com/odipar/YMXR/blob/e89556f/doc/conformance/SOURCES.md
[minymiser-decoder]: https://github.com/tattlemuss/minymiser/blob/master/player/ymp.s#L103-L176
[st1-copy]: https://github.com/odipar/ST1/blob/96cddfa/68k/ST1_wrap.S#L204-L220
[rhyme-tone]: https://github.com/sandord/rhyme/blob/5916db76/RhYMe/src/RhYMe.Core/Model/TonePeriodTable.cs
[minymiser-format]: https://github.com/tattlemuss/minymiser#minymiser
[ymxr-columns]: https://github.com/odipar/YMXR/blob/e89556f/doc/SPEC.md#1-the-columns

[leonard-scene]: https://demozoo.org/sceners/2527/
[tat-scene]: https://atariscne.org/news/index.php/in-the-spotlight-the-tat-avena-blog
[zx0-st-credit]: https://www.atarimania.com/games/atari-st-games-adam-is-me-41592

[synergy-3d]: https://demozoo.org/productions/169403/
[synergy-ym]: https://d-bug.me/mirror/pigwa/stuff/collections/atari_forever/Ym/index/33.html
[ym-import-slots]: https://github.com/odipar/YMXS/blob/main/doc/ym.md#4-the-slots
[ymxr-packer]: https://github.com/odipar/YMXR/blob/4a801e0/doc/tools.md#5-ym-to-ymxr

[ymxr-pack-options]: https://github.com/odipar/YMXR/blob/4a801e0/doc/tools.md#4-the-packers-flags-and-the-padding
[ymxr-sndh-layout]: https://github.com/odipar/YMXR/blob/4a801e0/doc/tools.md#12-ymxr-sndh

[stats-st4]: https://github.com/odipar/ST4
[stats-ymx]: https://github.com/odipar/YMX
[stats-ymxr]: https://github.com/odipar/YMXR
[stats-ymxs]: https://github.com/odipar/YMXS
[stats-dtx]: https://github.com/odipar/DTX
[claude-opus]: https://www.anthropic.com/claude/opus
[rhyme-imperium]: https://github.com/sandord/rhyme/blob/09067cee8f7a375b5530880bc9b214dd807419f7/examples/songs/Imperium.rhs
[ymxr-effect-rate]: https://github.com/odipar/YMXR/blob/4a801e01cf609320c971dc9d2b33150d9ab52eec/doc/SPEC.md#19-effect-rate
[ymxr-mapping]: https://github.com/odipar/YMXR/blob/4a801e01cf609320c971dc9d2b33150d9ab52eec/doc/SPEC.md#110-mapping
[ymxs-prescaler]: https://github.com/odipar/YMXS/blob/aa54377ad0bfa392f6f685e43510fda2106f02ab/doc/SPEC.md#33-the-rate
[ymxr-effect-fields]: https://github.com/odipar/YMXR/blob/4a801e01cf609320c971dc9d2b33150d9ab52eec/doc/SPEC.md#18-effect
[ymxr-target-map]: https://github.com/odipar/YMXR/blob/4a801e01cf609320c971dc9d2b33150d9ab52eec/doc/SPEC.md#21-the-targets
[ymxr-source-index]: https://github.com/odipar/YMXR/blob/4a801e01cf609320c971dc9d2b33150d9ab52eec/doc/SPEC.md#31-the-source-index
