# The releases

What changed in each published set of binaries, newest first.
`ymx/mkrelease.sh -publish` reads the section whose heading matches the
release it is staging and publishes it as the release notes, so the
release page says why the release exists. A release without a section
here is not published.

A release version is the binaries' own, and moves when they change. The
format version is a separate number, the compatibility gate a header
carries and a player checks: tunes packed at one format version play on
every release that reads it. The two were one number until 0.8, so a
release before it reads as its format version and a patch, and 0.7.2's
binaries and 0.8's read the same 0.7 files.

A section leads with the player's size, the stub's, and the format
version, and then itemises what changed - one item to a change, at the
level of what it means for someone using the binaries. The page is read
by someone deciding whether to take the release, not by someone
reviewing it: the commits carry the reasoning and the measurements, and
the section carries the list.

## 0.10.0

The player is 3,654 bytes at unit size 2, where 0.9.0 carried 3,534, and
the PRG stub 3,038. Format version 0.9: a tune packed at 0.8 has to be
repacked from its `.ym` source. Every packed section moves: its header is
twenty-eight bytes where it was twenty, and its signature names ST4
version 7.

- **The ST4 container is version 7.** The packer, the three decoders and
  the specification's Appendix A follow the ST4 repository at that
  version. A container carries a rewind point and a window, and its end
  code carries a repeat bit; a section of this version uses none of the
  three - it ends, its rewind field is `$FFFFFFFF` and its window is
  `N/k` - so a file decodes as it did, eight bytes longer a section. The
  stream decoder is 320 bytes where it was 288.
- **A pass longer than the ring rewinds instead of being cut.** Every
  stream's container carries `L` as its rewind point and the frames from
  `L` packed on their own; the player saves each decoder's state after
  `L` values and restores all but the write pointer after `O`, every pass.
  The loop table goes: twenty-five headers and the table itself, per tune
  that used to be cut. The header is 138 bytes where it was 142. The long
  at offset 30 is `L`, as it was. The long at offset 34 is `Q`. The
  section table follows at 38. The workspace before the rings holds a
  saved state per stream: 2,458 bytes where it held 1,658.
- **Copies from the literal stream.** `ymx -copies` lets a match beyond
  the ring copy from the literal stream, `-copiesS` searching S seconds a
  stream for a better parse; `st4 -c[S]` and `-rR` and `dst4 -rN` are
  ST4's own. Such a file sets flag bit 5 and plays only on a player built
  for its ring as a window: the `-copies` cores, twelve more in the
  binaries release, whose descriptor is version 2 and carries the window.
  Those are built for the default ring; a copies tune at another ring
  gets a core named for it, `-copies-nN`, which the Java and C#
  combiners assemble on the spot and `mkcores.sh -copies -nN` assembles
  for the Go tree. A player without a window rejects the file rather than
  decode a copy to other bytes.

## 0.9.0

The player is 3,534 bytes at unit size 2, where 0.8.3 carried 3,434, and
the PRG stub 3,038. Format version 0.8: a tune packed at 0.7 has to be
repacked from its `.ym` source. No byte of a file moves but the version
word; what moves is what a player does with two encodings, and two more
exist that did not.

- **A retune no longer stops the timer.** `RETUNE` addressed to a voice
  repatched the parameter and then stopped, loaded and started the timer,
  truncating the period in flight: a gate-phase jump of up to one timer
  period, heard as a tick where a sweep crosses a prescaler boundary on a
  frame the volume also moved. It repatches and moves the timer live now,
  as the voice-3 form always did, so the period completes and the
  displacement is the MFP's own 1 to 200 timer clocks.
- **Two encodings that were not there.** `START_RETRIGGER` at voice 3
  retunes a running retrigger stream and repatches its shape, which no
  `RETUNE` could reach because a shape is in stream X and not a voice's
  register. `RESUME` at voice 3 re-enters a released toggle stream at a
  prescaler the gap changed, programming the timer so the new rate is
  exact from the first tick and the installed half stands.
- **The action byte dispatches on its voice.** Opcode and voice together
  select a handler, through a table of 32 entries where there were 8. Four
  cycles less on every action byte, and 24 more off every voice-addressed
  `RETUNE`.
- **A malformed voice-3 byte no longer reads past a table.**
  `ymx_parmoff` held three longs where the index its lookup builds reaches
  four.
- **The rings stay the size they were asked for.** A body past the ring
  raised `N` to hold one pass, so a file's header gave a ring size the
  caller never asked for and a host sized its workspace from it. The
  packer packs at the size given and states the size that holds the body,
  which `-nN` then asks for.
- **A tune starts over where its source says.** Where its frame is not a
  whole number of 2-byte units the packer packs at unit 1, and where the
  wrap cannot enter that frame it starts over there regardless: a stream
  an earlier frame left running is not running on the second pass, which
  is audible, and the conversion says so. Of the 544 tunes in the
  collection none now starts over from frame 0 against its source, where
  twelve did.

## 0.8.3

The player is 3,434 bytes at unit size 2, the PRG stub 3,038. Format 0.7.
The thirteen binaries are byte-identical to 0.8.2's: this release changes
the standalone tool only.

- `ym-to-ymx` names itself where a tune cannot be read, and names the file
  where the file opens and is not a dump. One fault is in the command line
  and the other is in the file, and the three implementations split them the
  same way now.
- `ym-to-ymx` takes a prebuilt core from a staged release beside `dist/` as
  well as out of `dist/` itself, so a checkout that has assembled none
  builds an SNDH file or a program without rmac.

## 0.8.2

The player is 3,434 bytes at unit size 2, the PRG stub 3,038. Format 0.7.
The thirteen binaries are unchanged. This release changes the tool only.

- `ym-to-ymx -timersT` was read as the SNDH title and never reached the
  packer. Tunes packed with it carry the default timer map, and the run
  reported success. Repack them.
- `ym-to-ymx` no longer overwrites an existing `.sndh` or `.prg` without
  `-f`. Nor do `st4` and `dst4`.
- A zero, negative or out-of-range flag value stops the run. It was
  accepted, then ignored, and a file was written.
- `play` and the SNDH command pass the trim options to the packer. They
  were read as a file name.
- Every command that packs writes the same report: the per-stream table
  where the pack was asked for, the summary alone otherwise.
- A check compares the three implementations over every command and flag:
  the same bytes, the same output, the same exit status.

## 0.8.1

The player is 3,434 bytes at unit size 2, where 0.8.0 carried 3,434, and
the PRG stub 3,038 where it carried 3,038. Format 0.7 still. Every one of
the thirteen binaries is byte-identical to 0.8.0's: this release changes
the tool that carries them, not them.

- The standalone `ym-to-ymx` now covers six platforms rather than three -
  Windows, macOS and Linux on both x64 and arm64 - and each download is
  around 900 KB where it was 30 MB.
- It is built from a third implementation of the tools, in Go, which makes
  both true: one machine cross-compiles to every target with no toolchain
  installed for any of them.
- That implementation writes the same bytes as the other two. Every file
  it produces is checked against the C# tree's, file by file.

## 0.8.0

The player is 3,434 bytes at unit size 2, where 0.7.2 carried 3,434, and
the PRG stub 3,038 where it carried 3,038. Format 0.7 still: a tune
packed for any earlier release plays here untouched.

- The release version is the binaries' own now, and the format version is
  a separate number. They were one until here, which is why this release
  is 0.8.0 and not 0.7.3, and why it reads the same 0.7 files 0.7.2 did.
- `ymx/setversion.sh` takes `-format` or `-release`, so the number that
  breaks every packed tune cannot be moved by reaching for the one that
  breaks nothing.
- The binaries are byte-identical to 0.7.2's. Nothing in the player, the
  stub or the packer changed; this release renames what was already
  there.

## 0.7.2

The player is 3,434 bytes at unit size 2, where 0.7.1 carried 3,412, and
the PRG stub 3,038 where it carried 2,992. Format 0.7 still: a tune
packed for 0.7.1 plays here untouched.

- A tick that arrives while a play call is still running is dropped
  rather than run inside it. A machine too slow for its tune loses
  frames instead of the player losing its place.
- A channel moved from one timer to another hands back the timer it was
  taken on, not the one it moved to.
- The player's assumptions name all four MFP timers. A host that saved
  only the two the old list named, then played a tune on Timer C, left
  the system's 200 Hz clock disabled.
- The stub no longer restores timer data registers it cannot read back,
  and hands the MFP over as it found it.
- The raster monitor's PCM tick estimate was a sixth high. The six
  -perf cores change bytes for it; the other six and the stub do not.
- The program picks the clock that suits the tune: the VBL where the
  machine refreshes at the tune's own rate, Timer C where it does not.
- A -perf build clears the screen, so the bars show across it rather
  than in the borders alone.
- The release carries a standalone ym-to-ymx for Windows, macOS and
  Linux, each zipped with the script that runs it under Hatari.

## 0.7.1

The player is 3,412 bytes at unit size 2, where 0.7.0 carried 3,412 -
the player is untouched, and this patch is the PRG stub's alone, which
grows from 2,878 bytes to 2,992.

The stub is now an SNDH host of the ordinary kind: it drives play from
Timer C at the system's own 200 Hz, accumulating the tune's rate against
200, where every stub before it called play once per VBL and played
every tune at 50 Hz, its header unread. A 200 Hz tune through
`mkprg.sh` played four times slow; it now plays 400 frames in two
seconds, measured under Hatari with one frame write per frame. Where a
set claims Timer C for an effect channel, the stub ticks from the VBL as
before, and the combiner rejects such a set at any rate but 50.

With the rate word the stub descriptor moves to version 2: a word at
offset 18, patched by the combiner from the SNDH file's own timer tag,
and flag bit 1 marking the VBL fallback. `doc/BINARIES.md` section 5
states the host's side in full - what the timer tags ask of a host, why
`TC` is the tag this recipe writes, and what the stub does about it.

The banner no longer carries a version of its own.

The release carries a standalone `ym-to-ymx` for Windows, macOS and
Linux, one zip per platform, each with the script that runs it under
Hatari. The executable turns a YM dump into a `.ymx`, an SNDH file or a
TOS program, carries this release's own cores and stub, and needs
neither a repository nor an SDK. `ymx/publish.sh` builds the zips and
`ymx/mkrelease.sh` attaches them.

## 0.7.0

The player is 3,412 bytes at unit size 2, where 0.6.0 carried 3,412.
Format version 0.7: a tune packed at 0.6 has to be repacked from its
`.ym` source. Nothing in the file's bytes moved but the version word:
the repack is the whole cost, and the reason for the bump is that the
document changed under the format.

- **The specification is the release.** Eight runs of the conformance
  exercise - six at reader level, two at player level - each handed the
  document to three implementers with no access to any implementation,
  and each fixed every place where the document made an implementer
  choose. The last run at each level produced every value byte for byte
  from the document alone, and the sentences all eight runs asked for
  are in this version's SPEC.md.
- **A second reference carries every timer tick.** `MANIFEST.txt`
  records what each call writes, which checks a reader;
  `MANIFEST-ticks.txt` records the 286,452 ticks the timers land across
  the same ten tunes, which checks a player. The tick model is measured
  against Hatari's MFP, register by register, and the two player-level
  runs were measured against it.
- **Every kit tune is packed from a recorded source.** `SOURCES.md`
  names each tune's `.ym` and options, a test repacks and compares, and
  this bump repacked the kit from those recipes alone.
- **The documents were read whole for a human reader**, three passes,
  under AGENTS.md's rules; every figure in prose is read back by a
  test.

The binaries are reassembled at the new version and behave as 0.6.0's
did; the stub is unchanged at 2,878 bytes.

## 0.6.0

The player is 3,412 bytes at unit size 2, where 0.5.2 carried 3,394.
Format version 0.6: a tune packed at 0.5 has to be repacked from its
`.ym` source.

- A file may carry **extension streams** at indices 25 to 31. `S`, the
  stream count at offset 14, is 25 to 32 where it was always 25.
- The long at offset 38 is `Q`, the **required-streams mask**, one bit per
  stream, and thirty-two is the stream ceiling at every version because of
  it. A set bit requires the stream: a consumer that does not implement it
  rejects the file. A clear bit makes it advisory: a consumer that does
  not implement it reads none of it and produces the values it would
  produce from a file without it. Bits 0 to 24 are set in every file, so a
  file carrying no extension stream holds `$01FFFFFF`.
- The header is 142 bytes where 0.5 carried 138. The section table follows
  at 42 where it followed at 38, and the first body item is at 144.
- SPEC.md §1.7 is the registry. This version assigns no index: 25 to 29
  are the format's to assign later, 30 and 31 are custom at every version.
  An extension that turns out to be shared is registered from 25 to 29 in
  a later version.
- The refill turn is a position in a consumer's decode list, not a stream
  index, so a file carrying an extension at index 31 does not force `C` to
  32 for every consumer of it.

A file carrying no extension stream is what 0.5 would have written apart
from the header: 10 of the 10 reference dumps in `doc/conformance` are
byte for byte what they were.

## 0.5.2

The player is 3,394 bytes at unit size 2, where 0.5.1 carried 3,394, and
every tune packed at format 0.5 plays unchanged. The three SNDH cores are
byte for byte what 0.5.0 published. The PRG stub gives the system's own
tick back.

- After a program ran, the desktop could no longer time a double click.
  The cause is Timer C's count. A timer's data register reads as the count
  it has reached, not the count it restarts from, so a takeover that reads
  $FFFFFA23 and writes it back at the end leaves the operating system's
  200 Hz tick running to the count the timer had reached. The desktop
  measures a double click, a key repeat and the time of day in that tick.
- 0.5.1 read the symptom as parked timer vectors and gave those back. That
  was a real omission, and not this fault.
- The count is written rather than read now: 192, which with the /64
  prescaler the control register restores is 2457600 / 64 / 192, or
  200 Hz. The prescaler needs no such care: a control register does read
  as what was written to it.
- The stub is 2,878 bytes where 0.5.1 carried 2,884.

Fixed on the machine, which 0.5.1 was not: its own note said no program
had been run with $114 read back afterwards, and the symptom outlived the
release. The same fault, and the same cause, was found in the RhYMe
tracker's exported player.

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

The player, optimized. Every tune packed at format 0.4 plays unchanged:
the format did not move.

- 3,256 bytes at unit size 2, where 0.4 carried 3,324.
- Cheaper by about 94 cycles a frame, measured to within about 24:
  `ymx/test/run.sh` under Hatari 2.6.1 on a cycle-exact 8 MHz ST with TOS
  2.06 played 1,700 frames of one tune in 89 ticks of the 200 Hz clock
  where 0.4 took 93, three runs each, with the same chip-write checksum.
- The frame write counts its register selects through one step register,
  the channel-to-timer map is read through a displacement the init
  patches, the sample tick steps its pointer through its own patched
  address, and init sets up its streams in registers.
- `YMX_SUPER_HOST` is gone: the sample tick no longer borrows an address
  register, so the flag chose nothing. A build line that defines it still
  assembles.

## 0.4

The first release of format 0.4, renumbered from 1 while the format is
short of the stability 1.0 states. The binaries' names carry the release
version, so a file states which release it came from.

- A sample's loop point resolves as an unsigned word: a loop point of
  `$8000` through `$FFFE` addressed 65,536 bytes below the sample.
- Every timer a claim takes is handed back as it was found.
