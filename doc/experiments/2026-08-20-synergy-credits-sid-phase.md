# The Synergy Credits hunt: a bug every instrument called correct

The v2 report was one line: "something completely broke at 0:38 - verify
with v1." What followed is the most instructive debugging session this
repo has had, because every layer of verification - the corpus sweep, a
byte-level differential, hardware-trace comparison, even a spectral audio
diff - declared the two players equivalent while a musician could hear
they were not. The fault, and each instrument's blind spot, are worth
keeping. Companion:
[SID phase semantics](2026-08-20-sid-phase-semantics.md) holds the player
survey and the final semantics.

## The symptom

Synergy Credits (a SidSound-Designer-era Synergy tune, no drums, dual
SID wobble bass) audibly wrong in v2 from 0:38 onward - the exact frame
where the tune starts re-triggering its SID bass rapidly. v1 sounded
right.

## What each instrument said, and why it was wrong

**The corpus sweep said OK.** Two blind spots. It plays only the first
1,200 frames of tunes longer than 3,000 - frame ~1900 was never swept -
and it verifies REGISTER WRITES, which were correct throughout. (The
budget still stands; know what a sweep line does and does not claim.)

**The Unicorn differential said identical.** v1 and v2 were played side
by side for 2,600 frames comparing PSG pairs, MFP timer programs, the
timer vectors and every patched ISR operand - all equal, at k1 and k2.
Blind spot: Unicorn fires no interrupts, so it compares END-OF-FRAME
values. A retune leaves the vector holding the same value a start would
have written; the difference between "wrote it" and "left it" is
invisible in a state snapshot.

**Hatari traces said identical.** `--trace io_write` on both real runs:
the timer-register write streams bit-equal frame by frame, the per-frame
PSG content equivalent. True - and still not the sound, because WHAT is
written does not pin WHEN the square's halves play.

**The audio diff finally caught it, then misreported the fix.** A control
run proved Hatari bit-deterministic (two takes of v1, sample-identical),
so the spectral divergence starting at exactly 38.4s was real. But when
the suspected fix was applied and the spectral distances did not move,
the suspect was wrongly cleared - the section is dual-SID beating
material, where normalized spectral distance is dominated by benign
beat-phase sensitivity. A metric that cannot distinguish phase noise from
damage cannot judge a phase fix.

**What finally convicted it:** `cmp` on the pre-fix and post-fix packs -
the emitted action bytes differed, so the suspect code path HAD fired in
this tune (an earlier eyeball of a few dump frames had said otherwise) -
plus the owner's own recording matching the pre-fix rendering's era.
Byte evidence beats metrics.

## The root cause

v2's packer carried a deliberate "improvement": a SID re-starting after
a gap was resumed BY RETUNE whenever the slot's vector still held that
SID's half - timer reprogrammed, vector untouched - so the square's
phase would "free-run musically" instead of v1's hard restart. The
actual effect: the timer restart quantized the phase but the HALF-PARITY
(loud-first vs silent-first) was whatever the vector happened to hold -
a coin flip per re-start. A tune that re-triggers its bass every few
frames got a randomized attack on every note. Neither v1 nor any
surveyed player behaves this way; it was the worst point in the design
space, invisible to every write-level check by construction.

## The resolution (see the companion file for the full survey)

Three models were rendered and judged by ear on the same bars:

1. v1's: deterministic restart, the stale register value ringing through
   the first period, then the loud half. Better, "still a bit off".
2. maxYMiser's mask/resume: interrupt masked at release, counter keeps
   counting, phase continuous through the gap. Built, then set aside -
   the owner's trusted reference behaves differently.
3. **ym2149-rs's** (shipped): `sid_start` keeps the accumulator only
   while already active - "avoid phase pops" - and `sid_stop` resets it.
   Free-run while HELD, deterministic phase-zero restart at every gap:
   one silent timer period, then the loud half. Mapped to real timers:
   SID_START writes the voice silent under a brief mask and installs the
   loud half; the first tick, one period out, begins the alternation.

The result aligns with v1's audible character AND the reference's
documented semantics, and the owner's ear signed it off on the tune that
broke.

## The lessons, distilled

- A state snapshot cannot distinguish "wrote X" from "left X": SMC
  players need WRITE-EVENT comparison where interrupts are live.
- Sweep budgets are part of a sweep's claim. Say what was not covered.
- Normalized spectral distance is blind inside beating material; align
  sample-exactly and distrust it near commensurate frequencies.
- When a fix "changes nothing", diff the ARTIFACTS (the packs) before
  trusting the metric that says so.
- Phase is chip-visible state. The v2 differential contract ("compare
  every write") was necessary but not sufficient; the ear closed the
  gap, twice.
