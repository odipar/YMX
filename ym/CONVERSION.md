# What a YM conversion costs

A `YM5!` or `YM6!` register dump packed into a `.ymx`. This is what the front
end changes on the way, what it counts, and what it reports. See
[../README.md](../README.md) for how to run it, and
[../doc/SPEC.md](../doc/SPEC.md) for the container it writes.

## What the conversion is

A YM file and a `.ymx` hold the same fourteen registers per frame. What
differs is where the *effects* live. A YM6 frame carries up to two effect
slots, each three fields spread across spare register bits:

```
slot 1:  code = R1 bits 7-4    prescaler = R6 bits 7-5    count = R14
slot 2:  code = R3 bits 7-4    prescaler = R8 bits 7-5    count = R15

code bits 5-4:  voice + 1 (00 = the slot is idle this frame)
code bits 7-6:  00 SID   01 DigiDrum   10 Sinus-SID   11 Sync-Buzzer
parameter:      in the voice's own volume register - a SID's maximum
                volume, a drum's sample number, a buzzer's envelope shape
```

YM5 encodes less, in different places: R1 bits 5-4 give a SID voice, R3 bits
5-4 a digidrum voice, and a drum's prescaler is always in R8, for any
voice. Both dialects come out of the front end as the same thing - a code
byte and a count byte per frame per timer channel - so the dialect is not
recoverable downstream.

The vocabulary changes here too:

* a **SID voice** is a [toggle stream](../doc/terminology.md) - a square made
  by flipping a voice's volume between a level and zero;
* a **digidrum** is a **PCM stream** - a stored sample played out through a
  voice's volume register;
* a **sync-buzzer** is a **retrigger stream** - R13 rewritten at the timer's
  rate, restarting the envelope.

The register streams come out holding what the chip receives: the
effect bits are stripped, and R7 arrives with the voices a sample owns
already disconnected.

A YM frame starts at most two effects, so a YM tune uses two of the format's
four timer channels - the packer's default puts them on Timers A and D - and
the other two channels' streams repeat one value and compress to almost
nothing, and the base count leaves them outside the streams a player
decodes. Timer B and Timer C stay the host's.

## What a YM file gives up

| What changes | What it costs | Reported |
|---|---|---|
| A Sinus-SID code | the effect is dropped to idle: nothing plays | yes |
| A code with a prescaler or count of 0 | dropped to idle: prescaler 0 is the MFP's stopped state, a count of 0 is 256, and neither is armed here | yes |
| A SID or buzzer rate above what a real machine can run | dropped to idle | yes |
| A drum number with no sample behind it | dropped to idle | yes |
| A drum above the rate ceiling, when every trigger takes the exact ratio | bandwidth only: the sample is resampled and each divisor scaled by that ratio | yes |
| A drum above the rate ceiling, when its triggers cannot all take that ratio | the sample halves by a power of two, and a trigger the halved divisor cannot express is dropped to idle | yes |
| A tune whose length is not a whole unit | up to unit-1 duplicated safe frames, inaudible | yes |
| A loop frame the wrap cannot enter | the repeat starts at the next frame it can, or at frame 0 where no frame within a second can be entered | yes |
| A loop frame further from the end than a ring holds | the rings grow to hold the frames between | yes |
| A loop frame further from the end than the largest ring holds | the repeat starts at the first later frame within a second that a ring reaches back over | yes |
| The same, with no later frame the budget reaches | every stream is packed as two sections, which costs file bytes | yes |
| A loop frame with no unit boundary in reach, packing at 2 or 4 bytes a unit | the tune starts over from frame 0, so its opening is heard on every pass | yes |
| The SID gap model | a choice the file cannot record - see below | no |

The four drop counters are counts of YM effects the front end normalised
away. Three of them are drops the reference player makes too, so those are
faithful rather than lossy. The rate ceiling is this converter's own: an
8 MHz 68000 spends a quarter of itself on a 25,600 Hz interrupt, and a code
above it is dropped here.

* **Sinus-SID.** ST-Sound, the format author's own player, reads the effect
  code and runs an empty handler. The packer warns and drops it.

* **A drum above the rate ceiling is rescued, not dropped.** The sample is
  resampled to the highest MFP-representable rate under the ceiling,
  through the chip's volume curve and with a windowed-sinc filter, so no
  aliased fold-back brightens it. Every trigger of that drum has its timer
  divisor scaled by the same exact ratio, so **pitch stays what the dump
  specified, and duration to within one output sample of it**, and only
  bandwidth falls, by as little as the ceiling allows. A 29 kHz
  conversion-family drum lands at 25.6 kHz, not at the old half-rate 14.6.
  Where a drum's triggers cannot all take the exact ratio, a power-of-two
  factor is the fallback, and a trigger whose scaled divisor no prescaler
  and count pair represents is dropped to idle. No file in `test` or in
  the corpus takes that path: every drum rescued there takes the ratio.
  `-drumhz` moves the ceiling; each rescue is reported.

* **Drum samples become PSG-ready volume values** - the high nibble of an
  8-bit sample, or the low nibble for a 4-bit file. That is exactly the
  real-hardware mapping in the reference player's source, not a choice made
  here.

* **Padding to whole units.** At `-k2` or `-k4` the tune length and `C` must
  be whole units, because a padded section would decode one extra value into
  the ring and it would be played. A tune with an odd length is padded by
  duplicating a frame that neither writes R13 nor triggers a drum - the chip
  state is held one inaudible tick longer. Where no safe frame exists in the
  last 64, a packer that chose the unit itself drops to `-k1` and says so; an
  explicit `-kK` stops with the length it cannot pack.

* **A tune starts over from the frame its header gives.** A YM header gives
  the frame its own player went back to, and 99 of the corpus's 543 readable
  files give one other than 0 - on those, the opening that played once under
  the header is 47% of the tune on average. The file carries that frame as
  `L` and the player goes back to it, on two conditions.

  The first is the state at that frame. Every claimed timer is stopped and
  every skip bit cleared at the end of a pass, so frame `L` is entered with
  nothing carried in, and a frame that reads state an earlier frame set plays
  differently on the second pass than on the first. Frame `L` is clear of
  that when three things hold: every timer stream running there starts there,
  every skip bit set there is set by that frame's own `M`, and no voice is on
  the envelope generator before the next frame that writes R13. Where the
  header's frame fails one of them, the packer takes the next frame that
  holds all three, up to 1 second later.

  The second is how the player gets back to it. A wrap that moves the read
  position in every ring back by the length of a pass reaches only as far as
  a ring holds, so the frames from `L` to the end have to fit one. Where they
  do not, the rings grow to the smallest multiple of `C` that holds them,
  which costs workspace and no file bytes.

  Where the largest ring the format allows still will not hold them, the
  packer takes the first later frame within the budget that a ring does
  reach. Where the budget holds none, every stream is packed as two
  sections instead - the frames before `L`, then the frames from `L` on -
  and the player opens the second at the wrap. That one costs file bytes,
  since the replayed frames are packed on their own and no match reaches
  across the cut: on the six tunes in `test` that take it, the file is
  1 to 41 per cent larger than the same tune packed with `-l0` at the same
  unit size. The rings stay the size they were.

  A section is a whole number of units, so at `-k2` or `-k4` the cut falls on
  a unit boundary: where no frame near `L` that the wrap can enter falls on
  one, the file carries 0 and the tune starts over from its first frame, as
  every file before format version 0.5 did.

  Each conversion says what it did with the frame. `-lF` gives a frame of its
  own, and `-l0` starts the tune over from the beginning.

* **Samples never loop.** A YM file has no field for a repeating digidrum,
  so every sample comes across marked one-shot. This costs nothing -
  nothing in a YM dump needs it - but it is the one
  [format](../doc/SPEC.md#6-the-sample-table) feature a YM tune cannot reach.

* **A rate change is always a stop, load and run.** The format has a live
  retune that reprograms a timer without stopping it, and a YM tune never
  uses it. That is not a limit of the conversion: a YM file records a code
  sitting in a register, not the moment a player reprogrammed anything, so
  there is no live reprogram to be faithful to. How a period in flight ends
  is not in the file, and the reference player stopped the timer.

* **The SID gap model is a choice, not a fact.** When a square goes away and
  comes back, does it restart at phase zero or resume where it got to? The YM
  format never specified it, every player renders it differently, and each
  composer heard their own driver's rendering. The default is the ym2149-rs
  model - a gap restarts at phase zero, silence first - and `-sidresume`
  selects maxYMiser's, where a release only masks the interrupt and the
  timer keeps counting. Both are ordinary stream opcodes the player always
  carries, so the choice is per tune rather than per build.
  [../doc/experiments.md](../doc/experiments.md) has the survey.

* **A sync-buzzer's shape comes out of the voice's nibble.** YM6 files the
  envelope shape in the volume register of the voice the buzzer runs on,
  because the parameter field sits at one place for all three kinds and a
  buzzer's voice, following the envelope, leaves that nibble spare. The front
  end resolves it and writes the number into stream X; the player reads it
  there. Before the first buzzer and the first R13 write, X carries 0, a YM
  dump's own default; from the frame a buzzer is armed, X carries that voice's
  nibble, whether or not R13 was ever written. See
  [SPEC.md](../doc/SPEC.md#22-x---the-spare-operands).

## What it does not do

* **YM2's drums.** Mad Max's forty samples are held in the player, not in the
  file; supporting them means embedding the bank in the converter. Not yet.
* **YM2, YM3 and YM4 files.** The reader takes `YM5!` and `YM6!` only, and
  reports which it found. An LHA-archived `.ym` it does read: the wrapper is
  unpacked first, and every file in `test` is one. What the rest costs is
  1 file of the collection's 544, a `YM3!`; the other 543 read. The
  collection holds no `YM2!`, so the sample bank a YM2 conversion
  would have to carry - Mad Max's forty, held in that player rather than
  in the file - would serve nothing that is here to convert.
