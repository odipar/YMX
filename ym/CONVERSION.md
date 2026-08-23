# What a YM conversion costs

A `YM5!` or `YM6!` register dump packed into a `.ymx`. This is what the front
end changes on the way, what it counts, and what it reports.
[../README.md](../README.md) is how to run it, [../doc/SPEC.md](../doc/SPEC.md)
the container it writes, and [../ymr/CONVERSION.md](../ymr/CONVERSION.md) the
same account for a `.YMR`.

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
voice. Both dialects come out of the front end as the same thing — a code
byte and a count byte per frame per timer channel — so the dialect is not
recoverable downstream.

The vocabulary changes here too:

* a **SID voice** is a [toggle stream](../doc/terminology.md) — a square made
  by flipping a voice's volume between a level and zero;
* a **digidrum** is a **PCM stream** — a stored sample played out through a
  voice's volume register;
* a **sync-buzzer** is a **retrigger stream** — R13 rewritten at the timer's
  rate, restarting the envelope.

The register streams come out holding what the chip receives: the
effect bits are stripped, and R7 arrives with the voices a sample owns
already disconnected.

A YM frame starts at most two effects, so a YM tune uses two of the format's
four timer channels — the packer's default puts them on Timers A and D — and
the other two channels' streams pack to nothing. Timer B and Timer C stay
the host's.

## What a YM file gives up

| What changes | What it costs | Reported |
|---|---|---|
| A Sinus-SID code | the effect is dropped to idle: nothing plays | yes |
| A code with a prescaler or count of 0 | dropped to idle: prescaler 0 is the MFP's stopped state, and count 0 is 256 | yes |
| A SID or buzzer rate above what a real machine can run | dropped to idle | yes |
| A drum number with no sample behind it | dropped to idle | yes |
| A drum above the rate ceiling | bandwidth only: the sample is resampled and every trigger's divisor scaled by the same ratio | yes |
| A tune whose length is not a whole unit | one duplicated safe frame, inaudible | yes |
| A header that loops from a frame other than 0 | the tune starts over from frame 0, so its opening is heard on every pass | yes |
| The SID gap model | a choice the file cannot record — see below | no |

The four drop counters are counts of YM effects the front end normalised
away. They exist because the reference player would not have
started those codes either: dropping them makes the conversion faithful,
not lossy.

* **Sinus-SID.** Never seen in a dump, and never implemented by any player —
  the format author's included. The packer warns and drops it.

* **A drum above the rate ceiling is rescued, not dropped.** The sample is
  resampled to the highest MFP-representable rate under the ceiling, through
  the chip's volume curve and with a windowed-sinc filter, so no aliased
  fold-back brightens it. Every trigger of that drum has its timer divisor
  scaled by the same exact ratio, so **pitch and duration stay what the dump
  specified** and only bandwidth falls, by as little as the ceiling allows. A
  29 kHz conversion-family drum lands at 25.6 kHz, not at the old half-rate
  14.6. Where a drum's triggers cannot all take the exact ratio, a
  power-of-two factor is the fallback. `-drumhz` moves the ceiling; each
  rescue is a note.

* **Drum samples become PSG-ready volume values** — the high nibble of an
  8-bit sample, or the byte as-is for a 4-bit file. That is exactly the
  real-hardware mapping in the reference player's source, not a choice made
  here.

* **Padding to whole units.** At `-k2` or `-k4` the tune length and `C` must
  be whole units, because a padded section would decode one extra value into
  the ring and it would be played. A tune with an odd length is padded by
  duplicating a frame that neither writes R13 nor triggers a drum — the chip
  state is held one inaudible tick longer. Only where no safe frame exists
  near the end does the packer fall back to `-k1`, and it says so.

* **A tune starts over from its first frame.** A YM header names the frame
  its own player went back to, and 99 of the corpus's 543 readable files name
  one other than 0 — on those, the opening that played once under the header
  is 46% of the tune on average, and it is heard on every pass here. In
  exchange, each stream is one section instead of two cut at the loop frame:
  the half after the cut cannot reference the half before it, so every tune
  paid for a shape only some tunes used. Each conversion says which
  frame the header named.

* **Samples never loop.** A YM file has no field for a repeating digidrum,
  so every sample crosses marked one-shot. This costs nothing —
  nothing in a YM dump needs it — but it is the one
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
  model — a gap restarts at phase zero, silence first — and `-sidresume`
  selects maxYMiser's, where a release only masks the interrupt and the
  timer keeps counting. Both are ordinary stream verbs the player always
  carries, so the choice is per tune rather than per build.
  [../doc/experiments.md](../doc/experiments.md) has the survey.

* **A sync-buzzer's shape comes out of the voice's nibble.** YM6 files the
  envelope shape in the volume register of the voice the buzzer runs on,
  because the parameter field sits at one place for all three kinds and a
  buzzer's voice, following the envelope, leaves that nibble spare. The front
  end resolves it and writes the number into stream X; the player reads it
  there. A tune that arms a buzzer before it has written any shape carries 0,
  which is a YM dump's own default. See
  [SPEC.md](../doc/SPEC.md#22-x--the-spare-operands).

## What it does not do

* **YM2's drums.** Mad Max's forty samples are held in the player, not in the
  file; supporting them means embedding the bank in the converter. Not yet.
* **YM2, YM3 and packed `.ym` files.** The reader takes `YM5!` and `YM6!`
  only, and reports which it found.
