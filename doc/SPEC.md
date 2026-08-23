# The YMX format — specification

Version 1. Big-endian throughout.

YMX is a streaming register-dump format for the YM2149 sound chip in the
Atari ST, playable by a 68000 without the tune resident in memory. A file
carries twenty-five independently compressed streams: fourteen register
streams, one value per frame, and eleven streams of a **compiled effect
script** that drives the MFP's timers. Twenty-five is the stored count in
every file; §1.5 separates it from the decoded and the carrying counts.
Each stream is decoded through a ring of `N` bytes, one stream refilled per
frame, so memory use depends on the player configuration, not on tune
length.

The YM formats — YM3 to YM6 — store "special effects" as values the player
re-derives every frame from spare register bits. YMX resolves them at pack
time and stores the outcome; a player compares nothing at run time.

Two terms recur. A **player** performs §7, §8 and §9.2 and checks §9.1. A
**writer** — a packer, or a tracker emitting the format directly — is bound
by every rule in this document; §9.3 lists the rules no player checks.

Version 1 began as the `.yx6` container of the [ST4](https://github.com/odipar/ST4)
repository, renumbered; the layout has changed since, and this document
defines the current layout. No older YMX version exists to stay compatible
with.

**Vocabulary.** Defined in [terminology.md](terminology.md). The five terms
used most:

| word | meaning |
|---|---|
| **frame** | one call of the player; the tune's clock, typically 50 Hz |
| **stream** | a series of values arriving at one destination |
| **frame stream** | a stream delivering one value per frame — the fourteen sound registers |
| **timer stream** | a stream delivering values *between* frames, at a rate an MFP timer sets |
| **section** | one stream's values for the whole tune, packed |

---

## 1. The container

```
+--------------------------------+
| header, 30 bytes fixed         |
| section table, 4 x S           |
| packed sections, in stream order
| sample table + sample bytes    |
+--------------------------------+
```

### 1.1 Header

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `'YMX!'` — `$594D5821` |
| 4 | 2 | format version — **1** |
| 6 | 2 | flags (§1.2) |
| 8 | 4 | `O`, the frame count |
| 12 | 2 | frame rate in Hz: how often the player is called |
| 14 | 2 | `S`, the stream count — always **25**, see §1.5 |
| 16 | 2 | `N`, the ring size in bytes |
| 18 | 2 | `C`, values decoded per call |
| 20 | 4 | the YM2149's master clock in Hz (informational) |
| 24 | 4 | byte offset of the sample table; 0 when there are none |
| 28 | 2 | sample count |
| 30 | 4·S | byte offset of each **section**, covering frames `[0, O)` |

With `S` fixed at 25 the header is **130 bytes**. Everything after it is
body: the sections (§1.4), then the sample table (§6). Content in the body
is located only by offset — a section by its table entry, the sample table
by the offset at byte 24.

### 1.2 Flags

| bit | meaning |
|---:|---|
| 0 | the tune starts over at frame 0 instead of ending |
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

Each section is a complete ST4 container — signature, twenty-byte header,
four streams — as defined by the [ST4](https://github.com/odipar/ST4)
format. Every section begins on a long boundary; parts of a container are
read a word at a time. Two container parameters are fixed by this format:
no back-reference exceeds `N` bytes (§1.3), and no operation exceeds 65535
units. Every section in a file is packed at one **unit size** — 1, 2 or 4
bytes, recorded in the section's ST4 signature, not in the YMX header. A
player accepts only the unit size it was built for (§9.1).

A section may instead be **stored**: the bytes at its offset are the
values, one per frame, with no header and no signature, on the same long
boundary. Bit 31 of a table entry set marks a stored section; bits 30-0
are the offset. A container's header is twenty bytes, so a section shorter
than twenty bytes is smaller stored (a one-frame tune stores one byte per
stream). A stored section is read the same at any unit size; a file with
only stored sections plays on any build.

Each stream is packed as one section covering `[0, O)`. `O` is at least 1
and all twenty-five table entries are nonzero. Section order in the file
is not significant; a section is located only by its offset. When a tune
with flag bit 0 set reaches the end of a section, the player reopens the
section from its start (§8).

Packed sizes are not stored: ST4 counts output units, so a decoder does
not need them.

### 1.5 Three stream counts

`S` is fixed at 25; a player rejects any other value. The section table is
therefore always 100 bytes and the payload begins at a constant offset.
Three counts apply:

| | |
|---|---|
| **stored** — always 25 | the size of the section table; all entries nonzero |
| **decoded** — 17, 19, 21, 23 or 25 | what a player reads per cycle: 17 for a tune that uses no timer channel, then two more per channel, up to and including the **highest** channel in use |
| **carrying** — fewer still | an idle channel's pair is one value repeated, and compresses to almost nothing |

`C` covers the middle count. It steps by the highest channel in use, not
by the number in use: a player decodes stream 0 through the highest used
channel's pair and stops. A tune using only channel 3 decodes 25.

The channel pairs are last in stream order, so idle channels lie past the
decoded range and cost nothing.

---

## 2. The streams

Twenty-five slots, in this order. Streams 0–13 are frame streams; 14–24
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
frame whose M bit marks the channel, a P or X byte where the verb reads
one (§3). On other frames the byte's value is unspecified; a writer
repeats the previous byte, which compresses to nothing.

**Register masks.** A register value contains only the bits in the table
below; a writer writes the remaining bits as zero (§9.3), and a player
masks nothing:

| register | bits |
|---|---|
| R0, R2, R4 | 8 — a tone period's fine byte |
| R1, R3, R5 | 4 — its coarse nibble |
| R6 | 5 — the noise period |
| R7 | 6 — the mixer, bits 5-0; see below |
| R8, R9, R10 | 5 — four bits of level, and bit 4 following the envelope |
| R11, R12 | 8 — the envelope period, fine and coarse |
| R13 | 4 — the shape, or the whole byte `$FF`; see below |

Two registers have additional rules.

**R7 carries no port bits.** Bits 7-6 of the mixer register are the chip's
I/O port directions, not sound. The stream carries bits 5-0; a player
supplies the host's value for bits 7-6. On an ST that value is `$C0` —
both ports output, since port A drives the floppy select lines; port bits
taken from the file would drive the floppy selects with tune data. Bits
5-0 arrive with every sample-playing voice **disconnected** — no generator
mixed into the voice — applied at pack time (§9.3).

**R13 has a do-not-write value.** Any write to R13 restarts the envelope,
so a stream repeating the current shape would restart it every frame.
`$FF` is a marker: on a frame carrying `$FF`, the frame write does not
write R13. R13 is four bits wide, every genuine shape is `$00`-`$0F`, and
`$FF` is the one value that reaches a stream unmasked.

### 2.1 M — the master byte

`0`: nothing acts this frame.

| bits | meaning |
|---:|---|
| 0 | timer channel 0 acts — read its A and the operands of §3 |
| 1 | timer channel 1 acts |
| 2 | timer channel 2 acts |
| 3 | timer channel 3 acts |
| 4 | bits 7-5 are meaningful this frame — apply them |
| 7-5 | one bit per voice A, B, C — a set bit **skips** that voice |

Channels marked in one frame act in channel order, 0 first (§7).

A voice is **skipped** when its volume register is omitted from the frame
write, for as long as a timer stream owns that register. A skip does not
silence the voice: the timer stream writes the register at its own rate.
The register's stream advances one value per frame whether read or not,
so lifting a skip requires no resynchronisation.

Bits 7-5 are state, not an edge: re-asserting the same value has no
effect. With bit 4 clear, bits 7-5 are not read and the skip state is
unchanged.

### 2.2 X — the spare operands

| bits | meaning |
|---:|---|
| 7-4 | the envelope shape a retrigger stream restarts |
| 3-0 | what `START_PCM_PREEMPT` preempts: bit `c` marks timer channel `c`, whose timer must be stopped first |

The shape is one per frame, not one per channel: the chip has one envelope
generator, and per-channel shapes would drive it at two shapes and two
rates.

The shape is carried rather than derived, which keeps the player
independent of the source format. A toggle stream's volume and a PCM
stream's sample number are read from the voice's register stream (§3.2).
A shape is not a voice's — any number of voices may follow the one
generator — and its storage location differs by source format; the writer
resolves it into X. The format has no flag, shadow or priming for the
shape: a tune that arms a retrigger stream before any shape is set
carries the source's default (`0` for a YM dump).

### 2.3 T — the channel-to-timer map

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
flagged channels name distinct timers. The byte is read every frame; a changed byte takes effect before
that frame's actions (§7). A map change stops no timer, so a writer
changes the entry only of a channel with nothing running, and only to a
timer claimed at frame 0. A tune that never re-assigns repeats the byte,
which compresses to nothing.

Timer C is the Atari ST's 200 Hz system clock: a tune using it stops that
clock, and cannot be driven from a Timer C interrupt.

### 2.4 A and P — the action and its count

`A` is one action byte:

```
  7 6 5   4 3   2 1 0
 +-----+ +---+ +-----+
 | verb| |vc | | low |
 +-----+ +---+ +-----+
```

- **verb** (bits 7-5) — one of the eight in §3.
- **voice** (bits 4-3) — 0, 1, 2 for voices A, B, C. Three voices in a
  two-bit field, so **3 is no voice**; see `RETUNE` in §3.
- **low** (bits 2-0) — the MFP prescaler index for a verb that programs a
  timer, or flags for `HOLD` and `RESUME`. A programming verb's index is
  1 to 7; index 0 is the MFP's stopped state (§5), and no verb carries
  it.

`P` is the MFP timer count for a verb that programs or reloads a timer.

---

## 3. The verbs

| # | verb | operation |
|---:|---|---|
| 0 | `RESUME` | unmask a masked toggle stream. Flags: 1 = reload the count, 2 = reload the volume. Phase continued through the gap |
| 1 | `HOLD` | update a running stream. Flags: 1 = reload the count from P, 2 = reload the toggle stream's volume, 4 = reload the retrigger stream's shape. Emitted only on frames where a value changed |
| 2 | `RELEASE` | stop this channel's timer. Bit 0 set: mask the interrupt instead, leaving the timer counting |
| 3 | `START_TOGGLE` | start a toggle stream: select, volume, vector := the loud half, then a full program |
| 4 | `RETUNE` | a new rate for a running stream, keeping its place in the cycle. See §3.1 |
| 5 | `START_RETRIGGER` | start a retrigger stream: shape, vector := the retrigger tick, then a full program |
| 6 | `START_PCM` | a trigger, fresh or repeated: sample table lookup, select, vector, full program |
| 7 | `START_PCM_PREEMPT` | as `START_PCM`, but first stop the timer of every channel marked in X's low nibble |

What each verb reads besides its action byte:

| verb | P | X | the voice's register byte |
|---|---|---|---|
| `RESUME` | with flag 1 | — | the volume, with flag 2 |
| `HOLD` | with flag 1 | the shape, with flag 4 | the volume, with flag 2 |
| `RELEASE` | — | — | — |
| `RETUNE` to a voice | yes | — | the volume |
| `RETUNE` to voice 3 | yes | — | — |
| `START_TOGGLE` | yes | — | the volume |
| `START_RETRIGGER` | yes | the shape | — |
| `START_PCM` | yes | — | the sample number |
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
frame where the stream's parameter — a toggle stream's volume, a
retrigger stream's shape — did not change. Where the parameter changed on
the same frame, the ordinary form is required, and the period in flight
is truncated.

### 3.2 Where a stream's parameter comes from

A toggle stream's volume and a PCM stream's sample number are read from
the voice's register stream — the current frame's byte of R8+v, which the
voice's skip bit excludes from the frame write (§2.1). A retrigger
stream's shape is carried in X: it is the one envelope generator's, not a
voice's.

### 3.3 Phase

A toggle stream that stopped — released, or its voice taken by a PCM
stream — re-enters through `START_TOGGLE`, at phase zero: the player
writes the voice silent, and the first tick, one timer period later,
writes the level.

Phase is retained only across a held code and its retunes.

`RESUME` implements the alternative gap model: a release masks the timer
interrupt, the timer keeps counting, and a re-arrival resumes the toggle
stream at its current phase. The model in use is not recoverable from a
YM file and is fixed at pack time.

---

## 4. Timer streams and the code byte

The four **kinds** of timer stream:

| bits 7-6 | kind | what it is |
|---:|---|---|
| `00` | **toggle stream** | a square wave made by flipping a voice's volume between a level and zero — a "SID voice" |
| `01` | **PCM stream** | a stored sample played out through a voice's volume register — a "digidrum" |
| `10` | **wave stream** | a table read out the same way. Never seen in a dump; reserved |
| `11` | **retrigger stream** | R13 rewritten at the timer's rate, restarting the envelope — a "sync-buzzer" |

The **code byte** does not appear in the file. It is the intermediate a
writer compiles the script from, one byte per channel per frame, specified
so that writers reading different source formats compile the same codes to
the same script:

```
  7 6     5 4      3      2 1 0
 +-----+ +-----+ +---+ +-------+
 |kind | |vc+1 | | t | |prescal|
 +-----+ +-----+ +---+ +-------+
```

- **kind** — as above.
- **voice + 1** — a zero voice field means an idle channel, and a zero
  byte means nothing.
- **t** (bit 3) — unassigned; a writer may use it. A source whose
  triggers are events rather than repeated codes flips it on every
  trigger, so two triggers of one sample at one rate are two different
  bytes and the compiled script starts the sample twice. A source with no
  such signal leaves it zero, and every change of a code is then a
  trigger.
- **prescaler** — the MFP prescaler index, which the count byte completes.

---

## 5. Rates

A timer stream's rate is the MFP's own clock divided twice:

```
rate = 2457600 / prescaler[index] / count
```

| index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| divider | — | 4 | 10 | 16 | 50 | 64 | 100 | 200 |

The index equals the value of a delay-mode MFP timer control register; a
player programs it unchanged.

Index 0 is the MFP's stopped state, not a divider: a code with index 0
starts nothing. A count of 0 is read by the MFP as 256, the slowest count
of a prescaler.

The encodable range is 48 Hz — prescaler 200, count 0, which is 256 — to
614,400 Hz, prescaler 4 and count 1. What a writer emits is narrower: rate
ceilings are pack-time rules, not format limits.

---

## 6. The sample table

At the header's sample-table offset — a long boundary — `count` entries of
**8 bytes**:

| offset | size | field |
|---:|---:|---|
| 0 | 4 | byte offset of the sample's first byte |
| 4 | 2 | length in bytes |
| 6 | 2 | loop point, or `$FFFF` for one-shot |

Sample bytes are volume levels 0-15, followed by one **end marker**,
`$80`. A PCM tick writes a byte to the volume register, advances its
pointer, and ends the sample on a byte with bit 7 set; nothing is counted
or compared per tick. The marker has been written as a level by the time
it is tested, and `$80`'s level bits are zero, so the marker plays as one
tick of silence.

The **loop point** is a position in the sample, not an address. On the end
marker, a tick with a loop point other than `$FFFF` sets its pointer to
that position and continues; the seam costs the marker's one tick of
silence. A one-shot stops its timer. `0` is a valid loop point — the
sample repeats whole — so the no-loop value is one no length can reach.

A sample number is read from a voice's five-bit volume register (§3.2), so
a file may carry at most **32** samples.

---

## 7. The frame

One player call per frame, in this order:

1. **Skips.** If M bit 4 is set, set the three skip states from bits 7-5.
2. **The frame write** — fourteen register writes, R0 through R13, each
   value the current frame's byte of its stream. Omitted: a skipped
   voice's volume register, and R13 on a frame carrying `$FF`. R7 is
   written with the host's port bits in 7-6 (§2).
3. **Actions** — for each channel marked in M, in channel order: decode
   the A byte, run the verb. A changed T byte takes effect before the
   first action (§2.3).
4. **One refill** — decode `C` values into one stream's ring: stream `k`
   on frames where the frame number modulo `C` equals `k`. A `k` past the
   decoded streams (§1.5) refills nothing.

Before frame 0, a player decodes one group of every stream — `C` values,
or `O` when the tune is shorter and plays once — so frame 0's values are
present.

Actions follow the frame write so that their varying cost does not delay
the register writes. Skips precede it so that a voice released in a frame
has its volume written in that same frame.

A register write is two bus cycles — select, then value — and an interrupt
between them sends the value to the register the interrupt selected. A
single `movep.w` cannot be split by a 68000; masking interrupts across the
frame write is therefore optional.

---

## 8. Starting over

A packed stream restarts only from its beginning. A tune with flag bit 0
set plays its frames again from frame 0: when a section runs out
mid-refill, the player opens the same section again — a fresh decoder
writing into the same ring — and the ring contents stay one continuous
sequence. `O` need not fall on a group boundary.

On the frame that ends the tune, after that frame's write, actions and
refill: every claimed timer is stopped, its vector is parked on a routine
with no effect, its interrupt is enabled with no tick pending, and the
three skip states are cleared. This is the state before frame 0 of the
first pass, so every pass is identical.

---

## 9. Conformance

### 9.1 What a player checks

- the magic is `'YMX!'`;
- the version is 1;
- the stream count is 25 — it is fixed, not a size to adapt to;
- every section that is a container carries an ST4 signature matching the
  unit size the player was built for — a tune packed for a different one
  is rejected, not garbled. A stored section has no signature to check.

Beyond that a player checks nothing — §9.3 lists the unchecked rules — and
a malformed file is undefined behaviour.

### 9.2 Interpreted data

Fourteen streams are copied: each value reaches its register unchanged.
The rest is interpreted — a byte whose meaning is an operation rather than
a value. Those operations, in full:

| interpreted | the operation |
|---|---|
| M bit 4 with bits 7-5 | the three skip bits take the value in 7-5. On a frame with bit 4 clear they keep the value they had; nothing else changes them |
| skip bit set for voice v | no write to R8+v occurs in the frame write |
| a section offset with bit 31 set | the bytes at that offset are the section's values, one per frame: read them, do not decode them |
| R7 bits 7-6 | the value written there comes from the host. Bits 5-0 come from the stream |
| R13 = `$FF` | R13 is not written this frame |
| stream T's byte | compared each frame; a changed map is in force before the frame's actions |
| X bits 7-4 | the value written to R13 when a retrigger stream starts, and when a `HOLD` with flag 4 runs |
| X bits 3-0 with `START_PCM_PREEMPT` | each marked channel's timer is stopped before this channel's timer is programmed |
| `RELEASE` bit 0 clear | the timer is stopped. Set: the timer's interrupt is masked and the timer keeps counting |
| `RETUNE` addressed to voice 3 | the timer is reprogrammed without being stopped, so the count in flight runs to its end |
| a toggle stream's volume, a PCM stream's sample number | read from the voice's own register stream on the frame the stream starts, and where a verb's flag re-reads them (§3) |
| `START_TOGGLE` | R8+v is written 0 among that frame's actions, and the first tick, one timer period later, writes the level |
| programming a timer | four writes in the order stop, vector, count, run, with no tick of that timer between the first and the last, ending with that interrupt enabled and unmasked |
| a sample byte with bit 7 set | it is written to the volume register as a level, and the sample ends there |
| loop point `$FFFF` | the timer is stopped at that point. Any other value: the read position becomes that offset into the sample and the ticks continue |
| the frame's order | skip bits, then the fourteen register writes, then the frame's actions in channel order, then one refill |
| the frame after the last, flag bit 0 set | every claimed timer is stopped, its vector parked, its interrupt enabled with nothing pending, and every skip bit cleared, before frame 0 is played again |
| refill turn | on frame `f`, stream `f` modulo `C` is decoded `C` values further |
| a section running out mid-refill | decoding continues from the start of that same section, into the same ring |

### 9.3 The unchecked rules

No player checks these rules; a file that breaks one is undefined
behaviour (§9.1). Collected from the sections that define them:

The shape:

- `O` is at least 1; `N` and `C` are within §1.3. At a unit size above 1,
  `O` and `C` are multiples of the unit size.
- All twenty-five sections are present, and each decodes to exactly `O`
  values of one byte. No back-reference exceeds `N` bytes and no operation
  exceeds 65535 units (§1.4).
- The sample table is within §6: at most 32 samples, each shorter than
  65536 bytes, each terminated by `$80`, each loop point inside its own
  sample or `$FFFF`, the table on a long boundary.

The values:

- A register value contains only the bits of its mask (§2). R13 carries
  `$FF` on every frame that must not restart the envelope. R7 carries
  bits 5-0, with every generator masked off a voice for as long as a PCM
  stream owns it.
- On every frame a timer stream owns a voice's volume register — from the
  frame it starts to the frame the voice rejoins the frame write — M
  keeps that voice's skip bit set, and the frame that changes any skip
  bit sets M's bit 4.
- On a frame that starts a PCM or toggle stream, and on any frame a
  verb's flag re-reads the parameter, the voice's register byte carries
  the operand defined in §3.

The actions:

- At most one timer stream runs on a voice at a time; where a source
  starts two, the conflict is resolved at pack time.
- A programming verb's prescaler index is 1 to 7 (§2.4); its count byte
  may be 0, read by the MFP as 256 (§5).
- `RETUNE` to voice 3 is emitted only where the stream's parameter did
  not change that frame (§3.1).
- `START_PCM_PREEMPT`'s X nibble marks exactly the channels with a
  running timer that the trigger silences; with none, `START_PCM` is the
  encoding.
- Stream T is within §2.3: flagged channels name distinct timers at frame
  0; a change moves only channels with nothing running, among the timers
  claimed at frame 0.
