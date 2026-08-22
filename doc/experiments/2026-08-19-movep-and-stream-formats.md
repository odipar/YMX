# Writing the YM registers faster: movep and pre-formatted streams

**Question.** The YMX burst writes fourteen registers a frame: select
immediate, then a byte from the register's ring to the chip - 32 cycles per
register with the init-patched displacements. Can `movep` beat that? And if
packing the values costs the trick its profit, can the *packer* pre-format
the streams so movep gets its long for free - up to streams of ready-to-run
68000 code?

**Method.** Cycle counts from the 68000 tables for every packing sequence;
packed sizes measured over the full 515-file YM corpus with the real
St4EventOptimizer at ring-equivalent windows.

**Numbers.**

- `movep.l` at $FF8800 does write select, data, select, data (the PSG
  decodes $FF88xx on A1, so +4/+6 mirror +0/+2) - two registers in 24
  cycles. But the values arrive one byte per ring, and every gathering
  sequence costs at least what the trick saves. Best variant (preloaded
  pair-select skeletons, `move.b`/`swap`/`move.b`, reload per pair): 64
  cycles per pair - an exact tie with the plain form, bought with register
  pressure and a mirror-decode assumption.
- Streams stored as select:value words, so movep.w needs no gathering
  (28 cycles/register): **+25.5% packed size** corpus-wide, range +14% to
  +45%, no exceptions. ST4 stores literals raw, so the constant select
  byte rides every literal into stream B; matches absorb it, literals
  never do.
- Streams of `move.l #$rr00vv00,$FFFF8800.w` records (~20 cycles/register,
  no interpreter): worse still. Speedcode must be contiguous, so all
  fourteen registers share one stream, which only matches when every
  register's history repeats at once; literals cost eightfold; and the
  format's 32,512-byte offset reach caps the match window at 290 frames.

**Verdict.** Declined. The select immediate plus a straight RAM-to-chip
byte is the floor for values stored one byte per ring. What the question
did surface: the displacement patch (init writes each register's k*N into
the burst once; N capped at 2520 so 13*N fits a signed word) - adopted,
99 to 96 harness ticks.

**Postscript 2026-08-21.** `movep.w` shipped after all - not as the speed
trick declined here (it costs about 4 cycles more per register than the
plain pair) but because one instruction cannot be split by an interrupt.
That made the burst's interrupt mask optional rather than necessary; it
still ships on by default. See
[the unmasked burst](2026-08-21-the-unmasked-burst.md).
