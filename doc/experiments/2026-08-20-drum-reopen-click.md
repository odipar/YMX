# The drum reopen click: one frame of caution, audible on every hit

The report was "a small glitch/tick in the sample/drum playbacks" on
Chambers of Shaolin - Trapped in China, v2 only. The cause turned out to
be a single `+ 1` in the packer - a rounding written to be safe - and
finding it took two false trails that are worth more than the fix. One
was an audit that verified everything except the thing that was wrong;
the other was a capture chain that manufactured a phantom bug on a
second tune. Companion of method: the
[Synergy Credits hunt](2026-08-20-synergy-credits-sid-phase.md), which
taught that write-level instruments have blind spots; this hunt adds
that *counting* instruments have one too.

## The symptom

Every digidrum in the tune is followed by a faint click. v1 clean, v2
clicking, both packs of the same YM at the same options. The tick was
audible enough for a musician to flag it in normal listening, and (once
the ear knew it) obvious in a ten-second A/B clip.

## False trail one: the audit that counted everything and timed nothing

The first instrument was a trace audit over `--trace io_write` runs of
both builds: per drum window it counted ticks (833 and 987 - sample
length plus marker, exact), verified every window ran to its marker,
verified zero burst writes leaked into windows and zero data writes
landed under an invalid select. All of it passed, identically, in v1
and v2 - "write-level perfect" - and the hunt went off toward audio
artifacts and inherent tick jitter.

The blind spot: every one of those checks is a **count or an invariant
inside one build**. None of them compared the *frame index* of any
event **between** the builds. The actual defect - every window ending
one frame later in v2 - passes all of them: same tick counts, same
values, same markers, no leaks. A differential must diff *when*, not
just *what* and *how many*:

    per window, between builds:  start frame, end frame,
                                 tick-value stream, resume volume,
                                 R7 at the edges.

Under that comparison the bug is unmissable: **end delta +1 on 79 of 79
windows**, start delta 0, timer streams byte-identical, and the resume
volume one envelope step lower in v2 (14 became 13) because the reopen
was reading the *next* frame's ring byte - the fingerprint of an event
one frame late, visible in the values alone.

## False trail two: the capture that broke a second tune

To scale the ear, both builds were recorded under Hatari and A/B WAVs
cut. Wings of Death 8 - level 6 was captured with **four emulator
instances recording AVI concurrently** - and every take of every build
sounded broken, which read as "the regression is everywhere" until
Robbert distrusted the capture itself. He was right: the concurrent
takes were glitched recordings of correct playback.

The replacement is the trusted protocol, now standard:

* **One emulator at a time.** Concurrent windowed instances contend for
  the audio path; two at a time was already marginal, four was garbage.
* **Hatari's own WAV recorder, driven remotely.** This build *does*
  have `--cmd-fifo` (an earlier session recorded that it does not -
  wrong). Hatari must create the fifo itself - a pre-made `mkfifo`
  kills it at startup and the writer then blocks forever. Poll for the
  fifo, then:

      hatari-path soundout /path/out.wav
      hatari-shortcut recsound          # again to stop

* **Tune-relative cuts.** Locate the tune start by RMS threshold and
  cut the same window (here 0:06-0:16, 10.0s) from every version, so
  the ear compares like with like.

On trusted captures the picture snapped clean: v1 perfect, both v2
builds clicking, and a control pack with the digidrum select bits
stripped from the YM (fx codes cleared on 1,622 frames, everything else
untouched) perfectly clean - the drum path, nothing else.

## The cause

`EffectScript.duration()` computed a drum's gate window as

    ceil((len+1 ticks) * divisor * playerHz / MFP_CLOCK)  + 1

with the `+ 1` annotated "for the arming phase against the VBL". The
fear was real - the trigger action arms the timer a slice *into* its
frame, so the last tick lands later than the tick count alone says -
but a whole frame of grace is ~30x the actual bound, and it is not
free. It is 20ms, once per drum, of this:

* the sample ends; the marker tick parks the voice at the tail volume
  (here $0D) with the tone still forced off - a **loud DC level**;
* v1 reopened the gate in the marker tick itself, so the very next
  burst restored the tune's volume and mixer: the reopen transient sits
  within 20ms of the drum tail, masked by it;
* v2 held the DC park through one more full frame, then reopened -
  volume step and tone re-enable as an **isolated transient in clean
  context**, at CoS's drum rate almost five times a second.

Same transition, same step size, in both players; the extra frame moves
it out from under the drum's own masking. That is the whole bug. (The
class javadoc had even documented it, as "at most one extra frame of
the parked mid-volume" - written as a harmless rounding note. One frame
of wrongly-held mute after a percussive event is not harmless; it is a
click at the reopen.)

## The fix

The blanket frame became a bounded margin *inside* the ceiling:

    ceil( (len+1)*divisor*playerHz/MFP_CLOCK  +  1/16 frame )

The trigger action runs at most a few percent of a frame into its VBL
(gates, burst, action stage - bounded, small), so a sixteenth covers
the arming phase with room to spare, and the reopen lands on the same
frame boundary v1 used except when a drum genuinely ends within 1/16
frame of it - where the late frame is the *correct* side to err on.

Everything downstream of `drumEnd[]` heals with it: the gate mask, the
baked R7 force, the resume volume, suppressed-SID restarts, and the
loop-split rotation all derive from the same number.

## Verification

* Java suite and the emulation rig green (goldens that encoded the old
  late reopen sit in **three places** and all moved one frame: the
  `EffectScriptTest` rig scene and rotation test, `test_ymx.py`
  `run_effects` frames 32/33 and 49/50, and the class javadoc's
  frame-alignment section).
* The trace differential on CoS after the fix: 79/79 windows with start
  delta 0, end delta 0, byte-identical timer streams, identical resume
  volumes and R7 edges against v1.
* Robbert's ear on the trusted A/B: fixed.

## What to keep

1. **A differential must compare event timing, not event counts.** An
   audit can verify every count, marker and invariant in both builds
   and still miss a one-frame shift; diff the frame indices of window
   edges between builds, always.
2. **One frame is audible.** Any deliberate late bias on a gate around
   a percussive event is a click at the release. "At most one extra
   frame" in a design note deserves an ear test, not a shrug.
3. **Distrust the capture before the player.** A glitched recording of
   correct playback is indistinguishable from a broken player until the
   capture chain is validated. Record sequentially, use the emulator's
   native recorder, cut tune-relative windows.
4. **Strip-the-effect controls are cheap and decisive.** Clearing the
   fx select bits in the source YM isolated the drum path in one
   listen.
5. **The ear is an instrument.** It found what the audit called
   perfect, twice in two days. Give it clean, aligned, ten-second A/B
   clips and believe it.
