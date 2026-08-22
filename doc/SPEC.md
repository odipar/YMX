# The YMX format — specification

Version 1. Big-endian throughout.

YMX is a streaming register-dump format for the YM2149 sound chip as fitted
to the Atari ST, designed so a plain 68000 can play a tune it never holds in
memory. A file carries twenty-five independently compressed streams — fourteen
for the chip's sound registers, one value per frame, and eleven for a
**compiled effect script** that drives the MFP's timers. Twenty-five is the
count every file stores; how many a given tune fills, and how many a player
reads, are smaller and are §1.5. Each stream is
decoded through its own small ring, refilled one stream per frame, so the
memory a tune needs is a property of the player's configuration rather than
of the tune's length.

It extends the YM lineage — YM3, YM4, YM5, YM6 — in one respect that matters:
what those formats call a "special effect" is a value the *player* had to
re-derive every frame from bits smuggled into spare register fields. YMX
resolves all of that at pack time and writes down the outcome, so the player
compares nothing.

The layout described here is the one the `.yx6` container reached over ten
revisions inside the [ST4](https://github.com/odipar/ST4) repository, adopted
whole and renumbered to 1. There is no older YMX version to stay compatible
with.

**Vocabulary.** This document uses [terminology.md](terminology.md)
throughout. The four words it leans on hardest:

| word | meaning |
|---|---|
| **frame** | one call of the player; the tune's clock, typically 50 Hz |
| **stream** | a series of values arriving at one destination |
| **frame stream** | a stream delivering one value per frame — the fourteen sound registers |
| **timer stream** | a stream delivering values *between* frames, at a rate an MFP timer sets |
| **section** | one ST4 container holding a stream's intro or its loop |

---

## 1. The container

```
+--------------------------------+
| header, 34 bytes fixed         |
| intro section table, 4 x S     |
| loop  section table, 4 x S     |
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
| 20 | 4 | `L`, the loop frame; equal to `O` when the tune plays once |
| 24 | 4 | the YM2149's master clock in Hz (informational) |
| 28 | 4 | byte offset of the sample table; 0 when there are none |
| 32 | 2 | sample count |
| 34 | 4·S | byte offset of each **intro** section, covering frames `[0, L)` |
| 134 | 4·S | byte offset of each **loop** section, covering frames `[L, O)` |

With `S` fixed at 25 the header is **234 bytes**, and everything after it is
body: the sections (§1.4), then the sample table (§6). Nothing in the body is
found by position — a section through its offset in one of the two tables
above, the samples through the offset at byte 28.

### 1.2 Flags

| bit | meaning |
|---:|---|
| 0 | the tune loops |
| 1 | the tune uses timer channel 0 |
| 2 | timer channel 1 |
| 3 | timer channel 2 |
| 4 | timer channel 3 |
| 5-15 | reserved, written as 0 |

A player claims an MFP timer only for a channel whose flag is set, so the
timers a tune does not need stay the host's.

### 1.3 `N` and `C`

`N` is the ring size, one ring per stream, and is what a back-reference may
not reach past — every section is packed with that bound. `N` is capped at
**2520**, because a player may read register `k`'s ring through an
assembled-in displacement of `k·N` and `13·N` must fit a signed 16-bit
displacement.

`C` is how many values are decoded per call. It must

- be at least one refill slot per stream the tune actually **decodes** —
  17 when it names no timer channel, then 19, 21, 23 or 25 by the *highest*
  channel it names, since a player stops at the last channel rather than
  counting them; and
- divide `N`, which is what lets a player use a counted-wrap ring decoder
  rather than a general one.

960/24 is the usual pair and covers every tune that leaves channel 3 idle.

### 1.4 The sections

Each section is a complete, standard ST4 container — its own twenty-byte
header, then its four streams — packed at unit size 1 and placed on a long
boundary, so the container's own alignment rules hold where it sits. A player
opens one with the eight-instruction sequence ST4.S documents, and `dst4`
unpacks any section straight out of a `.ymx` for debugging.

Every stream is packed twice, because restarting it at the loop frame means
starting a decoder over: the intro section covers `[0, L)`, the loop section
covers `[L, O)`, and the player starts the loop section again every time round
(§8). The packer writes all twenty-five intro sections in stream order and
then all twenty-five loop sections, but nothing depends on that — a section is
reached only through its offset.

A section offset of 0 means the section is not present. A tune that loops from
frame 0 has no intro sections at all; a tune that does not loop has no loop
sections. Both halves of the table are always written, and the absent half is
zero.

Packed sizes are not stored. ST4 counts output units rather than input bytes,
so a decoder never needs them.

### 1.5 Twenty-five is the table, not the traffic

`S` is fixed. It is always 25, a reader must reject a file that says
otherwise, and the two section tables are therefore always 100 bytes each,
which is what puts the payload at a constant offset. Three different counts
get called "streams", and only the first is `S`:

| | |
|---|---|
| **stored** — always 25 | the size of the section tables. Absent sections have offset 0 |
| **decoded** — 17, 19, 21, 23 or 25 | what a player reads per cycle: 17 for a tune naming no timer channel, then two more per channel, up to and including the **highest** channel it names |
| **carrying** — fewer still | an idle channel's pair is stored, and packs to almost nothing |

The middle one is what `C` has to cover, and it steps by the highest channel
named rather than by the count of channels used, because a player stops at
the last channel rather than counting them. A tune using only channel 3 still
decodes 25.

So a `.ymx` always has twenty-five slots and rarely twenty-five streams worth
reading. The channel pairs come last precisely so the difference costs
nothing: what a tune does not name is a tail no player ever touches.

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

Register streams hold exactly what the chip should see: the spare bits older
YM formats smuggled effect fields into are stripped at pack time. R7 arrives
with the disconnection of sample-playing voices already applied.

### 2.1 M — the master byte

`0` means nothing anywhere this frame, which is the common case and costs one
test.

| bits | meaning |
|---:|---|
| 0 | timer channel 0 acts — read its A, and its P if the verb takes one |
| 1 | timer channel 1 acts |
| 2 | timer channel 2 acts |
| 3 | timer channel 3 acts |
| 4 | bits 7-5 are meaningful this frame — apply them |
| 7-5 | one bit per voice A, B, C — a set bit **gates** that voice |

To **gate** a voice is to leave its volume register out of the frame write:
the player patches that one write into two `nop`s, so the frame's value never
reaches the chip. It does not silence the voice — a timer stream owns the
register meanwhile and writes it at its own rate.

Bits 7-5 are **state, not an edge**: re-asserting the same value is
idempotent. Bit 4 says those bits are meaningful this frame; without it the
three gates stand as they were.

The skipped value stays in the ring, untouched and unsanitized: nothing edits
a ring at runtime, and the gate is a patched instruction rather than a test
the frame write has to make.

### 2.2 X — the spare operands

| bits | meaning |
|---:|---|
| 7-4 | the envelope shape a retrigger stream restarts |
| 3-0 | what `START_PCM_PREEMPT` preempts: a bit per timer channel whose timer must be stopped first |

The shape is **one per frame, not one per channel**. The chip has a single
envelope generator, so two retrigger streams cannot hold different shapes:
a player that let each channel restart its own would give one generator two
shapes at two rates.

Carrying it rather than deriving it is what keeps the source format out of
the player entirely. A toggle stream's volume and a PCM stream's sample
number are read off the voice's own register ring, and that is right:
both belong to the voice the effect took over, and the level a square chops
is the level the tune put in that register. A shape belongs to nothing of the
kind — any number of voices may follow the one generator — so where a source
files it is an accident of that source. A YM file uses the nibble of the
voice the buzzer names, because the parameter field sits at one place for all
three kinds and a buzzer's voice, following the envelope, leaves that nibble
spare. A `.YMR` uses R13, where the chip keeps it. The front end resolves
which, and simply writes the number down.

So the player does not look for it at all: `ymx_shape` is five instructions
with no branch, there is no header flag, no shadow and no priming. A tune
that arms a retrigger stream before it has set any shape carries whatever its
own format defaults to — `$08` for a `.YMR`, `0` for a YM dump — because
that is a fact of the source, resolved at the front end.

### 2.3 T — the channel-to-timer map

One byte, two bits per channel:

| value | timer |
|---:|---|
| 0 | Timer A |
| 1 | Timer B |
| 2 | Timer C |
| 3 | Timer D |

Channel 0 in bits 1-0, channel 1 in bits 3-2, and so on. A tune that never
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
- **voice** (bits 4-3) — 0, 1, 2 for voices A, B, C. There are three voices
  in a two-bit field, so **3 names no voice**; see `RETUNE` in §3.
- **low** (bits 2-0) — the MFP prescaler index for any verb that programs a
  timer, or a set of flags for `HOLD` and `RESUME`.

`P` is the MFP timer count for any action that programs or reloads a timer.

Bytes on frames where a channel is not consumed are unspecified. An encoder
should repeat the previous byte, which the event optimizer then packs away to
nothing.

---

## 3. The verbs

| # | verb | what it does |
|---:|---|---|
| 0 | `RESUME` | a masked toggle stream comes back. Flags: 1 = reload the count, 2 = reload the volume. The square's phase ran on through the gap |
| 1 | `HOLD` | a running stream's upkeep. Flags: 1 = reload the count from P, 2 = track the toggle stream's volume, 4 = track the retrigger stream's shape. Emitted only on frames where a value actually changed |
| 2 | `RELEASE` | stop this channel's timer. Bit 0 set masks the interrupt instead, leaving the counter running |
| 3 | `START_TOGGLE` | start a toggle stream: select, volume, vector := the loud half, then a full program |
| 4 | `RETUNE` | a new rate for a running stream, keeping its place in the cycle. See below |
| 5 | `START_RETRIGGER` | start a retrigger stream: shape, vector := the retrigger tick, then a full program |
| 6 | `START_PCM` | a trigger, fresh or repeated: sample table lookup, select, vector, full program |
| 7 | `START_PCM_PREEMPT` | as `START_PCM`, but first stop the timer of every channel named in X's low nibble — the stops come first, straight-line |

### 3.1 The two forms of RETUNE

`RETUNE` never touches the timer's vector, so whatever the tick handler was
doing it goes on doing.

**Addressed to a voice (0-2)** it is the ordinary retune: the volume is
repatched from the voice's ring byte, then the timer is stopped, loaded and
run again. The period in flight is truncated.

**Addressed to voice 3** — no such voice — it is the **live retune**: the
timer's nibble in the control register is replaced in a single write that
never lets it pass through zero, and the reload is written to the data
register with the timer still running, so the MFP takes it at the next
underflow. The period in flight runs to its own end. A square keeps both its
phase and its place inside the half it is in.

Naming no voice, the live form repatches nothing, so an encoder may emit it
only on a frame where the stream's parameter — a toggle stream's volume, a
retrigger stream's shape — did not move. Where the parameter moved on the
same frame, the ordinary form is the correct encoding and the truncated
period is the price.

### 3.2 Where a stream's parameter comes from

A **toggle stream's volume** and a **PCM stream's sample number** are read
by the player out of the voice's own register ring. They cost no stream and
they belong to the voice the effect took over.

A **retrigger stream's shape** does not belong to a voice — it belongs to the
one envelope generator — so it is carried in X.

### 3.3 Phase

A toggle stream that went away and comes back — a released note, or one whose
voice a PCM stream took — always re-enters through `START_TOGGLE`, which
restarts the square at phase zero: the player writes the voice silent, and
the first tick, one timer period later, plays the loud half.

Free-running phase belongs only to a held code and its retunes. `RETUNE` is
the held prescaler-slide and nothing else.

`RESUME` exists for the alternative gap model, in which a release only masks
the timer interrupt and the counter keeps counting, so a re-arrival resumes
the square where it got to. Which model applies is not recoverable from a YM
file and is chosen at pack time.

---

## 4. Timer streams and the code byte

The four **kinds** of timer stream, as a front end names them for the script
compiler:

| bits 7-6 | kind | what it is |
|---:|---|---|
| `00` | **toggle stream** | a square wave made by flipping a voice's volume between a level and zero — a "SID voice" |
| `01` | **PCM stream** | a stored sample played out through a voice's volume register — a "digidrum" |
| `10` | **wave stream** | a table read out the same way. Never seen in a dump; reserved |
| `11` | **retrigger stream** | R13 rewritten at the timer's rate, restarting the envelope — a "sync-buzzer" |

The **code byte** is the front end's interface to the script compiler. It
does not appear in the file — the compiled script does — but it is the ABI
both front ends write and the compiler reads:

```
  7 6     5 4      3      2 1 0
 +-----+ +-----+ +---+ +-------+
 |kind | |vc+1 | | t | |prescal|
 +-----+ +-----+ +---+ +-------+
```

- **kind** — as above.
- **voice + 1** — so a zero voice field means an idle channel, and a zero
  byte means nothing at all.
- **t** (bit 3) — free for the front end. A source whose triggers are events
  rather than repeated codes flips it on every trigger, so two triggers of
  one sample at one rate are two different bytes and the compiler starts the
  sample twice. A source with no such signal leaves it zero, and every change
  of a code is then a trigger.
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

Index 0 is not a divider but the MFP's **stopped** state, so a code naming it
starts nothing. A count of 0 is likewise stopped.

The reachable range is roughly 48 Hz to 25,600 Hz.

---

## 6. The sample table

At the byte offset in the header, `count` entries of **8 bytes**:

| offset | size | field |
|---:|---:|---|
| 0 | 4 | byte offset of the sample's first byte |
| 4 | 2 | length in bytes |
| 6 | 2 | loop point, or `$FFFF` for one-shot |

Sample bytes are PSG-ready volume levels, 0-15, followed by one **end marker**
with bit 7 set. A PCM tick plays a byte, steps its own pointer, and stops on
the marker — there is no counter and no compare per tick.

The **loop point** is a position in the sample, not an address. On meeting the
end marker a tick whose loop point is not `$FFFF` moves that position's
address into its own pointer and plays on; a one-shot stops its timer. `0` is
a real loop point — the sample that repeats whole — which is why the
not-looping value has to be one no length can reach.

The end tick has already written the marker as a level by the time it tests
it, so a loop costs one sample of silence at the seam.

A sample number is five bits wide where a front end reads it out of a volume
register, so a file may carry at most **32** samples.

---

## 7. The frame

A player's one call per frame does this, in this order:

1. **Apply the gates.** If M's bit 4 is set, gate the voices named by bits
   7-5 and open the rest.
2. **The frame write** — fourteen register writes, R0 through R13, taking
   each value from its ring. A gated voice's volume register is skipped. This
   leaves the frame's start at a fixed offset whatever the frame's script
   costs.
3. **The script's actions** — for each channel M names, decode its A byte and
   run the verb.
4. **One refill** — decode `C` values into exactly one stream's ring: stream
   `k` on the frame where the frame number modulo `C` is `k`.

Actions come *after* the frame write so their varying cost cannot jitter the
register writes. The gates come *before* it so that a voice released back
this frame gets its own volume written in the frame the source put it in.

Selecting a register and writing it are two bus cycles, and an interrupt
landing between them would send the value to whatever register the interrupt
selected. A single `movep.w` cannot be split by a 68000, which makes masking
interrupts across the burst optional rather than necessary.

---

## 8. Looping

A packed stream can only be restarted from its beginning, so every stream is
split at the loop frame `L`: an intro section for `[0, L)` and a loop section
for `[L, O)`. When a section runs out mid-refill the player starts that
stream's loop section over — a fresh decoder writing on into the same ring —
so the rings hold one continuous sequence and nothing on the read side
changes when it happens. Nothing requires `L` to fall on a group boundary.

`O` and `L` count **played** frames. When the effect state arriving from the
intro differs from the state arriving from the wrap, a packer rotates the
split forward to a frame where the two agree; the file then carries a few
frames twice, compiled differently. Nothing at play time distinguishes them.

---

## 9. Conformance

A reader must check:

- the magic is `'YMX!'`;
- the version is 1;
- the stream count is 25 — it is fixed, not a size to adapt to;
- every section's own ST4 signature matches the unit size the reader was
  built for — a tune packed for a different one is rejected, not garbled.

Beyond that a player checks nothing, and a malformed file is undefined
behaviour. This is deliberate: the format is a compilation target, and every
decision that could be made at pack time already was.
