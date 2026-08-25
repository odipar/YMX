# The releases

What changed in each published set of binaries, newest first.
`ymx/mkrelease.sh -publish` reads the section whose heading matches the
release it is staging and publishes it as the release notes, so the
release page says why the release exists. A release without a section
here is not published.

A release version is the format version the binaries read, then the
patch number the binaries carry of their own. The format version is the
compatibility gate: tunes packed at one format version play on every
patch of it.

## 0.6.0

The player is 3,412 bytes at unit size 2, where 0.5.2 carried 3,394.
Format version 0.6: a tune packed at 0.5 has to be repacked from its
`.ym` or `.ymr` source.

- A file may carry **extension streams** past the twenty-five, at indices
  25 to 31, and thirty-two is the stream ceiling at this version and at
  every later one. `S`, the stream count at offset 14, is 25 to 32 where
  it was always 25.
- The long at offset 38 is `Q`, the **required-streams mask**, one bit per
  stream, and thirty-two is the stream ceiling at every version because of
  it. A set bit requires the stream: a consumer that does not
  understand it rejects the file. A clear bit on a stream the file carries
  makes it advisory, and a consumer that does not understand it reads none
  of it and produces the values it would produce from a file without it.
  Bits 0 to 24 are set in every file, so a file carrying no extension
  stream holds `$01FFFFFF`.
- The header is 142 bytes where 0.5 carried 138. The section table follows
  at 42 where it followed at 38, and the first body item is at 144.
- SPEC.md §1.7 is the registry. This version assigns no index: 25 to 29
  are the format's to assign later, and 30 and 31 are private at every
  version, so a private stream never collides with a registered one. An
  extension that turns out to be shared is registered from 25 to 29 in a
  later version.
- The refill turn is a position in a consumer's decode list rather than a
  stream index, so a file carrying an extension at index 31 does not force
  `C` to 32 for every consumer of it.

A file carrying no extension stream is what 0.5 would have written apart
from the header: 10 of the 10 reference dumps in `doc/conformance` are
byte for byte what they were.

## 0.5.2

The player is 3,394 bytes at unit size 2, where 0.5.1 carried 3,394: the
player did not move, and every tune packed at format 0.5 plays unchanged.
The three SNDH cores are byte for byte what 0.5.0 published. The PRG stub
gives the system's own tick back.

- After a program ran, the desktop could no longer time a double click.
  0.5.1 read that as parked timer vectors and gave those back, which was
  a real omission and not this fault. The cause is Timer C's count. A
  timer's data register reads as the count it has reached, not as the
  count it restarts from, so a takeover that reads $FFFFFA23 and writes
  it back at the end leaves the operating system's 200 Hz tick running to
  the count the timer had reached. The desktop measures a double click, a
  key repeat and the time of day in that tick.
- The count is written rather than read now: 192, which with the /64
  prescaler the control register restores is 2457600 / 64 / 192, or
  200 Hz. The prescaler needs no such care, since a control register does
  read as what was written to it.
- The stub is 2,878 bytes where 0.5.1 carried 2,884.

Reported fixed on the machine, which is what 0.5.1 lacked: its own note
said no program had been run with $114 read back afterwards, and the
symptom outlived the release. The same fault, and the same cause, was
found in the RhYMe tracker's exported player.

## 0.5.1

The player is 3,394 bytes at unit size 2, where 0.5.0 carried 3,394: the
player did not move, and every tune packed at format 0.5 plays unchanged.
The three SNDH cores are byte for byte what 0.5.0 published. The PRG stub
is what changed, and it changed twice.

- Escape stops the program, as space already did.
- The program gives the four MFP timer vectors back. YMX.S's assumption 5
  has the host own the machine state: the player parks the vector of
  every timer it claims on an entry with no effect and restores none of
  them, and names this program as the worked example of doing it. The
  program saved the VBL vector and every MFP interrupt and timer
  register, and no timer vector. So a tune on Timer C handed the desktop
  back with $114 on the player's park entry: the timer ran at 200 Hz and
  its interrupt did nothing, the system's 200 Hz counter stopped
  advancing, and a double click, which is timed off that counter, was
  mistimed from then on. All four vectors are saved now - $110, $114,
  $120 and $134 - since stream T may put a channel on any of them.
- The stub is 2,884 bytes where 0.5.0 carried 2,814.

Verified by reading the assembled stub: each of the four addresses is
reached twice, a save and a give-back, which
`BinariesConsistencyTest` holds it to. Not verified by running: no
program has been run under emulation with $114 read back after it exits.

**This release does not fix the double click.** The vectors were a real
omission and the wrong cause; 0.5.2 has the count that fixes it.

## 0.5.0

A loop frame in the header, and the sections to reach it. Format version
0.5: a tune packed at 0.4 has to be repacked from its `.ym` or `.ymr`
source.

- The player is 3,394 bytes at unit size 2, where 0.4.1 carried 3,256,
  and costs about 16 cycles a frame more: `ymx/test/run.sh` under Hatari
  2.6.1 on a cycle-exact 8 MHz ST with TOS 2.06 played 18,000 frames of
  one tune in 945 ticks of the 200 Hz clock where 0.4.1 took 938, three
  runs each, with the same chip-write checksum. That length resolves to
  within about 2 cycles a frame; the harness's own 1,700 frames read 89
  ticks for both.
- The header is 138 bytes, eight more than 0.4 carried. The long at
  offset 30 is `L`, the frame a tune that starts over goes back to; the
  long at 34 is the offset of a loop table. The section table follows
  at 38.
- `L` carries the frame the source starts over from, where the packer can
  keep it: the frame has to be one the wrap can enter with the timers
  stopped, the skips cleared and the envelope generator not restarted.
  Where it cannot be entered the packer takes the next one that can, up to
  a second later; where no frame in reach can be, `L` carries 0 and the
  tune starts over from its first frame, as it did at 0.4. Each conversion
  says which.
- A tune whose pass fits a ring - `O - L` at most the ring size `N`, where
  `O` is the frame count - is decoded once. The refills stop at `O`
  values, and the wrap moves the read position back `O - L` bytes in every
  ring, so a second pass decodes nothing. The values written to the sound
  chip are the same either way.
- Raising `N` to hold a pass costs workspace and no file bytes, and a
  bigger ring lets a back-reference reach further, so a body past the ring
  raises it, up to the cap of 2,520 bytes a ring. The header carries the
  raised size, and a host sizes its workspace from that word rather than
  from the ring size the tune was packed with.
- Past that cap the file carries two sections per stream instead: the
  section table locates frames `[0, L)` and the loop table, twenty-five
  entries at the offset in the header, locates `[L, O)`. A stream opens
  its loop section when the first runs out and every time after that.
  This costs file bytes rather than workspace - the replayed frames are
  packed on their own - and a section is a whole number of units, so a cut
  falls on one.
- The workspace before the rings is twelve bytes larger: 1,658 bytes, plus
  25 `N` for the rings. The three longs are `O - L`, the frames one pass
  plays; the table a stream opens a section out of; and how many values a
  section of that table carries.

## 0.4.1

The player, optimized. Every tune packed at format 0.4 plays unchanged -
the format did not move.

- 3,256 bytes at unit size 2, where 0.4 carried 3,324.
- Cheaper by about 94 cycles a frame, and the measurement resolves to
  within about 24 of that: `ymx/test/run.sh` under Hatari 2.6.1 on a
  cycle-exact 8 MHz ST with TOS 2.06 played 1,700 frames of one tune in
  89 ticks of the 200 Hz clock where 0.4 took 93, three runs each, with
  the same chip-write checksum.
- The frame write counts its register selects through one step register,
  the channel-to-timer map is read through a displacement the init
  patches, the sample tick steps its pointer through its own patched
  address, and init sets up its streams in registers.
- `YMX_SUPER_HOST` is gone: the sample tick no longer borrows an address
  register, so the flag chose nothing. A build line that defines it
  still assembles.

## 0.4

The first release of format 0.4, renumbered from 1 while the format is
short of the stability 1.0 states. The binaries' names carry the release
version, so a file states which release it came from.

- A sample's loop point resolves as an unsigned word: a loop point of
  `$8000` through `$FFFE` addressed 65,536 bytes below the sample.
- Every timer a claim takes is handed back as it was found.
