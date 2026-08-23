# Experiments: the results

Ideas measured against the real corpus and the real tools, and what the
measurements said. Most are declines — an idea can be good, measurable and
still not worth its complexity, and writing the number down keeps it from
being proposed, measured and declined a second time. The rest are diagnoses:
bugs whose causes were buried deep enough that finding them taught something.

These are the results only. The full logs — the false trails, the instrument
readings, the method — were kept while the work was live and are in the
[ST4](https://github.com/odipar/ST4) repository's history, where the player
was built.

---

## Declined

**`movep` and pre-formatted streams** (2026-08-19). Would storing each
register's stream as ready-to-send `select:value` words, or as ready-to-run
68000 code, beat a select immediate plus a straight RAM-to-chip byte?
No — ties at best, +25% bytes at worst. Fourteen registers sharing one stream
only matches when every register's history repeats at once, literals cost
eightfold, and the format's 32,512-byte offset reach caps the match window at
290 frames.

What the question did surface, and what was adopted: **the displacement
patch** — init writes each register's `k·N` into the burst once, and `N` is
capped at 2520 so `13·N` fits a signed word. 99 harness ticks to 96.

`movep.w` shipped two days later, but not for speed: it costs about 4
cycles more per register than the plain pair. It shipped because one
instruction cannot be split by an interrupt. See *the unmasked
burst*.

**Register clustering** (2026-08-19). Would packing correlated registers into
one stream — the envelope pair, the volume triple — save bytes? The
prediction held exactly: joint streams save where registers change together
(the envelope pair almost always) and cost heavily where they do not (the
volume triple most of all, since a match must cover every clustered
register's history at once).

Declined on complexity, not on bytes. An adaptive version needs a
stream-layout menu in the header, and every reader of the format pays for that
configuration space forever: init derives the layout, the burst patcher goes
table-driven, refills need per-stream budgets, the verification matrix
multiplies. A permanent cost, paid in bytes, when the limits are cycles
and RAM — and clustering moves neither.

**Untried:** if file size ever becomes the constraint, the cheap
variant is fixed, menu-less, always-on clustering of (2,3), (4,5) and
(11,12) — one layout, two cursors, no adaptivity, −3.3% of register data. The
script's A/P pairs would likely join for the same correlation reason.

---

## Diagnosed and fixed

**The SID ticking** (2026-08-19). A toggle stream's square audibly ticked.
Cause: the frame write was writing the voice's volume register mid-phase,
under a timer stream that owned it. Fixed by the retune path plus the burst
skip; confirmed by ear against ST-Sound with ym2149-rs as a second reference.

The durable rule, and the reason the skip exists: **a frame player may not
write any register a timer stream currently owns** — not a volume register
under a toggle stream, and not under a PCM stream either.

**The Synergy Credits hunt** (2026-08-20). A bass line correct in every
write-level comparison and wrong by ear. Cause: the square's half-parity
was random per re-start.

The rule that outlived the bug: **phase is chip-visible state that a write
comparison cannot see.** A state snapshot cannot distinguish "wrote X" from
"left X", so a self-modifying player needs write-*event* comparison where
interrupts are live. Normalised spectral distance is insensitive inside
beating material. And when a fix "changes nothing", diff the artifacts before
relying on the metric that says so.

**SID phase semantics** (2026-08-20). A survey rather than a bug: the YM
format never specified what a re-started square does, so every player renders
these sections differently, and each composer heard their own driver's
rendering.

Shipped: the **ym2149-rs model** — a gap restarts at phase zero, silence
first. A fresh start writes the voice silent immediately and installs the
loud half; the first tick, one timer period out, begins the alternation. Held
codes free the phase; a retune keeps the installed half.

Both models are ordinary stream verbs the player always carries: `RESUME` is
maxYMiser's, and `RELEASE` grew a mask flag for it. The packer sets it per
tune with `-sidresume`. Since the model is per emitted byte, a packer could
switch mid-song; nothing in the format forbids it.

**The drum reopen click** (2026-08-20). A click after every digidrum, from
one cautionary `+1` frame in the packer's computed sample end.

Four things worth keeping:

1. **A differential must compare event *timing*, not event counts.** An audit
   can verify every count, marker and invariant in both builds and still miss
   a one-frame shift. Diff the frame indices of window edges.
2. **One frame is audible.** A deliberate late bias on a skip around a
   percussive event is a click at the release. "At most one extra frame" in a
   design note calls for an ear test.
3. **Distrust the capture before the player.** A glitched recording of correct
   playback is indistinguishable from a broken player until the capture chain
   is validated.
4. **The ear is an instrument.** It twice found what the audits passed.

**The timers left running** (2026-08-21). Two builds sending byte-identical
chip traffic, and one of them audibly wrong.

Cause: the player had stopped claiming timers it did not need — a correct
change in itself — and taking Timer A and D unconditionally had been
quiescing the host's machine as a side effect. Nothing had written down that
it mattered.

The fix went to the **host**, not the player: `YMX_player.S` saves and stops
TACR, TBCR, TCDCR and the four data registers, and restores them at exit,
counts before controls. A player claims a timer per channel its tune uses,
and nothing else.

Two things worth keeping:

1. **"Identical chip writes" is not "identical playback":** timer state
   never crosses the bus.
2. **A WAV A/B under an emulator cannot resolve a player change.** The control
   that proves it: two PRGs of the *same* player, sending byte-identical chip
   traffic, correlate **0.38**. Compare `--trace psg_write` streams instead —
   values, order and cycles, all deterministic.

---

## Changed

**The unmasked burst** (2026-08-21). The frame write masked interrupts,
priced in a comment at its two `sr` instructions. The cost was elsewhere:
the ~500 cycles of interrupt latency they guarded, paid by whichever tick
fell inside — longer than a whole tick period at the top of the timer
range.

The fix is atomicity, not a shorter mask. Every register write became a
single `movep.w`, which a 68000 cannot split, so tearing is impossible with
interrupts enabled or masked. That made the mask **optional** rather than
necessary: `YMX_MASK_BURST` is on by default and `-nomask` turns it off.

| what the mask costs | |
|---|---|
| player | 8 bytes |
| harness | 3 ticks in 1,700 frames |
| longest interrupt-free span | ~500 cycles, against one instruction |

Same chip traffic either way, byte for byte — 16,156 PSG writes over 900 VBLs
of one tune, identical. The flag moves when ticks run, not what reaches the
chip.

**Where hardware offers an instruction that does the whole operation, it removes
the race instead of scheduling around it, and usually costs less than the
mask it replaces.** For any player driving audio-rate interrupts, measure the
longest interrupt-free span and compare it against the shortest tick period
it allows.

---

## An audit, not an experiment

**The Chambers of Shaolin dump** (2026-08-20). Why the YM dump sounds
different from the game.

- **Melody: exact.** Every tone period matches the original replay — 0.00
  semitones of offset.
- **Drum timing: exact.** The dump holds the original's 66–79 ms hits by
  scaling count and rate together, just under three times each. Pitch and
  length preserved.
- **Drum content: replaced.** The original's samples are nearly binary, two
  thirds 0s and 15s — a bright buzzing waveform. The dump's are smooth
  full-range data, aligned correlation ~0.1. The converter re-rendered them
  through some other chain, and that darker character is what an ear that
  knows the game hears.

Every YM player renders this identically, because it is in the file; the
SNDH carries the game's own drums.

The audit exposed one weakness on the way: the drum rescue used to
halve a too-fast sample by powers of two through a 2-tap boxcar, folding the
removed octave back as aliased brightness. It now resamples to the highest
MFP-representable rate under the ceiling through a Hann-windowed sinc in the
volume curve's linear domain — pitch and duration exact — and `-drumhz` above
25,600 works instead of silently dropping.

**Untried:** a "pack from the original" path — trace a subtune's
register and timer activity under emulation and feed that to the packer
instead of a YM dump — would free YMX from dump quality. A real
project, not started.
