# What a `.YMR` conversion costs

RhYMe's own export packed into a `.ymx`. This is what the front end changes
on the way, what it counts, and what it reports.
[../README.md](../README.md) is how to run it, [../doc/SPEC.md](../doc/SPEC.md)
the container it writes, and [../ym/CONVERSION.md](../ym/CONVERSION.md) the
same account for a YM file.

## What the conversion is

A `.YMR` and a `.ymx` are the same idea with different bookkeeping. Both
stream a YM2149 register dump past a 68000 through small rings, refilled a
byte or two per frame, so the music never exists in memory as a whole; both
give every stream a ring that is the whole of its memory; both are packed
against that ring so no back-reference can reach outside it. The lineage is
shared — a .YMR's streams are ZX1, which ST4 grew out of, and why
[`org.ymr.Zx1`](../src/main/java/org/ymr/Zx1.java) reads them
through the [vendored jx1 decoder](../src/main/java/org/jx1/README.md) rather
than a second implementation of a format that already has one.

What differs is what a frame costs. A .YMR stores one entry per CHANGE and a
command stream saying which streams each frame POPS, so a held note costs
nothing after the frame it arrives on — and no frame can be reached except by
replaying every frame before it. A `.ymx` stores one value per frame per
stream and lets ST4 find the repetition, which is why a frame is a read from
each ring and no bookkeeping. So the conversion's shape is:
[`YmrReader`](../src/main/java/org/ymr/YmrReader.java) replays the command
stream once, from the start, and hands on the flat per-frame view. The pops
become frames.

The effect vocabularies then line up one to one: both formats hang the same
three tricks off an MFP timer, and each pair is the same effect for the same
reason.

* **A RhYMe PWM is a toggle stream** — what YM calls a SID voice. Both write
  one voice's volume register from a timer interrupt at audio rate,
  alternating a level with zero, and neither touches the mixer for it, so the
  values chop the signal the voice's own generators make rather than
  replacing it. Both take the loud level from what the song last set on that
  voice: RhYMe's handler toggles between the shadow volume and zero, and the
  toggle tick reads its level out of `R(8+voice)`. Same effect, same
  parameter, same place — which is why the converter writes nothing for a PWM.
* **A RhYMe Sample is a PCM stream** — a digidrum. Both walk a block of 4-bit
  levels into the voice's volume register, one byte a tick, at the rate the
  timer is programmed to, and both hand the register back to the song when
  the block runs out. RhYMe's exporter has already folded its samples down to
  the levels the PSG's volume register takes, which is exactly what a YMX
  sample table holds, so the bytes cross unchanged: they need a table entry
  and an end marker and nothing else. A YM digidrum arrives 8-bit and has to
  be folded; this is the one thing a .YMR hands over that needs no work.
* **A RhYMe RTE is a retrigger stream** — a sync-buzzer. Both rewrite R13
  from a timer interrupt, and the values say nothing: writing R13 sends the
  envelope generator back to the start of its shape, so the envelope becomes
  the waveform and the timer's rate becomes its pitch. The one difference is
  where each format FILES the shape — RhYMe in its own copy of R13, a YM dump
  in a voice's spare nibble — and the player reads neither place: the front
  end resolves it and stream X carries it. So nothing is written over a
  volume register for an RTE either.

Two more correspondences say why the register vector needs so little done to it.

**R13 and the `$FF` marker.** A .YMR frame that does not pop `envelope_shape`
must not write R13: the pop IS the retrigger, so writing the last shape again
would restart the envelope on every frame of a held note. No shape value can
mean "nothing", so the reader marks such a frame with `$FF` — and `$FF` is
what [SPEC.md](../doc/SPEC.md)'s **The frame** means by it, the value on which
the player skips the register. Two formats reached one convention from one
constraint, so the register vector is handed straight on.

**The shadow volume and the skip.** The .YMR spec suppresses the frame
write to a volume register owned by a running PWM or Sample — the value goes to
the player's shadow and never to the chip, so the frame write cannot contend
with the effect's own timer-rate writes — and says nothing of the sort about an
RTE, which writes R13 and leaves the voice's volume to the song. That is the
`.ymx` skip exactly: M's skip bits, one per voice, which a toggle arm
and a PCM arm set and a retrigger arm does not. It also determines which of a
stream's parameters can ride in a register that already means something. A PCM stream's sample number can sit in the volume byte because the
write is skipped — `ymx_skips` has overwritten it with two `nop`s,
so it does not reach the chip. A retrigger stream's shape cannot: an RTE
leaves the voice's volume to the song, so that byte is delivered, and a shape
hidden in its low nibble would cost the voice its level on any frame not
already following the envelope. So the shape is carried instead — see
**Where a retrigger stream's shape comes from** — and rides in X for every
source; the only parameter this conversion writes is a PCM stream's sample
number.

One engine reads both dialects, and four flags say which. A held PCM code
does not retrigger, because a .YMR's trigger is a pop and not the code's
continued presence — that stops a sustained sample being chopped into
frame-long pieces, where a YM dump's held drum code fires again every frame.
A voice playing a sample keeps its mixer bits, because RhYMe's player never
touches R7 for an effect: the mixer is the song's, and a song needing its
sample clean has already disconnected the voice itself, where a YM drum's
voice is forced off the mixer for it. A channel's own commands end the
sample running on it — an effect pop of 0 stops the timer, an effect pop of
anything else reprograms the one timer the sample was ticking on — where in a
YM dump nothing ends a sample but its own marker tick. And a rate pop that
moves the prescaler reprograms a running timer instead of restarting it, where
a YM dump has no live reprogram to be faithful to — see **Moving a prescaler
under a running timer**. The one thing that
needed inventing is the other half of "a pop is an event": the script acts
where a code byte CHANGES, so bit 3 of the code is flipped on every sample
trigger, and two pops of one sample at one rate become two different codes
and two starts.

<!-- The figures in this section are re-measured by the rig (ymx/test/rig.sh),
     which reads them back out of these sentences: keep the shape of them. -->
`ymr/test/deeper.ymr` is 9,984 frames at 50 Hz with a PWM on voice A, a
sync-buzzer on voice B and a PWM on voice C — three effects at once, which two
fixed channels could not have carried, and which is the case the four-channel
generalisation and the T stream were built for. Packed with the default shape,

```sh
java -ea -cp target/classes org.ymr.Ymr -f ymr/test/deeper.ymr doc.ymx
```

reports 249,600 bytes of register and script data packed into 11,348 (4.5%) in
a 12,656-byte file, 25 rings of 960 bytes, decoding 23 of the 25 streams so
that one of the default `C`=24's slots is idle. 4,860 of those 11,348 packed
bytes are the eleven script streams, which the `.YMR` — 10,488 bytes — does
not carry:
RhYMe's player reconciles its three timers every frame from what popped, and
this one replays decisions taken at pack time. That is the bookkeeping
difference paid in bytes, and it buys the flat frame.

### What a .ymr gives up

Everything not on this list is exact, and
[test/ymr_sweep.sh](../ymx/test/ymr_sweep.sh) says so: it replays a converted
tune on the real player and compares every write `YMX_play` makes to the sound
chip, plus which MFP timers it claimed, against its own decoder and replay of
the .YMR image. It walks 1,200 frames of a long tune, and the whole of one —
the wrap included — with `YMR_FRAME_CAP` raised;
`ymr/test/deeper.ymr` passes both. Two things it does not establish: it packs
at `-k1`, so the played frames are the .YMR's own and the padding the default
`-k2` may insert is never walked, and it does not compare what a timer was
PROGRAMMED to — that is the directed effect test's, and it is the dimension
the rate row below is about.

| What changes | What it costs | Reported |
|---|---|---|
| A sample numbered past the 32nd | the sample is dropped, and so is every trigger of it | yes |
| A sample past 65535 bytes | everything after its 65535th, so the length fits a word | yes |
| A sample looped from past its own end | it is played once instead | yes |
| A sample byte above the 4-bit levels | masked, since bit 7 ends a PCM stream | yes |
| A rate pop that also moves the effect's parameter | the timer period in flight, truncated | no — see below |
| A sample the song stops early | one sample byte, held until the next frame | no — the window is sub-frame |
| A PWM or RTE re-configured with nothing changed | nothing is emitted | no — RhYMe's exporter cannot write one |

The four that are counted are named once per sample or once per channel
rather than reported a frame at a time, because a song 9,984 frames long can
break one rule on a thousand of them and still only be doing one thing wrong.
The three that are not divide: two there is nothing useful to say about — one
is shorter than the frame it happens in, and the other cannot arise from a
file RhYMe wrote — and the third, the rate pop that moves the effect's
parameter too, is the next paragraph's subject.

Rate pops are on almost none of these lines, because almost none of them cost
anything; **Moving a prescaler under a running timer** below has the
mechanism. Measured: on `ymr/test/deeper.ymr` the compiled
script carries 3,795 live reloads and 321 live retunes against no opcode that
stops a timer to change its rate, and `ymr/test/signals.ymr` has 339 live
retunes and 2 that stop — those 2 being the frames where the effect's
parameter moved on the same frame as the rate, which is the row.

* **Three timers bound to voices, against four channels and a map.** A .YMR
  uses Timer A, Timer B and Timer D, and the spec fixes which voice each one
  drives — A to A, B to B, D to C — so the binding is normative, not the
  converter's. A `.ymx` has four timer channels and a stream saying
  which MFP timer each runs on, so the converter writes that binding
  into T: channels 0, 1 and 2 take Timers A, B and D, and the fourth channel,
  which no .YMR fills, takes the leftover Timer C, which keeps the map a
  permutation and costs nothing — the header never flags an idle channel and
  the player claims no timer for it. So a `.ymr` tune leaves Timer C, the
  system's 200 Hz clock, alone, and does take Timer B wherever it runs an
  effect there, which the YM packer's default map keeps free for rasters.
  Three channels means the player decodes 23 streams, so `C` must be at least
  23: the default 24 clears it by one slot, and more buys headroom no `.ymr`
  can use.
* **Thirty-two samples, where a .YMR may carry 65535.** A YMX sample number
  is the five bits the script reads out of a volume register, so everything
  past the cap is dropped and a trigger of a dropped one is reported. A YMX
  sample table entry holds its length in a word, too, so anything past 65535
  bytes is cut to fit. The .YMR spec caps a sample at 65536, so a file that
  keeps to it loses exactly the one byte; nothing in the reader enforces that
  ceiling, and a file that breaks it loses the excess.
* **A PCM tick still has no compare, and loops anyway.** It walks forward and
  stops on the first byte with bit 7 set, the whole of its end
  condition and the reason it costs no compare per tick. The sample table
  carries a loop word beside each sample, `YMX_init` resolves it to an address
  once, and the tick that meets the end marker moves that address into its own
  operand instead of stopping the timer. A one-shot says so with `$FFFF`, a
  value no length can reach — 0 is a real loop point, the sample that repeats
  whole. The end tick has already written the marker as a level by the time it
  tests it, so a loop costs one sample of silence at the seam and nothing
  else; a one-shot's end path is marker byte, middle volume, timer stopped.
  The alternative — writing the loop region out again and again towards a
  ceiling — makes a long loop both wrong and enormous.
* **Moving a prescaler under a running timer.** RhYMe pops a rate on
  its own to slide a pitch: control register, then data register, the timer
  never stopped, so a running PWM keeps its phase and a running sample its
  place and only the rate moves. All of that is preserved by the conversion.
  A .YMR rate entry is a prescaler and a counter, only the prescaler is in the
  code byte, and a pop that moves the counter alone therefore leaves the code
  where it was: the script emits a HOLD carrying the reload flag, and
  `ymx_hold` writes the new count to a timer it never stops — RhYMe's own
  live reload, opcode for opcode. A pitch slide is made of these reloads,
  and they cost nothing. A pop that moves the PRESCALER cannot be encoded
  that way: it changes the code byte, and every opcode that carries a rate
  goes through `ymx_program`, which stops the timer, loads the count and
  runs it again — the period in flight truncated, whichever opcode it was.
  So it has an encoding of its own instead. The action byte's
  voice field addresses three voices in two bits, so 3 is none of them, and
  RETUNE addressed to 3 is the live rate change: `ymx_live` masks the
  timer's nibble out of the control byte it reads back, ORs the new prescaler
  in and writes it once — the timer's nibble never passes through zero — then
  writes the reload, which the MFP takes at the next underflow. Addressed to
  no voice, it repatches nothing, so it is emitted only on a frame where
  the effect's parameter — a PWM's volume, an RTE's shape — did not change. Where
  the parameter moved too, the ordinary retune is emitted and the period in
  flight is truncated as before.
* **A sample the song stops early may write one byte more.** A .YMR can end
  a sample before its data runs out — an effect pop of 0, or a different
  effect arriving on the same timer, which is the same voice, since a .YMR
  binds each timer to one — and the conversion applies it on the frame it
  appears. Where the frame returns the voice, it returns it at once: the
  timer is stopped on that frame — by a RELEASE where the song popped 0, by
  the arriving opcode's own `ymx_program` where an RTE took the channel — the
  voice stops being the sample's, and its skip lifts. The player applies a
  frame's skip bits BEFORE the
  register burst and the script's actions after it, so the frame write this
  reopens is that same frame's, and the voice's own volume is on the chip
  inside the 20 ms the song placed it in, with no skew to correct. Where the
  frame passes the voice to a PWM instead, the skip stands, because the
  square requires it shut too, and the song's volume is not due back.
  What the ordering cannot cover is the interval between the burst and the
  action: a tick landing in it writes one more sample byte over the volume
  just written, and that byte stands until the next frame. It is one wrong
  level for most of one frame, against the whole frames of a sample that
  should not be playing. No ordering of the opcodes closes it either: the
  actions sit after the burst so their varying cost cannot jitter the
  register writes, and the one byte is the cost of that ordering.

Everything else the conversion has to change, it counts and names the same way:

* an effect type in the 4-255 the spec reserves, dropped: RhYMe's own player
  falls through to its PWM path for a type it has no handler for, and a type
  read as another effect is another sound;
* a timer configured with a prescaler of 0, the MFP's stopped state, or with
  a counter of 0, which the MFP reads as 256;
* and a sample index with no block behind it.

