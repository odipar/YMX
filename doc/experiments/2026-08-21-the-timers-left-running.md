# The timers left running: a regression with identical chip writes

Format v6 made the player claim a timer only for a channel its tune
names. A tune with no effects names none, so `YMX_init` claimed nothing -
and left TOS's timers exactly as it found them. Robbert heard it
immediately on Cuddly Demos - Mainmenu: "something is off with the buzzer
bass". Every instrument that reads the sound chip said the two builds were
identical, and every one of them was right. The machine underneath was
not.

## What the instruments said

The tune is 14 registers and nothing else - no digidrum, no SID, no
sync-buzzer, so no timer channel of its own and no timer claimed. Its bass is the envelope:
shape $0A written **once** at frame 0, then voice C gated in and out 585
times while the envelope period (42..63, so 124..186 Hz) carries the
pitch.

Under `--trace psg_write`, which stamps every write with its cycle:

* **16,122 writes compared between v2.0 and v6: zero value mismatches.**
* After removing the constant program-start offset, every write lands
  within **±20 cycles** of where the other build put it.
* On a tune that does use effects (Ghost Battle 3): tick period 1331.6 vs
  1331.5 cycles, burst at video_cyc 660 vs 652, first tick after the
  burst at 336 cycles in both.

The player was writing the same bytes to the same registers at the same
time. And it still sounded different, reproducibly.

## What was actually different

`YMX_init` in v2.0 stopped Timer A and Timer D unconditionally, parked its
own handlers on their vectors and enabled them. In v6, a tune that names
no channel leaves both alone - which means **TOS's timers keep running
underneath the tune**. Timer D is the RS232 baud-rate generator and ticks
at a few hundred kHz; the interrupt trace shows the difference plainly,
1.61M scheduled timer events against v2.0's 1.07M over the same window.

None of that reaches the sound chip. It reaches everything around it.

## The fix, and whose job it is

The player's job did not change: it claims a timer for each channel its
tune names, and nothing else. Quiescing the machine is the **host's**
job, and the host is where the fix went. `YMX_player.S` - the takeover
example every PRG is built from - already saved and cleared IERA, IERB,
IMRA and IMRB. Killing the enable bits silences the interrupts but leaves
the counters running, so it now saves and stops the timers themselves:

    TACR, TBCR, TCDCR and the four data registers saved, then
    clr.b $FFFFFA19 / $FFFFFA1B / $FFFFFA1D

and restores them at exit, counts before controls so a timer restarts on
the count TOS gave it.

## What to keep

1. **"Identical chip writes" is not "identical playback".** The bus is not
   the whole machine. A player that leaves the host's timers running hands
   the tune a different machine, and the difference is audible even when
   every byte and every cycle of the sound traffic matches.
2. **A WAV A/B under Hatari cannot resolve a player change.** The control
   that proves it: two PRGs of the *same* player, sending byte-identical
   chip traffic, correlate **0.38**; v2.0 against v6 correlated 0.73. Two
   captures of one binary are byte-identical, so this is not noise - it is
   the emulator's rendering reacting to binary layout. Compare
   `--trace psg_write` streams instead: values, order and cycles, all
   deterministic.
3. **The ear found it; the ear could not localise it.** Every "it sounds
   wrong" was real. Every attempt to confirm it by correlating waveforms
   pointed somewhere false. The hunt only converged when the question
   changed from "how does the audio differ" to "what else about the
   machine differs".
4. **A claim that stops being unconditional needs a second look at what
   the old claim was doing for free.** Taking Timer A and D whether or not
   a tune used them was quieting the machine as a side effect. Making the
   claim honest removed that, and nothing had written down that it
   mattered.
