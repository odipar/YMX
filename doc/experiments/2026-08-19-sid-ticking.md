# The SID ticking: a frame burst fighting a timer square

Not a decline — a diagnosis. Recorded in full because the bug hid behind a
design assumption that *sounded* right, passed every rig, and showed
up in one tune's bass line. The finding generalizes to any player that
writes YM registers per frame while a timer effect owns one of them.

**Symptom.** "Synergy Wicked Polygons 2.ym" (YM5, 43,132 frames, SID on
voice B for 91% of the tune at 100–1100 Hz): high-frequency ticking in the
final section (from ~13:52), audibly tied to the combination of the bass
line and the timer. Wings of Death 8 — SID at kHz rates plus digidrums —
had played clean, which is part of the story.

## The elimination path

- **Not timer priority.** The tune's second effect slot packs to nothing:
  only Timer A runs. There is no second timer to have a priority
  relationship with.
- **Not the file, not the emulator.** ym2149-rs, an independent YM6
  renderer, plays the same file clean. The fault had to be ours.
- **Partly the prescaler flapping** (fixed first, PR #11, necessary but
  not sufficient): the bass wobbles across the tp=2/tp=3 MFP prescaler
  boundary up to 214 times per 500 frames, and every flip ran a full SID
  restart — vector reset to the loud half, phase gone, a ~20 Hz tick
  component. ST-Sound free-runs `sidPos` through frequency changes, so a
  code change that differs only in its prescaler now RETUNES: stop, new
  count, new prescaler, volume tracked, the vector untouched — the
  installed half keeps playing. A rig scene flips the prescaler while the
  square sits in its quiet half and asserts the vector stays there.
- **Mostly the frame burst** (the main finding, below).

## The main finding: the burst wrote the square's register mid-phase

The player's design said: "the per-frame volume write and a running SID
cooperate — the nibble the burst writes IS the SID volume parameter, and
the ISR overwrites it within one timer period." That is true and still
WRONG. The burst write lands at an arbitrary phase of the square. When the
square is in its quiet half, the write forces the loud half back until the
next timer tick:

- at a 3 kHz SID, that is at most ~0.17 ms of wrong volume — inaudible,
  which is why Wings of Death 8 never complained;
- at a 100–1100 Hz buzz-bass, it is up to 5 ms — a click, up to fifty
  times a second, wherever the write meets the quiet half. High-frequency
  ticking, exactly in the bass+timer combination.

Both references already recorded it: ST-Sound's per-sample SID
**overrides** the
register value the frame code wrote (`sidVolumeCompute` wins), and
maxYMiser's frame code **skips** volume registers on SID channels — the
ISR owns the register, full stop.

**The fix** costs one SMC word per voice. The burst reads each register
through a patched displacement and writes it in one instruction; while a
slot holds a SID, that instruction's destination displacement is patched
from 2 (the PSG data register) to 0 — the write lands on the select
register, where the very next instruction's select overrides it before
anything reaches a data register. The gate opens again on release, on a
voice change, when a drum takes the voice over (the drum's sanitize needs
the write back), at init and at stop. The rig asserts all of it: no
volume write on gated frames, the write's return on release, the drum
takeover reopening it.

**Postscript 2026-08-21.** The gate survives, the mechanism does not. Once
every register write became a single `movep.w d1,0(a2)` there is no
destination displacement left to flip, so a muted write is two nops
stamped over the movep and its displacement word, reopened by copying the
instruction back from the `ymx_movep` template — a longword of SMC per
voice, not one word. See
[the unmasked burst](2026-08-21-the-unmasked-burst.md).

**The sibling, known and deferred:** the burst also writes a sanitized 0
to a DRUMMED voice's volume register every frame — one wrong sample per
frame at drum rates (~one in 120 at 6 kHz). Masked by drum content so far;
the same gate would fix it, but the drum's end is detected in an ISR,
which makes the reopening messier. Written down so it is found on purpose, not
by ear.

**Postscript 2026-08-19, 56 minutes later.** Closed. A drum trigger CLOSES
the same gate a SID does, so the fix above now reads backwards where it
lists a drum takeover among the reopenings, and the rig asserts the
opposite of what it asserted here — the drummed voice's volume must not be
written. The reopening went INTO the ISR after all, as this entry feared:
the marker tick reopens the gate through an address the trigger patches in,
the same self-modified pattern as the sample pointer. Moving it out to the
frame boundary, off a packer-computed sample end, came the next day with
the ordering change — and brought a +1-frame bias of its own, found by ear:
[the drum reopen click](2026-08-20-drum-reopen-click.md).

## The method that settled it

Ears settled the verdict, but the harness that fed them is reusable:

- **Reference render**: `ym2wav` — a 40-line main over the ST-Sound
  library (scratchpad; clone github.com/arnaud-carre/StSound, compile
  everything except the `.utf8.cpp` duplicates), with `ymMusicSeek` for a
  start offset. The reference semantics, on file.
- **Player render**: the packer's trim options (`-min13 -sec40`) build a
  playable excerpt of the moment under study; Hatari records it with
  `--avirecord --avi-file` (no WAV option exists). A killed Hatari never
  finalizes the RIFF sizes, so the extractor scans linearly for `01wb`
  audio chunks instead of walking the chunk tree.
- **What discriminated and what did not**: coarse spectral band ratios did
  NOT — ST-Sound's and Hatari's mixing models differ more than the
  artifact. What did: differencing two *Hatari* recordings (same emulator,
  same boot, sample-aligned) of the player with and without a change —
  the gate moved ~30% of the signal energy in the busy sections — and
  then A/B against the reference by ear for the verdict.

## Verdict

Fixed by the retune plus the burst gate; confirmed by ear against
ST-Sound with ym2149-rs as a second witness. The durable rule: **a frame
player may not write any register a timer effect currently owns** — not
volume registers under a SID, and (closed the same day) not under a drum
either.
