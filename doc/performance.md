# What the player costs

Cycles, measured, on real YM songs. A demo has a frame budget and needs
the worst case, not the average, so this states both and says which frame
produces the worst one. Every figure below came out of
[../ymx/test/cost.sh](../ymx/test/cost.sh) and can be taken again.

## How these were taken

The `-perf` player paints the background red when a frame's work starts
and yellow when it ends, and each tick handler paints its own colour
inside that span. Hatari traces palette writes with a cycle stamp, so a
run of a `-perf` program is a record of every call's exact duration.
`cost.sh` builds such a program per tune, runs it, and reads the spans
back: red to yellow is one call's own work, and a tick's paint-and-restore
pair inside that span is subtracted, because a tick is the tune's bill and
not the call's.

    ymx/test/cost.sh ym/test/*.ymx
    VBLS=3000 ymx/test/cost.sh one.ymx      # a longer sample

Hatari 2.6.1, `--machine st --cpuclock 8 --cpu-exact on --compatible on`,
TOS 2.06, 4 MB. One PAL frame is 160,256 cycles and one scanline 512, so
a frame is 313 lines. Figures are per play call unless they say otherwise.

## Real songs

Each row is a `.ym` from `ym/test`, packed at the defaults (`-n960 -c24
-k2`) and played from its own PRG. The tick columns are the tune's timer
work, not the call's.

| song | avg | p99 | max | max lines | ticks/frame | tick |
|---|---:|---:|---:|---:|---:|---:|
| Atomix | 1,575 | 2,444 | 3,472 | 6.8 | - | - |
| Knucklebusters | 1,655 | 2,840 | 3,484 | 6.8 | - | - |
| Crapman level 9 | 1,680 | 3,040 | 3,596 | 7.0 | - | - |
| Big - Harvey Smiths Show Jumping | 1,681 | 3,180 | 3,644 | 7.1 | - | - |
| Ghost Battle 1 - loader | 1,772 | 2,920 | 4,232 | 8.3 | 0.13 | 164 |
| A Prehistoric Tale 16 - intro | 1,800 | 3,264 | 4,356 | 8.5 | 0.10 | 132 |
| Chambers of Shaolin - Modu Attack | 1,808 | 6,516 | 11,036 | 21.6 | 0.59 | 165 |
| Seven Gates of Jambala - level 11 | 1,838 | 5,772 | 7,000 | 13.7 | 0.75 | 165 |
| Ooh Crikey - main menu | 1,877 | 3,080 | 4,428 | 8.7 | 0.36 | 164 |
| Turrican - world 4-3 | 1,912 | 3,232 | 4,252 | 8.3 | 0.16 | 164 |
| Life's a Bitch - Ak screen | 1,995 | 3,436 | 6,212 | 12.1 | 0.93 | 164 |
| Turrican 2 - world 4-3 Dragon Fight | 2,023 | 3,600 | 6,224 | 12.2 | 0.70 | 165 |
| Wings of Death 8 - level 6 | 2,122 | 3,632 | 6,988 | 13.7 | 1.17 | 165 |
| Synergy Credits | 2,317 | 3,424 | 3,880 | 7.6 | 0.22 | 132 |

**The budget line.** A YM6 song costs **1,600 to 2,300 cycles a frame on
average - 3 to 4.5 scanlines - with a p99 near 6 lines**, and its worst
call in a 1,500-frame sample lands between 7 and 14 lines. That is 2 to 4
per cent of a PAL frame on average and under 5 per cent at the p99. The
spread across songs is narrow because the frame's work barely depends on
the music: fourteen register writes, a few actions, one refill.

`ticks/frame` counts only the ticks that landed *inside* a frame's own
work, so it is a collision rate rather than the tune's tick rate; the
tune's real tick load is the timer bill below.

## The worst case, and which frame makes it

The kit's tunes are shaped to be adversarial, so they bound what any file
can do:

| tune | avg | p99 | max | max lines | the max lands on |
|---|---:|---:|---:|---:|---|
| `stored_tiny` | 1,129 | 1,640 | 1,640 | 3.2 | a stored-section refill |
| `unit4` | 1,438 | 3,052 | 3,912 | 7.6 | an early refill |
| `cut_form` | 1,592 | 2,980 | 4,700 | 9.2 | call 1419, where sections reopen at `L` = 1440 |
| `plays_once` | 1,692 | 2,908 | 3,540 | 6.9 | call 491 |
| `wide_ring` | 1,838 | 3,728 | **6,448** | **12.6** | a unit-1 refill through the word-offset path |
| `retrigger` | 1,886 | 3,272 | 4,252 | 8.3 | call 2995, the wrap teardown at `O` = 3000 |
| `unit1` | 1,900 | 4,488 | 5,132 | 10.0 | the first unit-1 refills |
| `resume_model` | 2,395 | 3,956 | 5,324 | 10.4 | near the teardown at `O` = 5379 |

**Three frames cost more than the rest**, and a demo that must fit every
frame budgets for them:

1. **A unit-1 refill through the word-offset path** - 12.6 lines, the
   highest seen. A section packed at `-k1` whose matches reach past 512
   units reads a word offset per match, and the decoder's per-unit work
   has no unit-2 or unit-4 shift to amortise it. Packing at the default
   `-k2` avoids this path.
2. **The frames where sections reopen** - 9.2 lines. Only a tune with a
   loop table has them, at frame `L` and on every later pass, one stream
   at a time on its own refill turn.
3. **The wrap teardown** - the frame that ends a pass stops every claimed
   timer, parks its vector and clears the skips. `retrigger`'s maximum is
   its call 2995 of 3000, and `resume_model`'s is near its own end.

Everything else sits under 9 lines. **13 scanlines - 4 per cent of a
frame - covers every shape measured**, including files no packer run has
ever produced.

### What a reopen spends

The window is exact. A stream crosses on the turn its head section runs
out, so the frames are `L - C` through `L - C + live - 1`, one for each
stream the tune decodes. `Dragon Flight  4 - Finish 1.ym` packed at `N` =
960 and `C` = 32, seventeen live streams and `L` = 1440, puts them at 1408
through 1424, and the measurement finds those seventeen and no others:

| calls | cycles |
|---|---:|
| 1404 to 1407 | 908 |
| 1408 to 1424 | 3160 to 4748 |
| 1425 to 1439 | 908 |
| 1440 on | 1856 |

The 908 and the 1856 are the refill's two regimes, and an average blends
them: a turn with nothing left to decode costs 908, which is every turn
between a section running dry and the next one opening, and a turn that
decodes its group costs 1856.

What the window costs above that is `ymx_reopen`, not the decode. The
routine is 39 instructions, fifteen of them a `move.l` writing decoder state
into the stream's block, and it runs once per stream per pass. The floor of
the window sits 1324 cycles above a working refill, at 3180 against 1856.

A decoder opened at a section's start does have nothing behind it, so its
first values come out as literals and its parse is thinner. Both are
measured, and both are the variation above the floor rather than the floor.
Over the seventeen streams, the group a section opens with holds 39 literal
units of 272 where a group from the middle of the same section holds none,
and 46 operations cover it where 17 cover the middle one. Eight of the
seventeen open with no literal and a single operation, the same parse the
middle group has, and their frames still cost 3192 to 3256: what the frame
decodes does not reach the floor.

The boundary falling mid-refill is not what costs. `C` has only to divide
`N` and cover the streams, so the same source packs both ways without
padding or moving its loop point: at `C` = 30, `(O - L) mod C` is ten and
the worst frame is 4736 cycles; at `C` = 32 it is zero and the worst frame
is 4748.

## What one tick costs

A tick is an MFP interrupt into a small handler. Measured between the
handler's own palette marks, which excludes the exception entry and the
`rte` and includes the monitor's own bookkeeping, so these compare with
each other rather than standing as absolute costs:

| tick | writes | measured |
|---|---|---:|
| retrigger (sync-buzzer) | R13, restarting the envelope | 108 |
| toggle (SID voice) | R8+v, a level or zero | 132 |
| PCM (digidrum) | R8+v, the next sample byte | 164 |

A digidrum at 6 kHz - the corpus's usual rate - is 120 ticks a frame at
50 Hz, so **about 19,700 cycles, 38 scanlines, 12 per cent of the frame**,
and that is the tune's cost under any player. The packer's ceiling of
25,600 Hz is a quarter of the machine by the same arithmetic, which is why
it is a ceiling.

The **timer burn** column of `cost.sh` reads the yellow bar: the player's
own estimate of what its ticks cost, burned off after the frame's work so
the monitor never delays a chip write. Measured burns range from 0.12
lines on a tune with no timers to 5.9 average and 64.6 peak on a
digidrum-heavy one - a scale of the tick load, in the player's own
reckoning.

### The estimate against the measurement

The accumulator adds a fixed number of 10-cycle quanta per tick, and these
measurements are what those constants are set from:

| tick | quanta | claimed | measured | ratio |
|---|---:|---:|---:|---:|
| retrigger | 12 | 120 | 108 | 1.11 |
| toggle | 15 | 150 | 132 | 1.14 |
| PCM | 18 | 180 | 164 | 1.10 |

Each estimate stands a little above its measurement, because the measured
span excludes the exception entry and the `rte` that a tick also costs.
What matters is that the three ratios agree: an estimate that weighed one
kind against another would make the yellow bar over-report whichever tune
ran the dearest kind. The PCM end tick, which the loop or the stop path
adds to the plain one, is 20.

The PCM constant was 21 until these measurements were taken, a sixth high
against the other two, and the bar over-reported a digidrum-heavy tune by
about that much. The rig's effect stage checks the accumulator's
arithmetic - that a drum playout adds its three ticks - and passed
throughout, because arithmetic over a wrong constant is still consistent.
`TickCostEstimateTest` now holds the constants to the figures in this
table, so the next change to one fails until the measurement behind it is
taken again.

## Memory

| | bytes |
|---|---:|
| player, unit size 2 | 3,434 |
| ST4 decoder | 288 |
| PRG stub | 3,038 |
| workspace, `N` = 960 | 25,658 |
| workspace, `N` = 1776 | 46,058 |
| workspace, `N` = 2520 (the cap) | 64,658 |

The workspace is `1658 + 25·N` and is sized for all twenty-five streams,
including the ones a tune leaves idle: a tune with no timer channel
decodes seventeen, a YM tune twenty-one, so between 3.8 and 7.7 KB of a
default workspace is ring nothing reads. The packed file is resident too -
streaming means the decoded frames are not held, not that the file is
absent.

## The tick source and the screen

The PRG stub drives play from Timer C, as an SNDH host does. Timer C
counts the MFP's own 2.4576 MHz crystal: /64 and 192 counts is 200.000
Hz, and a 50 Hz tune plays every fourth tick. The PAL frame is 160,256
cycles, which is 50.0527 Hz on the video clock, and the two clocks are
separate parts. The play call therefore walks against the screen:

| tick source | drift of the call against the frame |
|---|---|
| Timer C, 200 Hz accumulated | +169 cycles a frame, a third of a scanline |
| the VBL | none: the call is the frame |

Measured by tracing the `-perf` build's red mark under Hatari over 280
calls of one tune, both builds the same binary with the stub's VBL flag
the only difference. The Timer C figure is the crystals' own ratio -
160,425 cycles a play against the frame's 160,256 is 169 - and the
measurement returns 169.0. Nothing is lost or doubled: the accumulator
is integer arithmetic against 200.

A `-perf` build paints this. The red bar walks down the screen a third
of a scanline a frame and wraps every 948 frames, near 19 seconds. Under
the VBL it stands still. For music the difference is 0.1 per cent of
tempo, below hearing. A demo that wants the play call at a fixed place
on the screen drives it from the VBL.

### Why the bar stops, walks, and jumps back

The monitor waits for the beam before it paints red. It reads the video
address counter at `$FFFF8209`, which moves only while the shifter is
fetching pixels, and spins until it moves. A VBL host calls play in the
blanking, dozens of lines above the screen, so the wait puts every bar
at the same place and the bars compare.

Under a Timer C host the call lands anywhere in the frame, and the wait
then shows in three phases. Measured on `Synergy Credits`, 1,218 calls:

| where the call falls | what the bar does |
|---|---|
| in a border, where the counter is frozen | waits, and paints at line 63 |
| in the display, lines 63 to 262 | paints at once, and walks with the drift |
| past line 262 | waits for the next frame, and paints at line 63 again |

Every one of the 1,218 marks fell between lines 63 and 262 - the PAL
display area, 200 lines from 63 - and 284 of them, 23 per cent, sat
exactly on line 63. So the bar holds at 63, walks to 262 over about 600
frames, and returns to 63. The jump is the monitor's wait, not the
player's timing: across the same run Timer C ticked 4,874 times at a
mean interval of 40,106 cycles, the nominal 200 Hz, and play was called
on every fourth tick 1,216 times out of 1,217.

The wait sits before the red mark, so it costs the figures above
nothing: red to yellow is still the frame's own work. It does burn the
machine while it spins - up to 30,052 cycles was measured - which a
`-perf` build does and a plain one does not.

The wait cannot be skipped for a timer host alone. The player is not told
which clock calls it, and the counter cannot tell a timer host that landed
in a border from a VBL host, whose bar is invisible without the wait: a
build that stopped waiting once it had seen the display running painted a
bar in 1 frame of 499 under the VBL, because one call delayed into the
display turned the wait off for good.

None of it arises where the VBL carries the calls, which is what the PRG
stub picks whenever the machine refreshes at the tune's own rate. The
same program, the same tune, with only the rate word patched:

| the tune's rate against a 50 Hz screen | drift of the call | the stub picked |
|---|---:|---|
| 50 Hz, the screen's own | -0.6 cycles a frame | the VBL |
| 60 Hz | -63.7 cycles a frame | Timer C |

A `-perf` build also clears the screen before its banner, since the
monitor paints the background colour and the pixels the desktop left
behind stand in front of it: without the clear the bars show in the
borders alone.

## What moves these numbers

- **Unit size.** `-k1` costs the most per refill and `-k4` the least;
  `-k2` is the default and what the table above measures. `-k1` also
  reaches the word-offset path, which is the worst case.
- **`-nomask`.** The frame write masks interrupts by default. Dropping the
  mask does not change the frame's own cost; it changes when a tick lands
  - inside the burst rather than behind it. `doc/experiments.md` has the
  measurement: 3 ticks in 1,700 frames, against about 500 cycles of
  latency for whichever tick would have waited.
- **`-nN` and `-cC`.** A larger ring with a proportionally larger chunk
  refills less often and decodes more per refill; the average is flat
  either way, and the peak follows `C`.
- **The tune's timers.** The largest term by far on a digidrum tune, and
  none of it is the player's: it is what the music asks the chip for.
