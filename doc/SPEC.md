# The YMX format - specification

Version 0.9. Big-endian throughout.

YMX is a streaming register-dump format for the YM2149 sound chip in the
Atari ST, playable by a 68000 without the tune resident in memory. A file
carries twenty-five independently compressed streams: fourteen register
streams, one value per frame, and eleven streams of a **compiled effect
script** that drives the MFP's timers. A file may carry extension streams
past those, to a stored count of at most 32; §1.5 separates the stored,
decoded and changing counts. Each stream is decoded through a ring of `N`
bytes, one stream refilled per frame, so memory use depends on the player
configuration, not on tune length.

The YM formats - YM4 to YM6 - store "special effects" as values the player
re-derives every frame from spare register bits. YMX resolves them at pack
time and stores the outcome; a player compares nothing at run time.

Three roles read or write the format, and one more word covers the two
that read:

| | what it does | what binds it |
|---|---|---|
| **writer** | a packer, or a tracker emitting the format directly | every rule here; §9.3 lists the ones a player does not check |
| **player** | drives a sound chip from the file, frame by frame | §1, §2, §6, performs §7, §8 and §9.2, checks §9.1 |
| **reader** | produces the values a frame writes, and drives nothing | §1, §2, §7 steps 1 to 3, §8's frame sequence, checks §9.1 (§9.4) |
| **consumer** | a player or a reader - what reads a file rather than writing one | §1.6, §9.1 |

A player and a reader differ in what they produce, not in which streams
they read: each reads every stream of §2, and each reads the extension
streams it implements. §9.4 says what a reader leaves unread and why.

The format began as the `.yx6` container of the [ST4](https://github.com/odipar/ST4)
repository, renumbered; the layout has changed since, and this document
defines the current layout. A file states its version and a player checks
it (§9.1), so a player built for one version reads no file of another; a
tune packed at an older one is repacked from its source (§10.5).

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
| sections, located by offset    |
| sample table + sample bytes    |
+--------------------------------+
```

### 1.1 Header

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `'YMX!'` - `$594D5821` |
| 4 | 2 | format version, the major byte then the minor - **$0009**, version 0.9 |
| 6 | 2 | flags (§1.2) |
| 8 | 4 | `O`, the frame count |
| 12 | 2 | frame rate in Hz: how often the player is called |
| 14 | 2 | `S`, the stream count - **25** to **32**, see §1.5 |
| 16 | 2 | `N`, the ring size in bytes |
| 18 | 2 | `C`, values decoded per call |
| 20 | 4 | the YM2149's master clock in Hz - informational; **2000000** for an ST tune, 0 with no source clock |
| 24 | 4 | byte offset of the sample table; 0 when there are none |
| 28 | 2 | sample count |
| 30 | 4 | `L`, the frame a tune that starts over goes back to (§8), within the bounds §9.3 gives it; 0 where the tune plays once through, and 0 where it starts over from its first frame |
| 34 | 4 | `Q`, the **required-streams mask** (§1.6): bit `k` for stream `k` |
| 38 | 4·S | byte offset of each **section** (§1.4) |

The fixed fields occupy bytes 0 to 37 and the section table bytes 38 to
`37 + 4·S`, so the header is **138 bytes** where `S` is 25 and 166 where
`S` is 32. `38 + 4·S` is two short of a long boundary at every `S`, so
two pad bytes follow the header and the first body item is at offset
140, or 168 where `S` is 32. Everything after the header is body: the
sections (§1.4), then the sample table (§6). Content in the body is
located only by offset - a section by its table entry, the sample table
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
| 5 | a section copies from its literal stream (§1.4, Appendix A.5): the file plays only on a player built for its ring as a window (§9.1) |
| 6-15 | reserved, written as 0 |

A player claims an MFP timer only for channels whose flags are set; other
timers are not touched. Which timer a flagged channel gets is defined by
stream T (§2.3).

### 1.3 `N` and `C`

`N` is the ring size in bytes, one ring per stream, and the maximum
back-reference distance in every section. `N` is capped at **2520**: a
player may read register `k`'s ring through an assembled-in displacement of
`k·N`, and `13·N` must fit a signed 16-bit displacement. The cap is the
register streams'. Stream 14's ring is 35280 bytes from stream 0's at the
cap, past what a signed 16-bit displacement reaches, so streams 14 and
above are read through a computed address and place no bound of their own
on `N`.

`C` is the number of values decoded per refill call. Constraints:

- `C` is at least the length of the longest decode list the file admits:
  the base count of §1.5, and one more for each extension stream the file
  carries. A consumer that implements every extension the file carries
  holds that list, and a `C` that covers it covers every shorter one;
- `C` divides `N`;
- `N` is at least `2C`.

At a unit size above 1 (§1.4), `C` and `O` are multiples of the unit size.
Where a tune's own length falls short of a multiple, a writer raises `O`
by duplicating a frame near the end, the same frame in every stream, and
`L` moves with the frame it points at. The added frames are played like
any other, so the last of them is frame `O - 1`. Nothing marks an added
frame, so a reader reads `O` frames and no fewer.

960/24 is the default pair, valid for every tune that leaves channel 3
idle. A `C` of 24 covers every base count up to 23, and the difference is
room for extension streams: seven of them where no channel is flagged
(base count 17), five where channel 0 is the highest in use, three for
channel 1, one for channel 2. A longer decode list needs a larger `C`,
and `N` moves with it, because `C` divides `N`: 960 does not divide by
26, so a writer picks an `N` its `C` divides. 2520 is `2³·3²·5·7`, which
serves a `C` of 28 and of 30 but not of 32; 2496 is the largest ring a
`C` of 32 divides.

### 1.4 The sections

A section carries one stream's values, packed or stored. `O` is at least
1, section-table entries 0 to 24 are nonzero, and an entry from 25 up is
nonzero where the file carries that stream and 0 where it does not (§1.5).
Section order in the file is not significant; a section is located only by
its offset.

**One section per stream.** Each stream has one section, covering frames
`[0, O)`. Where a pass is longer than a ring (§8), every packed section
carries `L` in bytes as its rewind point (Appendix A.1), and a value is
one byte, so the rewind point is frame `L` in every stream; a stored
section carries no header and takes its form from the file's. A section
that rewinds has its frames from `L` packed apart from the frames before
`L`: no match in `[L, O)` reaches before `L` (§9.3), so a pass after the
first reads the history the first one did.

**Packed sections.** A packed section is a complete ST4 container - a
twenty-eight-byte header whose first long is the signature, then four
streams. Appendix A states the container and its bitstream in full. The
format comes from the [ST4](https://github.com/odipar/ST4) repository,
and an implementation follows the appendix rather than that repository.
A writer with no ST4 compressor stores every section instead (below) and
emits no container. A section of this version ends rather than repeats,
carries a rewind point only as §8 gives it, and records the window
`N/k`. An offset past the window is a copy from the literal stream
(Appendix A.5): a writer emits one only with flag bit 5 set, and a file
with the bit set plays only on a player built for `N/k` as its window
(§9.1). What a container may carry beyond that, §9.3 rules out.

**Two fixed parameters.** No back-reference exceeds `N` bytes (§1.3),
and no operation exceeds 65535 units.

**Unit size.** Every section in a file is packed at one unit size - 1, 2
or 4 bytes, recorded with the ST4 format version in the section's
signature, not in the YMX header. A packed section's first long is
`$53 $34 $07 k`: `'S'`, `'4'`, ST4 format version 7, and the unit size
`k`, one of 1, 2 or 4. Version 7 is the version this document defines. A
player built for one unit size rejects a container whose fourth byte
differs; a player that decodes all three rejects a file whose sections do
not share one unit size (§9.1). The unit size binds the packing and not
the values: a section's decoded bytes are its stream's values in frame
order at every unit size, as a stored section's bytes are.

**Stored sections.** A section may instead be stored: the bytes at its
offset are the values, one per frame, with no header and no signature.
Bit 31 of an entry set marks a stored section, in either table; bits 30-0
are the offset. A container's header is twenty-eight bytes, so a section
shorter than that is smaller stored (a one-frame tune stores one byte per
stream). A stored section is read the same at any unit size; a
file with only stored sections plays on any build.

**Alignment.** Every section, packed or stored, begins on a long
boundary, so a player may read a container's header a long at a time.
Bytes between one part and the next long boundary are padding: nothing
reads them, and their value is free.

**Starting over.** Where a tune with flag bit 0 set decodes past the end
of a section - which happens only where `O - L` is larger than `N` (§8) -
the player replays it: from the state it saved when the stream had
produced `L` values where `L` is not 0, and by opening the section again
from its start where `L` is 0. Where `O - L` is at most `N`, refilling
stops at `O` values and no section is decoded twice.

A section's length is not stored in the section: a packed section's output
size is in the container's header, and a stored section runs one byte per
frame. Either way the count is `O`.

### 1.5 Three stream counts

`S` is the stored count: 25 where the file carries no extension stream, and
one past the highest extension index it carries otherwise. A consumer
rejects an `S` below 25 or above 32 (§9.1). The section table is `4·S`
bytes, 100 of them where `S` is 25.

Streams 0 to 24 are the streams §2 defines. Streams 25 to 31 are
**extension streams** (§1.6). A file **carries** a stream where its
section-table entry is nonzero: entries 0 to 24 are nonzero in every file,
and an entry from 25 to `S - 1` is 0 where the file does not carry that
stream.

Three counts apply:

| | |
|---|---|
| **stored** - `S`, 25 to 32 | the size of the section table |
| **decoded** - 17, 19, 21, 23 or 25 | the base count a consumer reads per cycle: 17 where no channel is flagged, and `17 + 2(h + 1)` where `h` is the **highest** flagged channel. Each extension stream a consumer reads adds one |
| **changing** - fewer still | how many of those hold a value that moves: an idle channel's pair is one value repeated, and compresses to almost nothing |

The base count steps by the highest channel in use, not by the number in
use: a consumer reads stream 0 through the highest used channel's pair and
stops. A tune using only channel 3 has a base count of 25. The channel
pairs are last in base stream order, so idle channels lie past the base
count and cost nothing.

A consumer's **decode list** is streams 0 to the base count less one, in
that order, then the extension streams it implements and the file carries,
in ascending index order. §7 step 4 gives each position in the list its
refill turn. The list is not a prefix of stream order: a consumer that
implements the extension at index 30 reads streams 0 to 16 and stream 30,
and nothing between. Every extension index is above every base index, so a
base stream holds the same position in every consumer's list, and two
consumers of one file refill the base streams on the same calls.

### 1.6 The required-streams mask and the extension streams

Streams 25 to 31 are **extension streams**. §1.7's registry gives each
index its meaning. No field in the file names a stream: the index is the
name, and a stream carries no tag, no length and no type. A carried
extension stream is read as a base stream is: one section, decoding to
the count §1.4 gives every other stream, and delivering one value per
frame through a ring of `N` bytes.

`Q`, the long at offset 34, carries one bit per stream, bit `k` for stream
`k`. A set bit **requires** the stream: a consumer that does not implement
it rejects the file (§9.1). A clear bit on a stream the file carries makes
the stream **advisory**: a consumer that does not implement it reads none
of it, gives it no position in its decode list, and produces the values it
would produce from a file without it.

Bits 0 to 24 are set in every file. Bits for streams the file does not
carry are clear. `Q` is therefore `$01FFFFFF` in a file carrying no
extension stream, and a consumer that implements no extension accepts
exactly the files whose `Q` holds that value.

Thirty-two is the stream ceiling at this version and at every later one,
because `Q` is one long with one bit per stream, and `S` never exceeds it.

**What an advisory stream leaves alone.** Where a stream's mask bit is
clear, a consumer that reads the stream and a consumer that does not
produce the same register writes, the same actions and the same reported
value on every frame (§7). A stream that changes any of the three carries a
set bit, and a consumer that does not implement it rejects the file rather
than playing it wrong.

A consumer checks none of this: nothing in a file separates an advisory
stream that holds to the rule from one that breaks it. The rule binds a
writer (§9.3), and a file that breaks it produces two sets of values from
two conformant consumers, with nothing in the format to say which is right.

**What an advisory stream costs.** It raises the length `C` covers by one
(§1.3), so every consumer of the file decodes one more value on a refill
call and holds two more bytes of ring at the minimum, including a consumer
that reads none of the stream. The values a frame writes are unchanged.

**An extension stream's ring.** It is not a register's, so §1.3's
assembled-in displacement does not reach it, and a consumer reaches it as
it reaches M, X, T and the channel pairs.

### 1.7 The extension registry

| index | this version |
|---:|---|
| 25 | reserved |
| 26 | reserved |
| 27 | reserved |
| 28 | reserved |
| 29 | reserved |
| 30 | custom |
| 31 | custom |

This version assigns no index, and a file of this version carries no stream
at a reserved index.

A **reserved** index is this document's to assign. A later version assigns
one by filling its row: the name, what one value carries, whether the mask
bit is ever clear, and what a consumer that implements it produces.
Assigning an index does not change the format version. A consumer built
before the assignment rejects a file that requires the index, and reads
none of a file that leaves it advisory.

A **custom** index carries what a writer and its own tools agree it
carries. This document assigns nothing from 30 or 31 at any version, so a
custom stream never collides with a registered one. Two custom streams at
one index are one stream to the format, and no field in the file separates
them: a consumer that implements its own index 30 reads another writer's
index 30 as its own. A writer whose custom stream would change what plays
sets its bit, so a consumer that implements nothing at that index rejects
the file instead. A consumer that implements a different stream at that
index misreads it, and nothing in the file prevents that.

An extension that turns out to be shared is registered from 25 to 29 in a
later version, and a writer moves it there from its custom index.

---

## 2. The streams

Twenty-five slots, in this order. Streams 0-13 are frame streams; 14-24
are script data whose bytes never reach a chip register. Streams 25 to 31,
where a file carries them, are extension streams (§1.6) and are not in this
table.

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

**Read schedule.** Every stream carries one value per frame: frame `f`
takes each stream's `f`-th value, read or not. M, T and the fourteen
register streams are read every frame. Every other stream byte is read
only as specified: an A byte on a frame whose M bit marks the channel, a
P or X byte where the opcode reads one (§3). On other frames the byte's
value is unspecified: any byte is valid there - a stream's bytes before
its first read included - and repeating the previous byte compresses to
nothing.

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
taken from the file would drive the floppy selects with tune data. A reader
with no chip to drive uses the Atari ST value. Bits 5-0 arrive with every
sample-playing voice **disconnected** - no generator mixed into the voice -
applied at pack time (§9.3).

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
Lifting a skip requires no resynchronisation.

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
before any shape is set carries the shape the writer gives the tune's
start.

### 2.3 T - the channel-to-timer map

One byte, two bits per channel:

| value | timer |
|---:|---|
| 0 | Timer A |
| 1 | Timer B |
| 2 | Timer C |
| 3 | Timer D |

Channel 0 in bits 1-0, channel 1 in bits 3-2, and so on.

A stream's ticks name the timer the map gave its channel when that
stream's timer was last programmed, not the timer the current byte gives
it. A channel whose timer counts with its interrupt disabled (§3.3) is
running, so §9.3's rule that a change reaches only a channel with nothing
running holds over a disabling release as well as over a playing stream.

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
  two-bit field, so **3 is no voice**, and three opcodes take it as the
  discriminator of a second form: `RETUNE` (§3.1), `START_RETRIGGER` (§3.4)
  and `RESUME` (§3.5). `RELEASE` stops a channel's timer and names no voice,
  and a writer writes its field as 0; every other opcode addresses voice 0,
  1 or 2, and a value outside what an opcode defines is a malformed file
  (§9.1).
- **low** (bits 2-0) - the MFP prescaler index for an opcode that programs a
  timer, or flags for `HOLD`, `RELEASE` and a `RESUME` that names a voice.
  A programming opcode's index is 1 to 7; index 0 is the MFP's stopped state
  (§5), and no opcode carries it. `RESUME` at voice 3 programs, so its low
  bits are an index rather than flags (§3.5).

`P` is the MFP timer count for an opcode that programs or reloads a timer.

---

## 3. The opcodes

| # | opcode | operation | sound registers written in this frame |
|---:|---|---|---|
| 0 | `RESUME` | re-enable a released toggle stream's interrupt. Flags: 1 = reload the count, 2 = reload the volume. Phase continued through the gap. At voice 3 a rate instead, and the timer is programmed (§3.5) | none |
| 1 | `HOLD` | update a running stream. Flags: 1 = reload the count from P, 2 = reload the toggle stream's volume, 4 = reload the retrigger stream's shape. Emitted only on frames where a value changed | none |
| 2 | `RELEASE` | stop this channel's timer. Bit 0 set: disable the interrupt instead, leaving the timer counting | none |
| 3 | `START_TOGGLE` | start a toggle stream: select, volume, vector := the loud half, then a full program | `R(8+v) := 0`, a skipped voice included |
| 4 | `RETUNE` | a new rate for a running stream, keeping its place in the cycle. See §3.1 | none |
| 5 | `START_RETRIGGER` | start a retrigger stream: shape, vector := the retrigger tick, then a full program. At voice 3 the shape and a new rate over the stream already running, and no vector (§3.4) | none |
| 6 | `START_PCM` | a trigger, fresh or repeated: sample table lookup, select, vector, full program. The read position becomes the sample's first byte, so its first tick writes byte 0 | none |
| 7 | `START_PCM_PREEMPT` | as `START_PCM`, but first stop the timer of every channel marked in X's low nibble | none |

Every other write the operations name patches the channel's tick handler.
The first sound-register write of a stream a frame starts is its first
tick's, one timer period later.

What each opcode reads besides its action byte:

| opcode | P | X | the voice's register byte |
|---|---|---|---|
| `RESUME` to a voice | with flag 1 | - | the volume, with flag 2 |
| `RESUME` to voice 3 | yes | - | the volume |
| `HOLD` | with flag 1 | the shape, with flag 4 | the volume, with flag 2 |
| `RELEASE` | - | - | - |
| `RETUNE` to a voice | yes | - | the volume |
| `RETUNE` to voice 3 | yes | - | - |
| `START_TOGGLE` | yes | - | the volume |
| `START_RETRIGGER` to a voice | yes | the shape | - |
| `START_RETRIGGER` to voice 3 | yes | the shape | - |
| `START_PCM` | yes | - | the sample number |
| `START_PCM_PREEMPT` | yes | the channels to stop | the sample number |

### 3.1 The two forms of RETUNE

`RETUNE` does not write the timer's vector: the installed tick handler
stays installed. Neither form stops the timer. The control nibble is
replaced in a single write that never passes through zero, and the reload
is written to the data register with the timer running; the MFP takes it
at the next underflow. The period in flight completes, and a toggle stream
keeps its phase and its place inside its current half.

**Addressed to a voice (0-2)**: the volume is repatched from the voice's
register byte first.

**Addressed to voice 3**: nothing is repatched, so a writer emits this
form only on a frame where the stream's parameter - a toggle stream's
volume, a retrigger stream's shape - did not change.

Both forms need the timer already running. The control register is written
before the data register, so on a stopped timer the first period would run
at the previous count.

Either form continues the code held on its channel: same kind, same
voice, a changed rate. A changed kind or voice re-enters through the
kind's start opcode. Either form over a running PCM stream leaves the
sample playing from its current position at the new rate - the vector
stays installed, and the voice's byte the voiced form reads repatches the
toggle volume, which the next `START_TOGGLE` repatches again.

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

`HOLD` and `RESUME` read the register byte of the voice their channel's
stream runs on, which is the voice their own action byte names: a channel
carries one voice between one start opcode and the next (§9.3).

`RESUME` implements the alternative gap model: a release disables the
timer's interrupt, the timer keeps counting, and a re-arrival resumes the
toggle stream at its current phase. At voice 3 it programs the timer
(§3.5), so it keeps the half the release left standing but not the place
inside it. A tick that fell due while disabled is not delivered: no
interrupt is taken and the tick's handler does not run, so it writes no
register, reads no sample byte, and leaves a toggle stream on the half the
release left standing. The count runs on under it, and no tick is
delivered late: the first underflow after `RESUME` lands one. The model in
use is the writer's choice, fixed at pack time.

`RESUME` is emitted only where the gap was a disabling release and the
arriving code continues the same stream and the same voice. Addressed to a
voice its low bits are flags, so it carries no rate. A channel keeps the
prescaler index of the last opcode that programmed its timer, and that is
the index `RESUME`'s flag 1 reloads against, as it is the one `HOLD`'s
flag 1 reloads against. A prescaler changed across the gap re-enters
through `RESUME` at voice 3, which programs the timer at the index it
carries and ends with the interrupt enabled (§3.5, §9.2).

### 3.4 The retrigger retune

`START_RETRIGGER` addressed to voice 3 is a retrigger stream's shape and
its rate moving on one frame. The shape is taken from X and patched into
the tick, and the timer is reprogrammed the way §3.1 states: no stop, no
vector, the period in flight completing.

The voice field is the discriminator, and a retrigger stream needs no
voice of its own: its shape is the one envelope generator's, not a
voice's (§3.2). The form continues the stream on its channel, so a
changed kind re-enters through a start opcode as elsewhere.

Without it a shape and a rate moving together take the full
`START_RETRIGGER`, whose program stops the timer and truncates the period
in flight.

### 3.5 RESUME with a rate

`RESUME` addressed to voice 3 re-enters a released toggle stream at a
prescaler the gap changed. Its low bits are that index rather than flags,
`P` is the count, and the volume is read from the voice's register byte
with no flag to ask for it.

The timer is programmed - stopped, loaded, run - rather than reloaded with
the timer running. After a gap the counter holds an arbitrary count, so a
live reload would defer the new rate by up to one period of the old rate,
where a program lands it on the first tick after the re-arrival. No vector
is written, so the half the release left standing is the half that
returns.

The form names no voice. A channel carries one voice between one start
opcode and the next (§9.3), and that is the voice whose register byte the
volume comes from.

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

The **code byte** is one byte per channel per frame, specified
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
or compared per tick. The byte reaches the register unchanged, the marker
included: `$80` is written as `$80`, and the chip takes its low five bits,
which are zero. So the marker has been written as a level by the time it
is tested, and it plays as one tick of silence at the loop seam.

The **loop point** is a position in the sample, not an address. On the end
marker, a tick with a loop point other than `$FFFF` sets its pointer to
that position and continues. A one-shot writes 13 - a mid-scale level -
to the volume register and stops its timer, so the voice holds that level
until it rejoins the frame write. Both writes are the marker tick's own:
it writes the marker byte, then 13, and the timer stops after the second.
That tick is the one place in the format where a tick writes one register
twice. `0` is a valid loop point - the sample repeats whole - so the
no-loop value is one past the largest position a sample can hold.

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
   the A byte, run the opcode. `START_TOGGLE` writes `R(8+v)` here, a
   skipped voice included, and the other seven opcodes write no sound
   register. A changed T byte takes effect before the first action
   (§2.3).
4. **One refill** - decode `C` values into one stream's ring: the stream
   at position `k` of the consumer's decode list (§1.5) on the call whose
   count of calls since init, modulo `C`, equals `k`. The count runs on
   across a wrap, so the stream refilled after frame `O - 1` is the next in
   turn from the one refilled on it, for any `L`. A `k` past the end of the
   list refills nothing. Where the consumer reads no extension stream,
   position `k` is stream `k`.

Where flag bit 0 is clear, or `O - L` is at most `N`, a stream is decoded
to `O` values and refilled no further; the last refill before that is a
short one. Otherwise a section that runs out mid-refill is opened again
and the refills go on (§8).

Before frame 0, a player decodes one group of every stream in its decode
list, so that frame 0's values are present: `C` values, or `O` where the
tune is shorter than a group and the refills stop at `O`.

Actions follow the frame write so that their varying cost does not delay
the register writes. Skips precede it so that a voice released in a frame
has its volume written in that same frame.

A register write is two bus cycles - select, then value - and an interrupt
between them sends the value to the register the interrupt selected. A
single `movep.w` cannot be split by a 68000; masking interrupts across the
frame write is therefore optional.

Where a frame writes one register twice, the action's write follows the
frame write, and the register holds the action's value.

**What a call reports.** Each call reports one value. A call that plays a
frame before `O - 1` reports 0. Every call that plays frame `O - 1`
reports 1 where flag bit 0 is set, and 0 where it is clear, on the first
pass and on every later one. Where flag bit 0 is clear, the next call
plays no frame, writes no register, lands no tick and reports -1. The run
has ended at that call: every later call repeats the report, and a record
of the run ends with that call's entry.

---

## 8. Starting over

A tune with flag bit 0 set plays `[0, O)` once and `[L, O)` for ever
after. `L` is 0 where the whole tune repeats.

Frames are played 0 to `O - 1`, then `L` to `O - 1` on every later pass,
and frame `f` takes each stream's `f`-th value. `L` may be `O - 1`, and
every later pass is then one frame long. The two forms below reach
that sequence through a ring of `N` bytes. Neither changes which value a
frame reads.

```
      0                        L                        O
      |------------------------|------------------------|
      |      played once       |    played every pass   |
                               |<-------- O - L ------->|
```

On the frame that ends the tune, after its write, actions and refill, and
as the last thing that call does, every claimed timer is stopped, its
vector parked on a routine with no effect, its interrupt enabled with no
tick pending, and the three skip states cleared. That is the state frame 0
was played in on the first pass, so every pass is identical, and that call
lands no tick of its own: every timer is stopped before the next one would
fall due.

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

Where `L` is 0, a section that runs out mid-refill is opened again - a
fresh decoder writing into the same ring, so the ring contents stay one
continuous sequence - and the values that follow are frame 0's onwards.

Where `L` is not 0, the section is not opened again: every stream's
container carries `L` as its rewind point (§1.4), and the player replays
it. When a stream's decoder has produced `L` values the player saves the
decoder's state - the bit position and queue, the three stream positions,
the offset and the count in flight - and when it has produced `O` values
it restores all of that but the write position, which stays where the
ring has got to, and reads on: `O - L` values to the next restore, every
pass. The frames from `L` were packed on their own, so the state restored
at `O` decodes them as it did the first time. No boundary need fall on a
group boundary. Every stream reaches `L` values at the same value, so
each saves on its own refill turn and none saves before it has read `L`
values.

---

## 9. Conformance

### 9.1 What a player checks

- the magic is `'YMX!'`;
- the version is $0009 - 0.9;
- the stream count `S` is 25 to 32;
- the required-streams mask names no stream the consumer does not
  implement: `Q AND NOT implemented` is zero, where bit `k` of
  `implemented` is set for each stream `k` the consumer implements. Every
  consumer of this version implements streams 0 to 24; a consumer that
  carries no extension stream implements those twenty-five, and testing
  `Q` against `$01FFFFFF` is that check for it;
- every section that is a container carries an ST4 signature matching the
  format version and unit size the player was built for - a tune packed
  for a different one is rejected, not garbled. A stored section has no
  signature to check;
- a player built with no window rejects a file with flag bit 5 set: it
  has no copy code, and would decode a copy to other bytes. A player
  built for a window of `W` units rejects a file whose `N` is larger than
  `W · k`, since it reads an offset past `W` as a copy, and rejects a file
  with flag bit 5 set whose `N` is not `W · k` exactly.

Beyond that a player checks nothing - §9.3 lists the unchecked rules - and
a malformed file is undefined behaviour.

### 9.2 Interpreted data

Fourteen streams are copied: each value reaches its register unchanged.
The rest is interpreted - a byte whose meaning is an operation rather than
a value. Those operations:

| interpreted | the operation |
|---|---|
| M bit 4 set, with bits 7-5 | bit 5 sets voice A's skip state, bit 6 voice B's, bit 7 voice C's |
| M bit 4 clear | the three skip states keep the value they had; nothing else changes them |
| skip bit set for voice v | no write to R8+v occurs in the frame write |
| a section offset with bit 31 set | the bytes at that offset are the section's values, one per frame: read them, do not decode them |
| R7 bits 7-6 | the value written there comes from the host |
| R7 bits 5-0 | the value written there comes from the stream |
| R13 = `$FF` | R13 is not written this frame |
| stream T's byte | compared each frame; a changed map is in force before the frame's actions |
| X bits 7-4 | the shape a retrigger tick writes to R13 |
| `START_RETRIGGER`, and `HOLD` with flag 4 | X bits 7-4 become that channel's retrigger shape. Neither writes R13 in the frame |
| X bits 3-0 with `START_PCM_PREEMPT` | each marked channel's timer is stopped before this channel's timer is programmed |
| `RELEASE` bit 0 clear | the timer is stopped |
| `RELEASE` bit 0 set | the timer's interrupt is disabled and the timer keeps counting; a tick falling due in the gap is dropped |
| `RETUNE`, either form, and `START_RETRIGGER` at voice 3 | the timer is reprogrammed without being stopped, so the count in flight runs to its end |
| `RESUME` at voice 3 | the timer is programmed at the index the opcode carries, with no vector written, so the installed half stands and the new rate is exact from the first tick after it |
| `HOLD` flag 1, `RESUME` flag 1 | the count reaches the data register with the timer running, so the period in flight runs to its end and the next one takes the new count. Neither opcode stops a timer |
| a toggle stream's volume, a PCM stream's sample number | read from the voice's own register stream on the frame the stream starts, and where an opcode's flag re-reads them (§3) |
| `START_TOGGLE` | R8+v is written 0 among that frame's actions, and the first tick, one timer period later, writes the level |
| programming a timer | four writes in the order stop, vector, count, run, with no tick of that timer between the first and the last, ending with that interrupt enabled and unmasked. This document names no register and no address: which they are is the host's, and the order is what a player owes a stream |
| a sample byte with bit 7 set | it is written to the volume register as a level, and the sample ends there |
| loop point `$FFFF`, on the marker | 13 is written to the volume register and the timer is stopped |
| loop point of any other value, on the marker | the read position becomes that offset into the sample and the ticks continue |
| the frame's order | skip bits, then the fourteen register writes, then the frame's actions in channel order, then one refill |
| the frame that ends the tune, flag bit 0 set | after that frame's write, actions and refill: every claimed timer is stopped, its vector parked, its interrupt enabled with nothing pending, and every skip bit cleared, before frame `L` is played again |
| a required-streams mask bit outside the streams the consumer implements | the file is rejected |
| a required-streams mask bit inside them | that stream is read |
| an extension stream's table entry that is 0 | the file does not carry that stream: nothing is decoded there and it takes no position in the decode list |
| the decode list | streams 0 to the base count less one, then the extension streams the consumer implements and the file carries, in ascending index order |
| refill turn | on call `n` counted from init, the stream at position `n` modulo `C` of the consumer's decode list is decoded `C` values further, the count running on across a wrap |
| a section running out mid-refill | decoding continues into the same ring: from the start of the section where `L` is 0, and from the state saved at `L` values where it is not (§8) |

### 9.3 The unchecked rules

A player does not have to check these rules; a file that breaks one is
undefined behaviour (§9.1). Collected from the sections that define them,
and grouped by the streams they couple in §10, which states what each
coupling buys:

The shape:

- `O` is at least 1; `N` and `C` are within §1.3. At a unit size above 1,
  `O` and `C` are multiples of the unit size.
- `S` is 25 where the file carries no extension stream, and one past the
  highest extension index it carries otherwise.
- Section-table entries 0 to 24 are nonzero. An entry from 25 to `S - 1`
  is nonzero where the file carries that stream and 0 where it does not.
- Bits 0 to 24 of the required-streams mask are set. A bit from 25 up is
  set only for a stream the file carries.
- The file carries no stream at an index §1.7 leaves reserved.
- Where `L` is not 0, `L` is less than `O`.
- Each carried stream's section decodes to `O` values. Where `L` is 0 or
  `O` - `L` is at most `N`, every container carries `$FFFFFFFF` as its
  rewind point: a wrap reaches back one pass into the rings, or opens the
  section again, and no further (§8).
- Where `O` - `L` is larger than `N`, every container carries `L` as its
  rewind point, so §8's second form is the one in force, and no match in
  the frames from `L` reaches before `L`. At a unit size above 1, `L` is
  a multiple of the unit size.
- No container repeats: the bit after its end code is 0. Every container's
  window is `N` over the unit size. An offset past it is a copy from the
  literal stream (Appendix A.5), and a file carries one only with flag
  bit 5 set; with the bit clear no offset exceeds the window.
- Every section decodes to values of one byte. No back-reference exceeds
  `N` bytes and no operation exceeds 65535 units (§1.4).
- The sample table is within §6: at most 32 samples, the `$80` marker at
  each sample's length offset, each loop point less than its sample's
  length or `$FFFF`, the table on a long boundary.

The values:

- M marks only channels flagged in §1.2.
- An extension stream's mask bit is set where a consumer that reads none
  of the stream would produce a register write, an action or a reported
  value that a consumer reading it would not, and clear where it would not
  (§1.6).
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
  voice-addressed `RETUNE` retunes a toggle stream, on a frame whose
  `RESUME` at voice 3 re-enters one, and on any frame an opcode's flag
  re-reads the parameter, the voice's register byte carries the operand
  defined in §3.

The actions:

- At most one timer stream runs on a voice at a time; where a source
  starts two, the conflict is resolved at pack time.
- A programming opcode's prescaler index is 1 to 7 (§2.4); its count byte
  may be 0, read by the MFP as 256 (§5).
- `RETUNE` to voice 3 is emitted only where the stream's parameter did
  not change that frame (§3.1).
- A `RETUNE`, and a `START_RETRIGGER` at voice 3, keep the stream's kind
  and voice; a changed kind or voice re-enters through a start opcode
  (§3.1, §3.4).
- `START_RETRIGGER` at voice 3 and `RESUME` at voice 3 are emitted only
  over a stream already running on that channel: both reprogram a timer
  and write no vector (§3.4, §3.5).
- `RESUME` is emitted only after a disabling release, for the same stream
  and voice. Addressed to a voice the prescaler is the same too; at voice 3
  it is the index the opcode carries (§3.3, §3.5).
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

### 9.4 A reader

A **reader** produces the values a frame writes and drives no chip: a
converter, an analyser, a tool that reports what each frame writes. It
performs §7 steps 1 to 3, decodes every stream §1.5 counts, and checks
§9.1.

A reader takes frame `f`'s value from each stream directly. §7 step 4 and
§8's two forms state how a ring of `N` bytes delivers that value, and
neither changes which value frame `f` reads. From §8 a reader reads the
order the frames are played in and the state cleared at the end of a
pass. Of that state a reader holds the three skip states. It begins its
first call with all three clear, as §7 has a player begin frame 0, and
clears them again on the frame that ends the tune, after that frame's own
writes. Frame `L` is then played as §7 has it: its own M byte sets the
skip states again where it carries bit 4. The timers, their vectors and
their interrupts are a player's, and a reader holds none of them.

A per-frame register dump written by a reader carries the frame's values
alone: a voice a timer stream owns holds no level there, because the
levels it plays are a tick's. `START_TOGGLE`'s `R(8+v) := 0` is one of the
frame's own writes and stands in the dump, on a skipped voice too; the
levels the stream plays afterwards are a tick's.

It leaves unread what a timer writes between frames. A timer
stream's values reach the chip from a tick, at a rate §5 sets, and a
reader has no ticks: it produces one set of values a frame. So a reader
reads §3's fourth column, which says which opcodes write a sound register
in their own frame, and needs no other column; and it reads neither §5
nor §6, which say at what rate a tick lands and which sample byte it
carries.

A reader checks the mask as a player checks it (§9.1): a mask that names
an extension stream the reader does not implement is a rejection, not a
stream to step over. A reader reads every base stream §1.5 counts and each
extension stream it implements and the file carries. What it leaves unread
past that is an advisory extension, and by §1.6 that changes none of the
values it produces. So a reader and a player that implement different
extension streams still agree on every value a frame writes, and an
extension one of them lacks is a rejection rather than a disagreement.

A reader still reads M's skip bits and applies them (§2.1). A skip is
carried in the file, frame by frame, so a reader omits the same volume
registers a player does without computing when a stream ends.

Two things follow. A reader and a player agree on every value a frame
writes and on nothing between frames. And a file whose streams a reader
decodes without complaint may still be one §9.3 forbids: a reader checks
§9.1 and no more, exactly as a player does.

---

## 10. Coupled streams

Where one stream's byte settles what another's means, the frame each
turns on, and what the coupling buys.

Every rule here is one of §9.3's, so nothing in a player reports a file
that breaks one. Timings are §7's four steps: the skips precede the
frame write, and the actions follow it.

### 10.1 The volume register and the skip latch

A voice is skipped when its volume register is left out of the frame
write, for as long as a timer stream owns that register (§2.1).

**The skip bits are state, not an edge.** `M` bit 4 says bits 7-5 are
meaningful this frame; with bit 4 clear they are not read and the state
stands (§2.1). Every frame that changes a skip bit sets bit 4. A skip
left set holds the voice out of the frame write for the rest of the
tune.

**A start frame skips in that frame.** On a trigger frame the voice's
register byte holds the sample number, and a voice still in the frame
write sends that number to the chip as a volume.

**A skipped voice's byte is a parameter**, read from the current frame's
byte of `R8+v` (§3.2):

| opcode | what it reads from `R8+v` |
|---|---|
| `START_TOGGLE` | the volume |
| `START_PCM` | the sample number |
| `START_PCM_PREEMPT` | the sample number |
| `RETUNE` addressed to a voice | the volume |
| `HOLD` with flag 2 | the volume |
| `RESUME` with flag 2 | the volume |
| `RESUME` addressed to voice 3 | the volume |

A sample number reaches a player through a five-bit register, so a file
carries at most 32 samples (§6).

**The rejoin frame clears the skip**, and that frame's write carries the
voice's volume (§2.1).

`START_TOGGLE` is the one opcode that writes a sound register itself:
`R(8+v) := 0`, a skipped voice included. That is phase zero, and the
first level arrives one timer period later from the tick (§3.3). The
write lands in step 3, so where a frame writes one register twice the
action's value stands (§7).

The rejoin moves in three cases:

- A sample cut off by its own channel's next start does not reach its
  end marker. No tick writes that voice again, so the voice rejoins only
  where a later frame's `M` clears the skip (§9.3).
- After a one-shot the voice rejoins no earlier than §6's frame count. A
  skip lifted earlier puts the frame write and a tick on one register.
- On a frame whose `RELEASE` stops a sample the voice rejoins in that
  frame's write, which precedes the stop. A tick between the two writes
  one more sample byte over the returned volume (§3.3).

**One stream's life.** Seven frames, the frames carrying no action left
in. `R9` is voice B's register byte.

| frame | `M` | `A` | the action | `R9` |
|---:|---|---|---|---:|
| 0 | `$00` | | | 13 |
| 1 | `$53` | `$6E` | `START_TOGGLE`, voice B, prescaler 6 | 15 |
| 2 | `$01` | `$2A` | `HOLD`, voice B, flag 2 | 13 |
| 3 | `$01` | `$2A` | `HOLD`, voice B, flag 2 | 14 |
| 4 | `$00` | | | 14 |
| 5 | `$00` | | | 14 |
| 6 | `$93` | `$40` | `RELEASE` | 15 |

Frame 1 starts the stream and skips the voice in one byte. `$53` marks
channels 0 and 1, sets bit 4, and sets bit 6 alone, so voice B leaves the
frame write on the frame its stream takes the register.

Frames 2 to 5 hold the skip without restating it. `$01` marks channel 0
and leaves bit 4 clear; `$00` marks nothing. Bits 7-5 are read on none of
the four, and the state set on frame 1 stands through all of them.

`R9` carries the stream's volume across the whole of it, and the two
`HOLD`s follow the byte: flag 2 reloads the volume, and the frames
carrying one are the frames the byte moved on. It settles at 14 and the
channel acts no further.

Frame 6 clears bit 6 and sets bit 7 in one byte. Voice B rejoins the frame
write, which carries its 15 to the chip in that same frame, and voice C
leaves the write for a stream another channel starts on the same frame.

### 10.2 The one envelope generator

`R13` carries `$FF` on every frame that must not restart the envelope,
and a frame carrying `$FF` omits `R13` from the write (§7). Any other
value restarts it.

A retrigger stream's shape is in `X` bits 7-4, one per frame rather than
one per channel: the chip has one envelope generator, and per-channel
shapes would drive it at two shapes and two rates (§2.2).

A `HOLD` sets at most one of flag 2, the toggle stream's volume, and
flag 4, the retrigger stream's shape: a channel carries one stream kind
between one start opcode and the next (§9.3).

### 10.3 The timers and the map that names them

A stream's ticks name the timer the map gave its channel when that
stream's timer was last programmed, not the timer the current byte gives
it, and a map change stops no timer (§2.3). The byte is read every
frame, and a changed one takes effect before that frame's actions.

So a change reaches only a channel with nothing running, and names only
a timer claimed at frame 0 (§9.3). A channel whose timer counts with its
interrupt disabled is running, and a disabling `RELEASE` does not free
it (§3.3).

Three more hold between an opcode and the stream it addresses:

- At most one timer stream runs on a voice at a time. Where a source
  starts two, the conflict is resolved at pack time (§9.3).
- `RETUNE` replaces the control nibble and the reload with the timer
  running under either form. Voice 3 repatches no parameter, so it is
  emitted only on a frame where the stream's parameter did not change;
  where it changed, the voice-addressed form repatches it and the period
  in flight still completes (§3.1).
- `RESUME` continues one stream across a gap, same voice. Addressed to a
  voice it carries no rate; a prescaler changed across the gap re-enters
  through `RESUME` at voice 3, which programs the timer at the index it
  carries (§3.3, §3.5).

### 10.4 PCM, which takes a voice whole

`R7` carries bits 5-0, the host holding 7-6, with every generator masked
off a voice for as long as a PCM stream owns it (§2).

`START_PCM_PREEMPT`'s `X` bits 3-0 mark the channels with a running
timer that the trigger silences, and mark no others. Where it silences
none, `START_PCM` is the encoding (§2.2). The stops precede the opcode's
own timer program.

The end marker `$80` reaches the volume register unchanged and plays as
one tick of silence at the loop seam. On a one-shot the same tick then
writes 13 and stops the timer, so the voice holds a mid-scale level
until it rejoins the frame write (§6).

### 10.5 What the couplings buy

**The chip's.** One envelope generator gives one shape a frame, so `X`
carries the shape and no voice's register stream does. One mixer
register gives `R7` the masking of a PCM voice's generators. A five-bit
volume register sets the sample ceiling at 32. None of these is this
format's to settle.

**Bought.** A skipped voice's register byte standing for the stream's
parameter (§10.1), and `R13`'s `$FF` standing for a frame that leaves
the envelope alone (§10.2), each stand in for a stream the file would
otherwise carry. A stream costs `N` bytes of ring in every workspace,
read or not (§1.3), and one refill turn out of `C` (§1.5). The two hold
a YM tune's stream count at the 25 of §1.5.

**Derivable.** `START_PCM_PREEMPT`'s `X` nibble names the channels the
trigger stops, which a player already holds: it programmed those timers.
The skip bits are derivable the same way. The frame write is why the file
carries them instead: it is one unbroken run of register writes at a fixed
cost, and a skip settled inside it would put a test on every voice of
every frame. The bits state the answer where step 1 can apply it.

A coupling removed is a new format version, and a tune packed at the old
one is repacked from its source (§1.1). A value a player could derive is
a value a checker can derive: holding the channel state a player holds
and comparing it against the bits the file declares reports every rule
in this section, and moves the format none.

---

## Appendix A. The ST4 container

A packed section is one ST4 container. This appendix states it in full, so
a reader implements this document without a second one. The format is
ST4's; version 7 is the version stated here.

### A.1 The header, twenty-eight bytes

| offset | size | field |
|---:|---:|---|
| 0 | 4 | signature `$53 $34 $07 k`: `'S'`, `'4'`, format version 7, unit size `k` |
| 4 | 4 | output size in bytes, a multiple of `k` |
| 8 | 4 | where stream B starts, in bytes from the header's first byte |
| 12 | 4 | where stream C starts |
| 16 | 4 | where stream D starts |
| 20 | 4 | the rewind point in bytes, or `$FFFFFFFF` where there is none |
| 24 | 4 | `M`, the window in units |

Stream A begins at offset 28. The three offsets locate the others, so a
stream's length is the distance to the next one, and the bytes between the
last byte stream A uses and stream B's first are padding. The rewind point
and the window are read by a decoder that replays a loop or copies from
the literal stream (A.5); a section that does neither carries `$FFFFFFFF`
and `N/k`, and a reader of this version checks the two (§9.3) and reads
them no further.

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

A match copies one unit at a time, so a distance shorter than the length
repeats the units the match has already written, and a distance of one unit
fills the match with that one unit.

A flag bit precedes every block but the first: the stream opens on a
literals block, encoded as its row gives it, and the bit that follows that
block is read as the bit after any run of literals. After literals, a `0`
bit starts a match at the last offset, and a `1` bit is followed by the
two class bits of A.4, which give a match at a new offset or the end of
the stream. After a match of either kind, a `0` starts literals and a `1`
is followed by those same two class bits. A flag bit is never one of the
two class bits: a new offset costs three bits before its value. Two
literal runs never follow each other.

**The last offset is a count of units, and begins at one.** A decoder that
has read no new offset yet copies from one unit back - `k` bytes - so a
first block of literals may be followed immediately by a match at the last
offset. A match at a new offset becomes the last offset; literals and a
match at the last offset leave it as it was.

### A.4 New offsets

| class bits | meaning |
|---|---|
| `1 0` | byte offset from stream C, 1 to 256 units back |
| `1 1` | byte offset from stream C, 257 to 512 units back |
| `0 0` | word offset from stream D |
| `0 1` | end of the stream, followed by one repeat bit |

In the table above the left bit is the one stream A delivers first.

A byte offset of `n` units in bank `b` is stored as the byte
`256·(b + 1) - n`, where `b` is 0 for class `1 0` and 1 for class `1 1`. A
word offset of `n` units is stored big-endian as `65536 - n·k`, which is
`-n·k` in sixteen bits. Negate the stored word for the distance in bytes,
and divide that by `k` for `n`. A byte class counts units where the word
class counts bytes, so no division by `k` applies to it: recover its `n`
from the formula above and no other way.

A decoder reads one more bit after the end-of-stream class, the repeat
bit. A `0` ends the stream, and the decoder stops having written the
header's output size in bytes. A `1` repeats it: one last word offset is
read from stream D, the distance back to a loop point, and the stream
continues as a match that far back for as long as output is read (A.5).
No offset reaches further back than 32512 bytes at any `k`, and a match
at a new offset is at least 2 units long.

A decoder that has written the output size has also read streams A, B and
C to within the padding A.1 describes - three bytes at most, since the
next stream starts on a long boundary. Nothing else in a container tests
a decoder: a reading of A.3's blocks that is wrong reads the same flag
bits and the same gamma values, stops on an end-of-stream class, and
writes the output size in full with the wrong bytes in it. How much of
those three streams is left over separates the two.

### A.5 Loops and copies

The header's rewind point and window, and the repeat bit, are the
container's own loop and copy forms. A section of this version uses none
of them: it ends, its rewind field is `$FFFFFFFF`, and no offset exceeds
`N/k`, its window (§1.4, §9.3). They are stated so a reader of the
container is complete.

**A repeat.** A container packed with a loop point `R` stands for the
output `[0, R)` then `[R, O)` for ever. Where `O - R` is at most the
window, the repeat bit is set and the word after it in stream D is the
distance `O - R`; the decoder installs it as any other offset and matches
it for as long as output is read, so after one pass every unit is the one
`O - R` units back.

**A rewind.** Where `O - R` is larger than the window, the stream ends
plainly and the header gives `R` in bytes. The reader saves the decoder's
state when the output reaches `R` and restores it, all but the write
position, when it reaches `O`, on every pass. No match in `[R, O)` reaches
before `R`, so every pass reads the same history.

**A copy.** An offset larger than the window `M` is a copy from the
literal stream rather than a match from the output: it copies
`offset - M` units from behind the stream B read position, leaves that
position where it is, and advances the offset by the units copied, so a
match at the last offset after a copy resumes past it. A copy is shorter
than its distance, so the offset never reaches zero. A byte offset reaches
`512 - M` literal units back; a word offset as far as its word allows.

---

## Appendix B. A worked file

A file of 240 bytes, four frames, every section stored. Read against §1
and §7 it settles the header's fields, where the body begins, how a stored
section is read, and what a call reports.

### B.1 The header

```
   0  59 4D 58 21   'YMX!'
   4  00 09         format version 0.9
   6  00 01         flags: bit 0 set, the tune starts over
   8  00 00 00 04   O = 4 frames
  12  00 32         50 Hz
  14  00 19         S = 25: no extension stream
  16  03 C0         N = 960
  18  00 18         C = 24
  20  00 1E 84 80   master clock 2000000
  24  00 00 00 00   no sample table
  28  00 00         sample count 0
  30  00 00 00 00   L = 0, the tune starts over from its first frame
  34  01 FF FF FF   Q = $01FFFFFF: the twenty-five, and no extension
  38  80 00 00 8C   stream 0: bit 31 set, stored, at offset 140
  42  80 00 00 90   stream 1: stored, at offset 144
       ...
 134  80 00 00 EC   stream 24: stored, at offset 236
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
| 4 | 0 | the call 0 row again |
| 5 | 0 | the call 1 row again |
| 6 | 0 | the call 2 row again |
| 7 | 1 | the call 3 row again |

Call 3 plays frame `O - 1` and reports 1, the tune having gone round; call
4 plays frame 0 again, because `L` is 0. Call 7 plays frame `O - 1` on the
second pass and reports 1 as call 3 did: the report is the frame's, not the
first pass's. R7's values carry the host's port
bits: the stream's byte for frame 0 is `$38`, and `$38 | $C0` is 248.
