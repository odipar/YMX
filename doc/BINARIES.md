# The prebuilt binaries - combine contract

How a system without a 68000 assembler builds a playable SNDH file, and a
runnable TOS program, from binaries this repository assembles once.
`ymx/mkcores.sh` assembles them into `dist/`, and `org.ymx.MkSndh` and
`org.ymx.MkPrg` are the reference combiners. Any tool that follows this
document produces files of the same layout, played the same by any SNDH
host; what stays each combiner's own is the tag text - title, composer,
converter, subtune names - and the workspace size above §1's floor.
Big-endian throughout; every offset and size in bytes.

Two kinds of binary:

| file | contents |
|---|---|
| `ymxsndh-k1-v<release>.bin`, `-k2`, `-k4` | an **SNDH core**: the player and its SNDH glue, one per ST4 unit size |
| `ymxprg-v<release>.bin` | the **PRG stub**: a TOS program that drives an appended SNDH file |

Every name ends with the release version, the binaries' own, which moves
when they change. It is not the format version: a name says which
release a file came from, and the format that file reads is in the core's
descriptor (§1), which a combiner matches, and in MANIFEST.txt, which
names both. A `-perf` or `-nomask` in the name marks a core assembled
with the raster monitor in, or with the frame write unmasked; the flags
word below says which, so a combiner verifies rather than parses names.

Every variant is published at
[github.com/odipar/YMX/releases](https://github.com/odipar/YMX/releases)
under the tag `binaries-v<release>`, staged by `ymx/mkrelease.sh`:
twelve cores - three unit sizes by the four flag combinations - the
stub, one `ym-to-ymx` zip per platform, and a `MANIFEST.txt` of sizes
and SHA-256 digests with the source commit, the release version and the
format version. The zips hold a standalone `ym-to-ymx` and the script
that runs it under Hatari; the executable carries these same cores and
this same stub, so a machine with no SDK packs and combines without
following the recipe below. The release notes
are that release's section of [RELEASES.md](RELEASES.md), which says
what changed in it. A new format version is a new release; so is a patch
of the same format, which is published beside the patch before it:
`ymx/mkrelease.sh` removes no published release, so a superseded patch is
deleted by hand. An unchanged release updates in place.

## The stack

```
+----------------------------------------------+
| PRG header, 28 bytes                         |
| PRG stub, patched (§3)                       |
|  +--------------------------------------+    |
|  | entry triple and tags (§2)           |    |
|  | SNDH core (§1), the assembly         |    |
|  |   options in its flags               |    |
|  | subtune table                        |    |
|  | tune 1, .ymx                         |    |
|  | tune 2, .ymx                         |    |
|  | ...                                  |    |
|  | tune n, .ymx                         |    |
|  | workspace, zero bytes                |    |
|  +--------------------------------------+    |
| relocation table, one zero long              |
+----------------------------------------------+
```

The inner box is the SNDH file of §2 - any SNDH host plays it as it
stands; §4 adds the outer box. The assembler produced
only the two named binaries; every other byte is the combiners' or the
tunes' own.

---

## 1. The SNDH core

Position-independent. Fixed layout at its start:

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `bra.w` to init - the entry a combined file's outer header reaches |
| 4 | 4 | `bra.w` to exit |
| 8 | 4 | `bra.w` to play |
| 12 | 4 | `'YMXC'` |
| 16 | 2 | descriptor version - **1** |
| 18 | 2 | the ST4 unit size this core decodes: 1, 2 or 4 |
| 20 | 2 | flags: bit 0 = raster monitor built in, bit 1 = frame write unmasked |
| 22 | 2 | the format version the core reads - a combiner combines only tunes of the same version |
| 24 | 2 | `F`, the workspace bytes before the rings |
| 26 | 4 | table offset - written 0, patched by the combiner |
| 30 | 4 | workspace offset - written 0, patched by the combiner |

Both patched offsets are relative to the core's first byte and must be even.

The **subtune table** the table offset reaches: a word count `N`, then `N`
long offsets, each the position of one packed tune relative to the core's
first byte, each even. Init with subtune `s` (1-based, in `d0.w`) plays the
tune at entry `s`; out of range plays entry 1.

The **workspace** the workspace offset reaches: at least
`F + 25 · max(ring size)` zero bytes, where the maximum is over the combined
tunes' `N` header fields, and even-aligned. The core reads each tune's own
ring size, chunk and frame count from the tune's header at init, so tunes
with different rings and chunks combine into one file.

---

## 2. An SNDH file from a core

The result is a raw SNDH v2.2 file; [sndh.atari.org](http://sndh.atari.org/)
holds that format's own reference, and the recipe here is complete for
these files.

Three details depart from that reference, and each of them follows two
parsers that read v2.2 files instead - PSG Play (`frno7/psgplay`) and the
AtariAudio library in Arnaud Carré's SNDH Archive Player
(`arnaud-carre/sndh-player`). A file written from this recipe is read by
both:

* **The subtune-names tag reads `!#SN`.** The reference prints it `#!SN`
  in every place it appears. Both parsers match `!#SN` and neither matches
  `#!SN`, so a file spelled the reference's way loses its names in both.
* **Each subtune-name offset counts from that tag's first byte.** The
  reference's example writes the first offset from the first table word
  and the rest as deltas between successive names. PSG Play adds every
  word to the address of the `!#SN` bytes; the other parser steps over the
  table without reading it.
* **The `'##'` subtune count ends in a NUL.** The reference's tag table
  gives that tag no termination and its example writes the four bytes
  bare. PSG Play reads the two digits and then a NUL, warning when none
  follows; the other parser steps over one.

The reference fixes no tag order: its tag table is a list, and its own
example header writes an order this recipe does not. The order below fixes
one thing of its own - the `'##'` count comes before `FRMS` and before
`!#SN`, because a parser sizes both of those tables by the count it has
read.

In order, with every part after the tag block even-aligned and every pad
byte written 0:

1. **The entry triple**, 12 bytes: three `bra.w` instructions
   (`$60 $00`, then a word displacement). Each branches to the same entry
   of the core's own triple, so with the header `H` bytes long - the triple
   plus the tag block, padded even - all three displacements are `H - 2`.
2. **The tag block**: `'SNDH'`, then the tags, then `'HDNS'`, padded even.
   A combiner writes, in order: `TITL` (NUL-terminated text), `COMM` (when
   there is a composer), `CONV` (NUL-terminated text naming the
   converter), `'##'` plus two ASCII digits of the subtune count plus NUL,
   `TC` plus the frame rate in ASCII plus NUL, `FLAG~` plus one letter per
   MFP timer the subtunes claim - `a` to `d`, in that order - plus `y` for
   the YM2149 plus NUL, an even pad, `FRMS` with one long frame count per
   subtune (0 for a tune that starts over), `!#SN` with one word offset per
   subtune - each counted from the `!#SN` bytes - followed by the
   NUL-terminated names, an even pad, `HDNS`.
   A subtune claims one MFP timer per timer channel its header flags mark,
   on the timer its T stream gives that channel, and the tag names every
   timer any subtune claims: a tune using two channels on the packer's
   default map claims Timers A and D, and the same tune with those two
   channels moved to Timers B and C claims those instead. A tune using no
   timer channel claims none, and the tag reads `FLAG~y`.
3. **The core**, with its two offsets patched.
4. **The subtune table** (§1).
5. **The tunes**, each even-aligned.
6. **The workspace** (§1), last.

Rules the combiner keeps: every tune's unit size equals the core's - a tune
whose sections are all stored reads the same at any unit size and combines
with any core - one frame rate across the set for the `TC` tag, and at most
99 subtunes, the `'##'` tag's two digits.

---

## 3. The PRG stub

Position-independent, raw, even-sized. Fixed layout at its start:

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `bra.w` to the program |
| 4 | 4 | `'YMXP'` |
| 8 | 2 | descriptor version - **2** |
| 10 | 2 | subtunes - patched by the combiner |
| 12 | 4 | frames of subtune 1 when it plays once and stands alone - patched; 0 = play on |
| 16 | 2 | flags - patched; the bits are below |
| 18 | 2 | the tune rate in Hz - patched from the SNDH file's own timer tag; the assembler leaves 50 |

The flags word:

| bit | set by the combiner when | the stub then |
|---:|---|---|
| 0 | a scripted run asked for it | drops `YMXDONE.MRK` as it exits |
| 1 | the set claims Timer C, or the caller asked for the VBL | ticks play from the VBL rather than Timer C |
| 2 | the core is a `-perf` one | clears the screen before its banner |

Bit 1 carries two cases because the stub does one thing for both. A set
that claims Timer C leaves the stub no timer to tick from; a caller that
asks for the VBL wants the play call at a fixed place on the screen,
which Timer C cannot give, since it counts a crystal the video clock does
not track. The VBL is a 50 Hz clock either way, so a set at any other
rate stops the combine.

Bit 2 is the raster monitor's: it paints the background colour, and the
pixels the desktop left on the screen stand in front of it, so the bars
show in the borders alone until the screen is cleared.

The stub reaches the SNDH file at its own last byte: the file is appended
directly after it, at an even position - the reason the stub must be
even-sized.

## 4. A program from the stub

In order:

1. **The PRG header**, 28 bytes: `$601A`, text size (stub plus SNDH),
   then data, bss, symbol, reserved and flag longs, all 0, and a zero
   `absflag` word.
2. **The stub**, with its descriptor patched. The subtune count and frame
   count come out of the SNDH file's own `'##'` and `FRMS` tags; the frame
   count is 0 when the file holds more than one subtune.
3. **The SNDH file.**
4. **The relocation table**: one zero long - nothing in the stub or a
   position-independent SNDH file is relocated.

The program takes the machine over, drives play as §5 states, stops on
SPACE or ESCAPE or when a patched frame count runs out, and switches
subtunes on the number keys 1-9. It hands the machine back as it found
it: the VBL vector, the MFP's interrupt and timer registers, and all four
MFP timer vectors, which a player parks and restores none of.

## 5. Driving play - the host's side

An SNDH file is passive: init sets the tune up and installs the timers
its own effects claim, and something outside the file calls play at the
tune's rate. The header's timer tag - `TC` here, one of `TA` `TB` `TC`
`TD` `!V` in the wild - addresses that caller: it gives the rate, and
names the interrupt a desktop host should make the calls from. The
`FLAG~` letters list the timers the subtunes claim for effects, so a
host picks a tick source the tunes do not use.

The convention, and the reason `TC` is the tag this recipe writes: a
desktop host chains the operating system's own 200 Hz Timer C interrupt
- reprogramming nothing - counts the rate against 200, and calls play
when the count crosses. The system clock keeps running, and Timers A, B
and D stay free for the effects. The reference hosts read the tag the
same way: the rate is obeyed and the timer letter is advice, since a
host that owns its machine may make the calls from anything.

The stub owns the whole machine, so it does not chain: it programs
Timer C itself at the same 200 Hz (/64, count 192), accumulates the
patched rate against 200 - a 50 Hz tune plays every fourth tick, a
60 Hz one lands 60 calls in every 200 with no drift, a rate above 200
lands more than one call on a tick - and clears the in-service bit
before each call, so the file's own timers preempt the frame write
exactly as they would under a VBL host. Where the set claims Timer C
for an effect channel (flag bit 1, from the `FLAG~` letters), the stub
ticks from the VBL instead, and the combiner rejects such a set at any
rate but 50, since the VBL is a 50 Hz clock. The handback needs nothing
new: the four timer vectors, the control registers and Timer C's 192
were already restored.

## 6. From the release to a program, step by step

The whole build for a system with no assembler and no JVM.

1. **Fetch** the release `binaries-v<release>` - the newest patch of the
   tunes' format version - and check each file's SHA-256 against
   `MANIFEST.txt`.
2. **Pick the core** for the unit size the tunes are packed at - the
   fourth byte of any packed section's ST4 signature (`SPEC.md` §1.4) -
   and for the flags wanted. Verify its descriptor (§1): `'YMXC'`,
   descriptor version 1, the tunes' format version, the unit, the flags.
3. **Read each tune's header** (`SPEC.md` §1.1; flag bit 0 in §1.2):
   frame count, rate, ring size, and flag bit 0 for the `FRMS` entry. One
   rate across the set.
4. **Write the SNDH file** (§2): lay out the tag block before its entry
   triple - the block's length sets the three displacements - then write
   §2's order: the triple, the tag block, the core with its two offsets
   patched, the subtune table, the tunes, the workspace.
5. **Wrap it** (§4): the 28-byte PRG header, the stub with its subtune
   and frame fields patched from the SNDH file's own tags and its flags
   word set by the combiner (§3), the SNDH file, one zero long.

Step 4 alone gives a file any SNDH host plays; step 5 makes it a program.
The combiner writes the entry triple, the tag block, the subtune table
and the PRG header; patches two longs in the core and three fields in the
stub; copies the tunes; and zero-fills the workspace and the relocation
long. No instruction changes.

## 7. Use cases

Each case is §6 with one decision changed.

**One tune, its own sizes.** Read the tune's header, take the core its
sections' unit selects - `ymxsndh-k1-v<release>.bin` for a tune
packed at unit 1 - write the SNDH with one table entry and a workspace
of `F + 25 · N`, wrap it. The stub's frame count is the tune's frame
count when header flag bit 0 is clear, 0 when the tune starts over.

**A chosen workspace.** `N` is the packer's decision, written in the
tune's header - the maximum back-reference distance, not a combiner
option. The packer also raises `N` above the ring size it was asked for
where one pass of a tune needs a longer ring, so the ring size a set was
packed with is not the `N` its tunes carry: read every tune's header. A
combiner sets only the workspace size: anything at or above
`F + 25 · max(N)` (§1). `F + 25 · 2520` - the cap on `N` (`SPEC.md`
§1.3) - covers every legal tune, so a combiner may write that once and
skip the per-set maximum.

**Mixed tunes.** Tunes with different rings and chunks combine into one
file: the workspace takes the largest `N`, and the core reads each tune's
own header at init. The rules that stay: one unit size, one frame rate,
at most 99 subtunes.

**A tune with only stored sections.** Reads the same at any unit size and
combines with any core (§2).

**A measuring build.** The `-perf` cores carry the raster monitor, the
`-nomask` cores write the frame unmasked. Pick by name, verify by the
flags word (§1): the descriptor says what a core is, not the file name.

**Another format version.** A tune's header word at offset 4 gives its
format version; the release for it is tagged `binaries-v<that
version>.<patch>` - every tag carries a patch, `0` where the binaries
were never patched. One release stands per format version, the newest
patch, and the release for another format version stays published
beside it. A combiner takes the release whose format version its tunes
declare and verifies the core's word at offset 22 against them; the
word carries the format version alone, so every patch of one format
serves the same tunes.

**A program that reports its end.** One tune with header flag bit 0
clear, and stub flag bit 0 set (§3): the program plays the patched frame
count, drops `YMXDONE.MRK` on exit, and a harness watching for the file
closes the emulator by itself.

**A jukebox host.** Stop after §6 step 4: an SNDH host plays the file as
it stands. The stub and PRG header are only for running it as a TOS
program.

**A driver of its own.** Take the §2 file and call its triple directly:
init with the subtune number in `d0.w`, play once per frame at the `TC`
rate, exit to hand the machine back. Every entry preserves d0-a6.

**A changed set.** The file holds no checksums; the tags, the table and
the workspace all follow from the tune headers and positions, so adding,
dropping or replacing a tune is a rebuild from the same parts - §6 steps
4 and 5 again, not a patch.
