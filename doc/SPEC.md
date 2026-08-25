# The YMX format - specification

Version 0.5. Big-endian throughout.

YMX is a streaming register-dump format for the YM2149 sound chip in the
Atari ST, playable by a 68000 without the tune resident in memory. A file
carries twenty-five independently compressed streams: fourteen register
streams, one value per frame, and eleven streams of a **compiled effect
script** that drives the MFP's timers. Twenty-five is the stored count in
every file; §1.5 separates it from the decoded and the carrying counts.
Each stream is decoded through a ring of `N` bytes, one stream refilled per
frame, so memory use depends on the player configuration, not on tune
length.

The YM formats - YM4 to YM6 - store "special effects" as values the player
re-derives every frame from spare register bits. YMX resolves them at pack
time and stores the outcome; a player compares nothing at run time.

Two terms recur. A **player** reads §1, §2 and §6, performs §7, §8 and
§9.2, and checks §9.1. A
**writer** - a packer, or a tracker emitting the format directly - is bound
by every rule in this document; §9.3 lists the rules a player does not
have to check.

The format began as the `.yx6` container of the [ST4](https://github.com/odipar/ST4)
repository, renumbered; the layout has changed since, and this document
defines the current layout. No older YMX version exists to stay compatible
with.

**Vocabulary.** The five terms used most, defined here;
[terminology.md](terminology.md) maps this vocabulary to the names the
source formats and their tools use:

| word | meaning |
|---|---|
| **frame** | one call of the player; the tune's clock, typically 50 Hz |
| **stream** | a series of values arriving at one destination |
| **frame stream** | a stream delivering one value per frame - the fourteen sound registers |
| **timer stream** | a stream delivering values *between* frames, at a rate an MFP timer sets |
| **section** | one stream's values for a run of frames, packed |

---

## 1. The container

```
+--------------------------------+
| header, 38 bytes fixed         |
| section table, 4 x S           |
| loop table, 4 x S, or absent   |
| sections, located by offset    |
| sample table + sample bytes    |
+--------------------------------+
```

### 1.1 Header

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `'YMX!'` - `$594D5821` |
| 4 | 2 | format version, the major byte then the minor - **$0005**, version 0.5 |
| 6 | 2 | flags (§1.2) |
| 8 | 4 | `O`, the frame count |
| 12 | 2 | frame rate in Hz: how often the player is called |
| 14 | 2 | `S`, the stream count - always **25**, see §1.5 |
| 16 | 2 | `N`, the ring size in bytes |
| 18 | 2 | `C`, values decoded per call |
| 20 | 4 | the YM2149's master clock in Hz - informational; **2000000** for an ST tune, 0 with no source clock |
| 24 | 4 | byte offset of the sample table; 0 when there are none |
| 28 | 2 | sample count |
| 30 | 4 | `L`, the frame a tune that starts over goes back to (§8), within the bounds §9.3 gives it; 0 where the tune plays once through, and 0 where it starts over from its first frame |
| 34 | 4 | byte offset of the **loop table** (§1.4); 0 where the sections cover the whole tune |
| 38 | 4·S | byte offset of each **section** (§1.4) |

The fixed fields occupy bytes 0 to 37 and the section table bytes 38 to
137, so with `S` fixed at 25 the header is **138 bytes**. Every body item
begins on a long boundary, so the first of them is at offset 140 or later.
Everything after the header is
body: the loop table (§1.4) where the file carries one, then the sections
(§1.4), then the sample table (§6). Content in the body
is located only by offset - a section by its table entry, the sample table
by the offset at byte 24. Every offset counts from the file's first byte,
the magic at offset 0, and a **long boundary** is an offset divisible by
four.

### 1.2 Flags

| bit | meaning |
|---:|---|
| 0 | the tune starts over at frame `L` instead of ending |
| 1 | the tune uses timer channel 0 |
| 2 | timer channel 1 |
| 3 | timer channel 2 |
| 4 | timer channel 3 |
| 5-15 | reserved, written as 0 |

A player claims an MFP timer only for channels whose flags are set; other
timers are not touched. Which timer a flagged channel gets is defined by
stream T (§2.3).

### 1.3 `N` and `C`

`N` is the ring size in bytes, one ring per stream, and the maximum
back-reference distance in every section. `N` is capped at **2520**: a
player may read register `k`'s ring through an assembled-in displacement of
`k·N`, and `13·N` must fit a signed 16-bit displacement.

`C` is the number of values decoded per refill call. Constraints:

- `C` is at least the number of streams the tune decodes: 17 with no timer
  channel in use, then 19, 21, 23 or 25 by the highest channel in use
  (§1.5);
- `C` divides `N`;
- `N` is at least `2C`.

At a unit size above 1 (§1.4), `C` and `O` are multiples of the unit size.

960/24 is the default pair, valid for every tune that leaves channel 3
idle.

### 1.4 The sections

A section carries one stream's values, packed or stored. `O` is at least
1 and all twenty-five section-table entries are nonzero. Section order in
the file is not significant; a section is located only by its offset.

**One set of sections or two.** Where the header's loop table offset is
0, each stream has one section, covering frames `[0, O)`. Where it is
not, it locates a second table of twenty-five long entries, read exactly
as the section table is and beginning on a long boundary: each stream then
has two sections, the section table's covering frames `[0, L)` and the
loop table's covering `[L, O)`. Two sections carry the tune between them,
each frame in one of the pair, and the second is where a pass after the
first reads its values (§8).

**Packed sections.** A packed section is a complete ST4 container - a
twenty-byte header whose first long is the signature, then four streams.
Appendix A states the container and its bitstream in full; the format
comes from the [ST4](https://github.com/odipar/ST4) repository, and
Appendix A is what this document is read against. A writer with no ST4
compressor stores every section instead (below) and emits no container.

**Two fixed parameters.** No back-reference exceeds `N` bytes (§1.3),
and no operation exceeds 65535 units.

**Unit size.** Every section in a file is packed at one unit size - 1, 2
or 4 bytes, recorded with the ST4 format version in the section's
signature, not in the YMX header. A packed section's first long is
`$53 $34 $04 k`: `'S'`, `'4'`, ST4 format version 4, and the unit size
`k`, one of 1, 2 or 4. Version 4 is the version this document defines. A
player built for one unit size rejects a container whose fourth byte
differs; a player that decodes all three rejects a file whose sections do
not share one unit size (§9.1).

**Stored sections.** A section may instead be stored: the bytes at its
offset are the values, one per frame, with no header and no signature.
Bit 31 of an entry set marks a stored section, in either table; bits 30-0
are the offset. A container's header is twenty bytes, so a section
shorter than twenty bytes is smaller stored (a one-frame tune stores one
byte per stream). A stored section is read the same at any unit size; a
file with only stored sections plays on any build.

**Alignment.** Every section, packed or stored, begins on a long
boundary, so a player may read a container's header a long at a time.
Bytes between one part and the next long boundary are padding: nothing
reads them, and their value is free.

**Starting over.** Where a tune with flag bit 0 set decodes past the end
of a section - which happens only where `O - L` is larger than `N` (§8) -
the player opens a section from its start: the loop table's section for
that stream where the file carries a loop table, the same section again
where it does not. Where `O - L` is at most `N`, refilling stops at `O`
values and no section is opened twice.

A section's packed length is not stored: an ST4 decoder stops on the
container's end marker. The container's own header gives the output size in
bytes, and that size equals the section's frame count - `O` where the file
carries no loop table, `L` and `O - L` where it does.

### 1.5 Three stream counts

`S` is fixed at 25; a player must reject any other value. The section table is
therefore always 100 bytes and the payload begins at a constant offset.
Three counts apply:

| | |
|---|---|
| **stored** - always 25 | the size of the section table; all entries nonzero |
| **decoded** - 17, 19, 21, 23 or 25 | what a player reads per cycle: 17 where no channel is flagged, and `17 + 2(h + 1)` where `h` is the **highest** flagged channel |
| **carrying** - fewer still | an idle channel's pair is one value repeated, and compresses to almost nothing |

`C` covers the middle count. It steps by the highest channel in use, not
by the number in use: a player decodes stream 0 through the highest used
channel's pair and stops. A tune using only channel 3 decodes 25.

The channel pairs are last in stream order, so idle channels lie past the
decoded range and cost nothing.

---

## 2. The streams

Twenty-five slots, in this order. Streams 0-13 are frame streams; 14-24
are script data whose bytes never reach a chip register.

| # | name | carries |
|---:|---|---|
| 0-13 | R0-R13 | the YM2149's sound registers, one value per frame |
| 14 | **M** | what acts this frame, and which volume writes to skip |
| 15 | **X** | the operands an action byte has no room for |
| 16 | **T** | the channel-to-timer map |
| 17,18 | **A0,P0** | timer channel 0's action byte and timer count |
| 19,20 | **A1,P1** | timer channel 1 |
| 21,22 | **A2,P2** | timer channel 2 |
| 23,24 | **A3,P3** | timer channel 3 |

**Read schedule.** M, T and the fourteen register streams are read every
frame. Every other stream byte is read only as specified: an A byte on a
frame whose M bit marks the channel, a P or X byte where the opcode reads
one (§3). On other frames the byte's value is unspecified: any byte is
valid there - a stream's bytes before its first read included - and
repeating the previous byte compresses to nothing.

**Register masks.** A register value contains only the bits in the table
below; a writer writes the remaining bits as zero (§9.3), and a player
masks nothing:

| register | bits |
|---|---|
| R0, R2, R4 | 8 - a tone period's fine byte |
| R1, R3, R5 | 4 - its coarse nibble |
| R6 | 5 - the noise period |
| R7 | 6 - the mixer, bits 5-0; see below |
| R8, R9, R10 | 5 - four bits of level, and bit 4 following the envelope |
| R11, R12 | 8 - the envelope period, fine and coarse |
| R13 | 4 - the shape, or the whole byte `$FF`; see below |

Two registers have additional rules.

**R7 carries no port bits.** Bits 7-6 of the mixer register are the chip's
I/O port directions, not sound. The byte written to R7 is
`(value & $3F) | ports`, where `value` is the stream's byte and `ports` is
the host's port directions in bits 7-6. On an Atari ST `ports` is `$C0` -
both ports output, since port A drives the floppy select lines; port bits
taken from the file would drive the floppy selects with tune data. Bits
5-0 arrive with every sample-playing voice **disconnected** - no generator
mixed into the voice - applied at pack time (§9.3).

**R13 has a do-not-write value.** Any write to R13 restarts the envelope,
so a stream repeating the current shape would restart it every frame.
`$FF` is a marker: on a frame carrying `$FF`, the frame write does not
write R13. R13 is four bits wide, every genuine shape is `$00`-`$0F`, and
`$FF` is the one value that reaches a stream unmasked.

### 2.1 M - the master byte

`0`: nothing acts this frame.

| bits | meaning |
|---:|---|
| 0 | timer channel 0 acts - read its A and the operands of §3 |
| 1 | timer channel 1 acts |
| 2 | timer channel 2 acts |
| 3 | timer channel 3 acts |
| 4 | bits 7-5 are meaningful this frame - apply them |
| 5 | a set bit **skips** voice A |
| 6 | a set bit skips voice B |
| 7 | a set bit skips voice C |

Channels marked in one frame act in channel order, 0 first (§7).

A voice is **skipped** when its volume register is omitted from the frame
write, for as long as a timer stream owns that register. A skip does not
silence the voice: the timer stream writes the register at its own rate.
The register's stream advances one value per frame whether read or not,
so lifting a skip requires no resynchronisation.

Bits 7-5 are state, not an edge: re-asserting the same value has no
effect. With bit 4 clear, bits 7-5 are not read and the skip state is
unchanged.

### 2.2 X - the spare operands

| bits | meaning |
|---:|---|
| 7-4 | the envelope shape a retrigger stream restarts |
| 3-0 | the channels `START_PCM_PREEMPT` stops before programming its own timer: bit `c` marks timer channel `c` (§3) |

The shape is one per frame, not one per channel: the chip has one envelope
generator, and per-channel shapes would drive it at two shapes and two
rates.

Where a source keeps its shape differs by format; the writer resolves it
into X, and a player derives nothing. A toggle stream's volume and a PCM
stream's sample number come from the voice's register stream (§3.2); a
shape has no voice to come from. A tune that arms a retrigger stream
before any shape is set carries the shape the writer chooses for the
tune's start.

### 2.3 T - the channel-to-timer map

One byte, two bits per channel:

| value | timer |
|---:|---|
| 0 | Timer A |
| 1 | Timer B |
| 2 | Timer C |
| 3 | Timer D |

Channel 0 in bits 1-0, channel 1 in bits 3-2, and so on.

Frame 0's byte defines the map at claim time: for each flagged channel
(§1.2) a player claims the timer named by the channel's two bits, and
flagged channels name distinct timers. The byte is read every frame; a
changed byte takes effect before that frame's actions (§7). A map change
stops no timer, so a writer changes the entry only of a channel with
nothing running, and only to a timer claimed at frame 0. A tune that
never re-assigns repeats the byte, which compresses to nothing. With no
channel flagged the byte binds nothing: any value is valid, and a writer
repeats one byte.

Timer C is the Atari ST's 200 Hz system clock: a tune using it stops that
clock, and cannot be driven from a Timer C interrupt.

### 2.4 A and P - the action and its count

`A` is one action byte:

```
  7 6 5    4 3   2 1 0
 +------+ +---+ +-----+
 |opcode| |vc | | low |
 +------+ +---+ +-----+
```

- **opcode** (bits 7-5) - one of the eight in §3.
- **voice** (bits 4-3) - 0, 1, 2 for voices A, B, C. Three voices in a
  two-bit field, so **3 is no voice**: only `RETUNE` carries 3, and every
  other opcode addresses voice 0, 1 or 2. See `RETUNE` in §3.
- **low** (bits 2-0) - the MFP prescaler index for an opcode that programs a
  timer, or flags for `HOLD`, `RESUME` and `RELEASE`. A programming opcode's
  index is 1 to 7; index 0 is the MFP's stopped state (§5), and no opcode
  carries it.

`P` is the MFP timer count for an opcode that programs or reloads a timer.

---

## 3. The opcodes

| # | opcode | operation | sound registers written in this frame |
|---:|---|---|---|
| 0 | `RESUME` | re-enable a released toggle stream's interrupt. Flags: 1 = reload the count, 2 = reload the volume. Phase continued through the gap | none |
| 1 | `HOLD` | update a running stream. Flags: 1 = reload the count from P, 2 = reload the toggle stream's volume, 4 = reload the retrigger stream's shape. Emitted only on frames where a value changed | none |
| 2 | `RELEASE` | stop this channel's timer. Bit 0 set: disable the interrupt instead, leaving the timer counting | none |
| 3 | `START_TOGGLE` | start a toggle stream: select, volume, vector := the loud half, then a full program | `R(8+v) := 0` |
| 4 | `RETUNE` | a new rate for a running stream, keeping its place in the cycle. See §3.1 | none |
| 5 | `START_RETRIGGER` | start a retrigger stream: shape, vector := the retrigger tick, then a full program | none |
| 6 | `START_PCM` | a trigger, fresh or repeated: sample table lookup, select, vector, full program | none |
| 7 | `START_PCM_PREEMPT` | as `START_PCM`, but first stop the timer of every channel marked in X's low nibble | none |

Every other write the operations name patches the channel's tick handler.
The first sound-register write of a stream a frame starts is its first
tick's, one timer period later.

What each opcode reads besides its action byte:

| opcode | P | X | the voice's register byte |
|---|---|---|---|
| `RESUME` | with flag 1 | - | the volume, with flag 2 |
| `HOLD` | with flag 1 | the shape, with flag 4 | the volume, with flag 2 |
| `RELEASE` | - | - | - |
| `RETUNE` to a voice | yes | - | the volume |
| `RETUNE` to voice 3 | yes | - | - |
| `START_TOGGLE` | yes | - | the volume |
| `START_RETRIGGER` | yes | the shape | - |
| `START_PCM` | yes | - | the sample number |
| `START_PCM_PREEMPT` | yes | the channels to stop | the sample number |

### 3.1 The two forms of RETUNE

`RETUNE` does not write the timer's vector: the installed tick handler
stays installed.

**Addressed to a voice (0-2)**: the volume is repatched from the voice's
register byte, then the timer is stopped, loaded and run. The period in
flight is truncated.

**Addressed to voice 3**: the **live retune**. The timer's control nibble
is replaced in a single write that never passes through zero, and the
reload is written to the data register with the timer running; the MFP
takes it at the next underflow. The period in flight completes. A toggle
stream keeps its phase and its place inside its current half.

The live form repatches no parameter, so a writer emits it only on a
frame where the stream's parameter - a toggle stream's volume, a
retrigger stream's shape - did not change. Where the parameter changed on
the same frame, the ordinary form is required, and the period in flight
is truncated.

Either form continues the code held on its channel: same kind, same
voice, a changed rate. A changed kind or voice re-enters through the
kind's start opcode. The ordinary form over a running PCM stream leaves the
sample playing from its current position at the new rate - the vector
stays installed, and the voice's byte it reads repatches the toggle
volume, which the next `START_TOGGLE` repatches again.

### 3.2 Where a stream's parameter comes from

A toggle stream's volume and a PCM stream's sample number are read from
the voice's register stream - the current frame's byte of R8+v, which the
voice's skip bit excludes from the frame write (§2.1). A retrigger
stream's shape is carried in X: it is the one envelope generator's, not a
voice's.

### 3.3 Phase

A toggle stream that stopped - released, or its voice taken by a PCM
stream - re-enters through `START_TOGGLE`, at phase zero: the player
writes the voice silent, and the first tick, one timer period later,
writes the level.

Phase is retained only across a held code and its retunes.

`RESUME` implements the alternative gap model: a release disables the
timer's interrupt, the timer keeps counting, and a re-arrival resumes the
toggle stream at its current phase. A tick that fell due while disabled
is not delivered. The model in use is the writer's choice, fixed at pack
time.

`RESUME` is emitted only where the gap was a disabling release and the
arriving code continues the same stream - same voice, same prescaler; the
opcode's low bits are flags, so it carries no rate. A prescaler changed
across the gap re-enters through the voice-addressed `RETUNE`, whose
program ends with the interrupt enabled (§9.2).

Which `RELEASE` a writer emits: a retrigger stream's release stops its
timer; a toggle stream's release stops under the default model and
disables under the resume model; a sample that reaches its end marker
takes no `RELEASE` - the marker ends it; a source that ends a sample
early takes a stopping `RELEASE`, emitted only while the sample still
plays. On a frame whose `RELEASE` stops a sample, the voice rejoins in
that same frame's write, which precedes the stop (§7): a tick between
the two writes one more sample byte over the returned volume.

---

## 4. Timer streams and the code byte

This section binds a writer. The code byte it describes does not appear in
a file: it is the vocabulary §3's opcodes were resolved from, and a player
reads the opcodes rather than the codes.

The four **kinds** of timer stream:

| bits 7-6 | kind | what it is |
|---:|---|---|
| `00` | **toggle stream** | a square wave made by flipping a voice's volume between a level and zero - a "SID voice" |
| `01` | **PCM stream** | a stored sample played out through a voice's volume register - a "digidrum" |
| `10` | **wave stream** | a table read out the same way. Reserved: a writer of this version does not emit it |
| `11` | **retrigger stream** | R13 rewritten at the timer's rate, restarting the envelope - a "sync-buzzer" |

The **code byte** does not appear in the file. It is the intermediate a
writer compiles the script from, one byte per channel per frame, specified
so that writers reading different source formats compile the same codes to
the same script - §3.1 and §3.3 state which opcode a code's arrival, change
or departure selects:

```
  7 6     5 4      3      2 1 0
 +-----+ +-----+ +---+ +-------+
 |kind | |vc+1 | | t | |prescal|
 +-----+ +-----+ +---+ +-------+
```

- **kind** - as above.
- **voice + 1** - a zero voice field means an idle channel, and a zero
  byte means nothing.
- **t** (bit 3) - unassigned; a writer may use it. A source whose
  triggers are events rather than repeated codes flips it on every
  trigger, so two triggers of one sample at one rate are two different
  bytes and the compiled script starts the sample twice. A source with no
  such signal leaves it zero, and every change of a code is then a
  trigger. Whether a PCM code held unchanged restarts its sample on the
  frames it repeats, or plays it once, is fixed at pack time, like the
  gap model of §3.3.
- **prescaler** - the MFP prescaler index, which the count byte completes.

---

## 5. Rates

A timer stream's rate is the MFP's own clock divided twice:

```
rate = 2457600 / prescaler[index] / count
```

| index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| divider | - | 4 | 10 | 16 | 50 | 64 | 100 | 200 |

The index equals the value of a delay-mode MFP timer control register; a
player programs it unchanged.

Index 0 is the MFP's stopped state, not a divider: a code with index 0
starts nothing. A count of 0 is read by the MFP as 256, the slowest count
of a prescaler.

The encodable range is 48 Hz - prescaler 200, count 0, which is 256 - to
614,400 Hz, prescaler 4 and count 1. What a writer emits is narrower: rate
ceilings are pack-time rules, not format limits.

---

## 6. The sample table

At the header's sample-table offset - a long boundary - `count` entries of
**8 bytes**:

| offset | size | field |
|---:|---:|---|
| 0 | 4 | byte offset of the sample's first byte |
| 4 | 2 | the count of level bytes - the end marker is at this offset in the sample |
| 6 | 2 | loop point, or `$FFFF` for one-shot |

Sample bytes are volume levels 0-15, followed by one **end marker**,
`$80`. A PCM tick writes a byte to the volume register, advances its
pointer, and ends the sample on a byte with bit 7 set; nothing is counted
or compared per tick. The marker has been written as a level by the time
it is tested, and `$80`'s level bits are zero, so the marker plays as one
tick of silence at the loop seam.

The **loop point** is a position in the sample, not an address. On the end
marker, a tick with a loop point other than `$FFFF` sets its pointer to
that position and continues; the seam costs the marker's one tick of
silence. A one-shot writes 13 - a mid-scale level - to the volume
register and stops its timer, so the voice holds that level until it
rejoins the frame write. `0` is a valid loop point - the sample repeats
whole - so the no-loop value is one past the largest position a sample can
hold.

A sample number is read from a voice's five-bit volume register (§3.2), so
a file may carry at most **32** samples.

**The rejoin frame.** After a one-shot the voice rejoins the frame write
(§2.1) no earlier than this many frames past the one that starts the
sample:

```
frames = ceil(((length + 1) · prescaler[index] · count · rate + 2457600/16) / 2457600)
```

The ticks are `length + 1` - the marker ticks too - each
`prescaler[index] · count` cycles of the 2457600 Hz clock; `rate` is the
tune's frame rate, and the sixteenth of a frame covers the trigger
running partway into its own frame. A count byte of 0 counts as 256 here,
as §5 has it. A skip lifted earlier puts the frame write and a tick on one
register.

---

## 7. The frame

A player is init, then one call per frame. What a call produces is the
frame's sound-register writes, in the order below, and one reported value.
A player begins frame 0 with the three skip states clear.

One player call per frame, in this order:

1. **Skips.** If M bit 4 is set, set the three skip states from bits 7-5.
2. **The frame write** - fourteen register writes, R0 through R13, each
   value the current frame's byte of its stream. Omitted: a skipped
   voice's volume register, and R13 on a frame carrying `$FF`. R7 is
   written with the host's port bits in 7-6 (§2).
3. **Actions** - for each channel marked in M, in channel order: decode
   the A byte, run the opcode. A changed T byte takes effect before the
   first action (§2.3).
4. **One refill** - decode `C` values into one stream's ring: stream `k`
   on the call whose count of calls since init, modulo `C`, equals `k`. The
   count runs on across a wrap, so the stream refilled after frame `O - 1`
   is the next in turn from the one refilled on it, for any `L`. A `k` past
   the decoded streams (§1.5) refills nothing.

Where flag bit 0 is clear, or `O - L` is at most `N`, a stream is decoded
to `O` values and refilled no further; the last refill before that is a
short one. Otherwise a section that runs out mid-refill is opened again
and the refills go on (§8).

Before frame 0, a player decodes one group of every stream, so that frame
0's values are present: `C` values, or `O` where the tune is shorter than
a group and the refills stop at `O`.

Actions follow the frame write so that their varying cost does not delay
the register writes. Skips precede it so that a voice released in a frame
has its volume written in that same frame.

A register write is two bus cycles - select, then value - and an interrupt
between them sends the value to the register the interrupt selected. A
single `movep.w` cannot be split by a 68000; masking interrupts across the
frame write is therefore optional.

Where a frame writes one register twice, the action's write follows the
frame write, and the register holds the action's value.

**What a call reports.** Each call reports one value. Where flag bit 0 is
set, the call that plays frame `O - 1` reports 1 and every other call
reports 0. Where flag bit 0 is clear, the call that plays frame `O - 1`
reports 0; the next call plays no frame, writes no register and reports
-1, and so does every call after it.

---

## 8. Starting over

A tune with flag bit 0 set plays `[0, O)` once and `[L, O)` for ever
after. `L` is 0 where the whole tune repeats.

Frames are played 0 to `O - 1`, then `L` to `O - 1` on every later pass,
and frame `f` takes each stream's `f`-th value. The two forms below reach
that sequence through a ring of `N` bytes. Neither changes which value a
frame reads.

```
      0                        L                        O
      |------------------------|------------------------|
      |      played once       |    played every pass   |
                               |<-------- O - L ------->|
```

On the frame that ends the tune, after its write, actions and refill,
every claimed timer is stopped, its vector parked on a routine with no
effect, its interrupt enabled with no tick pending, and the three skip
states cleared. That is the state frame 0 was played in on the first
pass, so every pass is identical.

Frame `L` is then reached in one of two ways, and `O - L` against `N`
selects which.

**The rings still hold the pass**, where `O - L` is at most `N`.
Refilling stops at `O` values (§7). A read position advances one byte per
frame and returns to its ring's first byte at the ring end; on the wrap
it moves back `O - L` bytes from the byte that would carry frame `O`,
then forward `N` where that lands before the ring's first byte, so it
comes to rest on frame `L`'s byte. Nothing is decoded twice.

**The streams are decoded again**, where `O - L` is larger than `N`.
Refilling continues and the wrap moves no read position.

A section that runs out mid-refill is opened again - a fresh decoder
writing into the same ring, so the ring contents stay one continuous
sequence. No section boundary need fall on a group boundary. The loop
table offset (§1.4) says which section is opened:

```
      0        the section that just ran out, so the values that follow
               are frame 0's onwards. L is 0.
      not 0    the loop table's section, and that same one every time a
               section is opened after it.
```

A loop table therefore splits each stream: the section table's section
carries the `L` values read once, the loop table's the `O - L` read on
every pass. Every first section runs out at the same value, `L`, so each
stream crosses on its own refill turn and none crosses before it has read
`L` values.

---

## 9. Conformance

### 9.1 What a player checks

- the magic is `'YMX!'`;
- the version is $0005 - 0.5;
- the stream count is 25 - it is fixed, not a size to adapt to;
- every section that is a container carries an ST4 signature matching the
  format version and unit size the player was built for - a tune packed
  for a different one is rejected, not garbled. A stored section has no
  signature to check.

Beyond that a player checks nothing - §9.3 lists the unchecked rules - and
a malformed file is undefined behaviour.

### 9.2 Interpreted data

Fourteen streams are copied: each value reaches its register unchanged.
The rest is interpreted - a byte whose meaning is an operation rather than
a value. Those operations:

| interpreted | the operation |
|---|---|
| M bit 4 with bits 7-5 | bit 5 sets voice A's skip state, bit 6 voice B's, bit 7 voice C's. On a frame with bit 4 clear they keep the value they had; nothing else changes them |
| skip bit set for voice v | no write to R8+v occurs in the frame write |
| a section offset with bit 31 set | the bytes at that offset are the section's values, one per frame: read them, do not decode them |
| R7 bits 7-6 | the value written there comes from the host. Bits 5-0 come from the stream |
| R13 = `$FF` | R13 is not written this frame |
| stream T's byte | compared each frame; a changed map is in force before the frame's actions |
| X bits 7-4 | the shape a retrigger tick writes to R13 |
| `START_RETRIGGER`, and `HOLD` with flag 4 | X bits 7-4 become that channel's retrigger shape. Neither writes R13 in the frame |
| X bits 3-0 with `START_PCM_PREEMPT` | each marked channel's timer is stopped before this channel's timer is programmed |
| `RELEASE` bit 0 clear | the timer is stopped. Set: the timer's interrupt is disabled and the timer keeps counting; a tick falling due in the gap is dropped |
| `RETUNE` addressed to voice 3 | the timer is reprogrammed without being stopped, so the count in flight runs to its end |
| a toggle stream's volume, a PCM stream's sample number | read from the voice's own register stream on the frame the stream starts, and where an opcode's flag re-reads them (§3) |
| `START_TOGGLE` | R8+v is written 0 among that frame's actions, and the first tick, one timer period later, writes the level |
| programming a timer | four writes in the order stop, vector, count, run, with no tick of that timer between the first and the last, ending with that interrupt enabled and unmasked. This document names no register and no address: which they are is the host's, and the order is what a player owes a stream |
| a sample byte with bit 7 set | it is written to the volume register as a level, and the sample ends there |
| loop point `$FFFF` | on the marker, 13 is written to the volume register and the timer is stopped. Any other value: the read position becomes that offset into the sample and the ticks continue |
| the frame's order | skip bits, then the fourteen register writes, then the frame's actions in channel order, then one refill |
| the frame after the last, flag bit 0 set | every claimed timer is stopped, its vector parked, its interrupt enabled with nothing pending, and every skip bit cleared, before frame `L` is played again |
| refill turn | on frame `f`, stream `f` modulo `C` is decoded `C` values further |
| a section running out mid-refill | decoding continues into the same ring, from the start of a section |
| a loop table offset that is not 0 | the section opened there, and at every later one, is the loop table's for that stream; with 0 it is the same section again |

### 9.3 The unchecked rules

A player does not have to check these rules; a file that breaks one is
undefined behaviour (§9.1). Collected from the sections that define them:

The shape:

- `O` is at least 1; `N` and `C` are within §1.3. At a unit size above 1,
  `O` and `C` are multiples of the unit size.
- Where `L` is not 0, `L` is less than `O`.
- Where the loop table offset is 0, each of the twenty-five sections
  decodes to `O` values, and `L` is 0 or leaves `O` - `L` at most `N`, so
  a wrap reaches back one pass into the rings and no further (§8).
- Where the loop table offset is not 0 it is a long boundary, `L` is not
  0, and the loop table's twenty-five entries are nonzero, `O` - `L` is
  larger than `N`, so §8's second form is the one in force: each section
  table's section decodes to `L` values and each loop table's to `O` -
  `L`. At a unit size above 1, `L` is a multiple of the unit size.
- Every section decodes to values of one byte. No back-reference exceeds
  `N` bytes and no operation exceeds 65535 units (§1.4).
- The sample table is within §6: at most 32 samples, the `$80` marker at
  each sample's length offset, each loop point less than its sample's
  length or `$FFFF`, the table on a long boundary.

The values:

- M marks only channels flagged in §1.2.
- A register value contains only the bits of its mask (§2). R13 carries
  `$FF` on every frame that must not restart the envelope. R7 carries
  bits 5-0, with every generator masked off a voice for as long as a PCM
  stream owns it.
- A voice's skip state is set from the frame a timer stream takes its
  volume register through the frame before the voice rejoins the frame
  write - the rejoin frame's M clears it - and every frame that changes
  any skip bit sets M's bit 4. After a one-shot sample the rejoin is
  bounded by §6.
- On a frame that starts a PCM or toggle stream, on a frame whose
  voice-addressed `RETUNE` retunes a toggle stream, and on any frame an
  opcode's flag re-reads the parameter, the voice's register byte carries
  the operand defined in §3.

The actions:

- At most one timer stream runs on a voice at a time; where a source
  starts two, the conflict is resolved at pack time.
- A programming opcode's prescaler index is 1 to 7 (§2.4); its count byte
  may be 0, read by the MFP as 256 (§5).
- `RETUNE` to voice 3 is emitted only where the stream's parameter did
  not change that frame (§3.1).
- A `RETUNE` keeps the stream's kind and voice; a changed kind or voice
  re-enters through a start opcode (§3.1).
- `RESUME` is emitted only after a disabling release, for the same
  stream, voice and prescaler (§3.3).
- A `HOLD` sets at most one of flags 2 and 4: a channel runs one stream
  kind.
- A sample cut off by its own channel's next start never reaches its
  marker: no tick writes its voice again, and the voice rejoins only
  where a later frame clears its skip.
- `START_PCM_PREEMPT`'s X nibble marks exactly the channels with a
  running timer that the trigger silences; with none, `START_PCM` is the
  encoding.
- Stream T is within §2.3: flagged channels name distinct timers at frame
  0; a change moves only channels with nothing running, among the timers
  claimed at frame 0.

---

## Appendix A. The ST4 container

A packed section is one ST4 container. This appendix states it in full, so
a reader implements this document without a second one. The format is
ST4's; version 4 is the version stated here.

### A.1 The header, twenty bytes

| offset | size | field |
|---:|---:|---|
| 0 | 4 | signature `$53 $34 $04 k`: `'S'`, `'4'`, format version 4, unit size `k` |
| 4 | 4 | output size in bytes, a multiple of `k` |
| 8 | 4 | where stream B starts, in bytes from the header's first byte |
| 12 | 4 | where stream C starts |
| 16 | 4 | where stream D starts |

Stream A begins at offset 20. The three offsets locate the others, so a
stream's length is the distance to the next one and stream A is padded to
an even length.

### A.2 The four streams

| stream | contents |
|---|---|
| A | the bits: flags, class bits and lengths |
| B | the literal data, whole units |
| C | byte offsets, one byte each |
| D | word offsets, one word each |

Bits are read from stream A most significant first. A length is an
interlaced Elias gamma value: each binary digit of the value below its
leading 1 is written after a `1` marker bit, most significant first, and a
`0` bit ends the value. So 1 is `0`, 2 is `100`, 3 is `110`, 4 is `10100`.

### A.3 The blocks

| block | encoding |
|---|---|
| literals | `gamma(length)`, then the next `length` units of stream B |
| match at the last offset | `gamma(length)`, then `length` units copied from the current offset |
| match at a new offset | two class bits, one value from C or D, then `gamma(length - 1)` |

The first block is literals and carries no flag bit. After literals, a `0`
bit starts a match at the last offset and a `1` a match at a new offset, so
two literal runs never follow each other. After a match, a `0` starts
literals and a `1` a match at a new offset.

**The last offset begins at 1.** A decoder that has read no new offset yet
copies from one unit back, so a first block of literals may be followed
immediately by a match at the last offset.

### A.4 New offsets

| class bits | meaning |
|---|---|
| `1 0` | byte offset from stream C, 1 to 256 units back |
| `1 1` | byte offset from stream C, 257 to 512 units back |
| `0 0` | word offset from stream D |
| `0 1` | end of the stream |

A byte offset of `n` units in bank `b` is stored as the byte
`256·(b + 1) - n`, where `b` is 0 for class `1 0` and 1 for class `1 1`. A
word offset of `n` units is stored big-endian as `65536 - n·k`, which is
`-n·k` as a decoder keeps it.

A decoder stops on the end-of-stream class, having written the header's
output size in bytes. No offset reaches further back than 32512 bytes at
any `k`, and a match at a new offset is at least 2 units long.

---

## Appendix B. A worked file

`ym/test/Circus Attractions  2.ymx`, 240 bytes, four frames, every section
stored. Read against §1 and §7 it settles the header's fields, where the
body begins, how a stored section is read, and what a call reports.

### B.1 The header

```
   0  59 4D 58 21   'YMX!'
   4  00 05         format version 0.5
   6  00 01         flags: bit 0 set, the tune starts over
   8  00 00 00 04   O = 4 frames
  12  00 32         50 Hz
  16  03 C0         N = 960
  18  00 18         C = 24
  20  00 1E 84 80   master clock 2000000
  24  00 00 00 00   no sample table
  28  00 00         reserved
  30  00 00 00 00   L = 0, the tune starts over from its first frame
  34  00 00 00 00   no loop table, so each stream has one section
  38  80 00 00 8C   stream 0: bit 31 set, stored, at offset 140
  42  80 00 00 90   stream 1: stored, at offset 144
  46  80 00 00 94   stream 2: stored, at offset 148
       ...
 134  80 00 00 C0   stream 24: stored, at offset 192
```

The header ends at byte 137. Bytes 138 and 139 are padding to the next
long boundary, so the first body item is at 140, as §1.1 has it.

### B.2 Two sections

Every entry has bit 31 set, so every section is stored: the bytes at the
offset are the values, one per frame, with no container and no signature.
Four frames, so four bytes each.

```
 140  A3 8E FB 59   stream 0, R0: 163, 142, 251, 89
 144  02 0C 04 02   stream 1, R1: 2, 12, 4, 2
 192  FF FF FF FF   stream 13, R13: $FF on every frame
```

A file of stored sections reads the same at any unit size, so this one
plays on a player built for any of the three.

### B.3 What a player produces

The four frames, then the fifth call. R13 carries `$FF` on every frame, so
no frame writes it (§7 step 2), and streams 14 to 24 are script data whose
bytes never reach the chip.

| call | reports | writes |
|---:|---:|---|
| 0 | 0 | R0=163 R1=2 R2=238 R3=14 R4=238 R5=14 R6=12 R7=248 R8=15 R9=0 R10=0 R11=0 R12=0 |
| 1 | 0 | R0=142 R1=12 R2=238 R3=14 R4=238 R5=14 R6=28 R7=241 R8=12 R9=0 R10=0 R11=0 R12=0 |
| 2 | 0 | R0=251 R1=4 R2=238 R3=14 R4=238 R5=14 R6=12 R7=241 R8=13 R9=0 R10=0 R11=0 R12=0 |
| 3 | 1 | R0=89 R1=2 R2=238 R3=14 R4=238 R5=14 R6=12 R7=248 R8=15 R9=0 R10=0 R11=0 R12=0 |
| 4 | 0 | the frame 0 row again |

Call 3 plays frame `O - 1` and reports 1, the tune having gone round; call
4 plays frame 0 again, because `L` is 0. R7's values carry the host's port
bits: the stream's byte for frame 0 is `$38`, and `$38 | $C0` is 248.
