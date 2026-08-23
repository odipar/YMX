# The YMX format — specification

Version 1. Big-endian throughout.

YMX is a streaming register-dump format for the YM2149 sound chip as fitted
to the Atari ST, designed so a plain 68000 can play a tune it never holds in
memory. A file carries twenty-five independently compressed streams — fourteen
for the chip's sound registers, one value per frame, and eleven for a
**compiled effect script** that drives the MFP's timers. Twenty-five is the
count every file stores; how many a tune fills, and how many a player reads,
are smaller (§1.5). Each stream is decoded through its own small ring,
refilled one stream per frame, so the memory a tune needs is a property of
the player's configuration rather than of the tune's length.

It extends the YM lineage — YM3, YM4, YM5, YM6 — in one respect: what those
formats call a "special effect" is a value the *player* had to re-derive
every frame from bits carried in spare register fields. YMX
resolves all of that at pack time and writes down the outcome, so the player
compares nothing.

A **player** performs §7, §8 and §9.2 and checks only §9.1. A **writer** —
a packer, or a tracker emitting the format directly — follows every rule in
this document; the ones no player checks are collected in §9.3.

Version 1 began as the `.yx6` container the [ST4](https://github.com/odipar/ST4)
repository reached over ten revisions, adopted whole and renumbered; the layout
has moved since, and this document is where it is now. There is no older YMX
version to stay compatible with.

**Vocabulary.** This document uses [terminology.md](terminology.md)
throughout. The five words it leans on hardest:

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

With `S` fixed at 25 the header is **130 bytes**, and everything after it is
body: the sections (§1.4), then the sample table (§6). Nothing in the body is
found by position — a section through its offset in the table above, the
samples through the offset at byte 24.

### 1.2 Flags

| bit | meaning |
|---:|---|
| 0 | the tune starts over at frame 0 instead of ending |
| 1 | the tune uses timer channel 0 |
| 2 | timer channel 1 |
| 3 | timer channel 2 |
| 4 | timer channel 3 |
| 5-15 | reserved, written as 0 |

A player claims an MFP timer only for a channel whose flag is set, so the
timers a tune does not need stay the host's. Which timer a flagged channel
gets is stream T's to say (§2.3).

### 1.3 `N` and `C`

`N` is the ring size, one ring per stream, and the bound a back-reference may
not reach past — every section is packed with that bound. `N` is capped at
**2520**, because a player may read register `k`'s ring through an
assembled-in displacement of `k·N` and `13·N` must fit a signed 16-bit
displacement.

`C` is how many values are decoded per call. It must

- be at least one refill slot per stream the tune **decodes** —
  17 when it uses no timer channel, then 19, 21, 23 or 25 by the *highest*
  channel it uses, since a player stops at the last channel rather than
  counting them; and
- divide `N`, which lets a player use a counted-wrap ring decoder
  rather than a general one; and
- fit twice in a ring: `N` is at least `2C`.

At a unit size above 1, `C` and the frame count `O` are each a whole number
of units, so every budget a player hands the decoder divides cleanly.

960/24 is the usual pair and covers every tune that leaves channel 3 idle.

### 1.4 The sections

Each section is a complete ST4 container — signature, twenty-byte header,
then its four streams, as the [ST4](https://github.com/odipar/ST4) format
defines them — placed on a long boundary, so the container's own alignment
rules hold where it sits. Two of the container's bounds are this format's to
set: no back-reference reaches past `N` bytes, the ring a player decodes
through (§1.3), and no single operation is longer than 65535 units, so a
word-sized counter can run it. Every section in a file is packed at one
**unit size** — 1, 2 or 4 bytes, recorded in each section's ST4 signature
rather than in the YMX header — and a player accepts only the unit size it
was built for (§9.1).

A section may instead be **stored**: the bytes at its offset are its values,
one per frame, with no header and no signature. Bit 31 of a section's offset
says which of the two it is, and the offset is the rest. A container spends
twenty bytes on its header before a value is written down, so a section
shorter than that is smaller stored — a one-frame tune is one stored byte a
stream rather than twenty-five containers. A stored section reads the same
at any unit size, so a file whose every section is stored plays on any
build.

Each stream is packed once, into one section covering `[0, O)`. `O` is at
least 1 and all twenty-five sections are present: no offset in the table is
0. The sections sit in the file in stream order, but nothing depends on that
— a section is reached only through its offset. A tune that starts over
reaches the end of a section and the player opens it again from the top
(§8), the only way to restart a decoder.

Packed sizes are not stored. ST4 counts output units rather than input
bytes, so a decoder never needs them.

### 1.5 Three stream counts

`S` is fixed. It is always 25, a player must reject a file that says
otherwise, and the section table is therefore always 100 bytes, which puts
the payload at a constant offset. Three different counts get called
"streams", and only the first is `S`:

| | |
|---|---|
| **stored** — always 25 | the size of the section table; every offset is present |
| **decoded** — 17, 19, 21, 23 or 25 | what a player reads per cycle: 17 for a tune that uses no timer channel, then two more per channel, up to and including the **highest** channel in use |
| **carrying** — fewer still | an idle channel's pair is one value repeated, and packs to almost nothing |

`C` has to cover the middle one, and it steps by the highest channel in use
rather than by how many are in use, because a player stops at the last channel
rather than counting them. A tune using only channel 3 still decodes
25.

So a `.ymx` always has twenty-five slots and rarely twenty-five streams a
player reads. The channel pairs come last so the difference costs nothing:
what a tune leaves idle is a tail no player ever touches.

---

## 2. The streams

Twenty-five slots, in this order — every file has all of them, and most files
have nothing in several (§1.5). Streams 0–13 are frame streams; 14–24 are
script data whose bytes never reach a chip register.

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

The channel pairs come last so a tune using fewer of them leaves a tail no
player ever decodes.

**When a byte is read.** M, T and the fourteen register streams are read on
every frame. Every other stream's byte is read only where this document says
so — an A byte on a frame whose M bit marks the channel, a P or X byte where
the verb consumes one (§3) — and on any other frame its value is
unspecified. A writer repeats the previous byte there, and the repetition
packs to nothing.

**Register values carry only the bits their register keeps** — a writer
masks the rest to zero (§9.3), so a player masks nothing:

| register | bits kept |
|---|---|
| R0, R2, R4 | 8 — a tone period's fine byte |
| R1, R3, R5 | 4 — its coarse nibble |
| R6 | 5 — the noise period |
| R7 | 6 — the mixer, bits 5-0; see below |
| R8, R9, R10 | 5 — four bits of level, and bit 4 following the envelope |
| R11, R12 | 8 — the envelope period, fine and coarse |
| R13 | 4 — the shape, or the whole byte `$FF`; see below |

Two registers are not plain values, and a player must handle both.

**R7 carries no port bits.** The mixer register's top two bits are the chip's
I/O port directions, not sound, so the stream carries bits 5-0 and a player
supplies the host's own value for 7-6. On an ST that is `$C0` — both ports as
outputs, because port A drives the floppy select lines — and taking those two
bits from the file instead would leave the drive selected or deselected by
whatever the tune happened to carry there. Bits 5-0 arrive with every
sample-playing voice already **disconnected** — no generator mixed into it, so
its volume register alone is its output — a packing-time edit, not a player's.

**R13 has a value that means "do not write".** Writing R13 restarts the
envelope, whatever value is written, so a stream that repeated the current
shape would restart the envelope on every frame. `$FF` is therefore not a
shape but a marker: on a frame carrying it the frame write leaves R13 alone.
No real shape can be confused with it — R13 is four bits wide, so every
genuine value is `$00`-`$0F` — and `$FF` is the one value that reaches a
stream unmasked, for this reason.

### 2.1 M — the master byte

`0` means nothing anywhere this frame — the common case, and one test.

| bits | meaning |
|---:|---|
| 0 | timer channel 0 acts — read its A, and what the verb consumes (§3) |
| 1 | timer channel 1 acts |
| 2 | timer channel 2 acts |
| 3 | timer channel 3 acts |
| 4 | bits 7-5 are meaningful this frame — apply them |
| 7-5 | one bit per voice A, B, C — a set bit **skips** that voice |

The channels a frame marks act in channel order, 0 first (§7).

A voice is **skipped** when its volume register is left out of the frame
write, for as long as a timer stream owns that register. Skipping does not
silence the voice — the timer stream is writing the register meanwhile, at
its own rate. The register's stream still advances, one value per frame,
read or not, so lifting a skip needs no resynchronisation.

Bits 7-5 are **state, not an edge**: re-asserting the same value is
idempotent. Bit 4 says those bits are meaningful this frame; without it the
three skips stand as they were.

### 2.2 X — the spare operands

| bits | meaning |
|---:|---|
| 7-4 | the envelope shape a retrigger stream restarts |
| 3-0 | what `START_PCM_PREEMPT` preempts: bit `c` marks timer channel `c`, whose timer must be stopped first |

The shape is **one per frame, not one per channel**. The chip has a single
envelope generator, so two retrigger streams cannot hold different shapes:
a player that let each channel restart its own would give one generator two
shapes at two rates.

Carrying the shape rather than deriving it keeps the source format out of
the player. A toggle stream's volume and a PCM stream's sample number are
read off the voice's own register stream (§3.2); a shape belongs to no voice
— any number of voices may follow the one generator — so where a source
files it varies by source, and the writer resolves it into X.
The format carries no flag, shadow or priming for it: a tune that arms a
retrigger stream before setting any shape carries whatever its source
defaults to — `0` for a YM dump.

### 2.3 T — the channel-to-timer map

One byte, two bits per channel:

| value | timer |
|---:|---|
| 0 | Timer A |
| 1 | Timer B |
| 2 | Timer C |
| 3 | Timer D |

Channel 0 in bits 1-0, channel 1 in bits 3-2, and so on.

Frame 0's byte is the map in force when the player claims timers: each
flagged channel (§1.2) gets the timer its two bits name, and flagged
channels name **distinct** timers. The byte is read every frame, and a
changed byte is in force before that frame's actions (§7). A change stops no
timer, so a writer moves only channels that run nothing, and only among the
timers claimed at frame 0 — the others stayed the host's. A tune that never
re-assigns repeats the byte, which packs to nothing.

Timer C is the Atari ST's 200 Hz system clock. A tune that takes it stops
that clock while it plays, and cannot be driven from a Timer C interrupt.

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
- **low** (bits 2-0) — the MFP prescaler index for any verb that programs a
  timer, or a set of flags for `HOLD` and `RESUME`. A programming verb's
  index is 1 to 7: index 0 is the MFP's stopped state (§5), and no verb
  carries it.

`P` is the MFP timer count for any verb that programs or reloads a timer.

---

## 3. The verbs

| # | verb | what it does |
|---:|---|---|
| 0 | `RESUME` | a masked toggle stream comes back. Flags: 1 = reload the count, 2 = reload the volume. Its phase ran on through the gap |
| 1 | `HOLD` | a running stream's upkeep. Flags: 1 = reload the count from P, 2 = track the toggle stream's volume, 4 = track the retrigger stream's shape. Emitted only on frames where a value changed |
| 2 | `RELEASE` | stop this channel's timer. Bit 0 set masks the interrupt instead, leaving the timer counting |
| 3 | `START_TOGGLE` | start a toggle stream: select, volume, vector := the loud half, then a full program |
| 4 | `RETUNE` | a new rate for a running stream, keeping its place in the cycle. See below |
| 5 | `START_RETRIGGER` | start a retrigger stream: shape, vector := the retrigger tick, then a full program |
| 6 | `START_PCM` | a trigger, fresh or repeated: sample table lookup, select, vector, full program |
| 7 | `START_PCM_PREEMPT` | as `START_PCM`, but first stop the timer of every channel marked in X's low nibble — the stops come first, straight-line |

What each verb consumes beyond its own action byte:

| verb | P | X | the voice's register byte |
|---|---|---|---|
| `RESUME` | with flag 1 | — | the volume, with flag 2 |
| `HOLD` | with flag 1 | the shape, with flag 4 | the volume, with flag 2 |
| `RELEASE` | — | — | — |
| `START_TOGGLE` | yes | — | the volume |
| `RETUNE` to a voice | yes | — | the volume |
| `RETUNE` to voice 3 | yes | — | — |
| `START_RETRIGGER` | yes | the shape | — |
| `START_PCM` | yes | — | the sample number |
| `START_PCM_PREEMPT` | yes | the channels to stop | the sample number |

### 3.1 The two forms of RETUNE

`RETUNE` never touches the timer's vector, so whatever the tick handler was
doing it goes on doing.

**Addressed to a voice (0-2)** it is the ordinary retune: the volume is
repatched from the voice's register byte, then the timer is stopped, loaded
and run again. The period in flight is truncated.

**Addressed to voice 3** — no such voice — it is the **live retune**: the
timer's nibble in the control register is replaced in a single write that
never lets it pass through zero, and the reload is written to the data
register with the timer still running, so the MFP takes it at the next
underflow. The period in flight runs to its own end. A toggle stream keeps
both its phase and its place inside the half it is in.

Naming no voice, the live form repatches nothing, so a writer may emit it
only on a frame where the stream's parameter — a toggle stream's volume, a
retrigger stream's shape — did not move. Where the parameter moved on the
same frame, the ordinary form is the correct encoding, and the period in
flight is truncated.

### 3.2 Where a stream's parameter comes from

A **toggle stream's volume** and a **PCM stream's sample number** are read
by the player out of the voice's own register stream — this frame's byte of
R8+v, which the voice's skip bit keeps from the chip (§2.1). They cost no
stream of their own, and they belong to the voice the timer stream took
over.

A **retrigger stream's shape** does not belong to a voice — it belongs to
the one envelope generator — so it is carried in X.

### 3.3 Phase

A toggle stream that went away and comes back — a released note, or one whose
voice a PCM stream took — always re-enters through `START_TOGGLE`, which
restarts it at phase zero: the player writes the voice silent, and the first
tick, one timer period later, plays the loud half.

Free-running phase belongs only to a held code and its retunes. `RETUNE` is
the held prescaler-slide and nothing else.

`RESUME` exists for the alternative gap model, in which a release only masks
the timer interrupt and the timer keeps counting, so a re-arrival resumes the
toggle stream where it got to. Which model applies is not recoverable from a
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

The **code byte** does not appear in the file — the compiled script does. It
is the intermediate a writer compiles the script from, one byte per channel
per frame, and it is specified so that two writers reading different source
formats compile the same codes to the same script:

```
  7 6     5 4      3      2 1 0
 +-----+ +-----+ +---+ +-------+
 |kind | |vc+1 | | t | |prescal|
 +-----+ +-----+ +---+ +-------+
```

- **kind** — as above.
- **voice + 1** — so a zero voice field means an idle channel, and a zero
  byte means nothing.
- **t** (bit 3) — free for the writer. A source whose triggers are events
  rather than repeated codes flips it on every trigger, so two triggers of
  one sample at one rate are two different bytes and the compiled script
  starts the sample twice. A source with no such signal leaves it zero, and
  every change of a code is then a trigger.
- **prescaler** — the MFP prescaler index, which the count byte finishes.

---

## 5. Rates

A timer stream's rate is the MFP's own clock divided twice:

```
rate = 2457600 / prescaler[index] / count
```

| index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| divider | — | 4 | 10 | 16 | 50 | 64 | 100 | 200 |

The index is also the value a delay-mode MFP timer's control register runs
at, so a player programs it unchanged.

Index 0 is not a divider but the MFP's **stopped** state, so a code that
selects it starts nothing. A count of 0 is not: the MFP reads it as 256, the
slowest tick a prescaler gives.

The encodable range is 48 Hz — prescaler 200, count 0, which is 256 — to
614,400 Hz, prescaler 4 and count 1. What a writer emits is narrower: how
much of the machine a rate may cost is a packing-time rule, not a format
limit.

---

## 6. The sample table

At the byte offset in the header — on a long boundary — `count` entries of
**8 bytes**:

| offset | size | field |
|---:|---:|---|
| 0 | 4 | byte offset of the sample's first byte |
| 4 | 2 | length in bytes |
| 6 | 2 | loop point, or `$FFFF` for one-shot |

Sample bytes are volume levels 0-15, as the YM2149 takes them, followed by
one **end marker**: `$80`. A PCM tick plays a byte, steps its own pointer,
and ends the sample on a byte with bit 7 set — nothing is counted and
nothing compared per tick. The end tick has already written the marker to
the volume register by the time it tests it, and `$80`'s level bits are
zero, so the marker plays as one tick of silence.

The **loop point** is a position in the sample, not an address. On the end
marker, a tick whose loop point is not `$FFFF` moves that position's address
into its own pointer and plays on, so a loop costs one tick of silence at
the seam. A one-shot stops its timer. `0` is a real loop point —
the sample that repeats whole — so the not-looping value must be one no
length can reach.

A sample number rides a voice's five-bit volume register (§3.2), so a file
may carry at most **32** samples.

---

## 7. The frame

A player's one call per frame does this, in this order:

1. **Apply the skips.** If M's bit 4 is set, skip the voices marked by bits
   7-5 and restore the rest.
2. **The frame write** — fourteen register writes, R0 through R13, each
   value this frame's byte of its stream. A skipped voice's volume register
   is left out; so is R13 on a frame carrying `$FF`; and R7 goes out with
   the host's own port bits in 7-6 (§2). This leaves the frame's start at a
   fixed offset whatever the frame's script costs.
3. **The script's actions** — for each channel M marks, in channel order,
   decode its A byte and run the verb. A changed T byte was applied before
   the first of them (§2.3).
4. **One refill** — decode `C` values into one stream's ring: stream
   `k` on the frame where the frame number modulo `C` is `k`. A `k` past
   the streams the tune decodes (§1.5) has nothing to refill, and those
   frames decode nothing.

Before frame 0 the player decodes one group of every stream — `C` values,
or `O` when the tune is shorter and plays once — so frame 0's values are in
the rings before the first call.

Actions come *after* the frame write so their varying cost cannot jitter the
register writes. The skips come *before* it so that a voice released back
this frame gets its own volume written in the frame the source put it in.

Selecting a register and writing it are two bus cycles, and an interrupt
landing between them would send the value to whatever register the interrupt
selected. A single `movep.w` cannot be split by a 68000, which makes masking
interrupts across the frame write optional rather than necessary.

---

## 8. Starting over

A packed stream can only be restarted from its beginning, so a tune whose
flag bit 0 is set plays its frames again from frame 0. When a section runs
out mid-refill the player opens that stream's section again — a fresh decoder
writing on into the same ring — so the rings hold one continuous sequence and
nothing on the read side changes when it happens. Nothing requires `O` to
fall on a group boundary.

On the frame that ends the tune, and after that frame's write, actions and
refill: every timer a flag claims is stopped, its vector goes back to the
entry that does nothing, its interrupt is enabled with no tick of it pending,
and all three skip bits are cleared. That is the state a player is in before
frame 0 the first time, so the second pass runs exactly as the first.

---

## 9. Conformance

### 9.1 What a player checks

- the magic is `'YMX!'`;
- the version is 1;
- the stream count is 25 — it is fixed, not a size to adapt to;
- every section that is a container carries an ST4 signature matching the
  unit size the player was built for — a tune packed for a different one is
  rejected, not garbled. A stored section has no signature to check.

Beyond that a player checks nothing — §9.3 has the rules that go unchecked
— and a malformed file is undefined behaviour.

### 9.2 Interpreted data

Fourteen streams are copied: each value reaches its register unchanged. The
rest is interpreted — a byte whose meaning is an operation rather than a
value. Those operations, in full:

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

No player checks these rules; a file that breaks one is undefined behaviour
(§9.1). They are gathered here, from the sections that state them, so a
writer has one list.

The shape:

- `O` is at least 1, and `N` and `C` follow §1.3. At a unit size above 1,
  `O` and `C` are whole units.
- All twenty-five sections are present, and each decodes to exactly `O`
  values, one byte per frame. No back-reference reaches past `N` bytes and
  no operation is longer than 65535 units (§1.4).
- The sample table follows §6: at most 32 samples, each shorter than 65536
  bytes, each closed by `$80`, each loop point inside its own sample or
  `$FFFF`, and the table on a long boundary.

The values:

- A register byte carries only the bits §2 keeps. R13 carries `$FF` on
  every frame that must not restart the envelope. R7 carries bits 5-0, with
  every generator masked off a voice for as long as a PCM stream owns it.
- On every frame a timer stream owns a voice's volume register — from the
  frame it starts to the frame the voice rejoins the frame write — M keeps
  that voice's skip bit set, and the frame that changes any skip bit sets
  M's bit 4.
- On a frame that starts a PCM or toggle stream, and on any frame a verb's
  flag re-reads the parameter, the voice's register byte carries the
  operand §3 says it does.

The actions:

- At most one timer stream runs on a voice at a time; where a source starts
  two, the conflict is resolved at pack time.
- A programming verb's prescaler index is 1 to 7 (§2.4), and its count byte
  may be 0, which the MFP reads as 256 (§5).
- `RETUNE` to voice 3 is emitted only where the stream's parameter did not
  move that frame (§3.1).
- `START_PCM_PREEMPT`'s X nibble marks exactly the channels whose timers
  are running a stream this trigger silences; the plain `START_PCM` is the
  encoding when there are none.
- Stream T follows §2.3: flagged channels name distinct timers on frame 0,
  and a change moves only channels that run nothing, among the timers
  claimed at frame 0.
