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

**Speaker notes**

RhYMe, Sandor Drieënhuizen's Atari ST tracker, was the first
application to use YMX and is its primary user. A musician enters
notes and shapes instruments in RhYMe. My premise for YMX was
to work out the instrument's behavior offline and store it as
register data. The Atari ST can then stream that data to the
YM2149 using the same playback procedures for different instruments.

This example uses a square instrument on voice A and six frames per
tracker row. R0 and R1 encode its tone period and R8 sets its volume.
The table shows the writes for those registers at the three notes.
[RhYMe tracker][rhyme] · [RhYMe tone periods][rhyme-tone]
[RhYMe YMXS export][rhyme-export]

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

**Speaker notes**

I started from an idea familiar from Arnaud Carré's YM5 and YM6
formats. Record the settings that reach the sound chip each frame
and play them back in order. YM6 also carries instructions for
effects between frames. It encodes them in spare bits and the
last two bytes of a frame. I kept the frame rows and made the
timer operations explicit in the tune data.

Carré's published notes mention YM2 and specify YM3 through
YM6. The YM1 label appears in public references. I could not
verify a release or a specification from Carré.
[Carré's YM format notes][ym] · [YM-Player notes][ym1-reference]

Arnaud Carré mentions writing a YM7 player for an embedded
device. I found no public YM7 source code or format
specification. [Carré's YM7 reference][ym7]

YMX follows the YM numbering. X stands for any number. YMXS
defines the tune abstraction. YMXR encodes a tune as DTX tables
and plays it on the Atari ST. DTX defines the table abstraction,
and ST4 compresses columns for the 68000.
[YMXS import rules][import]
[YMX family][ymx-family] · [Leonard / Oxygene][leonard-scene]

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

**Speaker notes**

I drew on Steven Tattersall's minymiser for streaming playback. Tat
groups the values by register, with the mixer bits stored alongside
the volumes. The player decodes the compressed streams as the tune
plays and keeps the recent output in rolling buffers. The decoder
can copy earlier values from those buffers while the tune stays
packed in memory.
[Tat's minymiser notes][minymiser] · [Decoder source][minymiser-decoder]
[minymiser format][minymiser-format] · [Tat / Avena][tat-scene]

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

**Speaker notes**

I separated reading a row from refilling the buffers. Before
playback, each buffer has its bytes for frames 1 to 4. In frame 1,
the player reads A1, B1, C1 and D1. That frame's refill writes A5
through A8 to column A's buffer. The next frame reads row 2 and
refills B. The number on each byte is the frame that will read it.

With `N` columns of one-byte values, a refill writes `N` bytes to
one column's buffer. Loading and saving decoder state once per
batch spreads that cost over `N` bytes.
[ST1 streaming design][st1-streaming] · [DTX refill schedule][dtx-refill]

With four columns of one-byte values, four one-byte decoder calls
become one four-byte call per frame. DTX3 stores constant columns
as values. The refill period can exceed the column count to fit
the ring and decoding unit.

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

**Speaker notes**

I tried Einar Saukas's ZX0 first. I was aiming for a similar
compression ratio and a decoder suited to the 68000. The ZX0
decoder I tried unpacked in one pass. I needed to pause partway
through a stream and resume later. ST1 did that with ZX1, Saukas's
successor to ZX0. ST1 still read a format designed for 8-bit
machines.

I ran the ZX1 encoder on `ABCABCABC` and decoded the result back
to the same input. The encoder reserves a byte for bits and fills
it as it emits literals and offsets to the same stream. The
decoder loads the first bit byte, `DB`, to read a literal length
of three. It reads `41 42 43` as literals while keeping the
remaining bits from `DB`. After the new offset `FA`, it reads the
rest of the match length from `DB` and `40`. The match copies
six bytes from three bytes back. The offset `FF FF` ends the
stream.

ST4 split the compressed data into four streams. That let the
68000 refill its bit buffer a word at a time and copy aligned
literals in words or longwords.

ST1's ZX1 decoder copies one byte per move with
`move.b (a2)+,(a1)+`. With a two-byte unit, the ST4 copy ladder
repeats `move.w (a3)+,(a1)+`. Each move copies a word and advances
both pointers. A jump enters
the short match ladder at the first move needed for that segment.
Longer matches and literals use the counted ladder.
[ZX0][zx0] · [ZX1][zx1] · [ST1][st1] · [ST4][st4]
[ZX0 credit in an Atari ST release][zx0-st-credit]

[ZX1 encoder][zx1-encoder] · [ZX1 decoder][zx1-decoder]
[ST1 byte-copy ladder][st1-copy] · [ST4 copy ladders][st4-copy]

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

**Speaker notes**

The host reads one tune row per frame. The player applies the
row's timer operations before writing its sound registers. At a
timer tick, it reads the next row of the running source. The
prescaler and count set that timer's rate.

Timer A starts a SID at frame 100 and stops it at frame 120.
After 10 idle frames, it starts a wavetable at frame 130 with
a new source at 1.2 kHz. Frame 140 retunes it to 2.4 kHz.
Frame 150 switches the volume lookup. The next tick uses the
new lookup at the same rate and source position. Frame 160
stops the wavetable after 30 frames.

Timer D plays a 20-frame digidrum from frame 120 to frame 140.
Timer B runs independently.

The zoom shows one host frame from the start of frame 140 to
the start of frame 141. At 50 Hz each frame lasts 20 ms. The
wavetable uses a prescaler of 64. The retune changes the count
from 32 to 16, so the timer goes from 24 to 48 ticks per frame.
[YMXS frame and tick procedures][ymxs] · [RhYMe effects][rhyme-effects]
[YMXR effect rate][ymxr-effect-rate] · [YMXR mapping][ymxr-mapping]

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

**Speaker notes**

YMXS defines the tune data and what playback does on a frame or
timer tick. JSON and CSV encode that data as text. JSON supports
every tune. CSV supports text fields free of line breaks.

YMXR defines an Atari ST binary tune format. The tune file
stores its frame table and timer sources as DTX tables. YMXR
also includes a 68000 player. Another implementation can follow
the YMXR specification and read the same tune files. A separate
YMXS player can define a different playback layout, for example
for a game that shares voices between music and sound effects.
[YMXS specification][ymxs] · [YMXS JSON][ymxs-json] · [YMXS CSV][ymxs-csv]
[YMXR specification][ymxr-columns] · [Building another player][players]

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

**Speaker notes**

YMXR encodes the YMXS frame rows in a DTX table. The player reads
one row per frame. Columns 0 to 13 correspond to the YM2149 sound
registers. The timer columns set the target, source, control and
count for each effect. The file groups those fields in Timer A, D,
B, C order. Count and Control show column positions. Version 10
appends mapping columns 30 to 33 in the same timer order.

The other cells show example indices. Source 2 is a digidrum on
R8. Source 1 is a SID loop on R9. Source 3 writes a tone period
to R0 and R1. Source 4 writes pitch and volume to R4, R5 and R10
through target 19. Mapping 0 writes source values unchanged.
Mappings 1 and 2 select lookups.

Bits 0 to 2 of Control select the prescaler divisor. The upper
bits carry flags for the write and timer operation. Count is the
reload value in the timer's data register. The prescaler divisor
and reload count together set the tick rate. A Count byte of zero
counts as 256 when Control marks it as a value.
[YMXR column layout][ymxr-columns] · [YMXR effect rate][ymxr-effect-rate]
[YMXS prescaler and count][ymxs-prescaler] · [YMXR mapping][ymxr-mapping]
[YMXR target map][ymxr-target-map]

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

**Speaker notes**

The player writes one source value to the target at each timer
tick. The first three sources supply volume levels. The examples
use R8 for voice A. Voices B and C use R9 and R10.

The SID loops two levels, the digidrum plays a sample once in this
example, and the wavetable loops one cycle. The SID and digidrum
values come from the YMXS test tune, where the drum replaces the
SID on voice A. The wavetable values are illustrative.

The sync buzzer source has one row with one value, the envelope
shape. Writing the same shape to R13 restarts the envelope on
every tick. The value 0 comes from the RhYMe export of Doclands'
Deeper.
[YMXS digidrum test tune][drum] · [RhYMe timer effects][rhyme-effects]
[YMXS register behavior][ymxs] · [RhYMe demo song][rhyme-demo]

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

**Speaker notes**

The Source field selects a DTX1 table from the tune's source index.
A tune can use several SID loops and samples. Source numbers start
at 1. Setting the Source field to 0 stops the timer.

The Target field selects a procedure from the fixed YMXR target
map. Targets 8, 9 and 10 write the volume registers R8, R9 and
R10 for voices A, B and C. The player can route both SID loops and
both digidrum samples to any of these targets. Target 14 writes the
tone period to R0 and R1. Target 17 writes pitch and volume to R0,
R1 and R8.

The example uses Timer B from the YMXR frame table. Source 3
supplies 63 and 1. Mapping 1 selects a lookup that maps 1 to 2.
Target 14 writes 63 to R0 and 2 to R1. The lookup applies to the
last source column. Mapping 0 writes each value unchanged.

The numbers shown are the decoded fields. Bit 7 marks a set
field, and bits 0 to 6 encode the source number or target code.
The source index is specific to each tune. The target map is
part of the format.
[YMXR effect columns][ymxr-effect-fields] · [YMXR target map][ymxr-target-map]
[YMXR source index][ymxr-source-index] · [YMXR mapping][ymxr-mapping]

**Visual**

Place Mapping 1 between Source 3 and Target 14 above the index
tables. Show the input row, the lookup and the output values.
Highlight Source 3 and Target 14 in their tables. Draw a shared
branch from the two SID loops and two samples to targets 8, 9 and
10. Draw separate arrows from source 3 to target 14 and source 4
to target 17. Give the three voice volume targets distinct colors.

## 11. Development timeline

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

**Speaker notes**

YX6 first streamed register dumps. After moving it to ST4, we added
timer playback for SID voices, digidrums and the sync buzzer. The
first YMX format came from that player. RhYMe used it for Atari ST
exports that August. Once YMXS separated the tune model from the
playback format, RhYMe could prepare the tune as YMXS and pass it
to the YMXR tools.
[RhYMe adopts YMX][rhyme-ymx] · [RhYMe moves to YMXR][rhyme-ymxr]
[Git commits and release notes][timeline]

The commit counts cover the history reachable from `main`. The
release counts cover published GitHub releases.
[ST4][stats-st4] · [YMX][stats-ymx] · [YMXR][stats-ymxr] · [YMXS][stats-ymxs] · [DTX][stats-dtx]

Claude Code wrote most of the suite under my direction. I used
Claude Opus 5 and 5.5 for most of that work.
[YMX attribution][credits] · [Claude Opus][claude-opus]

**Visual**

Place a vector chip icon beside the AI assistance credit. Show
the milestones above the GitHub table. Use the full timeline
for individual releases.

## 12. YMX architecture

**On screen**

RhYMe export and YMXR playback

**Speaker notes**

RhYMe prepares a YMXS tune offline. The YMXR tools package the
tune and its player as SNDH or a TOS program.

The host frame clock calls YMXR to apply a DTX3 tune row. ST4
decompresses the changing columns. The frame writes the registers
the row sets, controls the MFP timers and selects the active
mapping when the row starts or remaps an effect.

On an MFP interrupt, YMXR reads a DTX1 source row at the current
source position. The active mapping selects identity or a lookup.
YMXR maps the last value, preserves earlier values and writes the
target registers. Frame writes and tick writes reach the YM2149.
[YMXR playback diagram][ymxr-playback] · [RhYMe export][rhyme-export]
[DTX tables][dtx]

**Visual**

Group RhYMe export, tune file data and Atari ST playback. Use a
narrow lane for the export and separate frame and timer tick blocks
within playback. Trace the DTX3 tune table into the frame block
and the DTX1 sources into the timer tick block. Both paths end at
the YM2149.

## 13. YM6 and DTX sizes

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

**Speaker notes**

Scavenger wrote the music for Rapido's 3D screen in the Synergy
Megademo. The combined dump in the archive has a truncated
payload. I joined the two complete YM5 parts in frame order and
encoded the square-wave effect in YM6. The result has 79,900
frames at 50 Hz.

I compared the uncompressed YM6 with the DTX image from the
smallest SNDH I measured. The separate SNDH breakdown includes
the YMXR player and its workspace.

I tested ST4 unit widths and ring sizes with and without
literal-stream copies. This build uses 1-byte units and a
780-byte ring per live column, with copies enabled. A 1,920-byte
ring reduced the standalone tune to 96,072 bytes, but increased
the SNDH to 139,308 bytes because it used more workspace.

The linked player core is 3,402 bytes. The bound tune data
includes the timer sources. The workspace includes 15,600 bytes
of DTX state, 2,136 bytes of YMXR state and 2 bytes for alignment.
SNDH headers, subtune offsets and file alignment add 124 bytes.
The DTX image has 100,604 bytes of packed frame table and
1,476 bytes for its reader and column offsets.

Pack with `ym-to-ymxr -k1 -m780 -copies`. Build the SNDH with
`ymxr-sndh -link -t'Wicked Polygons' -c'Scavenger / Synergy'`.
[Synergy 3D screen and credits][synergy-3d] · [YM soundtrack archive][synergy-ym]
[YM slot encoding][ym-import-slots] · [YMXR packer][ymxr-packer]
[YMXR packing options][ymxr-pack-options] · [YMXR SNDH layout][ymxr-sndh-layout]

**Visual**

Show the uncompressed YM6 and the DTX image on one scale. Below
the chart, show the SNDH total with its player and a proportional
bar the same height as the table header. Show the byte counts in
the table and the packing options and tune size at the bottom.

## 14. YMXR frame cost

**On screen**

Average 68000 cycles per frame

| Tune | Playback | DTX advance | Other frame work | Effect timers | Total cycles | % of budget |
|---|---|---:|---:|---:|---:|---:|
| Chambers of Shaolin | Register writes only | 575 | 556 | 0 | 1,131 | 0.71% |
| DBA 5 | SID voice | 740 | 811 | 2,747 | 4,298 | 2.69% |
| Synergy Credits | Two SID timers | 903 | 1,092 | 4,228 | 6,223 | 3.89% |
| Deeper | SID, samples, sync buzzer | 847 | 1,303 | 11,304 | 13,454 | 8.41% |

160,000 cycles per frame at 8 MHz and 50 Hz.

**Speaker notes**

The YMXR cycle rig counts 68000 instructions with zero wait states.
The looping tunes ran through one wrap. The digidrum version of
Doclands' Deeper came from RhYMe as YMXS and was packed with
two-byte ST4 units, as were the other tunes. Deeper ran through
the end of the song.

The frame call includes the DTX advance and the rest of the
player's frame work. The timer figures count the handler cycles
and 64 cycles per interrupt for entry and return. Count each
interrupt in the rig's tick trace. Add that cost to the frame
call. The host's frame clock is outside these figures.

All four tunes run at 50 Hz. At a nominal 8 MHz, the frame
budget is 160,000 cycles. Divide total cycles by that budget
to get the percentage.
[YMXR frame measurements][ymxr-performance] · [YMXR tick costs][ymxr-ticks]
[YMXR cycle rig][ymxr-cycle-rig] · [Benchmark tunes][ymxr-fixtures]
[RhYMe demo song][rhyme-demo]

**Visual**

Compare four real tunes on one scale. Stack DTX advance, other
frame work and effect timer interrupts. Label the frame call,
timer interrupts, total cycles and share of the frame budget.

## 15. A RhYMe song on the Atari ST

**On screen**

Imperium

YM remake by iSo

Original by Madeon

RhYMe export

Atari ST playback

`IMPERIUM.PRG`

**Speaker notes**

To close, I play iSo's YM remake of Madeon's Imperium. RhYMe can
save its YMXS data or use the YMXR tools to package the tune as
SNDH or a TOS program.
Another player can read that same YMXS tune and choose a layout
suited to its host.
[RhYMe export][rhyme-export] · [YMX player design][players]

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
