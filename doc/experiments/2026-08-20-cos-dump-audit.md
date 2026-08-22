# Auditing a dump against its original: the Chambers of Shaolin drums

After the reopen click was fixed and the drum rescue rebuilt, Trapped in
China still sounded wrong to Robbert - "the digi drums seem higher pitch
somehow", then, against the original, "much lower". Neither impression
was a YMX bug. Both were real, both had different causes, and pinning
them took an audit technique worth keeping: measure the YM dump against
the *original replay* running on the same emulated machine.

## The instruments

**Period-contour matching** identifies which SNDH subtune a YM dump is,
and measures pitch, without listening. Trace the original replay
(`--trace io_write`, byte-lane decoded - this driver writes the PSG in
word writes, select in the high byte), rebuild per-frame tone periods,
and correlate mean-removed log-period sequences against the dump's
registers over a lag search. Trapped in China matched SNDH subtune 1 at
r = 0.935; the median log-period offset at alignment is the pitch
difference in octaves, exact to the register.

**Tick-content comparison** does the same for drums: split the trace's
volume-register writes into hits at the timer-control edges, and
correlate each hit's value sequence against the dump's drum samples at
the implied decimation. Alignment-searched correlation near zero means
different material, not a resampling.

Two traps this hunt fell into first, kept here so the next one does not:
a *frame-slice* count is not a *rate* (drum-time slices merged and read
as 37 kHz where the truth was 9.9); and "no EOI, must be a CPU loop" is
backwards - count the EOIs first. 11,784 volume writes against 11,785
Timer A EOIs settled it: fully timer-driven.

## The verdict on the dump (YM5, attrs 5, the 1999 conversion family)

* **Melody: exact.** Every tone period matches the original replay -
  the offset measured 0.00 semitones. Nobody transposed anything.
* **Drum timing: exact.** The original plays ~658/782-sample hits at
  9,910 Hz (Timer A, TDR 62, /4) - 66-79 ms. The dump's drums hold those
  durations by scaling both together - 1,972 samples at 29,257 Hz, just
  under three times the count and just under three times the rate. Pitch
  and length are preserved.
* **Drum content: replaced.** The original's samples are nearly binary
  - two thirds 0s and 15s, a bright buzzing waveform. The dump's are
  smooth full-range data; aligned correlation ~0.1. The converter
  re-rendered the drums through some other chain, and that mellow,
  darker character is what an ear that knows the game hears as "lower".

Every YM player renders this identically - ST-Sound, ym2149-rs, YMX -
because it is in the file. The faithful Chambers of Shaolin is the
SNDH; the dump's drums are a cover version.

## What it cost YMX, and what it changed

Chasing the perception exposed one real YMX weakness on the way: the
drum rescue halved a too-fast drum by powers of two through a 2-tap
boxcar, which folded the removed octave back as aliased brightness -
audibly "higher" drums on the two above-ceiling tunes. The rescue now
resamples to the highest MFP-representable rate under the ceiling
(29,257 -> 25,600, ratio 8/7, pitch and duration exact) through a
Hann-windowed sinc in the volume curve's linear domain, and `-drumhz`
above 25,600 works instead of silently dropping (the generic SID/buzzer
ceiling no longer applies to drums).

## The door left open

A "pack from the original" path - trace a subtune's register and timer
activity under Hatari and feed that to the packer instead of a YM dump -
would free YMX from dump quality entirely. A real project, not started;
the instruments above are its first half.
