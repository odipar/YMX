# SID-voice phase at (re)start: what other players do

Surveyed 2026-08-20 while root-causing the Synergy Credits v2 breakage
(the retune-for-restart substitution). Source-verified against ST-Sound,
maxYMiser's replay source, TAO's own driver (disassembled from the SNDH
archive), sc68, psgplay, AtariAudio and Hatari's MFP models, and a
pattern-scan of 5,896 depacked SNDH files.

## The hardware ground truth (MC68901, all four MFP models match)

- Stopping a timer preserves its main counter; only residual prescaler
  count is lost. Timers RESUME.
- Writing TDR while stopped loads the main counter immediately; while
  running, the value latches at the next terminal count (and a write
  landing exactly on the 01 passage loads an indeterminate value - the
  reason YMX reloads only on a CHANGED count).
- Therefore stop -> TDR -> TCR is the one deterministic phase reset, and
  the first tick fires one full period after the start. Which half plays
  first is whatever the installed handler writes at that tick.
- Even so, hardware determinism is a few MFP cycles wide: the MFP clock
  is asynchronous to the CPU (Hatari injects deliberate +-2-cycle start
  jitter, added for Wings of Death 2).

## What each player does at a SID (re)start

| player | phase at re-start | first half |
|---|---|---|
| ST-Sound (the de-facto YM spec) | NEVER resets: sidPos free-runs, even while the effect is off, at the stale rate; sidStart touches step/volume/flag only | off-half from theoretical phase 0 |
| maxYMiser | free-runs on held notes AND plain re-triggers; chain restarts at step 0 only when the timer changes usage; full stop/load/start + YM-oscillator reset only via the explicit per-instrument sync command (manual: even then only 180-degree determinism) | step 0 of the user sequence (polarity not public) |
| TAO's driver | free-runs on plain note changes (SMC chain repointed on the fly); embeds stop -> load -> start inside frequency-change steps - deterministic reset exactly there | sequence data |
| SNDH corpus at large | 781 strict stop->load->start instances vs ~4,090 load->start against a running timer (indirect writes undercounted): the full reset idiom was toolbox, not law | - |
| YMX v1, and v2 as shipped | deterministic: every code arrival is stop -> vector:=loud half -> TDR -> TCR; a held code reloads TDR on change only; a prescaler-slide retunes without touching the vector | loud |

Notable: ST-Sound is internally split by design - sync-buzzer and
digidrum phase-reset on every (per-frame!) start, SID alone free-runs.
And no primary source documents a loud-half-first convention anywhere;
it is YMX's own, kept because it is deterministic, hardware-honest, and
it is the sound this player has always made.

## What YMX v2 does (decided by ear against ym2149-rs)

The YM format never specified phase; the dumps' composers heard whatever
their own driver did, and every later player renders these sections
differently. Three models were tried on Synergy Credits' bass:

1. v2 pre-fix ("resume by retune"): timer restarted but the square's
   half-parity random per re-start - audibly broken from 0:38, the worst
   of both worlds.
2. v1's loud-half restart (stale register value rings through the first
   period, then the loud half): better, "still a bit off".
3. maxYMiser's mask/resume (phase continuous through the gap): built,
   then replaced before listening - the owner trusts ym2149-rs's
   rendering, which is not this.

Shipped: the **ym2149-rs model** (sid_start keeps the accumulator only
while already active - "avoid phase pops" - and sid_stop resets it, so a
gap restarts at phase zero, gate-off first). Mapped to timers: a fresh
START writes the voice silent immediately and installs the loud half; the
first tick, one timer period out, begins the alternation - one quiet
period, then loud, deterministic at every gap. HELD frees the phase;
RETUNE keeps the installed half.

Both gap models are now ordinary stream verbs the player always carries:
verb 0 is the maxYMiser RESUME and RELEASE grew a mask flag. The packer
is set per tune - `-sidresume` selects the mask/resume model, the
ym2149-rs restarts are the default - and since the choice is per emitted
byte, a future packer could even switch models mid-song; nothing in the
format forbids it. The Synergy originals (SidSound Designer, binary-only)
are a third, unknowable driver - the per-tune switch exists precisely for
that uncertainty.
